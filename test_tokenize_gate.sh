#!/usr/bin/env bash
# Tests for step 2's handling of blobExec's "incomplete but resumable" statuses
# (4 = a blob timed out, 5 = the stall watchdog fired), for both the serial and
# the sharded branch, plus the marker clearing and the timeout passthrough.
#
# Since the default changed, blobExec exits 4 or 6 only with --strict-tokenize
# (and always in --mode sharded). By default it skips a failed blob, records it in
# <work>/tokenize-skipped.tsv and exits 0; cases 10-14 pin that path. The strict
# cases below pass --strict-tokenize so that they show a real combination.
#
# Why these matter:
#   - the marker is what stops a later FROM_STEP=1 run from deleting the work, so
#     a branch that does not write it silently loses days of tokenizing. Until
#     this round only the serial branch wrote it, and ctp.py picks sharded mode
#     for the largest repositories.
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
# The tokenizer reads the memo location from the environment, not from argv, so
# the only way to assert it arrives is to record it here.
[ -n "${STUB_ENV:-}" ] && printf 'BFG_MEMO_DIR=%s\n' "${BFG_MEMO_DIR:-unset}" >> "$STUB_ENV"
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
# What blobExec writes by default: rows in --skipped-tsv, and (STUB_REFOLD=1) the
# --refold-marker file when a retry recovered a blob.
for a in "$@"; do
    case "$a" in
        --skipped-tsv=*)
            if [ -n "${STUB_TSV_ROWS:-}" ]; then
                f=${a#--skipped-tsv=}
                [ -s "$f" ] || printf 'sha\tpath\treason\tdetail\ttokenizer\n' > "$f"
                printf '%b' "$STUB_TSV_ROWS" >> "$f"
            fi ;;
        --refold-marker=*)
            [ "${STUB_REFOLD:-0}" = 1 ] && : > "${a#--refold-marker=}" ;;
    esac
done
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
echo "case 1: serial branch, --strict-tokenize, blobExec exits 4 — marker written, work kept"
W=$(fixture)
OUT=$(STUB_RC=4 run_step2 "$W" --strict-tokenize); RC=$?
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

echo "case 5bis: serial branch, --strict-tokenize, blobExec exits 6 - parser-crash marker written"
# The silent-empty defect: srcML dies on a signal, the wrapper used to report
# success with zero bytes, and a 0-byte tokenization was published. Exit 6 is that
# failure made visible, and it must keep the work rather than wipe it: the fix is a
# denylist entry or a fixed srcML, not a re-run, so the memo has to survive.
W=$(fixture)
OUT=$(STUB_RC=6 run_step2 "$W" --strict-tokenize); RC=$?
[ "$RC" -ne 0 ]; check "step 2 refuses to continue (exit $RC)" $?
[ -f "$W/TOKENIZE-PARSER-CRASHES" ]; check "TOKENIZE-PARSER-CRASHES written" $?
[ -f "$W/proj-blobmap.db" ]; check "the work is still there" $?
grep -q "denylist" <<<"$OUT"; check "names the denylist as the remedy" $?
grep -qi "not.*slowness\|blob-timeout will not help" <<<"$OUT"; check "says --blob-timeout will not help" $?
rm -rf "$W"

echo "case 5ter: sharded branch, a shard exits 6 - same marker"
W=$(fixture)
OUT=$(STUB_RC=6 run_step2 "$W" --mode sharded --shards 2); RC=$?
[ "$RC" -ne 0 ]; check "step 2 refuses to continue (exit $RC)" $?
[ -f "$W/TOKENIZE-PARSER-CRASHES" ]; check "TOKENIZE-PARSER-CRASHES written for a sharded build" $?
rm -rf "$W"

echo "case 5quater: shard_build.sh propagates 6 rather than collapsing to 1"
# Without this a parser crash in a sharded build exits 1, the marker is never
# written, and a later step-1 run deletes a days-long build to rediscover it.
O=$(mktemp -d "${TMPDIR:-/tmp}/shardout-XXXXXX")
S=$(mktemp -d "${TMPDIR:-/tmp}/shardsrc-XXXXXX")
PATH="$BIN:$PATH" STUB_RC=6 timeout 300 "$SHARD_BUILD" \
    --src "$S" --out "$O" --shards 2 \
    --jar "$HERE/blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar" \
    --command "$HERE/tokenizeByBlobId/tokenBySha.pl" \
    --mask '\.c$' --tok-cmd "true" > "$O/out.log" 2>&1
GOT=$?
[ "$GOT" -eq 6 ]; check "a shard exiting 6 makes shard_build.sh exit 6 (got $GOT)" $?
! grep -q "merge + serial re-fold" "$O/out.log"; check "it does not merge a knowingly incomplete build (6)" $?
rm -rf "$O" "$S"

