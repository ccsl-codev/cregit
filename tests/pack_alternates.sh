#!/usr/bin/env bash
# Tests step 2's borrowed objects: blobExec --alternates by default, the pack that
# must copy them into the cregit repo, and what happens when that pack fails.
# java is stubbed: as blobExec it builds a real dst that borrows src's objects.
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

# As blobExec: dst gets one new commit on src's tree. With --alternates it
# borrows that tree and its blobs; without, it copies them (git fetch).
# STUB_LOCK_PACK=1 then makes objects/pack read-only, so a repack fails.
# STUB_KILL_WALK=1 drops the ref again and SIGKILLs itself, as a killed walk.
make_stub_java() {  # $1 = bin dir
    cat > "$1/java" <<'STUB'
#!/usr/bin/env bash
[ -n "${STUB_ARGV:-}" ] && printf '%s\n' "$*" >> "$STUB_ARGV"
case "$*" in *blobExec*) ;; *) exit 0 ;; esac
pos=(); skip=0; alt=0
for a in "$@"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    case "$a" in
        -jar) skip=1 ;;
        --alternates) alt=1 ;;
        -*)   ;;
        *)    pos+=("$a") ;;
    esac
done
src=${pos[0]} dst=${pos[1]}
[ -d "$dst" ] || git init -q --bare "$dst"
if [ "$alt" = 1 ]; then
    echo "$(cd "$src/objects" && pwd)" > "$dst/objects/info/alternates"
else
    git --git-dir="$dst" fetch -q "$src" "+refs/heads/*:refs/remotes/src/*"
fi
tree=$(git --git-dir="$src" rev-parse 'HEAD^{tree}')
c=$(echo rewritten | GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@x GIT_COMMITTER_NAME=t \
    GIT_COMMITTER_EMAIL=t@x GIT_AUTHOR_DATE='@1700000000 +0000' GIT_COMMITTER_DATE='@1700000000 +0000' \
    git --git-dir="$dst" commit-tree "$tree")
git --git-dir="$dst" update-ref refs/heads/master "$c"
git --git-dir="$dst" for-each-ref --format='%(refname)' refs/remotes | while read -r r; do
    git --git-dir="$dst" update-ref -d "$r"; done
[ "${STUB_LOCK_PACK:-0}" = 1 ] && chmod a-w "$dst/objects/pack"
# A walk killed mid-way: dst borrows and holds a new object, but no ref yet.
if [ "${STUB_KILL_WALK:-0}" = 1 ]; then
    git --git-dir="$dst" update-ref -d refs/heads/master
    kill -9 $$
fi
exit 0
STUB
    chmod +x "$1/java"
}

# The runner takes CREGIT from $(pwd): tokenize/ for the identity, plus build
# files and placeholder artifacts, or the staleness guard would build.
repo_fixture() {
    local f p
    f=$(mktemp -d "${TMPDIR:-/tmp}/packrepo-XXXXXX")
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
    printf '#!/bin/sh\necho "%s/libsrcml"\n' "$f" > "$f/tokenize/srcMLtoken/srcml2token"
    echo "placeholder v1" > "$f/libsrcml"
    printf '%s' "$f"
}

# A work dir resumable at step 2: a real original bare repo with two commits.
fixture() {
    local w t
    w=$(mktemp -d "${TMPDIR:-/tmp}/packwork-XXXXXX")
    t="$w/tmp-src"
    git init -q "$t"
    echo "int a;" > "$t/a.c"; echo "readme" > "$t/README"
    git -C "$t" add . && git -C "$t" -c user.name=t -c user.email=t@x commit -q -m one
    echo "readme 2" > "$t/README"
    git -C "$t" -c user.name=t -c user.email=t@x commit -q -am two
    git clone -q --bare "$t" "$w/proj-original.git"
    rm -rf "$t"
    mkdir -p "$w/memo"
    printf '%s' "$w"
}

run_from() {  # $1 = work dir, rest = extra runner args + FROM_STEP
    local w=$1; shift
    ( cd "$REPO" \
      && PATH="$BIN:$PATH" LEGACY_JAVA_HOME=/nonexistent/jdk8 timeout 300 \
         bash ./run_pipeline_process.sh \
            --repo-url /nonexistent/does-not-exist.git \
            --repo-name proj \
            --work "$w" \
            --skip-html --mask '\.c$' \
            "$@" 2>&1 )
}

