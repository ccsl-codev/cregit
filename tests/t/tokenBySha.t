#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 18;
use FindBin;
use File::Temp qw(tempdir);
use Digest::SHA qw(sha1_hex);

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

my $script  = "$root/tokenizeByBlobId/tokenBySha.pl";
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
