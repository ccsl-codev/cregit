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
                    the newly selected files. See --mask-widened for the one
                    verified way past that.
  --mask-widened    resume across a mask change instead of rebuilding, keeping
                    the tokenizations already in blob_map. REQUIRES FROM_STEP>=2,
                    because a step-1 run deletes $WORK — blob map, cregit.git and
                    all — and there is then nothing to reuse.
                    Only the mask is relaxed. The tokenizer command is still
                    checked, and blobExec still verifies, against the rows
                    themselves, that (1) every already-tokenized path is still
                    selected by the new mask, and (2) the retained new_blob ids
                    actually exist in the cregit bare repo. Either check failing
                    refuses the run (exit 3) and changes nothing.
                    Safe because the mask decides WHICH files are tokenized and
                    never HOW: the language comes from the file's extension, per
                    file (tokenize/CregitLanguages.pm), so the same blob at the
                    same path yields the same tokens under any mask selecting it.
                    tree_map, commit_map and ref_map are discarded (trees gain
                    entries, commits name trees, refs name commits), and so are
                    blob_map's identity rows — those say "not selected, bytes pass
                    through", and a wider mask selects some of those paths, where
                    reusing the row would leave RAW SOURCE in the tokenized repo.
                    Not available with --mode sharded: each shard builds a fresh
                    blob map, so there is no recorded mask to widen.
  --retokenize EXTS comma-separated extensions whose CACHED TOKENIZATIONS are to
                    be thrown away and redone, because the tokenizer that made
                    them has changed: --retokenize rs, --retokenize c,h.
                    Lowercase, no dots, as spelled in tokenize/CregitLanguages.pm.
                    REQUIRES FROM_STEP=2 exactly: step 1 deletes $WORK, so there
                    would be nothing cached left to invalidate, and step 3 or
                    later skips the invalidation entirely and would run the rest
                    of the pipeline over the poisoned tokens. Not available with
                    --mode sharded.

                    Why it exists. blobExec decides whether to reuse a cached
                    tokenization from the recorded command and mask. The command
                    is the constant path tokenizeByBlobId/tokenBySha.pl and the
                    mask says WHICH files to tokenize, never HOW — so when a
                    tokenizer is corrected, neither value moves and every cached
                    row for that language stays a cache hit. Measured: a
                    rustTokenizer binary 16 days older than its own source kept
                    emitting a line:col prefix, and 741,869 .rs entries across
                    45 projects carried it with no error anywhere.

                    Every run now reports a per-extension tokenizer identity
                    (tokenize/tokenizerIdentity.pl: a digest of that extension's
                    whole parser toolchain). blobExec records it and compares it,
                    and a mismatch REFUSES the run, naming the extensions and
                    this flag. Nothing is invalidated without this flag, so the
                    186 published projects are untouched.

                    SELECTIVE, on measurement: re-tokenizing is 88% of total
                    pipeline time, so a .rs-only defect costs .rs entries only.
                    Everything else in blob_map survives. tree_map, commit_map
                    and ref_map do not — a tree names its blobs, and a kept
                    tree_map row would short-circuit the re-walk — but rebuilding
                    those is a walk, not a tokenize.

                    BOTH cache layers go together, and neither can be done
                    without the other: the blob_map rows AND their entries in the
                    memo, which is keyed on sha1 of the file's CONTENT with no
                    tokenizer in the key. Dropping only the blob_map row would
                    make the walker re-run the tokenizer command and the memo
                    would answer it with the same stale tokens.

                    It CANNOT quietly do nothing: blobExec refuses, before
                    changing anything, if no cached row carries a named
                    extension, if the memo held none of the affected blobs, if
                    another extension's tokenizer also changed and was not named,
                    or if a retained new_blob id is missing from cregit.git.
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
                    tokenize back into a commit walk. torvalds__linux's memo
                    holds ~2.6 million entries against 3,228,137 blobs.
                    ONE DIRECTORY PER REPOSITORY. The memo key is sha1 of the
                    file's CONTENT with no extension in it, so two repositories
                    sharing a directory would serve each other's entries, and
                    identical content under a different extension is a different
                    language and different tokens.
                    A step-1 wipe that would delete a memo holding 10,000 entries
                    or more (MEMO_KEEP_THRESHOLD, overridable as
                    CREGIT_MEMO_KEEP_THRESHOLD) is refused; see --force-clean.
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
                    TOKENIZE-TIMEOUTS or TOKENIZE-STALLED marker, or a memo big
                    enough to be worth keeping. Without this the runner refuses:
                    those markers mean the work is incomplete but resumable at
                    step 2, and deleting it means re-tokenizing everything to
                    retry a few blobs, while deleting a large memo means
                    re-tokenizing from cold what is already tokenized.

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
  --firm-map PATH   forward --firm-map to step 10: the domain->firm CSV
                    (cregit-token-pipeline/data/affiliation.merged.csv). Unlike
                    the sidecar this is joined PER ROW against person_domain, so
                    it fills firm_raw and firm_source. Omit and those columns are
                    empty strings, so the schema is unchanged either way.
  --firm-canonical PATH
                    forward --firm-canonical to step 10: the reviewed
                    canonical-name table (data/firm_canonical.csv) that fills the
                    `firm` column. Needs --firm-map. Omit and `firm` repeats
                    `firm_raw`, so split spellings of one firm stay split.

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
# 1 means "run ensure_artifacts and exit". Distinct from --build-only, which
# rebuilds all six unconditionally: this one runs exactly the guard a normal run
# runs, so it is also how that guard is tested.
ENSURE_ARTIFACTS_ONLY=0
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
# Empty means "<work>/memo", resolved after argument parsing because it depends on
# --work. A caller that wants the memo to survive a FROM_STEP=1 wipe passes a
# directory outside $WORK; nothing else about the run changes.
MEMO_DIR=""
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
# Empty means "do not pass the flag", as above. The difference from the sidecar is
# that these two are joined per row rather than injected as constants, so they
# change what every row says rather than what every row repeats.
FIRM_MAP=""
FIRM_CANONICAL=""
FORCE_CLEAN=0
# 1 means "pass --mask-widened to blobExec", which is the ONLY way a mask change
# resumes instead of rebuilding. Off by default and it must stay that way: the
# refusal is correct for every case except a verified widening, and blobExec does
# the verifying against the rows rather than trusting this flag.
MASK_WIDENED=0
# Empty means "do not pass --retokenize", so no cached tokenization is ever
# invalidated unless an operator asked for it by extension. Off by default and it
# must stay that way: 186 projects are published against the caches this would
# delete.
RETOKENIZE=""
# Empty means "do not pass the flag", so blobExec keeps its own defaults (600s
# per blob, 1800s stall window). The CREGIT_* environment fallbacks exist so the
# values are reachable through ctp.py, which has no passthrough of its own but
# does hand its environment to this script.
BLOB_TIMEOUT="${CREGIT_BLOB_TIMEOUT:-}"
STALL_TIMEOUT="${CREGIT_STALL_TIMEOUT:-}"

