#!/usr/bin/env bash
# Tests for ensure_artifacts(): the guard that decides whether a build artifact
# has to be rebuilt before a run.
#
# Why this exists. The guard used to be existence-only —
#
#     [ -x "$RUST_TOKENIZER" ] || build_rust_tokenizer
#
# — so an artifact that EXISTED but was older than its sources was never
# rebuilt. Measured on this machine on 2026-09-20:
# tokenize/rustTokenizer/target/release/rust_tokenizer was dated 2026-09-04
# 18:57 while tokenize/rustTokenizer/src/main.rs was dated 2026-09-20 21:30 —
# a 16-day-old binary running against source that had been fixed. The fix
# (729643e) removed a `line:col<TAB>` prefix from every Rust token, and because
# the stale binary kept emitting it, every downstream consumer split the token
# on the wrong field and `token_type`, `token_value`, `source_text`,
# `source_line`, `source_col` and `is_structural` were all shifted by one. Not
# one step of the pipeline errored.
#
# So the guard has to compare mtimes for that binary. SCOPE, decided
# deliberately: the Rust tokenizer and nothing else. It is the pipeline's only
# COMPILED tokenizer — CregitLanguages.pm:109-115 routes C, C++ and Java to
# tokenizeSrcMl.pl and M4 to m4Tokenizer/m4.py, all interpreted and therefore
# re-read from disk every invocation. The four sbt jars and srcml2token keep
# their existence-only guards, and the last two cases in this file PIN that, so
# the gap is a recorded decision rather than something a later reader has to
# rediscover.
#
# The other half of the requirement is the fast path: an up-to-date checkout
# must still start a run without rebuilding anything, so every case below also
# asserts what was NOT built.
#
# No real building happens: `make` and `sbt` are stubbed on PATH and record
# their argv, so a case is milliseconds and nothing depends on cargo, g++, sbt
# or a JDK. The runner is driven through --ensure-artifacts, which runs exactly
# this guard and exits.
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

# ---------------------------------------------------------------------------
# A fixture CREGIT tree: every source path the guard consults and every
# artifact it guards, and nothing else. The runner takes CREGIT from $(pwd),
# so the fixture is a directory holding a copy of the runner.
#
# Two mtime generations are used throughout: sources are stamped OLD and
# artifacts NEW, which is what an up-to-date checkout looks like. A case makes
# one source NEW to make one artifact stale.
# ---------------------------------------------------------------------------
OLD_STAMP=202601010000
NEW_STAMP=202602010000

SOURCES=(
    tokenize/srcMLtoken/Makefile
    tokenize/srcMLtoken/srcml2token.cpp
    tokenize/srcMLtoken/srcml2token.hpp
    tokenize/srcMLtoken/srcml2tokenHandlers.cpp
    tokenize/srcMLtoken/srcml2tokenHandlers.hpp
    tokenize/rustTokenizer/Makefile
    tokenize/rustTokenizer/Cargo.toml
    tokenize/rustTokenizer/Cargo.lock
    tokenize/rustTokenizer/src/main.rs
    blobExec/build.sbt
    blobExec/project/build.properties
    blobExec/project/plugins.sbt
    blobExec/src/main/scala/cregit/blobexec/Main.scala
    slickGitLog/build.sbt
    slickGitLog/project/build.properties
    slickGitLog/project/plugins.sbt
    slickGitLog/src/main/scala/gitLogToDb.scala
    persons/build.sbt
    persons/project/build.properties
    persons/project/plugins.sbt
    persons/src/main/scala/unifyPersons.scala
    remapCommits/build.sbt
    remapCommits/project/build.properties
    remapCommits/project/plugins.sbt
    remapCommits/src/main/scala/remapCommits.scala
)

ARTIFACTS=(
    tokenize/srcMLtoken/srcml2token
    tokenize/rustTokenizer/target/release/rust_tokenizer
    blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar
    slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar
    persons/target/scala-2.10/persons_2.10-0.1-SNAPSHOT-one-jar.jar
    remapCommits/target/scala-2.10/remapcommits_2.10-0.1-SNAPSHOT-one-jar.jar
)

