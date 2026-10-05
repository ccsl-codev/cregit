#!/usr/bin/env bash
# Tests for ensure_artifacts(): only the Rust tokenizer (the one compiled
# tokenizer) is rebuilt when older than its sources; other artifacts are
# existence-only. make and sbt are stubbed on PATH and log their argv.
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

# The runner takes CREGIT from $(pwd), so a fixture holds a copy of it.
# Sources are stamped OLD and artifacts NEW: an up-to-date checkout.
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
    # The guard also compares directory mtimes; unstamped, they look just edited.
    find "$f" -type d -exec touch -t "$OLD_STAMP" {} +
    for p in "${ARTIFACTS[@]}"; do
        mkdir -p "$f/$(dirname "$p")"
        echo "artifact" > "$f/$p"
        chmod +x "$f/$p"
        touch -t "$NEW_STAMP" "$f/$p"
    done
    printf '%s' "$f"
}

# cwd is logged too: the three legacy jars share one sbt command line.
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
W=$(fixture)
OUT=$(run_guard "$W"); RC=$?
[ "$RC" -eq 0 ]; check "exits 0" $?
N=$(build_count "$W"); [ "$N" -eq 0 ]; check "no build was invoked (built: $N)" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 2: a newer Rust source — the exact defect this guard exists for"
W=$(fixture)
touch -t "$NEW_STAMP" "$W/tokenize/rustTokenizer/src/main.rs"
# An equal timestamp is not stale; make the source strictly newer.
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
# Pins the accepted gap: a stale sbt jar or srcml2token is used, not rebuilt.
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
# Otherwise a typo in the source list would silently disable the guard.
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