# Markers meaning "the work in $WORK is incomplete but recoverable, and a
# FROM_STEP=1 wipe would throw away days of tokenizing to redo it". Written by
# step 2 when blobExec reports a timed-out blob (exit 4), a stall (exit 5), or a
# parser crash (exit 6); see keep_markers_present and --force-clean.
KEEP_MARKERS="TOKENIZE-TIMEOUTS TOKENIZE-STALLED TOKENIZE-PARSER-CRASHES"

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

# How many memoized tokenizations make a memo directory too expensive to delete
# on a default flag. A memo hit returns without invoking srcml at all
# (tokenizeByBlobId/tokenBySha.pl), so the entries ARE the tokenizing already
# done. Measured on this machine, 2026-09-19, non-empty memos in corpus-files:
# jq 1,813 | libuv 13,381 | zstd 15,091 | tmux 26,976 |
# tencent__tencentkona-21 89,453 | torvalds__linux ~2,600,000 (>= 200,001 counted).
# 10,000 sits above the smallest of those and far below the two that matter.
# Overridable so the guard can be tested without planting 10,000 files.
MEMO_KEEP_THRESHOLD="${CREGIT_MEMO_KEEP_THRESHOLD:-10000}"

# Canonical form of a path, whether or not it exists yet. Used to decide whether
# the memo sits inside $WORK: a textual comparison answers that wrongly whenever
# the two are spelled differently (one relative, one absolute), and the wrong
# answer here is a silently deleted memo.
canonical_path() {
    readlink -f -- "$1" 2>/dev/null || printf '%s' "$1"
}

