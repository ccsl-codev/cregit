#!/usr/bin/env bash
# Profile step 2 (tokenize) on one repository, without running the pipeline.
#
#   profiling/profile-tokenize.sh --src <bare.git> --scratch <dir> --out <dir> \
#       [--jar blobExec/target/.../blobExec.jar] [--mode pipeline|pipeline-trees] \
#       [--perl] [--parallelism N]
#
# Drives the same java invocation step 2 uses, with JFR on. --perl adds
# Devel::NYTProf to every perl in the per-blob chain; --parallelism pins the JVM
# pool through -XX:ActiveProcessorCount, which is how a 1-thread run is compared
# against a 16-thread one.
#
# Output: <out>/tokenize.<pid>.jfr, plus tokenize.times.txt and the blobExec
# stats line, which carries blobCommandExecutions and blobsCacheHit.

set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=profiling/lib.sh
. "$here/lib.sh"
root=$(cd -- "$here/.." && pwd)

src=""; scratch=""; out=""; jar=""; mode="pipeline"; perlprof=0; parallelism=""
mask='\.(c|h)$'
while [ $# -gt 0 ]; do
    case $1 in
        --src)         src=$2; shift 2 ;;
        --scratch)     scratch=$2; shift 2 ;;
        --out)         out=$2; shift 2 ;;
        --jar)         jar=$2; shift 2 ;;
        --mode)        mode=$2; shift 2 ;;
        --mask)        mask=$2; shift 2 ;;
        --parallelism) parallelism=$2; shift 2 ;;
        --perl)        perlprof=1; shift ;;
        -h|--help)     sed -n '2,16p' "$0"; exit 0 ;;
        *)             prof_die "unknown option: $1" ;;
    esac
done

[ -d "$src" ]     || prof_die "--src must be an existing bare repository"
[ -n "$scratch" ] || prof_die "--scratch <dir> is required (it is written to and may be deleted)"
out=$(prof_out "$out" "$scratch")

if [ -z "$jar" ]; then
    jar=$(find "$root/blobExec/target" -name 'blobExec*.jar' -print 2>/dev/null | sort | tail -1)
fi
[ -n "$jar" ] && [ -f "$jar" ] || prof_die "blobExec jar not found; build it first or pass --jar"

for tool in srcml ctags java; do
    prof_have "$tool" || prof_die "$tool not on PATH (enter the devenv shell)"
done
[ -x "$root/tokenize/srcMLtoken/srcml2token" ] || prof_die "srcml2token is not built"

mkdir -p "$scratch"
dst="$scratch/dst.git"; db="$scratch/blobmap.db"; memo="$scratch/memo"
mkdir -p "$memo"
[ -e "$dst" ] && prof_die "$dst already exists; give an empty --scratch"

export BFG_MEMO_DIR="$memo"
export BFG_TOKENIZE_CMD="$root/tokenize/tokenize.pl \
  --srcml2token=$root/tokenize/srcMLtoken/srcml2token \
  --srcml=$(command -v srcml) \
  --ctags=$(command -v ctags)"

identity=$(perl "$root/tokenize/tokenizerIdentity.pl" \
    --srcml2token="$root/tokenize/srcMLtoken/srcml2token" \
    --srcml="$(command -v srcml)" \
    --ctags="$(command -v ctags)")
[ -n "$identity" ] || prof_die "cannot compute the tokenizer identity"

prof_jfr_env "$out" tokenize
[ -n "$parallelism" ] && \
    export JDK_JAVA_OPTIONS="$JDK_JAVA_OPTIONS -XX:ActiveProcessorCount=$parallelism"

if [ "$perlprof" = 1 ]; then
    if prof_nytprof_available perl; then
        prof_nytprof_env "$out" chain
        prof_log "Devel::NYTProf on for the per-blob perl chain"
    else
        prof_log "Devel::NYTProf absent; skipping the perl layer (see PROFILING.md)"
    fi
fi

mode_flag=""
[ "$mode" = pipeline ]       && mode_flag=--pipeline
[ "$mode" = pipeline-trees ] && mode_flag=--pipeline-trees

prof_log "recording to $out"
start=$SECONDS
rc=0
java -jar "$jar" ${mode_flag:+"$mode_flag"} \
    "--tokenizer-identity=$identity" \
    "$src" "$dst" "$db" \
    "$root/tokenizeByBlobId/tokenBySha.pl" \
    "$mask" >"$out/tokenize.stdout.txt" 2>&1 || rc=$?
prof_report_times "$out" tokenize "$((SECONDS - start))"
tail -3 "$out/tokenize.stdout.txt" >&2

# blobExec exits non-zero for a timed-out blob (4), a stall (5) and a parser
# crash: reported, not treated as a failed profile, because the recording is
# still complete and those statuses are themselves a finding.
[ "$rc" = 0 ] || prof_log "blobExec exited $rc; the recording is still usable"
prof_log "next: profiling/jfr2folded.py --out $out $out/tokenize.*.jfr"
