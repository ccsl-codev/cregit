#!/usr/bin/env python3
"""POSIX sh / bash tokenizer for cregit. See README.md for the output format."""
import argparse
import bisect
import re
import sys
from dataclasses import dataclass

import tree_sitter._binding
import tree_sitter_bash
import tree_sitter_bash._binding
from tree_sitter import Language, Parser

REVISION = "0.1.0"
CREGIT_VERSION = "0.0.1"
UNIT_HEADER = f"begin_unit|revision:{REVISION};language:Sh;cregit-version:{CREGIT_VERSION}".encode()

# tree-sitter-bash parses these as keywords; for the shell they are commands.
KEYWORD_COMMANDS = {b"export", b"local", b"readonly", b"declare", b"typeset", b"unset", b"unsetenv"}

WHITESPACE = b" \t\r\n"
WORD = re.compile(rb"[^ \t\r\n]+")
WORD_END = b" \t\n;&|()<>"  # as for the shell, CR does not end a word

PARSER = Parser(Language(tree_sitter_bash.language()))


@dataclass(slots=True)
class Token:
    offset: int
    kind: bytes
    value: bytes


@dataclass(slots=True)
class Marker:
    text: bytes


BLANK = Marker(b"")

END_RANK, BEGIN_RANK, TOKEN_RANK = 0, 1, 2


class Output:
    """Tokens and markers, sorted by offset at the end, because the heredocs are emitted
    after the tree walk. At one offset: end markers (innermost first), begin markers
    (outermost first), then the token."""

    def __init__(self):
        self.items = []

    def add(self, offset: int, rank: int, tie: int, records: list) -> None:
        self.items.append(((offset, rank, tie, len(self.items)), records))

    def token(self, offset: int, kind: bytes, value: bytes) -> None:
        self.add(offset, TOKEN_RANK, 0, [Token(offset, kind, value)])

    def begin(self, offset: int, depth: int, name: bytes, decl: bytes) -> None:
        self.add(offset, BEGIN_RANK, depth, [Marker(b"begin_" + name), Marker(decl)])

    def end(self, offset: int, depth: int, name: bytes) -> None:
        self.add(offset, END_RANK, -depth, [Marker(b"end_" + name), BLANK])

    def records(self):
        for _key, records in sorted(self.items, key=lambda item: item[0]):
            yield from records


BACKSLASH, SINGLE_QUOTE, DOUBLE_QUOTE, BACKQUOTE = b"\\'\"`"
DOLLAR, HASH, LESS, NEWLINE, SPACE = b"$#<\n "
OPEN_PAREN, CLOSE_PAREN, OPEN_BRACE, CLOSE_BRACE = b"(){}"
DIGITS = b"0123456789"
CLOSER = {OPEN_PAREN: CLOSE_PAREN, OPEN_BRACE: CLOSE_BRACE}
QUOTE_CONTEXTS = {SINGLE_QUOTE: "single", DOUBLE_QUOTE: "double", BACKQUOTE: "backquote"}


@dataclass
class Heredoc:
    io_start: int          # start of the fd digits, or of the operator
    op_start: int
    op_end: int
    word_start: int
    word_end: int
    strip_tabs: bool
    delimiter: bytes       # unquoted
    body_start: int = 0
    body_end: int = 0
    term_start: int | None = None  # None when the terminator is missing
    term_end: int | None = None
    line_end: int = 0      # end of the terminator line, or of the body

    def spans(self) -> tuple[tuple[int, int], tuple[int, int]]:
        return (self.io_start, self.word_end), (self.body_start, self.line_end)


@dataclass
class Context:
    kind: str
    depth: int = 0              # brackets still open
    opener: int | None = None   # ( or { of $( ) or ${ }
    expansion: bool = False     # $( ), ${ } or $(( ))


def scan_heredocs(text: bytes) -> list[Heredoc]:
    """Locate heredocs by the rules of the shell: tree-sitter-bash reads many of them as
    code (README.md, Heredocs)."""
    return HeredocScanner(text).scan()


