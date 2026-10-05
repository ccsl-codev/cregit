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
  --ensure-artifacts
                    run the pre-run build guard and exit, building nothing that
                    is already current. That guard builds any MISSING artifact,
                    and additionally rebuilds the Rust tokenizer when its
                    sources are newer than the binary — it is the pipeline's only
                    compiled tokenizer, and a stale one silently shifts every
                    Rust token column by a field. The four jars and srcml2token
                    are still only checked for existence.

Target repository:
  --repo-url URL    git URL (or local path) of the repository to process (REQUIRED)
  --repo-name NAME  short name used to prefix the output files
                    (default: derived from --repo-url)
  --commit-url URL  base URL for the commit links in the generated HTML
                    (default: derived from --repo-url as <url minus .git>/commit/,
                    which is correct for GitHub/GitLab-style hosts)
  --mask REGEX      regex selecting the files to tokenize; quote it
                    (default: `perl tokenize/fileMask.pl`, every extension
                    cregit can parse). A different mask forces a full rebuild,
                    unless --mask-widened applies.
  --mask-widened    resume across a wider mask, keeping blob_map's
                    tokenizations. Needs FROM_STEP>=2, not with --mode sharded.
                    blobExec verifies the rows and refuses with exit 3 if the
                    new mask drops a tokenized path or a new_blob is missing.
  --retokenize EXTS comma-separated extensions (as in CregitLanguages.pm, e.g.
                    rs or c,h) whose cached tokenizations and memo entries are
                    redone, after their tokenizer identity changed. Needs
                    FROM_STEP=2 exactly, not with --mode sharded. blobExec
                    refuses (exit 7) a --retokenize that would change nothing.
  --work DIR        working/output directory (default: ../cregit-files).
                    NOTE: a full run (FROM_STEP=1) starts by deleting this
                    directory; use one directory per target repository.
                    Pass FROM_STEP=2 (the trailing positional argument) to
                    resume instead, keeping the memo, the bare repos and the
                    blob map — that is how a tokenize timeout or stall is
                    recovered without redoing the work.
  --memo-dir DIR    where to memoize tokenized blobs (default: <work>/memo).
                    Pass a directory OUTSIDE --work and no FROM_STEP=1 wipe can
                    reach it, so a from-scratch rebuild still gets every memo
                    hit. That matters when the mask changes: blobExec refuses to
                    resume against a different mask, so the whole project must be
                    rebuilt — but a memo hit returns without invoking srcml at
                    all (tokenizeByBlobId/tokenBySha.pl), which turns a cold
                    tokenize back into a commit walk.
                    ONE DIRECTORY PER REPOSITORY. The memo key is sha1 of the
                    file's CONTENT with no extension in it, so two repositories
                    sharing a directory would serve each other's entries, and
                    identical content under a different extension is a different
                    language and different tokens.
                    A step-1 wipe that would delete a memo holding 10,000 entries
                    or more (MEMO_KEEP_THRESHOLD, overridable as
                    CREGIT_MEMO_KEEP_THRESHOLD) is refused; see --force-clean.
  --blob-timeout N  wall-clock budget in seconds for one blob's tokenizer
                    (blobExec default: 600). A child that exceeds it is killed
                    and that blob is excluded, as an EXCLUDED failed blob line
                    says. Also settable
                    as CREGIT_BLOB_TIMEOUT in the environment, which is how to
                    reach it through ctp.py.
  --stall-timeout N watchdog window in seconds (blobExec default: 1800). If no
                    blob, tree, commit or blob copy completes anywhere in this
                    window the run is killed with exit 5. Must be larger than
                    --blob-timeout; blobExec refuses an explicit value that is
                    not, and raises a defaulted one. Also CREGIT_STALL_TIMEOUT.
  --force-clean     allow the FROM_STEP=1 wipe even when $WORK holds a
                    TOKENIZE-TIMEOUTS or TOKENIZE-STALLED marker, or a memo big
                    enough to be worth keeping. Without this the runner refuses:
                    those markers mean the work is incomplete but resumable at
                    step 2, and deleting it means re-tokenizing everything to
                    retry a few blobs, while deleting a large memo means
                    re-tokenizing from cold what is already tokenized.

