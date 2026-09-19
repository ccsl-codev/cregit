#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: run_pipeline_process.sh --repo-url URL [options] [FROM_STEP]

Runs the full cregit pipeline (clone -> tokenize -> blame -> HTML views ->
Parquet dataset) on the git repository given by --repo-url. Missing build
artifacts (jars, tokenizers) are built automatically first — run inside
`devenv shell` so the pinned toolchain is available.

Build:
  --build-only      build all pipeline artifacts and exit, running nothing

Target repository:
  --repo-url URL    git URL (or local path) of the repository to process (REQUIRED)
  --repo-name NAME  short name used to prefix the output files
                    (default: derived from --repo-url)
  --commit-url URL  base URL for the commit links in the generated HTML
                    (default: derived from --repo-url as <url minus .git>/commit/,
                    which is correct for GitHub/GitLab-style hosts)
  --mask REGEX      regex selecting the files to tokenize; quote it.
                    Default: the universal mask, every extension the tokenizer
                    can parse (C, C++, Java, Rust), case-insensitively. It is
                    derived from tokenize/CregitLanguages.pm rather than written
                    out here; print it with `perl tokenize/fileMask.pl`.
                    Note .am/.ac are routed to the m4 parser but are NOT in the
                    default mask — m4Tokenizer/m4.py mis-lexes real autotools
                    quoting; see %MASKED_LANGUAGES in CregitLanguages.pm.
                    Changing the mask forces a full rebuild: blobExec records the
                    mask in its blob map and refuses to resume against a
                    different one, because the old tree_map entries would omit
                    the newly selected files.
  --work DIR        working/output directory (default: ../cregit-files).
                    NOTE: a full run (FROM_STEP=1) starts by deleting this
                    directory; use one directory per target repository.
                    Pass FROM_STEP=2 (the trailing positional argument) to
                    resume instead, keeping the memo, the bare repos and the
                    blob map — that is how a tokenize timeout or stall is
                    recovered without redoing the work.
  --blob-timeout N  wall-clock budget in seconds for one blob's tokenizer
                    (blobExec default: 600). A child that exceeds it is killed,
                    that blob is left untokenized, step 2 stops with exit 4 and
                    a step-2 resume retries exactly those blobs. Also settable
                    as CREGIT_BLOB_TIMEOUT in the environment, which is how to
                    reach it through ctp.py.
  --stall-timeout N watchdog window in seconds (blobExec default: 1800). If no
                    blob, tree, commit or blob copy completes anywhere in this
                    window the run is killed with exit 5. Must be larger than
                    --blob-timeout; blobExec refuses an explicit value that is
                    not, and raises a defaulted one. Also CREGIT_STALL_TIMEOUT.
  --force-clean     allow the FROM_STEP=1 wipe even when $WORK holds a
                    TOKENIZE-TIMEOUTS or TOKENIZE-STALLED marker. Without this
                    the runner refuses, because those markers mean the work is
                    incomplete but resumable at step 2, and deleting it means
                    re-tokenizing everything to retry a few blobs.

Output:
  --skip-html       do not generate the HTML views (step 9). The views cost
                    94-255 MB per project and step 10 does not read them, so a
                    corpus run that only wants the Parquet dataset should skip
                    them. NOTE: the HTML views are the fallback output when
                    python3+duckdb is missing, so --skip-html without duckdb
                    leaves the run with no final artifact.
  --gc MODE         how to pack the generated cregit repo after tokenizing
                    (default: plain)
                      none        do not pack at all. Fastest, but every later
                                  step then reads loose objects.
                      plain       git gc --prune=now
                      aggressive  git gc --prune=now --aggressive. Measured on
                                  Linux (22 M loose objects) this failed with
                                  "failed to run repack" after hours of work.
                    A failed pack never aborts the run: the Parquet dataset is
                    the product, and packing only makes later steps faster.
  --memory-limit SIZE
                    forward --memory-limit to step 10 (the DuckDB generator).
                    Omit to accept that script's own default of 8GB. Takes an
                    absolute size such as 3GB, never a percentage.
                    Measured: the limit bounds DuckDB's buffers, not the
                    process, which settles at about 1.4x the limit. A corpus run
                    with N concurrent projects must therefore budget
                    1.4 x N x SIZE of RAM. Two projects at 8GB need 22 GB and
                    will exhaust a 30 GB box that already runs other software.
  --duckdb-threads N
                    forward --duckdb-threads to step 10. Each sorting thread
                    holds its own buffers, so fewer threads lower the peak.
                    Omit to accept the generator's default.
  --project-meta PATH
                    forward --project-meta to step 10: a JSON sidecar of
                    per-project provenance written by cregit-token-pipeline's
                    project_meta.py. Omit and the generator emits those columns
                    as empty strings, so the schema is unchanged either way.
  --project-key NAME
                    forward --project-key to step 10: which key of the sidecar
                    this project is (the corpus manifest name). Omit to let the
                    generator use --repo-name. The generator fails loudly on a
                    key the sidecar does not hold.