class HeredocScanner:
    def __init__(self, text: bytes):
        self.text = text
        self.stack = [Context("code")]
        self.heredocs: list[Heredoc] = []
        self.pending: list[Heredoc] = []  # operators of the current line
        self.expansion_end = -1

    def scan(self) -> list[Heredoc]:
        i = 0
        while i < len(self.text):
            context = self.stack[-1]
            if context.kind == "single":
                i = self.single_quoted(i)
            elif context.kind == "double":
                i = self.double_quoted(i)
            elif context.kind == "arith":
                i = self.arithmetic(i, context)
            else:
                i = self.code(i, context)
        for heredoc in self.pending:  # operators on the last line, without a newline: empty bodies
            heredoc.body_start = heredoc.body_end = heredoc.line_end = len(self.text)
        return self.heredocs + self.pending

    def single_quoted(self, i: int) -> int:
        close = self.text.find(b"'", i)
        if close < 0:
            return len(self.text)
        self.stack.pop()
        return close + 1

    def double_quoted(self, i: int) -> int:
        byte = self.text[i]
        if byte == BACKSLASH:
            return i + 2
        if byte == DOUBLE_QUOTE:
            self.stack.pop()
            return i + 1
        if byte == DOLLAR:
            after = self.open_expansion(i)
            if after is not None:
                return after
        if byte == BACKQUOTE:
            self.stack.append(Context("backquote"))
        return i + 1

    def arithmetic(self, i: int, context: Context) -> int:
        byte = self.text[i]
        if byte == OPEN_PAREN:
            context.depth += 1
        elif byte == CLOSE_PAREN:
            context.depth -= 1
            if context.depth == 0:
                self.close(context, i + 1)
        return i + 1

    def close(self, context: Context, after: int) -> None:
        self.stack.pop()
        if context.expansion:
            self.expansion_end = after

    def at_word_start(self, i: int) -> bool:
        """Right after an expansion the word goes on: bash reads $(echo a)#b as a#b."""
        return i == 0 or (self.text[i - 1] in WORD_END and i != self.expansion_end)

    def code(self, i: int, context: Context) -> int:
        text = self.text
        byte = text[i]
        if byte == BACKSLASH:
            return i + 2
        if byte == BACKQUOTE and context.kind == "backquote":
            self.stack.pop()
            return i + 1
        if byte in QUOTE_CONTEXTS:
            self.stack.append(Context(QUOTE_CONTEXTS[byte]))
            return i + 1
        if byte == DOLLAR:
            after = self.open_expansion(i)
            if after is not None:
                return after
        if byte == OPEN_PAREN and text[i + 1:i + 2] == b"(" and self.at_word_start(i):
            self.stack.append(Context("arith", depth=2))  # (( )) or for (( ))
            return i + 2
        if context.opener is not None:
            if byte == context.opener:
                context.depth += 1
                return i + 1
            if byte == CLOSER[context.opener]:
                context.depth -= 1
                if context.depth == 0:
                    self.close(context, i + 1)
                return i + 1
        if byte == HASH and context.opener != OPEN_BRACE and self.at_word_start(i):
            ends = [text.find(b"\n", i)]
            if context.kind == "backquote":  # the closing backquote also ends the comment
                ends.append(text.find(b"`", i))
            ends = [end for end in ends if end >= 0]
            return min(ends) if ends else len(text)
        if byte == LESS and text[i + 1:i + 2] == b"<":
            return self.operator(i)
        if byte == NEWLINE:
            i += 1
            if self.pending:
                i = read_bodies(text, i, self.pending)
                self.heredocs.extend(self.pending)
                self.pending = []
            return i
        return i + 1

    def open_expansion(self, i: int) -> int | None:
        text = self.text
        if text[i + 1:i + 3] == b"((":
            self.stack.append(Context("arith", depth=2, expansion=True))
            return i + 3
        if text[i + 1:i + 2] in (b"(", b"{"):
            self.stack.append(Context("code", depth=1, opener=text[i + 1], expansion=True))
            return i + 2
        return None

    def operator(self, i: int) -> int:
        text = self.text
        if text[i + 2:i + 3] == b"<":  # here-string
            return i + 3
        heredoc = heredoc_at(text, i)
        if heredoc is None:  # no delimiter word
            return i + 2 + (text[i + 2:i + 3] == b"-")
        self.pending.append(heredoc)
        return heredoc.word_end