Output:
  --skip-html       do not generate the HTML views (step 9). The views cost
                    94-255 MB per project and the dataset generator does not
                    read them, so a run that only wants the Parquet dataset
                    should skip them. NOTE: the HTML views are the fallback
                    output when python3+duckdb is missing, so --skip-html
                    without duckdb leaves the run with no final artifact.
  --reblame         re-blame every file in step 7, replacing existing .blame
                    output. Pass it when the blame itself changed; a resume
                    without it skips every file already blamed.
  --gc MODE         how to pack the generated cregit repo after tokenizing
                    (default: plain)
                      none        do not pack at all. Fastest, but every later
                                  step then reads loose objects.
                      plain       git gc --prune=now
                      aggressive  git gc --prune=now --aggressive. Slowest, and
                                  its delta search can fail outright on a repo
                                  with millions of loose objects.
                    A failed pack never aborts the run: the Parquet dataset is
                    the product, and packing only makes later steps faster.
  --memory-limit SIZE
                    cap the DuckDB heap in the dataset generator (step 10).
                    Omit to accept that script's own default of 8GB. Takes an
                    absolute size such as 3GB, never a percentage.
                    Budget 1.4 x N x SIZE of RAM for N parallel runs: SIZE caps
                    DuckDB's buffers, so the process settles above it.
  --duckdb-threads N
                    cap DuckDB's worker threads in the dataset generator. Each
                    sorting thread holds its own buffers, so fewer threads lower
                    the peak. Omit to accept the generator's default of 0, which
                    lets DuckDB choose.
  --project-meta PATH
                    a JSON sidecar of per-project provenance, written by
                    cregit-token-pipeline's project_meta.py. Omit and the
                    dataset generator emits those columns as empty strings, so
                    the schema is unchanged either way.
  --project-key NAME
                    which key of the sidecar holds this project. Omit to use
                    --repo-name. A key the sidecar does not hold fails the run.
  --firm-map PATH   the domain->firm CSV joined per row against person_domain
                    to fill firm_raw and firm_source. Omit and they are empty.
  --firm-canonical PATH
                    the reviewed canonical-name table that fills `firm`. Needs
                    --firm-map. Omit and `firm` repeats `firm_raw`.

Tokenizer:
  --mode MODE   tokenizer walk mode (default: pipeline)
                  serial          single-threaded reference walker
                  pipeline        look-ahead parallel walker (fastest for normal repos)
                  pipeline-trees  parallel walker + parallel tree assembly
                  sharded         N memory-bounded shards -> merge + serial re-fold,
                                  for repos too large to tokenize in one process;
                                  delegates to blobExec/shard_build.sh
  --shards N    shard count for --mode sharded (default: 4)
  --tokenizer-worker
                send each blob to a pool of persistent tokenizer processes
                (tokenizeByBlobId/tokenWorker.pl) instead of starting the
                tokenizer once per blob. Same output. Needs --mode pipeline or
                pipeline-trees (default: off)
  --jobs N      concurrent blame/HTML processes (default: CREGIT_JOBS,
                otherwise min(4, available CPUs)). Blame is the pipeline's
                bottleneck. Each file is independent, so the output does not
                depend on N. The step is resumable, so N can change between
                runs.
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

die_status() {
    log "ERROR: $2"
    exit "$1"
}

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

# Packs the generated bare repo, as --gc selects. A gc failure only warns: under
# `set -e` it would fire the EXIT trap, which deletes $WORK and every finished step.
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

    # shellcheck disable=SC2086
    if git --git-dir="$REPO_PATH_CREGIT_BARE" gc $gc_args; then
        log "gc ($GC_MODE) done"
    else
        log "warning: gc ($GC_MODE) failed; the object store is still readable"
        log "warning: later steps run unpacked, so they read more slowly"
    fi
    return 0
}

build_dataset_argv() {
    DATASET_ARGV=(
        --blame-dir  "$WORK/blame"
        --source-dir "$REPO_PATH_ORIGINAL"
        --cregit-db  "$DB_PATH_CREGIT"
        --persons-db "$DB_PATH_PERSONS"
        --output     "$DATASET_PATH"
        --repo-name  "$REPO_NAME"
        --verbose
    )
    [ -n "$MEMORY_LIMIT" ]   && DATASET_ARGV+=(--memory-limit "$MEMORY_LIMIT")
    [ -n "$DUCKDB_THREADS" ] && DATASET_ARGV+=(--duckdb-threads "$DUCKDB_THREADS")
    [ -n "$PROJECT_META" ]   && DATASET_ARGV+=(--project-meta "$PROJECT_META")
    [ -n "$PROJECT_KEY" ]    && DATASET_ARGV+=(--project-key "$PROJECT_KEY")
    [ -n "$FIRM_MAP" ]       && DATASET_ARGV+=(--firm-map "$FIRM_MAP")
    [ -n "$FIRM_CANONICAL" ] && DATASET_ARGV+=(--firm-canonical "$FIRM_CANONICAL")
    return 0   # a false test above must not fail the function under `set -e`
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
# 1 means "run ensure_artifacts and exit". Distinct from --build-only, which
# rebuilds all six unconditionally: this one runs exactly the guard a normal run
# runs, so it is also how that guard is tested.
ENSURE_ARTIFACTS_ONLY=0
REPO_GIT_URL=""
REPO_NAME=""
REPO_COMMIT_URL=""
MASK=""
WORK="../cregit-files"
# Empty means "<work>/memo"; resolved after parsing because it depends on --work.
MEMO_DIR=""
SKIP_HTML=0
REBLAME=0
GC_MODE="plain"
MEMORY_LIMIT=""
DUCKDB_THREADS=""
# Empty means "do not pass the flag", as above: step 10 then emits the
# per-project provenance columns as empty strings, so the schema does not depend
# on whether the caller knows about the sidecar.
PROJECT_META=""
PROJECT_KEY=""
FIRM_MAP=""
FIRM_CANONICAL=""
FORCE_CLEAN=0
MASK_WIDENED=0
RETOKENIZE=""
# Empty means "do not pass the flag". The CREGIT_* fallbacks are how ctp.py,
# which has no passthrough of its own, reaches these values.
BLOB_TIMEOUT="${CREGIT_BLOB_TIMEOUT:-}"
STALL_TIMEOUT="${CREGIT_STALL_TIMEOUT:-}"
TOKENIZER_WORKER=0

KEEP_MARKERS="TOKENIZE-TIMEOUTS TOKENIZE-STALLED TOKENIZE-PARSER-CRASHES"

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

TOKENIZE_TIMEOUT_STATUS=4
TOKENIZE_STALLED_STATUS=5
TOKENIZE_PARSER_CRASH_STATUS=6

# Memo entries below which a step-1 wipe is allowed. Overridable for tests.
MEMO_KEEP_THRESHOLD="${CREGIT_MEMO_KEEP_THRESHOLD:-10000}"
case "$MEMO_KEEP_THRESHOLD" in
    ''|0|*[!0-9]*)
        echo "invalid CREGIT_MEMO_KEEP_THRESHOLD: '$MEMO_KEEP_THRESHOLD' is not a positive integer" >&2
        exit 2
        ;;
