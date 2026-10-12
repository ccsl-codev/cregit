# shTokenizer

`sh_tokenizer.py` tokenizes POSIX sh / bash scripts into the line-oriented view used
by cregit, the format consumed by `blameRepo` and `generate_dataset`.

It uses [tree-sitter-bash](https://github.com/tree-sitter/tree-sitter-bash) for the
syntax, plus its own heredoc locator: tree-sitter-bash misreads heredoc bodies in common
forms, such as a `&&` after the delimiter (`cat >expect <<-\EOF &&`), and reads their
text as code.

## Requirements

Python 3 with the `tree-sitter` and `tree-sitter-bash` packages (provided by
`devenv shell`).

## How to use

```sh
sh_tokenizer.py [--language=Sh] [--position] [--verbose] <file> [<output>]
sh_tokenizer.py --library-paths
```

The token stream goes to `<output>`, or to _stdout_ without it. `--language` and
`--verbose` are accepted so the `tokenize.pl` dispatcher can call it like the other
tokenizers. `--position` prefixes every line with `LINE:COL|` (tokens) or `-:-|`
(markers).

`--library-paths` prints the files of the compiled tree-sitter runtime and bash
grammar. They decide the tokens as much as `sh_tokenizer.py` does, so
`tokenize/tokenizerIdentity.pl` digests them in the tokenizer identity of `.sh`.

Exit status: 0 whenever the file can be read, including files with syntax errors;
1 if the file cannot be read; 2 on a usage error.

## Output format

One line per token or marker:

```
begin_unit|revision:0.1.0;language:Sh;cregit-version:0.0.1
kind|value
...
end_unit

```

Every `end_*` marker, `end_unit` included, is followed by an empty line (the line
`-:-|` with `--position`), exactly as `tokenizeSrcMl.pl` does for C, C++ and Java.
`generate_dataset` classifies these lines as structural blanks.

If a file cannot be tokenized (an internal error, or an output that fails the checks of
this format), a warning goes to _stderr_ and the output is the plain split of the file
into words, all of kind `unknown`, so the pipeline never stops on a file.

### Token kinds

| kind | what |
|---|---|
| `keyword` | alphabetic reserved words: `if then else elif fi case esac for select while until do done in function` |
| `command` | the command name of a simple command (`git`, `:`, `.`, `return`, `time`), and the builtins `export`, `local`, `readonly`, `declare`, `typeset`, `unset` and `unsetenv` |
| `word` | any other unquoted word: arguments, options, paths, patterns, test operators and the numbers they compare (`-z`, `-eq 1`), the name in a function definition |
| `variable` | variable names in expansions, assignments and declaration commands, special parameters (`1`, `@`, `#`, `?`) |
| `number` | numbers in arithmetic (`$(( ))`, `$[ ]`, `(( ))`, the `(( ))` of `for`), file descriptors in redirections (`2>`, `6<<`, `>&2`) |
| `op` | operators and punctuation: `&& \|\| \| ; ;; & ( ) { } ! = < > >> <& >& << <<- $ ${ $( ) $(( ))`, backquotes, the brackets `[ ] [[ ]]` of a test command, arithmetic operators, the `\` of a line continuation |
| `literal` | a whole string: `'...'`, `"..."` or `$'...'` |
| `heredoc` | a whole heredoc body |
| `heredoc_delim` | the delimiter word after `<<` / `<<-` and the terminator line |
| `comment` | a whole comment, `#` to end of line |

### Values

- The value is the exact source text of the token, never the value the shell would
  compute: quotes, backslashes and escapes are kept.
- CR and LF inside a value are replaced by a space, so every token fits on one line.
- Values are never empty.
- Values are raw bytes: files that are not valid UTF-8 are copied through unchanged.
  Tokens are only split at ASCII characters, never inside a multi-byte sequence.
- Concatenating all values after removing whitespace (space, tab, CR, LF) gives the
  source file after removing whitespace. No non-whitespace byte is dropped or added.

### Strings

A string is one `literal` token holding its whole source text, quotes and expansions
included, as the other tokenizers emit a string literal. In a concatenation such as
`'a'"b"c`, each quoted piece is a literal of its own and `c` is a `word`. A heredoc
inside a string (in `"$(cat <<EOF ...)"`) is emitted by the heredoc rules and cuts the
string into several literals.

### Heredocs

The body of a heredoc is one `heredoc` token, expansions included. The operator is an
`op`; the delimiter and the terminator are `heredoc_delim`.

Heredoc extents are decided by the tokenizer, not by tree-sitter, following the shell:
operators `<<` and `<<-` in code, `$( )` and backquotes included, but not inside quotes,
comments or arithmetic (`$(( ))`, `(( ))`); `<<<` is not a heredoc. A body starts after
the end of the line holding its operator, or after the previous body started from that
line; it ends before the first line equal to the unquoted delimiter, leading tabs
removed for `<<-`; a body without a terminator runs to the end of the file.

### Structural markers

Markers carry no source text. `generate_dataset` classifies them as structural
(`is_structural = 1`).

| markers | around |
|---|---|
| `begin_unit|...` / `end_unit` | the whole file |
| `begin_function`, `DECL|function|<name>` / `end_function` | a function definition |

### Positions

With `--position`, `LINE:COL` is the 1-based position of the first byte of the token;
`COL` counts the characters of the line decoded as UTF-8 with invalid bytes replaced, as
`generate_dataset` reads the source: a truncated multi-byte sequence counts as one
character, and so does each other invalid byte.

## Limitations

- A string or heredoc body that spans several lines is one line of the tokenized file,
  so its blame is coarser than the line blame of git. Code passed as a string is a
  string too: in the git test suite, the whole body of `test_expect_success 'title' '...'`
  is one `literal`.
- The heredoc locator reads `((` at the start of a word as arithmetic; bash reads it as
  two nested subshells when it is not valid arithmetic (`((cd x) )`).
- Inside backquotes, a backquote within quotes opens a nested command substitution;
  bash ends the backquotes there.
- tree-sitter-bash cuts an unquoted word at a form feed or a vertical tab, which the shell
  reads as part of the word; that byte is then a `word` token of its own.
- When tree-sitter-bash pairs the quotes of a line wrongly (as in the `case` pattern
  `?*' '?*)`) and reaches a quote it cannot close, everything from that quote to the end
  of the file is one error token, which comes out as a single `word`.

## How to test

```sh
cd tokenize/shTokenizer
make test
```

`make expected` regenerates the expected outputs in `tests/expected/` from
`tests/fixtures/`: `NAME.sh.token` with `--position` and `NAME.sh.nopos.token` without.
Check the diff of every regenerated file.

## License

GPL-3.0+
