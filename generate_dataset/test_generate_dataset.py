"""Unit tests for generate_dataset.py's source-walking helpers.

These three functions walk a token stream and the original source side by side.
They must agree, and when the source runs out first they must stop rather than
crash: the dataset step runs last, after tokenising and blaming, so a crash here
throws away the whole project's work. microsoft/terminal died in skip_literal
after 2,204 seconds:

    TypeError: can only concatenate str (not "NoneType") to str
      at generate_dataset.py:145 in skip_literal

Run with:  python3 -m pytest generate_dataset/test_generate_dataset.py
"""
from __future__ import annotations

import pytest

from generate_dataset import (DEFAULT_MEMORY_LIMIT, SourceReader, is_ws,
                              parse_memory_limit, skip_comment, skip_literal,
                              skip_token)


def reader(text: str) -> SourceReader:
    return SourceReader(text)


# --------------------------------------------------------------------------- #
# end-of-source safety. The regression group.
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize("fn", [skip_literal, skip_comment, skip_token])
def test_stops_instead_of_crashing_when_the_source_runs_out(fn):
    """The token stream claims more characters than the source holds.

    All three must return what they read. Before the fix skip_literal raised
    TypeError here, because its inner whitespace loop could exhaust the source
    after the None guard at the top of the iteration had already passed.
    """
    assert isinstance(fn("abcdef", reader("ab")), str)


def test_skip_literal_survives_source_ending_in_whitespace():
    """The exact shape that killed microsoft/terminal.

    The token is non-whitespace while the source is whitespace, so skip_literal
    enters its inner loop, consumes to end-of-source, and comes back with
    ch = None. The guard at the top of the iteration cannot see that.
    """
    assert skip_literal('"xy"', reader("  ")) == "  "


def test_skip_literal_survives_an_empty_source():
    assert skip_literal("abc", reader("")) == ""


@pytest.mark.parametrize("fn", [skip_literal, skip_comment, skip_token])
def test_empty_token_reads_nothing(fn):
    """No token means no source consumed, so a later reader position is intact."""
    r = reader("untouched")
    assert fn("", r) == ""
    assert r.read_char() == "u"


# --------------------------------------------------------------------------- #
# normal behaviour, so the guard above cannot be "fixed" by breaking early
# --------------------------------------------------------------------------- #

def test_skip_literal_returns_the_matching_source_text():
    assert skip_literal('"hi"', reader('"hi" rest')) == '"hi"'


def test_skip_literal_keeps_whitespace_the_token_stream_dropped():
    """srcML collapses whitespace inside a literal, so the source carries runs
    the token does not. Those bytes belong in source_text."""
    assert skip_literal('"a b"', reader('"a   b" tail')) == '"a   b"'


def test_skip_token_counts_non_whitespace_only():
    """skip_token consumes as many non-whitespace characters as the token holds,
    and keeps any whitespace it passes through."""
    assert skip_token("ab", reader("a b rest")) == "a b"


def test_skip_comment_returns_the_comment_text():
    assert skip_comment("/*x*/", reader("/*x*/ after")) == "/*x*/"


def test_is_ws_treats_none_as_not_whitespace():
    """The helper is called with the reader's None sentinel on purpose, so it
    must never raise."""
    assert is_ws(None) is False
    assert is_ws(" ") is True
    assert is_ws("a") is False


# --------------------------------------------------------------------------- #
# scratch placement. On this host /tmp is a 16 GB tmpfs, which is RAM, and
# Phase 1 writes one row per token. A large project must not build that table
# there: filling a tmpfs also exhausts system memory.
# --------------------------------------------------------------------------- #

def run_main(monkeypatch, tmp_path, argv_extra=()):
    """Drive main() far enough to reach the scratch allocation, then stop.

    mkstemp is the call under test, so raising from it pins the arguments
    without needing duckdb, sqlite_scanner or a real blame tree.
    """
    import generate_dataset as gd

    blame = tmp_path / "blame"
    (blame / "sub").mkdir(parents=True)
    (blame / "sub" / "a.c.blame").write_text("")
    src = tmp_path / "src" / "sub"
    src.mkdir(parents=True)
    (src / "a.c").write_text("int x;\n")
    for name in ("cregit.db", "persons.db"):
        (tmp_path / name).write_text("")
    out = tmp_path / "out" / "proj-dataset.parquet"

    seen = {}

    def fake_mkstemp(*a, **kw):
        seen.update(kw)
        raise RuntimeError("stop here")

    monkeypatch.setattr(gd.tempfile, "mkstemp", fake_mkstemp)
    monkeypatch.setattr(gd.sys, "argv", [
        "generate_dataset.py",
        "--blame-dir", str(blame),
        "--source-dir", str(tmp_path / "src"),
        "--cregit-db", str(tmp_path / "cregit.db"),
        "--persons-db", str(tmp_path / "persons.db"),
        "--output", str(out),
        *argv_extra,
    ])
    with pytest.raises(RuntimeError, match="stop here"):
        gd.main()
    return seen, out