def heredoc_at(text: bytes, i: int) -> Heredoc | None:
    strip_tabs = text[i + 2:i + 3] == b"-"
    op_end = i + 2 + strip_tabs
    word_start = op_end
    while word_start < len(text) and text[word_start] in b" \t":
        word_start += 1
    word_end = skip_quoted_word(text, word_start)
    if word_end == word_start:
        return None
    io_start = i
    while io_start > 0 and text[io_start - 1] in DIGITS:
        io_start -= 1
    if io_start < i and io_start > 0 and text[io_start - 1] not in WORD_END:
        io_start = i  # digits belong to a longer word, not an fd
    word = text[word_start:word_end].replace(b"\\\n", b"")  # line continuations
    return Heredoc(io_start=io_start, op_start=i, op_end=op_end, word_start=word_start,
                   word_end=word_end, strip_tabs=strip_tabs,
                   delimiter=bytes(b for b in word if b not in b"\\'\""))


def skip_quoted_word(text: bytes, i: int) -> int:
    n = len(text)
    while i < n and text[i] not in WORD_END:
        byte = text[i]
        if byte == BACKSLASH:
            i += 2
        elif byte in (SINGLE_QUOTE, DOUBLE_QUOTE):
            close = text.find(bytes([byte]), i + 1)
            i = n if close < 0 else close + 1
        else:
            i += 1
    return min(i, n)


def read_bodies(text: bytes, i: int, pending: list[Heredoc]) -> int:
    n = len(text)
    for heredoc in pending:
        heredoc.body_start = i
        pos = i
        while pos < n:
            newline = text.find(b"\n", pos)
            line_end = n if newline < 0 else newline
            line = text[pos:line_end]
            stripped = line.lstrip(b"\t") if heredoc.strip_tabs else line
            if stripped == heredoc.delimiter:
                heredoc.body_end = pos
                heredoc.term_start = pos + len(line) - len(stripped)
                heredoc.term_end = heredoc.line_end = line_end
                i = min(line_end + 1, n)
                break
            pos = line_end + 1
        else:
            heredoc.body_end = heredoc.line_end = i = n
    return i


def blank_heredocs(text: bytes, heredocs: list[Heredoc]) -> bytes:
    out = bytearray(text)
    for heredoc in heredocs:
        for start, end in heredoc.spans():
            for k in range(start, end):
                if out[k] != NEWLINE:
                    out[k] = SPACE
    return bytes(out)


def split_words(src: bytes, start: int, end: int):
    for m in WORD.finditer(src, start, end):
        yield m.start(), m.end()


def flatten(value: bytes) -> bytes:
    return value.replace(b"\r", b" ").replace(b"\n", b" ").rstrip(b" \t")


