#!/usr/bin/env bash
# Profile step 10 (generate_dataset.py) with cProfile.
#
#   profiling/profile-dataset.sh --out DIR -- <generate_dataset.py arguments>
#
# cProfile and pstats are standard library, so this needs nothing added. Output:
#
#   <out>/dataset.pstats     the binary profile, for pstats or snakeviz
#   <out>/dataset.txt        cumulative time, top 60 functions
#
# Most of step 10 is inside DuckDB, which cProfile sees as one C call. That is
# the point of running it: it says how much of the step is python at all.

set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=profiling/lib.sh
. "$here/lib.sh"
root=$(cd -- "$here/.." && pwd)

out=""
while [ $# -gt 0 ]; do
    case $1 in
        --out)     out=$2; shift 2 ;;
        --)        shift; break ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *)         prof_die "unknown option: $1 (dataset arguments go after --)" ;;
    esac
done
out=$(prof_out "$out")
[ $# -gt 0 ] || prof_die "no generate_dataset.py arguments given (they go after --)"

python=${PYTHON:-python3}
prof_have "$python" || prof_die "$python not on PATH"
"$python" -c 'import duckdb' 2>/dev/null \
    || prof_log "warning: duckdb is missing, so the generator will not get far"

start=$SECONDS
"$python" -m cProfile -o "$out/dataset.pstats" \
    "$root/generate_dataset/generate_dataset.py" "$@"
prof_report_times "$out" dataset "$((SECONDS - start))"

"$python" - "$out/dataset.pstats" >"$out/dataset.txt" <<'PY'
import pstats, sys
pstats.Stats(sys.argv[1]).sort_stats("cumulative").print_stats(60)
PY
prof_log "wrote $out/dataset.pstats and $out/dataset.txt"