def test_scratch_db_is_created_beside_the_output(monkeypatch, tmp_path):
    """The output directory is the one place the caller sized for this project,
    because the Parquet file lands there."""
    seen, out = run_main(monkeypatch, tmp_path)
    assert seen["dir"] == out.parent


def test_scratch_db_ignores_tmpdir(monkeypatch, tmp_path):
    """tempfile honours TMPDIR. Passing dir= explicitly is what stops the
    scratch table following TMPDIR onto the tmpfs."""
    sentinel = tmp_path / "tmpfs-stand-in"
    sentinel.mkdir()
    monkeypatch.setenv("TMPDIR", str(sentinel))
    seen, out = run_main(monkeypatch, tmp_path)
    assert seen["dir"] == out.parent
    assert list(sentinel.iterdir()) == []


def test_output_directory_is_created_when_absent(monkeypatch, tmp_path):
    """mkstemp needs the directory to exist, so main() must make it rather than
    fail after the caller already paid for tokenising and blaming."""
    _seen, out = run_main(monkeypatch, tmp_path)
    assert out.parent.is_dir()


# --------------------------------------------------------------------------- #
# the DuckDB heap cap. DuckDB's default limit is 80% of *total* RAM, so on a
# shared box it sits above what is free and the kernel kills the process before
# DuckDB spills. That killed the Linux run at step 10 on 2026-09-14, at 17.5 GB
# resident on a 30 GB host, after tokenising and blaming had both succeeded.
# --------------------------------------------------------------------------- #

@pytest.mark.parametrize("text", ["8GB", "512MB", "1.5GiB", "8 GB", "8gb", "64KB"])
def test_a_size_with_a_unit_is_accepted(text):
    assert parse_memory_limit(text) == text.strip()


def test_a_percentage_is_refused_with_a_reason():
    """A percentage measures total RAM. The free part is the part that matters,
    so accepting one restores the default that let the OOM killer win."""
    with pytest.raises(ValueError, match="absolute size"):
        parse_memory_limit("80%")


@pytest.mark.parametrize("text", ["", "lots", "8", "GB", "8 gigabytes"])
def test_a_size_without_a_readable_unit_is_refused(text):
    with pytest.raises(ValueError, match="memory size"):
        parse_memory_limit(text)


def test_the_default_is_far_below_a_thirty_gigabyte_host():
    """The default must leave room for the agents and MCP servers that already
    hold about 18 GB of this box, not merely for DuckDB."""
    assert parse_memory_limit(DEFAULT_MEMORY_LIMIT) == "8GB"


def test_a_bad_memory_limit_stops_the_run_before_phase_1(monkeypatch, tmp_path):
    """Phase 1 costs half an hour on a large repository. A typo in the size
    string must fail first, so SystemExit arrives instead of mkstemp's marker."""
    with pytest.raises(SystemExit):
        run_main(monkeypatch, tmp_path, argv_extra=("--memory-limit", "80%"))


def test_a_negative_thread_count_stops_the_run_before_phase_1(monkeypatch, tmp_path):
    with pytest.raises(SystemExit):
        run_main(monkeypatch, tmp_path, argv_extra=("--duckdb-threads", "-1"))


def test_duckdb_reads_back_the_default_limit():
    """The regex proves the string is well formed. Only DuckDB proves it is
    a string DuckDB accepts, so ask DuckDB."""
    duckdb = pytest.importorskip("duckdb")
    con = duckdb.connect()
    con.execute(f"SET memory_limit='{DEFAULT_MEMORY_LIMIT}'")
    reported = con.execute("SELECT current_setting('memory_limit')").fetchone()[0]
    con.execute("SET threads=4")
    threads = con.execute("SELECT current_setting('threads')").fetchone()[0]
    con.close()
    # DuckDB answers in binary units, so 8 GB decimal reads back as 7.4 GiB.
    assert reported.endswith("GiB")
    assert 7.0 <= float(reported.split()[0]) <= 8.0
    assert int(threads) == 4
