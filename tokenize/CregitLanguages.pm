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

# THE single source of truth for "which files can cregit tokenize".
#
# Before this module the same knowledge was written out three times and the
# copies did not agree:
#
#   tokenize/tokenize.pl          %extensions  — the dispatcher's gate
#   tokenize/tokenizeSrcMl.pl     %extensions  — the srcML parser's autodetect
#   tokenizeByBlobId/tokenBySha.pl %mapLang    — the per-blob gate bfg calls
#
# tokenBySha.pl mapped `go`, `md` and `yaml`; tokenize.pl had no parser for any
# of them. So such a blob passed the first gate and died at the second with
# "Unknown parser for extension" — a whole project's run lost to an
# inconsistency between two tables that describe the same capability. They are
# now one table, and t/languages.t holds them to it.
#
# The file mask the pipeline selects blobs with is DERIVED from this table
# (file_mask below), never typed out again: a mask naming an extension the table
# does not know is a run that dies mid-way, and an extension in the table that
# no mask names is source silently left untokenized.

use strict;
use warnings;

# Extension (lowercase, no leading dot) -> language name. The language names are
# the ones %LANG_PARSER_REL keys on and the ones passed as --language.
#
# Verified against the pinned srcML (1.1.0) on 2026-09-19 by tokenizing the same
# C++ fixture under each extension and comparing the token stream to .cpp's:
# .hxx and .tcc are byte-identical to .cpp. `.ixx .inl .cppm .cxxm .ipp` were
# candidates and are NOT here: srcml 1.1.0 does not know those extensions and
# then ignores `-l C++`, emitting an XML declaration with no <unit> at all. It
# exits 0 doing so, so the chain produces an EMPTY token file and reports
# success. That is why t/languages.t probes srcML per extension instead of
# trusting a reference page.
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
    # M4 (autoconf/automake input). Routed, but NOT in the mask: see
    # %MASKED_LANGUAGES for the measurement that keeps it out.
    'ac'   => 'M4',
    'am'   => 'M4',
    # Rust
    'rs'   => 'Rust',
);

# Languages the pipeline's file mask is allowed to select, i.e. the ones whose
# parser is known to produce correct tokens for real-world input.
#
# M4 is deliberately ABSENT, on measurement. m4Tokenizer/m4.py was Python 2 and
# did not run at all under the pinned Python 3 (SyntaxError on `print`), so the
# "the parser exists and is executable" check that cleared M4 for inclusion was
# testing the wrong thing. It has since been ported, and it still must not be in
# the mask, because its lexer is wrong on real autotools input:
#
#   Lexer::__init__ sets end_quote to a BACKTICK, not the apostrophe that m4
#   uses and that this file's own changequote() defaults to. So `` `x' `` runs
#   the string on to the next backtick and swallows whatever is between --
#   measured on a two-line fixture, an AC_MSG_WARN macro call disappeared inside
#   a string token. Setting end_quote to an apostrophe is NOT the fix: shell
#   backticks in Makefile.am then never close, and the sample below goes from
#   36/38 files parsed to 12/38.
#
#   Measured 2026-09-19 over 38 real configure.ac/configure.in/Makefile.am files
#   on this machine: 36 parse, 2 die with "generator raised StopIteration" (an
#   unterminated string at EOF; under Python 2 the same condition silently
#   truncated the token stream and exited 0).
#
# Selecting .am/.ac would therefore fail whole projects at step 2, for 1.4 MB of
# source in 22 projects. Fixing the m4 lexer is its own task with its own
# fixtures; when it is done, add 'M4' here and the mask widens by itself.
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

# The parser every srcML-routed language shares. Named so callers can ask "is
# this extension srcML's?" without string-matching a path.
our $SRCML_PARSER_REL = 'tokenizeSrcMl.pl';

# Extensions keyed WITH the leading dot, as tokenize.pl and tokenizeSrcMl.pl
# look them up (both lowercase the extension first).
sub extensions_by_dot {
    return map { ( ".$_" => $EXT_LANG{$_} ) } keys %EXT_LANG;
}

# The same, restricted to the languages srcML parses. tokenizeSrcMl.pl must not
# autodetect a .rs file as Rust: it would hand `-l Rust` to srcml.
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

# Extensions in a stable order, so the derived mask is byte-stable: it is stored
# in blobExec's meta table and compared string-for-string on every resume.
sub extensions_sorted {
    return sort keys %EXT_LANG;
}

# The extensions the mask may name: every extension of every masked language.
sub masked_extensions_sorted {
    return grep { $MASKED_LANGUAGES{ $EXT_LANG{$_} } } extensions_sorted();
}

# The universal file mask: every extension of every masked language, and nothing
# else.
#
# Case-insensitive, because `.C` and `.H` are real files and the per-language
# masks this replaces missed them. Anchored at the end only: every consumer
# matches it against a repository-relative path, not just a basename, and all
# four regex engines it reaches (Scala/Java in blobExec, Perl in blameRepoFiles
# and prettyPrintFiles, and whatever later reads the recorded string) search
# rather than full-match.
sub file_mask {
    return '(?i)\.(' . join('|', map { quotemeta } masked_extensions_sorted()) . ')$';
}

1;
