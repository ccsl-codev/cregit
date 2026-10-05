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

# Prints blobExec's --tokenizer-identity, ext=<sha256>,...: a digest of each
# extension's parser, tokenize.pl, CregitLanguages.pm and, for srcML languages,
# srcml2token, libsrcml, ctags and CregitSrcMl.pm. A change refuses old caches.

use strict;
use warnings;

use Digest::SHA qw(sha256_hex);
use File::Basename;
use FindBin;
use lib $FindBin::Bin;
use CregitLanguages;
use Getopt::Long;

my $usage = "
Usage: $0 [options]

Prints blobExec's --tokenizer-identity value for this checkout:
  ext=<sha256>,ext=<sha256>,...

Options:
   --srcml2token=<path>   the built srcML tokenizer (required for C/C++/Java)
   --ctags=<path>         the ctags binary       (required for C/C++/Java)
   --all-extensions       cover every extension in the table, not only the
                          masked ones (.am/.ac are routed but not masked)
";

my $srcml2tokenPath = "";
my $ctagsPath       = "";
my $allExtensions   = 0;

GetOptions(
    "srcml2token=s"  => \$srcml2tokenPath,
    "ctags=s"        => \$ctagsPath,
    "all-extensions" => \$allExtensions,
) or die($usage);

my $basedir = $FindBin::Bin;

my @shared = ("$basedir/tokenize.pl", "$basedir/CregitLanguages.pm");

my %parsers = CregitLanguages::parsers($basedir);

my @extensions = $allExtensions
    ? CregitLanguages::extensions_sorted()
    : CregitLanguages::masked_extensions_sorted();

# A missing component is fatal: an identity without it would compare equal
# across a change in it.
sub digest_of {
    my ($path) = @_;
    open(my $fh, '<', $path)
        or die "tokenizerIdentity: cannot read [$path]: $!\n"
             . "  It is part of a tokenizer's identity, and an identity computed without it\n"
             . "  would compare equal across a change in it. Build the checkout first\n"
             . "  (./run_pipeline_process.sh --ensure-artifacts) and try again.\n";
    binmode $fh;
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    close $fh;
    return $sha->hexdigest;
}

sub libsrcml_of {
    my ($srcml2token) = @_;
    open(my $ph, '-|', $srcml2token, "--libsrcml-path")
        or die "tokenizerIdentity: cannot run [$srcml2token]: $!\n";
    my $path = <$ph> // "";
    close $ph;
    chomp $path;
    die "tokenizerIdentity: [$srcml2token] does not answer --libsrcml-path, so it is\n"
      . "  not the srcml2token that parses with libsrcml. Rebuild it: make -C tokenize/srcMLtoken\n"
        if $? != 0 or $path eq "";
    return $path;
}

my %digestCache;
sub cached_digest {
    my ($path) = @_;
    $digestCache{$path} //= digest_of($path);
    return $digestCache{$path};
}

my @pairs;
my $libsrcml;
for my $ext (@extensions) {
    my $language = $CregitLanguages::EXT_LANG{$ext};
    die "tokenizerIdentity: no language for extension [$ext]\n" unless defined $language;
    my $parser = $parsers{$language};
    die "tokenizerIdentity: no parser for language [$language]\n" unless defined $parser;

    my @components = (@shared, $parser);

    if ($CregitLanguages::LANG_PARSER_REL{$language} eq $CregitLanguages::SRCML_PARSER_REL) {
        for my $pair (["--srcml2token", $srcml2tokenPath],
                      ["--ctags",       $ctagsPath]) {
            my ($flag, $path) = @$pair;
            die "tokenizerIdentity: $flag is required, because extension [$ext] is parsed by\n"
              . "  $CregitLanguages::SRCML_PARSER_REL and its tokens depend on that binary.\n$usage"
                if $path eq "";
            push @components, $path;
        }
        $libsrcml //= libsrcml_of($srcml2tokenPath);
        push @components, $libsrcml, "$basedir/CregitSrcMl.pm";
    }

    my $combined = sha256_hex(join("\n", map { cached_digest($_) } @components));
    push @pairs, "$ext=$combined";
}

die "tokenizerIdentity: no extensions to report\n" unless @pairs;

print join(",", @pairs), "\n";