DST=proj-cregit.git

# True when dst holds every object it references, without any alternates file.
self_contained() {  # $1 = dst
    [ ! -e "$1/objects/info/alternates" ] && [ ! -e "$1/objects/info/alternates.packing" ] \
        && git --git-dir="$1" fsck --connectivity-only --no-dangling >/dev/null 2>&1 \
        && git --git-dir="$1" cat-file -e 'master:README'
}

BIN=$(mktemp -d "${TMPDIR:-/tmp}/packbin-XXXXXX")
make_stub_java "$BIN"
REPO=$(repo_fixture)
trap 'rm -rf "$BIN" "$REPO"' EXIT

# ---------------------------------------------------------------------------
echo "case 1: by default step 2 borrows, and the pack leaves dst whole on its own"
W=$(fixture); ARGV="$W/argv.log"
OUT=$(STUB_ARGV="$ARGV" run_from "$W" 2)
grep -q 'Step 3' <<<"$OUT"; check "step 2 completed and the pipeline moved on" $?
grep 'blobExec' "$ARGV" | grep -q -- '--alternates'; check "blobExec was given --alternates" $?
grep -q 'no longer borrows any' <<<"$OUT"; check "the runner packed the borrowed objects in" $?
self_contained "$W/$DST"; check "dst has no alternates file and fsck passes without it" $?
git --git-dir="$W/$DST" count-objects -v | grep -q '^count: 0'
check "every object is packed, none left loose" $?
[ ! -e "$W/PACK-FAILED" ]; check "no PACK-FAILED marker" $?
! compgen -G "$W/$DST/objects/pack/*.bitmap" >/dev/null; check "and no bitmap index" $?
git clone -q "$W/$DST" "$W/clone" && [ -f "$W/clone/README" ] && [ ! -e "$W/clone/.git/objects/info/alternates" ]
check "a step-6 style clone reads every file and borrows nothing" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 2: --copy-objects, CREGIT_COPY_OBJECTS=1 and --gc none all copy instead"
for how in flag env gcnone; do
    W=$(fixture); ARGV="$W/argv.log"
    case $how in
        flag)   OUT=$(STUB_ARGV="$ARGV" run_from "$W" --copy-objects 2) ;;
        env)    OUT=$(CREGIT_COPY_OBJECTS=1 STUB_ARGV="$ARGV" run_from "$W" 2) ;;
        gcnone) OUT=$(STUB_ARGV="$ARGV" run_from "$W" --gc none 2) ;;
    esac
    grep 'blobExec' "$ARGV" | grep -q -- '--alternates'; [ $? -ne 0 ]
    check "$how: blobExec was NOT given --alternates" $?
    [ ! -e "$W/$DST/objects/info/alternates" ] && git --git-dir="$W/$DST" cat-file -e 'master:README'
    check "$how: dst holds its objects without borrowing" $?
    if [ "$how" = flag ]; then
        compgen -G "$W/$DST/objects/pack/*.pack" >/dev/null \
            && ! compgen -G "$W/$DST/objects/pack/*.bitmap" >/dev/null
        check "$how: gc packed dst, without a bitmap index" $?
    fi
    rm -rf "$W"
done

# ---------------------------------------------------------------------------
echo "case 2b: CREGIT_BLOBEXEC_OPTS and CREGIT_BLOBEXEC_JAVA_OPTS reach blobExec"
W=$(fixture); ARGV="$W/argv.log"
OUT=$(CREGIT_BLOBEXEC_OPTS="--commits-per-transaction=1 --loose-compression=-1" \
      CREGIT_BLOBEXEC_JAVA_OPTS="-Dorg.eclipse.jgit.util.sha1.implementation=java -XX:ActiveProcessorCount=3" \
      STUB_ARGV="$ARGV" run_from "$W" 2)