Tokenizer:
  --mode MODE   tokenizer walk mode (default: pipeline)
                  serial          single-threaded reference walker
                  pipeline        look-ahead parallel walker (fastest for normal repos)
                  pipeline-trees  parallel walker + parallel tree assembly
                  sharded         N memory-bounded shards -> merge + serial re-fold,
                                  for repos too large to tokenize in one process;
                                  delegates to blobExec/shard_build.sh
  --shards N    shard count for --mode sharded (default: 4)
  --jobs N      concurrent blame/HTML processes (default: CREGIT_JOBS,
                otherwise min(4, available CPUs)). Blame is the pipeline's
                bottleneck: measured on Linux the serial step managed 8 files
                per minute against 64,536 files, which is 5.6 days. Each
                file is independent, so the output does not depend on N. The
                step is resumable, so N can change between runs.
  FROM_STEP     resume from this step number (default: 1). A full run (step 1)
                starts clean; resuming keeps existing work.

example — run cregit on your own repo, tokenizing Java files:
  ./run_pipeline_process.sh --repo-url https://github.com/OWNER/REPO.git --mask '\.java$'
EOF
}

die() {
    log "ERROR: $1"
    exit 1
}

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

# pack_cregit_repo: pack the generated repo, honouring --gc, and never abort.
#
# The repack is an optimisation, not a product. The keeper is the Parquet
# dataset. So a repack failure must not end the run.
#
# Measured on Linux, 2026-09-14: `gc --prune=now --aggressive` on 22,258,632
# loose objects printed "cannot be read" for four objects and then "failed to
# run repack", exit 128. All four objects read fine with `git cat-file -t`
# afterwards, so repack exhausted a resource rather than finding damage.
# Because `set -euo pipefail` is on and the old call was unguarded, that exit
# aborted the script and fired the EXIT trap, which deletes $WORK. One optional
# optimisation was able to destroy 15.2 h of finished tokenizing.
#
# Default is `plain`: it packs the loose objects, which every later step reads
# back, without the --window=250 --depth=50 delta search that failed.
pack_cregit_repo() {
    case "$GC_MODE" in
        none)
            log "gc skipped (--gc none)"
            return 0
            ;;
        plain)      gc_args="--prune=now" ;;
        aggressive) gc_args="--prune=now --aggressive" ;;
        *) die "--gc takes none, plain or aggressive (got '$GC_MODE')" ;;
    esac

    git --git-dir="$REPO_PATH_CREGIT_BARE" reflog expire --expire=now --all \
        || log "warning: reflog expire failed; continuing"
    # shellcheck disable=SC2086
    if git --git-dir="$REPO_PATH_CREGIT_BARE" gc $gc_args; then
        log "gc ($GC_MODE) done"
    else
        log "warning: gc ($GC_MODE) failed; the object store is still readable"
        log "warning: later steps run unpacked, so they read more slowly"
    fi
    return 0
}

MODE="pipeline"
SHARDS=4
CPU_COUNT=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 1)
case "$CPU_COUNT" in
    ''|*[!0-9]*|0) CPU_COUNT=1 ;;
esac
DEFAULT_JOBS=4
[ "$CPU_COUNT" -lt "$DEFAULT_JOBS" ] && DEFAULT_JOBS=$CPU_COUNT
JOBS=${CREGIT_JOBS:-$DEFAULT_JOBS}
FROM_STEP=1
BUILD_ONLY=0
REPO_GIT_URL=""
REPO_NAME=""
REPO_COMMIT_URL=""
# Empty means "use the universal mask", which is DERIVED from the tokenizer's own
# extension table by tokenize/fileMask.pl rather than written out here. A default
# typed beside the table drifts from it, and both directions are quiet: a mask
# naming an extension with no parser kills the run part-way through, and an
# extension the table knows but no mask names is source silently left
# untokenized. Resolved after argument parsing, so --mask still wins.
MASK=""
WORK="../cregit-files"
SKIP_HTML=0
GC_MODE="plain"
# Empty means "do not pass the flag", so generate_dataset.py keeps its own
# default. Step 10 settles at about 1.4x the limit, so a corpus run with N
# concurrent projects must budget 1.4 x N x limit of RAM.
MEMORY_LIMIT=""
DUCKDB_THREADS=""
# Empty means "do not pass the flag", as above: step 10 then emits the
# per-project provenance columns as empty strings, so the schema does not depend
# on whether the caller knows about the sidecar.
PROJECT_META=""
PROJECT_KEY=""
FORCE_CLEAN=0
# Empty means "do not pass the flag", so blobExec keeps its own defaults (600s
# per blob, 1800s stall window). The CREGIT_* environment fallbacks exist so the
# values are reachable through ctp.py, which has no passthrough of its own but
# does hand its environment to this script.
BLOB_TIMEOUT="${CREGIT_BLOB_TIMEOUT:-}"
STALL_TIMEOUT="${CREGIT_STALL_TIMEOUT:-}"

