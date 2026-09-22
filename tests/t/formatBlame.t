#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 12;
use FindBin;
use File::Temp qw(tempdir);

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

my $script  = "$root/blameRepo/formatBlame.pl";
my $workdir = tempdir(CLEANUP => 1);

$ENV{GIT_CONFIG_NOSYSTEM} = 1;
$ENV{GIT_CONFIG_GLOBAL}   = '/dev/null';
$ENV{GIT_AUTHOR_DATE}     = '2020-01-01T00:00:00 +0000';
$ENV{GIT_COMMITTER_DATE}  = '2020-01-01T00:00:00 +0000';
$ENV{GIT_COMMITTER_NAME}  = 'Committer';
$ENV{GIT_COMMITTER_EMAIL} = 'committer@example.com';

sub git {
    my ($repo, @args) = @_;
    my $cmd = "git -C '$repo' " . join(' ', @args);
    my $out = `$cmd`;
    die "git failed: $cmd" if $? != 0;
    chomp $out;
    return $out;
}

sub commit_as {
    my ($repo, $name, $message) = @_;
    local $ENV{GIT_AUTHOR_NAME}  = $name;
    local $ENV{GIT_AUTHOR_EMAIL} = lc($name) . '@example.com';
    git($repo, "commit -q -m '$message'");
    return git($repo, "rev-parse HEAD");
}

sub write_file {
    my ($path, $content) = @_;
    open(my $fh, '>', $path) or die $!;
    print $fh $content;
    close $fh;
}

sub slurp_lines {
    my ($file) = @_;
    open(my $fh, '<', $file) or die "unable to read [$file]: $!";
    my @lines = <$fh>;
    chomp @lines;
    return @lines;
}

my $repo = "$workdir/repo";
mkdir $repo or die $!;
git($repo, "init -q -b main");
write_file("$repo/f.c", "int one;\nint two;\n");
git($repo, "add f.c");
my $cid1 = commit_as($repo, "Alice", "first");
write_file("$repo/f.c", "int one;\nint two;\nint three;\n");
git($repo, "add f.c");
my $cid2 = commit_as($repo, "Bob", "second");

{
    my $dest = tempdir(CLEANUP => 1);
    my $status = system("perl '$script' '$repo' f.c '$dest' 2>'$workdir/stderr'");
    is($status, 0, "formatBlame.pl succeeds");
    ok(-f "$dest/f.c.blame", "creates <dest>/f.c.blame");

    my @lines = slurp_lines("$dest/f.c.blame");
    is(scalar(@lines), 3, "one blame line per source line");
    is($lines[0], "$cid1;;\tint one;",   "line 1 blamed on the first commit");
    is($lines[1], "$cid1;;\tint two;",   "line 2 blamed on the first commit");
    is($lines[2], "$cid2;;\tint three;", "line 3 blamed on the second commit");
}

{
    my $dest = tempdir(CLEANUP => 1);
    my $status = system("perl '$script' --blameExtension=.tok '$repo' f.c '$dest' 2>/dev/null");
    is($status, 0, "formatBlame.pl with --blameExtension succeeds");
    ok(-f "$dest/f.c.tok", "creates <dest>/f.c.tok");
}

{
    git($repo, "mv f.c g.c");
    my $cid3 = commit_as($repo, "Alice", "rename");

    my $dest = tempdir(CLEANUP => 1);
    my $status = system("perl '$script' '$repo' g.c '$dest' 2>/dev/null");
    is($status, 0, "formatBlame.pl on the renamed file succeeds");

    my @lines = slurp_lines("$dest/g.c.blame");
    is(scalar(@lines), 3, "renamed file still has three blame lines");
    is($lines[0], "$cid1;f.c;\tint one;",
       "pre-rename lines carry the original filename");
    is($lines[2], "$cid2;f.c;\tint three;",
       "all pre-rename commits report the old name");
}
