import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))

import sh_tokenizer  # noqa: E402

FIXTURES = HERE / "fixtures"
EXPECTED = HERE / "expected"
SCRIPT = ROOT / "sh_tokenizer.py"


def tokenize(src: bytes) -> bytes:
    return sh_tokenizer.render(src, sh_tokenizer.tokenize(src), False)


def token_lines(src: bytes) -> list[bytes]:
    out = tokenize(src).split(b"\n")
    return [line for line in out[1:] if b"|" in line and not line.startswith(b"DECL|")]


def is_valid(src: bytes) -> bool:
    return sh_tokenizer.check(src, list(sh_tokenizer.Tokenizer(src).run().records())) is None


class GoldenFixturesTest(unittest.TestCase):

    def test_fixtures_match_expected_output(self):
        for fixture in sorted(FIXTURES.glob("*.sh")):
            src = fixture.read_bytes()
            records = sh_tokenizer.tokenize(src)
            for suffix, with_position in ((".token", True), (".nopos.token", False)):
                expected = EXPECTED / (fixture.name + suffix)
                with self.subTest(expected.name):
                    self.assertEqual(sh_tokenizer.render(src, records, with_position), expected.read_bytes())


class HeredocScannerTest(unittest.TestCase):

    def bodies(self, src: bytes) -> list[bytes]:
        return [src[h.body_start:h.body_end] for h in sh_tokenizer.scan_heredocs(src)]

    def test_heredoc_followed_by_and_and(self):
        self.assertEqual(self.bodies(b"cat >x <<-\\EOF &&\n\ta\n\tEOF\necho\n"), [b"\ta\n"])

    def test_several_heredocs_on_one_line(self):
        src = b"cmd <<A 6<<-'B' 7<<\"C\"\na\nA\n\tb\n\tB\nc\nC\nnext\n"
        self.assertEqual(self.bodies(src), [b"a\n", b"\tb\n", b"c\n"])
        fds = [src[h.io_start:h.op_start] for h in sh_tokenizer.scan_heredocs(src)]
        self.assertEqual(fds, [b"", b"6", b"7"])

    def test_not_a_heredoc(self):
        for src in (b"echo '<<EOF'\n", b'echo "<<EOF"\n', b"# <<EOF\n", b"x=$((1<<2))\n",
                    b"cat <<<word\n", b"echo a\\<<b\n"):
            with self.subTest(src):
                self.assertEqual(self.bodies(src), [])

    def test_arithmetic_command_is_not_a_heredoc(self):
        for src in (b'x=1\n(( y = x << 2 ))\necho "y=$y"\necho after\n',
                    b"if ((1 << 2)); then echo yes; fi\necho after\n",
                    b"for ((i = 1; i << 2 < 8; i++)); do echo $i; done\necho after\n"):
            with self.subTest(src):
                self.assertEqual(self.bodies(src), [])

    def test_heredoc_inside_backquotes(self):
        self.assertEqual(self.bodies(b"x=`cat <<EOF\nbody $y\nEOF\n`\necho \"[$x]\"\n"), [b"body $y\n"])
        # The closing backquote also ends the comment.
        self.assertEqual(self.bodies(b"x=`echo a #c`; cat <<EOF\nbody\nEOF\n"), [b"body\n"])

    def test_crlf_heredoc(self):
        # The delimiter and the terminator line are both EOF\r.
        self.assertEqual(self.bodies(b"cat <<EOF\r\nbody\r\nEOF\r\necho after\r\n"), [b"body\r\n"])

    def test_hash_after_an_expansion_is_not_a_comment(self):
        for src in (b"echo $(echo a)#b <<EOF\nbody\nEOF\necho after\n",
                    b"echo $((1))#b <<EOF\nbody\nEOF\necho after\n"):
            with self.subTest(src):
                self.assertEqual(self.bodies(src), [b"body\n"])

    def test_line_continuation_inside_the_delimiter(self):
        # The delimiter is EOF.
        self.assertEqual(self.bodies(b"cat <<EO\\\nF\nbody\nEOFF\nEOF\necho after\n"), [b"body\nEOFF\n"])

    def test_missing_terminator_runs_to_end_of_file(self):
        self.assertEqual(self.bodies(b"cat <<EOF\nno end\n"), [b"no end\n"])

    def test_body_starts_after_a_multiline_string(self):
        src = b"cat <<EOF; echo 'a\nb'\nbody\nEOF\n"
        self.assertEqual(self.bodies(src), [b"body\n"])

    def test_dash_terminator_indented_with_spaces_does_not_close(self):
        self.assertEqual(self.bodies(b"cat <<-EOF\n  EOF\nEOF\n"), [b"  EOF\n"])

    def test_operator_on_the_last_line_without_newline(self):
        self.assertEqual(self.bodies(b"cat <<EOF"), [b""])

    def test_empty_delimiter_ends_at_the_first_empty_line(self):
        self.assertEqual(self.bodies(b"cat <<''\nbody\n\nafter\n"), [b"body\n"])


