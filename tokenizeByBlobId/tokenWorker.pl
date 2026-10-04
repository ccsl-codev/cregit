#!/usr/bin/env perl

use strict;
use warnings;
use bytes;
use Digest::SHA qw(sha1_hex);
use Errno qw(EINTR);
use File::Copy qw(move);
use File::Path qw(make_path);
use File::Temp qw(tempfile);
use FindBin qw($RealBin);
use IO::Select;
use POSIX qw(_exit);
use File::Basename qw(basename dirname);
use lib "$RealBin/../tokenize";
use CregitLanguages;
use CregitSrcMl;

binmode STDIN;
binmode STDOUT;
$| = 1;
$SIG{PIPE} = 'IGNORE';

my $memoDir = $ENV{BFG_MEMO_DIR};
die "You must define BFG_MEMO_DIR\n" if not defined($memoDir) or $memoDir eq "";
die "Sha dir [$memoDir] does not exist\n" if not -d $memoDir;

my $tokenizeCmd = $ENV{BFG_TOKENIZE_CMD};
die "Tokenize command not defined. Use BFG_TOKENIZE_CMD\n"
    if not defined($tokenizeCmd) or $tokenizeCmd eq "";

my @tokenizeCommand = grep { length($_) } split /\s+/, $tokenizeCmd;
my $buildDir = "$RealBin/build";
make_path($buildDir) if not -d $buildDir;

my $inProcess = in_process_tokenizer(@tokenizeCommand);

print STDOUT "READY\n";

while (defined(my $header = <STDIN>)) {
    if ($header !~ /\AREQ ([[:xdigit:]]+) ([0-9]+) ([0-9]+) ([0-9]+) ([0-9]+)\n\z/) {
        protocol_error("malformed request header");
    }

    my ($origSha, $bodyLen, $fnLen, $pathLen, $timeoutSecs) = ($1, $2, $3, $4, $5);
    my $filename = read_exact($fnLen, "filename");
    my $fullPath = read_exact($pathLen, "full path");
    my $contents = read_exact($bodyLen, "body");

    my ($exitCode, $stdout, $stderr);
    my $ok = eval {
        ($exitCode, $stdout, $stderr) = process_request(
            $origSha, $filename, $fullPath, $contents, $timeoutSecs
        );
        1;
    };
    if (not $ok) {
        $exitCode = 255;
        $stdout = "";
        $stderr = $@ || "tokenizer request failed\n";
    }

    print STDOUT "RES $exitCode " . length($stdout) . " " . length($stderr) . "\n";
    print STDOUT $stdout, $stderr;
}

$inProcess->shutdown() if $inProcess;
exit 0;

