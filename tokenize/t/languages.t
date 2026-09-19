#!/usr/bin/env perl

# The test that would have caught the live defect.
#
# tokenizeByBlobId/tokenBySha.pl used to map `go`, `md` and `yaml`, and
# tokenize/tokenize.pl had no parser for any of the three. The two scripts are
# consecutive gates on the same blob, so such a file passed the first and died at
# the second with "Unknown parser for extension" — after step 1 had already run.
# Nothing tested that the two tables described the same capability.
#
# So this file asserts, for EVERY extension the shared table names:
#
#   1. tokenBySha.pl (the per-blob gate bfg calls) accepts it and asks for the
#      same language tokenize.pl would;
#   2. tokenize.pl routes it to a parser that exists on disk and is executable;
#   3. the universal file mask selects it, case-insensitively, and selects
#      nothing that is not in the table;
#   4. for the srcML-routed extensions, the pinned srcml ACTUALLY PARSES a file
#      with that extension.
#
# (4) is not paranoia. srcml 1.1.0 keys its parser off the file extension and
# ignores `-l C++` when it does not recognise one: for .ixx, .inl, .cppm, .cxxm
# and .ipp it emits an XML declaration with no <unit> and exits 0. The chain then
# writes an EMPTY token file and reports success, so an unverified extension in
# the mask is silent data loss, not a crash. Probing beats reading a reference
# page that says "srcML supports C++".

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

use lib "$FindBin::Bin/..";
use CregitLanguages;

my $tokenizeDir = "$FindBin::Bin/..";
my $dispatcher  = "$tokenizeDir/tokenize.pl";
my $srcMlDirect = "$tokenizeDir/tokenizeSrcMl.pl";
my $fileMaskPl  = "$tokenizeDir/fileMask.pl";
my $tokenBySha  = "$FindBin::Bin/../../tokenizeByBlobId/tokenBySha.pl";

my $workdir = tempdir(CLEANUP => 1);
my %EXT_LANG = %CregitLanguages::EXT_LANG;
my %parsers  = CregitLanguages::parsers($tokenizeDir);
my $mask     = CregitLanguages::file_mask();

sub slurp {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    local $/;
    my $c = <$fh>;
    return defined $c ? $c : '';
}

sub run_cmd {
    my (@cmd) = @_;
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    my $status = system(join(' ', @cmd) . " > '$out' 2> '$err'");
    return ($status, slurp($out), slurp($err));
}

# ---------------------------------------------------------------------------
# The table itself
# ---------------------------------------------------------------------------

ok(scalar(keys %EXT_LANG) > 0, "the extension table is not empty");

# Every language in the extension table has a parser, and every parser in the
# parser table is reachable from some extension. Either gap is a hole: a language
# with no parser is the go/md/yaml defect, and a parser no extension routes to is
# dead code pretending the capability exists.
my %langs_from_ext = map { $_ => 1 } values %EXT_LANG;
is_deeply([sort keys %langs_from_ext],
          [sort keys %CregitLanguages::LANG_PARSER_REL],
          "every language an extension names has a parser, and vice versa");

for my $lang (sort keys %parsers) {
    my $p = $parsers{$lang};
    ok(-e $p, "parser for [$lang] exists on disk: $p");
    ok(-x $p, "parser for [$lang] is executable: $p");
}

# The three entries that caused the defect. Named individually so a
# reintroduction says which one.
for my $dead (qw(go md yaml)) {
    ok(!exists $EXT_LANG{$dead},
       "[$dead] is not in the table — cregit has no parser for it "
       . "(tokenize/goTokenizer has sources but no built binary and no entry in "
       . "the parser table)");
}

# Lowercase, dotless keys: tokenize.pl prepends the dot, tokenBySha.pl does not,
# and both lc() the extension before the lookup. An uppercase key is unreachable.
for my $ext (sort keys %EXT_LANG) {
    is($ext, lc($ext), "table key [$ext] is lowercase, so the lc() lookup finds it");
    unlike($ext, qr/^\./, "table key [$ext] carries no leading dot");
}

# ---------------------------------------------------------------------------
# The three tables that used to be separate literals now come from this one
# ---------------------------------------------------------------------------

