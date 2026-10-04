# Cregit

![Cregit logo](./logos/cregit.png)

## About

This repository is the `cregit-codev` fork of the original `cregit`
project.

The original upstream repository is available at
https://github.com/cregit/cregit.

## Quickstart

Requires [Nix](https://nixos.org/download/) and
[devenv](https://devenv.sh/getting-started/); everything else (JDKs, sbt,
srcml, ctags, cargo, the Perl modules) is pinned by [`devenv.nix`](./devenv.nix).

```sh
git clone https://github.com/ccsl-codev/cregit.git
cd cregit
devenv shell               # enter the pinned toolchain
./run_pipeline_process.sh --repo-url https://github.com/OWNER/REPO.git
```

The script builds anything missing first, then runs the whole pipeline on the
repository you point it at — e.g. `--repo-url https://github.com/jqlang/jq.git`
makes a nice small C demo. No target repo in mind? Validate the install by
running cregit on itself — about two minutes end to end (plus the one-time
build on the first run):

```sh
./run_pipeline_process.sh --repo-url https://github.com/ccsl-codev/cregit.git --mask '\.(c|cpp|hpp|java|rs)$'
```

Tip: with [direnv](https://direnv.net/) installed, run `direnv allow` once in
the checkout — the repo ships an [`.envrc`](./.envrc), so the devenv shell then
activates automatically whenever you `cd` in, and typing `devenv shell` is no
longer needed.

By default every file the tokenizer can parse is tokenized — C, C++, Java and
Rust, case-insensitively. The default mask is derived from the tokenizer's own
extension table, so it cannot drift from it; print it with `perl
tokenize/fileMask.pl`. Pass `--mask` to narrow it to one language. `.am`/`.ac`
are routed to the m4 parser but are not in the default mask, because that parser
mis-lexes real autotools quoting (see `%MASKED_LANGUAGES` in
`tokenize/CregitLanguages.pm`). The browsable per-file
HTML views land in the sibling directory `../cregit-files/html`. See
[How to use](#how-to-use) for all flags and outputs.

## Preliminaries

- Code is written in Scala, C++, Rust and Perl.
- Platform: Linux x86_64 or macOS arm64 — the pinned `srcml` 1.1.0 parser is a
  prebuilt binary available only for those platforms.

## Prerequisites

|       |                                    |                            |
| ----- | ---------------------------------- | -------------------------- |
| srcml | https://www.srcml.org/             | Make sure srcml is in path |
| ctags | https://github.com/universal-ctags | Make sure ctags is in path |

The tokenization step is now provided by the [blobExec](./blobExec) sbt module
(consumes upstream `com.madgag:bfg-library` from Maven Central). It replaces the
previous `dmgerman/bfg-repo-cleaner@blobexec` fork. See [blobExec/README.md](./blobExec/README.md).

### Dependencies

For each module, its dependencies are documented in their corresponding README file.

As an example, on Debian 9 the following packages must be installed:

```
cmake libarchive-dev libxml++2.6-dev libxml2-dev libcurl4-openssl-dev libxslt1-dev libboost-all-dev libantlr-dev libssl-dev libxerces-c-dev exuberant-ctags libdbi-perl libjgit-java libhtml-fromtext-perl libset-scalar-perl libdbd-sqlite3-perl
```

## How to build

The pipeline script builds any missing artifact automatically before a run.
To build everything explicitly (inside `devenv shell`):

```sh
./run_pipeline_process.sh --build-only
```

This builds, in dependency order:

| artifact                                                      | module              | toolchain                 |
| ------------------------------------------------------------- | ------------------- | ------------------------- |
| `tokenize/srcMLtoken/srcml2token`                              | C++ transcoder      | gcc + xerces-c            |
| `tokenize/rustTokenizer` binary                                | Rust tokenizer      | cargo                     |
| `blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar`       | tokenization driver | sbt, JDK 21               |
| `{slickGitLog,persons,remapCommits}/target/scala-2.10/*-one-jar.jar` | history / persons / remap tools | sbt 0.13, JDK 8 |

To build a single module manually: `sbt assembly` in `blobExec`;
`sbt --java-home "$LEGACY_JAVA_HOME" one-jar` in `slickGitLog`, `persons` or
`remapCommits` (they are Scala 2.10 and do not build on a modern JDK); `make`
in `tokenize/srcMLtoken` and `tokenize/rustTokenizer`. Build `srcml2token`
before running the pipeline or the Perl test suite — the tokenizer shells out
to it.

## How to test

Run the test suites inside the pinned development environment (`devenv shell`).
The commands below mirror the required checks in GitHub Actions:

```sh
cd blobExec && sbt -batch test assembly

cd ../tokenize/srcMLtoken && make && make test
cd ../rustTokenizer && make && make test

cd ../..
prove tokenize/t tokenizeByBlobId/t blameRepo/t prettyPrint/t

# the pipeline runner's own guards (stubbed builds and a stubbed java, seconds)
bash test_ensure_artifacts.sh
bash test_retokenize_passthrough.sh
bash test_reblame_passthrough.sh

for module in slickGitLog persons remapCommits; do
  (cd "$module" && sbt --java-home "$LEGACY_JAVA_HOME" -batch test one-jar)
done
```

The Perl tests create temporary Git repositories and SQLite databases. The
`tokenizeSrcMl` tests require `srcml2token`, so build the C++ tokenizer before
running `prove`.

## How to use

`run_pipeline_process.sh` is the driver for the whole pipeline: it clones the
target repository, tokenizes it (rewriting each matched blob to its
token-level representation), builds the history and persons databases, blames
every tokenized file, generates the HTML views and writes a unified Parquet
dataset (see [generate_dataset/DATASET.md](./generate_dataset/DATASET.md)).

```sh
# smoke test — cregit on itself (validates the install, ~2 min):
./run_pipeline_process.sh --repo-url https://github.com/ccsl-codev/cregit.git --mask '\.(c|cpp|hpp|java|rs)$'

# a small C project (demo-sized):
./run_pipeline_process.sh --repo-url https://github.com/jqlang/jq.git

# a Java project, with its own output directory:
./run_pipeline_process.sh \
  --repo-url https://github.com/OWNER/REPO.git \
  --mask '\.java$' \
  --work ../cregit-files-REPO
```

Blame and HTML generation process independent files concurrently. By default,
the pipeline uses up to four workers (or fewer when fewer CPUs are available).
Use `--jobs N` or `CREGIT_JOBS=N` to choose another limit.

Flags (see `./run_pipeline_process.sh --help` for the full list):

| flag                  | meaning                                                    | default                          |
| --------------------- | ---------------------------------------------------------- | -------------------------------- |
| `--repo-url`          | git URL or local path of the repository to process         | **required**                     |
| `--repo-name`         | short name prefixed to the output files                    | derived from `--repo-url`        |
| `--commit-url`        | base URL for commit links in the generated HTML            | `<repo-url minus .git>/commit/`  |
| `--mask`              | regex of files to tokenize (C, C++, Java, Rust); quote it   | `perl tokenize/fileMask.pl`      |
| `--work`              | working/output directory                                   | `../cregit-files`                |
| `--memo-dir`          | where to memoize tokenized blobs; outside `--work` it survives the full-run wipe | `<work>/memo` |
| `--mode` / `--shards` | tokenizer walk mode / shard count for `sharded`            | `pipeline` / `4`                 |
| `--jobs`              | concurrent blame/HTML processes                            | `CREGIT_JOBS` or up to `4` CPUs  |
| `--reblame`           | re-blame every file in step 7, replacing existing `.blame` output | off — a resume skips files already blamed |
| `--strict-tokenize`   | stop step 2 on the first tokenizer failure (exit 4 or 6), as earlier versions did | off — a failed blob is skipped and recorded; also `CREGIT_STRICT_TOKENIZE=1` |
| `--max-retries`       | retries of a timed-out blob in the same run; 0 = no retry  | `0`; also `CREGIT_MAX_RETRIES` |
| `--timeout-retry-factor` | budget of each retry, as a multiple of `--blob-timeout`; 0 or 1 = the same budget | `3`; also `CREGIT_TIMEOUT_RETRY_FACTOR` |
| `--retry-skipped`     | try again the earlier timeouts even when blame output exists (a recovered blob means a full re-blame) | off; also `CREGIT_RETRY_SKIPPED=1` |

**`git blame` runs with `-C100` copy detection** (`blameRepo/formatBlame.pl:73`), so a
token moved between files keeps its original author. Upstream shipped that commented
out. Enabling it changed the author of up to 25% of a project's tokens, so blame output
produced before 2026-09-22 is not comparable with output produced after it.

**Pass `--reblame` whenever the blame itself changed**, not the file list.
`blameRepoFiles.pl` skips any file whose `.blame` output already exists. That skip makes
a resume cheap, and it makes a re-blame a silent no-op: step 7 reports every file as
already done and exits 0, then step 10 rebuilds from the old blame. One pilot changed
**0 of 15,036,195 tokens** that way, with identical row counts and `rc=0`. Read step 7's
summary to confirm a real re-blame — `Already done [0]` is the proof. Do **not** pass the
flag to resume an interrupted run.

A full run starts by **deleting the work directory** — to keep several target
repositories side by side, give each its own `--work`. To resume a failed run
without starting over, pass the step number printed in the step banners (with
the same target flags), e.g. `./run_pipeline_process.sh --repo-url … 5`.

That wipe also takes the memo, which is the tokenizing already done: a memo hit
returns without invoking `srcml` at all. Put it out of reach with `--memo-dir`,
**one directory per repository** (the memo key is a hash of the file contents and
names neither repository nor extension). The runner refuses a step-1 wipe that
would delete a memo of 10,000 entries or more; `--force-clean` overrides it.

209 blobs in 21 corpus projects are on a **blob denylist**
(`blobExec/src/main/resources/cregit/blobexec/blob-denylist.tsv`), in **three
distinct srcML 1.1.0 defects** that the file's header keeps apart:

* **8 blobs, Java, srcML does not terminate** — upstream srcML/srcML#2361, open.
* **4 blobs, C, srcML does not terminate** — a C++ header that the extension table
  hands to srcML's **C** parser. A different defect with **no upstream issue**, so
  it cites the analysis document instead.
* **197 blobs, C and C++, srcML CRASHES** — 153 on SIGSEGV and 44 on SIGABRT, in
  milliseconds under the `--position` flag the token format requires. Not a hang,
  no upstream issue, cited to the sweep that found it. This is the whole history
  of 36 files in 18 named projects, and 19 are affected: one `deflate.c` blob is
  vendored byte-identically into two projects, so one entry covers both. The other
  707 historical versions of those same paths parse cleanly, so the trigger is
  content, not the path.

Denylisted blobs are never handed to the tokenizer, are dropped from the rewritten
trees rather than kept as raw source, are counted as `blobsDenylisted` and named
with a reason and a citation, and they do **not** change the exit status. Adding an
entry means editing that file and rebuilding the jar.

### When the tokenizer fails on a blob: skipped, recorded, and the run continues

By default, a blob whose tokenizer fails does **not** stop the run. There are three
failures:

| reason         | what happened                                                    | retried by a later run? |
| -------------- | ---------------------------------------------------------------- | ----------------------- |
| `parser-crash` | the tokenizer exited 33: srcML died on a signal, or gave no tokens | no: deterministic for this tokenizer |
| `empty-output` | the tokenizer exited 0 with no output for a non-empty input       | no: deterministic for this tokenizer |
| `timeout`      | the tokenizer ran longer than `--blob-timeout` and was killed      | **yes**: a timeout depends on load |

blobExec drops such a blob exactly as it drops a denylisted blob. The file is not in
the tokenized repository, not as raw source and not as an empty tokenization, so it
has no blame and no dataset row. A `SKIPPED blob` line in `pipeline.log` names the
sha, the path, the reason and the detail. Step 2 then prints a warning, for example
`WARNING: 3 blobs skipped, see <work>/tokenize-skipped.tsv (parser-crash=1, timeout=1, denylisted=1).`,
and the run continues. The exit status of blobExec is 0.

**`<work>/tokenize-skipped.tsv` lists every blob the dataset does not contain**: the
skipped blobs, the denylisted blobs and the oversized blobs. It is tab-separated, with
a header. This row comes from `SkipFailedBlobSpec`:

```
sha	path	reason	detail	tokenizer
65b2df87f7df3aeedef04be96703e55ac19c2cfb	deep/b.c	denylisted	reason=srcML 1.1.0 does not terminate on it; citation=srcML/srcML#2361	c=0123456789abcdef
```

`reason` is one of `denylisted`, `oversized`, `parser-crash`, `empty-output` or
`timeout`. `detail` is the denylist reason and citation, the size, the signal
(`exit=33 signal=11`), the byte counts, or the budget (`timeout=600s`). `tokenizer`
is `<ext>=<identity>` from `tokenize/tokenizerIdentity.pl`. The file is in the work
directory, next to `<name>-dataset.parquet`, because that directory is the project's
output folder: `ctp.py` reads the dataset from there, and `retain.py` removes only
`memo/` and `html/`. A resumed step 2 appends to the file and does not write a second
row for the same sha, path and reason. A row is removed when its blob tokenizes on a
later run. A step-1 run deletes the work directory, and so the file, and writes it
again. When a tree that holds a dropped blob occurs again under a different path, the
walk reuses the rewritten tree, and the file names only the first path. The sha is
always in the file.

**A timeout can get retries in the same run.** By default it does not:
`--max-retries` is 0, so a timeout skips and records the blob at once. A timeout is
often the load of the machine, not the blob. To retry, set `--max-retries 3` (or
`CREGIT_MAX_RETRIES=3`). Then the stall watchdog window grows from 30 min to about
2 h 20 min (see the worst case below). blobExec retries a timed-out blob up to
`--max-retries` times. Before each retry it waits while the 1-minute load
average (`/proc/loadavg`) is above the limit (`--load-limit`, default 2 x the number of
processors; `CREGIT_LOAD_LIMIT` in the runner), for 10 minutes at most
(`--load-wait-max`). Each retry has `--timeout-retry-factor` times the budget (default
3, so 1800 s for the default 600 s). The first retry that tokenizes ends the sequence.
The blob is dropped only if all 1 + N attempts time out. The record then lists the
attempts: `timeout=600s retries=3x1800s`. A parser crash and empty output get no retry,
because they are deterministic.

Why these defaults. The machine this runs on sits at a 1-minute load of 21 to 27 on 16
processors. A limit equal to the processor count would make every wait last until its
cap, so the limit is twice the processor count. The 1-minute load average falls by a
factor of e each minute after a burst ends, so a burst is gone from it within about
five minutes; a wait longer than 10 minutes helps only under a load that does not end,
and with `--max-retries 3` each wait can occur three times per blob.

**Worst case for one blob, with `--max-retries 3` and the other defaults:** 600 s,
then 3 x (600 s wait + 1800 s retry) = 7800 s, about 2 h 10 min, plus 5 s kill grace for each of the 4 attempts
(7820 s). No progress is stamped during that sequence, so the stall window must be
larger than it. A defaulted `--stall-timeout` is raised to that time plus one
`--blob-timeout` (8420 s, about 2 h 20 min); an explicit value that is too small is
refused, and the message names `--max-retries`, `--timeout-retry-factor` and
`--load-wait-max`. The cost: a real stall is then seen after 2 h 20 min, not after
30 min. This is why the default is 0 retries: the window stays at 3 x
`--blob-timeout` (30 min).

**A timeout that stays is not permanent either.** blobExec records it in the
`retry_blob` table of the blob map, and a later step-2 run can try it first. If it
then tokenizes, blobExec empties `commit_map`, `tree_map` and `ref_map` and folds all
of history again, with the blob in it. The blob rows stay, so no other blob is
tokenized again. But every rewritten commit then has a new sha, so the runner removes
the outputs made from the old commits (as it does for `--retokenize`), `blame/`
included, and step 7 blames the whole project again. For the largest project that is
about 130 hours, for one file that timed out once. So the runner applies two rules:

1. **While there is no blame output**, step 2 tries the earlier timeouts by itself.
   "Blame output" means any `.blame` file in `<work>/blame` or in
   `<work>/blame-c100-incoming` (a re-blame made on another machine).
2. **When blame output exists**, step 2 passes `--no-retry-timed-out` to blobExec.
   The blobs stay dropped, their `retry_blob` rows and skip-file rows stay, and the
   blame is not touched. Step 2 prints, for example,
   `WARNING: 1 timed-out blobs stay dropped (see <work>/tokenize-skipped.tsv); --retry-skipped retries them and re-blames the project (41213 .blame files would be deleted).`
   Pass `--retry-skipped` (or `CREGIT_RETRY_SKIPPED=1`) to try them anyway, and accept
   the re-blame. A re-fold deletes `<work>/blame` and `<work>/blame-c100-incoming`,
   because both name the commits of before the re-fold; the count covers both.

If a blob times out again on such a retry, it stays dropped and nothing is folded
again.

**A crash is recorded only through the blob map, never in the memo.**
`tokenizeByBlobId/tokenBySha.pl` writes a memo entry only when the tokenizer succeeds,
so a crash or a timeout is never cached there. A crash is "remembered" only because
the trees and commits that omit the blob are in the blob map, and the blob map is
keyed on the tokenizer identity: a changed tokenizer is refused (exit 3), and
`--retokenize` empties `tree_map`, so the walk gets to the blob again and tries the new
tokenizer on it.

**To require a clean run, pass `--strict-tokenize`** (or `CREGIT_STRICT_TOKENIZE=1`).
Then the first failure stops step 2, as in earlier versions: blobExec records nothing
for the containing commit and exits 4 (timeout) or 6 (parser crash), and the runner
writes `TOKENIZE-TIMEOUTS` or `TOKENIZE-PARSER-CRASHES` and stops. A step-2 resume
retries exactly those blobs. A timeout held because blame output exists also counts,
so a strict step 2 exits 4 until `--retry-skipped`. `--mode sharded` is always strict,
because the shard merge does not carry the retry records over.

| blobExec exit status | before this change | now, default | now, `--strict-tokenize` |
| -------------------- | ------------------ | ------------ | ------------------------ |
| a blob timed out (on all attempts) | 4, step 2 stops | 0, blob skipped and recorded; a later run retries it while no blame exists, or with `--retry-skipped` | 4, step 2 stops |
| a blob timed out, then tokenized on a retry | 4, step 2 stops | 0, not skipped | 0, not skipped |
| a parser crash or empty output | 6, step 2 stops | 0, blob skipped and recorded | 6, step 2 stops |
| a denylisted or oversized blob | 0 | 0, and recorded | 0, and recorded |
| an abort (`--abort-on-error`) | 2 | 2 | 2 |
| a stall (watchdog) | 5 | 5 | 5 |

Example run (cregit run on itself):
![Example cregit run](cregit.gif)
p.s.: long pauses are trimmed.

### When a tokenizer is corrected: `--retokenize`

A corrected tokenizer does **not** by itself produce corrected tokens on a
resume. blobExec decides whether to reuse a cached tokenization from the
recorded `command` and `mask`. `command` is the constant path
`tokenizeByBlobId/tokenBySha.pl`, and `mask` says *which* files to tokenize,
never *how* — so rebuilding a tokenizer moves neither value, every cached row for
that language stays a cache hit, and the run reproduces the old tokenizer's
output and exits 0. Measured, for the Rust fix in `729643e`: 741,869 `.rs`
(blob, path) pairs across 45 projects.

Two mechanisms close that:

1. **Every run reports a tokenizer identity** — one opaque digest per file
   extension, covering that extension's whole parser toolchain
   (`tokenize/tokenizerIdentity.pl`). blobExec records it in the blob map's
   `meta` table and compares it on every later run. A mismatch, on an extension
   that actually has cached rows, **refuses the run** (exit 3) and names the flag
   to fix it. Nothing is invalidated automatically.
2. **`--retokenize EXTS` is the opt-in past that refusal**, and it is selective:
   re-tokenizing is 88% of total pipeline time, so a `.rs`-only defect costs
   `.rs` entries only.

To re-tokenize only the `.rs` entries of one project, after rebuilding the
tokenizer:

```sh
# 1. make sure the binary is actually current (this also proves the identity moved)
./run_pipeline_process.sh --ensure-artifacts

# 2. resume that project at step 2 — never step 1, which deletes the work
./run_pipeline_process.sh \
  --repo-url <url> --repo-name <name> \
  --work ../cregit-files-<name> \
  --retokenize rs \
  2
```

`--retokenize` needs `FROM_STEP=2` exactly: step 1 deletes the work directory
(so there would be nothing cached to invalidate) and step 3 or later skips the
invalidation entirely. It is not available with `--mode sharded`.

What it does, in one transaction: drops the `blob_map` rows for those extensions,
purges **their entries in the memo** (which is keyed on `sha1` of the file's
content with no tokenizer in the key, so dropping only the `blob_map` row would
let the memo answer with the same stale tokens), and drops `tree_map`,
`commit_map` and `ref_map` because a tree names its blobs. Every other
extension's tokenizations survive.

It cannot quietly do nothing. blobExec refuses, **before changing anything**, if
no cached row carries a named extension, if the memo held none of the affected
blobs (which means `--memo-dir` is not this project's), if another extension's
tokenizer also changed and was not named, or if a retained `new_blob` id is
missing from the cregit repository. An ineffective `--retokenize` exits 7, never
0.

### Outputs

Everything lands in the work directory (default: `../cregit-files`, a sibling
of the checkout):

| path                                    | content                                          |
| --------------------------------------- | ------------------------------------------------ |
| `html/`                                 | per-file HTML views of token-level contributions |
| `<name>-dataset.parquet`                | unified token/commit/author dataset ([schema](./generate_dataset/DATASET.md)) |
| `<name>-cregit.git`, `<name>-cregit/`   | the tokenized ("view") repository                |
| `<name>-original.git`, `<name>-original/` | bare + working clones of the target repository |
| `<name>-*.db`                           | SQLite databases: history (original and cregit), blob map, persons |
| `blame/`                                | per-file token blame                             |
| `pipeline.log`                          | full log of the run                              |

### Environment variables

The pipeline script sets these itself; you only need them when invoking the
tools manually (the numbered steps inside `run_pipeline_process.sh` are the
reference for manual invocations):

| variable           | meaning                                                                                     |
| ------------------ | ------------------------------------------------------------------------------------------- |
| `BFG_MEMO_DIR`     | directory used to memoize tokenized blobs                                                    |
| `BFG_TOKENIZE_CMD` | tokenize command; the script routes it through `tokenize/tokenize.pl`, which dispatches by file extension |
| `CREGIT_JOBS`      | concurrent blame/HTML processes when `--jobs` is not provided (otherwise up to four CPUs)   |
| `LEGACY_JAVA_HOME` | JDK 8 home for the Scala 2.10 modules (provided by `devenv shell`)                           |

## Contributing

Contributions are welcome! Please read our [contributing guide](CONTRIBUTING.md) before opening an issue or pull request.

For larger changes, please open an issue first to discuss the proposal.

## License

The license of Cregit is [GPL-3.0+](LICENSE.md).

## TODO

- use preferred name in html files
- customize programs to read a JSON file with configuration?
