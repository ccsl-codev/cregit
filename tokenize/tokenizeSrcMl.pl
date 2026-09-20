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

use strict;
use File::Basename;
use File::Temp qw(tempfile);
use FindBin;
use lib $FindBin::Bin;
use CregitLanguages;

# Exit status meaning "this parse did not produce a usable tokenization": srcML
# died on a signal, either pipeline stage exited non-zero, or the token stream
# came back empty (which a healthy srcML parse never is -- see Tokenize).
#
# Picked to be distinguishable from every other outcome in the chain: below 128,
# so it can never be confused with a shell's 128+signal encoding; clear of GNU
# timeout's 124 and 137, which blobExec already reads as "we killed it"; and clear
# of blobExec's own 2 (abort), 3 (mask changed) and 4 (timed out).
our $PARSER_CRASH_EXIT = 33;

my %declarations;
my %listDeclarations;

my %languages = ("C" => 1,
                 "C++" => 1,
                 "Java" => 1);

# Only the srcML-routed extensions: this script is the srcML parser, so
# autodetecting a .rs file here would hand `-l Rust` to srcml. tokenize.pl is the
# dispatcher that knows about the other parsers.
my %extensions = CregitLanguages::srcml_extensions_by_dot();



use Getopt::Long;

my $usage = "
Usage $0 [options] <sourcefilename> <outputfile>*
        
Options:
   --srcml2token=<path to srcml2token>
   --srcml=<path to srcml>
   --language=<C/C++/Java>
   --ctags=-<path to ctags-universal>
   --position
";

my $basedir = dirname($0);
$basedir = "." if ($basedir eq "");

my $srcml   = "srcml";
my $srcml2token = "$basedir/srcMLtoken/srcml2token";
my $ctags = "ctags-universal";
my $language = "";
my $verbose;
my $position = 0;

GetOptions ("srcml=s" => \$srcml, 
            "srcml2token=s"   => \$srcml2token,
            "language=s"      => \$language,
            "ctags=s"         => \$ctags,
            "position"        => \$position,
            "verbose"  => \$verbose)   # flag
  or die($usage);


my $filename = shift;
my $output = shift;


print STDERR "Tokenizing $filename\n" if $verbose;
if ($output ne "") {
    open(OUT, ">$output") or die "Unable to create output file\n";
    select OUT;
}

# find language

if ($language eq "") {
    # autodetect
    Usage("File has no extension. You must provide one [$filename]") unless $filename =~ /(\.[a-z\+]+)$/i;
    my $ext = lc($1);
    $language = CregitLanguages::language_for_ext( substr($1, 1) );
    $language = undef unless defined $language and exists $extensions{ lc($1) };
    Usage("Unknown extension [$ext] in file [$filename]. You must provide language using --language option") unless defined $language and $language ne "";
}

Usage("filename not specified") if $filename eq "";

Read_Declarations($filename, $language);

#Declarations_Test();

Tokenize($filename);

if ($output ne "") {
    close(OUT);
}

exit;


