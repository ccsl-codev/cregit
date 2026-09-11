#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use FindBin;
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $script = "$FindBin::Bin/../prettyPrintFiles.pl";
my $workdir = tempdir(CLEANUP => 1);
my $repo = "$workdir/repo";
my $blame_dir = "$workdir/blame";
my $output_dir = "$workdir/html";
my $call_log = "$workdir/calls.log";

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
    return '' unless -f $path;
    open(my $fh, '<', $path) or die "unable to read [$path]: $!";
    local $/;
    my $content = <$fh>;
    return defined $content ? $content : '';
}

sub git {
    my (@args) = @_;
    system('git', '-C', $repo, @args) == 0 or die "git failed: @args";
}

local $ENV{GIT_CONFIG_NOSYSTEM} = 1;
local $ENV{GIT_CONFIG_GLOBAL} = '/dev/null';

make_path($repo);
git('init', '-q', '-b', 'main');
write_file("$repo/src/a.c", "int a;\n");
write_file("$repo/src/b.c", "int b;\n");
write_file("$repo/src/empty.c", '');
write_file("$repo/notes.txt", "notes\n");

local $ENV{GIT_AUTHOR_NAME} = 'Alice';
local $ENV{GIT_AUTHOR_EMAIL} = 'alice@example.com';
local $ENV{GIT_COMMITTER_NAME} = 'Alice';
local $ENV{GIT_COMMITTER_EMAIL} = 'alice@example.com';
git('add', '.');
git('commit', '-q', '-m', 'fixture');

write_file("$blame_dir/src/a.c.blame", "fixture\n");
write_file("$blame_dir/src/empty.c.blame", "fixture\n");

my $stub = "$workdir/pretty-stub.pl";
write_file(
    $stub,
    <<'STUB'
#!/usr/bin/env perl
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Path qw(make_path);

if ($ENV{BARRIER_DIR}) {
    open(my $ready, '>', "$ENV{BARRIER_DIR}/$$") or die $!;
    close $ready;
    my $released = 0;
    for (1..200) {
        my @ready = glob("$ENV{BARRIER_DIR}/*");
        if (@ready >= 2) {
            $released = 1;
            last;
        }
        select(undef, undef, undef, 0.01);
    }
    exit 9 unless $released;
}

exit 7 if $ENV{FAIL_RENDER};

open(my $log, '>>', $ENV{CALL_LOG}) or die $!;
print $log join("\t", @ARGV), "\n";
close $log;

my @positional = grep { $_ !~ /^--(?:header|footer)=/ } @ARGV;
my $output = $positional[4];
my $dir = dirname($output);
make_path($dir) unless -d $dir;
open(my $out, '>', $output) or die $!;
print $out "rendered $positional[5]\n";
close $out;
STUB
);
chmod 0755, $stub or die $!;

my $header = "$workdir/header.html";
my $footer = "$workdir/footer.html";
write_file($header, "header\n");
write_file($footer, "footer\n");

sub run_driver {
    my (@extra) = @_;
    my $stdout = "$workdir/stdout";
    my $stderr = "$workdir/stderr";
    local $ENV{CALL_LOG} = $call_log;
    my $status = system(
        "'$^X' '$script' --prettyCommand='$stub' --header='$header' --footer='$footer' " .
        join(' ', @extra) . " 'cregit.db' 'authors.db' '$repo' '$blame_dir' " .
        "'$output_dir' 'https://example.test/commit/' '\\.c\$' > '$stdout' 2> '$stderr'"
    );
    return ($status, slurp($stdout), slurp($stderr));
}

{
    my ($status, $stdout, $stderr) = run_driver();
    is($status, 0, 'prettyPrintFiles.pl processes a repository fixture');
    ok(-f "$output_dir/src/a.c.html", 'a matching source with blame is rendered');
    ok(!-e "$output_dir/src/b.c.html", 'a source without blame is skipped');
    ok(!-e "$output_dir/src/empty.c.html", 'an empty source is skipped');
    ok(!-e "$output_dir/notes.txt.html", 'a non-matching extension is filtered out');
    like(
        $stdout,
        qr/Newly processed \[1\] Already done \[0\] files Error \[0\]/,
        'the first-run summary reports one generated file'
    );
    is($stderr, '', 'the normal driver run has no stderr');

    my $log = slurp($call_log);
    like($log, qr/--header=\Q$header\E/, 'the custom header is forwarded');
    like($log, qr/--footer=\Q$footer\E/, 'the custom footer is forwarded');
    like($log, qr/src\/a\.c/, 'the repository-relative title is forwarded');
}

{
    my ($status, $stdout) = run_driver();
    is($status, 0, 'a second driver run succeeds');
    like(
        $stdout,
        qr/Newly processed \[0\] Already done \[1\] files Error \[0\]/,
        'existing HTML is skipped without --overwrite'
    );
    is(scalar(() = slurp($call_log) =~ /rendered|--header=/g), 1, 'the renderer was called only once');
}

{
    my ($status, $stdout) = run_driver('--overwrite');
    is($status, 0, '--overwrite succeeds');
    like(
        $stdout,
        qr/Newly processed \[1\] Already done \[0\] files Error \[0\]/,
        '--overwrite regenerates existing output'
    );
    my @calls = grep { length } split /\n/, slurp($call_log);
    is(scalar(@calls), 2, 'the renderer is invoked again during overwrite');
}

{
    local $ENV{FAIL_RENDER} = 1;
    my ($status, $stdout) = run_driver('--overwrite', '--jobs=2');
    isnt($status, 0, 'a renderer failure makes the repository driver fail');
    like(
        $stdout,
        qr/Newly processed \[1\] Already done \[0\] files Error \[1\]/,
        'the failure summary reports the renderer error'
    );
}

{
    write_file("$blame_dir/src/b.c.blame", "fixture\n");
    my $barrier = "$workdir/pretty-barrier";
    make_path($barrier);
    local $ENV{BARRIER_DIR} = $barrier;
    my ($status, $stdout) = run_driver('--overwrite', '--jobs=2');
    is($status, 0, '--jobs runs renderers concurrently');
    like(
        $stdout,
        qr/Newly processed \[2\] Already done \[0\] files Error \[0\]/,
        'parallel rendering reports every completed file'
    );
    ok(-f "$output_dir/src/a.c.html" && -f "$output_dir/src/b.c.html",
       'parallel rendering writes both outputs');
}

{
    my ($status, undef, $stderr) = run_driver('--jobs=0');
    isnt($status, 0, 'zero jobs is rejected');
    like($stderr, qr/--jobs must be a positive integer/,
         'invalid jobs reports a useful error');
}

done_testing();
