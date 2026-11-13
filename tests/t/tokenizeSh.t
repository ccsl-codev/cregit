#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

my $dispatcher = "$root/tokenize/tokenize.pl";
my $shDir      = "$root/tokenize/shTokenizer";
my $fixture    = "$shDir/tests/fixtures/01_test_body.sh";
my $expected   = "$shDir/tests/expected/01_test_body.sh.nopos.token";

plan skip_all => "python3 with tree-sitter-bash not available (run inside devenv shell)"
    unless system("python3 -c 'import tree_sitter, tree_sitter_bash' >/dev/null 2>&1") == 0;

plan tests => 7;

my $workdir = tempdir(CLEANUP => 1);

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

sub run_dispatcher {
    my (@args) = @_;
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    my $status = system("perl '$dispatcher' " . join(' ', @args) . " > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

{
    my ($status, $out) = run_dispatcher("--language=Sh", "'$fixture'");
    is($status, 0, "dispatching a .sh file as Sh succeeds");
    is($out, slurp($expected), "dispatcher output matches the shTokenizer expected output");
}

{
    my ($status, $out) = run_dispatcher("'$fixture'");
    is($status, 0, "autodetecting .sh succeeds");
    is($out, slurp($expected), "autodetected output matches the explicit --language=Sh run");
}

{
    my ($status, $out) = run_dispatcher("--language=Sh", "--position", "'$fixture'");
    is($status, 0, "--position is forwarded to the shell tokenizer");
    like($out, qr/\A-:-\|begin_unit\|/, "markers are prefixed with -:-|");
    like($out, qr/^2:1\|command\|test_expect_success$/m, "tokens are prefixed with LINE:COL|");
}
