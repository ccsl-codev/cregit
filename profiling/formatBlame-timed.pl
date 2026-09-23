#!/usr/bin/env perl

# A drop-in for blameRepo/formatBlame.pl that records what one file cost, then
# hands the same argv to the real thing. Passed by profile-blame.sh through
# blameRepoFiles.pl --formatBlame; nothing in the pipeline references it.
#
# Step 7's cost tracks the individual file, not the project, so the per-file row
# is the measurement. cpu_s comes from times(), which counts the git blame child
# too, and needs nothing core perl does not already have.

use strict;
use warnings;
use Time::HiRes qw(time);

my $real = $ENV{CREGIT_PROFILE_FORMATBLAME}
    or die "CREGIT_PROFILE_FORMATBLAME is not set\n";
my $out = $ENV{CREGIT_PROFILE_PERFILE}
    or die "CREGIT_PROFILE_PERFILE is not set\n";

# argv from blameRepoFiles.pl: --blameExtension=..., repo, name, destination.
my $name = @ARGV >= 2 ? $ARGV[$#ARGV - 1] : 'unknown';

my $t0 = time;
my @c0 = times;
my $status = system($^X, $real, @ARGV);
my @c1 = times;

my $wall = time - $t0;
my $cpu  = ($c1[2] - $c0[2]) + ($c1[3] - $c0[3]);

# One append per file: several workers share this file.
if (open(my $fh, '>>', $out)) {
    printf {$fh} "%.2f\t%.2f\t%s\n", $wall, $cpu, $name;
    close $fh;
}

exit($status == 0 ? 0 : 1);