fixture() {
    local f p
    f=$(mktemp -d "${TMPDIR:-/tmp}/ensureart-XXXXXX")
    cp "$RUNNER" "$f/run_pipeline_process.sh"
    for p in "${SOURCES[@]}"; do
        mkdir -p "$f/$(dirname "$p")"
        echo "source" > "$f/$p"
        touch -t "$OLD_STAMP" "$f/$p"
    done
    # Directories are sources too — the guard passes whole source trees to find,
    # and a directory's own mtime is what changes when a file is added or
    # removed. Stamp them OLD as well, or a freshly created fixture looks like a
    # checkout whose every source tree was just edited.
    find "$f" -type d -exec touch -t "$OLD_STAMP" {} +
    for p in "${ARTIFACTS[@]}"; do
        mkdir -p "$f/$(dirname "$p")"
        echo "artifact" > "$f/$p"
        chmod +x "$f/$p"
        touch -t "$NEW_STAMP" "$f/$p"
    done
    printf '%s' "$f"
}

# `make` and `sbt` that record their argv and do nothing else. The working
# directory goes in the record too: the three legacy jars share one sbt command
# line and are told apart only by the directory it runs in.
make_stubs() {  # $1 = bin dir
    local b=$1 tool
    for tool in make sbt; do
        cat > "$b/$tool" <<STUB
#!/usr/bin/env bash
printf '$tool [cwd=%s] %s\n' "\$PWD" "\$*" >> "\$STUB_ARGV"
exit 0
STUB
        chmod +x "$b/$tool"
    done
}

BIN=$(mktemp -d "${TMPDIR:-/tmp}/ensurebin-XXXXXX")
make_stubs "$BIN"
trap 'rm -rf "$BIN"' EXIT

# Run the guard in a fixture. Prints the runner's own output; the builds it
# invoked land in $ARGV.
run_guard() {  # $1 = fixture dir
    local f=$1
    ARGV="$f/argv.log"
    : > "$ARGV"
    ( cd "$f" \
      && STUB_ARGV="$ARGV" PATH="$BIN:$PATH" LEGACY_JAVA_HOME=/nonexistent/jdk8 \
         timeout 120 bash ./run_pipeline_process.sh \
            --repo-url /nonexistent/does-not-exist.git \
            --repo-name proj \
            --work "$f/work" \
            --mask '\.c$' \
            --ensure-artifacts 2>&1 )
}

built() {  # $1 = fixture, $2 = grep pattern for the build command
    grep -q -- "$2" "$1/argv.log"
}

build_count() {  # $1 = fixture
    if [ -s "$1/argv.log" ]; then wc -l < "$1/argv.log" | tr -d ' '; else echo 0; fi
}

# ---------------------------------------------------------------------------
echo "case 1: everything current — the fast path builds nothing"
# The whole reason the guard cannot simply always rebuild: a corpus run starts
# 188 times, and six builds each would dominate the schedule.
W=$(fixture)
OUT=$(run_guard "$W"); RC=$?
[ "$RC" -eq 0 ]; check "exits 0" $?
N=$(build_count "$W"); [ "$N" -eq 0 ]; check "no build was invoked (built: $N)" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 2: a newer Rust source — the exact defect this guard exists for"
W=$(fixture)
touch -t "$NEW_STAMP" "$W/tokenize/rustTokenizer/src/main.rs"
# The artifact and the source now share a timestamp, which is NOT stale: make
# the source strictly newer, as a real edit would.
touch "$W/tokenize/rustTokenizer/src/main.rs"
OUT=$(run_guard "$W"); RC=$?
[ "$RC" -eq 0 ]; check "exits 0" $?
built "$W" 'rustTokenizer'; check "rustTokenizer was rebuilt" $?
N=$(build_count "$W"); [ "$N" -eq 1 ]; check "and nothing else was (built: $N)" $?
grep -q 'main.rs' <<<"$OUT"; check "the log names the source that triggered it" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 3: a missing artifact still rebuilds (the old behaviour is kept)"
W=$(fixture)
rm -f "$W/tokenize/rustTokenizer/target/release/rust_tokenizer"
OUT=$(run_guard "$W"); RC=$?
[ "$RC" -eq 0 ]; check "exits 0" $?
built "$W" 'rustTokenizer'; check "rustTokenizer was rebuilt" $?
N=$(build_count "$W"); [ "$N" -eq 1 ]; check "and nothing else was (built: $N)" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
# Each declared Rust source, one case each. Cargo.lock matters as much as
# main.rs: a dependency bump changes the tokenizer's behaviour without touching a
# line of this project's code.
# ---------------------------------------------------------------------------
rust_stale_case() {  # $1 = source path, relative to the fixture
    echo "case: a newer $1 rebuilds the Rust tokenizer"
    local w
    w=$(fixture)
    touch -t "$NEW_STAMP" "$w/$1"
    touch "$w/$1"
    local out rc
    out=$(run_guard "$w"); rc=$?
    [ "$rc" -eq 0 ]; check "exits 0" $?
    built "$w" 'rustTokenizer'; check "rustTokenizer was rebuilt" $?
    local n; n=$(build_count "$w"); [ "$n" -eq 1 ]; check "nothing else was (built: $n)" $?
    rm -rf "$w"
}

