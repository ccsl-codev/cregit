#!/usr/bin/env perl

# A persistent tokenBySha.pl with the same output and memo. The protocol is in
# WORKER_PROTOCOL.md.

use strict;
use warnings;
use bytes;
use Digest::SHA qw(sha1_hex);
use File::Basename qw(dirname);
use File::Copy qw(move);
use File::Path qw(make_path);
use File::Temp qw(tempfile);
use FindBin qw($RealBin);
use POSIX qw(_exit);
use lib "$RealBin/../tokenize";
use CregitLanguages;

binmode STDIN;
binmode STDOUT;
$| = 1;

my $memoDir = $ENV{BFG_MEMO_DIR} // "";
die "You must define BFG_MEMO_DIR\n" if $memoDir eq "";
die "Sha dir [$memoDir] does not exist\n" if not -d $memoDir;
my @tokenizeCommand = split ' ', $ENV{BFG_TOKENIZE_CMD} // "";
die "Tokenize command not defined. Use BFG_TOKENIZE_CMD\n" if not @tokenizeCommand;
my $buildDir = "$RealBin/build";
make_path($buildDir);

print "READY\n";
while (defined(my $header = <STDIN>)) {
    my ($bodyLength, $nameLength, $timeoutSecs) = $header =~ /\AREQ (\d+) (\d+) (\d+)\n\z/
        or protocol_error("malformed request header [$header]");
    my $filename = read_exact($nameLength);
    my $contents = read_exact($bodyLength);
    my ($exit, $out, $err) = eval { tokenize_blob($filename, $contents, $timeoutSecs) };
    ($exit, $out, $err) = (255, "", $@) if not defined $exit;
    print "RES $exit ", length($out), " ", length($err), "\n", $out, $err;
}
exit 0;

sub read_exact {
    my ($length) = @_;
    my $value = "";
    while (length($value) < $length) {
        my $read = read(STDIN, $value, $length - length($value), length($value));
        protocol_error(defined $read ? "the request ended early" : "cannot read the request: $!")
            if not $read;
    }
    return $value;
}

sub protocol_error {
    print STDERR "tokenWorker.pl: @_\n";
    exit 2;
}

sub tokenize_blob {
    my ($filename, $contents, $timeoutSecs) = @_;
    my ($ext) = $filename =~ /\.([^.]+)$/;
    $ext = lc($ext // "");
    my $language = $CregitLanguages::EXT_LANG{$ext};
    return (255, "", "unknown file extension [$ext]\n") if not defined $language;

    my $sha1 = sha1_hex($contents);
    my $memoFile = "$memoDir/" . substr($sha1, 0, 2) . "/" . substr($sha1, 2, 2) . "/$sha1";
    return (0, read_file($memoFile), "") if -f $memoFile;

    my $workDir = File::Temp->newdir("tokdir-XXXXX", DIR => $buildDir);
    write_file("$workDir/input.$ext", $contents);
    my ($exit, $out, $err) = run_tokenizer("$workDir", $timeoutSecs, @tokenizeCommand,
                                           "--language=$language", "input.$ext");
    return ($exit, "", $err) if $exit != 0;
    write_memo($memoFile, $out);
    return (0, $out, $err);
}

sub run_tokenizer {
    my ($workDir, $timeoutSecs, @command) = @_;
    my $pid = fork() // die "cannot fork the tokenizer: $!\n";
    exec_in_group($workDir, @command) if $pid == 0;

    my $timedOut = 0;
    local $SIG{ALRM} = sub { $timedOut = 1; kill_group($pid) };
    alarm($timeoutSecs);
    waitpid($pid, 0);
    my $status = $?;
    alarm(0);

    my $err = read_file("$workDir/.stderr");
    return (124, "", $err . "tokenize command timed out after [$timeoutSecs] seconds\n") if $timedOut;
    return (255, "", $err . "tokenize command was killed by signal " . ($status & 127) . "\n") if $status & 127;
    return ($status >> 8, read_file("$workDir/.stdout"), $err);
}

# The child's own process group lets a timeout kill srcml and everything else
# the tokenizer started.
sub exec_in_group {
    my ($workDir, @command) = @_;
    setpgrp(0, 0);
    chdir($workDir) and open(STDIN, '<', '/dev/null') and open(STDOUT, '>', '.stdout')
        and open(STDERR, '>', '.stderr') or _exit(255);
    { no warnings 'exec'; exec { $command[0] } @command; }
    print STDERR "cannot execute the tokenizer [$command[0]]: $!\n";
    _exit(255);
}

sub kill_group {
    my ($pid) = @_;
    kill 'TERM', -$pid;
    select(undef, undef, undef, 0.1);
    kill 'KILL', -$pid;
}

sub read_file {
    my ($path) = @_;
    open(my $in, '<:raw', $path) or die "cannot read [$path]: $!\n";
    local $/;
    return <$in> // "";
}

sub write_file {
    my ($path, $contents) = @_;
    open(my $out, '>:raw', $path) or die "cannot write [$path]: $!\n";
    print {$out} $contents;
    close($out) or die "cannot close [$path]: $!\n";
}

sub write_memo {
    my ($memoFile, $output) = @_;
    make_path(dirname($memoFile));
    my ($out, $temp) = tempfile("tmpfile-out-XXXXX", DIR => $buildDir);
    binmode $out;
    print {$out} $output;
    close($out) or die "cannot close [$temp]: $!\n";
    move($temp, $memoFile) or die "cannot move the tokenization to [$memoFile]: $!\n";
}
