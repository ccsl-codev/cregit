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

By default only C files (`'\.[ch]$'`) are tokenized; pass `--mask` for other
languages (C, C++, Java, Rust and m4 are supported). The browsable per-file
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
| `--mask`              | regex of files to tokenize (C, C++, Java, Rust, m4); quote it | `'\.[ch]$'`                   |
| `--work`              | working/output directory                                   | `../cregit-files`                |
| `--mode` / `--shards` | tokenizer walk mode / shard count for `sharded`            | `pipeline` / `4`                 |
| `--jobs`              | concurrent blame/HTML processes                            | `CREGIT_JOBS` or up to `4` CPUs  |

A full run starts by **deleting the work directory** — to keep several target
repositories side by side, give each its own `--work`. To resume a failed run
without starting over, pass the step number printed in the step banners (with
the same target flags), e.g. `./run_pipeline_process.sh --repo-url … 5`.

Example run (cregit run on itself):
![Example cregit run](cregit.gif)
p.s.: long pauses are trimmed.

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
