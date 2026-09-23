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

plan tests => 21;

my $workdir = tempdir(CLEANUP => 1);

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

my ($PARSER_CRASH_EXIT) = slurp($script) =~ /\$PARSER_CRASH_EXIT\s*=\s*(\d+)/
    or die "no \$PARSER_CRASH_EXIT in $script";

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

{
    my $crasher = "$FindBin::Bin/fixtures/srcml-position-crash.c";

    my $rawRc = system("srcml -l C --position '$crasher' >/dev/null 2>&1");

    my $rawSignal = $rawRc & 127;
    if (not $rawSignal and ($rawRc >> 8) > 128) {
        $rawSignal = ($rawRc >> 8) - 128;
    }

  SKIP: {
        skip "srcml no longer dies on this fixture (rc=$rawRc): the upstream bug "
            . "appears fixed, so the crash-handling assertions cannot be exercised", 6
            unless $rawSignal;

        is($rawSignal == 11 || $rawSignal == 6, 1,
           "fixture kills srcml with SIGSEGV(11) or SIGABRT(6), got signal $rawSignal");

        my ($status, $out, $err) = run_tokenizer("--position", "'$crasher'");

        isnt($status, 0,
             "a srcML signal death is NOT reported as success (was exit 0 before the fix)");
        is($status >> 8, $PARSER_CRASH_EXIT,
           "it exits the specific parser-crash status $PARSER_CRASH_EXIT, so the caller can count it");
        is($out, "",
           "no tokenization is emitted on stdout");
        like($err, qr/FAILED/,
             "the failure is reported on stderr instead of passing silently");
        like($err, qr/killed by signal $rawSignal/,
             "stderr names the signal, so srcml's death is attributed to srcml "
             . "and not to srcml2token, which exits 0 on the truncated XML");
    }
}

{
    my $empty = "$workdir/empty.c";
    open(my $fh, '>', $empty) or die $!;
    close $fh;

    my ($status, $out, $err) = run_tokenizer("--position", "'$empty'");
    is($status, 0, "a genuinely empty C file still tokenizes successfully");
    isnt($out, "", "and its tokenization is non-empty: the begin_unit/end_unit wrapper");
    like($out, qr/begin_unit/, "the wrapper is what makes an empty stream a reliable failure signal");
}

{
    my $blank = "$workdir/blank.c";
    open(my $fh, '>', $blank) or die $!;
    print $fh "   \n\n\t\n";
    close $fh;

    my ($status, $out, $err) = run_tokenizer("--position", "'$blank'");
    is($status, 0, "a whitespace-only C file still tokenizes successfully");
    isnt($out, "", "and is not mistaken for a failed parse");
}

{
    my $odd = "$workdir/it's odd.c";
    open(my $fh, '>', $odd) or die $!;
    print $fh "int main() { return 0; }\n";
    close $fh;

    my ($status, $out, $err) = run_tokenizer("--position", "\"$odd\"");
    is($status, 0, "a path containing a quote is passed to the shell safely");
    like($out, qr/begin_unit/, "and really was tokenized");
}
