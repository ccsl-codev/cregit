#!/usr/bin/env bash
# Tests for run_pipeline_process.sh's work-directory guard.
#
# A FROM_STEP=1 run deletes $WORK, and so does the failure trap. That is correct
# for a fresh run and catastrophic for a resumable one: when step 2 stops with a
# timed-out blob (blobExec exit 4) or a stall (exit 5) it leaves a marker, and the
# memo, bare repos and blob map in that directory are exactly what a step-2 resume
# needs. Deleting them turns a one-blob retry into days of re-tokenizing.
#
# The property each case asserts is simply: does the directory (and the work
# planted in it) still exist afterwards?
#
# No pipeline work is performed: every case fails early, either at the guard or
# at the first step, which is enough to exercise both deletion paths (the pre-run
# wipe and the EXIT trap). Requires the build artifacts to be present, which they
# are in a built checkout; it never builds anything itself.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$HERE/run_pipeline_process.sh"
PASS=0
FAIL=0

# Work dir holding a marker plus a stand-in for the expensive work.
fixture() {  # $1 = marker name or "none"
    local w
    w=$(mktemp -d "${TMPDIR:-/tmp}/guardtest-XXXXXX")
    mkdir -p "$w/memo" "$w/proj-original.git"
    echo "32 hours of tokenizing" > "$w/proj-blobmap.db"
    [ "$1" = "none" ] || echo "marker from a previous run" > "$w/$1"
    printf '%s' "$w"
}

# Plant memoized tokenizations in the layout tokenBySha.pl writes:
# <memo>/xx/yy/<sha1-of-contents>. The count is what the guard measures.
plant_memo() {  # $1 = memo dir, $2 = how many entries
    local d=$1 n=$2 i sha
    for i in $(seq 1 "$n"); do
        sha=$(printf '%040d' "$i")
        mkdir -p "$d/${sha:0:2}/${sha:2:2}"
        echo "tokens for blob $i" > "$d/${sha:0:2}/${sha:2:2}/$sha"
    done
}

count_memo() {  # $1 = memo dir
    find "$1" -mindepth 3 -maxdepth 3 -type f 2>/dev/null | wc -l
}

run_runner() {  # $1 = work dir, rest = extra args (including any FROM_STEP)
    local w=$1; shift
    # Executed directly, not via `bash <file>`: that is how ctp.py launches it
    # (subprocess.Popen(["./run_pipeline_process.sh", …])), so a lost executable
    # bit shows up here as exit 126 instead of silently in production.
    timeout 120 "$RUNNER" \
        --repo-url /nonexistent/does-not-exist.git \
        --repo-name proj \
        --work "$w" \
        --skip-html \
        "$@" 2>&1
}

check() {  # $1 = description, $2 = condition result (0/1)
    if [ "$2" -eq 0 ]; then
        echo "  ok   — $1"
        PASS=$((PASS + 1))
    else
        echo "  FAIL — $1"
        FAIL=$((FAIL + 1))
    fi
}

# ---------------------------------------------------------------------------
echo "case 1: TOKENIZE-TIMEOUTS marker + FROM_STEP=1 (default) must refuse"
# This also covers the EXIT trap: the guard's own `die` is a non-zero exit with
# FROM_STEP=1, which is precisely the condition the trap deletes on.
W=$(fixture TOKENIZE-TIMEOUTS)
OUT=$(run_runner "$W"); RC=$?
[ "$RC" -ne 0 ]; check "refuses (exit $RC)" $?
[ -d "$W" ]; check "work directory still exists" $?
[ -f "$W/proj-blobmap.db" ]; check "the expensive work is still there" $?
[ -f "$W/TOKENIZE-TIMEOUTS" ]; check "the marker itself survived the failure trap" $?
grep -q "TOKENIZE-TIMEOUTS" <<<"$OUT"; check "names the marker" $?
grep -q -- "--from-step 2" <<<"$OUT"; check "gives the ctp.py resume form" $?
grep -q -- "--force-clean" <<<"$OUT"; check "gives the escape hatch" $?
rm -rf "$W"

echo "case 2: TOKENIZE-STALLED marker + FROM_STEP=1 must refuse too"
W=$(fixture TOKENIZE-STALLED)
OUT=$(run_runner "$W"); RC=$?
[ "$RC" -ne 0 ]; check "refuses (exit $RC)" $?
[ -f "$W/proj-blobmap.db" ]; check "the expensive work is still there" $?
grep -q "TOKENIZE-STALLED" <<<"$OUT"; check "names the marker" $?
rm -rf "$W"

echo "case 3: marker + --force-clean must still wipe (deliberate clean restart)"
W=$(fixture TOKENIZE-TIMEOUTS)
OUT=$(run_runner "$W" --force-clean); RC=$?
[ "$RC" -ne 0 ]; check "still fails later, on the bogus clone (exit $RC)" $?
[ ! -f "$W/proj-blobmap.db" ]; check "the work was deleted, as asked" $?
[ ! -f "$W/TOKENIZE-TIMEOUTS" ]; check "the marker was deleted, as asked" $?
rm -rf "$W"