# Markers meaning "the work in $WORK is incomplete but recoverable, and a
# FROM_STEP=1 wipe would throw away days of tokenizing to redo it". Written by
# step 2 when blobExec reports a timed-out blob (exit 4) or a stall (exit 5);
# see keep_markers_present and --force-clean.
KEEP_MARKERS="TOKENIZE-TIMEOUTS TOKENIZE-STALLED"

# Prints the first marker found in $WORK and returns 0; returns 1 if none.
keep_markers_present() {
    local m
    for m in $KEEP_MARKERS; do
        if [ -e "${WORK}/${m}" ]; then
            printf '%s' "${WORK}/${m}"
            return 0
        fi
    done
    return 1
}

# tokenize_gate <exit status> <what ran>: turn blobExec's two "incomplete but
# resumable" statuses into a marker plus resume instructions, and stop the run.
# Shared by the serial and sharded branches of step 2 so they cannot drift — the
# sharded branch not having this is how a 435 GB build would have been wiped.
#
#   4 = a blob's tokenizer was killed on its budget, so those files would hold raw
#       source instead of tokens. Nothing was recorded for the containing commit,
#       so a step-2 resume retries exactly those blobs.
#   5 = the stall watchdog killed a run that stopped making progress.
#
# Anything else non-zero is an ordinary failure and keeps the old behaviour.
tokenize_gate() {
    local rc="$1" what="$2"
    [ "$rc" -eq 0 ] && return 0

    local resume_lines="       runner:  $0 --repo-url <url> --work $WORK [same flags] 2
       ctp.py:  python3 ./ctp.py run [same flags] --from-step 2"
    local timeout_knob="Slowness is --blob-timeout (runner: --blob-timeout N, or
     CREGIT_BLOB_TIMEOUT=N in the environment, which reaches this script through
     ctp.py too). A child that reported status 137 was SIGKILLed, which on this
     box usually means the kernel's OOM killer rather than slowness — more time
     will not help it; give the run more memory or exclude that blob via --mask."

    if [ "$rc" -eq 4 ]; then
        date -u +"%Y-%m-%dT%H:%M:%SZ" > "${WORK}/TOKENIZE-TIMEOUTS"
        echo "$what exited 4: at least one blob timed out; see the blobsTimedOut" \
             "count and the 'will retry on the next run' lines in this step's log." \
             "Nothing was recorded for the containing commit, so resuming at step 2" \
             "retries those blobs. Resuming at step 1 deletes this directory instead;" \
             "while this marker exists the runner refuses to do that without" \
             "--force-clean." >> "${WORK}/TOKENIZE-TIMEOUTS"
        die "$what left blobs untokenized (exit 4). Refusing to continue: the
     dataset would carry raw source in place of tokens. Marker written to
     ${WORK}/TOKENIZE-TIMEOUTS.
     Recovery — resume at step 2. Do NOT re-run from step 1: that deletes
     $WORK, including the memo and the tokenizing already done, and redoes all
     of it to retry a handful of blobs. Step 2 retries exactly the blobs that
     failed and needs no database surgery:
$resume_lines
     If the same blobs keep failing: $timeout_knob"
    elif [ "$rc" -eq 5 ]; then
        date -u +"%Y-%m-%dT%H:%M:%SZ" > "${WORK}/TOKENIZE-STALLED"
        echo "$what exited 5: the stall watchdog fired. The STALLED line in this" \
             "step's log names the work that was in flight. Resume at step 2 to keep" \
             "this directory; while this marker exists the runner refuses a step-1" \
             "wipe without --force-clean." >> "${WORK}/TOKENIZE-STALLED"
        die "$what stalled and was killed by blobExec's watchdog (exit 5). Marker
     written to ${WORK}/TOKENIZE-STALLED. The memo in $WORK is durable and
     resuming picks up where it stopped — but only at step 2. A step-1 re-run
     deletes $WORK first, memo included, so the durability buys nothing:
$resume_lines
     The STALLED line in this step's log names the blobs that were in flight.
     If one of them is pathological: $timeout_knob"
    else
        die "$what failed (exit $rc)"
    fi
}

