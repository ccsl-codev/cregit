#!/usr/bin/env perl

# For every extension the shared table names: both gates accept it and agree on
# the language, its parser exists and is executable, the mask selects it
# case-insensitively and nothing else, and srcml really parses it.
#
# The last one matters because srcml 1.1.0 exits 0 and emits no <unit> for an
# extension it does not know, so "exit 0" alone proves nothing.

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

# A language with no parser, or a parser no extension reaches, is a hole.
my %langs_from_ext = map { $_ => 1 } values %EXT_LANG;
is_deeply([sort keys %langs_from_ext],
          [sort keys %CregitLanguages::LANG_PARSER_REL],
          "every language an extension names has a parser, and vice versa");

for my $lang (sort keys %parsers) {
    my $p = $parsers{$lang};
    ok(-e $p, "parser for [$lang] exists on disk: $p");
    ok(-x $p, "parser for [$lang] is executable: $p");
}

# Named individually so a reintroduction says which one.
for my $dead (qw(go md yaml)) {
    ok(!exists $EXT_LANG{$dead},
       "[$dead] is not in the table — cregit has no parser for it "
       . "(tokenize/goTokenizer has sources but no built binary and no entry in "
       . "the parser table)");
}

# Both callers lc() before the lookup, so an uppercase key is unreachable.
for my $ext (sort keys %EXT_LANG) {
    is($ext, lc($ext), "table key [$ext] is lowercase, so the lc() lookup finds it");
    unlike($ext, qr/^\./, "table key [$ext] carries no leading dot");
}

# ---------------------------------------------------------------------------
# The callers derive their tables from this one
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

# Both directions: nothing the mask names is missing from the table, and nothing
# a masked language owns is missing from the mask.
my @masked_ext = CregitLanguages::masked_extensions_sorted();
ok(scalar(@masked_ext) > 0, "the mask names at least one extension");

for my $ext (@masked_ext) {
    ok(exists $EXT_LANG{$ext}, "masked extension [$ext] is in the extension table");
    for my $name ("file.$ext", "deep/path/to/file.$ext", "file." . uc($ext)) {
        ok($name =~ /$mask/, "the mask selects [$name]");
    }
}

# M4 is routed but must not be masked: m4.py mis-lexes real autotools quoting.
ok(!$CregitLanguages::MASKED_LANGUAGES{'M4'},
   "M4 is routed but not masked — m4.py mis-lexes real autotools quoting");
for my $ext (qw(am ac)) {
    is($EXT_LANG{$ext}, 'M4', "[$ext] is still routed to M4 by both gates");
    ok("file.$ext" !~ /$mask/, "the mask does not select [file.$ext]");
}

# Extensions cregit cannot tokenize, including the five the srcML probe rejects.
for my $no (qw(go md yaml py txt cs cppm ixx inl ipp cxxm rb ts json xml)) {
    ok("file.$no" !~ /$mask/, "the mask does not select [file.$no]");
}
ok("file.hxx.bak" !~ /$mask/, "the mask is anchored: file.hxx.bak is not selected");
ok("Makefile" !~ /$mask/, "the mask does not select an extensionless file");

# ---------------------------------------------------------------------------
# tokenBySha.pl — the first gate
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
# tokenize.pl — the second gate: every extension must produce tokens
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

# ---------------------------------------------------------------------------
# .C and .H are C++ by the GNU convention, and their lowercase forms are C
# ---------------------------------------------------------------------------

is(CregitLanguages::language_for_ext('C'), 'C++', ".C routes to C++");
is(CregitLanguages::language_for_ext('H'), 'C++', ".H routes to C++");
is(CregitLanguages::language_for_ext('c'), 'C',   ".c routes to C");
is(CregitLanguages::language_for_ext('h'), 'C',   ".h routes to C");

# The mask selects both cases, so the routing above decides what parses them.
ok("src/Matrix.C" =~ /$mask/, "the mask selects .C");
ok("src/Matrix.H" =~ /$mask/, "the mask selects .H");

# Case-insensitive for every other extension.
is(CregitLanguages::language_for_ext('CPP'), 'C++', ".CPP still routes to C++");
is(CregitLanguages::language_for_ext('Java'), 'Java', ".Java still routes to Java");
is(CregitLanguages::language_for_ext('RS'), 'Rust', ".RS still routes to Rust");

is(CregitLanguages::language_for_ext('zzz'), undef, "an unknown extension routes nowhere");
is(CregitLanguages::language_for_ext(''), undef, "an empty extension routes nowhere");

done_testing();
