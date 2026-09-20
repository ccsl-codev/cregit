#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $script      = "$FindBin::Bin/../tokenizeSrcMl.pl";
my $srcml2token = "$FindBin::Bin/../srcMLtoken/srcml2token";
my $fixtures    = "$FindBin::Bin/../srcMLtoken/tests";
my $expected    = "$FindBin::Bin/expected";

plan skip_all => "srcml not on PATH"
    unless system("srcml --version >/dev/null 2>&1") == 0;
plan skip_all => "ctags not on PATH"
    unless system("ctags --version >/dev/null 2>&1") == 0;
plan skip_all => "srcml2token not built (cd tokenize/srcMLtoken && make)"
    unless -x $srcml2token;

plan tests => 21;

# Exit status tokenizeSrcMl.pl uses for "srcML died / this tokenization is
# unusable". Must match $PARSER_CRASH_EXIT there and BlobExec.ParserCrashExitCode.
my $PARSER_CRASH_EXIT = 33;

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

# -- the silent-empty defect -------------------------------------------------
#
# srcML 1.1.0 dies on a signal when --position is given on certain C/C++ inputs.
# This fixture is one of the 36 confirmed corpus files: the 1,263-byte
# ext/liblzma/check/crc32_small.c from sumatrapdfreader__sumatrapdf, which is the
# file that rules out any size explanation. Before the fix this exact input made
# the tokenizer report success with zero bytes of output, and that empty result was
# written and memoized as the file's tokenization.
#
# --position is not optional: the token format carries positions, so the flag
# cannot be dropped to dodge the crash. Detection is the only available fix.
{
    my $crasher = "$FindBin::Bin/fixtures/srcml-position-crash.c";

    # Guard the fixture itself: if a future srcML stops crashing on it, these
    # assertions would silently stop testing anything, so prove the crash is real
    # before asserting on how it is handled.
    my $rawRc = system("srcml -l C --position '$crasher' >/dev/null 2>&1");

    # A signal death reaches us in one of two encodings and both must be accepted:
    # as a raw wait status ($rc & 127) when the child is waited for directly, or as
    # a shell's 128+signal exit code when a shell sits in between -- which it does
    # here, because this system() call has redirections and so goes through sh.
    # Conflating the two is what made this defect hard to see in the first place.
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

# The emptiness invariant the fix relies on, asserted rather than assumed: a
# successful srcML tokenization is never empty. Even a zero-byte source file yields
# the begin_unit/end_unit wrapper. That is what makes "empty stream" a safe failure
# signal here, where for a general-purpose filter it would be a false positive.
{
    my $empty = "$workdir/empty.c";
    open(my $fh, '>', $empty) or die $!;
    close $fh;

    my ($status, $out, $err) = run_tokenizer("--position", "'$empty'");
    is($status, 0, "a genuinely empty C file still tokenizes successfully");
    isnt($out, "", "and its tokenization is non-empty: the begin_unit/end_unit wrapper");
    like($out, qr/begin_unit/, "the wrapper is what makes an empty stream a reliable failure signal");
}

# Whitespace-only is the nearest thing to a legitimately empty file, and it must
# also still succeed: the fix must not turn "nearly empty" into a failure.
{
    my $blank = "$workdir/blank.c";
    open(my $fh, '>', $blank) or die $!;
    print $fh "   \n\n\t\n";
    close $fh;

    my ($status, $out, $err) = run_tokenizer("--position", "'$blank'");
    is($status, 0, "a whitespace-only C file still tokenizes successfully");
    isnt($out, "", "and is not mistaken for a failed parse");
}

# A path containing a single quote used to be interpolated straight into the shell
# command string. Now it is quoted, so it must tokenize rather than fail.
{
    my $odd = "$workdir/it's odd.c";
    open(my $fh, '>', $odd) or die $!;
    print $fh "int main() { return 0; }\n";
    close $fh;

    my ($status, $out, $err) = run_tokenizer("--position", "\"$odd\"");
    is($status, 0, "a path containing a quote is passed to the shell safely");
    like($out, qr/begin_unit/, "and really was tokenized");
}
