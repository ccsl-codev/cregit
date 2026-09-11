#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Temp qw(tempdir);

my $script  = "$FindBin::Bin/../blameRepoFiles.pl";
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

done_testing();
