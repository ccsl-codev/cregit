#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 20;
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

# Content moved between files in one commit, which a whole-file rename does not
# cover. The block is well over the 100-character score, so this tests the flag.
{
    my $repo2 = "$workdir/moved";
    mkdir $repo2 or die $!;
    git($repo2, "init -q -b main");

    my $block = join('', map {
        "    total = total + value_$_ * multiplier_$_ + offset_$_;\n"
    } 1 .. 6);

    write_file("$repo2/origin.c", "int helper(void)\n{\n$block    return total;\n}\n");
    git($repo2, "add origin.c");
    my $author = commit_as($repo2, "Author", "write the helper");

    write_file("$repo2/origin.c", "int helper(void)\n{\n    return 0;\n}\n");
    write_file("$repo2/moved.c", "int helper(void)\n{\n$block    return total;\n}\n");
    git($repo2, "add origin.c moved.c");
    my $mover = commit_as($repo2, "Mover", "move the helper into its own file");

    my $dest = tempdir(CLEANUP => 1);
    my $status = system("perl '$script' '$repo2' moved.c '$dest' 2>/dev/null");
    is($status, 0, "formatBlame.pl on the destination of a move succeeds");

    my @lines = slurp_lines("$dest/moved.c.blame");
    is(scalar(@lines), 10, "the moved file has ten blame lines");

    my @moved = @lines[2 .. 7];
    my @wrong = grep { /^\Q$mover\E;/ } @moved;
    is(scalar(@wrong), 0,
       "no moved line is credited to the mover");
    my @right = grep { /^\Q$author\E;origin\.c;/ } @moved;
    is(scalar(@right), 6,
       "all six moved lines are credited to the author, naming origin.c");
}

# Two non-contiguous groups from one commit, in a renamed file: --porcelain
# suppresses the second group's header, and field 2 must survive that.
{
    my $repo3 = "$workdir/suppressed";
    mkdir $repo3 or die $!;
    git($repo3, "init -q -b main");

    write_file("$repo3/old.c", "int one;\nint two;\nint three;\n");
    git($repo3, "add old.c");
    my $first = commit_as($repo3, "Alice", "write three lines");

    write_file("$repo3/old.c", "int one;\nint TWO;\nint three;\n");
    git($repo3, "add old.c");
    my $second = commit_as($repo3, "Bob", "change only the middle line");

    git($repo3, "mv old.c new.c");
    commit_as($repo3, "Carol", "rename the file");

    my $dest = tempdir(CLEANUP => 1);
    my $status = system("perl '$script' '$repo3' new.c '$dest' 2>/dev/null");
    is($status, 0, "formatBlame.pl succeeds on a split-commit renamed file");

    my @lines = slurp_lines("$dest/new.c.blame");
    is($lines[0], "$first;old.c;\tint one;",
       "the first group of the split commit names the old filename");
    is($lines[1], "$second;old.c;\tint TWO;",
       "the intervening commit names the old filename");

    # The regression: this is the group whose header git suppressed.
    is($lines[2], "$first;old.c;\tint three;",
       "the second group of the same commit still names the old filename");
}
