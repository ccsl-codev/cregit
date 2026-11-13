#!/usr/bin/env perl

# Paths that git quotes (a byte >= 0x80, '"', '\', a tab, a newline) and paths
# that start with '-' must be blamed like any other path. Before the fix,
# blameRepoFiles.pl read `git ls-files` without -z (a quoted path did not match
# the mask, so the file was silently not blamed) and passed the path to
# formatBlame.pl and git without '--' (a leading '-' was read as an option).

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $root   = "$FindBin::Bin/../..";   # tests/t -> repo root
my $script = "$root/blameRepo/blameRepoFiles.pl";
my $format = "$root/blameRepo/formatBlame.pl";
my $workdir = tempdir(CLEANUP => 1);

$ENV{GIT_CONFIG_NOSYSTEM} = 1;
$ENV{GIT_CONFIG_GLOBAL}   = '/dev/null';
$ENV{GIT_AUTHOR_DATE}     = '2020-01-01T00:00:00 +0000';
$ENV{GIT_COMMITTER_DATE}  = '2020-01-01T00:00:00 +0000';
$ENV{GIT_AUTHOR_NAME}     = 'Alice';
$ENV{GIT_AUTHOR_EMAIL}    = 'alice@example.com';
$ENV{GIT_COMMITTER_NAME}  = 'Alice';
$ENV{GIT_COMMITTER_EMAIL} = 'alice@example.com';

# List form, no shell: the paths below hold quotes, tabs and newlines.
sub git {
    my ($repo, @args) = @_;
    system('git', '-C', $repo, @args) == 0 or die "git failed: @args";
}

sub git_out {
    my ($repo, @args) = @_;
    open(my $fh, '-|', 'git', '-C', $repo, @args) or die "git failed: @args";
    local $/;
    my $out = <$fh>;
    close $fh or die "git failed: @args";
    return $out;
}

sub write_file {
    my ($path, $content) = @_;
    open(my $fh, '>', $path) or die "$path: $!";
    print $fh $content;
    close $fh;
}

sub slurp {
    my ($path) = @_;
    open(my $fh, '<', $path) or return undef;
    local $/;
    my $c = <$fh>;
    close $fh;
    return $c;
}

# Bytes, not characters: git and the scripts see raw bytes.
my $eAcute = "\xc3\xa9";
my $uUml   = "\xc3\xbc";
my @hard = (
    "caf${eAcute}.c",                    # non-ASCII byte
    "dir ${uUml}/x.h",                   # non-ASCII byte in a folder name
    "q\"uote.c",                         # double quote
    "back\\slash.c",                     # backslash
    "tab\there.c",                       # tab
    "new\nline.c",                       # newline
    "it's.c",                            # single quote (no quoting by git)
    "-dash.c",                           # leading '-'
    "---- Books & Notes ----/notes.c",   # leading '-' folder
    "a/-inner.c",                        # '-' in a later segment
);
my @plain = ("normal.c");

my $repo = "$workdir/repo";
mkdir $repo or die $!;
git($repo, 'init', '-q', '-b', 'main');
my $n = 0;
foreach my $p (@hard, @plain) {
    my $full = "$repo/$p";
    (my $dir = $full) =~ s{/[^/]*$}{};
    system('mkdir', '-p', '--', $dir) == 0 or die "mkdir $dir";
    $n++;
    write_file($full, "int v$n;\nint w$n;\n");
}
write_file("$repo/notes.txt", "not source\n");
git($repo, 'add', '-A');
git($repo, 'commit', '-q', '-m', 'first');

# Sanity: git really quotes the hard paths in plain ls-files output.
my $listed = git_out($repo, 'ls-files');
like($listed, qr/^"caf\\303\\251\.c"$/m, "git ls-files quotes a non-ASCII path (the defect input)");
like($listed, qr/^"tab\\there\.c"$/m, "git ls-files quotes a tab");

my $out = "$workdir/blame-out";
mkdir $out or die $!;
my $stdout = `perl '$script' '$repo' '$out' '\\.(c|h)\$' 2>'$workdir/stderr'`;
my $status = $?;
my $stderr = slurp("$workdir/stderr");
is($status, 0, "blameRepoFiles.pl succeeds on hard paths") or diag($stderr);
my $total = scalar(@hard) + scalar(@plain);
like($stdout, qr/Newly processed \[$total\] Already done \[0\] files Error \[0\]/,
     "every mask-matched file is processed, none fails");

foreach my $p (@hard, @plain) {
    (my $shown = $p) =~ s/([^\x20-\x7e])/sprintf('\\x%02x', ord($1))/ge;
    my $b = slurp("$out/$p.blame");
    ok(defined $b, "blame file exists for [$shown]");
    my @lines = defined $b ? split(/\n/, $b) : ();
    is(scalar(@lines), 2, "two blame lines for [$shown]");
    # Field 2 is empty: the lines come from this file, not from another one.
    like($lines[0] // '', qr/^[0-9a-f]{40};;\tint v[0-9]+;$/, "record format for [$shown]");
}
ok(!-e "$out/notes.txt.blame", "the mask still filters out notes.txt");

# A second run finds every hard path as done (the -f check uses the raw path).
my $again = `perl '$script' '$repo' '$out' '\\.(c|h)\$' 2>/dev/null`;
like($again, qr/Newly processed \[0\] Already done \[$total\] files Error \[0\]/,
     "a second run skips every hard path as done");

# formatBlame.pl alone, with a path that starts with '-'.
{
    my $o2 = "$workdir/fb-out";
    mkdir $o2 or die $!;
    system('perl', $format, '--', $repo, '-dash.c', $o2) == 0
        or fail("formatBlame.pl -- <repo> -dash.c <dir> fails");
    ok(-s "$o2/-dash.c.blame", "formatBlame.pl blames a path that starts with '-'");
}

# git blame --porcelain quotes "filename" too. The compare with the blamed path
# uses the raw bytes (above: a hard path never names itself in field 2). Field 2
# keeps git's quoted form, as before the fix: it never holds a raw newline or tab,
# so a record stays on one line.
foreach my $case (["old${eAcute}.c", "\"old\\303\\251.c\""],
                  ["old\nname.c",    "\"old\\nname.c\""]) {
    my ($old, $quoted) = @$case;
    (my $shown = $old) =~ s/([^\x20-\x7e])/sprintf('\\x%02x', ord($1))/ge;
    my $r = "$workdir/renamed-" . length($old) . "-" . ord(substr($old, 3, 1));
    mkdir $r or die $!;
    git($r, 'init', '-q', '-b', 'main');
    write_file("$r/$old", join('', map { "int line$_;\n" } 1 .. 6));
    git($r, 'add', '-A');
    git($r, 'commit', '-q', '-m', 'add');
    git($r, 'mv', '--', $old, "new${uUml}.c");
    git($r, 'commit', '-q', '-m', 'rename');
    my $o3 = "$r-out";
    mkdir $o3 or die $!;
    my $s = system('perl', $script, $r, $o3, '\.c$');
    is($s, 0, "blameRepoFiles.pl succeeds on the repo renamed from [$shown]");
    my $b = slurp("$o3/new${uUml}.c.blame") // '';
    my @lines = split(/\n/, $b);
    is(scalar(@lines), 6, "six blame lines, one per record, after a rename from [$shown]");
    is(scalar(grep { /^[0-9a-f]{40};\Q$quoted\E;\tint line[0-9];$/ } @lines), 6,
       "field 2 is git's quoted form of [$shown]");
}

done_testing();