echo "case 6: a completed step 2 clears the markers"
# Otherwise the directory stays marked forever and every later FROM_STEP=1 run
# dies at the guard, with no --force-clean passthrough in ctp.py to get past it.
W=$(fixture)
: > "$W/TOKENIZE-TIMEOUTS"
: > "$W/TOKENIZE-STALLED"
: > "$W/TOKENIZE-PARSER-CRASHES"
OUT=$(STUB_RC=0 run_step2 "$W")  # fails later, at a step this test does not stub
[ ! -f "$W/TOKENIZE-TIMEOUTS" ]; check "TOKENIZE-TIMEOUTS cleared" $?
[ ! -f "$W/TOKENIZE-STALLED" ]; check "TOKENIZE-STALLED cleared" $?
[ ! -f "$W/TOKENIZE-PARSER-CRASHES" ]; check "TOKENIZE-PARSER-CRASHES cleared" $?
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

echo "case 9bis: BFG_MEMO_DIR reaches step 2, and --memo-dir is what sets it"
# tokenizeByBlobId/tokenBySha.pl takes the memo location from the environment and
# dies without it, and a memo hit is a tokenization that never runs srcml. So the
# value that arrives here is the whole of --memo-dir's effect.
W=$(fixture)
export STUB_ENV="$W/java-env"
OUT=$(STUB_RC=4 run_step2 "$W")
grep -qx "BFG_MEMO_DIR=$W/memo" "$STUB_ENV"; check "default: <work>/memo, exactly as before" $?
rm -f "$STUB_ENV"
M=$(mktemp -d "${TMPDIR:-/tmp}/gatememo-XXXXXX")
rmdir "$M"                       # the runner must create it, as it does for <work>/memo
OUT=$(STUB_RC=4 run_step2 "$W" --memo-dir "$M")
grep -qx "BFG_MEMO_DIR=$M" "$STUB_ENV"; check "--memo-dir: the memo lives outside \$WORK" $?
[ -d "$M" ]; check "and the runner created it" $?
! grep -q "warning: --memo-dir" <<<"$OUT"; check "no inside-\$WORK warning for an outside dir" $?
unset STUB_ENV
rm -rf "$W" "$M"

echo "case 9: a bad timeout value is refused before any work"
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W" --blob-timeout 0); RC=$?
[ "$RC" -ne 0 ]; check "--blob-timeout 0 is rejected (exit $RC)" $?
grep -q "invalid --blob-timeout" <<<"$OUT"; check "says which flag was wrong" $?
OUT=$(STUB_RC=0 run_step2 "$W" --stall-timeout abc); RC=$?
[ "$RC" -ne 0 ]; check "--stall-timeout abc is rejected (exit $RC)" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
# The default: a failed blob is skipped, recorded, and step 2 continues.
ROWS='1111111111111111111111111111111111111111\tsrc/crash.c\tparser-crash\texit=33 signal=11\tc=abcdef01\n'
ROWS+='2222222222222222222222222222222222222222\tsrc/slow.c\ttimeout\ttimeout=600s\tc=abcdef01\n'
ROWS+='3333333333333333333333333333333333333333\tsrc/deny.java\tdenylisted\treason=hang; citation=srcML/srcML#2361\tjava=abcdef02\n'

echo "case 10: default serial branch - skipped blobs are recorded, and step 2 continues"
W=$(fixture)
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=0 STUB_TSV_ROWS="$ROWS" run_step2 "$W")
grep -q -- "--skipped-tsv=$W/tokenize-skipped.tsv" "$STUB_ARGV"; check "the skip file path reaches blobExec" $?
grep -q -- "--refold-marker=$W/TOKENIZE-REFOLDED" "$STUB_ARGV"; check "so does the re-fold marker path" $?
! grep -q -- "--strict-tokenize" "$STUB_ARGV"; check "strict mode is off by default" $?
[ -d "$W/proj-cregit.git" ]; check "step 2 finished and produced its output" $?
grep -q "WARNING: 3 blobs skipped, see $W/tokenize-skipped.tsv" <<<"$OUT"; check "the warning names the count and the file" $?
grep -q "parser-crash=1" <<<"$OUT" && grep -q "timeout=1" <<<"$OUT" && grep -q "denylisted=1" <<<"$OUT"
check "the warning gives the count for each reason" $?
grep -q "step 3\|Step 3\|\[3/" <<<"$OUT"; check "the run went on to step 3" $?
[ ! -e "$W/TOKENIZE-TIMEOUTS" ] && [ ! -e "$W/TOKENIZE-PARSER-CRASHES" ]; check "no failure marker is written" $?
head -n 1 "$W/tokenize-skipped.tsv" | grep -qx "$(printf 'sha\tpath\treason\tdetail\ttokenizer')"; check "the file has the header" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 11: default serial branch, no skipped blobs - no warning"
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W")
! grep -q "blobs skipped" <<<"$OUT"; check "no warning when nothing was skipped" $?
rm -rf "$W"

