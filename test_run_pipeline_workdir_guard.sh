#!/usr/bin/env bash
# A resumable $WORK must survive both deletion paths: the FROM_STEP=1 wipe and
# the EXIT trap. `java` is stubbed, so no pipeline work runs.
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
    # Executed directly, as ctp.py launches it: a lost exec bit surfaces as 126.
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
