#!/usr/bin/env bash
# Tests for the runner's half of the cache-invalidation mechanism: the tokenizer
# identity it computes and passes to blobExec on EVERY run, the --retokenize
# passthrough, the guards that stop the flag being a quiet no-op, and the
# derived-artifact drop that must happen only after the invalidation succeeded.
#
# Why each of these is here:
#
#   * the identity has to arrive. If the runner does not pass
#     --tokenizer-identity, blobExec records nothing, compares nothing, and the
#     hole is exactly as open as it was — a corrected tokenizer reusing every
#     cached row, silently, which is what put a line:col prefix into 741,869 .rs
#     entries across 45 projects.
#   * --retokenize has to arrive WITH --memo-dir. They are two caches of the same
#     answer: the blob map, and the memo keyed on sha1 of the file's content with
#     no tokenizer in the key. Invalidating either alone invalidates nothing.
#   * the flag must not be reachable in a mode or a step where it would do
#     nothing. FROM_STEP=1 deletes the caches first; FROM_STEP=3 skips step 2, so
#     the invalidation never runs and the run exits 0 over poisoned tokens;
#     sharded mode builds a fresh blob map per shard.
#   * exit 7 must fail the run. blobExec returns it when --retokenize would have
#     invalidated nothing. Treating that as success is the whole failure mode.
#   * the derived artifacts (blame, html, the databases) must be dropped only
#     AFTER step 2 succeeded. Blame is the pipeline's bottleneck — measured at 8
#     files a minute — so deleting it to serve a request blobExec then rejects is
#     the most expensive way to handle a typo.
#
# `java` is stubbed on PATH and records its argv, so no tokenizing happens and no
# case takes more than a second or two.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$HERE/run_pipeline_process.sh"
PASS=0
FAIL=0

check() {  # $1 = description, $2 = condition result (0/1)
    if [ "$2" -eq 0 ]; then
        echo "  ok   — $1"; PASS=$((PASS + 1))
    else
        echo "  FAIL — $1"; FAIL=$((FAIL + 1))
    fi
}