# In-process srcML only for the stock dispatcher/parser with options the worker honors.
sub in_process_tokenizer {
    my ($program, @options) = @_;
    return undef if ($ENV{BFG_WORKER_INPROC} // "1") eq "0";
    return undef unless defined $program;
    my $name = basename($program);
    return undef unless $name eq "tokenize.pl" or $name eq $CregitLanguages::SRCML_PARSER_REL;

    my %config = (
        srcml       => "srcml",
        srcml2token => dirname($program) . "/srcMLtoken/srcml2token",
        ctags       => "ctags-universal",
        position    => 0,
    );
    for my $option (@options) {
        if    ($option =~ /\A--srcml=(.+)\z/)       { $config{srcml} = $1 }
        elsif ($option =~ /\A--srcml2token=(.+)\z/) { $config{srcml2token} = $1 }
        elsif ($option =~ /\A--ctags=(.+)\z/)       { $config{ctags} = $1 }
        elsif ($option eq "--position")             { $config{position} = 1 }
        else { return undef }
    }
    return CregitSrcMl->new(%config);
}

sub srcml_language {
    my ($language) = @_;
    my $parser = $CregitLanguages::LANG_PARSER_REL{$language};
    return (defined $parser and $parser eq $CregitLanguages::SRCML_PARSER_REL);
}

sub read_exact {
    my ($length, $label) = @_;
    my $value = "";

    while (length($value) < $length) {
        my $read = read(STDIN, $value, $length - length($value), length($value));
        protocol_error("unable to read $label: $!") if not defined($read);
        protocol_error("unexpected EOF while reading $label") if $read == 0;
    }

    return $value;
}

sub protocol_error {
    my ($message) = @_;
    print STDERR "$message\n";
    exit 2;
}

sub process_request {
    my ($origSha, $filename, $fullPath, $contents, $timeoutSecs) = @_;

    my ($fileExt, $language);
    if ($filename =~ /\.([^.]+)\z/) {
        $fileExt = lc($1);
        $language = CregitLanguages::language_for_ext($1);
    }
    if (not defined($language)) {
        my $shownExt = defined($fileExt) ? $fileExt : "";
        return (255, "", "unknown file extension [$shownExt]\n");
    }

    my $sha1 = sha1_hex($contents);
    my $dir = "$memoDir/" . substr($sha1, 0, 2) . "/" . substr($sha1, 2, 2);
    my $memoFile = "$dir/$sha1";

    if (-f $memoFile) {
        open(my $memo, '<:raw', $memoFile)
            or die "unable to open memoized file [$memoFile]: $!\n";
        local $/;
        my $output = <$memo>;
        close($memo) or die "unable to close memoized file [$memoFile]: $!\n";
        return (0, $output, "");
    }

    my $tempDir = File::Temp->newdir(
        "tokdir-XXXXX",
        DIR => $buildDir,
        CLEANUP => 1,
    );
    my $inputName = "input.$fileExt";
    my $inputFile = "$tempDir/$inputName";
    open(my $input, '>:raw', $inputFile)
        or die "unable to write temp input [$inputFile]: $!\n";
    print {$input} $contents;
    close($input) or die "unable to close temp input [$inputFile]: $!\n";

    my ($exitCode, $output, $error);
    if ($inProcess and srcml_language($language)) {
        ($exitCode, $output, $error) = $inProcess->tokenize(
            $language, $inputName, "$tempDir", $timeoutSecs
        );
    } else {
        my @command = (@tokenizeCommand, "--language=$language", $inputName);
        ($exitCode, $output, $error) = run_command(
            "$tempDir", $timeoutSecs, @command
        );
    }
    return ($exitCode, "", $error) if $exitCode != 0;

    make_path($dir) if not -d $dir;
    my ($memoOut, $tempOutput) = tempfile(
        "tmpfile-out-XXXXX",
        DIR => $buildDir,
        UNLINK => 0,
    );
    binmode $memoOut;
    print {$memoOut} $output;
    close($memoOut) or die "unable to close tokenizer output [$tempOutput]: $!\n";
    move($tempOutput, $memoFile)
        or die "unable to move tokenizer output to [$memoFile]: $!\n";

    return (0, $output, $error);
}

sub run_command {
    my ($workingDir, $timeoutSecs, @command) = @_;
    pipe(my $stdoutRead, my $stdoutWrite) or die "unable to create stdout pipe: $!\n";
    pipe(my $stderrRead, my $stderrWrite) or die "unable to create stderr pipe: $!\n";

    my $pid = fork();
    die "unable to fork tokenizer: $!\n" if not defined($pid);

    if ($pid == 0) {
        close($stdoutRead);
        close($stderrRead);
        open(STDOUT, '>&', $stdoutWrite) or _exit(255);
        open(STDERR, '>&', $stderrWrite) or _exit(255);
        binmode STDOUT;
        binmode STDERR;
        close($stdoutWrite);
        close($stderrWrite);
        setpgrp(0, 0) or do {
            print STDERR "unable to create tokenizer process group: $!\n";
            _exit(255);
        };
        chdir($workingDir) or do {
            print STDERR "unable to enter tokenizer directory [$workingDir]: $!\n";
            _exit(255);
        };
        { no warnings 'exec'; exec {$command[0]} @command; }
        print STDERR "unable to execute tokenizer [$command[0]]: $!\n";
        _exit(255);
    }

    close($stdoutWrite);
    close($stderrWrite);
    binmode $stdoutRead;
    binmode $stderrRead;

    my $timedOut = 0;
    local $SIG{ALRM} = sub {
        $timedOut = 1;
        kill 'TERM', -$pid;
        select(undef, undef, undef, 0.1);
        kill 'KILL', -$pid;
    };
    alarm($timeoutSecs);

    my $stdoutFd = fileno($stdoutRead);
    my $stderrFd = fileno($stderrRead);
    my $selector = IO::Select->new($stdoutRead, $stderrRead);
    my %stream = (
        $stdoutFd => "",
        $stderrFd => "",
    );
    while ($selector->count()) {
        for my $handle ($selector->can_read()) {
            my $chunk = "";
            my $read = sysread($handle, $chunk, 65536);
            if (not defined($read)) {
                next if $! == EINTR;
                die "unable to read tokenizer output: $!\n";
            }
            if ($read == 0) {
                $selector->remove($handle);
                close($handle);
                next;
            }
            $stream{fileno($handle)} .= $chunk;
        }
    }

    waitpid($pid, 0);
    my $status = $?;
    alarm(0);

    my $stdout = $stream{$stdoutFd};
    my $stderr = $stream{$stderrFd};
    if ($timedOut) {
        $stderr .= "tokenize command timed out after [$timeoutSecs] seconds\n";
        return (124, "", $stderr);
    }

    if ($status == -1) {
        return (255, "", $stderr . "unable to wait for tokenizer: $!\n");
    }
    my $signal = $status & 127;
    if ($signal) {
        return (128 + $signal, "", $stderr . "tokenizer killed by signal [$signal]\n");
    }

    return ($status >> 8, $stdout, $stderr);
}