sub Tokenize
{
    my $saveDir = `pwd`;
    chomp $saveDir;
    my ($filename) = @_;

    # The two stages still stream through a single pipe on purpose: buffering
    # srcML's XML would cost memory proportional to the source (3.9MB of C becomes
    # far more XML) and buy nothing. What changes is that the pipeline's per-stage
    # exit statuses are no longer discarded.
    #
    # Why this is not just `close(parser)`: Perl's `open(FH, "cmd |")` reports
    # failure only at close, and $? then carries the LAST command's status. Here
    # that is srcml2token, which exits 0 even while printing
    #   "Fatal Error at file stdin, line 1, char 1 / invalid document structure"
    # on the truncated XML a crashed srcML leaves behind. So an upstream srcML
    # death was hidden twice over: once by open(), once by srcml2token's exit 0.
    #
    # bash's PIPESTATUS is the only thing that reports BOTH stages, so it is
    # written to a temp file and read back after close. Note that PIPESTATUS
    # encodes a signal death the way a shell does, as 128+signal (139 for SIGSEGV,
    # 134 for SIGABRT), not as a raw wait status.
    my ($statusFh, $statusFile) =
        tempfile("cregit-pipestatus-XXXXXX", TMPDIR => 1, UNLINK => 1);
    close $statusFh;

    my $pipeline = sprintf(
        '%s -l %s --position %s | %s; printf %%s\\ %%s "${PIPESTATUS[0]}" "${PIPESTATUS[1]}" > %s',
        Shell_Quote($srcml),   Shell_Quote($language),
        Shell_Quote($filename), Shell_Quote($srcml2token),
        Shell_Quote($statusFile));

    # Explicitly bash, not the `sh` that a one-argument open() would pick:
    # PIPESTATUS is a bashism and /bin/sh is not guaranteed to be bash.
    open(parser, "-|", "bash", "-c", $pipeline)
        or die "Unable to execute srcml pipeline on file [$filename]: $!";

    my $lastLine = -1;
    my $tokensRead = 0;

    while (<parser>) {
        $tokensRead++;
        #        print STDERR;
        chomp;
        my $line =$_;
        die "unable to parse srcml line [$line]" unless $line =~ /^([0-9]+|-):([0-9]+|-)\s+(.+)$/;
        my ($line, $col, $token) = ($1, $2, $3);
#        print STDERR "$line:$col:[$token]\n";
        die "ilegall line [$line] with [$line][$col]" if $line eq '' ;
        if ($line != $lastLine) {
            my @d = Declarations_In_Line($line);
            foreach my $dec (@d) {
                my %thisDec = Get_Declaration($line, $dec);
                if ($position) {
                    print "$line:-|";
                }
                print "DECL|";
                print "$thisDec{type}|$thisDec{name}\n";
            }
        } 
        $lastLine = $line;
        if ($position) {
            print "$line:$col|"
        }
        print "$token\n";
        if ($token =~ /^end_/) {
            if ($position) {
                print "-:-|"
            }
            printf "\n";
        }

    }
    my $closed      = close parser;
    my $closeStatus = $?;
    chdir($saveDir);

    Verify_Parse($filename, $tokensRead, $statusFile, $closed, $closeStatus);
}

# Single-quote a string for the shell: end the quote, escape the literal quote,
# reopen. Replaces the bare '$filename' interpolation that used to sit in the
# command string, which broke on any path containing a quote.
sub Shell_Quote
{
    my ($s) = @_;
    $s = '' unless defined $s;
    $s =~ s/'/'\\''/g;
    return "'$s'";
}

# Fail closed. Anything other than "both stages exited 0 and we got a non-empty
# token stream" is a defect, and must be reported with a status the caller can
# count rather than silently becoming a 0-byte tokenization.
sub Verify_Parse
{
    my ($filename, $tokensRead, $statusFile, $closed, $closeStatus) = @_;

    my ($srcmlStatus, $tokenStatus) = Read_Pipe_Status($statusFile);

    # srcML first: it is the stage that crashes, and its status is the one the old
    # code could never see.
    if (defined $srcmlStatus and $srcmlStatus > 128) {
        my $signal = $srcmlStatus - 128;
        Parser_Crash($filename,
            "srcml was killed by signal $signal (shell status $srcmlStatus). "
            . "srcML 1.1.0 dies on a signal on some C/C++ inputs when --position "
            . "is given; --position cannot be dropped because the token format "
            . "depends on it.");
    }
    if (defined $srcmlStatus and $srcmlStatus != 0) {
        Parser_Crash($filename, "srcml exited $srcmlStatus.");
    }
    if (defined $tokenStatus and $tokenStatus > 128) {
        my $signal = $tokenStatus - 128;
        Parser_Crash($filename,
            "srcml2token was killed by signal $signal (shell status $tokenStatus).");
    }
    if (defined $tokenStatus and $tokenStatus != 0) {
        Parser_Crash($filename, "srcml2token exited $tokenStatus.");
    }

    # If bash never wrote the statuses we cannot claim the parse was clean, so the
    # close status is used as a (weaker) backstop rather than ignored.
    if (not defined $srcmlStatus or not defined $tokenStatus) {
        if (not $closed or $closeStatus != 0) {
            Parser_Crash_Unknown($filename, $closeStatus);
        }
    }

    # The emptiness invariant, and the reason a crash can be caught even without
    # PIPESTATUS: a successful srcML tokenization is NEVER empty. Even a zero-byte
    # source file yields the 80-byte begin_unit/end_unit wrapper, for an empty and
    # a whitespace-only file alike. So zero tokens here always means the parse
    # failed, where a general-purpose filter could read it as empty input.
    if ($tokensRead == 0) {
        Parser_Crash($filename,
            "the token stream was empty. A successful srcML parse always emits at "
            . "least the begin_unit/end_unit wrapper, even for an empty file, so an "
            . "empty stream is a failed parse and never a legitimately empty result.");
    }
}

