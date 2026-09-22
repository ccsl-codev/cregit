package CregitLanguages;

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

# The only extension table in cregit. tokenize.pl, tokenizeSrcMl.pl,
# tokenizeByBlobId/tokenBySha.pl and fileMask.pl all derive from it.

use strict;
use warnings;

# Lowercase extension, no leading dot -> language, spelled as --language takes it.
our %EXT_LANG = (
    'c'    => 'C',
    'h'    => 'C',
    'c++'  => 'C++',
    'cc'   => 'C++',
    'cp'   => 'C++',
    'cpp'  => 'C++',
    'cxx'  => 'C++',
    'h++'  => 'C++',
    'hh'   => 'C++',
    'hpp'  => 'C++',
    'hxx'  => 'C++',
    'tcc'  => 'C++',
    'java' => 'Java',
    'ac'   => 'M4',
    'am'   => 'M4',
    'rs'   => 'Rust',
);

# M4 is missing on purpose: m4.py raises StopIteration on real autotools quoting.
our %MASKED_LANGUAGES = map { $_ => 1 } qw(C C++ Java Rust);

our %LANG_PARSER_REL = (
    'C'    => 'tokenizeSrcMl.pl',
    'C++'  => 'tokenizeSrcMl.pl',
    'Java' => 'tokenizeSrcMl.pl',
    'M4'   => 'm4Tokenizer/m4.py',
    'Rust' => 'rustTokenizer/target/release/rust_tokenizer',
);

our $SRCML_PARSER_REL = 'tokenizeSrcMl.pl';

# An uppercase .C or .H is C++ by the GNU convention. Every other extension is
# case-insensitive.
our %CASE_SENSITIVE_EXT = ( 'C' => 'C++', 'H' => 'C++' );

# $ext arrives without its dot and with its case intact.
sub language_for_ext {
    my ($ext) = @_;
    return undef unless defined $ext && $ext ne '';
    return $CASE_SENSITIVE_EXT{$ext} if exists $CASE_SENSITIVE_EXT{$ext};
    return $EXT_LANG{ lc($ext) };
}

sub srcml_extensions_by_dot {
    return map  { ( ".$_" => $EXT_LANG{$_} ) }
           grep { $LANG_PARSER_REL{ $EXT_LANG{$_} } eq $SRCML_PARSER_REL }
           keys %EXT_LANG;
}

sub parsers {
    my ($basedir) = @_;
    die "parsers() needs the tokenize/ directory" unless defined $basedir and $basedir ne '';
    return map { ( $_ => "$basedir/$LANG_PARSER_REL{$_}" ) } keys %LANG_PARSER_REL;
}

sub extensions_sorted { return sort keys %EXT_LANG }

sub masked_extensions_sorted {
    return grep { $MASKED_LANGUAGES{ $EXT_LANG{$_} } } extensions_sorted();
}

# Sorted, because blobExec stores this mask and compares it on resume. Anchored
# at the end only: every consumer searches a path, not a bare basename.
sub file_mask {
    return '(?i)\.(' . join('|', map { quotemeta } masked_extensions_sorted()) . ')$';
}

1;
