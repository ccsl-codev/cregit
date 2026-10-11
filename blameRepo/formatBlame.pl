#!/usr/bin/env perl

# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.


use File::Basename;
use File::Path qw(make_path remove_tree);
use strict;
use File::Temp qw/ tempfile tempdir mkstemp/;
use File::Copy;
use Getopt::Long;
use Pod::Usage;
use FindBin qw($RealBin);
use POSIX qw(_exit);

# build/ tempdir anchored to the script's dir, not the caller's CWD.
my $buildDir = "$RealBin/build";
make_path($buildDir) if not -d $buildDir;

my $blameExtension = ".blame";
my $help = 0;
my $man = 0;
my $verbose = 0;
# Wall-clock limit in seconds for blame -C100. 0 means no limit.
# blameRepoFiles.pl starts this script, so CREGIT_C100_LIMIT reaches it with no new flag.
my $c100Limit = (defined $ENV{CREGIT_C100_LIMIT} and $ENV{CREGIT_C100_LIMIT} ne '')
    ? $ENV{CREGIT_C100_LIMIT} : 3600;

GetOptions ("blameExtension=s" => \$blameExtension, 
            "c100-limit=s" => \$c100Limit,
            "help"     => \$help,      # string
            "verbose"  => \$verbose,
            "man"      => \$man)   # flag
        or die("Error in command line arguments\n");

die "Error: the -C100 limit must be a whole number of seconds, not [$c100Limit]\n"
    unless $c100Limit =~ /^[0-9]+$/;

if ($man) {
    pod2usage(-verbose=>2);
    exit(1)
}

if (scalar(@ARGV) != 3) {
    pod2usage(-verbose=>1);
    exit(1)
}

my $repo = shift @ARGV;
my $file = shift @ARGV;
my $dest = shift @ARGV;

my ($fh, $temp) = mkstemp( "$buildDir/tmpfile-XXXXX" );

Usage("Error [$file] should be a file in repository [$repo] [$repo/$file]\n\n usage $0 <repo> <filename> <destinationDir>") unless -f "$repo/$file";
Usage( "Error [$repo] should be a git repo\n\nUsage $0 <repo> <filename> <destinationDir>") unless -d "$repo/.git";
Usage( "Error [$dest] should be a directory\n\nUsage $0 <repo> <filename> <destinationDir>") unless -d $dest and $dest ne "";

if ($verbose) {
    print STDERR "$0 processing repo [$repo] file [$file] [$dest]\n";
}

# Suppressed headers still owe field 2 a filename.
my %filenameOfCommit;

# -C100: credit a line moved in from another file to its author, not the mover.
# --porcelain: same shas, but the header once per commit instead of once per line.
# A huge generated file can take hours with -C100. After $c100Limit seconds, the
# script stops it and runs plain blame on the same file, with no limit.
my $started = time();
my $status = Blame_To_Temp($c100Limit, '-C100', '--porcelain');
if (not defined $status) {
    printf STDERR "PLAIN-BLAME %s after %d s of -C100\n", $file, time() - $started;
    $status = Blame_To_Temp(0, '--porcelain');
}
close $fh;
# A git that stops early (killed, out of memory) gives a short or empty stream.
# Do not keep it: no .blame file, and a non-zero exit that blameRepoFiles.pl counts.
if ($status != 0) {
    unlink($temp);
    print STDERR "Error: git blame of [$file] in [$repo] failed with status [$status]; no blame file written\n";
    exit(1);
}
copy_file($temp, $dest, $file . $blameExtension);
if ($verbose) {
    print STDERR "...completed\n";
}

# Runs git blame with @options on $file and writes the records to the temp file.
# Returns the wait status of git, or undef if $limit (seconds, 0 = none) passed first.
# On undef, git is killed and the temp file is empty again.
sub Blame_To_Temp {
    my ($limit, @options) = @_;
    %filenameOfCommit = ();
    seek($fh, 0, 0) and truncate($fh, 0) or die "unable to empty [$temp]: $!";
    # fork + exec, not a shell, so the pid and the exit status are those of git.
    my $pid = open(IN, '-|');
    die "unable to start git blame: $!" unless defined $pid;
    if ($pid == 0) {
        exec('git', '-C', $repo, 'blame', @options, $file);
        print STDERR "unable to execute git: $!\n";
        _exit(127);
    }
    my $timedOut = 0;
    eval {
        local $SIG{ALRM} = sub { die "c100-limit\n" };
        alarm($limit);
        while (my $l = Read_Record()) {
            print $fh $l;
            print $fh "\n";
        }
        alarm(0);
    };
    alarm(0);
    if ($@) {
        die $@ unless $@ eq "c100-limit\n";
        $timedOut = 1;
        kill('KILL', $pid);
    }
    close IN;
    my $waitStatus = $?;
    return undef if $timedOut;
    return $waitStatus;
}

sub Read_Record {
    my $f ;
    my $cid;
    my $sawFilename;
    while (<IN>) {
	chomp;
        if ($_ =~ /^([0-9a-f]{40}) [0-9]/ ) {
            $cid = $1;
            $f = "$1;";
            $sawFilename = 0;
        } elsif ($_ =~ /^(filename) (.+)$/) {
            $filenameOfCommit{$cid} = $2 if defined $cid;
            $sawFilename = 1;
	    if ($2 ne $file) {
		$f .=  $2 . ";";
	    } else {
		$f .= ";";
	    }
	} elsif (/^	(.*)/) { #actual line
            if (not $sawFilename) {
                my $known = defined $cid ? $filenameOfCommit{$cid} : undef;
                if (defined $known and $known ne $file) {
                    $f .= $known . ";";
                } else {
                    $f .= ";";
                }
            }
	    $f = $f . $_;
	    return $f;
	} else {
	    ; # simply ignore
	}
    }
    return $f;
}

sub Usage {
    my ($m) = @_;
    print STDERR "$m\n";
    pod2usage(-verbose=>1);
    exit(1);
}

sub copy_file
{
    my ($from, $toDir, $toName) = @_;
    
    my $to = "${toDir}/$toName";
    $toDir = dirname($to);

#    printf ("copy [$from] to [$to] [$toDir][$toName]\n");

    die "from file does not exist in copy_file [$from]" if not -f $from;

    if (not -d $toDir) {
        printf("Creating directory [$toDir]\n");
	make_path($toDir) or "die unable to create directory $to";
    } 
    move($from, $to) or
            (unlink($to),  "unable to move [$from] to [$to]");
}

__END__

=head1 NAME

formatBlame.pl - extract the blame information for a file

=head1 SYNOPSIS

formatBlame.pl [options] <repository> <file> <outputDirectory>

     Options:
       --blame-extension=s  extension to append to output files. 
                            Default is .blame
       --c100-limit=N       seconds for git blame -C100. After that, the
                            script kills it and runs git blame without -C.
                            Default is $CREGIT_C100_LIMIT, else 3600.
                            0 means no limit.
       --help               brief help message
       --man                full documentation

=head1 OPTIONS

=over 8

=item B<--help>
    Print a brief help message and exits.

=item B<--man>
    Prints the manual page and exits.

=back

=head1 DESCRIPTION

B<This program> will read run blame on a given file in a given git 
repository and create a file in the output directory
with the same full path as the original file, with 
the .blame extension (which can be changed with the --blame option).

If git blame -C100 does not end within the limit, the script runs
git blame without -C and prints "PLAIN-BLAME <file> after <N> s of -C100"
to STDERR. If git fails, the script writes no output file and exits 1.

=cut