# need_val <flag> <value...>: refuse a value-taking flag with no value.
need_val() {
    [ $# -ge 2 ] || { echo "missing value for $1" >&2; usage; exit 2; }
}

while [ $# -gt 0 ]; do
    case "$1" in
        --build-only) BUILD_ONLY=1; shift ;;
        --repo-url)   need_val "$@"; REPO_GIT_URL="$2"; shift 2 ;;
        --repo-name)  need_val "$@"; REPO_NAME="$2"; shift 2 ;;
        --commit-url) need_val "$@"; REPO_COMMIT_URL="$2"; shift 2 ;;
        --mask)       need_val "$@"; MASK="$2"; shift 2 ;;
        --work)       need_val "$@"; WORK="$2"; shift 2 ;;
        --skip-html)  SKIP_HTML=1; shift ;;
        --force-clean) FORCE_CLEAN=1; shift ;;
        --blob-timeout)  need_val "$@"; BLOB_TIMEOUT="$2"; shift 2 ;;
        --stall-timeout) need_val "$@"; STALL_TIMEOUT="$2"; shift 2 ;;
        --gc)         need_val "$@"; GC_MODE="$2"; shift 2 ;;
        --memory-limit)   need_val "$@"; MEMORY_LIMIT="$2"; shift 2 ;;
        --duckdb-threads) need_val "$@"; DUCKDB_THREADS="$2"; shift 2 ;;
        --project-meta)   need_val "$@"; PROJECT_META="$2"; shift 2 ;;
        --project-key)    need_val "$@"; PROJECT_KEY="$2"; shift 2 ;;
        --mode)       need_val "$@"; MODE="$2"; shift 2 ;;
        --shards)     need_val "$@"; SHARDS="$2"; shift 2 ;;
        --jobs)       need_val "$@"; JOBS="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        ''|*[!0-9]*) echo "unknown argument: $1" >&2; usage; exit 2 ;;
        *) FROM_STEP="$1"; shift ;;
    esac
done

# Resolve the default mask from the extension table. Read from this script's own
# directory rather than $(pwd), so it does not depend on the caller's working
# directory the way $CREGIT below does.
if [ -z "$MASK" ]; then
    SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    MASK="$(perl "${SELF_DIR}/tokenize/fileMask.pl")" || {
        echo "cannot read the universal mask from ${SELF_DIR}/tokenize/fileMask.pl;" \
             "pass --mask explicitly" >&2
        exit 2
    }
    [ -n "$MASK" ] || { echo "${SELF_DIR}/tokenize/fileMask.pl printed nothing" >&2; exit 2; }
fi

# Reject a bad --gc value now, not after tokenizing. pack_cregit_repo runs at the
# end of step 2, so a typo caught there costs the whole tokenize first.
case "$GC_MODE" in
    none|plain|aggressive) ;;
    *) echo "invalid --gc: '$GC_MODE' (want none, plain or aggressive)" >&2; exit 2 ;;
esac

# Step 10 is the LAST step, so a typo here costs the whole run. Reject it now.
# The rule mirrors parse_memory_limit() in generate_dataset.py: an absolute
# size, never a percentage, because a percentage measures total RAM and only the
# free part is usable.
if [ -n "$MEMORY_LIMIT" ]; then
    case "$MEMORY_LIMIT" in
        *%) echo "invalid --memory-limit: '$MEMORY_LIMIT' takes an absolute size, not a percentage (example: 3GB)" >&2; exit 2 ;;
    esac
    if ! printf '%s' "$MEMORY_LIMIT" \
        | grep -Eqi '^[0-9]+(\.[0-9]+)?[[:space:]]?(B|K|M|G|T|KB|MB|GB|TB|KIB|MIB|GIB|TIB)$'; then
        echo "invalid --memory-limit: cannot read '$MEMORY_LIMIT' as a memory size (example: 3GB)" >&2
        exit 2
    fi
fi

if [ -n "$DUCKDB_THREADS" ]; then
    case "$DUCKDB_THREADS" in
        ''|*[!0-9]*) echo "invalid --duckdb-threads: '$DUCKDB_THREADS' (want a positive integer)" >&2; exit 2 ;;
        0) echo "invalid --duckdb-threads: 0 (want a positive integer)" >&2; exit 2 ;;
    esac
fi

# Same reasoning as --memory-limit: step 10 is the last step, so an unreadable
# sidecar must be caught now rather than after the whole run.
if [ -n "$PROJECT_META" ] && [ ! -f "$PROJECT_META" ]; then
    echo "invalid --project-meta: '$PROJECT_META' is not a file (generate it with project_meta.py)" >&2
    exit 2
fi
if [ -n "$PROJECT_KEY" ] && [ -z "$PROJECT_META" ]; then
    echo "--project-key without --project-meta has nothing to key into" >&2
    exit 2
fi

# Reject bad timeout values here rather than at step 2, which on a large repo is
# hours in. blobExec enforces the relationship between the two (the stall window
# must exceed the per-blob budget); this only checks they are positive integers.
for _tv in "BLOB_TIMEOUT:$BLOB_TIMEOUT:--blob-timeout" "STALL_TIMEOUT:$STALL_TIMEOUT:--stall-timeout"; do
    _val=${_tv#*:}; _flag=${_val#*:}; _val=${_val%%:*}
    [ -n "$_val" ] || continue
    case "$_val" in
        ''|*[!0-9]*) echo "invalid $_flag: '$_val' (want a positive whole number of seconds)" >&2; exit 2 ;;
        0) echo "invalid $_flag: 0 (want a positive whole number of seconds)" >&2; exit 2 ;;
    esac
done

