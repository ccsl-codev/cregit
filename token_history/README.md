# token_history — every token that ever existed

The tip dataset (`generate_dataset`) holds only the tokens alive at HEAD. This step replays the tokenized history (`<name>-cregit.git`) and writes one row for every token that ever existed: its birth commit, the first commit that removed it, and its intervals on the mainline (the first-parent history of HEAD).

**Scope: `.c` and `.h` paths only** (`MASK` in `token_history.py`). The tip dataset also has other file types; on Linux, 503 files (`.rs`, `.cpp`, `.cc`, 1.05 M rows) are not in the history.

## Two stages

| Stage | Script | Cost (Linux, Sep 2026) | Output |
| --- | --- | --- | --- |
| 1. Replay | `token_history.py` | 50.4 h with 8 jobs; 122,910 paths, 407.9 M token rows | `part-*.tsv` (tokens), `runs-*.tsv` (move candidates) |
| 2. Build | `build.py --mode <mode>` | load 12 min; link pass 2.3 h (`moves`) or 1.9 h (`renames`); chain resolution 5–10 min; write 5 min | `history-<mode>.parquet`, `links-<mode>.tsv` |
| 3. Attribute | `attribute.py` | not timed | `commits.parquet`: person and firm of every cregit commit |

On the io_uring pilot (96 paths) stage 1 takes 2–3 min and stage 2 under 10 s.

Stage 2 reads only the files of stage 1. Keep them: every mode, and any later change to the move rules, rebuilds from them without a new replay. A change to the replay rules (`replay.py`) needs a new stage 1.

At kernel scale, stage 2 works in parts so that it stays inside its memory limit (`--memory`, default 6 GB):

- `--reuse` keeps the loaded tables in `OUT/build.duckdb` and a finished `links-<mode>.tsv`, so a build that stopped after the link pass restarts at the chain resolution. The link pass writes `links-<mode>.tsv.part` and renames it only at the end.
- `--chunks N` (default 16) looks up the origins and writes the parquet in N chunks of paths, with no global sort. The row order of the parquet is not defined.
- `links-<mode>.parquet` is not written here: the analysis (`msr-code/analysis/rq_history.py`) converts the TSV once for its own use.

## Move modes (stage 2)

| Mode | Links | Matches |
| --- | --- | --- |
| `off` | none | the history of each path alone |
| `renames` | a deleted file to a file created in the same commit | close to plain `git blame` with rename detection |
| `moves` (default) | any exact run of ≥ `--min-alnum` alphanumeric characters that one commit removes and adds | keeps the first identity of moved code |

`moves` is not a copy of `git blame -C100`, and it does **not** follow more moves:

- `moves` links only an exact run that the **same commit** removes and adds. Each removed token is used once. So it does not follow a **copy** whose source stays in place, which `git blame -C` does follow.
- `git blame -C100` diffs a block against the whole old file, so it drops a move that its diff breaks into short pieces. `moves` keeps some of those.
- Measured on Linux (a 1/32 sample of the tip, 5.49 M tokens, 30 Sep 2026): where the two disagree on the birth time, `-C100` gives the **older** commit in about 87% of the cases (981,149 tokens against 145,019).

On the whole Linux tip, the origin commit agrees with the `-C100` tip dataset on 80.46% of the tokens for `moves` and 81.13% for `renames` (`history/validate-<mode>.json`). A rule that copies `git blame -C` needs only the repository and the stage 1 files, so it can be added to `moves.py` later.

## Run

```sh
# once per repository: changed-path Bloom filters (a path-limited log goes from ~21 s to ~4 s on Linux)
git -C X-cregit.git commit-graph write --reachable --changed-paths

# stage 1 (resumable: a new run skips the paths in paths-done.txt)
python3 token_history.py --repo X-cregit.git --out OUT --jobs 8 --ping 60 [--paths FILE | -- PATHSPEC]

# progress at any time
./history_status.sh OUT

# stage 2, once per mode, any time later
python3 build.py --out OUT --mode moves
python3 build.py --out OUT --mode renames

# check the tip against the tip dataset
python3 validate.py --history OUT/history-moves.parquet --dataset X-dataset.parquet

# person and firm of every cregit commit, with the joins of the tip dataset
python3 attribute.py --cregit-db X-cregit.db --persons-db X-persons.db \
    --firm-map affiliation.merged.csv --firm-canonical firm_canonical.csv \
    --out OUT/commits.parquet --dataset X-dataset.parquet
```

`validate.py` compares only the dataset rows of the files that the history has (so only `.c` and `.h`). Its `same_text` counts tokens whose text matches the dataset's token type, its token value, or both joined by `|`.

Stage 1 writes `history-progress.json` and a log line every `--ping` seconds. A path that fails goes to `errors.txt` with its traceback, and the run continues. A `git` command that fails for the whole repository (the merge list, the mainline log or the path list) stops the run with `GitError`, so a run cannot end with no data and 0 errors.

## Columns of `history-<mode>.parquet`

| Column | Meaning |
| --- | --- |
| `file_path`, `token_id` | the token; `token_id` counts per path |
| `token` | the cregit line, `type\|value` |
| `born_sha` | the cregit commit that added the line |
| `born_in_merge` | 1 when no parent of a merge had the line |
| `copy_of` | the token this line duplicates, when a merge kept one token twice |
| `died_sha` | the first non-merge commit that removed it |
| `tip_index` | its line in the file at HEAD, when alive there (joins `token_index` of the tip dataset) |
| `mainline_in_sha`, `mainline_in_ts` | the first-parent commit (and committer time) where it entered the mainline |
| `mainline_out_sha`, `mainline_out_ts` | where it left the mainline; empty while alive |
| `origin_path`, `origin_token_id`, `origin_born_sha` | its first identity after the move links of the mode |
| `move_mode` | the mode of the build |

A token that left the mainline and came back has one row per interval. A token alive at any time T is a row with `mainline_in_ts <= T` and `T < mainline_out_ts` (or no `mainline_out_ts`).

## Tests

```sh
pytest token_history -q
```

The tests build small git repositories. Each of the seven replay rules (simplify-merges, real parent order, a merge equal to one parent takes it whole, a commit with no diff keeps the parent blob, a line kept twice becomes a copy, a parent without the file is no source, an error in one path is a record) has a test that fails when the rule is removed.
