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

# Single source of truth for which files cregit can tokenize. tokenize.pl,
# tokenizeSrcMl.pl, tokenizeByBlobId/tokenBySha.pl and the pipeline's default
# mask all derive from it; t/languages.t holds them to it.

use strict;
use warnings;

# Extension (lowercase, no leading dot) -> language name, as passed to --language.
# srcML 1.1.0 ignores -l for an extension it does not know (.ixx .inl .cppm .cxxm
# .ipp) and emits no <unit> while exiting 0, so t/languages.t probes each entry.
our %EXT_LANG = (
    # C
    'c'    => 'C',
    'h'    => 'C',
    # C++
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
    # Java
    'java' => 'Java',
    # Routed, not masked; see %MASKED_LANGUAGES.
    'ac'   => 'M4',
    'am'   => 'M4',
    # Rust
    'rs'   => 'Rust',
);

# Languages the default mask may select. M4 is out: m4.py's lexer uses a backtick
# end_quote and raises StopIteration on an unterminated string, so real autotools
# input fails. Add 'M4' when that is fixed and the mask widens by itself.
our %MASKED_LANGUAGES = (
    'C'    => 1,
    'C++'  => 1,
    'Java' => 1,
    'Rust' => 1,
);

# Language -> parser, relative to the tokenize/ directory. Every value must
# exist and be executable in a built checkout; t/languages.t asserts it.
our %LANG_PARSER_REL = (
    'C'    => 'tokenizeSrcMl.pl',
    'C++'  => 'tokenizeSrcMl.pl',
    'Java' => 'tokenizeSrcMl.pl',
    'M4'   => 'm4Tokenizer/m4.py',
    'Rust' => 'rustTokenizer/target/release/rust_tokenizer',
);

# The parser the srcML-routed languages share.
our $SRCML_PARSER_REL = 'tokenizeSrcMl.pl';

# Extensions whose CASE routes them differently. By the GNU convention an
# uppercase .C or .H is C++, while the lowercase forms are C; every other
# extension is case-insensitive.
our %CASE_SENSITIVE_EXT = (
    'C' => 'C++',
    'H' => 'C++',
);

# The language for one extension, given without its dot and with its case intact.
# Returns undef when nothing routes it.
sub language_for_ext {
    my ($ext) = @_;
    return undef unless defined $ext && $ext ne '';
    return $CASE_SENSITIVE_EXT{$ext} if exists $CASE_SENSITIVE_EXT{$ext};
    return $EXT_LANG{ lc($ext) };
}

# Keyed WITH the leading dot, as both callers look them up after lc().
sub extensions_by_dot {
    return map { ( ".$_" => $EXT_LANG{$_} ) } keys %EXT_LANG;
}

# srcML-routed extensions only: srcml must never be handed -l Rust.
sub srcml_extensions_by_dot {
    return map { ( ".$_" => $EXT_LANG{$_} ) }
           grep { $LANG_PARSER_REL{ $EXT_LANG{$_} } eq $SRCML_PARSER_REL }
           keys %EXT_LANG;
}

# Language -> absolute parser path, given the tokenize/ directory.
sub parsers {
    my ($basedir) = @_;
    die "parsers() needs the tokenize/ directory" unless defined $basedir and $basedir ne "";
    return map { ( $_ => "$basedir/$LANG_PARSER_REL{$_}" ) } keys %LANG_PARSER_REL;
}

# Sorted, so the mask is byte-stable: blobExec stores it and compares it on resume.
sub extensions_sorted {
    return sort keys %EXT_LANG;
}

# The extensions the mask may name: every extension of every masked language.
sub masked_extensions_sorted {
    return grep { $MASKED_LANGUAGES{ $EXT_LANG{$_} } } extensions_sorted();
}

# Case-insensitive (.C and .H are real). End-anchored only: every consumer
# searches a repository-relative path rather than full-matching a basename.
sub file_mask {
    return '(?i)\.(' . join('|', map { quotemeta } masked_extensions_sorted()) . ')$';
}

1;
