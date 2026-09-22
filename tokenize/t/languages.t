#!/usr/bin/env perl

# The srcml probe below is the one claim the table cannot make about itself:
# srcml 1.1.0 keys its parser off the extension, so for one it does not know it
# ignores -l, emits no <unit> and still exits 0.

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

use lib "$FindBin::Bin/..";
use CregitLanguages;

my $tokenizeDir = "$FindBin::Bin/..";
my $dispatcher  = "$tokenizeDir/tokenize.pl";
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

sub write_file {
    my ($path, $content) = @_;
    open(my $fh, '>', $path) or die "unable to write [$path]: $!";
    print $fh $content;
    close $fh or die "unable to close [$path]: $!";
    return $path;
}

# Runs @cmd with no shell, so no caller quotes its own arguments. $stdin names a
# file to feed on standard input, or is undef for none.
sub run_cmd {
    my ($stdin, @cmd) = @_;
    my $out = "$workdir/stdout";
    my $err = "$workdir/stderr";
    my $pid = fork();
    defined $pid or die "fork: $!";
    if (!$pid) {
        open(STDIN,  '<', $stdin // '/dev/null') or die $!;
        open(STDOUT, '>', $out) or die $!;
        open(STDERR, '>', $err) or die $!;
        exec { $cmd[0] } @cmd;
        exit 127;
    }
    waitpid($pid, 0);
    return ($?, slurp($out), slurp($err));
}

subtest 'the table agrees with itself' => sub {
    ok(scalar(keys %EXT_LANG) > 0, "the extension table is not empty");

    my %langs_from_ext = map { $_ => 1 } values %EXT_LANG;
    is_deeply([sort keys %langs_from_ext],
              [sort keys %CregitLanguages::LANG_PARSER_REL],
              "every language an extension names has a parser, and vice versa");

    is_deeply([grep { $_ ne lc($_) || /^\./ } sort keys %EXT_LANG], [],
              "every key is lowercase and dotless, so the lc() lookup finds it");

    is_deeply([grep { !exists $EXT_LANG{$_} } CregitLanguages::masked_extensions_sorted()], [],
              "every masked extension is in the extension table");

    is_deeply([CregitLanguages::extensions_sorted()],
              [qw(ac am c c++ cc cp cpp cxx h h++ hh hpp hxx java rs tcc)],
              "the extension set is pinned, so narrowing the corpus is deliberate");
};

subtest 'language_for_ext applies the case override' => sub {
    is(CregitLanguages::language_for_ext('C'), 'C++', ".C routes to C++");
    is(CregitLanguages::language_for_ext('H'), 'C++', ".H routes to C++");
    is(CregitLanguages::language_for_ext('c'), 'C',   ".c routes to C");
    is(CregitLanguages::language_for_ext('h'), 'C',   ".h routes to C");
    is(CregitLanguages::language_for_ext('CPP'),  'C++',  ".CPP routes to C++");
    is(CregitLanguages::language_for_ext('Java'), 'Java', ".Java routes to Java");
    is(CregitLanguages::language_for_ext('RS'),   'Rust', ".RS routes to Rust");
    is(CregitLanguages::language_for_ext('zzz'), undef, "an unknown extension routes nowhere");
    is(CregitLanguages::language_for_ext(''),    undef, "an empty extension routes nowhere");
};

subtest 'file_mask builds a regex that escapes and anchors' => sub {
    my ($status, $out) = run_cmd(undef, 'perl', $fileMaskPl);
    is($status, 0, "fileMask.pl succeeds");
    chomp(my $printed = $out);
    is($printed, $mask, "fileMask.pl prints exactly CregitLanguages::file_mask()");

    like($mask, qr/^\Q(?i)\E/, "the mask is case-insensitive: .C and .H are real files");

    for my $name (qw(file.c deep/path/to/file.cpp file.CPP src/Matrix.C src/Matrix.H a.h++)) {
        ok($name =~ /$mask/, "the mask selects [$name]");
    }
    for my $name (qw(file.go file.py file.ixx file.hxx.bak Makefile file.am)) {
        ok($name !~ /$mask/, "the mask does not select [$name]");
    }
};

subtest 'M4 is routed but not masked' => sub {
    ok(!$CregitLanguages::MASKED_LANGUAGES{'M4'},
       "m4.py mis-lexes real autotools quoting, so M4 stays out of the mask");
    is($EXT_LANG{$_}, 'M4', "[$_] is still routed to M4") for qw(am ac);
};

subtest 'tokenBySha.pl reads the table in its own process' => sub {
    my $stub = write_file("$workdir/stub-tokenizer.sh", "#!/bin/sh\necho \"\$1\"\n");
    chmod 0755, $stub or die $!;
    my $memoDir = tempdir(CLEANUP => 1);

    my %ext_for_lang;
    $ext_for_lang{$EXT_LANG{$_}} //= $_ for sort keys %EXT_LANG;
    my @cases = map { ["sample.$ext_for_lang{$_}", $_] } sort keys %ext_for_lang;
    push @cases, ['sample.C', 'C++'];

    my $n = 0;
    for my $case (@cases) {
        my ($name, $lang) = @$case;
        # The memo keys on content, so each run needs its own blob to miss.
        my $in = write_file("$workdir/blob-in", "blob for $name\n");

        local %ENV = %ENV;
        $ENV{BFG_MEMO_DIR}     = $memoDir;
        $ENV{BFG_TOKENIZE_CMD} = $stub;
        $ENV{BFG_BLOB}         = sprintf("%040d", $n++);
        $ENV{BFG_FILENAME}     = $name;

        my ($status, $out) = run_cmd($in, 'perl', $tokenBySha);
        is($status, 0, "tokenBySha.pl accepts $name (exit 0)");
        chomp(my $got = $out);
        is($got, "--language=$lang", "tokenBySha.pl asks for --language=$lang on $name");
    }
};

my ($srcml_status) = run_cmd(undef, 'srcml', '--version');
my $have_srcml   = $srcml_status == 0;
my $have_s2t     = -x "$tokenizeDir/srcMLtoken/srcml2token";
my $have_rustbin = -x $parsers{'Rust'};
my $srcml_parser = "$tokenizeDir/$CregitLanguages::SRCML_PARSER_REL";

my %fixture = (
    'C'    => "#include <stdio.h>\nint main(void) { printf(\"hi\\n\"); return 0; }\n",
    'C++'  => "#include <vector>\nnamespace n { template <typename T> class B { T v; public: T get() const { return v; } }; }\n",
    'Java' => "package p;\npublic class Sample { public int twice(int n) { return n * 2; } }\n",
    'M4'   => "AC_INIT([sample], [1.0])\nAC_PROG_CC\nAC_OUTPUT\n",
    'Rust' => "pub fn twice(n: i32) -> i32 { n * 2 }\n",
);

subtest 'the parser really recognises every extension' => sub {
    for my $ext (sort keys %EXT_LANG) {
        my $lang = $EXT_LANG{$ext};
        SKIP: {
            skip "srcml or srcml2token unavailable", 2
                if $parsers{$lang} eq $srcml_parser && !($have_srcml && $have_s2t);
            skip "rust_tokenizer not built", 2
                if $lang eq 'Rust' && !$have_rustbin;

            my $file = write_file("$workdir/probe.$ext", $fixture{$lang});
            my ($status, $out) = run_cmd(undef, 'perl', $dispatcher, "--language=$lang", $file);
            is($status, 0, "tokenize.pl tokenizes a .$ext file as $lang (exit 0)");
            ok(length($out) > 0,
               "tokenizing .$ext produced tokens — the parser recognised the extension");
        }
    }
};

subtest 'autodetection matches an explicit --language' => sub {
    plan skip_all => "srcml or srcml2token unavailable" unless $have_srcml && $have_s2t;
    for my $ext (qw(c cpp hxx tcc java)) {
        my $lang = $EXT_LANG{$ext};
        my $file = write_file("$workdir/auto.$ext", $fixture{$lang});
        my (undef, $auto) = run_cmd(undef, 'perl', $dispatcher, $file);
        my (undef, $expl) = run_cmd(undef, 'perl', $dispatcher, "--language=$lang", $file);
        is($auto, $expl, "autodetected .$ext matches the explicit --language=$lang run");
    }
};

done_testing();
