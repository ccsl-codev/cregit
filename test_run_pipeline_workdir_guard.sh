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
echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
