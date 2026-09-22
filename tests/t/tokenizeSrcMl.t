#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

my $script      = "$root/tokenize/tokenizeSrcMl.pl";
my $srcml2token = "$root/tokenize/srcMLtoken/srcml2token";
my $fixtures    = "$root/tokenize/srcMLtoken/tests";
my $expected    = "$FindBin::Bin/expected";

plan skip_all => "srcml not on PATH"
    unless system("srcml --version >/dev/null 2>&1") == 0;
plan skip_all => "ctags not on PATH"
    unless system("ctags --version >/dev/null 2>&1") == 0;
plan skip_all => "srcml2token not built (cd tokenize/srcMLtoken && make)"
    unless -x $srcml2token;

plan tests => 8;

my $workdir = tempdir(CLEANUP => 1);

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

sub run_tokenizer {
    my (@args) = @_;
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    my $status = system("perl '$script' --ctags=ctags --srcml2token='$srcml2token' "
                        . join(' ', @args) . " > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

{
    my ($status, $out, $err) = run_tokenizer("--position", "'$fixtures/main.c'");
    is($status, 0, "tokenizing main.c with --position succeeds");
    is($out, slurp("$expected/main.c.token"),
       "main.c --position output matches the golden file");
}

{
    my ($status, $out, $err) = run_tokenizer("'$fixtures/main.c'");
    is($status, 0, "tokenizing main.c without --position succeeds");
    is($out, slurp("$expected/main.c.nopos.token"),
       "main.c output matches the golden file");
}

{
    my ($status, $out, $err) = run_tokenizer("--position", "'$fixtures/StringUtil.java'");
    is($status, 0, "tokenizing StringUtil.java succeeds");
    is($out, slurp("$expected/StringUtil.java.token"),
       "StringUtil.java output matches the golden file");
}

{
    my $bogus = "$workdir/mystery.xyz";
    open(my $fh, '>', $bogus) or die $!;
    print $fh "int main() {}\n";
    close $fh;

    my ($status, $out, $err) = run_tokenizer("'$bogus'");
    isnt($status, 0, "unknown extension exits non-zero");
    like($err, qr/Unknown extension/, "unknown extension reported on stderr");
}