for my $script ($dispatcher, $srcMlDirect, $tokenBySha) {
    my $src = slurp($script);
    like($src, qr/CregitLanguages/,
         "$script reads its extension table from CregitLanguages");
    unlike($src, qr/["']\.?cpp["']\s*=>/,
           "$script holds no second literal extension table");
}

# ---------------------------------------------------------------------------
# The mask is derived from the table, and agrees with it in both directions
# ---------------------------------------------------------------------------

{
    my ($status, $out) = run_cmd("perl '$fileMaskPl'");
    is($status, 0, "fileMask.pl succeeds");
    chomp(my $printed = $out);
    is($printed, $mask, "fileMask.pl prints exactly CregitLanguages::file_mask()");
}

like($mask, qr/^\Q(?i)\E/, "the mask is case-insensitive: .C and .H are real files");

# Every extension the mask may name is in the table, so it has a parser, and the
# mask really does name it. Both directions matter: an extension the mask names
# but the table does not know kills the run part-way, and an extension of a
# masked language that the mask forgets is source silently left untokenized.
my @masked_ext = CregitLanguages::masked_extensions_sorted();
ok(scalar(@masked_ext) > 0, "the mask names at least one extension");

for my $ext (@masked_ext) {
    ok(exists $EXT_LANG{$ext}, "masked extension [$ext] is in the extension table");
    for my $name ("file.$ext", "deep/path/to/file.$ext", "file." . uc($ext)) {
        ok($name =~ /$mask/, "the mask selects [$name]");
    }
}

# M4 is in the table (tokenize.pl routes .am/.ac to m4Tokenizer/m4.py) but must
# not be in the mask: m4.py's lexer sets end_quote to a backtick instead of an
# apostrophe, so `x' swallows text up to the next backtick, and 2 of 38 real
# autotools files on this machine die outright. See %MASKED_LANGUAGES. Selecting
# .am/.ac would fail whole projects at step 2.
ok(!$CregitLanguages::MASKED_LANGUAGES{'M4'},
   "M4 is routed but not masked — m4.py mis-lexes real autotools quoting");
for my $ext (qw(am ac)) {
    is($EXT_LANG{$ext}, 'M4', "[$ext] is still routed to M4 by both gates");
    ok("file.$ext" !~ /$mask/, "the mask does not select [file.$ext]");
}

# Extensions cregit cannot tokenize, including the five candidates the srcML
# probe below rejects. A mask that selected these would produce empty token
# files (srcML) or kill the run (no parser at all).
for my $no (qw(go md yaml py txt cs cppm ixx inl ipp cxxm rb ts json xml)) {
    ok("file.$no" !~ /$mask/, "the mask does not select [file.$no]");
}
ok("file.hxx.bak" !~ /$mask/, "the mask is anchored: file.hxx.bak is not selected");
ok("Makefile" !~ /$mask/, "the mask does not select an extensionless file");

# ---------------------------------------------------------------------------
# tokenBySha.pl — the first gate. Same extension, same language, no die.
# ---------------------------------------------------------------------------

{
    my $stub = "$workdir/stub-tokenizer.sh";
    open(my $fh, '>', $stub) or die $!;
    print $fh "#!/bin/sh\necho \"\$1\"\n";
    close $fh;
    chmod 0755, $stub or die $!;

    my $memoDir = tempdir(CLEANUP => 1);
    my $n = 0;
    for my $ext (sort keys %EXT_LANG) {
        # Distinct content per extension, so each run is a cache miss.
        my $in = "$workdir/blob-in";
        open(my $ifh, '>', $in) or die $!;
        print $ifh "blob for $ext\n";
        close $ifh;

        local %ENV = %ENV;
        $ENV{BFG_MEMO_DIR}     = $memoDir;
        $ENV{BFG_TOKENIZE_CMD} = $stub;
        $ENV{BFG_BLOB}         = sprintf("%040d", $n++);
        $ENV{BFG_FILENAME}     = "sample.$ext";

        my ($status, $out, $err) = run_cmd("perl '$tokenBySha' < '$in'");
        is($status, 0, "tokenBySha.pl accepts .$ext (exit 0)");
        chomp(my $got = $out);
        is($got, "--language=$EXT_LANG{$ext}",
           "tokenBySha.pl asks for --language=$EXT_LANG{$ext} on a .$ext blob");
    }
}

# ---------------------------------------------------------------------------
# tokenize.pl — the second gate, and the real parsers. This is where go/md/yaml
# died. Every extension must produce tokens, not an error and not silence.
# ---------------------------------------------------------------------------

my $have_srcml   = system("srcml --version >/dev/null 2>&1") == 0;
my $have_s2t     = -x "$tokenizeDir/srcMLtoken/srcml2token";
my $have_rustbin = -x $parsers{'Rust'};

# Fixtures that are real source in each language, small enough to stay fast.
my %fixture = (
    'C'    => "#include <stdio.h>\nint main(void) { printf(\"hi\\n\"); return 0; }\n",
    'C++'  => "#include <vector>\nnamespace n { template <typename T> class B { T v; public: T get() const { return v; } }; }\n",
    'Java' => "package p;\npublic class Sample { public int twice(int n) { return n * 2; } }\n",
    'M4'   => "AC_INIT([sample], [1.0])\nAC_PROG_CC\nAC_OUTPUT\n",
    'Rust' => "pub fn twice(n: i32) -> i32 { n * 2 }\n",
);

for my $ext (sort keys %EXT_LANG) {
    my $lang = $EXT_LANG{$ext};
    my $srcml_routed = $parsers{$lang} eq "$tokenizeDir/$CregitLanguages::SRCML_PARSER_REL";

    if ($srcml_routed and not($have_srcml and $have_s2t)) {
        SKIP: { skip "srcml or srcml2token unavailable", 2 }
        next;
    }
    if ($lang eq 'Rust' and not $have_rustbin) {
        SKIP: { skip "rust_tokenizer not built", 2 }
        next;
    }

    my $file = "$workdir/probe.$ext";
    open(my $fh, '>', $file) or die $!;
    print $fh $fixture{$lang};
    close $fh;

    my ($status, $out, $err) = run_cmd("perl '$dispatcher'", "--language='$lang'", "'$file'");
    is($status, 0, "tokenize.pl tokenizes a .$ext file as $lang (exit 0)");
    # The point of the probe: srcml exits 0 and emits nothing when it does not
    # recognise the extension, so "exit 0" alone proves nothing.
    ok(length($out) > 0,
       "tokenizing .$ext produced tokens — the parser recognised the extension");
}

# Autodetection must reach the same answer as --language, for every extension:
# blobExec calls tokenize.pl with --language, but a human calling it without one
# must not get a different table.
if ($have_srcml and $have_s2t) {
    for my $ext (qw(c cpp hxx tcc java)) {
        my $lang = $EXT_LANG{$ext};
        my $file = "$workdir/auto.$ext";
        open(my $fh, '>', $file) or die $!;
        print $fh $fixture{$lang};
        close $fh;
        my (undef, $auto) = run_cmd("perl '$dispatcher'", "'$file'");
        my (undef, $expl) = run_cmd("perl '$dispatcher'", "--language='$lang'", "'$file'");
        is($auto, $expl, "autodetected .$ext matches the explicit --language=$lang run");
    }
}

done_testing();
