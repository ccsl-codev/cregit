#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 24;
use FindBin;
use File::Temp qw(tempdir);
use Digest::SHA qw(sha1_hex);

my $script  = "$FindBin::Bin/../tokenBySha.pl";
my $workdir = tempdir(CLEANUP => 1);

my $stub = "$workdir/stub-tokenizer.sh";
{
    open(my $fh, '>', $stub) or die $!;
    print $fh "#!/bin/sh\necho STUB-TOKENIZER\necho \"\$1\"\ncat \"\$2\"\n";
    close $fh;
    chmod 0755, $stub or die $!;
}

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

sub run_tokenbysha {
    my ($content, %env) = @_;
    my $in  = "$workdir/stdin";
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    open(my $fh, '>', $in) or die $!;
    print $fh $content;
    close $fh;

    local %ENV = %ENV;
    while (my ($k, $v) = each %env) {
        if (defined $v) { $ENV{$k} = $v } else { delete $ENV{$k} }
    }

    my $status = system("perl '$script' < '$in' > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

my $memoDir = tempdir(CLEANUP => 1);
my $content = "int a;\nint b;\n";
my $sha1 = sha1_hex($content);
my $memoFile = "$memoDir/" . substr($sha1, 0, 2) . "/" . substr($sha1, 2, 2) . "/$sha1";

{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "0" x 40,
        BFG_FILENAME     => "foo.c",
    );
    is($status, 0, "first run succeeds");
    like($out, qr/^STUB-TOKENIZER\n--language=C\n/,
         "stub is invoked with --language=C for a .c file");
    is($out, "STUB-TOKENIZER\n--language=C\n$content",
       "blob content reaches the tokenizer");
    ok(-f $memoFile, "output is memoized under xx/yy/<sha1> of the blob content");
    is(slurp($memoFile), $out, "memoized file matches stdout");
}

{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => "/bin/false",
        BFG_BLOB         => "0" x 40,
        BFG_FILENAME     => "foo.c",
    );
    is($status, 0, "cache hit succeeds even with a broken tokenize command");
    is($out, slurp($memoFile), "cache hit replays the memoized output");
}

{
    my ($status, $out, $err) = run_tokenbysha("class X {};\n",
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "1" x 40,
        BFG_FILENAME     => "foo.cpp",
    );
    is($status, 0, ".cpp run succeeds");
    like($out, qr/^STUB-TOKENIZER\n--language=C\+\+\n/, ".cpp maps to --language=C++");
}

{
    my $failedContent = "int failure;\n";
    my $failedSha1 = sha1_hex($failedContent);
    my $failedMemoFile = "$memoDir/" . substr($failedSha1, 0, 2) . "/" .
                         substr($failedSha1, 2, 2) . "/$failedSha1";

    my ($status, $out, $err) = run_tokenbysha($failedContent,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => "/bin/false",
        BFG_BLOB         => "5" x 40,
        BFG_FILENAME     => "failure.c",
    );
    isnt($status, 0, "a tokenizer failure on a cache miss exits non-zero");
    like($err, qr/tokenize command failed/, "the tokenizer failure is reported");
    ok(!-e $failedMemoFile, "failed tokenizer output is not memoized");

    ($status, $out, $err) = run_tokenbysha($failedContent,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "5" x 40,
        BFG_FILENAME     => "failure.c",
    );
    is($status, 0, "the same blob can be tokenized after the command is fixed");
    is($out, "STUB-TOKENIZER\n--language=C\n$failedContent",
       "the retry executes the fixed tokenizer instead of replaying a poisoned cache entry");
}

{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "2" x 40,
        BFG_FILENAME     => "foo.zzz",
    );
    isnt($status, 0, "unknown extension exits non-zero");
    like($err, qr/unknown file extension/, "unknown extension reported on stderr");
}

{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => undef,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "3" x 40,
        BFG_FILENAME     => "foo.c",
    );
    isnt($status, 0, "missing BFG_MEMO_DIR exits non-zero");
}

{
    my ($status, $out, $err) = run_tokenbysha($content,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "4" x 40,
        BFG_FILENAME     => "",
    );
    isnt($status, 0, "empty BFG_FILENAME exits non-zero");
}

# -- the parser-crash status must survive this wrapper -----------------------
#
# blobExec distinguishes a parser crash from an ordinary tokenizer error by the
# exact exit status, so this wrapper must propagate it rather than flattening every
# failure onto die's 255. That flattening is what would put a srcML crash back into
# the untraceable bucket it came from.
my $PARSER_CRASH_EXIT = 33;

{
    my $crashy = "$workdir/crashy-tokenizer.sh";
    open(my $fh, '>', $crashy) or die $!;
    # Emits a truncated prefix before failing, like a srcML that died part-way.
    print $fh "#!/bin/sh\necho 'partial'\nexit $PARSER_CRASH_EXIT\n";
    close $fh;
    chmod 0755, $crashy or die $!;

    my $crashContent = "int crashed;\n";
    my $crashSha1 = sha1_hex($crashContent);
    my $crashMemo = "$memoDir/" . substr($crashSha1, 0, 2) . "/" .
                    substr($crashSha1, 2, 2) . "/$crashSha1";

    my ($status, $out, $err) = run_tokenbysha($crashContent,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $crashy,
        BFG_BLOB         => "6" x 40,
        BFG_FILENAME     => "crashed.c",
    );
    isnt($status, 0, "a parser crash in the tokenizer exits non-zero");
    is($status >> 8, $PARSER_CRASH_EXIT,
       "the exact parser-crash status is propagated, not flattened to die's 255");
    ok(!-e $crashMemo,
       "a crashed tokenization is NOT memoized: no 0-byte entry to replay forever");

    # And the blob is still retryable once the parser is fixed, exactly as for an
    # ordinary failure -- a crash must not poison the memo.
    ($status, $out, $err) = run_tokenbysha($crashContent,
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $stub,
        BFG_BLOB         => "6" x 40,
        BFG_FILENAME     => "crashed.c",
    );
    is($status, 0, "the same blob tokenizes once the parser works");
    is($out, "STUB-TOKENIZER\n--language=C\n$crashContent",
       "and the retry produces real tokens rather than replaying an empty cache entry");
}

# An ordinary (non-crash) tokenizer status is propagated too, so blobExec can still
# tell the two apart downstream.
{
    my $seven = "$workdir/exit7-tokenizer.sh";
    open(my $fh, '>', $seven) or die $!;
    print $fh "#!/bin/sh\nexit 7\n";
    close $fh;
    chmod 0755, $seven or die $!;

    my ($status, $out, $err) = run_tokenbysha("int seven;\n",
        BFG_MEMO_DIR     => $memoDir,
        BFG_TOKENIZE_CMD => $seven,
        BFG_BLOB         => "7" x 40,
        BFG_FILENAME     => "seven.c",
    );
    is($status >> 8, 7,
       "an ordinary tokenizer status is propagated unchanged, and is not 33");
}
