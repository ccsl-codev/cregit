#!/usr/bin/env perl

# Tests for tokenize/tokenizerIdentity.pl: stable when nothing changes, moves
# only the affected extensions, and is fatal (never silent) on a missing part.

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);
use File::Path qw(make_path);
use File::Copy;

use lib "$FindBin::Bin/../../tokenize";
use CregitLanguages;

my $realTokenizeDir = "$FindBin::Bin/../../tokenize";

sub fixture {
    my $dir = tempdir(CLEANUP => 1);
    my $tok = "$dir/tokenize";
    make_path($tok);
    for my $f (qw(tokenize.pl CregitLanguages.pm tokenizerIdentity.pl tokenizeSrcMl.pl CregitSrcMl.pm)) {
        copy("$realTokenizeDir/$f", "$tok/$f") or die "copy $f: $!";
    }
    chmod 0755, "$tok/tokenizerIdentity.pl", "$tok/tokenize.pl", "$tok/tokenizeSrcMl.pl";

    # The identity digests content, so "changing a tokenizer" is a new string.
    make_path("$tok/rustTokenizer/target/release");
    write_file("$tok/rustTokenizer/target/release/rust_tokenizer", "RUST TOKENIZER v1\n");
    make_path("$tok/m4Tokenizer");
    write_file("$tok/m4Tokenizer/m4.py", "M4 v1\n");
    write_srcml2token($dir, "v1");
    write_file("$dir/libsrcml",    "LIBSRCML v1\n");
    write_file("$dir/ctags",       "CTAGS v1\n");
    return $dir;
}

sub write_file {
    my ($path, $content) = @_;
    open(my $fh, '>', $path) or die "write $path: $!";
    print $fh $content;
    close $fh;
    chmod 0755, $path;
}

sub write_srcml2token {
    my ($dir, $version) = @_;
    write_file("$dir/srcml2token", "#!/bin/sh\n# SRCML2TOKEN $version\necho '$dir/libsrcml'\n");
}

sub identity {
    my ($dir, @extra) = @_;
    my @cmd = ("perl", "$dir/tokenize/tokenizerIdentity.pl",
               "--srcml2token=$dir/srcml2token",
               "--ctags=$dir/ctags", @extra);
    open(my $ph, '-|', @cmd) or die "run: $!";
    my $out = do { local $/; <$ph> };
    close $ph;
    my $status = $?;
    $out = '' unless defined $out;
    chomp $out;
    return ($out, $status);
}

sub as_map {
    my ($spec) = @_;
    return map { split(/=/, $_, 2) } split(/,/, $spec);
}

# ---------------------------------------------------------------------------
my $dir = fixture();
my ($base, $status) = identity($dir);
is($status, 0, "tokenizerIdentity.pl succeeds on a complete checkout");

my %baseMap = as_map($base);

is_deeply(
    [sort keys %baseMap],
    [CregitLanguages::masked_extensions_sorted()],
    "every masked extension gets an identity, and only those");

# The format blobExec's TokenizerIdentity.parse accepts.
for my $ext (sort keys %baseMap) {
    like($ext, qr/^[a-z0-9+]+$/, "extension [$ext] is lowercase and dotless");
    like($baseMap{$ext}, qr/^[0-9a-f]{64}$/, "identity for [$ext] is a sha256 hex digest");
}

my ($again) = identity($dir);
is($again, $base, "an unchanged checkout yields a byte-identical identity");

# ---------------------------------------------------------------------------
write_file("$dir/tokenize/rustTokenizer/target/release/rust_tokenizer", "RUST TOKENIZER v2 fixed\n");
my ($afterRust) = identity($dir);
my %rustMap = as_map($afterRust);

isnt($rustMap{rs}, $baseMap{rs}, "rebuilding the Rust tokenizer moves the .rs identity");
for my $ext (grep { $_ ne 'rs' } sort keys %baseMap) {
    is($rustMap{$ext}, $baseMap{$ext},
       "and leaves [$ext] alone, so its tokenizations are not invalidated");
}

# ---------------------------------------------------------------------------
for my $component (qw(srcml2token libsrcml ctags)) {
    my $d = fixture();
    my ($before) = identity($d);
    if ($component eq "srcml2token") { write_srcml2token($d, "v2") }
    else                             { write_file("$d/$component", "$component v2\n") }
    my ($after) = identity($d);
    my %b = as_map($before);
    my %a = as_map($after);
    isnt($a{c}, $b{c}, "changing $component moves the .c identity");
    is($a{rs}, $b{rs}, "changing $component leaves .rs alone");
}

# CregitSrcMl.pm is tokenizeSrcMl.pl's copy in the tokenizer worker.
for my $script (qw(tokenizeSrcMl.pl CregitSrcMl.pm)) {
    my $d = fixture();
    my ($before) = identity($d);
    write_file("$d/tokenize/$script", "# v2\n");
    my ($after) = identity($d);
    my %b = as_map($before);
    my %a = as_map($after);
    isnt($a{c}, $b{c}, "editing $script moves the .c identity");
    is($a{rs}, $b{rs}, "and leaves .rs alone");
}

# ---------------------------------------------------------------------------
# Deliberately every extension: either file can change tokens with no parser
# change (tokenize.pl picks parser and flags, CregitLanguages.pm the language).
for my $shared (qw(tokenize.pl CregitLanguages.pm)) {
    my $d = fixture();
    my ($before) = identity($d);
    my %b = as_map($before);
    open(my $fh, '>>', "$d/tokenize/$shared") or die $!;
    print $fh "\n# a change\n";
    close $fh;
    my ($after) = identity($d);
    my %a = as_map($after);
    for my $ext (sort keys %b) {
        isnt($a{$ext}, $b{$ext}, "editing $shared moves [$ext] too");
    }
}

# ---------------------------------------------------------------------------
{
    my $d = fixture();
    unlink("$d/tokenize/rustTokenizer/target/release/rust_tokenizer");
    my @cmd = ("perl", "$d/tokenize/tokenizerIdentity.pl",
               "--srcml2token=$d/srcml2token", "--ctags=$d/ctags");
    my $err = `@cmd 2>&1 1>/dev/null`;
    isnt($?, 0, "an unbuilt Rust tokenizer is fatal, not omitted");
    like($err, qr/rust_tokenizer/, "and the message names the file");
}

{
    my $d = fixture();
    my @cmd = ("perl", "$d/tokenize/tokenizerIdentity.pl", "--ctags=$d/ctags");
    my $err = `@cmd 2>&1 1>/dev/null`;
    isnt($?, 0, "omitting --srcml2token is fatal: .c tokens depend on it");
    like($err, qr/--srcml2token/, "and the message names the flag");
}

{
    my $d = fixture();
    write_file("$d/srcml2token", "#!/bin/sh\nexit 0\n");
    my ($out, $status) = identity($d);
    isnt($status, 0, "an srcml2token that does not name its libsrcml is fatal");
    is($out, "", "and prints no identity");
}

# ---------------------------------------------------------------------------
{
    my ($all) = identity($dir, "--all-extensions");
    my %allMap = as_map($all);
    is_deeply([sort keys %allMap], [CregitLanguages::extensions_sorted()],
              "--all-extensions covers every extension in the table");
    ok(exists $allMap{am}, "including .am, which is routed to m4 but not masked");
}

done_testing();
