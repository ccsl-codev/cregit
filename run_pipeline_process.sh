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

Tokenizer:
  --mode MODE   tokenizer walk mode (default: pipeline)
                  serial          single-threaded reference walker
                  pipeline        look-ahead parallel walker (fastest for normal repos)
                  pipeline-trees  parallel walker + parallel tree assembly
                  sharded         N memory-bounded shards -> merge + serial re-fold,
                                  for repos too large to tokenize in one process;
                                  delegates to blobExec/shard_build.sh
  --shards N    shard count for --mode sharded (default: 4)
  --jobs N      concurrent blame/HTML processes
                (default: CREGIT_JOBS, otherwise min(4, available CPUs))
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
        --mode)       need_val "$@"; MODE="$2"; shift 2 ;;
        --shards)     need_val "$@"; SHARDS="$2"; shift 2 ;;
        --jobs)       need_val "$@"; JOBS="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        ''|*[!0-9]*) echo "unknown argument: $1" >&2; usage; exit 2 ;;
        *) FROM_STEP="$1"; shift ;;
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
mkdir -p $WORK/memo $WORK/blame $WORK/html
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

if [ "$MODE" = "sharded" ]; then
  # Memory-bounded path: N tree-only shards in parallel, then merge + serial
  # re-fold into $SHARD_OUT/final/{dst.git,blobmap.db} (byte-identical to serial).
  "${CREGIT}/blobExec/shard_build.sh" \
    --src "$REPO_PATH_ORIGINAL_BARE" \
    --out "$SHARD_OUT" \
    --shards "$SHARDS" \
    --jar "$BFG" \
    --command "${CREGIT}/tokenizeByBlobId/tokenBySha.pl" \
    --mask "$MASK" \
    --tok-cmd "$BFG_TOKENIZE_CMD"
else
  MODE_FLAG=""
  [ "$MODE" = "pipeline" ]       && MODE_FLAG="--pipeline"
  [ "$MODE" = "pipeline-trees" ] && MODE_FLAG="--pipeline-trees"
  java -jar "$BFG" $MODE_FLAG \
    "$REPO_PATH_ORIGINAL_BARE" \
    "$REPO_PATH_CREGIT_BARE" \
    "$DB_PATH_BLOBMAP" \
    "${CREGIT}/tokenizeByBlobId/tokenBySha.pl" \
    "$MASK"
fi

[ -d "$REPO_PATH_CREGIT_BARE" ] || die "tokenize did not produce $REPO_PATH_CREGIT_BARE"
git --git-dir="$REPO_PATH_CREGIT_BARE" reflog expire --expire=now --all
git --git-dir="$REPO_PATH_CREGIT_BARE" gc --prune=now --aggressive
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
perl $CREGIT/blameRepo/blameRepoFiles.pl --verbose \
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
# ---------------------------------------------------------------------------
step "generate HTML views"
if [ "$STEP_NUM" -ge "$FROM_STEP" ]; then
[ -f "$DB_PATH_CREGIT" ] || die "step 8 did not complete"
perl $CREGIT/prettyPrint/prettyPrintFiles.pl --verbose \
  --jobs="$JOBS" \
  $DB_PATH_CREGIT $DB_PATH_PERSONS \
  $REPO_PATH_ORIGINAL $WORK/blame $WORK/html \
  $REPO_COMMIT_URL "$MASK"
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
"$PYTHON" "$DATASET_SCRIPT" \
  --blame-dir  "$WORK/blame" \
  --source-dir "$REPO_PATH_ORIGINAL" \
  --cregit-db  "$DB_PATH_CREGIT" \
  --persons-db "$DB_PATH_PERSONS" \
  --output     "$DATASET_PATH" \
  --repo-name  "$REPO_NAME" \
  --verbose
log "dataset written: $DATASET_PATH"
else
log "skip: python3 with the duckdb module is unavailable (provided by devenv shell); HTML views in $WORK/html are the final output"
fi
fi
end_step