class TokenizerTest(unittest.TestCase):

    def test_strings_and_heredoc_bodies_are_one_token(self):
        src = b"echo \"a $b c\" 'd e' $'f\\tg'\ncat <<EOF\nx $y\nz\nEOF\n"
        self.assertEqual(token_lines(src), [
            b"command|echo", b'literal|"a $b c"', b"literal|'d e'", b"literal|$'f\\tg'",
            b"command|cat", b"op|<<", b"heredoc_delim|EOF", b"heredoc|x $y z", b"heredoc_delim|EOF",
        ])

    def test_heredoc_text_is_never_code(self):
        src = b"cat >x <<-\\EOF &&\n\tif true; then echo 'a b'; fi\n\tEOF\necho\n"
        self.assertEqual(token_lines(src), [
            b"command|cat", b"op|>", b"word|x", b"op|<<-", b"heredoc_delim|\\EOF", b"op|&&",
            b"heredoc|if true; then echo 'a b'; fi", b"heredoc_delim|EOF", b"command|echo",
        ])

    def test_heredoc_inside_a_string_cuts_it(self):
        src = b'x="$(cat <<-EOF\n\tbody\n\tEOF\n)" &&\necho\n'
        self.assertEqual(token_lines(src), [
            b"variable|x", b"op|=", b'literal|"$(cat', b"op|<<-", b"heredoc_delim|EOF",
            b"heredoc|body", b"heredoc_delim|EOF", b'literal|)"', b"op|&&", b"command|echo",
        ])

    def test_empty_heredoc_body_has_no_token(self):
        self.assertEqual(token_lines(b"cat <<EOF\nEOF\n"),
                         [b"command|cat", b"op|<<", b"heredoc_delim|EOF", b"heredoc_delim|EOF"])

    def test_function_markers(self):
        out = tokenize(b"f() {\n\tg () { :; }\n}\n")
        self.assertIn(b"begin_function\nDECL|function|f\nword|f\n", out)
        self.assertIn(b"begin_function\nDECL|function|g\nword|g\n", out)
        self.assertIn(b"end_function\n\nop|}\nend_function\n\n", out)

    def test_numbers_only_in_arithmetic_and_redirections(self):
        self.assertEqual(token_lines(b"exit 1\necho x >&2 2>e\ny=$((1 + 2))\n"), [
            b"command|exit", b"word|1",
            b"command|echo", b"word|x", b"op|>&", b"number|2", b"number|2", b"op|>", b"word|e",
            b"variable|y", b"op|=", b"op|$((", b"number|1", b"op|+", b"number|2", b"op|))",
        ])
        self.assertEqual(token_lines(b"[ $x -eq 1 ]\n[[ $x -eq 1 ]]\ntest $x -eq 1\n"), [
            b"op|[", b"op|$", b"variable|x", b"word|-eq", b"word|1", b"op|]",
            b"op|[[", b"op|$", b"variable|x", b"word|-eq", b"word|1", b"op|]]",
            b"command|test", b"op|$", b"variable|x", b"word|-eq", b"word|1",
        ])
        self.assertEqual(token_lines(b"(( 2 ))\nfor ((i = 0; i < 3; i++)); do echo 4; done\n"), [
            b"op|((", b"number|2", b"op|))",
            b"keyword|for", b"op|((", b"variable|i", b"op|=", b"number|0", b"op|;", b"word|i", b"op|<",
            b"number|3", b"op|;", b"word|i", b"op|++", b"op|))", b"op|;",
            b"keyword|do", b"command|echo", b"word|4", b"op|;", b"keyword|done",
        ])

    def test_line_continuation(self):
        self.assertEqual(token_lines(b"a \\\n  b\n"), [b"command|a", b"op|\\", b"word|b"])

    def test_positions(self):
        out = sh_tokenizer.render(b"a\n  b\n", sh_tokenizer.tokenize(b"a\n  b\n"), True).split(b"\n")
        self.assertIn(b"1:1|command|a", out)
        self.assertIn(b"2:3|command|b", out)
        self.assertEqual(out[0][:4], b"-:-|")

    def test_positions_count_characters(self):
        # An invalid byte counts as one character.
        for src in ("echo é x\n".encode(), b"echo \xe9 x\n"):
            with self.subTest(src):
                out = sh_tokenizer.render(src, sh_tokenizer.tokenize(src), True).split(b"\n")
                self.assertIn(b"1:8|word|x", out)

    def test_word_with_invalid_utf8_is_one_token(self):
        self.assertEqual(token_lines(b"echo \xe2\x82 b\n"), [b"command|echo", b"word|\xe2\x82", b"word|b"])
        self.assertEqual(token_lines(b"echo a\xe2\x82b c\n"), [b"command|echo", b"word|a\xe2\x82b", b"word|c"])
        self.assertEqual(token_lines(b"echo a\xe9$y\xe9b\n"),
                         [b"command|echo", b"word|a\xe9", b"op|$", b"variable|y", b"word|\xe9b"])

    def test_form_feed_and_vertical_tab_are_kept(self):
        # tree-sitter-bash still cuts a word at them.
        self.assertEqual(token_lines(b"echo \x0c x\x0by\n"),
                         [b"command|echo", b"word|\x0c", b"word|x", b"word|\x0b", b"word|y"])

    def test_garbage_never_fails(self):
        for src in (b"", b"\n\n", b"if then fi fi ((( ''' \"", b"cat <<\n", b"\xff\xfe\x00 \x80",
                    b"echo 'x", b"f() {"):
            with self.subTest(src):
                out = tokenize(src)
                self.assertTrue(out.startswith(sh_tokenizer.UNIT_HEADER))
                self.assertTrue(out.endswith(b"end_unit\n\n"))
                self.assertNotIn(b"\nunknown|", out)
                self.assertTrue(is_valid(src))

    def test_loose_words_inside_a_syntax_error(self):
        self.assertEqual(token_lines(b"case 'a b' in\n"), [b"word|case", b"literal|'a b'", b"word|in"])
        self.assertIn(b"word|-u", token_lines(b"if then -u fi\n"))

    def test_syntax_errors_around_heredocs_keep_the_output_valid(self):
        # Fuzzing inputs: error recovery ends a node inside a body, or adds a MISSING node.
        for src in (b'e(){case"1" in)ro<<O\n;\n', b'<<f "\x00n"|\ni', b'<<f "\x00n"|\nt\n',
                    b"'\x00d\"'<<E\n_\n", b'e(){case"1" in)ro<<O\n\t\n)', b"''$((\"\">&2&&"):
            with self.subTest(src):
                self.assertTrue(is_valid(src))

    def test_fallback_is_a_word_split(self):
        src = b"echo 'a b'\n"
        failures = {"check fails": mock.patch.object(sh_tokenizer, "check", return_value="forced"),
                    "tokenizer raises": mock.patch.object(sh_tokenizer.Tokenizer, "run", side_effect=RuntimeError)}
        for label, failure in failures.items():
            with self.subTest(label):
                with failure, contextlib.redirect_stderr(io.StringIO()) as err:
                    lines = token_lines(src)
                self.assertEqual(lines, [b"unknown|echo", b"unknown|'a", b"unknown|b'"])
                self.assertIn("falling back to word split", err.getvalue())


