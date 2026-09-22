#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

my $script = "$root/tokenize/text/simpleTokenizer.pl";
my $workdir = tempdir(CLEANUP => 1);

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

sub run_tokenizer {
    my ($content, $use_file_argument) = @_;
    my $input = "$workdir/input.txt";
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";

    open(my $fh, '>', $input) or die $!;
    print $fh $content;
    close $fh;

    my $redirect = $use_file_argument ? "'$input'" : "< '$input'";
    my $status = system("'$^X' '$script' $redirect > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

{
    my ($status, $out, $err) = run_tokenizer("alpha   beta\ngamma\n", 0);
    is($status, 0, 'tokenizing stdin succeeds');
    is($out, "alpha\nbeta\ngamma\n", 'runs of whitespace separate tokens');
    is($err, '', 'normal stdin tokenization has no stderr');
}

{
    my ($status, $out) = run_tokenizer("alpha (beta[gamma]) delta\n", 1);
    is($status, 0, 'tokenizing a file argument succeeds');
    is(
        $out,
        "alpha\n(\nbeta\n[\ngamma\n]\n)\ndelta\n",
        'parentheses and brackets become standalone tokens'
    );
}

{
    my ($status, $out) = run_tokenizer("  lone-token  \n", 0);
    is($status, 0, 'leading and trailing whitespace are accepted');
    is($out, "lone-token\n", 'whitespace does not emit empty tokens');
}

done_testing();