echo "case 4: marker + FROM_STEP=2 must proceed with the directory untouched"
W=$(fixture TOKENIZE-TIMEOUTS)
OUT=$(run_runner "$W" 2); RC=$?
# It fails inside step 2, because the fixture's blobmap.db is a stand-in rather
# than a real database — which is the point: it reached step 2 at all.
[ "$RC" -ne 0 ]; check "fails inside step 2 on the fixture's stand-in db (exit $RC)" $?
[ -d "$W" ]; check "work directory still exists" $?
[ -f "$W/proj-blobmap.db" ]; check "the expensive work is still there" $?
[ -f "$W/TOKENIZE-TIMEOUTS" ]; check "the marker is still there" $?
grep -q "tokenize \[" <<<"$OUT"; check "got past the guard and into step 2" $?
! grep -q "refusing to delete" <<<"$OUT"; check "the guard did not fire on a resume" $?
rm -rf "$W"

echo "case 5: no marker + FROM_STEP=1 wipes, exactly as before this guard"
W=$(fixture none)
OUT=$(run_runner "$W"); RC=$?
[ "$RC" -ne 0 ]; check "fails on the bogus clone (exit $RC)" $?
[ ! -f "$W/proj-blobmap.db" ]; check "a fresh run still starts clean" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
# The memo is the second thing in $WORK worth more than the run that deletes it.
# A memo hit returns without invoking srcml at all (tokenizeByBlobId/tokenBySha.pl),
# so its entries ARE tokenizing already done: torvalds__linux holds ~2.6 million of
# them against 3,228,137 blobs. The mask changed corpus-wide on 2026-09-19 and
# blobExec refuses to resume against a different mask, so every re-run is a
# FROM_STEP=1 run — the case that wipes.

echo "case 6: a large memo inside \$WORK must refuse the FROM_STEP=1 wipe"
W=$(fixture none)
plant_memo "$W/memo" 4
OUT=$(CREGIT_MEMO_KEEP_THRESHOLD=3 run_runner "$W"); RC=$?
[ "$RC" -ne 0 ]; check "refuses (exit $RC)" $?
[ -d "$W" ]; check "work directory still exists" $?
[ "$(count_memo "$W/memo")" -eq 4 ]; check "all 4 memo entries survived the guard AND the exit trap" $?
[ -f "$W/proj-blobmap.db" ]; check "the rest of the work is still there" $?
grep -q "refusing to delete" <<<"$OUT"; check "says it is refusing" $?
grep -q -- "--memo-dir" <<<"$OUT"; check "names the way to preserve the memo" $?
grep -q -- "--force-clean" <<<"$OUT"; check "gives the escape hatch" $?
rm -rf "$W"

echo "case 7: a large memo + --force-clean must still wipe (deliberate)"
W=$(fixture none)
plant_memo "$W/memo" 4
OUT=$(CREGIT_MEMO_KEEP_THRESHOLD=3 run_runner "$W" --force-clean); RC=$?
[ "$RC" -ne 0 ]; check "still fails later, on the bogus clone (exit $RC)" $?
[ ! -f "$W/proj-blobmap.db" ]; check "the work was deleted, as asked" $?
[ "$(count_memo "$W/memo")" -eq 0 ]; check "the memo was deleted, as asked" $?
rm -rf "$W"

echo "case 8: --memo-dir outside \$WORK survives the wipe it is meant to survive"
W=$(fixture none)
M=$(mktemp -d "${TMPDIR:-/tmp}/guardmemo-XXXXXX")
plant_memo "$M" 4
# Threshold 3 as well: the guard must NOT fire, because this memo is not in the
# directory being deleted. Nothing needs saving, so nothing needs refusing.
OUT=$(CREGIT_MEMO_KEEP_THRESHOLD=3 run_runner "$W" --memo-dir "$M"); RC=$?
[ "$RC" -ne 0 ]; check "fails on the bogus clone, not at the guard (exit $RC)" $?
! grep -q "refusing to delete" <<<"$OUT"; check "the guard did not fire" $?
[ ! -f "$W/proj-blobmap.db" ]; check "the work dir was wiped, as a step-1 run does" $?
[ "$(count_memo "$M")" -eq 4 ]; check "every memo entry survived the wipe" $?
grep -q "Memo: $M" <<<"$OUT"; check "the banner records where the memo is" $?
grep -q "survives a FROM_STEP=1 wipe" <<<"$OUT"; check "and says it is outside the work dir" $?
rm -rf "$W" "$M"

echo "case 9: the default memo is still \$WORK/memo, unchanged by this option"
W=$(fixture none)
OUT=$(run_runner "$W"); RC=$?
grep -q "Memo: $W/memo" <<<"$OUT"; check "defaults to <work>/memo" $?
grep -q "inside the work dir" <<<"$OUT"; check "and says a step-1 run deletes it" $?
rm -rf "$W"

echo "case 10: a memo below the threshold does not block the wipe"
W=$(fixture none)
plant_memo "$W/memo" 2
OUT=$(CREGIT_MEMO_KEEP_THRESHOLD=3 run_runner "$W"); RC=$?
! grep -q "refusing to delete" <<<"$OUT"; check "2 entries against a threshold of 3: no refusal" $?
[ ! -f "$W/proj-blobmap.db" ]; check "so the wipe happened as before" $?
rm -rf "$W"

echo "case 11: the shipped threshold is 10,000, not a test value"
grep -q 'MEMO_KEEP_THRESHOLD="${CREGIT_MEMO_KEEP_THRESHOLD:-10000}"' "$RUNNER"
check "MEMO_KEEP_THRESHOLD defaults to 10000" $?

# ---------------------------------------------------------------------------
echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
