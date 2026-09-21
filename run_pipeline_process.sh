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
  --mask REGEX      regex selecting the files to tokenize; quote it
                    (default: '\.[ch]$' — C sources and headers.
                    Tokenizers exist for C, C++, Java, Rust and m4 files)
  --work DIR        working/output directory (default: ../cregit-files).
                    NOTE: a full run (FROM_STEP=1) starts by deleting this
                    directory; use one directory per target repository.

Output:
  --skip-html       do not generate the HTML views (step 9). The views cost
                    94-255 MB per project and the dataset generator does not
                    read them, so a run that only wants the Parquet dataset
                    should skip them. NOTE: the HTML views are the fallback
                    output when python3+duckdb is missing, so --skip-html
                    without duckdb leaves the run with no final artifact.
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

Tokenizer:
  --mode MODE   tokenizer walk mode (default: pipeline)
                  serial          single-threaded reference walker
                  pipeline        look-ahead parallel walker (fastest for normal repos)
                  pipeline-trees  parallel walker + parallel tree assembly
                  sharded         N memory-bounded shards -> merge + serial re-fold,
                                  for repos too large to tokenize in one process;
                                  delegates to blobExec/shard_build.sh
  --shards N    shard count for --mode sharded (default: 4)
  --blob-timeout SECONDS
                wall-clock budget for tokenizing one blob (default: blobExec's
                own 600). A tokenizer that exceeds it is killed and the run
                stops with exit 4, leaving that blob to be retried by a re-run
                from step 2. Raise this when a blob keeps timing out because it
                is genuinely large; the stall window below is raised with it
                automatically. Exit 4 and exit 5 keep --work rather than deleting
                it, because the re-run resumes from the memo there.
  --stall-timeout SECONDS
                window with no completed work after which the run is declared
                stuck and killed (default: blobExec's own 1800, or one blob
                lifetime, whichever is larger). It must exceed --blob-timeout,
                because a commit whose last blob is slow completes nothing until
                that blob is killed.
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

# Stop, but keep $WORK, and exit with the status the failing tool reported.
# For a retryable failure the work directory IS the recovery: blobExec's memo,
# the partly tokenized repo and the marker file all live there, and re-running
# the same command is the documented fix. `die` would instead leave the cleanup
# trap to delete every commit the run had already folded.
die_retryable() {
    KEEP_WORK=1
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
REPO_GIT_URL=""
REPO_NAME=""
REPO_COMMIT_URL=""
MASK='\.[ch]$'
WORK="../cregit-files"
SKIP_HTML=0
GC_MODE="plain"
MEMORY_LIMIT=""
DUCKDB_THREADS=""
BLOB_TIMEOUT=""
STALL_TIMEOUT=""
KEEP_WORK=0

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
        --gc)         need_val "$@"; GC_MODE="$2"; shift 2 ;;
        --memory-limit)   need_val "$@"; MEMORY_LIMIT="$2"; shift 2 ;;
        --duckdb-threads) need_val "$@"; DUCKDB_THREADS="$2"; shift 2 ;;
        --mode)       need_val "$@"; MODE="$2"; shift 2 ;;
        --shards)     need_val "$@"; SHARDS="$2"; shift 2 ;;
        --blob-timeout)  need_val "$@"; BLOB_TIMEOUT="$2"; shift 2 ;;
        --stall-timeout) need_val "$@"; STALL_TIMEOUT="$2"; shift 2 ;;
        --jobs)       need_val "$@"; JOBS="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        ''|*[!0-9]*) echo "unknown argument: $1" >&2; usage; exit 2 ;;
        *) FROM_STEP="$1"; shift ;;
    esac
done

# Validate early: pack_cregit_repo runs only once tokenizing has finished.
case "$GC_MODE" in
    none|plain|aggressive) ;;
    *) echo "invalid --gc: '$GC_MODE' (want none, plain or aggressive)" >&2; exit 2 ;;
esac

# Validate early: blobExec rejects these too, but step 2 can be an hour of
# cloning away, and a typo must not cost that hour.
for tmo in "--blob-timeout:$BLOB_TIMEOUT" "--stall-timeout:$STALL_TIMEOUT"; do
    case "${tmo#*:}" in
        '') ;;
        0|*[!0-9]*) echo "invalid ${tmo%%:*}: '${tmo#*:}' (want a positive whole number of seconds)" >&2; exit 2 ;;
    esac
