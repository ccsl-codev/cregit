# token_history — every token that ever existed

The tip dataset (`generate_dataset`) holds only the tokens alive at HEAD. This step replays the tokenized history (`<name>-cregit.git`) and writes one row for every token that ever existed: its birth commit, the first commit that removed it, and its intervals on the mainline (the first-parent history of HEAD).

## Two stages

| Stage | Script | Cost | Output |
| --- | --- | --- | --- |
| 1. Replay | `token_history.py` | hours; Linux about 1.5–2 days with 8 jobs | `part-*.tsv` (tokens), `runs-*.tsv` (move candidates) |
| 2. Build | `build.py --mode <mode>` | seconds to minutes | `history-<mode>.parquet` |

Stage 2 reads only the files of stage 1. Keep them: every mode, and any later change to the move rules, rebuilds from them without a new replay.

## Move modes (stage 2)

| Mode | Links | Matches |
| --- | --- | --- |
| `off` | none | the history of each path alone |
| `renames` | a deleted file to a file created in the same commit | plain `git blame` (99.90% of tokens on the io_uring pilot) |
| `moves` (default) | any exact run of ≥ `--min-alnum` alphanumeric characters that one commit removes and adds | keeps the first identity of moved code; follows more moves than `git blame -C100` |

`moves` is not a copy of `git blame -C100`: git diffs a block against the whole old file and drops a move that its diff breaks into short pieces. On the io_uring pilot, `moves` agrees with the `-C100` tip dataset on 49% of the tip tokens, `off` on 62%. A rule that copies git needs only the repository and the stage 1 files, so it can be added to `moves.py` later.

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
```

Stage 1 writes `history-progress.json` and a log line every `--ping` seconds. A path that fails goes to `errors.txt` with its traceback, and the run continues.

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