class Tokenizer:
    def __init__(self, src: bytes):
        self.src = src
        self.out = Output()
        self.covered = bytearray(len(src))
        self.heredocs = scan_heredocs(src)
        # Heredoc bytes, for the heredoc rules only: a tree-sitter leaf can still span them.
        self.reserved = bytearray(len(src))
        for heredoc in self.heredocs:
            for start, end in heredoc.spans():
                self.reserved[start:end] = b"\x01" * (end - start)

    def run(self) -> Output:
        tree = PARSER.parse(blank_heredocs(self.src, self.heredocs))
        self.walk(tree.root_node, 0, False)
        self.emit_heredocs()
        self.fill_gaps()
        return self.out

    def emit(self, start: int, end: int, kind: bytes, reserved_ok: bool = False) -> None:
        k = start
        while k < end:
            if self.covered[k] or (self.reserved[k] and not reserved_ok):
                k += 1
                continue
            j = k
            while j < end and not self.covered[j] and (reserved_ok or not self.reserved[j]):
                j += 1
            self.emit_span(k, j, kind)
            k = j

    def emit_span(self, start: int, end: int, kind: bytes) -> None:
        while start < end and self.src[start] in WHITESPACE:
            start += 1
        value = flatten(self.src[start:end])
        if not value:
            return
        self.out.token(start, kind, value)
        self.covered[start:end] = b"\x01" * (end - start)

    def emit_heredocs(self) -> None:
        for heredoc in self.heredocs:
            if heredoc.io_start < heredoc.op_start:
                self.emit(heredoc.io_start, heredoc.op_start, b"number", reserved_ok=True)
            self.emit(heredoc.op_start, heredoc.op_end, b"op", reserved_ok=True)
            self.emit(heredoc.word_start, heredoc.word_end, b"heredoc_delim", reserved_ok=True)
            self.emit(heredoc.body_start, heredoc.body_end, b"heredoc", reserved_ok=True)
            if heredoc.term_start is not None:
                self.emit(heredoc.term_start, heredoc.term_end, b"heredoc_delim", reserved_ok=True)

    def fill_gaps(self) -> None:
        for word_start, word_end in split_words(self.src, 0, len(self.src)):
            k = word_start
            while k < word_end:
                if self.covered[k]:
                    k += 1
                    continue
                run_end = k
                while run_end < word_end and not self.covered[run_end]:
                    run_end += 1
                continuation = self.src[k:run_end] == b"\\" and self.src[run_end:run_end + 1] in (b"\n", b"\r")
                self.emit(k, run_end, b"op" if continuation else b"word")
                k = run_end

    def walk(self, node, depth: int, in_arith: bool) -> None:
        stack = [(node, depth, in_arith)]
        while stack:
            current, current_depth, current_arith = stack.pop()
            if self.visit(current, current_depth, current_arith):
                continue
            for child in reversed(current.children):
                stack.append((child, current_depth + 1, current_arith or opens_arithmetic(current, child)))

    def visit(self, node, depth: int, in_arith: bool) -> bool:
        """Handle node; True when its children must not be walked."""
        if node.is_missing or node.start_byte == node.end_byte:
            return True
        node_type = node.type
        start, end = node.start_byte, node.end_byte

        if node_type == "function_definition":
            name = node.child_by_field_name("name")
            label = self.src[name.start_byte:name.end_byte] if name else b""
            self.out.begin(start, depth, b"function", b"DECL|function|" + flatten(label))
            self.out.end(end, depth, b"function")
            return False

        if node_type in ("string", "raw_string", "ansi_c_string"):
            # A heredoc inside the string ("$(cat <<EOF ...)") cuts it in pieces.
            self.emit(start, end, b"literal")
            return True

        if node_type == "ERROR":
            self.visit_error(node, depth)
            return True

        if node_type == "concatenation":
            self.visit_concatenation(node, depth, in_arith)
            return True

        if node.child_count > 0:
            return False

        self.emit(start, end, leaf_kind(node, self.src[start:end], in_arith))
        return True

    def visit_concatenation(self, node, depth: int, in_arith: bool) -> None:
        """tree-sitter-bash cuts a word at every byte that is not valid UTF-8; the pieces
        are joined back, so no token ends inside a multi-byte sequence."""
        children = node.children
        i = 0
        while i < len(children):
            j = i
            while (j + 1 < len(children) and children[j].type == children[j + 1].type == "word"
                   and children[j].end_byte == children[j + 1].start_byte
                   and self.non_ascii_cut(children[j].end_byte)):
                j += 1
            if j > i:
                self.emit(children[i].start_byte, children[j].end_byte, b"word")
            else:
                self.walk(children[i], depth + 1, in_arith)
            i = j + 1

    def non_ascii_cut(self, offset: int) -> bool:
        return self.src[offset - 1] >= 0x80 or self.src[offset] >= 0x80

    def visit_error(self, node, depth: int) -> None:
        """Inside a syntax error the kinds of loose leaves are unreliable (`-u` comes out as
        `-` and the keyword `u`), so leaves that touch are merged into one `word`."""
        run_start = run_end = None
        for child in node.children:
            if child.child_count == 0 and child.type not in ("comment", "raw_string", "ansi_c_string"):
                if run_end is not None and child.start_byte == run_end:
                    run_end = child.end_byte
                    continue
                if run_start is not None:
                    self.emit(run_start, run_end, b"word")
                run_start, run_end = child.start_byte, child.end_byte
                continue
            if run_start is not None:
                self.emit(run_start, run_end, b"word")
                run_start = run_end = None
            self.walk(child, depth + 1, False)
        if run_start is not None:
            self.emit(run_start, run_end, b"word")


def opens_arithmetic(parent, child) -> bool:
    """True when child is inside $(( )), $[ ], (( )) or the header of for (( ))."""
    if parent.type == "arithmetic_expansion":
        return True
    if parent.type == "compound_statement":
        return parent.children[0].type == "(("
    if parent.type == "c_style_for_statement":
        return child.type not in ("do_group", "compound_statement")
    return False


def leaf_kind(node, text: bytes, in_arith: bool) -> bytes:
    node_type = node.type
    if node_type == "comment":
        return b"comment"
    if not node.is_named:
        if node_type.isalpha():
            return b"command" if text in KEYWORD_COMMANDS else b"keyword"
        return b"op"
    if node_type in ("variable_name", "special_variable_name"):
        return b"variable"
    if node_type == "file_descriptor":
        return b"number"
    if node_type == "number":
        parent, previous = node.parent, node.prev_sibling
        fd_target = (parent is not None and parent.type == "file_redirect" and previous is not None
                     and previous.type in (">&", "<&"))
        return b"number" if in_arith or fd_target else b"word"
    if node_type == "word" and node.parent is not None and node.parent.type == "command_name":
        return b"command"
    return b"word"


