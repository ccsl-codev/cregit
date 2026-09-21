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

# Prints blobExec's --tokenizer-identity argument: one opaque value per file
# extension, changing whenever the thing that turns that extension's bytes into
# tokens changes.
#
#   ext=<hex>,ext=<hex>,...
#
# Why this exists. blobExec decided whether to reuse a cached tokenization on
# `command` and `mask`. `command` is the fixed path
# tokenizeByBlobId/tokenBySha.pl and `mask` says WHICH files to tokenize, never
# HOW. So when the Rust tokenizer's output format was corrected in 729643e,
# neither value moved: every cached .rs row stayed a cache hit and a run with
# the corrected binary reproduced the broken tokens, in 45 projects, with no
# error anywhere. An identity that tracks the TOOLCHAIN is the missing key.
#
# What goes into an extension's identity, and why each part:
#
#   * its language's parser (CregitLanguages::LANG_PARSER_REL) — the tokenizer
#     itself. For Rust that is the compiled cargo artifact, which is exactly the
#     file that went stale.
#   * srcml2token, srcml and ctags, for the srcML-routed languages only. The
#     token stream is the product of all three: srcml2token transcodes srcML's
#     XML, and tokenizeSrcMl.pl merges ctags output into it. A change in any of
#     them changes the tokens.
#   * tokenize.pl and CregitLanguages.pm, for every extension. tokenize.pl is
#     the dispatcher that decides which parser runs and with which flags
#     (--position among them, which the token format depends on), and
#     CregitLanguages.pm decides which language an extension is. Either one can
#     change an extension's tokens without the parser changing at all.
#
# Including the shared files makes the identity CONSERVATIVE: editing
# tokenize.pl moves every extension's identity, so every project with cached
# rows refuses until an operator decides what to do. That is the right direction
# to be blunt in. A refusal costs a conversation; a false "unchanged" costs a
# republished corpus.
#
# The output is sorted and every value is a sha256 hex digest of a digest list,
# so the string is byte-stable for a given checkout: it is stored in the blob
# map's meta table and compared string-for-string on every resume.

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
   --srcml2token=<path>   the built C++ transcoder (required for C/C++/Java)
   --srcml=<path>         the srcml binary       (required for C/C++/Java)
   --ctags=<path>         the ctags binary       (required for C/C++/Java)
   --all-extensions       cover every extension in the table, not only the
                          masked ones (.am/.ac are routed but not masked)
";

my $srcml2tokenPath = "";
my $srcmlPath       = "";
my $ctagsPath       = "";
my $allExtensions   = 0;

GetOptions(
    "srcml2token=s"  => \$srcml2tokenPath,
    "srcml=s"        => \$srcmlPath,
    "ctags=s"        => \$ctagsPath,
    "all-extensions" => \$allExtensions,
) or die($usage);

my $basedir = $FindBin::Bin;

# Every extension's identity includes these, because either one can change what
# an extension's tokens are without any parser changing.
my @shared = ("$basedir/tokenize.pl", "$basedir/CregitLanguages.pm");

my %parsers = CregitLanguages::parsers($basedir);

my @extensions = $allExtensions
    ? CregitLanguages::extensions_sorted()
    : CregitLanguages::masked_extensions_sorted();

# A file that is named but absent is fatal. Emitting an identity that silently
# omitted a component would be worse than useless: it would compare EQUAL across
# a change in that component, which is the exact failure this program exists to
# make impossible. The Rust parser is a build artifact, so this is also where an
# unbuilt checkout is caught.
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

my %digestCache;
sub cached_digest {
    my ($path) = @_;
    $digestCache{$path} //= digest_of($path);
    return $digestCache{$path};
}

my @pairs;
for my $ext (@extensions) {
    my $language = $CregitLanguages::EXT_LANG{$ext};
    die "tokenizerIdentity: no language for extension [$ext]\n" unless defined $language;
    my $parser = $parsers{$language};
    die "tokenizerIdentity: no parser for language [$language]\n" unless defined $parser;

    my @components = (@shared, $parser);

    # The srcML chain is three binaries deep and all three shape the tokens.
    # Required rather than optional: an identity for .c computed without
    # srcml2token would not move when srcml2token does.
    if ($CregitLanguages::LANG_PARSER_REL{$language} eq $CregitLanguages::SRCML_PARSER_REL) {
        for my $pair (["--srcml2token", $srcml2tokenPath],
                      ["--srcml",       $srcmlPath],
                      ["--ctags",       $ctagsPath]) {
            my ($flag, $path) = @$pair;
            die "tokenizerIdentity: $flag is required, because extension [$ext] is parsed by\n"
              . "  $CregitLanguages::SRCML_PARSER_REL and its tokens depend on that binary.\n$usage"
                if $path eq "";
            push @components, $path;
        }
    }

    # Digest of the component digests, not of the concatenated bytes: the list is
    # what identifies the toolchain, and a per-file digest makes a failure
    # readable with --verbose-style debugging if it is ever needed.
    my $combined = sha256_hex(join("\n", map { cached_digest($_) } @components));
    push @pairs, "$ext=$combined";
}

die "tokenizerIdentity: no extensions to report\n" unless @pairs;

print join(",", @pairs), "\n";
