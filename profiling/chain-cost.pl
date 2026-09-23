#!/usr/bin/env perl

# Decompose the per-blob cost of step 2 into its layers, by timing a ladder of
# real invocations on the same input.
#
#   profiling/chain-cost.pl --input FILE --out DIR [--reps 20] [--dry-run]
#
# The project's ranked lever list puts a persistent tokenizer worker second, at
# 1.4-1.6x, on the strength of one sentence: 82.2 ms fixed cost per blob, of
# which 54.8 ms is perl and shell startup. No derivation of those two numbers is
# recorded anywhere. This script derives them, and needs no profiler to do it.
#
# Each rung adds one layer of the chain. A difference between two rungs is that
# layer's cost:
#
#   sh        /bin/sh -c true                     shell spawn
#   perl      perl -e0                            interpreter startup
#   srcml     srcml -> /dev/null                  the parser alone
#   native    srcml | srcml2token                 + the C++ transcoder
#   srcmlpl   tokenizeSrcMl.pl                    + 1 perl, 1 bash, ctags
#   dispatch  tokenize.pl                         + 1 perl (the dispatcher)
#   bysha     tokenBySha.pl, cold memo            + 1 perl, 1 sh, sha1, tempdir
#   memohit   tokenBySha.pl, warm memo            the cache-hit path
#
#   bysha - native  is the cost a persistent worker would remove.
#   bysha           is the fixed-plus-variable cost the 82.2 ms figure names.
#   srcmlpl - native isolates the ctags double parse.
#
# Reports median, min and max wall per rung, and CPU seconds from times(), which
# counts every child. Nothing is written outside --out.

use strict;
use warnings;
use Getopt::Long;
use File::Basename qw(basename dirname);
use File::Temp qw(tempdir);
use File::Spec;
use Cwd qw(abs_path);
use Time::HiRes qw(time);

my ($input, $out, $reps, $dryRun, $help) = (undef, undef, 20, 0, 0);
GetOptions(
    'input=s' => \$input,
    'out=s'   => \$out,
    'reps=i'  => \$reps,
    'dry-run' => \$dryRun,
    'help'    => \$help,
) or die "bad arguments\n";

if ($help) { print_usage(); exit 0 }
die "--input FILE is required\n" unless defined $input && -f $input;
die "--out DIR is required\n"    unless defined $out;
die "--out must be an absolute path\n" unless File::Spec->file_name_is_absolute($out);
die "--reps must be at least 1\n" unless $reps >= 1;

my $root = abs_path(dirname(__FILE__) . '/..');
my $srcml2token = "$root/tokenize/srcMLtoken/srcml2token";
my $srcml = which('srcml') or die "srcml not on PATH\n";
my $ctags = which('ctags') // which('ctags-universal') or die "ctags not on PATH\n";
die "srcml2token is not built at $srcml2token\n" unless -x $srcml2token || $dryRun;

my ($ext) = $input =~ /\.([^.\/]+)$/;
die "--input needs an extension\n" unless defined $ext;
my %lang = ('c' => 'C', 'h' => 'C', 'cpp' => 'C++', 'hpp' => 'C++', 'java' => 'Java');
my $lang = $lang{lc $ext} or die "no srcml language for .$ext\n";

my ($work, $memo, $copy);
if ($dryRun) {
    # Nothing is created, not even a temporary directory: /tmp is tmpfs here and
    # a dry run should cost no memory either.
    ($work, $memo, $copy) = ('<tmpdir>', '<tmpdir>/memo', "<tmpdir>/input.$ext");
} else {
    mkdir $out unless -d $out;
    die "cannot create $out\n" unless -d $out;
    # A private work dir, so the ladder never writes into the repository and the
    # memo rungs start from a known-cold cache.
    $work = tempdir("$out/chain-XXXXX", CLEANUP => 1);
    $memo = "$work/memo";
    mkdir $memo;
    $copy = "$work/input.$ext";
    copy_file($input, $copy);
}

my $tokCmd = "$root/tokenize/tokenize.pl --srcml2token=$srcml2token --srcml=$srcml --ctags=$ctags";

my @ladder = (
    ['sh',       ['/bin/sh', '-c', 'true']],
    ['perl',     [$^X, '-e0']],
    # Exactly the command tokenizeSrcMl.pl builds, so a difference against the
    # rung above it is a layer and not a change of flags.
    ['srcml',    ['/bin/sh', '-c', qq{$srcml -l '$lang' --position '$copy' >/dev/null}]],
    ['native',   ['/bin/sh', '-c', qq{$srcml -l '$lang' --position '$copy' | $srcml2token >/dev/null}]],
    ['srcmlpl',  [$^X, "$root/tokenize/tokenizeSrcMl.pl",
                  "--srcml2token=$srcml2token", "--srcml=$srcml", "--ctags=$ctags",
                  "--language=$lang", $copy]],
    ['dispatch', [$^X, "$root/tokenize/tokenize.pl",
                  "--srcml2token=$srcml2token", "--srcml=$srcml", "--ctags=$ctags",
                  "--language=$lang", $copy]],
    ['bysha',    [$^X, "$root/tokenizeByBlobId/tokenBySha.pl"]],
    ['memohit',  [$^X, "$root/tokenizeByBlobId/tokenBySha.pl"]],
);

