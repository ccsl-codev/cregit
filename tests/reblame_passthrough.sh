#!/usr/bin/env bash
# Tests for the runner's half of the re-blame mechanism: the --reblame
# passthrough to step 7, and the guard that stops the flag being a quiet no-op.
#
# Why this exists. blameRepoFiles.pl skips any file whose .blame output already
# exists, and step 7 never passed --overwrite. So a deliberate re-blame — change a
# git blame flag, resume at step 7 — reported every file as already done, exited 0,
# and step 10 rebuilt the Parquet from the OLD blame. Measured on a three-project
# pilot before this flag existed: 0 of 15,036,195 tokens changed author, with
# identical schema and identical row counts, which is exactly what a successful run
# looks like. The unit test for --overwrite already existed and passed; nothing
# tested that the runner passes it. That is the gap these cases close.
#
# `blameRepoFiles.pl` and `java` are stubbed on PATH / in the fixture and record
# their argv, so no blame runs and no case takes more than a second or two.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNNER="$ROOT/run_pipeline_process.sh"
PASS=0
FAIL=0

check() {  # $1 = description, $2 = condition result (0/1)
    if [ "$2" -eq 0 ]; then
        echo "  ok   — $1"; PASS=$((PASS + 1))
    else
        echo "  FAIL — $1"; FAIL=$((FAIL + 1))
    fi
}

make_stub_java() {  # $1 = bin dir
    cat > "$1/java" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
    chmod +x "$1/java"
}

# A throwaway CREGIT tree. The runner takes CREGIT from $(pwd), and its artifact
# staleness guard runs BEFORE any step — including a step-7 resume — so the tree
# needs tokenize/ and each module's build files plus placeholder artifacts, or the
# guard starts a real build and the run dies before step 7. The artifacts are
# placeholders: the guard digests their bytes, so any content works.
#
# On top of that, a stub blameRepoFiles.pl that appends its argv to $BLAME_ARGV.
# The real script is not used, because the assertion is about which flags arrive,
# not about what blame produces.
repo_fixture() {
    local f p
    f=$(mktemp -d "${TMPDIR:-/tmp}/reblamerepo-XXXXXX")
    cp "$RUNNER" "$f/run_pipeline_process.sh"
    cp -r "$ROOT/tokenize" "$f/tokenize"
    rm -rf "$f/tokenize/rustTokenizer/target" "$f/tokenize/srcMLtoken/srcml2token"
    for p in blobExec slickGitLog persons remapCommits; do
        mkdir -p "$f/$p/src"
        cp "$ROOT/$p/build.sbt" "$f/$p/build.sbt"
        cp -r "$ROOT/$p/project" "$f/$p/project"
        rm -rf "$f/$p/project/target" "$f/$p/project/project"
    done
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
    mkdir -p "$f/blameRepo"
    cat > "$f/blameRepo/blameRepoFiles.pl" <<'STUB'
#!/usr/bin/env perl
open(my $fh, '>>', $ENV{BLAME_ARGV}) or exit 0;
print $fh join(' ', @ARGV), "\n";
close $fh;
exit 0;
STUB
    chmod +x "$f/blameRepo/blameRepoFiles.pl"
    : > "$f/blameRepo/formatBlame.pl"
    printf '%s' "$f"
}

# A work directory that looks finished through step 6: the non-bare cregit clone
# step 7 requires, and a blame directory holding output a re-blame must replace.
fixture() {
    local w
    w=$(mktemp -d "${TMPDIR:-/tmp}/reblamework-XXXXXX")
    mkdir -p "$w/proj-cregit" "$w/proj-cregit.git" "$w/blame" "$w/html"
    echo "days of blame" > "$w/blame/one.c.blame"
    echo "db"            > "$w/proj-cregit.db"
    printf '%s' "$w"
}

run_from() {  # $1 = work dir, $2 = FROM_STEP, rest = extra runner args
    local w=$1 from=$2; shift 2
    # BLAME_ARGV must be exported, not just assigned. `VAR=x somefunc` sets the
    # variable for the function without exporting it, so the runner's child shell
    # and the stub perl beneath it never saw it.
    ( cd "$REPO" \
      && PATH="$BIN:$PATH" BLAME_ARGV="${BLAME_ARGV:-}" timeout 120 \
         bash ./run_pipeline_process.sh \
            --repo-url /nonexistent/does-not-exist.git \
            --repo-name proj \
            --work "$w" \
            --skip-html --gc none \
            --mask '\.c$' \
            "$@" "$from" 2>&1 )
}

BIN=$(mktemp -d "${TMPDIR:-/tmp}/reblamebin-XXXXXX")
make_stub_java "$BIN"
REPO=$(repo_fixture)
trap 'rm -rf "$BIN" "$REPO"' EXIT

# ---------------------------------------------------------------------------
echo "case 1: without --reblame, step 7 does NOT get --overwrite"
W=$(fixture); ARGV="$W/blame-argv.log"
OUT=$(BLAME_ARGV="$ARGV" run_from "$W" 7); RC=$?
[ -f "$ARGV" ]; check "step 7 invoked blameRepoFiles.pl" $?
grep -q -- '--jobs=' "$ARGV"; check "and passed --jobs" $?
grep -q -- '--overwrite' "$ARGV"; [ $? -ne 0 ]
check "and NOT --overwrite: a resume stays cheap by default" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 2: with --reblame, step 7 gets --overwrite"
W=$(fixture); ARGV="$W/blame-argv.log"
OUT=$(BLAME_ARGV="$ARGV" run_from "$W" 7 --reblame); RC=$?
[ -f "$ARGV" ]; check "step 7 invoked blameRepoFiles.pl" $?
grep -q -- '--overwrite' "$ARGV"; check "--overwrite reached blameRepoFiles.pl" $?
grep -q -- '--formatBlame=' "$ARGV"; check "and the formatter is still named" $?
# Match the log sentence, not the substring "reblame". The flag name appears in the
# usage text and in every refusal message too, so a bare substring match passed even
# when step 7 never ran.
grep -q 'replacing every .blame file' <<<"$OUT"
check "and the run says it is replacing every .blame file" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 3: --reblame after step 7 is refused, because it would be skipped"
# The whole point of the flag is to close a silent no-op. Accepting it where it
# cannot act would reopen the same hole one step later.
for FROM in 8 9 10; do
    W=$(fixture)
    OUT=$(BLAME_ARGV="$W/blame-argv.log" run_from "$W" "$FROM" --reblame); RC=$?
    [ "$RC" -ne 0 ]; check "refused at step $FROM (exit $RC)" $?
    grep -q 'FROM_STEP<=7' <<<"$OUT"; check "and names the step it needs" $?
    rm -rf "$W"
done

# ---------------------------------------------------------------------------
echo "case 4: --reblame is accepted at every step that still reaches step 7"
for FROM in 2 7; do
    W=$(fixture); ARGV="$W/blame-argv.log"
    OUT=$(BLAME_ARGV="$ARGV" run_from "$W" "$FROM" --reblame); RC=$?
    grep -q 'FROM_STEP<=7' <<<"$OUT"; [ $? -ne 0 ]
    check "not refused at step $FROM" $?
    rm -rf "$W"
done

# ---------------------------------------------------------------------------
echo ""
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ]