rust_stale_case tokenize/rustTokenizer/Cargo.lock
rust_stale_case tokenize/rustTokenizer/Cargo.toml
rust_stale_case tokenize/rustTokenizer/Makefile

# ---------------------------------------------------------------------------
# The accepted gap, pinned so it is a decision and not a surprise.
#
# The four sbt jars and srcml2token are still guarded on existence alone. A
# stale one WOULD be used, silently. Rust was fixed alone because it is the only
# compiled tokenizer (CregitLanguages.pm:109-115 routes the other three
# languages to interpreted scripts, which cannot go stale this way) and because
# it is the artifact that actually failed. These cases assert the current
# behaviour so that whoever changes their minds has to change a test and read
# this comment.
# ---------------------------------------------------------------------------
gap_case() {  # $1 = label, $2 = source to touch, $3 = grep pattern for its build
    echo "case: a newer $1 source does NOT rebuild it — known, accepted gap"
    local w
    w=$(fixture)
    touch -t "$NEW_STAMP" "$w/$2"
    touch "$w/$2"
    local out rc
    out=$(run_guard "$w"); rc=$?
    [ "$rc" -eq 0 ]; check "exits 0" $?
    local n; n=$(build_count "$w"); [ "$n" -eq 0 ]; check "nothing was built (built: $n)" $?
    rm -rf "$w"
}

gap_case srcml2token  tokenize/srcMLtoken/srcml2token.cpp                 'srcMLtoken'
gap_case blobExec     blobExec/src/main/scala/cregit/blobexec/Main.scala  'assembly'
gap_case slickGitLog  slickGitLog/src/main/scala/gitLogToDb.scala         'one-jar'
gap_case persons      persons/src/main/scala/unifyPersons.scala           'one-jar'
gap_case remapCommits remapCommits/src/main/scala/remapCommits.scala      'one-jar'

# But a MISSING one is still built, for all five. That behaviour predates this
# change and must survive it.
missing_case() {  # $1 = label, $2 = artifact to delete, $3 = grep pattern
    echo "case: a missing $1 is still built"
    local w
    w=$(fixture)
    rm -f "$w/$2"
    local out rc
    out=$(run_guard "$w"); rc=$?
    [ "$rc" -eq 0 ]; check "exits 0" $?
    built "$w" "$3"; check "$1 was built" $?
    local n; n=$(build_count "$w"); [ "$n" -eq 1 ]; check "and only it (built: $n)" $?
    rm -rf "$w"
}

missing_case srcml2token  tokenize/srcMLtoken/srcml2token                                     'srcMLtoken'
missing_case blobExec     blobExec/target/scala-2.13/blobExec-0.1.0-assembly.jar              'assembly'
missing_case slickGitLog  slickGitLog/target/scala-2.10/slickgitlog_2.10-0.1-SNAPSHOT-one-jar.jar 'one-jar'

# ---------------------------------------------------------------------------
echo "case: a Rust source path the guard names but the checkout lacks is fatal"
# A typo in the source list would silently switch the guard off for that
# artifact, which is exactly the class of failure this whole file exists to
# stop. It must be loud instead.
W=$(fixture)
rm -f "$W/tokenize/rustTokenizer/Cargo.lock"
OUT=$(run_guard "$W"); RC=$?
[ "$RC" -ne 0 ]; check "the run fails (exit $RC)" $?
grep -q 'Cargo.lock' <<<"$OUT"; check "and names the missing source path" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case: --ensure-artifacts does not run the pipeline"
W=$(fixture)
OUT=$(run_guard "$W"); RC=$?
[ "$RC" -eq 0 ]; check "exits 0" $?
[ ! -d "$W/work" ]; check "the work directory was never created" $?
grep -q 'Step 1' <<<"$OUT"; [ $? -ne 0 ]; check "no pipeline step ran" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo ""
echo "passed: $PASS   failed: $FAIL"
[ "$FAIL" -eq 0 ]