done

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

# needs_build <artifact> <source path>...: true when the artifact is missing, or
# when any source is newer than it. Staleness has to count, not just absence: a
# jar left behind by another branch's checkout is reused otherwise, and the
# pipeline then runs that branch's code without saying so.
needs_build() {
    artifact=$1
    shift
    [ -e "$artifact" ] || return 0
    [ -n "$(find "$@" -type f -newer "$artifact" -print -quit 2>/dev/null)" ]
}

# Build only what is missing or out of date, so an up-to-date checkout starts
# instantly. Each list names sources only, never a target/ directory: build
# output is newer than the artifact by definition and would always look stale.
ensure_artifacts() {
    S="$CREGIT/tokenize/srcMLtoken"
    R="$CREGIT/tokenize/rustTokenizer"
    needs_build "$SRCML2TOKEN"      "$S"/*.cpp "$S"/*.hpp "$S/Makefile"          && build_srcml2token
    needs_build "$RUST_TOKENIZER"   "$R/src" "$R/Cargo.toml" "$R/Cargo.lock"     && build_rust_tokenizer
    needs_build "$BFG"              "$CREGIT/blobExec/src" "$CREGIT/blobExec/build.sbt" "$CREGIT/blobExec/project" && build_blobexec
    needs_build "$SLICKGITLOG_JAR"  "$CREGIT/slickGitLog/src" "$CREGIT/slickGitLog/build.sbt"   && build_legacy_jar slickGitLog
    needs_build "$PERSONS_JAR"      "$CREGIT/persons/src" "$CREGIT/persons/build.sbt"           && build_legacy_jar persons
    needs_build "$REMAPCOMMITS_JAR" "$CREGIT/remapCommits/src" "$CREGIT/remapCommits/build.sbt" && build_legacy_jar remapCommits
    return 0   # a false needs_build above must not fail the function under `set -e`
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
    if [ $ec -ne 0 ] && [ "$KEEP_WORK" = 1 ]; then
        log "Pipeline stopped (exit $ec) — keeping $WORK so a re-run can resume"
    elif [ $ec -ne 0 ] && [ "$FROM_STEP" = "1" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
        log "Pipeline failed (exit $ec) — removing $WORK for a clean restart"
        rm -rf "$WORK"
    fi
}
trap cleanup EXIT

# A full run starts clean; resuming (FROM_STEP >= 2) keeps existing work.
if [ "$FROM_STEP" = "1" ] && [ -d "$WORK" ] && [ -n "$WORK" ] && [ "$WORK" != "/" ]; then
    rm -rf "$WORK"
fi

LOG_FILE="${WORK}/pipeline.log"
mkdir -p $WORK/memo $WORK/blame
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

# Omitted flags leave blobExec on its own defaults, so the two tools never
# disagree about what the default is.
TIMEOUT_FLAGS=()
[ -n "$BLOB_TIMEOUT" ]  && TIMEOUT_FLAGS+=("--blob-timeout=$BLOB_TIMEOUT")
[ -n "$STALL_TIMEOUT" ] && TIMEOUT_FLAGS+=("--stall-timeout=$STALL_TIMEOUT")

if [ "$MODE" = "sharded" ]; then
  # Memory-bounded path: N tree-only shards in parallel, then merge + serial
  # re-fold into $SHARD_OUT/final/{dst.git,blobmap.db} (byte-identical to serial).
  SHARD_ARGV=(
    --src "$REPO_PATH_ORIGINAL_BARE"
    --out "$SHARD_OUT"
    --shards "$SHARDS"
    --jar "$BFG"
    --command "${CREGIT}/tokenizeByBlobId/tokenBySha.pl"
    --mask "$MASK"
    --tok-cmd "$BFG_TOKENIZE_CMD"
  )
  [ -n "$BLOB_TIMEOUT" ]  && SHARD_ARGV+=(--blob-timeout "$BLOB_TIMEOUT")
  [ -n "$STALL_TIMEOUT" ] && SHARD_ARGV+=(--stall-timeout "$STALL_TIMEOUT")
  # A shard that times out exits 4 and shard_build.sh forwards it, so the same
  # retry advice as the single-process path applies.
  SHARD_RC=0
  "${CREGIT}/blobExec/shard_build.sh" "${SHARD_ARGV[@]}" || SHARD_RC=$?
  if [ "$SHARD_RC" -eq 4 ]; then
    die_retryable 4 "a tokenize shard left blobs untokenized (exit 4). Refusing
     to continue: the dataset would carry raw source in place of tokens. $WORK is
     kept. Recovery: re-run the same command with a trailing 2 (resume from step
     2) — the shard resumes and retries just those blobs. Re-running from step 1
     deletes $WORK instead. If they keep timing out, add --blob-timeout SECONDS."
  elif [ "$SHARD_RC" -ne 0 ]; then
    die "sharded tokenize failed (shard_build.sh exit $SHARD_RC)"
  fi
else
  MODE_FLAG=""
  [ "$MODE" = "pipeline" ]       && MODE_FLAG="--pipeline"
  [ "$MODE" = "pipeline-trees" ] && MODE_FLAG="--pipeline-trees"
  # Capture the status instead of letting `set -e` take it. Two statuses need
  # their own message:
  #   4 = a blob's tokenizer was killed on its budget, so those files would hold
  #       raw source instead of tokens. The walk stopped at that commit and
  #       recorded nothing for it, so re-running retries just those blobs. Stop
  #       here: steps 3-10 would otherwise build and validate an incomplete
  #       dataset.
  #   5 = the stall watchdog killed a run that stopped making progress.
  BFG_RC=0
  java -jar "$BFG" $MODE_FLAG "${TIMEOUT_FLAGS[@]+"${TIMEOUT_FLAGS[@]}"}" \
    "$REPO_PATH_ORIGINAL_BARE" \
    "$REPO_PATH_CREGIT_BARE" \
    "$DB_PATH_BLOBMAP" \
    "${CREGIT}/tokenizeByBlobId/tokenBySha.pl" \
    "$MASK" || BFG_RC=$?
  if [ "$BFG_RC" -eq 4 ]; then
    date -u +"%Y-%m-%dT%H:%M:%SZ" > "${WORK}/TOKENIZE-TIMEOUTS"
    echo "blobExec exited 4: at least one blob timed out; see the blobsTimedOut" \
         "count and the 'will retry on the next run' lines in this step's log." \
         "Nothing was recorded for the containing commit, so resuming from step 2" \
         "retries those blobs." >> "${WORK}/TOKENIZE-TIMEOUTS"
    die_retryable 4 "tokenize left blobs untokenized (blobExec exit 4). Refusing
     to continue: the dataset would carry raw source in place of tokens. Marker
     written to ${WORK}/TOKENIZE-TIMEOUTS, and $WORK is kept. Recovery: re-run
     the same command with a trailing 2 (resume from step 2) — the timed-out
     blobs are retried and no database surgery is needed. Do NOT re-run from
     step 1: a full run starts by deleting $WORK, which is where the memo that
     makes the retry cheap lives. If the blobs keep timing out, the tokenizer is
     too slow for them: add --blob-timeout SECONDS."
  elif [ "$BFG_RC" -eq 5 ]; then
    date -u +"%Y-%m-%dT%H:%M:%SZ" > "${WORK}/TOKENIZE-STALLED"
    echo "blobExec exited 5: the stall watchdog fired. The STALLED line in this" \
         "step's log names the work that was in flight." >> "${WORK}/TOKENIZE-STALLED"
    die_retryable 5 "tokenize stalled and was killed by blobExec's watchdog
     (exit 5). The memo is durable and $WORK is kept, so re-running the same
     command with a trailing 2 (resume from step 2) continues from where it
     stopped; re-running from step 1 deletes $WORK instead. The STALLED line in
     this step's log names the blobs that were in flight; if one of them is
     pathological, add --blob-timeout SECONDS (which raises the stall window
     with it) or exclude it via --mask."
  elif [ "$BFG_RC" -ne 0 ]; then
    die "tokenize failed (blobExec exit $BFG_RC)"
  fi
fi

[ -d "$REPO_PATH_CREGIT_BARE" ] || die "tokenize did not produce $REPO_PATH_CREGIT_BARE"
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
git clone $REPO_PATH_ORIGINAL_BARE $REPO_PATH_ORIGINAL
git clone $REPO_PATH_CREGIT_BARE $REPO_PATH_CREGIT
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