if ($dryRun) {
    printf "%-9s %s\n", $_->[0], join(' ', @{ $_->[1] }) for @ladder;
    print "\nreps=$reps input=$input lang=$lang out=$out\n";
    exit 0;
}

my %result;
for my $rung (@ladder) {
    my ($name, $argv) = @$rung;

    # bysha reads the blob on stdin and memoizes by content sha1: wipe the memo
    # for the cold rung, and leave the one bysha just wrote for the warm one.
    my %env = (BFG_MEMO_DIR => $memo, BFG_TOKENIZE_CMD => $tokCmd,
               BFG_FILENAME => "input.$ext", BFG_BLOB => '0' x 40);
    my $stdin = ($name eq 'bysha' || $name eq 'memohit') ? $copy : undef;
    wipe_memo($memo) if $name eq 'bysha';

    run_once($argv, \%env, $stdin);   # warm the page cache, discard

    my (@wall, $cpu);
    my @c0 = times;
    for (1 .. $reps) {
        wipe_memo($memo) if $name eq 'bysha';
        push @wall, run_once($argv, \%env, $stdin);
    }
    my @c1 = times;
    $cpu = ($c1[2] - $c0[2]) + ($c1[3] - $c0[3]);

    @wall = sort { $a <=> $b } @wall;
    $result{$name} = {
        min    => $wall[0],
        median => $wall[ int(@wall / 2) ],
        max    => $wall[-1],
        cpu    => $cpu / $reps,
    };
}

my $report = "$out/chain-cost.tsv";
open(my $fh, '>', $report) or die "cannot write $report: $!\n";
print {$fh} "rung\tmedian_ms\tmin_ms\tmax_ms\tcpu_ms\n";
for my $rung (@ladder) {
    my $r = $result{ $rung->[0] };
    printf {$fh} "%s\t%.2f\t%.2f\t%.2f\t%.2f\n", $rung->[0],
        1000 * $r->{median}, 1000 * $r->{min}, 1000 * $r->{max}, 1000 * $r->{cpu};
}

# The three differences the lever list turns on, stated so nobody has to
# subtract two rows by hand and get the sign wrong.
my $d = sub { 1000 * ($result{ $_[0] }{median} - $result{ $_[1] }{median}) };
print {$fh} "\n";
printf {$fh} "perl_and_shell_overhead_ms\t%.2f\n", $d->('bysha', 'native');
printf {$fh} "ctags_and_wrapper_ms\t%.2f\n",       $d->('srcmlpl', 'native');
printf {$fh} "dispatcher_ms\t%.2f\n",              $d->('dispatch', 'srcmlpl');
printf {$fh} "bysha_wrapper_ms\t%.2f\n",           $d->('bysha', 'dispatch');
printf {$fh} "total_per_blob_ms\t%.2f\n",       1000 * $result{bysha}{median};
printf {$fh} "memo_hit_ms\t%.2f\n",             1000 * $result{memohit}{median};
close $fh;

print "wrote $report\n";
open($fh, '<', $report) or die;
print while <$fh>;
close $fh;

sub run_once {
    my ($argv, $env, $stdin) = @_;
    my $t0 = time;
    my $pid = fork();
    die "fork: $!\n" unless defined $pid;
    if (!$pid) {
        local %ENV = (%ENV, %$env);
        open(STDOUT, '>', '/dev/null') or exit 127;
        open(STDERR, '>', '/dev/null') or exit 127;
        if (defined $stdin) { open(STDIN, '<', $stdin) or exit 127 }
        else                { open(STDIN, '<', '/dev/null') or exit 127 }
        exec { $argv->[0] } @$argv;
        exit 127;
    }
    waitpid($pid, 0);
    return time - $t0;
}

sub wipe_memo {
    my ($dir) = @_;
    # Two levels of sha1 fan-out, nothing else: no File::Path::remove_tree on
    # a path the caller gave.
    for my $a (glob "$dir/*") {
        next unless -d $a;
        for my $b (glob "$a/*") {
            unlink glob "$b/*" if -d $b;
            rmdir $b;
        }
        rmdir $a;
    }
}

sub copy_file {
    my ($from, $to) = @_;
    open(my $i, '<', $from) or die "cannot read $from: $!\n";
    open(my $o, '>', $to)   or die "cannot write $to: $!\n";
    binmode $i; binmode $o;
    local $/;
    print {$o} <$i>;
    close $i; close $o;
}

sub which {
    my ($name) = @_;
    for my $d (File::Spec->path) {
        my $p = File::Spec->catfile($d, $name);
        return $p if -x $p && !-d $p;
    }
    return undef;
}

sub print_usage {
    print <<"USAGE";
usage: chain-cost.pl --input FILE --out DIR [--reps N] [--dry-run]

  --input   a source file whose extension srcml recognises (.c .h .cpp .java)
  --out     absolute path for chain-cost.tsv; never a pipeline work directory
  --reps    repetitions per rung, default 20
  --dry-run print the ladder and exit, running nothing
USAGE
}
