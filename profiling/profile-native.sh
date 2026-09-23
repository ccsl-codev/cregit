#!/usr/bin/env bash
# Profile the C++ transcoder, tokenize/srcMLtoken/srcml2token.
#
#   profiling/profile-native.sh --input FILE --out DIR [--language C] [--reps 1]
#
# perf record is the tool this wants and perf is NOT installed on this host
# (PROFILING.md has the devenv.nix diff). Valgrind's callgrind is installed, so
# that is the fallback: it gives per-function attribution today, at roughly 50x
# slowdown, which is fine for one blob and useless for a corpus.
#
#   perf      <out>/srcml2token.folded   -> a flamegraph, via stackcollapse-perf.pl
#   callgrind <out>/callgrind.annotated  -> a ranked function table, no flamegraph
#
# Build with `make -C tokenize/srcMLtoken srcml2token-profile` first: the default
# build is -O without a frame pointer, and perf cannot walk that stack.

set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=profiling/lib.sh
. "$here/lib.sh"
root=$(cd -- "$here/.." && pwd)

input=""; out=""; language="C"; reps=1
while [ $# -gt 0 ]; do
    case $1 in
        --input)    input=$2; shift 2 ;;
        --out)      out=$2; shift 2 ;;
        --language) language=$2; shift 2 ;;
        --reps)     reps=$2; shift 2 ;;
        -h|--help)  sed -n '2,17p' "$0"; exit 0 ;;
        *)          prof_die "unknown option: $1" ;;
    esac
done

[ -f "$input" ] || prof_die "--input must be an existing file"
out=$(prof_out "$out")
prof_have srcml || prof_die "srcml not on PATH (enter the devenv shell)"

bin="$root/tokenize/srcMLtoken/srcml2token-profile"
[ -x "$bin" ] || bin="$root/tokenize/srcMLtoken/srcml2token"
[ -x "$bin" ] || prof_die "srcml2token is not built"

# srcml runs once; the transcoder is what is being profiled, so its input is a
# file on disk and not a pipe that would put srcml in the same profile.
xml="$out/input.srcml.xml"
srcml -l "$language" --position "$input" >"$xml"
prof_log "srcML XML: $xml ($(wc -c <"$xml") bytes)"

if prof_have perf; then
    perf record -g --call-graph=fp -o "$out/perf.data" -- \
        sh -c "for i in \$(seq $reps); do '$bin' <'$xml' >/dev/null; done"
    if prof_have stackcollapse-perf.pl; then
        perf script -i "$out/perf.data" | stackcollapse-perf.pl >"$out/srcml2token.folded"
        prof_log "folded stacks: $out/srcml2token.folded"
    else
        perf script -i "$out/perf.data" >"$out/perf.script"
        prof_log "perf script: $out/perf.script (no stackcollapse-perf.pl; see PROFILING.md)"
    fi
    perf report -i "$out/perf.data" --stdio --sort=symbol >"$out/perf.report" 2>&1 || true
elif prof_have valgrind; then
    prof_log "perf absent; using callgrind, which is ~50x slower and gives no flamegraph"
    valgrind --tool=callgrind --callgrind-out-file="$out/callgrind.out" \
        "$bin" <"$xml" >/dev/null
    if prof_have callgrind_annotate; then
        callgrind_annotate "$out/callgrind.out" >"$out/callgrind.annotated"
        prof_log "function table: $out/callgrind.annotated"
    fi
else
    prof_die "neither perf nor valgrind is available"
fi