grep blobExec "$ARGV" | grep -q -- '--commits-per-transaction=1 --loose-compression=-1 '
check "the blobExec flags, before the positional arguments" $?
grep blobExec "$ARGV" | grep -q -- '^-Dorg.eclipse.jgit.util.sha1.implementation=java -XX:ActiveProcessorCount=3 -jar '
check "the JVM flags, before -jar" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 3: a failed pack stops the run, resumably, and leaves dst readable"
W=$(fixture)
OUT=$(STUB_LOCK_PACK=1 run_from "$W" 2); RC=$?
[ "$RC" -ne 0 ]; check "the run failed (got exit $RC)" $?
grep -q 'Step 3' <<<"$OUT"; [ $? -ne 0 ]; check "and stopped before step 3" $?
[ -f "$W/PACK-FAILED" ]; check "a PACK-FAILED marker was written" $?
[ -f "$W/$DST/objects/info/alternates" ]; check "the alternates file is still in place" $?
git --git-dir="$W/$DST" cat-file -e 'master:README'; check "so dst still reads the borrowed blobs" $?
chmod u+w "$W/$DST/objects/pack"
OUT=$(run_from "$W" 3)
grep -q 'still borrows objects; packing it before step 3' <<<"$OUT"
check "a resume at step 3 packs first" $?
self_contained "$W/$DST"; check "and dst is then whole on its own" $?
[ ! -e "$W/PACK-FAILED" ]; check "and the marker is gone" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 4: a run killed while fsck ran (file set aside) is put back and redone"
W=$(fixture)
OUT=$(STUB_LOCK_PACK=1 run_from "$W" 2)
chmod u+w "$W/$DST/objects/pack"
mv "$W/$DST/objects/info/alternates" "$W/$DST/objects/info/alternates.packing"
OUT=$(run_from "$W" 4)
grep -q 'packing it before step 4' <<<"$OUT"; check "a resume at step 4 packs first" $?
self_contained "$W/$DST"; check "and dst is whole, with neither file left" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 5: a full run (FROM_STEP=1) keeps the work dir when the pack fails"
W=$(mktemp -d "${TMPDIR:-/tmp}/packfull-XXXXXX")
S=$(fixture)
OUT=$( cd "$REPO" && STUB_LOCK_PACK=1 PATH="$BIN:$PATH" LEGACY_JAVA_HOME=/nonexistent/jdk8 timeout 300 \
       bash ./run_pipeline_process.sh --repo-url "$S/proj-original.git" --repo-name proj \
            --work "$W/work" --skip-html --mask '\.c$' 2>&1 ); RC=$?
[ "$RC" -ne 0 ] && [ -f "$W/work/PACK-FAILED" ] && [ -d "$W/work/$DST" ]
check "the EXIT trap kept \$WORK: the marker says it is resumable (exit $RC)" $?
chmod -R u+w "$W" "$S"; rm -rf "$W" "$S"

# ---------------------------------------------------------------------------
echo "case 6: --gc aggressive borrows too, and its repack leaves dst whole"
W=$(fixture); ARGV="$W/argv.log"
OUT=$(STUB_ARGV="$ARGV" run_from "$W" --gc aggressive 2)
grep 'blobExec' "$ARGV" | grep -q -- '--alternates'; check "blobExec was given --alternates" $?
grep -q 'no longer borrows any' <<<"$OUT" && grep -q 'Step 3' <<<"$OUT"
check "the runner packed the borrowed objects in and moved on" $?
self_contained "$W/$DST"; check "dst has no alternates file and fsck passes without it" $?
! compgen -G "$W/$DST/objects/pack/*.bitmap" >/dev/null; check "and no bitmap index" $?
rm -rf "$W"

# ---------------------------------------------------------------------------
echo "case 7: a walk killed mid-way leaves dst borrowing; a step-2 resume finishes it"
W=$(fixture)
OUT=$(STUB_KILL_WALK=1 run_from "$W" 2); RC=$?
[ "$RC" -ne 0 ] && ! grep -q 'Step 3' <<<"$OUT"; check "the run failed in step 2 (exit $RC)" $?
[ -f "$W/$DST/objects/info/alternates" ] && [ -d "$W/proj-original.git" ]
check "dst still borrows, and src is kept" $?
OUT=$(run_from "$W" 2)
grep -q 'no longer borrows any' <<<"$OUT" && grep -q 'Step 3' <<<"$OUT"
check "the resume ran step 2 again, packed, and moved on" $?
self_contained "$W/$DST"; check "dst is whole on its own" $?
git clone -q "$W/$DST" "$W/clone" && [ -f "$W/clone/README" ] && [ ! -e "$W/clone/.git/objects/info/alternates" ]
check "a step-6 style clone reads every file and borrows nothing" $?
rm -rf "$W"

echo ""
echo "pack_alternates: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