esac

# Canonical form of a path, existing or not, for the inside-$WORK comparison.
canonical_path() {
    readlink -m -- "$1" 2>/dev/null || printf '%s' "$1"
}

# True when <dir> holds at least <n> entries. Counts at most <n> and stops: a
# large memo holds millions of files.
# find dies of SIGPIPE when head closes the pipe, which `|| true` absorbs so
# `set -o pipefail` does not abort the script.
memo_entries_at_least() {
    local dir=$1 n=$2 count
    [ -d "$dir" ] || return 1
    count=$( { find "$dir" -mindepth 3 -maxdepth 3 -type f -print 2>/dev/null || true; } \
             | head -n "$n" | wc -l )
    [ "$count" -ge "$n" ]
}

# True when the memo lives inside $WORK, so a wipe of $WORK would take it too.
path_inside_work() {
    local cw cp
    cw=$(canonical_path "$WORK")
    cp=$(canonical_path "$1")
    case "$cp" in
        "$cw"|"$cw"/*) return 0 ;;
        *) return 1 ;;
    esac
}

# Prints the memo directory and returns 0 when deleting $WORK would destroy a
# memo worth keeping.
# Every memo a wipe of $WORK would take with it, not just this run's. Passing
# --memo-dir elsewhere moves the NEW memo out of harm's way; it does not move the
# one a previous default run already left in $WORK/memo.
memo_at_risk() {
    local candidate
    for candidate in "$MEMO_DIR" "$WORK/memo"; do
        [ -n "$candidate" ] || continue
        path_inside_work "$candidate" || continue
        if memo_entries_at_least "$candidate" "$MEMO_KEEP_THRESHOLD"; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

memo_rescue_advice() {
    cat <<EOF
     Move it out of the way first, then this run keeps every memo hit:
       mv "$MEMO_DIR" /some/other/place/${REPO_NAME:-repo}-memo
       $0 --repo-url <url> --work $WORK --memo-dir /some/other/place/${REPO_NAME:-repo}-memo [same flags]
       ctp.py: python3 ./ctp.py run [same flags] --memo-dir /some/other/place
     A memo hit returns without invoking srcml at all, so this is the difference
     between re-walking the commits and tokenizing the whole repository again.
     ONE DIRECTORY PER REPOSITORY: the memo key is sha1 of the file contents and
     carries no repository and no extension.
     Delete it deliberately instead: --force-clean (or rm -rf "$MEMO_DIR").
EOF
}

resume_instructions() {
    printf '%s' "Recovery — resume at step 2. Do NOT re-run from step 1: that deletes
     $WORK, including the memo and the tokenizing already done, and redoes all of
     it to retry a handful of blobs. Step 2 retries exactly what failed and needs
     no database surgery:
       runner:  $0 --repo-url <url> --work $WORK [same flags] 2
       ctp.py (the external driver):  python3 ctp.py run [same flags] --from-step 2"
}

timeout_knob() {
    printf '%s' "Slowness is --blob-timeout (runner: --blob-timeout N, or
     CREGIT_BLOB_TIMEOUT=N in the environment, which reaches this script through
     ctp.py too). A child that reported status 137 was SIGKILLed, which on this
     box usually means the kernel's OOM killer rather than slowness — more time
     will not help it; give the run more memory or exclude that blob via --mask."
}

write_resume_marker() {
    local marker=$1 stage=$2 status=$3 detail=$4
    date -u +"%Y-%m-%dT%H:%M:%SZ" > "${WORK}/${marker}"
    echo "$stage exited $status: $detail Resuming at step 2 retries exactly that" \
         "work; resuming at step 1 deletes this directory instead, and while this" \
         "marker exists the runner refuses to do that without --force-clean." \
         >> "${WORK}/${marker}"
}

tokenize_gate() {
    local status=$1 stage=$2 marker summary
    [ "$status" -eq 0 ] && return 0
    case "$status" in
        "$TOKENIZE_TIMEOUT_STATUS")
            marker="TOKENIZE-TIMEOUTS"
            summary="$stage left blobs untokenized, so the dataset would carry raw source.
     $(timeout_knob)" ;;
        "$TOKENIZE_STALLED_STATUS")
            marker="TOKENIZE-STALLED"
            summary="$stage stalled and the watchdog killed it.
     $(timeout_knob)" ;;
        "$TOKENIZE_PARSER_CRASH_STATUS")
            marker="TOKENIZE-PARSER-CRASHES"
            summary="$stage hit a parser crash: deterministic, so --blob-timeout will not help.
     Fix srcML, or denylist the diagnosed blob." ;;
        7) die "$stage refused --retokenize, which would have invalidated nothing (exit 7).
     Check the extension spelling (as in tokenize/CregitLanguages.pm) and that
     --memo-dir names this project's memo ($MEMO_DIR). Nothing in $WORK changed." ;;
        *) die "$stage failed (exit $status)" ;;
    esac

    write_resume_marker "$marker" "$stage" "$status" "$summary"
    die_status "$status" "$summary (exit $status)
     Marker: ${WORK}/${marker}
     $(resume_instructions)"
}

# need_val <flag> <value...>: refuse a value-taking flag with no value.
need_val() {
    [ $# -ge 2 ] || { echo "missing value for $1" >&2; usage; exit 2; }
}

while [ $# -gt 0 ]; do
    case "$1" in
        --build-only) BUILD_ONLY=1; shift ;;
        --ensure-artifacts) ENSURE_ARTIFACTS_ONLY=1; shift ;;
        --repo-url)   need_val "$@"; REPO_GIT_URL="$2"; shift 2 ;;
        --repo-name)  need_val "$@"; REPO_NAME="$2"; shift 2 ;;
        --commit-url) need_val "$@"; REPO_COMMIT_URL="$2"; shift 2 ;;
        --mask)       need_val "$@"; MASK="$2"; shift 2 ;;
        --work)       need_val "$@"; WORK="$2"; shift 2 ;;
        --memo-dir)   need_val "$@"; MEMO_DIR="$2"; shift 2 ;;
        --skip-html)  SKIP_HTML=1; shift ;;
        --reblame)    REBLAME=1; shift ;;
        --force-clean) FORCE_CLEAN=1; shift ;;
        --mask-widened) MASK_WIDENED=1; shift ;;
        --retokenize) need_val "$@"; RETOKENIZE="$2"; shift 2 ;;
        --blob-timeout)  need_val "$@"; BLOB_TIMEOUT="$2"; shift 2 ;;
        --tokenizer-worker) TOKENIZER_WORKER=1; shift ;;
        --stall-timeout) need_val "$@"; STALL_TIMEOUT="$2"; shift 2 ;;
        --gc)         need_val "$@"; GC_MODE="$2"; shift 2 ;;
        --memory-limit)   need_val "$@"; MEMORY_LIMIT="$2"; shift 2 ;;
        --duckdb-threads) need_val "$@"; DUCKDB_THREADS="$2"; shift 2 ;;
        --project-meta)   need_val "$@"; PROJECT_META="$2"; shift 2 ;;
        --project-key)    need_val "$@"; PROJECT_KEY="$2"; shift 2 ;;
        --firm-map)       need_val "$@"; FIRM_MAP="$2"; shift 2 ;;
        --firm-canonical) need_val "$@"; FIRM_CANONICAL="$2"; shift 2 ;;
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

if [ "$MASK_WIDENED" = 1 ] && [ "$FROM_STEP" = "1" ]; then
    echo "--mask-widened needs FROM_STEP>=2. A step-1 run starts by deleting $WORK, so the
     blob map it would reuse and the cregit.git its new_blob ids live in are both
     gone before blobExec starts, and the flag would preserve nothing.
     Resume instead:
       runner:  $0 --repo-url <url> --work $WORK --mask-widened [same flags] 2
       ctp.py:  python3 ./ctp.py run [same flags] --mask-widened --from-step 2" >&2
    exit 2
fi

if [ "$MASK_WIDENED" = 1 ] && [ "$MODE" = "sharded" ]; then
    echo "--mask-widened is not available with --mode sharded: every shard builds a fresh
     blob map, so no recorded mask exists to widen. Reuse a prior run's
     tokenizations with shard_build.sh --warm-db instead." >&2
    exit 2
fi

if [ -n "$RETOKENIZE" ] && [ "$FROM_STEP" != "2" ]; then
    echo "--retokenize needs FROM_STEP=2 exactly (got $FROM_STEP).
     Step 1 deletes $WORK, so the blob map and memo whose poisoned entries this flag
     removes are gone before blobExec starts — and a from-scratch run re-tokenizes
     everything anyway, with the current tokenizer, which is the same outcome at
     full cost.
     Step 3 or later never reaches the invalidation at all: it lives in step 2, so
     the flag would be skipped, steps 3-10 would run over the poisoned tokens, and
     the run would exit 0.
     Resume at step 2:
       runner:  $0 --repo-url <url> --work $WORK --retokenize $RETOKENIZE [same flags] 2
       ctp.py:  python3 ./ctp.py run [same flags] --from-step 2" >&2
    exit 2
fi

if [ "$REBLAME" = 1 ] && [ "$FROM_STEP" -gt 7 ]; then
    echo "--reblame needs FROM_STEP<=7 (got $FROM_STEP). The re-blame happens inside
     step 7. From step 8 onward the flag is skipped, step 10 rebuilds the Parquet from
     the blame files already on disk, and the run exits 0 having changed nothing.
     Resume at step 7:
       runner:  $0 --repo-url <url> --work $WORK --reblame [same flags] 7
       ctp.py:  python3 ./ctp.py run [same flags] --reblame --from-step 7" >&2
    exit 2
fi

if [ -n "$RETOKENIZE" ] && [ "$MODE" = "sharded" ]; then
    echo "--retokenize is not available with --mode sharded: every shard builds a fresh
     blob map, so there are no cached tokenizations for it to invalidate. Retokenize
     the merged result in a non-sharded step-2 resume, or drop the shards' warm db." >&2
    exit 2
fi

if [ -n "$RETOKENIZE" ] && [ "$MASK_WIDENED" = 1 ]; then
    echo "--mask-widened and --retokenize cannot be used in the same run. Each verifies its
     own precondition against the blob map's rows, and together each would verify
     against a state the other is about to change.
     Do them one at a time: widen first, then resume again with --retokenize." >&2
    exit 2
fi

if [ -n "$RETOKENIZE" ]; then
    for _ext in ${RETOKENIZE//,/ }; do
        case "$_ext" in
            ''|*[!a-z0-9+]*)
                echo "invalid --retokenize: '$_ext' is not an extension. Want lowercase names
     without a leading dot, comma-separated, as spelled in tokenize/CregitLanguages.pm:
     --retokenize rs   --retokenize c,h" >&2
                exit 2 ;;
        esac
    done
fi

# Validate early: pack_cregit_repo runs only once tokenizing has finished.
case "$GC_MODE" in
    none|plain|aggressive) ;;
    *) echo "invalid --gc: '$GC_MODE' (want none, plain or aggressive)" >&2; exit 2 ;;
esac

# Validate early: the dataset generator is the last step. Mirrors
# parse_memory_limit() in generate_dataset.py, which refuses a percentage
# because a percentage measures total RAM, not the free part.
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

# Same for the sidecar: the dataset generator is the last step.
if [ -n "$PROJECT_META" ] && [ ! -f "$PROJECT_META" ]; then
    echo "invalid --project-meta: '$PROJECT_META' is not a file (generate it with project_meta.py)" >&2
    exit 2
fi
if [ -n "$PROJECT_KEY" ] && [ -z "$PROJECT_META" ]; then
    echo "--project-key without --project-meta has nothing to key into" >&2
    exit 2
fi

if [ -n "$FIRM_MAP" ] && [ ! -f "$FIRM_MAP" ]; then
    echo "invalid --firm-map: '$FIRM_MAP' is not a file (build it with build_domain_map.py)" >&2
    exit 2
fi
if [ -n "$FIRM_CANONICAL" ] && [ ! -f "$FIRM_CANONICAL" ]; then
    echo "invalid --firm-canonical: '$FIRM_CANONICAL' is not a file" >&2
    exit 2
fi
if [ -n "$FIRM_CANONICAL" ] && [ -z "$FIRM_MAP" ]; then
    echo "--firm-canonical without --firm-map has no firm_raw to canonicalise" >&2
    exit 2
fi

# Validate early: step 2 runs for hours. blobExec enforces the relationship
# between the two values; this only rejects non-positive integers.
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

# Resolve the memo location now that --work is final. The default is unchanged
# from before this option existed, so a caller that does not pass --memo-dir gets
# exactly the old layout and the old behaviour.
if [ -z "$MEMO_DIR" ]; then
    MEMO_DIR="${WORK}/memo"
else
    case "$MEMO_DIR" in
        /|.|..) echo "refusing unsafe --memo-dir: '$MEMO_DIR'" >&2; exit 2 ;;
    esac
    # An explicit memo dir inside $WORK is legal but pointless, and saying so is
    # cheaper than discovering it after a wipe.
    if path_inside_work "$MEMO_DIR"; then
        log "warning: --memo-dir $MEMO_DIR is inside $WORK, so a FROM_STEP=1 run still deletes it"
        log "warning: pass a directory outside $WORK for a memo that survives the wipe"
    fi
fi

case "$MODE" in
    serial|pipeline|pipeline-trees|sharded) ;;
    *) echo "invalid --mode: $MODE (serial|pipeline|pipeline-trees|sharded)" >&2; exit 2 ;;
esac
if [ "$TOKENIZER_WORKER" = 1 ] && [ "$MODE" != pipeline ] && [ "$MODE" != pipeline-trees ]; then
    echo "--tokenizer-worker needs --mode pipeline or pipeline-trees" >&2; exit 2
fi
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

# Only the Rust tokenizer is checked for staleness: it is the one compiled
# tokenizer. The jars and srcml2token are only checked for existence.
RUST_TOKENIZER_SOURCES=(
    "$CREGIT/tokenize/rustTokenizer/Makefile"
    "$CREGIT/tokenize/rustTokenizer/Cargo.toml"
    "$CREGIT/tokenize/rustTokenizer/Cargo.lock"
    "$CREGIT/tokenize/rustTokenizer/src"
)

NEEDS_BUILD_REASON=""
needs_build() {
    local artifact=$1; shift
    NEEDS_BUILD_REASON=""
    [ $# -gt 0 ] || die "needs_build: no sources declared for $artifact (a source list is missing)"
    local p
    for p in "$@"; do
        [ -e "$p" ] || die \
"declared source path does not exist: $p
     (it guards $artifact). Either the checkout is incomplete or the source list
     in this script is wrong. Refusing to guess: an unreadable source list
     silently stops guarding that artifact, which is how a 16-day-old
     rustTokenizer binary shipped a corpus of shifted token columns."
    done
    if [ ! -e "$artifact" ]; then
        NEEDS_BUILD_REASON="missing"
        return 0
    fi
    local newer
    newer=$(find "$@" -newer "$artifact" -print -quit 2>/dev/null) || true
    [ -n "$newer" ] || return 1
    NEEDS_BUILD_REASON="$newer"
    return 0
}

ensure_artifacts() {
    [ -x "$SRCML2TOKEN" ]      || build_srcml2token
    if needs_build "$RUST_TOKENIZER" "${RUST_TOKENIZER_SOURCES[@]}"; then
        log "artifact out of date ($NEEDS_BUILD_REASON): $RUST_TOKENIZER"
        build_rust_tokenizer
    fi
    [ -f "$BFG" ]              || build_blobexec
    [ -f "$SLICKGITLOG_JAR" ]  || build_legacy_jar slickGitLog
    [ -f "$PERSONS_JAR" ]      || build_legacy_jar persons
    [ -f "$REMAPCOMMITS_JAR" ] || build_legacy_jar remapCommits
}

if [ "$BUILD_ONLY" = 1 ]; then
    build_all
    exit 0
fi

# Auto-build any missing or out-of-date artifact BEFORE the work directory is
# touched, so a build failure never disturbs the outputs of a previous run.
ensure_artifacts

if [ "$ENSURE_ARTIFACTS_ONLY" = 1 ]; then
    log "artifacts up to date"
    exit 0
fi

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
        local marker memo
        if [ "$FORCE_CLEAN" = 1 ]; then
            log "Pipeline failed (exit $ec) — removing $WORK as --force-clean asks"
            rm -rf "$WORK"
            return 0
        fi
        if marker=$(keep_markers_present); then
            log "Pipeline failed (exit $ec) — keeping $WORK: $marker says the work is resumable"
            log "Resume with FROM_STEP=2 (runner: append '2'; ctp.py: --from-step 2),"
            log "or discard it deliberately with --force-clean."
            return 0
        fi
        # Same for the memo; the memo itself is the evidence, so there is no marker.
        if memo=$(memo_at_risk); then
            log "Pipeline failed (exit $ec) — keeping $WORK: $memo holds at least" \
                "$MEMO_KEEP_THRESHOLD memoized tokenizations"
            log "Move the memo out with --memo-dir, or discard it with --force-clean."
            return 0
        fi
        log "Pipeline failed (exit $ec) — removing $WORK for a clean restart"
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

# A full run starts clean; resuming (FROM_STEP >= 2) keeps existing work.
if [ "$FROM_STEP" = "1" ] && [ -d "$WORK" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
    if marker=$(keep_markers_present) && [ "$FORCE_CLEAN" != "1" ]; then
        die "refusing to delete $WORK: $marker
     That run stopped with work that is incomplete but resumable — the memo,
     the bare repos and the blob map in there are still good, and step 2 will
     retry only the blobs that failed.
     Resume (keeps the directory, retries the failed blobs):
       runner:  $0 --repo-url <url> --work $WORK [same flags] 2
       ctp.py (the external driver):  python3 ctp.py run [same flags] --from-step 2
     Start over and lose that work, deliberately:
       $0 --force-clean [same flags]
     (or remove $marker by hand)"
    fi
    # Losing a large memo is the second expensive mistake, and a healthy project
    # carries one, so no failure marker warns of it. A widened mask makes this
    # likely: blobExec refuses to resume against a different mask, so every re-run
    # is a FROM_STEP=1 run. Checked after the marker, so a resumable directory
    # still gets the more useful advice.
    if memo=$(memo_at_risk) && [ "$FORCE_CLEAN" != "1" ]; then
        die "refusing to delete $WORK: $memo holds at least $MEMO_KEEP_THRESHOLD memoized
     tokenizations, and deleting them means tokenizing this repository from cold.
$(memo_rescue_advice)"
    fi
    rm -rf "$WORK"
fi

LOG_FILE="${WORK}/pipeline.log"
# The memo directory must exist: tokenizeByBlobId/tokenBySha.pl refuses to run
# without it, and --memo-dir can place it outside $WORK.
mkdir -p "$MEMO_DIR" "$WORK/blame"
[ "$SKIP_HTML" = 1 ] || mkdir -p $WORK/html
exec > >(tee -a "$LOG_FILE") 2>&1

echo ""
echo "████████████████████████████████████████████████████████████████████████"
echo "  CreGit Pipeline — ${REPO_NAME} (tokenize mode: ${MODE}, file jobs: ${JOBS})"
echo "  Repo: ${REPO_GIT_URL}"
echo "  Mask: ${MASK}   Commit links: ${REPO_COMMIT_URL}"
if path_inside_work "$MEMO_DIR"; then
    echo "  Memo: ${MEMO_DIR}  (inside the work dir: a FROM_STEP=1 run deletes it)"
else
    echo "  Memo: ${MEMO_DIR}  (outside the work dir: survives a FROM_STEP=1 wipe)"
fi
echo "  Log: $LOG_FILE"
echo "████████████████████████████████████████████████████████████████████████"
echo ""

# ---------------------------------------------------------------------------
# --mask-widened / --retokenize: drop what a re-fold invalidates, keep what it
# reuses
# ---------------------------------------------------------------------------
#
# Both flags re-fold the whole history. A widening because every tree gains
# entries; an invalidation because a re-tokenized blob changes the tree above it,
# and blobExec empties tree_map and commit_map for exactly that reason. Either way
# every rewritten commit gets a new sha. Steps 3-10 are all derived from those shas,
# and two of them do NOT rebuild themselves, which makes this mandatory rather
# than tidy:
#
#   step 6  `git clone` into a directory that already exists is `fatal:
#           destination path already exists and is not an empty directory`.
#           Verified. Any resume over a project that once completed dies here.
#   step 7  blameRepoFiles.pl skips a file whose .blame output already exists
#           (--overwrite is off by default). Those files name cregit commit shas
#           from BEFORE the re-fold, so keeping them means step 10 joins blame
#           against commits that no longer exist. That failure is quiet, which
#           makes it worse than step 6's.
#
# Steps 3, 4 and 5 rebuild cleanly on their own (slickGitLog drops and recreates
# its schema), but their outputs are deleted anyway: "everything derived from the
# tokenized repository" is a rule someone can check, and "these three are
# self-cleaning and those two are not" is a rule that rots.
#
# KEPT, deliberately and by name: the original bare clone (step 2's input), the
# cregit bare repo (where blob_map's new_blob ids live), the blob map itself, and
# the memo. Those four are the entire point of the flag.
#
# Named paths only. No globs, and never $WORK itself — the wipe of $WORK is the
# expensive mistake this whole script is defended against.
drop_refold_derived_artifacts() {  # $1 = which flag is asking, for the log
    log "$1: dropping the artifacts derived from the tokenized repo,"
    log "  because the re-fold gives every cregit commit a new sha."
    log "  KEEPING: $REPO_PATH_ORIGINAL_BARE, $REPO_PATH_CREGIT_BARE,"
    log "           $DB_PATH_BLOBMAP, $MEMO_DIR"
    local _stale
    for _stale in \
        "$WORK/blame" \
        "$WORK/html" \
        "$REPO_PATH_ORIGINAL" \
        "$REPO_PATH_CREGIT" \
        "$DB_PATH_ORIGINAL" \
        "$DB_PATH_CREGIT" \
        "$DB_PATH_PERSONS" \
        "$XLS_PATH_PERSONS" \
        "$DATASET_PATH" \
        "${WORK}/${REPO_NAME}.validated"
    do
        if [ -e "$_stale" ]; then
            log "  dropping $_stale"
            rm -rf -- "$_stale"
        fi
    done
    mkdir -p "$WORK/blame"
    [ "$SKIP_HTML" = 1 ] || mkdir -p "$WORK/html"
}

if [ "$MASK_WIDENED" = 1 ]; then
    drop_refold_derived_artifacts "--mask-widened"
fi

# --retokenize does the same drop, but AFTER step 2 has actually invalidated
# something — see the end of step 2. blobExec refuses a --retokenize that would
# invalidate nothing, and doing the drop up front would mean a refused request had
# already deleted the blame output. Blame is this pipeline's bottleneck (measured:
# 8 files a minute, 5.6 days for the largest project), so destroying it to answer
# a request that was then rejected is the most expensive possible way to handle a
# typo in an extension name.

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

export BFG_MEMO_DIR="$MEMO_DIR"

# Route through the tokenize.pl dispatcher (not tokenizeSrcMl.pl directly) so it can
# fan out by language: srcML for .c/.h, rustTokenizer for .rs, etc. The
# --srcml2token / --ctags paths are forwarded only to the srcML parser.
export BFG_TOKENIZE_CMD="${CREGIT}/tokenize/tokenize.pl \
  --srcml2token=${SRCML2TOKEN} \
  --ctags=$(which ctags)"

# Lets blobExec refuse a cache built by a different tokenizer; fatal when empty,
# because an empty identity turns the check off.
TOKENIZER_IDENTITY=$(perl "${CREGIT}/tokenize/tokenizerIdentity.pl" \
    --srcml2token="${SRCML2TOKEN}" \
    --ctags="$(which ctags)") \
  || die "cannot compute the tokenizer identity (tokenize/tokenizerIdentity.pl).
     blobExec needs it to tell a cache built by this tokenizer from one built by a
     different one. Build the artifacts first: $0 --ensure-artifacts"
[ -n "$TOKENIZER_IDENTITY" ] \
  || die "tokenize/tokenizerIdentity.pl printed nothing; refusing to run with no tokenizer identity"
log "tokenizer identity: $TOKENIZER_IDENTITY"

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
  # Said out loud rather than left to be discovered. The tokenizer identity is not
  # plumbed through shard_build.sh / shard_merge.py, so the merged blobmap.db a
  # sharded build produces carries no tokenizer_id.* rows. A later non-sharded
  # resume over that database records the identity of whatever tokenizer is then
  # current, without being able to check it against the one that built it.
  log "note: --mode sharded does not record a tokenizer identity in the merged blob map."
  log "      A later resume cannot detect a tokenizer change against it. Non-sharded"
  log "      modes record and check it; see --retokenize."
else
  MODE_FLAG=""
  [ "$MODE" = "pipeline" ]       && MODE_FLAG="--pipeline"
  [ "$MODE" = "pipeline-trees" ] && MODE_FLAG="--pipeline-trees"
  WIDENED_FLAG=""
  [ "$MASK_WIDENED" = 1 ] && WIDENED_FLAG="--mask-widened"
  WORKER_FLAG=""
  [ "$TOKENIZER_WORKER" = 1 ] && WORKER_FLAG="--tokenizer-worker=${CREGIT}/tokenizeByBlobId/tokenWorker.pl"
  # --retokenize carries --memo-dir with it, always, and blobExec refuses one
  # without the other: the blob map and the memo are two caches of the same
  # answer, and invalidating either alone invalidates nothing.
  RETOKENIZE_FLAGS=()
  if [ -n "$RETOKENIZE" ]; then
      RETOKENIZE_FLAGS=("--retokenize=$RETOKENIZE" "--memo-dir=$MEMO_DIR")
  fi
  java -jar "$BFG" $MODE_FLAG $WIDENED_FLAG ${WORKER_FLAG:+"$WORKER_FLAG"} \
    "--tokenizer-identity=$TOKENIZER_IDENTITY" \
    ${RETOKENIZE_FLAGS[@]+"${RETOKENIZE_FLAGS[@]}"} \
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

# Step 2 finished, so the markers must go: they would block every later
# FROM_STEP=1 run, and ctp.py has no --force-clean passthrough.
for _m in $KEEP_MARKERS; do
    if [ -e "${WORK}/${_m}" ]; then
        log "tokenize completed — clearing ${WORK}/${_m}"
        rm -f "${WORK}/${_m}"
    fi
done

# Only after blobExec accepted the invalidation: a refused one (exit 7) must not
# have cost the blame output.
if [ -n "$RETOKENIZE" ]; then
    drop_refold_derived_artifacts "--retokenize $RETOKENIZE"
fi

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
# A missing LFS object fails checkout, and blame and HTML read only mask-matched
# source, never LFS payloads.
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
# Without --overwrite, a re-blame skips every file that has .blame output.
REBLAME_OPTS=()
[ "$REBLAME" = 1 ] && REBLAME_OPTS+=(--overwrite)
[ "$REBLAME" = 1 ] && log "--reblame: replacing every .blame file, not resuming"
perl $CREGIT/blameRepo/blameRepoFiles.pl \
  --jobs="$JOBS" \
  --formatBlame=$CREGIT/blameRepo/formatBlame.pl \
  ${REBLAME_OPTS[@]+"${REBLAME_OPTS[@]}"} \
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
# Step 9 — generate HTML views (skippable: nothing downstream reads $WORK/html)
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
build_dataset_argv
"$PYTHON" "$DATASET_SCRIPT" "${DATASET_ARGV[@]}"
log "dataset written: $DATASET_PATH"
elif [ "$SKIP_HTML" = 1 ]; then
die "python3 with the duckdb module is unavailable (provided by devenv shell) and --skip-html suppressed the HTML views — this run produced no final artifact"
else
log "skip: python3 with the duckdb module is unavailable (provided by devenv shell); HTML views in $WORK/html are the final output"
fi
fi
end_step