# The target repository is mandatory (only --build-only runs without one).
if [ "$BUILD_ONLY" = 0 ]; then
    if [ -z "$REPO_GIT_URL" ]; then
        echo "error: --repo-url is required (git URL or local path of the repository to process)" >&2
        usage
        exit 2
    fi
    # Derive the repo name and commit URL from --repo-url when not given explicitly.
    if [ -z "$REPO_NAME" ]; then
        REPO_NAME=$(basename "$REPO_GIT_URL" .git)
    fi
    case "$REPO_NAME" in
        */*|'') echo "invalid --repo-name: '$REPO_NAME'" >&2; exit 2 ;;
    esac
    if [ -z "$REPO_COMMIT_URL" ]; then
        # GitHub/GitLab-style default; pass --commit-url for other hosts if you
        # want working commit links in the generated HTML.
        REPO_COMMIT_URL="${REPO_GIT_URL%.git}/commit/"
    fi
    case "$WORK" in
        /|.|..|'') echo "refusing unsafe --work: '$WORK'" >&2; exit 2 ;;
    esac
fi

case "$MODE" in
    serial|pipeline|pipeline-trees|sharded) ;;
    *) echo "invalid --mode: $MODE (serial|pipeline|pipeline-trees|sharded)" >&2; exit 2 ;;
esac
if [ "$MODE" = "sharded" ]; then
    [ "$SHARDS" -ge 1 ] 2>/dev/null || { echo "--shards must be a positive integer" >&2; exit 2; }
fi
[ "$JOBS" -ge 1 ] 2>/dev/null || { echo "--jobs must be a positive integer" >&2; exit 2; }

step() {
    STEP_NUM=${STEP_NUM:-0}
    STEP_NUM=$((STEP_NUM + 1))
    STEP_START=$(date +%s)
    [ "$STEP_NUM" -lt "$FROM_STEP" ] && return 0
    echo ""
    echo "═══════════════════════════════════════════════════════════════════"
    echo "  Step $STEP_NUM — $1"
    echo "═══════════════════════════════════════════════════════════════════"
}

end_step() {
    [ "$STEP_NUM" -lt "$FROM_STEP" ] && return 0
    local elapsed=$(( $(date +%s) - STEP_START ))
    echo "  ✓ completed in ${elapsed}s"
}

CREGIT=$(pwd)
BFG="${CREGIT}/blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar"
SLICKGITLOG_JAR="${CREGIT}/slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar"
PERSONS_JAR="${CREGIT}/persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar"
REMAPCOMMITS_JAR="${CREGIT}/remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar"
SRCML2TOKEN="${CREGIT}/tokenize/srcMLtoken/srcml2token"
RUST_TOKENIZER="${CREGIT}/tokenize/rustTokenizer/target/release/rust_tokenizer"

# ---------------------------------------------------------------------------
# Build — every artifact the pipeline needs. Missing artifacts are built
# automatically before a run; --build-only (re)builds everything and exits.
# blobExec is Scala 2.13 and uses the default (modern) JDK; slickGitLog,
# persons and remapCommits are Scala 2.10 + sbt 0.13 and need JDK 8, provided
# as LEGACY_JAVA_HOME by `devenv shell`.
# ---------------------------------------------------------------------------
require_legacy_jdk() {
    [ -n "${LEGACY_JAVA_HOME:-}" ] || die \
"LEGACY_JAVA_HOME is not set (JDK 8, needed for the Scala 2.10 modules).
Enter the pinned environment first:  devenv shell"
}

build_srcml2token() {
    log "build: tokenize/srcMLtoken (C++)"
    make -C "$CREGIT/tokenize/srcMLtoken" || die "build failed: srcml2token"
}

build_rust_tokenizer() {
    log "build: tokenize/rustTokenizer (cargo)"
    make -C "$CREGIT/tokenize/rustTokenizer" || die "build failed: rustTokenizer"
}

build_blobexec() {
    log "build: blobExec (sbt assembly, modern JDK)"
    ( cd "$CREGIT/blobExec" && sbt -batch assembly ) || die "build failed: blobExec"
}

build_legacy_jar() {  # $1 = module directory
    require_legacy_jdk
    log "build: $1 (sbt one-jar, JDK 8)"
    ( cd "$CREGIT/$1" && sbt --java-home "$LEGACY_JAVA_HOME" -batch one-jar ) \
        || die "build failed: $1"
}

build_all() {
    build_srcml2token
    build_rust_tokenizer
    build_blobexec
    build_legacy_jar slickGitLog
    build_legacy_jar persons
    build_legacy_jar remapCommits
    log "build complete"
}

# Build only what is missing, so an already-built checkout starts instantly.
ensure_artifacts() {
    [ -x "$SRCML2TOKEN" ]      || build_srcml2token
    [ -x "$RUST_TOKENIZER" ]   || build_rust_tokenizer
    [ -f "$BFG" ]              || build_blobexec
    [ -f "$SLICKGITLOG_JAR" ]  || build_legacy_jar slickGitLog
    [ -f "$PERSONS_JAR" ]      || build_legacy_jar persons
    [ -f "$REMAPCOMMITS_JAR" ] || build_legacy_jar remapCommits
}

if [ "$BUILD_ONLY" = 1 ]; then
    build_all
    exit 0
fi

# Auto-build any missing artifact BEFORE the work directory is touched, so a
# build failure never disturbs the outputs of a previous run.
ensure_artifacts

REPO_PATH_ORIGINAL="${WORK}/${REPO_NAME}-original"
REPO_PATH_CREGIT="${WORK}/${REPO_NAME}-cregit"
REPO_PATH_ORIGINAL_BARE="${REPO_PATH_ORIGINAL}.git"
SHARD_OUT="${WORK}/shard-build"

# The tokenized (cregit) bare repo. blobExec is a from-scratch src->dst rewriter:
# the serial/pipeline modes write it directly; the sharded mode produces it as the
# merged, serial re-folded result under shard-build/final.
if [ "$MODE" = "sharded" ]; then
    REPO_PATH_CREGIT_BARE="${SHARD_OUT}/final/dst.git"
else
    REPO_PATH_CREGIT_BARE="${REPO_PATH_CREGIT}.git"
fi

DB_PATH_ORIGINAL="${REPO_PATH_ORIGINAL}.db"
DB_PATH_CREGIT="${REPO_PATH_CREGIT}.db"
DB_PATH_BLOBMAP="${WORK}/${REPO_NAME}-blobmap.db"
DB_PATH_PERSONS="${WORK}/${REPO_NAME}-persons.db"
XLS_PATH_PERSONS="${WORK}/${REPO_NAME}-persons.xls"
DATASET_PATH="${WORK}/${REPO_NAME}-dataset.parquet"

PYTHON=$(command -v python3 || true)  # only needed by step 10 (dataset)

cleanup() {
    local ec=$?
    if [ $ec -ne 0 ] && [ "$FROM_STEP" = "1" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
        # A recoverable tokenize failure must survive its own error path. Without
        # this the exit-4 / exit-5 die below would delete the marker it just
        # wrote, plus the memo, the bare repos and the blob map it promises are
        # still there — the work the operator is told to resume from.
        local marker
        if marker=$(keep_markers_present); then
            log "Pipeline failed (exit $ec) — keeping $WORK: $marker says the work is resumable"
            log "Resume with FROM_STEP=2 (runner: append '2'; ctp.py: --from-step 2),"
            log "or discard it deliberately with --force-clean."
            return 0
        fi
        log "Pipeline failed (exit $ec) — removing $WORK for a clean restart"
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

# A full run starts clean; resuming (FROM_STEP >= 2) keeps existing work.
if [ "$FROM_STEP" = "1" ] && [ -d "$WORK" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
    # ...unless the previous run left work that is incomplete but recoverable.
    # Deleting it here is the expensive mistake: on a large repository this is
    # days of tokenizing, and the re-run would redo all of it to retry one blob.
    if marker=$(keep_markers_present) && [ "$FORCE_CLEAN" != "1" ]; then
        die "refusing to delete $WORK: $marker
     That run stopped with work that is incomplete but resumable — the memo,
     the bare repos and the blob map in there are still good, and step 2 will
     retry only the blobs that failed.
     Resume (keeps the directory, retries the failed blobs):
       runner:  $0 --repo-url <url> --work $WORK [same flags] 2
       ctp.py:  python3 ./ctp.py run [same flags] --from-step 2
     Start over and lose that work, deliberately:
       $0 --force-clean [same flags]
     (or remove $marker by hand)"
    fi
    rm -rf "$WORK"
fi

LOG_FILE="${WORK}/pipeline.log"
mkdir -p $WORK/memo $WORK/blame
# memo/ is mandatory: tokenizeByBlobId/tokenBySha.pl dies without BFG_MEMO_DIR.
# html/ is only created when step 9 will actually write into it.
[ "$SKIP_HTML" = 1 ] || mkdir -p $WORK/html
exec > >(tee -a "$LOG_FILE") 2>&1

echo ""
echo "████████████████████████████████████████████████████████████████████████"
echo "  CreGit Pipeline — ${REPO_NAME} (tokenize mode: ${MODE}, file jobs: ${JOBS})"
echo "  Repo: ${REPO_GIT_URL}"
echo "  Mask: ${MASK}   Commit links: ${REPO_COMMIT_URL}"
echo "  Log: $LOG_FILE"
echo "████████████████████████████████████████████████████████████████████████"
echo ""

# ---------------------------------------------------------------------------
# Step 1 — clone bare original repo
# ---------------------------------------------------------------------------
step "clone bare original repo"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
git clone --bare $REPO_GIT_URL $REPO_PATH_ORIGINAL_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 2 — tokenize (rewrite .c/.h blobs to their token-level representation)
#          blobExec reads the original bare (src) and builds the cregit bare
#          (dst) from scratch; step 3+ consume that dst.
# ---------------------------------------------------------------------------
step "tokenize [$MODE] (src→dst)"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_ORIGINAL_BARE" ] || die "step 1 did not produce $REPO_PATH_ORIGINAL_BARE"
[ -f "$BFG" ] || die "blobExec jar not found: $BFG (run: ./run_pipeline_process.sh --build-only)"

export BFG_MEMO_DIR="${WORK}/memo"

# Route through the tokenize.pl dispatcher (not tokenizeSrcMl.pl directly) so it can
# fan out by language: srcML for .c/.h, rustTokenizer for .rs, etc. The --srcml* /
# --ctags paths are forwarded only to the srcML parser. Behavior-preserving for C.
export BFG_TOKENIZE_CMD="${CREGIT}/tokenize/tokenize.pl \
  --srcml2token=${SRCML2TOKEN} \
  --srcml=$(which srcml) \
  --ctags=$(which ctags)"

TOKENIZE_RC=0
if [ "$MODE" = "sharded" ]; then
  # Memory-bounded path: N tree-only shards in parallel, then merge + serial
  # re-fold into $SHARD_OUT/final/{dst.git,blobmap.db} (byte-identical to serial).
  # shard_build.sh propagates blobExec's 4 and 5 instead of collapsing them, so
  # this branch gets the same marker and the same guard as the serial one.
  "${CREGIT}/blobExec/shard_build.sh" \
    --src "$REPO_PATH_ORIGINAL_BARE" \
    --out "$SHARD_OUT" \
    --shards "$SHARDS" \
    --jar "$BFG" \
    --command "${CREGIT}/tokenizeByBlobId/tokenBySha.pl" \
    --mask "$MASK" \
    --tok-cmd "$BFG_TOKENIZE_CMD" \
    ${BLOB_TIMEOUT:+--blob-timeout "$BLOB_TIMEOUT"} \
    ${STALL_TIMEOUT:+--stall-timeout "$STALL_TIMEOUT"} || TOKENIZE_RC=$?
  tokenize_gate "$TOKENIZE_RC" "sharded tokenize (shard_build.sh)"
else
  MODE_FLAG=""
  [ "$MODE" = "pipeline" ]       && MODE_FLAG="--pipeline"
  [ "$MODE" = "pipeline-trees" ] && MODE_FLAG="--pipeline-trees"
  java -jar "$BFG" $MODE_FLAG \
    ${BLOB_TIMEOUT:+--blob-timeout=$BLOB_TIMEOUT} \
    ${STALL_TIMEOUT:+--stall-timeout=$STALL_TIMEOUT} \
    "$REPO_PATH_ORIGINAL_BARE" \
    "$REPO_PATH_CREGIT_BARE" \
    "$DB_PATH_BLOBMAP" \
    "${CREGIT}/tokenizeByBlobId/tokenBySha.pl" \
    "$MASK" || TOKENIZE_RC=$?
  tokenize_gate "$TOKENIZE_RC" "tokenize (blobExec)"
fi

[ -d "$REPO_PATH_CREGIT_BARE" ] || die "tokenize did not produce $REPO_PATH_CREGIT_BARE"

# Step 2 finished: the work here is no longer "incomplete but resumable", so drop
# the markers. Leaving them would block every later FROM_STEP=1 run for the life
# of the directory, and ctp.py has no --force-clean passthrough to get past that.
for _m in $KEEP_MARKERS; do
    if [ -e "${WORK}/${_m}" ]; then
        log "tokenize completed — clearing ${WORK}/${_m}"
        rm -f "${WORK}/${_m}"
    fi
done

pack_cregit_repo
fi
end_step

# ---------------------------------------------------------------------------
# Step 3 — git log DB (original repo)
# ---------------------------------------------------------------------------
step "git log DB (original repo)"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_ORIGINAL_BARE" ] || die "step 1 did not produce $REPO_PATH_ORIGINAL_BARE"
java -jar "$SLICKGITLOG_JAR" \
  $DB_PATH_ORIGINAL $REPO_PATH_ORIGINAL_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 4 — git log DB (cregit repo)
# ---------------------------------------------------------------------------
step "git log DB (cregit repo)"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_CREGIT_BARE" ] || die "step 2 did not produce $REPO_PATH_CREGIT_BARE"
java -jar "$SLICKGITLOG_JAR" \
  $DB_PATH_CREGIT $REPO_PATH_CREGIT_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 5 — persons DB
# ---------------------------------------------------------------------------
step "persons DB"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_CREGIT" ] || die "step 4 did not produce $DB_PATH_CREGIT"
java -jar "$PERSONS_JAR" \
  $REPO_PATH_ORIGINAL_BARE $XLS_PATH_PERSONS $DB_PATH_PERSONS
fi
end_step

# ---------------------------------------------------------------------------
# Step 6 — clone non-bare working clones (for blame / HTML gen)
# ---------------------------------------------------------------------------
step "clone non-bare working clones"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_PERSONS" ] || die "step 5 did not produce $DB_PATH_PERSONS"
# GitHub's LFS no longer serves some objects in this corpus, and a smudge
# failure exits 128 — which the EXIT trap then answers by deleting a finished
# step 2. The tokenizer never reads LFS payloads: the mask selects source
# files, and the pointers are enough to walk the tree.
GIT_LFS_SKIP_SMUDGE=1 git clone $REPO_PATH_ORIGINAL_BARE $REPO_PATH_ORIGINAL
GIT_LFS_SKIP_SMUDGE=1 git clone $REPO_PATH_CREGIT_BARE $REPO_PATH_CREGIT
fi
end_step

# ---------------------------------------------------------------------------
# Step 7 — blame
# ---------------------------------------------------------------------------
step "blame"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$REPO_PATH_CREGIT" ] || die "step 6 did not produce $REPO_PATH_CREGIT"
perl $CREGIT/blameRepo/blameRepoFiles.pl \
  --jobs="$JOBS" \
  --formatBlame=$CREGIT/blameRepo/formatBlame.pl \
  $REPO_PATH_CREGIT $WORK/blame "$MASK"
fi
end_step

# ---------------------------------------------------------------------------
# Step 8 — remap commits (cregit → original commit mapping)
# ---------------------------------------------------------------------------
step "remap commits"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -d "$WORK/blame" ] || die "step 7 did not run (blame dir missing)"
java -jar "$REMAPCOMMITS_JAR" \
  $DB_PATH_CREGIT $REPO_PATH_CREGIT_BARE
fi
end_step

# ---------------------------------------------------------------------------
# Step 9 — generate HTML views
#          Skippable: nothing downstream reads $WORK/html. Step 10 builds the
#          Parquet dataset from the blame dir and the DBs only.
# ---------------------------------------------------------------------------
step "generate HTML views"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
if [ "$SKIP_HTML" = 1 ]; then
log "skip: --skip-html given, no HTML views written (step 10 does not read them)"
else
[ -f "$DB_PATH_CREGIT" ] || die "step 8 did not complete"
perl $CREGIT/prettyPrint/prettyPrintFiles.pl --verbose \
  --jobs="$JOBS" \
  $DB_PATH_CREGIT $DB_PATH_PERSONS \
  $REPO_PATH_ORIGINAL $WORK/blame $WORK/html \
  $REPO_COMMIT_URL "$MASK"
fi
fi
end_step

# ---------------------------------------------------------------------------
# Step 10 — generate the unified Parquet dataset (see generate_dataset/DATASET.md).
#           Needs python3 with the duckdb module (provided by `devenv shell`);
#           when unavailable the step is skipped and the HTML views remain the
#           final output, so a long run is never lost to a missing python dep.
# ---------------------------------------------------------------------------
step "generate Parquet dataset"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_CREGIT" ] || die "step 9 did not produce $DB_PATH_CREGIT"
DATASET_SCRIPT="$CREGIT/generate_dataset/generate_dataset.py"
[ -f "$DATASET_SCRIPT" ] || die "dataset generator not found: $DATASET_SCRIPT"
if [ -n "$PYTHON" ] && "$PYTHON" -c 'import duckdb' 2>/dev/null; then
# Collect the optional flags, so an empty value passes nothing and the generator
# keeps its own default. An array, not "$@": overwriting the positional
# parameters here would be a side effect on the whole script.
DATASET_OPTS=()
if [ -n "$MEMORY_LIMIT" ]; then
    DATASET_OPTS+=(--memory-limit "$MEMORY_LIMIT")
fi
if [ -n "$DUCKDB_THREADS" ]; then
    DATASET_OPTS+=(--duckdb-threads "$DUCKDB_THREADS")
fi
if [ -n "$PROJECT_META" ]; then
    DATASET_OPTS+=(--project-meta "$PROJECT_META")
fi
if [ -n "$PROJECT_KEY" ]; then
    DATASET_OPTS+=(--project-key "$PROJECT_KEY")
fi
# ${a[@]+"${a[@]}"} keeps an empty array safe under `set -u`.
"$PYTHON" "$DATASET_SCRIPT" \
  --blame-dir  "$WORK/blame" \
  --source-dir "$REPO_PATH_ORIGINAL" \
  --cregit-db  "$DB_PATH_CREGIT" \
  --persons-db "$DB_PATH_PERSONS" \
  --output     "$DATASET_PATH" \
  --repo-name  "$REPO_NAME" \
  ${DATASET_OPTS[@]+"${DATASET_OPTS[@]}"} \
  --verbose
log "dataset written: $DATASET_PATH"
elif [ "$SKIP_HTML" = 1 ]; then
die "python3 with the duckdb module is unavailable (provided by devenv shell) and --skip-html suppressed the HTML views — this run produced no final artifact"
else
log "skip: python3 with the duckdb module is unavailable (provided by devenv shell); HTML views in $WORK/html are the final output"
fi
fi
end_step