# memo_entries_at_least <dir> <n>: true when <dir> holds at least <n> entries.
# Counts at most <n> and stops: torvalds__linux's memo holds ~2.6 million files,
# and a full count would be minutes of stat() before the run has even started.
# find dies of SIGPIPE when head closes the pipe, which `|| true` absorbs so
# `set -o pipefail` does not abort the script.
memo_entries_at_least() {
    local dir=$1 n=$2 count
    [ -d "$dir" ] || return 1
    # Entries are <memo>/xx/yy/<sha1-of-contents>, so depth 3 counts memoized
    # tokenizations and nothing else.
    count=$( { find "$dir" -mindepth 3 -maxdepth 3 -type f -print 2>/dev/null || true; } \
             | head -n "$n" | wc -l )
    [ "$count" -ge "$n" ]
}

# True when the memo lives inside $WORK, so a wipe of $WORK would take it too.
memo_inside_work() {
    local cw cm
    cw=$(canonical_path "$WORK")
    cm=$(canonical_path "$MEMO_DIR")
    case "$cm" in
        "$cw"|"$cw"/*) return 0 ;;
        *) return 1 ;;
    esac
}

# Prints the memo directory and returns 0 when deleting $WORK would destroy a
# memo worth keeping. The partner's instruction was "create exceptions for the
# rm -rf for the Linux run": losing 2.6 million memoized tokenizations must not
# be possible by leaving a flag off.
memo_at_risk() {
    [ -n "$MEMO_DIR" ] || return 1
    memo_inside_work || return 1
    memo_entries_at_least "$MEMO_DIR" "$MEMO_KEEP_THRESHOLD" || return 1
    printf '%s' "$MEMO_DIR"
}

# The recovery the guard prints, and the whole reason --memo-dir exists.
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
    elif [ "$rc" -eq 6 ]; then
        date -u +"%Y-%m-%dT%H:%M:%SZ" > "${WORK}/TOKENIZE-PARSER-CRASHES"
        echo "$what exited 6: at least one blob's tokenizer reported a parser" \
             "crash (srcML died on a signal, or produced no tokens). See the" \
             "blobsParserCrashed count and the 'reported a parser crash' lines in" \
             "this step's log. Nothing was recorded for the containing commit." \
             "This is deterministic: re-running alone will NOT clear it." \
             >> "${WORK}/TOKENIZE-PARSER-CRASHES"
        die "$what hit a parser crash (exit 6). Refusing to continue: before this
     was detected, such a blob became a silent 0-byte tokenization and the file
     vanished from the dataset with nothing counting it. Marker written to
     ${WORK}/TOKENIZE-PARSER-CRASHES.
     This is NOT slowness and --blob-timeout will not help: srcML 1.1.0 faults in
     its C/C++ position tracking, and --position cannot be dropped because the
     token format depends on it. Re-running alone will not clear it either,
     because the crash is deterministic.
     The 'reported a parser crash' lines in this step's log name each blob with
     its path and signal. Either fix/upgrade srcML, or add those blobs to the
     blob denylist with a reason and citation so they are excluded explicitly
     and reported without blocking publication.
     If you do denylist them, resume at step 2 to keep the memo:
$resume_lines"
    elif [ "$rc" -eq 7 ]; then
        # No marker file, deliberately. The markers mean "work in $WORK is
        # incomplete but resumable and a step-1 wipe would destroy it". This is the
        # opposite situation: nothing was started and nothing was changed. The run
        # simply must not be read as a success, which is what the non-zero exit and
        # this message are for.
        die "$what refused the invalidation and changed nothing (exit 7).
     --retokenize was asked for and would have invalidated NOTHING, so blobExec
     stopped instead of running the walk and exiting 0. The Error line above this
     says which of the reasons it was: no cached row carries a named extension, or
     the memo at --memo-dir held none of the affected blobs.
     The point of the refusal: an invalidation that quietly invalidates nothing is
     indistinguishable in a log from one that worked, and the dataset then ships
     with the tokens the flag was meant to remove.
     Check the extension spelling (lowercase, no dot, as in
     tokenize/CregitLanguages.pm), that --memo-dir names THIS project's memo
     ($MEMO_DIR), and that this work directory is the one holding the poisoned
     entries. Nothing in $WORK has been modified."
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
        --ensure-artifacts) ENSURE_ARTIFACTS_ONLY=1; shift ;;
        --repo-url)   need_val "$@"; REPO_GIT_URL="$2"; shift 2 ;;
        --repo-name)  need_val "$@"; REPO_NAME="$2"; shift 2 ;;
        --commit-url) need_val "$@"; REPO_COMMIT_URL="$2"; shift 2 ;;
        --mask)       need_val "$@"; MASK="$2"; shift 2 ;;
        --work)       need_val "$@"; WORK="$2"; shift 2 ;;
        --memo-dir)   need_val "$@"; MEMO_DIR="$2"; shift 2 ;;
        --skip-html)  SKIP_HTML=1; shift ;;
        --force-clean) FORCE_CLEAN=1; shift ;;
        --mask-widened) MASK_WIDENED=1; shift ;;
        --retokenize) need_val "$@"; RETOKENIZE="$2"; shift 2 ;;
        --blob-timeout)  need_val "$@"; BLOB_TIMEOUT="$2"; shift 2 ;;
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

# --mask-widened only means anything on a resume that KEEPS the work directory.
# A step-1 run deletes $WORK first, taking the blob map and the cregit bare repo
# with it, so the flag would silently do nothing while the operator believed days
# of tokenizing were being reused. Refuse instead of being a quiet no-op: the
# whole value of the flag is the work it preserves, and "it ran and preserved
# nothing" is indistinguishable from success in the log.
if [ "$MASK_WIDENED" = 1 ] && [ "$FROM_STEP" = "1" ]; then
    echo "--mask-widened needs FROM_STEP>=2. A step-1 run starts by deleting $WORK, so the
     blob map it would reuse and the cregit.git its new_blob ids live in are both
     gone before blobExec starts, and the flag would preserve nothing.
     Resume instead:
       runner:  $0 --repo-url <url> --work $WORK --mask-widened [same flags] 2
       ctp.py:  python3 ./ctp.py run [same flags] --mask-widened --from-step 2" >&2
    exit 2
fi

# Each shard builds its own fresh dst.git and blobmap.db, so there is never a
# recorded mask for --mask-widened to widen. blobExec refuses the combination too;
# catching it here means the operator hears about it before the clone.
if [ "$MASK_WIDENED" = 1 ] && [ "$MODE" = "sharded" ]; then
    echo "--mask-widened is not available with --mode sharded: every shard builds a fresh
     blob map, so no recorded mask exists to widen. Reuse a prior run's
     tokenizations with shard_build.sh --warm-db instead." >&2
    exit 2
fi

# --retokenize invalidates CACHED work, so like --mask-widened it only means
# anything on a resume that keeps the work directory. A step-1 run deletes $WORK —
# blob map, memo and cregit.git — so there would be nothing left to invalidate and
# the flag would be a quiet no-op while the operator believed the poisoned entries
# had been removed. That is the one thing this flag must never be.
#
# And it must be EXACTLY 2, not "2 or more": the invalidation happens inside step
# 2, so --retokenize with FROM_STEP=3 would skip it entirely, run steps 3-10 over
# the poisoned tokens and exit 0. A flag that cannot quietly do nothing cannot be
# allowed to be skipped either.
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

# Each shard builds its own fresh dst.git and blobmap.db, so a shard has no cached
# tokenization to invalidate. blobExec refuses the combination too; catching it
# here means the operator hears about it before the clone.
if [ -n "$RETOKENIZE" ] && [ "$MODE" = "sharded" ]; then
    echo "--retokenize is not available with --mode sharded: every shard builds a fresh
     blob map, so there are no cached tokenizations for it to invalidate. Retokenize
     the merged result in a non-sharded step-2 resume, or drop the shards' warm db." >&2
    exit 2
fi

# One verified invalidation per run. Each of the two verifies its own precondition
# against the rows themselves, and run together each would be verifying against a
# state the other is about to change: a widening reasons about identity rows on the
# assumption the tokenizations are valid, an invalidation reasons about which
# tokenizations to drop on the assumption the mask has not moved. blobExec refuses
# the combination too; catching it here means it is caught before the clone.
if [ -n "$RETOKENIZE" ] && [ "$MASK_WIDENED" = 1 ]; then
    echo "--mask-widened and --retokenize cannot be used in the same run. Each verifies its
     own precondition against the blob map's rows, and together each would verify
     against a state the other is about to change.
     Do them one at a time: widen first, then resume again with --retokenize." >&2
    exit 2
fi

# Refuse a malformed extension list here rather than after the clone. Same
# alphabet blobExec's TokenizerIdentity accepts, and the same reason: a typo that
# silently selected nothing would be a no-op invalidation.
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

# Same again for the firm map. Worse than the sidecar if it slips through: a
# missing map does not fail, it silently publishes blank firm columns, and firm
# attribution is what this corpus is built to measure.
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
    if memo_inside_work; then
        log "warning: --memo-dir $MEMO_DIR is inside $WORK, so a FROM_STEP=1 run still deletes it"
        log "warning: pass a directory outside $WORK for a memo that survives the wipe"
    fi
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

# ---------------------------------------------------------------------------
# Staleness — which sources decide whether the Rust tokenizer is current.
#
# ONLY the Rust tokenizer, deliberately and on a decision. It is the single
# COMPILED tokenizer in the pipeline: CregitLanguages.pm:109-115 routes C, C++
# and Java to tokenizeSrcMl.pl and M4 to m4Tokenizer/m4.py, which are
# interpreted and therefore read fresh from disk on every invocation. An
# interpreted tokenizer cannot go stale the way a cargo artifact can. (The
# srcml2token binary those scripts shell out to is compiled, but it is currently
# newer than its own sources — verified — and it is not the artifact that
# failed.)
#
# Sources are named file by file and tree by tree, not as "the module
# directory", because the build OUTPUT lives inside that same directory: a
# directory-wide comparison would find target/ newer than the binary it
# contains and rebuild on every single run.
#
# A path listed here that the checkout does not have is a hard error, not a
# skip: a typo would switch the guard off silently, which is precisely the
# failure mode the guard exists to remove.
# ---------------------------------------------------------------------------
RUST_TOKENIZER_SOURCES=(
    "$CREGIT/tokenize/rustTokenizer/Makefile"
    "$CREGIT/tokenize/rustTokenizer/Cargo.toml"
    "$CREGIT/tokenize/rustTokenizer/Cargo.lock"
    "$CREGIT/tokenize/rustTokenizer/src"
)

# needs_build <artifact> <source>...
#
# Returns 0 when the artifact has to be (re)built and sets NEEDS_BUILD_REASON to
# why: either "missing" or the path of the first source found newer than it, so
# the log says what triggered the build. Returns 1 when the artifact is current.
#
# The reason goes in a variable rather than on stdout deliberately. Called as
# `reason=$(needs_build ...)` the `die` below would exit the command
# substitution's subshell only, the function would look like it had returned
# "artifact is current", and a checkout with a missing source file would sail
# past the guard — the exact silent-skip this function exists to prevent.
#
# `find -newer ... -print -quit` stops at the first hit, so the common answer
# ("nothing is newer") costs one stat per source file and the fast path stays
# fast: a few milliseconds over all six artifacts, against builds costing
# minutes. Source DIRECTORIES are passed whole and walked, and their own mtimes
# count too: a file added to or removed from blobExec/src changes the directory
# and nothing else, and that is still a source change.
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

# Build what is missing, and — for the Rust tokenizer only — what is out of
# date, so an already-built and up-to-date checkout starts instantly and a
# checkout whose Rust sources moved does not run yesterday's tokenizer.
#
# Existence alone was not enough, and that was a real defect rather than a
# theoretical one. tokenize/rustTokenizer/target/release/rust_tokenizer was
# dated 2026-09-04 18:57 while its src/main.rs was dated 2026-09-20 21:30: the
# binary predated the fix (729643e) that removed a `line:col<TAB>` prefix from
# every Rust token, so the stale binary kept emitting it and every consumer
# split the token on the wrong field. token_type, token_value, source_text,
# source_line, source_col and is_structural were all shifted by one, and nothing
# errored. `[ -x "$RUST_TOKENIZER" ]` was true the whole time.
#
# KNOWN GAP, accepted rather than fixed here: the four sbt jars — blobExec
# included — and srcml2token stay behind an existence-only test. A stale jar
# would still be used, silently. The narrow fix was chosen because Rust is the
# only language routed to a compiled tokenizer (CregitLanguages.pm:109-115) and
# it is the artifact that actually failed; widening the guard is a separate
# decision with its own risk of rebuilding on every run.
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
        # A recoverable tokenize failure must survive its own error path. Without
        # this the exit-4 / exit-5 die below would delete the marker it just
        # wrote, plus the memo, the bare repos and the blob map it promises are
        # still there — the work the operator is told to resume from.
        local marker memo
        if marker=$(keep_markers_present); then
            log "Pipeline failed (exit $ec) — keeping $WORK: $marker says the work is resumable"
            log "Resume with FROM_STEP=2 (runner: append '2'; ctp.py: --from-step 2),"
            log "or discard it deliberately with --force-clean."
            return 0
        fi
        # Same reasoning, for the memo rather than the markers: a failed run must
        # not be the thing that deletes 2.6 million memoized tokenizations. There
        # is no marker to write here — the memo itself is the evidence.
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
    # Losing a large memo is the second expensive mistake, and unlike the markers
    # it is not written by a failure: a perfectly healthy, fully validated project
    # carries one. The mask changed corpus-wide on 2026-09-19 and blobExec refuses
    # to resume against a different mask, so every re-run of those projects is a
    # FROM_STEP=1 run — which is exactly when this fires. Checked after the marker
    # so a resumable directory still gets the more useful advice.
    if memo=$(memo_at_risk) && [ "$FORCE_CLEAN" != "1" ]; then
        die "refusing to delete $WORK: $memo holds at least $MEMO_KEEP_THRESHOLD memoized
     tokenizations, and deleting them means tokenizing this repository from cold.
$(memo_rescue_advice)"
    fi
    rm -rf "$WORK"
fi

LOG_FILE="${WORK}/pipeline.log"
mkdir -p "$MEMO_DIR" "$WORK/blame"
# The memo is mandatory: tokenizeByBlobId/tokenBySha.pl dies without BFG_MEMO_DIR,
# and refuses to run if the directory does not exist. It defaults to $WORK/memo
# and lives wherever --memo-dir says, which may be outside $WORK entirely.
# html/ is only created when step 9 will actually write into it.
[ "$SKIP_HTML" = 1 ] || mkdir -p $WORK/html
exec > >(tee -a "$LOG_FILE") 2>&1

echo ""
echo "████████████████████████████████████████████████████████████████████████"
echo "  CreGit Pipeline — ${REPO_NAME} (tokenize mode: ${MODE}, file jobs: ${JOBS})"
echo "  Repo: ${REPO_GIT_URL}"
echo "  Mask: ${MASK}   Commit links: ${REPO_COMMIT_URL}"
if memo_inside_work; then
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
# fan out by language: srcML for .c/.h, rustTokenizer for .rs, etc. The --srcml* /
# --ctags paths are forwarded only to the srcML parser. Behavior-preserving for C.
export BFG_TOKENIZE_CMD="${CREGIT}/tokenize/tokenize.pl \
  --srcml2token=${SRCML2TOKEN} \
  --srcml=$(which srcml) \
  --ctags=$(which ctags)"

# Which tokenizer produced which extension's tokens, so blobExec can refuse to
# reuse a cache built by a different one. Computed from the same three binaries
# BFG_TOKENIZE_CMD above pins, plus each language's parser and the dispatcher, by
# tokenize/tokenizerIdentity.pl. The failure it closes: `command` is the constant
# path tokenizeByBlobId/tokenBySha.pl and `mask` says which files, not how, so a
# rebuilt tokenizer moved neither and every cached row stayed a hit.
#
# Fatal if it cannot be computed. An empty identity would make the whole check a
# no-op, and the pipeline would go back to reusing caches it cannot vouch for.
TOKENIZER_IDENTITY=$(perl "${CREGIT}/tokenize/tokenizerIdentity.pl" \
    --srcml2token="${SRCML2TOKEN}" \
    --srcml="$(which srcml)" \
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
  # --retokenize carries --memo-dir with it, always, and blobExec refuses one
  # without the other: the blob map and the memo are two caches of the same
  # answer, and invalidating either alone invalidates nothing.
  RETOKENIZE_FLAGS=()
  if [ -n "$RETOKENIZE" ]; then
      RETOKENIZE_FLAGS=("--retokenize=$RETOKENIZE" "--memo-dir=$MEMO_DIR")
  fi
  java -jar "$BFG" $MODE_FLAG $WIDENED_FLAG \
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

# Step 2 finished: the work here is no longer "incomplete but resumable", so drop
# the markers. Leaving them would block every later FROM_STEP=1 run for the life
# of the directory, and ctp.py has no --force-clean passthrough to get past that.
for _m in $KEEP_MARKERS; do
    if [ -e "${WORK}/${_m}" ]; then
        log "tokenize completed — clearing ${WORK}/${_m}"
        rm -f "${WORK}/${_m}"
    fi
done

# Only now, with the invalidation verified and the re-fold done. blobExec refuses a
# --retokenize that would invalidate nothing (exit 7) and changes nothing on that
# path, so reaching here means the re-fold really happened and every cregit commit
# really does have a new sha — which is what makes dropping the derived artifacts
# mandatory rather than tidy. Step 6's clone fails outright on a directory that
# exists, and step 7's blame silently keeps .blame files naming commits that no
# longer exist.
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
if [ -n "$FIRM_MAP" ]; then
    DATASET_OPTS+=(--firm-map "$FIRM_MAP")
fi
if [ -n "$FIRM_CANONICAL" ]; then
    DATASET_OPTS+=(--firm-canonical "$FIRM_CANONICAL")
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
