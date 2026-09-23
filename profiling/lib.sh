#!/usr/bin/env bash
# Shared helpers for the profiling harness. Sourced, never executed.
#
# Nothing here runs during an ordinary pipeline run: run_pipeline_process.sh
# sources this file only when CREGIT_PROFILE is set.

prof_die() { echo "profiling: $*" >&2; exit 2; }
prof_log() { echo "profiling: $*" >&2; }
prof_have() { command -v "$1" >/dev/null 2>&1; }

# Output goes where the caller said, and never inside a pipeline work directory:
# step 1 deletes $WORK, so a profile written there is a profile deleted by the
# next run.
prof_out() {
    local out=$1 work=${2:-}
    [ -n "$out" ] || prof_die "no output directory given (--out)"
    case $out in
        /*) : ;;
        *)  prof_die "--out must be an absolute path (got '$out')" ;;
    esac
    if [ -n "$work" ]; then
        case "$out/" in
            "$work"/*) prof_die "refusing to write profiles inside the work directory $work" ;;
        esac
    fi
    mkdir -p "$out" || prof_die "cannot create $out"
    printf '%s\n' "$out"
}

# Devel::NYTProf is not in devenv.nix; see PROFILING.md for the proposed diff.
# Step 7 is the only step that runs on another machine, so its portability to
# core perl plus git is load-bearing: a missing profiler degrades to a no-op.
# Probed by looking for the file rather than loading it, because loading
# Devel::NYTProf outside -d: has side effects.
prof_nytprof_available() {
    "${1:-perl}" -e 'for (@INC) { exit 0 if -f "$_/Devel/NYTProf.pm" } exit 1' 2>/dev/null
}

# PERL5OPT reaches every perl the step forks, so one export covers the whole
# chain without touching a single perl source file. addpid keeps concurrent
# workers from overwriting one another's output.
prof_nytprof_env() {
    local outdir=$1 tag=$2
    export NYTPROF="file=$outdir/nytprof.$tag.out:addpid=1:sigexit=int,term"
    export PERL5OPT="-d:NYTProf"
}

# JFR needs no dependency and no code change: the JDK launcher reads
# JDK_JAVA_OPTIONS itself. Event overrides rather than a .jfc file, so the whole
# configuration is one greppable string.
#
#   ExecutionSample     where CPU goes
#   JavaMonitorEnter    time lost to a contended lock -- dbLock in Walker.scala
#   ThreadPark          time lost waiting on the queue, which is not the same thing
#   ObjectAllocationSample  allocation profile
#   FileRead/FileWrite  I/O wait on the object store
#
# The thresholds are 1 ms, not the 10 ms of settings=profile: a lock held
# briefly but taken per blob is invisible at 10 ms and is exactly the shape
# being looked for. %p gives one file per JVM, so profiling one step does not
# clobber the recordings of the other four java steps.
prof_jfr_env() {
    local outdir=$1 tag=$2
    export JDK_JAVA_OPTIONS="-XX:StartFlightRecording=settings=profile,filename=$outdir/$tag.%p.jfr,dumponexit=true,jdk.ExecutionSample#period=1ms,jdk.NativeMethodSample#period=1ms,jdk.JavaMonitorEnter#threshold=1ms,jdk.ThreadPark#threshold=1ms,jdk.FileRead#threshold=1ms,jdk.FileWrite#threshold=1ms,jdk.ObjectAllocationSample#throttle=300/s"
}

# CPU against wall for a whole process tree. /usr/bin/time is not installed on
# this host; the bash times builtin reports children's accumulated user and
# system time, which is what distinguishes work from waiting.
prof_report_times() {
    local outdir=$1 tag=$2 wall=$3
    {
        printf 'tag\t%s\n' "$tag"
        printf 'wall_s\t%s\n' "$wall"
        times
    } >"$outdir/$tag.times.txt"
    prof_log "wall and cpu written to $outdir/$tag.times.txt"
}