echo "case 12: --strict-tokenize and CREGIT_STRICT_TOKENIZE=1 reach blobExec"
W=$(fixture)
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=0 run_step2 "$W" --strict-tokenize)
grep -q -- "--strict-tokenize" "$STUB_ARGV"; check "the flag is passed through" $?
rm -f "$STUB_ARGV"
OUT=$(STUB_RC=0 CREGIT_STRICT_TOKENIZE=1 run_step2 "$W")
grep -q -- "--strict-tokenize" "$STUB_ARGV"; check "CREGIT_STRICT_TOKENIZE=1 is honoured" $?
OUT=$(STUB_RC=0 CREGIT_STRICT_TOKENIZE=yes run_step2 "$W"); RC=$?
[ "$RC" -ne 0 ] && grep -q "invalid CREGIT_STRICT_TOKENIZE" <<<"$OUT"; check "a bad CREGIT_STRICT_TOKENIZE is refused" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 13: a re-fold after a recovered retry drops the outputs of the old commits"
W=$(fixture)
mkdir -p "$W/blame/old"; echo old > "$W/blame/old/x.blame"
echo old > "$W/proj-dataset.parquet"
printf 'sha\tpath\treason\tdetail\ttokenizer\n' > "$W/tokenize-skipped.tsv"
OUT=$(STUB_RC=0 STUB_REFOLD=1 run_step2 "$W")
[ ! -e "$W/blame/old/x.blame" ]; check "the old blame output is removed" $?
[ ! -e "$W/proj-dataset.parquet" ]; check "the old dataset is removed" $?
[ ! -e "$W/TOKENIZE-REFOLDED" ]; check "the marker is removed after the drop" $?
[ -f "$W/tokenize-skipped.tsv" ]; check "the skip file is kept" $?
[ -f "$W/proj-blobmap.db" ]; check "the blob map is kept" $?
rm -rf "$W"

echo "case 14: sharded mode is always strict"
W=$(fixture)
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=4 run_step2 "$W" --mode sharded --shards 2)
grep -q -- "--shard=0/2 --strict-tokenize" "$STUB_ARGV"; check "every shard gets --strict-tokenize" $?
unset STUB_ARGV
rm -rf "$W"

# ---------------------------------------------------------------------------
# Earlier timeouts are retried by themselves only while no blame output exists.
TIMEOUT_TSV=$(printf 'sha\tpath\treason\tdetail\ttokenizer\n2222222222222222222222222222222222222222\tsrc/slow.c\ttimeout\ttimeout=600s retry=1800s\tc=abcdef01\n')

echo "case 15: no blame output - the retry pass runs"
W=$(fixture)
printf '%s\n' "$TIMEOUT_TSV" > "$W/tokenize-skipped.tsv"
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=0 run_step2 "$W")
! grep -q -- "--no-retry-timed-out" "$STUB_ARGV"; check "blobExec is not told to hold the retries" $?
! grep -q "stay dropped" <<<"$OUT"; check "no held-retry warning" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 16: blame output exists, no --retry-skipped - held, warned, blame untouched"
W=$(fixture)
printf '%s\n' "$TIMEOUT_TSV" > "$W/tokenize-skipped.tsv"
mkdir -p "$W/blame/src"; echo kept > "$W/blame/src/a.c.blame"; echo kept > "$W/blame/src/b.c.blame"
mkdir -p "$W/blame-c100-incoming/gcj"; echo kept > "$W/blame-c100-incoming/gcj/a.c.blame"
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=0 run_step2 "$W")
grep -q -- "--no-retry-timed-out" "$STUB_ARGV"; check "blobExec is told to hold the retries" $?
grep -q "WARNING: 1 timed-out blobs stay dropped (see $W/tokenize-skipped.tsv); --retry-skipped retries them and re-blames the project (3 .blame files would be deleted)" <<<"$OUT"
check "the warning gives the count, the file, the flag and the blame estimate" $?
[ -f "$W/blame/src/a.c.blame" ] && [ -f "$W/blame/src/b.c.blame" ] && [ -f "$W/blame-c100-incoming/gcj/a.c.blame" ]
check "the blame output is untouched, the incoming re-blame too" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 17: blame output exists, --retry-skipped - retried, re-folded, blame dropped"
for how in flag env; do
    W=$(fixture)
    printf '%s\n' "$TIMEOUT_TSV" > "$W/tokenize-skipped.tsv"
    mkdir -p "$W/blame/src"; echo old > "$W/blame/src/a.c.blame"
    mkdir -p "$W/blame-c100-incoming/gcj"; echo old > "$W/blame-c100-incoming/gcj/a.c.blame"
    export STUB_ARGV="$W/java-argv"
    if [ "$how" = flag ]; then
        OUT=$(STUB_RC=0 STUB_REFOLD=1 run_step2 "$W" --retry-skipped)
    else
        OUT=$(STUB_RC=0 STUB_REFOLD=1 CREGIT_RETRY_SKIPPED=1 run_step2 "$W")
    fi
    ! grep -q -- "--no-retry-timed-out" "$STUB_ARGV"; check "($how) blobExec may retry" $?
    [ ! -e "$W/blame/src/a.c.blame" ]; check "($how) the re-fold drops the old blame" $?
    [ ! -e "$W/blame-c100-incoming" ]; check "($how) the re-fold drops blame-c100-incoming too" $?
    ! grep -q "stay dropped" <<<"$OUT"; check "($how) no held-retry warning" $?
    unset STUB_ARGV
    rm -rf "$W"
