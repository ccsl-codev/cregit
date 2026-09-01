#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use DBI;

my $script = "$FindBin::Bin/../prettyPrint-author.pl";
my $workdir = tempdir(CLEANUP => 1);
my $cid = 'a' x 40;
my $original_cid = 'b' x 40;
my $second_cid = 'c' x 40;
my $second_original_cid = 'd' x 40;

sub write_file {
    my ($path, $content) = @_;
    my $dir = dirname($path);
    make_path($dir) unless -d $dir;
    open(my $fh, '>', $path) or die "unable to write [$path]: $!";
    print $fh $content;
    close $fh;
}

sub slurp {
    my ($path) = @_;
    open(my $fh, '<', $path) or die "unable to read [$path]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

my $cregit_db = "$workdir/cregit.db";
my $authors_db = "$workdir/authors.db";

{
    my $dbh = DBI->connect("dbi:SQLite:dbname=$cregit_db", '', '', { RaiseError => 1 });
    $dbh->do('CREATE TABLE commits (cid TEXT PRIMARY KEY, autname TEXT, autemail TEXT, autdate TEXT, summary TEXT)');
    $dbh->do('CREATE TABLE commitmap (cid TEXT PRIMARY KEY, originalcid TEXT, repo TEXT)');
    $dbh->do(
        'INSERT INTO commits VALUES (?, ?, ?, ?, ?)',
        undef,
        $cid,
        'Alice Example',
        'alice@example.com',
        '2020-01-01 00:00:00',
        'add "x"'
    );
    $dbh->do('INSERT INTO commitmap VALUES (?, ?, ?)', undef, $cid, $original_cid, 'g');
    $dbh->do(
        'INSERT INTO commits VALUES (?, ?, ?, ?, ?)',
        undef,
        $second_cid,
        'Bob Example',
        'bob@example.com',
        '2020-01-02 00:00:00',
        'add y'
    );
    $dbh->do('INSERT INTO commitmap VALUES (?, ?, ?)', undef, $second_cid, $second_original_cid, 'g');
    $dbh->disconnect();
}

{
    my $dbh = DBI->connect("dbi:SQLite:dbname=$authors_db", '', '', { RaiseError => 1 });
    $dbh->do('CREATE TABLE emails (emailname TEXT, emailaddr TEXT, personid TEXT)');
    $dbh->do('CREATE TABLE persons (personid TEXT PRIMARY KEY, personname TEXT)');
    $dbh->do(
        'INSERT INTO emails VALUES (?, ?, ?)',
        undef,
        'Alice Example',
        'alice@example.com',
        'alice'
    );
    $dbh->do('INSERT INTO persons VALUES (?, ?)', undef, 'alice', 'Alice Example');
    $dbh->do(
        'INSERT INTO emails VALUES (?, ?, ?)',
        undef,
        'Bob Example',
        'bob@example.com',
        'bob'
    );
    $dbh->do('INSERT INTO persons VALUES (?, ?)', undef, 'bob', 'Bob Example');
    $dbh->disconnect();
}

my $source = "$workdir/example.c";
my $blame = "$workdir/example.c.blame";
my $bad_blame = "$workdir/bad.blame";
my $tie_source = "$workdir/tie.c";
my $tie_blame = "$workdir/tie.c.blame";
my $header = "$workdir/header.html";
my $footer = "$workdir/footer.html";

write_file($source, "int x;\n");
write_file(
    $blame,
    "$cid;;\tname|int\n" .
    "$cid;;\tname|x\n" .
    "$cid;;\toperator|;\n"
);
write_file($bad_blame, "$cid;;\tname|float\n");
write_file($tie_source, "x+y;\n");
write_file(
    $tie_blame,
    "$cid;;\tname|x\n" .
    "$second_cid;;\toperator|+\n" .
    "$cid;;\tname|y\n" .
    "$second_cid;;\toperator|;\n"
);
write_file(
    $header,
    "HEADER _CREGIT_FILENAME_ _CREGIT_DIRNAME_ _CREGIT_VERSION_ _CREGIT_REPO_URL_\n"
);
write_file($footer, "FOOTER\n");

sub run_renderer {
    my ($blame_file, $output, $source_file) = @_;
    $source_file = $source unless defined $source_file;
    my $stdout = "$workdir/stdout";
    my $stderr = "$workdir/stderr";
    my $status = system(
        "'$^X' '$script' --header='$header' --footer='$footer' " .
        "'$cregit_db' '$authors_db' '$source_file' '$blame_file' '$output' " .
        "'src/example.c' 'https://example.test/commit/' > '$stdout' 2> '$stderr'"
    );
    return ($status, slurp($stdout), slurp($stderr));
}

{
    my @rendered;
    for my $run (1..4) {
        my $output = "$workdir/out/tie-$run.html";
        my ($status) = run_renderer($tie_blame, $output, $tie_source);
        is($status, 0, "equal-contribution fixture renders on run $run");
        push @rendered, slurp($output);
    }

    like(
        $rendered[0],
        qr/<!--file stats-->.*Alice Example.*<!--file stats-->.*Bob Example/s,
        'equal contributions use the author name as a stable tiebreaker'
    );
    is($rendered[1], $rendered[0], 'equal-contribution output is stable on run 2');
    is($rendered[2], $rendered[0], 'equal-contribution output is stable on run 3');
    is($rendered[3], $rendered[0], 'equal-contribution output is stable on run 4');
}

{
    my $output = "$workdir/out/deep/example.html";
    my ($status, $stdout, $stderr) = run_renderer($blame, $output);
    is($status, 0, 'prettyPrint-author.pl renders a valid fixture');
    ok(-f $output, 'the HTML output is created, including parent directories');

    my $html = slurp($output);
    like(
        $html,
        qr/HEADER src\/example\.c src 1\.0-RC2 https:\/\/example\.test\/commit\//,
        'header placeholders are expanded'
    );
    like($html, qr/FOOTER/, 'the configured footer is included');
    like($html, qr/Alice Example/, 'the unified author name comes from the persons database');
    like($html, qr/add &quot;x&quot;/, 'commit summaries are escaped in span metadata');
    like(
        $html,
        qr/windowpop\('$original_cid'\)/,
        'token links use the remapped original commit id'
    );
    like($html, qr/Overall Contributors/, 'file-level contribution statistics are emitted');
    like($html, qr/<td align="right">3<\/td>/, 'all three source tokens are counted');
    is($stderr, '', 'a valid rendering has no stderr');
}

{
    my $output = "$workdir/out/bad.html";
    my ($status, $stdout, $stderr) = run_renderer($bad_blame, $output);
    isnt($status, 0, 'a token/source mismatch fails the render');
    like($stderr, qr/Difference/, 'the mismatch explains the violated invariant');
    ok(!-e $output, 'a failed render does not publish a partial output file');
}

done_testing();
