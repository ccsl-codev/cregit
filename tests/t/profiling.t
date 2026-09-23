#!/usr/bin/env perl

# The profiling harness: off by default, refuses to write into a work directory,
# and degrades rather than failing when a profiler is missing.

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $root = "$FindBin::Bin/../..";
my $prof = "$root/profiling";
my $tmp  = tempdir(CLEANUP => 1);

sub bash { return system('bash', '-c', $_[0]) }

sub bash_out {
    my ($script) = @_;
    open(my $fh, '-|', 'bash', '-c', $script) or die "bash: $!";
    local $/;
    my $out = <$fh>;
    close $fh;
    return defined $out ? $out : '';
}

my @sh = map { "$prof/$_" }
    qw(lib.sh profile-tokenize.sh profile-blame.sh profile-native.sh profile-dataset.sh);
is(bash("bash -n '$_'"), 0, "bash -n: $_") for @sh, "$root/run_pipeline_process.sh";

my @pl = ("$prof/chain-cost.pl", "$prof/formatBlame-timed.pl");
is(bash("'$^X' -c '$_' >/dev/null 2>&1"), 0, "perl -c: $_") for @pl;

# Sourcing the library must not switch anything on: a corpus run reaches this
# file only through a gate that is off unless CREGIT_PROFILE is set.
my $leak = bash_out(
    ". '$prof/lib.sh'; echo \"[\${JDK_JAVA_OPTIONS:-}][\${PERL5OPT:-}][\${NYTPROF:-}]\"");
like($leak, qr/^\[\]\[\]\[\]$/m, 'sourcing lib.sh exports no profiler variable');

# Output never lands in the pipeline work directory: step 1 deletes it.
isnt(bash(". '$prof/lib.sh'; prof_out relative/path >/dev/null 2>&1"), 0,
    'prof_out refuses a relative path');
isnt(bash(". '$prof/lib.sh'; prof_out '$tmp/work/inside' '$tmp/work' >/dev/null 2>&1"), 0,
    'prof_out refuses a path inside the work directory');
is(bash(". '$prof/lib.sh'; prof_out '$tmp/beside' '$tmp/work' >/dev/null 2>&1"), 0,
    'prof_out accepts a path beside the work directory');
ok(-d "$tmp/beside", 'prof_out created the output directory');

my $jfr = bash_out(". '$prof/lib.sh'; prof_jfr_env '$tmp/out' tokenize; echo \"\$JDK_JAVA_OPTIONS\"");
like($jfr, qr/StartFlightRecording/,            'JFR is armed through JDK_JAVA_OPTIONS');
like($jfr, qr{\Qfilename=$tmp/out/tokenize.%p.jfr\E},
    'one recording per JVM, in the caller output directory');
like($jfr, qr/jdk\.JavaMonitorEnter#threshold=1ms/,
    'lock contention is recorded at 1ms, not the 10ms of settings=profile');

my $nyt = bash_out(". '$prof/lib.sh'; prof_nytprof_env '$tmp/out' blame; echo \"\$PERL5OPT|\$NYTPROF\"");
like($nyt, qr/^-d:NYTProf\|/,   'NYTProf reaches every forked perl through PERL5OPT');
like($nyt, qr/addpid=1/,        'concurrent workers do not overwrite one profile');

# Step 7 is the only step that runs on another machine, so a missing
# Devel::NYTProf must be a no-op and not an error.
my $probe = bash(". '$prof/lib.sh'; prof_nytprof_available perl");
ok($probe == 0 || $probe >> 8 == 1, 'NYTProf detection answers without dying');

SKIP: {
    my $py = "$prof/jfr2folded.py";
    skip 'python3 not available', 3 unless bash('command -v python3 >/dev/null 2>&1') == 0;
    my $out = "$tmp/folded";
    is(bash("'$py' --out '$out' --json '$FindBin::Bin/fixtures/jfr-print.json' >/dev/null"),
        0, 'jfr2folded converts a recording dump');
    ok(-s "$out/ExecutionSample.folded", 'a CPU flamegraph input is produced');
    open(my $fh, '<', "$out/JavaMonitorEnter.folded") or die $!;
    my $line = <$fh>;
    close $fh;
    # 3.5 ms, so the contention flamegraph ranks by time held and not by count.
    like($line, qr/Mapping\.getBlob 3500000$/, 'contention is weighted by duration');
}

SKIP: {
    skip 'srcml not on PATH', 2 unless bash('command -v srcml >/dev/null 2>&1') == 0;
    my $ladder = bash_out("'$prof/chain-cost.pl' --input '$root/tokenize/srcMLtoken/tests/main.c'"
        . " --out '$tmp/chain' --dry-run 2>&1");
    like($ladder, qr/^bysha\s/m, 'chain-cost lists the full per-blob ladder');
    ok(!-d "$tmp/chain", 'a dry run writes nothing');
}

done_testing();
