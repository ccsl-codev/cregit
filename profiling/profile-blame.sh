#!/usr/bin/env bash
# Profile step 7 (blame) on one working clone, without running the pipeline.
#
#   profiling/profile-blame.sh --repo <clone> --blame-dir <dir> --out <dir> \
#       [--jobs N] [--mask '\.(c|h)$'] [--overwrite]
#
# Two things are recorded whether or not a profiler is installed:
#   <out>/blame.times.txt    wall against children CPU for the whole step
#   <out>/blame.perfile.tsv  seconds and CPU seconds per file, sorted
#
# The per-file table is the one that matters here. Step 7's cost tracks the
# individual file, not the project -- one 1,666,007-line generated file has
# consumed over an hour of CPU on its own -- so a step total says almost
# nothing and a ranked per-file table says almost everything.
#
# Devel::NYTProf, when present, adds per-function attribution over the perl.
# When absent this degrades to the two tables above: step 7 is the only step
# that can run on another machine, and it must keep needing core perl and git
# only.

set -euo pipefail

here=$(cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=profiling/lib.sh
. "$here/lib.sh"
root=$(cd -- "$here/.." && pwd)

repo=""; blamedir=""; out=""; jobs=1; overwrite=0
mask='\.(c|h)$'
while [ $# -gt 0 ]; do
    case $1 in
        --repo)      repo=$2; shift 2 ;;
        --blame-dir) blamedir=$2; shift 2 ;;
        --out)       out=$2; shift 2 ;;
        --jobs)      jobs=$2; shift 2 ;;
        --mask)      mask=$2; shift 2 ;;
        --overwrite) overwrite=1; shift ;;
        -h|--help)   sed -n '2,20p' "$0"; exit 0 ;;
        *)           prof_die "unknown option: $1" ;;
    esac
done

[ -d "$repo/.git" ] || prof_die "--repo must be a non-bare git clone"
[ -n "$blamedir" ]  || prof_die "--blame-dir <dir> is required"
out=$(prof_out "$out" "$blamedir")
mkdir -p "$blamedir"

if prof_nytprof_available perl; then
    prof_nytprof_env "$out" blame
    prof_log "Devel::NYTProf on; report with: nytprofhtml -f $out/nytprof.blame.out.<pid>"
else
    prof_log "Devel::NYTProf absent: wall, CPU and the per-file table only"
fi

# Per-file cost, measured by the profiler rather than by blameRepoFiles.pl, so
# the step itself stays unmodified. formatBlame.pl is what blameRepoFiles.pl
# forks, and it is one process per file.
printf 'wall_s\tcpu_s\tfile\n' >"$out/blame.perfile.tsv"
export CREGIT_PROFILE_PERFILE="$out/blame.perfile.raw"
export CREGIT_PROFILE_FORMATBLAME="$root/blameRepo/formatBlame.pl"
: >"$CREGIT_PROFILE_PERFILE"

overwrite_flag=()
[ "$overwrite" = 1 ] && overwrite_flag=(--overwrite)

start=$SECONDS
rc=0
perl "$root/blameRepo/blameRepoFiles.pl" \
    --jobs="$jobs" \
    --formatBlame="$here/formatBlame-timed.pl" \
    ${overwrite_flag[@]+"${overwrite_flag[@]}"} \
    "$repo" "$blamedir" "$mask" >"$out/blame.stdout.txt" 2>&1 || rc=$?
prof_report_times "$out" blame "$((SECONDS - start))"

# formatBlame-timed.pl appends one row per file to $CREGIT_PROFILE_PERFILE.
if [ -s "$out/blame.perfile.raw" ]; then
    sort -rn "$out/blame.perfile.raw" >>"$out/blame.perfile.tsv"
    rm -f "$out/blame.perfile.raw"
    prof_log "slowest files:"
    head -6 "$out/blame.perfile.tsv" >&2
fi

[ "$rc" = 0 ] || prof_log "blameRepoFiles.pl exited $rc"
