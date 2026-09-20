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


# this program is run by bfg. It does not take any parameters. Instead, it simply reads
# its parameters from the environment

# Env. variables

# BFG_BLOB: the id of the blob (a SHA)
# BFG_FILENAME: basename of the file to process. it has an extension
# BFG_MEMO_DIR: directory of memoized files
# BFG_TOKENIZE_CMD: command to tokenize, might include parameters

# this program must not have any parameters


# it should  get all its parameters from the environment

 
use Digest::SHA qw(sha1_hex);
use File::Temp qw/ tempfile tempdir mkstemp/;
use strict;
use File::Path qw(make_path);
use File::Copy;
use FindBin qw($RealBin);
use lib "$FindBin::RealBin/../tokenize";
use CregitLanguages;

# build/ tempdir anchored to the script's dir, not the caller's CWD.
my $buildDir = "$RealBin/build";
make_path($buildDir) if not -d $buildDir;



if (not defined($ENV{BFG_MEMO_DIR}) ||  $ENV{BFG_MEMO_DIR} eq "") {
    die "You must define the environment variable BFG_MEMO_DIR equal to the directory where to memoize"
}

my $shaDir = $ENV{BFG_MEMO_DIR};

if ($shaDir eq "") {
    die "Directory to use to memoize not set. Use BFG_MEMO_DIR environment variable to set"
}

my $tokenizeCmd = $ENV{BFG_TOKENIZE_CMD};

if ($tokenizeCmd eq "") {
    die ("Tokenize command not defined. Use environment variable BFG_TOKENIZE_CMD");
}


my $contents;

die "Sha dir [$shaDir] does not exist" if not -d $shaDir;

my $contents = join( "", <> );

my $sha1 = sha1_hex($contents);

#`printf "--------------\n"`;
#`printf "$sha1\n" >> /speed/tmp/output.txt`;

#`printenv | grep BFG  >> /speed/tmp/output.txt`;

my $dir = $shaDir . '/' . substr($sha1, 0,2) . '/' . substr($sha1, 2,2);
my $filename = $shaDir . '/' . substr($sha1, 0,2) . '/' . substr($sha1, 2,2) . '/' . $sha1;

my $blob = $ENV{BFG_BLOB};
my $blobFN = $ENV{BFG_FILENAME};

die "BFG_FILENAME environment variable not set " if $blobFN eq "";

my $fileExt;
my $blobLang;

if ($blobFN =~ /\.([^.]+)$/) {
    $fileExt = lc($1);
    $blobLang = CregitLanguages::language_for_ext($1);
}

if (not defined($blobLang)) {
    die "unknown file extension [$fileExt]";
}

if (-f $filename) {
    open(IN, $filename) || die "unable to open memoized file [$filename]";
    my $contents = join( "", <IN> );
    print $contents;
    close(IN);
    
} else {

  # srcml 1.1.0 requires a file extension to parse source code correctly,
  # even when --language is specified. Use SUFFIX so the temp file gets
  # the original file's extension (e.g. .c or .h).
  # Fixed input name in a per-run unique dir: ctags derives __anon ids from the
  # filename, so a stable name gives deterministic output; the dir isolates workers.
  my $wd = tempdir( "$buildDir/tokdir-XXXXX", CLEANUP => 1 );
  my $inName = "input.$fileExt";
  my $file = "$wd/$inName";
  open(my $fh, '>', $file) or die "unable to write temp input [$file]: $!";
  print $fh $contents;
  close $fh;

  my ($fout, $outfile) = mkstemp( "$buildDir/tmpfile-out-XXXXX" );

  my $langOp = "--language=" . $blobLang;

  open(PROC, "cd \"$wd\" && $tokenizeCmd $langOp \"$inName\" |") or die "unable to execute $tokenizeCmd (verify variable BFG_TOKENIZE_CMD) [$tokenizeCmd]";

  while (<PROC>) {
      print $_;
      print $fout $_;
  }
  my $tokenizeSucceeded = close PROC;
  my $tokenizeStatus = $?;
  close $fout or die "unable to close temporary tokenizer output [$outfile]: $!";

  if (not $tokenizeSucceeded) {
      unlink($outfile);
      unlink($file);
      if ($tokenizeStatus == -1) {
          die "tokenize command could not be executed [$tokenizeCmd]: $!";
      }
      my $signal = $tokenizeStatus & 127;
      if ($signal) {
          die "tokenize command was killed by signal $signal [$tokenizeCmd]";
      }
      # Propagate rather than die: die exits 255 and would erase the distinction
      # between an ordinary tokenizer error and the specific "srcML died, this
      # tokenization is unusable" status that blobExec counts on its own.
      # Nothing is memoized on this path -- $outfile is unlinked above -- so the
      # 0-byte tokenization that used to be written and cached is simply absent.
      my $exitCode = $tokenizeStatus >> 8;
      print STDERR "tokenize command failed with exit code [$exitCode] "
          . "[$tokenizeCmd]; propagating that status and memoizing nothing\n";
      exit($exitCode);
  }

  if (not -d $dir) {
      make_path($dir);
  }

  move( $outfile, $filename) or die "The move operation to memoized directory failed: $!";

}
