#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 46;
use FindBin;
use File::Temp qw(tempdir);
use File::Basename qw(dirname);

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

# The -C100 time limit and the git exit status, on the same moved.c repository.
# A fake git on PATH is slow or fails for "blame"; all other commands go to the real git.
{
    my $repo2 = "$workdir/moved";
    my $mover = git($repo2, "rev-parse HEAD");
    my $realGit = `command -v git`;
    chomp $realGit;
    my $fakeBin = "$workdir/fakebin";
    mkdir $fakeBin or die $!;
    write_file("$fakeBin/git", <<"FAKE");
#!/bin/sh
blame=0; c100=0
for a in "\$@"; do
    [ "\$a" = blame ] && blame=1
    [ "\$a" = -C100 ] && c100=1
done
if [ \$blame = 1 ]; then
    case "\$FAKE_GIT_MODE" in
        slow-c100) [ \$c100 = 1 ] && exec sleep 60 ;;
        slow-c100-plain-fails) [ \$c100 = 1 ] && exec sleep 60; exit 3 ;;
        fail) echo "fatal: fake failure" >&2; exit 3 ;;
        c100-exit128) [ \$c100 = 1 ] && { echo "fatal: unable to generate diff (fake)" >&2; exit 128; } ;;
        exit128) echo "fatal: unable to generate diff (fake)" >&2; exit 128 ;;
        killed) printf '%040d 1 1 1\\n' 0; kill -9 \$\$ ;;
    esac
fi
exec '$realGit' "\$@"
FAKE
    chmod 0755, "$fakeBin/git" or die $!;

    my $buildDir = dirname($script) . "/build";
    my @tempsBefore = glob("$buildDir/tmpfile-*");

    my $slurp = sub {
        my ($f) = @_;
        open(my $fh, '<', $f) or return undef;
        local $/;
        my $c = <$fh>;
        close $fh;
        return $c;
    };
    my $run = sub {
        my ($mode, $opts, %env) = @_;
        my $dest = tempdir(CLEANUP => 1);
        local $ENV{PATH} = "$fakeBin:$ENV{PATH}";
        local $ENV{FAKE_GIT_MODE} = $mode;
        local $ENV{CREGIT_C100_LIMIT} = $env{limit} if defined $env{limit};
        my $t0 = time();
        my $status = system("perl '$script' $opts '$repo2' moved.c '$dest' 2>'$workdir/stderr'");
        return ($status, $dest, time() - $t0, $slurp->("$workdir/stderr"));
    };

    # (a) A blame that ends within the limit: the same bytes as with no limit.
    my ($s1, $d1) = $run->('none', '');
    my ($s2, $d2) = $run->('none', '--c100-limit=0');
    my ($s3, $d3) = $run->('none', '--c100-limit=3600');
    is($s1 + $s2 + $s3, 0, "the default, no limit and 3600 s runs all succeed");
    my $bytes = $slurp->("$d1/moved.c.blame");
    is($slurp->("$d2/moved.c.blame"), $bytes, "no limit gives the same bytes as the default limit");
    is($slurp->("$d3/moved.c.blame"), $bytes, "a 3600 s limit gives the same bytes as the default limit");

    # (b) -C100 passes the limit: plain blame output and the PLAIN-BLAME line.
    my ($s4, $d4, $sec4, $err4) = $run->('slow-c100', '--c100-limit=1');
    is($s4, 0, "a -C100 run that passes the limit still succeeds");
    cmp_ok($sec4, '<', 30, "the script kills the slow -C100 git and does not wait for it");
    like($err4, qr/^PLAIN-BLAME moved\.c after [0-9]+ s of -C100$/m,
         "the script prints the PLAIN-BLAME line to STDERR");
    my @plain = split /\n/, ($slurp->("$d4/moved.c.blame") // '');
    is(scalar(@plain), 10, "the plain blame file has ten lines");
    my @byMover = grep { /^\Q$mover\E;;/ } @plain[2 .. 7];
    is(scalar(@byMover), 6, "plain blame credits the moved lines to the mover, so no -C100 ran");
    my $plainRef = `git -C '$repo2' blame --porcelain moved.c | grep -c '^	'`;
    chomp $plainRef;
    is($plainRef, 10, "git blame without -C gives the same line count");

    # The environment variable sets the limit when there is no flag.
    my ($s5, $d5, $sec5, $err5) = $run->('slow-c100', '', limit => 1);
    is($s5, 0, "CREGIT_C100_LIMIT=1 run succeeds");
    like($err5, qr/^PLAIN-BLAME moved\.c after [0-9]+ s of -C100$/m,
         "CREGIT_C100_LIMIT sets the -C100 limit");
    is($slurp->("$d5/moved.c.blame"), $slurp->("$d4/moved.c.blame"),
       "the flag and the environment variable give the same bytes");

    # (c) git fails: a non-zero exit and no blame file.
    my ($s6, $d6, undef, $err6) = $run->('fail', '');
    isnt($s6, 0, "a failing git blame gives a non-zero exit");
    ok(!-e "$d6/moved.c.blame", "a failing git blame writes no blame file");
    like($err6, qr/^Error: git blame of \[moved\.c\]/m, "a failing git blame prints an error");

    # git killed after part of its output: the defect that wrote an empty .blame.
    my ($s7, $d7) = $run->('killed', '');
    isnt($s7, 0, "a killed git blame gives a non-zero exit");
    ok(!-e "$d7/moved.c.blame", "a killed git blame writes no blame file");

    # -C100 passes the limit and plain blame then fails.
    my ($s8, $d8, undef, $err8) = $run->('slow-c100-plain-fails', '--c100-limit=1');
    isnt($s8, 0, "a failing plain blame after the limit gives a non-zero exit");
    ok(!-e "$d8/moved.c.blame", "a failing plain blame writes no blame file");

    # (d) -C100 ends with git exit 128 (xdiff refuses a candidate blob over 1 GiB): plain blame.
    my ($s9, $d9, undef, $err9) = $run->('c100-exit128', '');
    is($s9, 0, "a -C100 git exit 128 falls back to plain blame and succeeds");
    like($err9, qr/^PLAIN-BLAME moved\.c after git exit 128 of -C100$/m,
         "the script prints the PLAIN-BLAME line for the git exit 128");
    is($slurp->("$d9/moved.c.blame"), $slurp->("$d4/moved.c.blame"),
       "the exit 128 fallback gives the same bytes as the time-limit fallback");

    # Plain blame also ends with exit 128: a non-zero exit and no blame file.
    my ($s10, $d10) = $run->('exit128', '');
    isnt($s10, 0, "a plain blame exit 128 after the fallback gives a non-zero exit");
    ok(!-e "$d10/moved.c.blame", "a failing fallback writes no blame file");

    my @tempsAfter = glob("$buildDir/tmpfile-*");
    is(scalar(@tempsAfter), scalar(@tempsBefore), "the failed runs leave no temp file in build/");

    # A limit that is not a whole number of seconds is rejected.
    my ($s9) = $run->('none', '--c100-limit=abc');
    isnt($s9, 0, "a limit that is not a number is rejected");
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
