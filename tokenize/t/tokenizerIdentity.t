#!/usr/bin/env perl

# Tests for tokenize/tokenizerIdentity.pl, the value blobExec compares to decide
# whether a cached tokenization was made by the tokenizer this run is using.
#
# What has to be true of it, and why:
#
#   1. It changes when a tokenizer changes. That is the whole point: the defect
#      it exists for is that `command` (the constant path
#      tokenizeByBlobId/tokenBySha.pl) and `mask` both stayed the same while the
#      Rust tokenizer's output format was corrected, so every cached .rs row
#      stayed a cache hit.
#   2. It does NOT change when nothing changes. An identity that moved on every
#      run would refuse every resume, which is indistinguishable from having no
#      cache at all.
#   3. It changes the identity of the RIGHT extensions and no others. Selectivity
#      is the difference between re-tokenizing 741,869 .rs entries and
#      re-tokenizing the whole corpus at 88% of total pipeline time.
#   4. A component it cannot read is fatal, never omitted. An identity computed
#      without a component compares EQUAL across a change in that component,
#      which is exactly the silence being removed.
#   5. blobExec can parse what it prints.

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);
use File::Path qw(make_path);
use File::Copy;

use lib "$FindBin::Bin/..";
use CregitLanguages;

my $realTokenizeDir = "$FindBin::Bin/..";

# A throwaway copy of tokenize/ with stand-in "binaries", so a test can change a
# tokenizer without touching the checkout or building anything.
sub fixture {
    my $dir = tempdir(CLEANUP => 1);
    my $tok = "$dir/tokenize";
    make_path($tok);
    for my $f (qw(tokenize.pl CregitLanguages.pm tokenizerIdentity.pl tokenizeSrcMl.pl)) {
        copy("$realTokenizeDir/$f", "$tok/$f") or die "copy $f: $!";
    }
    chmod 0755, "$tok/tokenizerIdentity.pl", "$tok/tokenize.pl", "$tok/tokenizeSrcMl.pl";

    # Stand-ins for the parsers and the external binaries. Content is what the
    # identity digests, so "changing a tokenizer" is writing a different string.
    make_path("$tok/rustTokenizer/target/release");
    write_file("$tok/rustTokenizer/target/release/rust_tokenizer", "RUST TOKENIZER v1\n");
    make_path("$tok/m4Tokenizer");
    write_file("$tok/m4Tokenizer/m4.py", "M4 v1\n");
    write_file("$dir/srcml2token", "SRCML2TOKEN v1\n");
    write_file("$dir/srcml",       "SRCML v1\n");
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

sub identity {
    my ($dir, @extra) = @_;
    my @cmd = ("perl", "$dir/tokenize/tokenizerIdentity.pl",
               "--srcml2token=$dir/srcml2token",
               "--srcml=$dir/srcml",
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

# 3 — coverage. Every masked extension and nothing else, because those are the
# extensions the pipeline's mask can select and therefore the ones blob_map can
# hold rows for.
is_deeply(
    [sort keys %baseMap],
    [CregitLanguages::masked_extensions_sorted()],
    "every masked extension gets an identity, and only those");

# 5 — the format blobExec's TokenizerIdentity.parse accepts.
for my $ext (sort keys %baseMap) {
    like($ext, qr/^[a-z0-9+]+$/, "extension [$ext] is lowercase and dotless");
    like($baseMap{$ext}, qr/^[0-9a-f]{64}$/, "identity for [$ext] is a sha256 hex digest");
}

# 2 — stability. Run twice, unchanged, and get the same string.
my ($again) = identity($dir);
is($again, $base, "an unchanged checkout yields a byte-identical identity");

# ---------------------------------------------------------------------------
# 1 and 3 — the Rust tokenizer changes, and ONLY .rs moves. This is the exact
# shape of the live defect: a corrected rustTokenizer binary, everything else
# untouched.
write_file("$dir/tokenize/rustTokenizer/target/release/rust_tokenizer", "RUST TOKENIZER v2 fixed\n");
my ($afterRust) = identity($dir);
my %rustMap = as_map($afterRust);

isnt($rustMap{rs}, $baseMap{rs}, "rebuilding the Rust tokenizer moves the .rs identity");
for my $ext (grep { $_ ne 'rs' } sort keys %baseMap) {
    is($rustMap{$ext}, $baseMap{$ext},
       "and leaves [$ext] alone, so its tokenizations are not invalidated");
}

# ---------------------------------------------------------------------------
# 3, the other direction — the srcML chain moves the srcML-routed extensions and
# not Rust. Each of the three binaries counts: the token stream is the product of
# all of them.
for my $component (qw(srcml2token srcml ctags)) {
    my $d = fixture();
    my ($before) = identity($d);
    write_file("$d/$component", "$component v2\n");
    my ($after) = identity($d);
    my %b = as_map($before);
    my %a = as_map($after);
    isnt($a{c}, $b{c}, "changing $component moves the .c identity");
    is($a{rs}, $b{rs}, "changing $component leaves .rs alone");
}

# tokenizeSrcMl.pl itself, for completeness.
{
    my $d = fixture();
    my ($before) = identity($d);
    write_file("$d/tokenize/tokenizeSrcMl.pl", "# v2\n");
    my ($after) = identity($d);
    my %b = as_map($before);
    my %a = as_map($after);
    isnt($a{c}, $b{c}, "editing tokenizeSrcMl.pl moves the .c identity");
    is($a{rs}, $b{rs}, "and leaves .rs alone");
}

# ---------------------------------------------------------------------------
# The shared files move EVERY extension, deliberately. tokenize.pl decides which
# parser runs and with which flags (--position, which the token format depends
# on) and CregitLanguages.pm decides which language an extension is, so either can
# change an extension's tokens with no parser change at all. Blunt in the safe
# direction: a refusal costs a conversation, a false "unchanged" costs a corpus.
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
# 4 — a missing component is fatal, and says which file and why.
{
    my $d = fixture();
    unlink("$d/tokenize/rustTokenizer/target/release/rust_tokenizer");
    my @cmd = ("perl", "$d/tokenize/tokenizerIdentity.pl",
               "--srcml2token=$d/srcml2token", "--srcml=$d/srcml", "--ctags=$d/ctags");
    my $err = `@cmd 2>&1 1>/dev/null`;
    isnt($?, 0, "an unbuilt Rust tokenizer is fatal, not omitted");
    like($err, qr/rust_tokenizer/, "and the message names the file");
}

{
    my $d = fixture();
    my @cmd = ("perl", "$d/tokenize/tokenizerIdentity.pl", "--srcml=$d/srcml", "--ctags=$d/ctags");
    my $err = `@cmd 2>&1 1>/dev/null`;
    isnt($?, 0, "omitting --srcml2token is fatal: .c tokens depend on it");
    like($err, qr/--srcml2token/, "and the message names the flag");
}

# ---------------------------------------------------------------------------
# --all-extensions covers the routed-but-unmasked ones (.am/.ac), so the flag can
# be used the day the m4 lexer is fixed and M4 joins the mask.
{
    my ($all) = identity($dir, "--all-extensions");
    my %allMap = as_map($all);
    is_deeply([sort keys %allMap], [CregitLanguages::extensions_sorted()],
              "--all-extensions covers every extension in the table");
    ok(exists $allMap{am}, "including .am, which is routed to m4 but not masked");
}

done_testing();
