#!/usr/bin/env bash
# Tests for step 2's handling of blobExec's "incomplete but resumable" statuses
# (4 = a blob timed out, 5 = the stall watchdog fired), for both the serial and
# the sharded branch, plus the marker clearing and the timeout passthrough.
#
# Why these matter:
#   - the marker is what stops a later FROM_STEP=1 run from deleting the work, so
#     a branch that does not write it silently loses days of tokenizing.
#   - the markers are never removed by anything else, so a resumed-and-finished
#     directory that keeps them blocks every later legitimate FROM_STEP=1 run.
#   - the recovery text names --blob-timeout, which is only actionable if the
#     runner actually passes it through.
#
# No tokenizing happens: `java` is stubbed on PATH, so each case is a second or
# two and nothing depends on srcml, perl or the JVM. The stub records its argv,
# which is how the passthrough is asserted.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$HERE/run_pipeline_process.sh"
SHARD_BUILD="$HERE/blobExec/shard_build.sh"
PASS=0
FAIL=0

check() {  # $1 = description, $2 = condition result (0/1)
    if [ "$2" -eq 0 ]; then
        echo "  ok   — $1"; PASS=$((PASS + 1))
    else
        echo "  FAIL — $1"; FAIL=$((FAIL + 1))
    fi
}

# A `java` that exits with $STUB_RC, logs its argv to $STUB_ARGV, and (on 0)
# creates the destination repo directory its caller will check for.
make_stub_java() {  # $1 = bin dir
    cat > "$1/java" <<'STUB'
#!/usr/bin/env bash
: "${STUB_RC:=0}"
[ -n "${STUB_ARGV:-}" ] && printf '%s\n' "$*" >> "$STUB_ARGV"
# Positional arguments after the jar are: src dst db command mask.
pos=(); skip=0
for a in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    case "$a" in
        -jar) skip=1 ;;
        -*)   ;;
        *)    pos+=("$a") ;;
    esac
done
if [ "$STUB_RC" = 0 ] && [ "${#pos[@]}" -ge 2 ]; then mkdir -p "${pos[1]}"; fi
exit "$STUB_RC"
STUB
    chmod +x "$1/java"
}

# A work directory that looks like a resumable step-1 output.
fixture() {
    local w
    w=$(mktemp -d "${TMPDIR:-/tmp}/gatetest-XXXXXX")
    mkdir -p "$w/memo" "$w/proj-original.git"
    echo "hours of tokenizing" > "$w/proj-blobmap.db"
    printf '%s' "$w"
}

run_step2() {  # $1 = work dir, rest = extra runner args; needs STUB_RC exported
    local w=$1; shift
    PATH="$BIN:$PATH" timeout 300 "$RUNNER" \
        --repo-url /nonexistent/does-not-exist.git \
        --repo-name proj \
        --work "$w" \
        --skip-html --gc none \
        "$@" 2 2>&1
}

BIN=$(mktemp -d "${TMPDIR:-/tmp}/gatebin-XXXXXX")
make_stub_java "$BIN"
trap 'rm -rf "$BIN"' EXIT

# ---------------------------------------------------------------------------
echo "case 1: serial branch, blobExec exits 4 — marker written, work kept"
W=$(fixture)
OUT=$(STUB_RC=4 run_step2 "$W"); RC=$?
[ "$RC" -ne 0 ]; check "step 2 refuses to continue (exit $RC)" $?
[ -f "$W/TOKENIZE-TIMEOUTS" ]; check "TOKENIZE-TIMEOUTS written" $?
[ -f "$W/proj-blobmap.db" ]; check "the work is still there" $?
grep -q -- "--from-step 2" <<<"$OUT"; check "names the resume path" $?
grep -q "137" <<<"$OUT"; check "distinguishes an OOM kill from slowness" $?
rm -rf "$W"

echo "case 2: serial branch, blobExec exits 5 — stalled marker written"
W=$(fixture)
OUT=$(STUB_RC=5 run_step2 "$W"); RC=$?
[ "$RC" -ne 0 ]; check "step 2 refuses to continue (exit $RC)" $?
[ -f "$W/TOKENIZE-STALLED" ]; check "TOKENIZE-STALLED written" $?
[ -f "$W/proj-blobmap.db" ]; check "the work is still there" $?
rm -rf "$W"