def position_of(src: bytes, line_starts: list[int], offset: int) -> bytes:
    """LINE:COL of offset; COL counts the characters of the line decoded as UTF-8."""
    line = bisect.bisect_right(line_starts, offset) - 1
    prefix = src[line_starts[line]:offset].decode("utf-8", errors="replace")
    return f"{line + 1}:{len(prefix) + 1}".encode()


def render(src: bytes, records, with_position: bool) -> bytes:
    marker_prefix = b"-:-|" if with_position else b""
    line_starts = [0] + [m.end() for m in re.finditer(rb"\n", src)]
    lines = [marker_prefix + UNIT_HEADER]
    for record in records:
        if isinstance(record, Token):
            prefix = position_of(src, line_starts, record.offset) + b"|" if with_position else b""
            lines.append(prefix + record.kind + b"|" + record.value)
        else:
            lines.append(marker_prefix + record.text)
    lines += [marker_prefix + b"end_unit", marker_prefix]
    return b"\n".join(lines) + b"\n"


def check(src: bytes, records: list) -> str | None:
    values = [record.value for record in records if isinstance(record, Token)]
    if b"".join(WORD.findall(b"".join(values))) != b"".join(WORD.findall(src)):
        return "token values do not rebuild the source"
    open_markers = []
    for record in records:
        if not isinstance(record, Marker):
            continue
        m = re.fullmatch(rb"(begin|end)_([a-z_]+)", record.text)
        if m is None:
            continue
        if m.group(1) == b"begin":
            open_markers.append(m.group(2))
        elif not open_markers or open_markers.pop() != m.group(2):
            return "unbalanced markers"
    if open_markers:
        return "unbalanced markers"
    return None


def fallback_records(src: bytes) -> list:
    return [Token(start, b"unknown", src[start:end]) for start, end in split_words(src, 0, len(src))]


def tokenize(src: bytes, name: str = "") -> list:
    try:
        records = list(Tokenizer(src).run().records())
        reason = check(src, records)
    except Exception as exc:  # noqa: BLE001 - the tokenizer must never fail
        reason = f"internal error: {type(exc).__name__}: {exc}"
    if reason is None:
        return records
    print(f"sh_tokenizer: falling back to word split for [{name}]: {reason}", file=sys.stderr)
    return fallback_records(src)


def library_paths() -> list[str]:
    """The compiled tree-sitter libraries, which tokenize/tokenizerIdentity.pl digests."""
    return [tree_sitter._binding.__file__, tree_sitter_bash._binding.__file__]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="POSIX sh / bash tokenizer for cregit")
    parser.add_argument("--language", default="Sh", help="Language of the source; only Sh is accepted")
    parser.add_argument("--position", action="store_true",
                        help="Prefix every line with LINE:COL| (tokens) or -:-| (markers)")
    parser.add_argument("--verbose", action="store_true", help="Report the file being tokenized on stderr")
    parser.add_argument("--library-paths", action="store_true",
                        help="Print the files of the tree-sitter runtime and of the bash grammar, and exit")
    parser.add_argument("source", nargs="?", help="File to tokenize")
    parser.add_argument("output", nargs="?", help="Output file (default: stdout)")
    args = parser.parse_args(argv)
    if args.library_paths:
        print("\n".join(library_paths()))
        return 0
    if args.source is None:
        parser.error("the following arguments are required: source")
    if args.language.lower() != "sh":
        print(f"sh_tokenizer: can only tokenize Sh, not [{args.language}]", file=sys.stderr)
        return 2
    try:
        with open(args.source, "rb") as f:
            src = f.read()
    except OSError as exc:
        print(f"sh_tokenizer: cannot read {args.source}: {exc}", file=sys.stderr)
        return 1
    if args.verbose:
        print(f"sh_tokenizer: tokenizing {args.source}", file=sys.stderr)
    data = render(src, tokenize(src, args.source), args.position)
    if args.output:
        with open(args.output, "wb") as f:
            f.write(data)
    else:
        sys.stdout.buffer.write(data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