# A `java` that exits with $STUB_RC, logs its argv, and (on 0) creates the
# destination repo its caller checks for.
make_stub_java() {  # $1 = bin dir
    cat > "$1/java" <<'STUB'
#!/usr/bin/env bash
: "${STUB_RC:=0}"
[ -n "${STUB_ARGV:-}" ] && printf '%s\n' "$*" >> "$STUB_ARGV"
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

# ---------------------------------------------------------------------------
# A throwaway CREGIT tree. The runner takes CREGIT from $(pwd), and it needs the
# tokenize/ scripts (the identity is computed by tokenize/tokenizerIdentity.pl)
# plus each module's declared sources and artifacts, or the staleness guard would
# start real builds. The artifacts are placeholders: the identity digests their
# BYTES, so any content works, and a case that "changes a tokenizer" just writes a
# different string into one.
# ---------------------------------------------------------------------------
repo_fixture() {
    local f p
    f=$(mktemp -d "${TMPDIR:-/tmp}/retokrepo-XXXXXX")
    cp "$RUNNER" "$f/run_pipeline_process.sh"
    cp -r "$HERE/tokenize" "$f/tokenize"
    rm -rf "$f/tokenize/rustTokenizer/target" "$f/tokenize/srcMLtoken/srcml2token"
    for p in blobExec slickGitLog persons remapCommits; do
        mkdir -p "$f/$p"
        cp "$HERE/$p/build.sbt" "$f/$p/build.sbt"
        cp -r "$HERE/$p/project" "$f/$p/project"
        rm -rf "$f/$p/project/target" "$f/$p/project/project"
        mkdir -p "$f/$p/src"
    done
    # Placeholder artifacts, stamped newer than everything above so the staleness
    # guard is satisfied and no build runs.
    for p in tokenize/srcMLtoken/srcml2token \
             tokenize/rustTokenizer/target/release/rust_tokenizer \
             blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar \
             slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar \
             persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar \
             remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar
    do
        mkdir -p "$f/$(dirname "$p")"
        echo "placeholder v1" > "$f/$p"
        chmod +x "$f/$p"
    done
    printf '%s' "$f"
}

# A work directory that looks like a finished run resumable at step 2: the bare
# original, a blob map, a memo, and the derived artifacts a re-fold invalidates.
fixture() {
    local w
    w=$(mktemp -d "${TMPDIR:-/tmp}/retokwork-XXXXXX")
    mkdir -p "$w/memo/ab/cd" "$w/proj-original.git" "$w/blame" "$w/html"
    echo "hours of tokenizing" > "$w/proj-blobmap.db"
    echo "tokens"              > "$w/memo/ab/cd/abcdef"
    echo "days of blame"       > "$w/blame/one.c.blame"
    echo "html"                > "$w/html/index.html"
    echo "db"                  > "$w/proj-original.db"
    printf '%s' "$w"
}

run_step2() {  # $1 = work dir, rest = extra runner args + FROM_STEP
    local w=$1; shift
    ( cd "$REPO" \
      && PATH="$BIN:$PATH" LEGACY_JAVA_HOME=/nonexistent/jdk8 timeout 300 \
         bash ./run_pipeline_process.sh \
            --repo-url /nonexistent/does-not-exist.git \
            --repo-name proj \
            --work "$w" \
            --skip-html --gc none \
            "$@" 2>&1 )
}

BIN=$(mktemp -d "${TMPDIR:-/tmp}/retokbin-XXXXXX")
make_stub_java "$BIN"
REPO=$(repo_fixture)
trap 'rm -rf "$BIN" "$REPO"' EXIT

# ---------------------------------------------------------------------------
echo "case 1: every run passes a tokenizer identity, whether or not it invalidates"
W=$(fixture); ARGV="$W/argv.log"
OUT=$(STUB_RC=0 STUB_ARGV="$ARGV" run_step2 "$W" 2); RC=$?
# Step 2 is what these cases are about. The run goes on to die at step 5, because
# the stubbed java writes no databases; reaching step 3 is the signal that step 2
# finished and the tokenize gate was satisfied.
grep -q 'Step 3' <<<"$OUT"; check "step 2 completed and the pipeline moved on" $?
grep -q -- '--tokenizer-identity=' "$ARGV"; check "blobExec was given --tokenizer-identity" $?
# It must be a real per-extension map, not an empty string that records nothing.
grep -qE -- '--tokenizer-identity=[a-z0-9+]+=[0-9a-f]{64}' "$ARGV"
check "and the value is ext=sha256 pairs" $?
grep -q -- '--retokenize=' "$ARGV"; [ $? -ne 0 ]
check "and NO --retokenize: nothing is invalidated by default" $?
grep -q -- '--memo-dir=' "$ARGV"; [ $? -ne 0 ]
check "and no --memo-dir either, which blobExec would refuse on its own" $?
[ -f "$W/blame/one.c.blame" ]; check "the blame output was left alone" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 2: --retokenize is passed through, and always with --memo-dir"
W=$(fixture); ARGV="$W/argv.log"
OUT=$(STUB_RC=0 STUB_ARGV="$ARGV" run_step2 "$W" --retokenize rs 2); RC=$?
grep -q 'Step 3' <<<"$OUT"; check "step 2 completed and the pipeline moved on" $?
grep -q -- '--retokenize=rs' "$ARGV"; check "--retokenize=rs reached blobExec" $?
grep -q -- "--memo-dir=$W/memo" "$ARGV"; check "and --memo-dir named this project's memo" $?
grep -q -- '--tokenizer-identity=' "$ARGV"; check "and the identity too, which blobExec requires" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 3: a successful invalidation drops the artifacts derived from the re-fold"
# Mandatory, not tidy: a re-tokenized blob changes the tree above it, so every
# cregit commit gets a new sha. Step 6's clone then fails on an existing directory
# and step 7's blame silently keeps .blame files naming commits that are gone.
W=$(fixture); ARGV="$W/argv.log"
OUT=$(STUB_RC=0 STUB_ARGV="$ARGV" run_step2 "$W" --retokenize rs 2); RC=$?
[ ! -f "$W/blame/one.c.blame" ]; check "the stale blame output is gone" $?
[ ! -f "$W/html/index.html" ]; check "the stale html is gone" $?
[ ! -f "$W/proj-original.db" ]; check "the stale git-log db is gone" $?
[ -f "$W/proj-blobmap.db" ]; check "the blob map is KEPT — it is what was reused" $?
[ -f "$W/memo/ab/cd/abcdef" ]; check "and so is the memo" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 4: a REFUSED invalidation (exit 7) fails the run and keeps the blame output"
W=$(fixture); ARGV="$W/argv.log"
OUT=$(STUB_RC=7 STUB_ARGV="$ARGV" run_step2 "$W" --retokenize rs 2); RC=$?
[ "$RC" -ne 0 ]; check "the run fails (exit $RC), it does not exit 0" $?
grep -q 'invalidat' <<<"$OUT"; check "and says the invalidation was refused" $?
[ -f "$W/blame/one.c.blame" ]; check "days of blame output survived the refusal" $?
[ -f "$W/proj-blobmap.db" ]; check "so did the blob map" $?
[ ! -f "$W/TOKENIZE-TIMEOUTS" ]; check "no resumable-work marker was written" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 5: --retokenize at step 1 is refused, because step 1 deletes the caches"
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W" --retokenize rs 1); RC=$?
[ "$RC" -ne 0 ]; check "refused (exit $RC)" $?
grep -q 'FROM_STEP=2' <<<"$OUT"; check "and names the resume it wants" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 6: --retokenize at step 3 is refused, because it would be skipped entirely"
# The invalidation lives in step 2. From step 3 the flag does nothing, the poisoned
# tokens go through steps 3-10, and the run exits 0.
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W" --retokenize rs 3); RC=$?
[ "$RC" -ne 0 ]; check "refused (exit $RC)" $?
grep -q 'exactly' <<<"$OUT"; check "and says the step must be exactly 2" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 7: --retokenize with --mode sharded is refused"
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W" --retokenize rs --mode sharded --shards 2 2); RC=$?
[ "$RC" -ne 0 ]; check "refused (exit $RC)" $?
grep -q 'sharded' <<<"$OUT"; check "and says why" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 8: a malformed extension list is refused before the clone"
for BAD in ".rs" "RS" "r s" "" "rs,.c"; do
    W=$(fixture)
    OUT=$(STUB_RC=0 run_step2 "$W" --retokenize "$BAD" 2); RC=$?
    [ "$RC" -ne 0 ]; check "refused [$BAD] (exit $RC)" $?
    rm -rf "$W"
done

# ---------------------------------------------------------------------------
echo "case 9: --mask-widened and --retokenize are not combined"
W=$(fixture)
OUT=$(STUB_RC=0 run_step2 "$W" --retokenize rs --mask-widened --mask '\.c$' 2); RC=$?
[ "$RC" -ne 0 ]; check "refused (exit $RC)" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo ""
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ]