# Reads the two shell statuses bash left behind. Returns (undef, undef) when the
# file is missing or unparseable, so the caller can fall back rather than assume 0.
sub Read_Pipe_Status
{
    my ($statusFile) = @_;
    open(my $fh, '<', $statusFile) or return (undef, undef);
    my $line = <$fh>;
    close $fh;
    return (undef, undef) unless defined $line;
    chomp $line;
    return (undef, undef) unless $line =~ /^([0-9]+)\s+([0-9]+)$/;
    return ($1, $2);
}

sub Parser_Crash
{
    my ($filename, $why) = @_;
    print STDERR "cregit: tokenization of [$filename] FAILED: $why\n";
    print STDERR "cregit: refusing to emit a tokenization for [$filename]; "
        . "exiting $PARSER_CRASH_EXIT so the caller can count this blob.\n";
    exit($PARSER_CRASH_EXIT);
}

sub Parser_Crash_Unknown
{
    my ($filename, $closeStatus) = @_;
    print STDERR "cregit: tokenization of [$filename] FAILED: the srcml pipeline "
        . "reported a non-zero close status ($closeStatus) and bash did not report "
        . "PIPESTATUS, so the failing stage is unknown.\n";
    exit($PARSER_CRASH_EXIT);
}



sub Declarations_Test
{
    foreach my $line (sort {$a <=> $b} keys %declarations) {
        # each element is an array of hashes
        print "Line: $line\n";
        #    my $t = $decls{$line}{name};
        #    my %h = @$t;
        #    foreach my $k2 (sort keys %h) {
        #        print "  $k2 => $h{$k2}\n";
        #    }
        print "\n";
        print "Test decl in line [$line]\n";
        print join(':', Declarations_In_Line($line));
        print "\n";
        next;
        print "Test Get_Declarations [$line]\n";
        my @d = Declarations_In_Line($line);
        foreach my $dec (@d) {
            print "Declarations for [$line][$dec]\n";
            
            my %thisDec = Get_Declaration($line, $dec);
            foreach my $k (sort keys %thisDec) {
                print "   $k => $thisDec{$k}\n";
            }
            print "\n";
        }
        print "End Test Get_Declarations [$line]\n";
        print "\n";
    }
}

sub Declarations_In_Line
{
    my ($line) = @_;;
    my $a = $listDeclarations{$line};
    return () if not defined($a);
    return (@$a);
}

sub Get_Declaration
{
    my ($line, $name) = @_;
    my $d = $declarations{$line}{$name};
    die "Illegal value in get declaration [$line][$name]" unless defined $d;
    my %h = @$d;
    return %h;
}


sub Read_Declarations
{
    my ($filename, $language) = @_;

    my $CTAGS = "$ctags --language-force=$language -x --sort=n --_xformat='%n %N @@@ %K @@@ %S'";

    open(ctags, "$CTAGS '$filename'|") or die "Unable to execute ctags on file [$filename]";

    while (<ctags>) {
        my %decl;
        my $rest;
        my $line;
        chomp;
        $decl{original} = $_;
        die "unable to parse output [$_]" unless /^([0-9]+) (.+) @@@ (.*) @@@ (.*)$/;
        #($decl{name}, $decl{type}, $decl{line}, $rest) = ($1, $2, $3, $4);
        ($decl{line}, $decl{name}, $decl{type}, $decl{sig}) = ($1, $2, $3, $4);
        if ($decl{sig} ne "-") {
            $decl{name} .= " " . $decl{sig}
        }
        $line = $decl{line};
        # skip the filename
        $decl{decl} = substr($rest, length($filename)+1);
        $decl{decl} =~ s/^\s+//;
        # we might have more than one declaration per line
        $declarations{$line}{$decl{name}} = [%decl];
        if (not defined $listDeclarations{$line}) {
            $listDeclarations{$line} = [];
        }
        my $v = $listDeclarations{$line};
        push (@$v, $decl{name});
        # build the return data structure
        # a hash of the four values
    }
    close ctags;
}


sub Usage {

    print STDERR @_;
    die $usage;

}
