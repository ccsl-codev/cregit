#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $root = "$FindBin::Bin/../..";   # tests/t -> repo root

my $script  = "$root/blameRepo/blameRepoFiles.pl";
my $workdir = tempdir(CLEANUP => 1);

$ENV{GIT_CONFIG_NOSYSTEM} = 1;
$ENV{GIT_CONFIG_GLOBAL}   = '/dev/null';
$ENV{GIT_AUTHOR_DATE}     = '2020-01-01T00:00:00 +0000';
$ENV{GIT_COMMITTER_DATE}  = '2020-01-01T00:00:00 +0000';
$ENV{GIT_AUTHOR_NAME}     = 'Alice';
$ENV{GIT_AUTHOR_EMAIL}    = 'alice@example.com';
$ENV{GIT_COMMITTER_NAME}  = 'Alice';
$ENV{GIT_COMMITTER_EMAIL} = 'alice@example.com';

sub git {
    my ($repo, @args) = @_;
    my $cmd = "git -C '$repo' " . join(' ', @args);
    system($cmd) == 0 or die "git failed: $cmd";
}

sub write_file {
    my ($path, $content) = @_;
    open(my $fh, '>', $path) or die $!;
    print $fh $content;
    close $fh;
}

my $repo = "$workdir/repo";
mkdir $repo or die $!;
git($repo, "init -q -b main");
write_file("$repo/a.c", "int a;\n");
write_file("$repo/b.c", "int b;\n");
write_file("$repo/notes.txt", "hello\n");
git($repo, "add .");
git($repo, "commit -q -m first");

my $out = "$workdir/blame-out";
mkdir $out or die $!;

{
    my $stdout = `perl '$script' '$repo' '$out' '\\.c\$' 2>'$workdir/stderr'`;
    is($?, 0, "blameRepoFiles.pl succeeds");
    ok(-f "$out/a.c.blame", "a.c.blame created");
    ok(-f "$out/b.c.blame", "b.c.blame created");
    ok(!-e "$out/notes.txt.blame", "notes.txt is filtered out by the regexp");
    like($stdout, qr/Newly processed \[2\] Already done \[0\] files Error \[0\]/,
         "summary reports two newly processed files");
}

{
    my $stdout = `perl '$script' '$repo' '$out' '\\.c\$' 2>/dev/null`;
    is($?, 0, "second run succeeds");
    like($stdout, qr/Newly processed \[0\] Already done \[2\] files Error \[0\]/,
         "existing .blame files are skipped");
}

{
    my $stdout = `perl '$script' --overwrite '$repo' '$out' '\\.c\$' 2>/dev/null`;
    is($?, 0, "overwrite run succeeds");
    like($stdout, qr/Newly processed \[2\] Already done \[0\] files Error \[0\]/,
         "--overwrite reprocesses the files");
}

{
    my $stdout = `perl '$script' --jobs=2 --blameCommand=/bin/false --overwrite '$repo' '$out' '\\.c\$' 2>/dev/null`;
    isnt($?, 0, "a formatter failure makes the repository driver fail");
    like($stdout, qr/Newly processed \[2\] Already done \[0\] files Error \[2\]/,
         "the parallel failure summary reports every formatter error");
}

{
    my $barrier = "$workdir/blame-barrier";
    mkdir $barrier or die $!;
    my $stub = "$workdir/blame-parallel-stub.pl";
    write_file($stub, <<'STUB');
#!/usr/bin/env perl
use strict;
use warnings;

open(my $ready, '>', "$ENV{BARRIER_DIR}/$$") or die $!;
close $ready;

for (1..200) {
    my @ready = glob("$ENV{BARRIER_DIR}/*");
    exit 0 if @ready >= 2;
    select(undef, undef, undef, 0.01);
}
exit 9;
STUB
    chmod 0755, $stub or die $!;

    local $ENV{BARRIER_DIR} = $barrier;
    my $stdout = `perl '$script' --jobs=2 --blameCommand='$stub' --overwrite '$repo' '$out' '\\.c\$' 2>/dev/null`;
    is($?, 0, "--jobs runs blame commands concurrently");
    like($stdout, qr/Newly processed \[2\] Already done \[0\] files Error \[0\]/,
         "parallel blame reports every completed file");
}

{
    my $stdout = `perl '$script' --jobs=0 '$repo' '$out' '\\.c\$' 2>&1`;
    isnt($?, 0, "zero jobs is rejected");
    like($stdout, qr/--jobs must be a positive integer/,
         "invalid jobs reports a useful error");
}

# A run cannot finish before its single longest file does, so the largest file
# must not be the last one started: every other job slot would drain while it
# runs alone. git ls-files order is alphabetical, which puts big files wherever
# their names fall. Dispatch order is reported on STDERR as "N: name", so assert
# on that rather than on timing, which would make the test flaky.
{
    my $sized = "$workdir/sized";
    mkdir $sized or die $!;
    git($sized, "init -q -b main");
    # Alphabetical order here is the exact opposite of size order.
    write_file("$sized/a_small.c",  "int a;\n");
    write_file("$sized/b_big.c",    "int b;\n" x 400);
    write_file("$sized/c_medium.c", "int c;\n" x 20);
    git($sized, "add .");
    git($sized, "commit -q -m first");

    my $sizedOut = "$workdir/sized-out";
    mkdir $sizedOut or die $!;
    system("perl '$script' '$sized' '$sizedOut' '\\.c\$' 2>'$workdir/order'") == 0
        or die "sized run failed";

    open(my $fh, '<', "$workdir/order") or die $!;
    my @dispatched;
    while (<$fh>) { push @dispatched, $1 if /^\d+: (.+)$/; }
    close $fh;

    is_deeply(\@dispatched, ['b_big.c', 'c_medium.c', 'a_small.c'],
              "files are dispatched largest first, not alphabetically");
}

# Equal sizes must not reshuffle the queue: ties keep git ls-files order, so a
# corpus of uniform files is dispatched exactly as it was before this change.
{
    my $tied = "$workdir/tied";
    mkdir $tied or die $!;
    git($tied, "init -q -b main");
    write_file("$tied/$_.c", "int x;\n") for qw(a b c);
    git($tied, "add .");
    git($tied, "commit -q -m first");

    my $tiedOut = "$workdir/tied-out";
    mkdir $tiedOut or die $!;
    system("perl '$script' '$tied' '$tiedOut' '\\.c\$' 2>'$workdir/tied-order'") == 0
        or die "tied run failed";

    open(my $fh, '<', "$workdir/tied-order") or die $!;
    my @dispatched;
    while (<$fh>) { push @dispatched, $1 if /^\d+: (.+)$/; }
    close $fh;

    is_deeply(\@dispatched, ['a.c', 'b.c', 'c.c'],
              "equally sized files keep git ls-files order");
}

done_testing();