class CommandLineTest(unittest.TestCase):

    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True)

    def test_dispatcher_style_call(self):
        fixture = FIXTURES / "02_functions.sh"
        result = self.run_cli("--language=Sh", str(fixture))
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, (EXPECTED / "02_functions.sh.nopos.token").read_bytes())

    def test_output_argument(self):
        fixture = FIXTURES / "02_functions.sh"
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "out.token"
            result = self.run_cli(str(fixture), str(output))
            self.assertEqual(result.returncode, 0)
            self.assertEqual(result.stdout, b"")
            self.assertEqual(output.read_bytes(), (EXPECTED / "02_functions.sh.nopos.token").read_bytes())

    def test_position_option(self):
        result = self.run_cli("--position", str(FIXTURES / "12_non_utf8.sh"))
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, (EXPECTED / "12_non_utf8.sh.token").read_bytes())

    def test_library_paths(self):
        result = self.run_cli("--library-paths")
        self.assertEqual(result.returncode, 0)
        paths = result.stdout.decode().split()
        self.assertEqual(len(paths), 2)
        for path in paths:
            self.assertTrue(Path(path).is_file(), path)

    def test_unreadable_file(self):
        result = self.run_cli(str(HERE / "does-not-exist.sh"))
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"cannot read", result.stderr)

    def test_wrong_language(self):
        self.assertEqual(self.run_cli("--language=C", str(FIXTURES / "02_functions.sh")).returncode, 2)

    def test_usage_errors(self):
        fixture = str(FIXTURES / "02_functions.sh")
        for args in (("--bogus", fixture), (fixture, os.devnull, "extra"), ()):
            with self.subTest(args):
                result = self.run_cli(*args)
                self.assertEqual(result.returncode, 2)
                self.assertIn(b"usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