done

echo "case 18: a .blame file in blame-c100-incoming counts as blame output"
W=$(fixture)
mkdir -p "$W/blame-c100-incoming/gcj/src"; echo x > "$W/blame-c100-incoming/gcj/src/a.c.blame"
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=0 run_step2 "$W")
grep -q -- "--no-retry-timed-out" "$STUB_ARGV"; check "held because of the incoming blame" $?
unset STUB_ARGV
rm -rf "$W"

echo "case 19: --max-retries, --timeout-retry-factor and CREGIT_LOAD_LIMIT reach blobExec"
W=$(fixture)
export STUB_ARGV="$W/java-argv"
OUT=$(STUB_RC=0 CREGIT_LOAD_LIMIT=8 run_step2 "$W" --timeout-retry-factor 0)
grep -q -- "--timeout-retry-factor=0" "$STUB_ARGV"; check "the factor is passed through (0 = the first budget)" $?
grep -q -- "--load-limit=8" "$STUB_ARGV"; check "the load limit is passed through" $?
rm -f "$STUB_ARGV"
OUT=$(STUB_RC=0 CREGIT_TIMEOUT_RETRY_FACTOR=5 run_step2 "$W")
grep -q -- "--timeout-retry-factor=5" "$STUB_ARGV"; check "CREGIT_TIMEOUT_RETRY_FACTOR is honoured" $?
rm -f "$STUB_ARGV"
OUT=$(STUB_RC=0 run_step2 "$W" --max-retries 3)
grep -q -- "--max-retries=3" "$STUB_ARGV"; check "an explicit --max-retries 3 is passed through" $?
rm -f "$STUB_ARGV"
OUT=$(STUB_RC=0 CREGIT_MAX_RETRIES=5 run_step2 "$W")
grep -q -- "--max-retries=5" "$STUB_ARGV"; check "CREGIT_MAX_RETRIES is honoured" $?
rm -f "$STUB_ARGV"
OUT=$(STUB_RC=0 run_step2 "$W")
! grep -q -- "--max-retries=\|--load-limit=" "$STUB_ARGV"; check "without them, blobExec keeps its own defaults" $?
grep -q "blobExec default: 0" "$RUNNER"; check "the help text names the default of 0 retries" $?
OUT=$(STUB_RC=0 run_step2 "$W" --max-retries three); RC=$?
[ "$RC" -ne 0 ] && grep -q "invalid --max-retries" <<<"$OUT"; check "a bad --max-retries is refused" $?
OUT=$(STUB_RC=0 CREGIT_MAX_RETRIES=500 run_step2 "$W"); RC=$?
[ "$RC" -ne 0 ] && grep -q "invalid --max-retries" <<<"$OUT"; check "too many retries are refused" $?
OUT=$(STUB_RC=0 CREGIT_LOAD_LIMIT=lots run_step2 "$W"); RC=$?
[ "$RC" -ne 0 ] && grep -q "invalid CREGIT_LOAD_LIMIT" <<<"$OUT"; check "a bad CREGIT_LOAD_LIMIT is refused" $?
OUT=$(STUB_RC=0 run_step2 "$W" --timeout-retry-factor x); RC=$?
[ "$RC" -ne 0 ] && grep -q "invalid --timeout-retry-factor" <<<"$OUT"; check "a bad factor is refused" $?
OUT=$(STUB_RC=0 CREGIT_RETRY_SKIPPED=maybe run_step2 "$W"); RC=$?
[ "$RC" -ne 0 ] && grep -q "invalid CREGIT_RETRY_SKIPPED" <<<"$OUT"; check "a bad CREGIT_RETRY_SKIPPED is refused" $?
unset STUB_ARGV
rm -rf "$W"

# ---------------------------------------------------------------------------
echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