echo "case 3: sharded branch, a shard exits 4 — same marker, same message"
# Until this round the marker lived only in the serial branch, and shard_build.sh
# collapsed every failure to 1, so this case lost the whole build.
W=$(fixture)
OUT=$(STUB_RC=4 run_step2 "$W" --mode sharded --shards 2); RC=$?
[ "$RC" -ne 0 ]; check "step 2 refuses to continue (exit $RC)" $?
[ -f "$W/TOKENIZE-TIMEOUTS" ]; check "TOKENIZE-TIMEOUTS written for a sharded build" $?
[ -f "$W/proj-blobmap.db" ]; check "the work is still there" $?
grep -q -- "--from-step 2" <<<"$OUT"; check "names the resume path" $?
rm -rf "$W"

echo "case 4: sharded branch, a shard exits 5 — stalled marker"
W=$(fixture)
OUT=$(STUB_RC=5 run_step2 "$W" --mode sharded --shards 2); RC=$?
[ "$RC" -ne 0 ]; check "step 2 refuses to continue (exit $RC)" $?
[ -f "$W/TOKENIZE-STALLED" ]; check "TOKENIZE-STALLED written for a sharded build" $?
rm -rf "$W"

echo "case 5: shard_build.sh propagates 4 and 5 rather than collapsing to 1"
for rc in 4 5; do
    O=$(mktemp -d "${TMPDIR:-/tmp}/shardout-XXXXXX")
    S=$(mktemp -d "${TMPDIR:-/tmp}/shardsrc-XXXXXX")
    PATH="$BIN:$PATH" STUB_RC=$rc timeout 300 "$SHARD_BUILD" \
        --src "$S" --out "$O" --shards 2 \
        --jar "$HERE/blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar" \
        --command "$HERE/tokenizeByBlobId/tokenBySha.pl" \
        --mask '\.c$' --tok-cmd "true" > "$O/out.log" 2>&1
    GOT=$?
    [ "$GOT" -eq "$rc" ]; check "a shard exiting $rc makes shard_build.sh exit $rc (got $GOT)" $?
    ! grep -q "merge + serial re-fold" "$O/out.log"; check "it does not merge a knowingly incomplete build ($rc)" $?
    rm -rf "$O" "$S"
done

echo "case 6: a completed step 2 clears the markers"
# Otherwise the directory stays marked forever and every later FROM_STEP=1 run
# dies at the guard, with no --force-clean passthrough in ctp.py to get past it.
W=$(fixture)
: > "$W/TOKENIZE-TIMEOUTS"
: > "$W/TOKENIZE-STALLED"
OUT=$(STUB_RC=0 run_step2 "$W")  # fails later, at a step this test does not stub
[ ! -f "$W/TOKENIZE-TIMEOUTS" ]; check "TOKENIZE-TIMEOUTS cleared" $?
[ ! -f "$W/TOKENIZE-STALLED" ]; check "TOKENIZE-STALLED cleared" $?
[ -d "$W/proj-cregit.git" ]; check "step 2 did produce its output" $?
grep -q "clearing" <<<"$OUT"; check "says so in the log" $?
rm -rf "$W"

echo "case 7: --blob-timeout / --stall-timeout reach blobExec"
W=$(fixture)
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=4 run_step2 "$W" --blob-timeout 900 --stall-timeout 3600)
grep -q -- "--blob-timeout=900" "$STUB_ARGV"; check "the flag is passed through" $?
grep -q -- "--stall-timeout=3600" "$STUB_ARGV"; check "so is the stall window" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 8: the CREGIT_* environment fallbacks reach blobExec (the ctp.py path)"
W=$(fixture)
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=4 CREGIT_BLOB_TIMEOUT=1200 CREGIT_STALL_TIMEOUT=4800 run_step2 "$W")
grep -q -- "--blob-timeout=1200" "$STUB_ARGV"; check "CREGIT_BLOB_TIMEOUT is honoured" $?
grep -q -- "--stall-timeout=4800" "$STUB_ARGV"; check "CREGIT_STALL_TIMEOUT is honoured" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 9: a bad timeout value is refused before any work"
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W" --blob-timeout 0); RC=$?
[ "$RC" -ne 0 ]; check "--blob-timeout 0 is rejected (exit $RC)" $?
grep -q "invalid --blob-timeout" <<<"$OUT"; check "says which flag was wrong" $?
OUT=$(STUB_RC=0 run_step2 "$W" --stall-timeout abc); RC=$?
[ "$RC" -ne 0 ]; check "--stall-timeout abc is rejected (exit $RC)" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
