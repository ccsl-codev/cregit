"""Unit tests for generate_dataset.py's source-walking helpers.

These three functions walk a token stream and the original source side by side.
They must agree, and when the source runs out first they must stop rather than
crash: the dataset step runs last, so a crash here throws away the whole
project's work.

Run with:  python3 -m pytest tests/test_generate_dataset.py
"""
from __future__ import annotations

import json
import sqlite3

import pytest

from generate_dataset import (DEFAULT_MEMORY_LIMIT, PROJECT_META_FIELDS,
                              SourceReader, is_ws, load_project_meta,
                              parse_memory_limit, project_meta_sql,
                              skip_comment, skip_literal, skip_token,
                              sql_literal)


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
    """The token is non-whitespace while the source is whitespace, so skip_literal
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
# the DuckDB heap cap
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


# --------------------------------------------------------------------------- #
# per-project metadata columns
# --------------------------------------------------------------------------- #

def test_a_metadata_value_with_an_apostrophe_is_escaped():
    """An unescaped ' ends the literal early and the rest of the value becomes
    SQL. Doubling it is the standard escape and the only one DuckDB needs."""
    assert sql_literal("openjdk/jdk21u") == "'openjdk/jdk21u'"
    assert sql_literal("o'brien/repo") == "'o''brien/repo'"
    assert sql_literal("") == "''"


def test_a_value_holding_two_apostrophes_escapes_both():
    """Every occurrence is replaced, not just the first."""
    assert sql_literal("it's o'clock") == "'it''s o''clock'"
    assert sql_literal("''") == "''''''"


def test_a_backslash_is_left_alone():
    """file_mask is a regex: \\.(c|cc|cp|cpp|cxx|h|hh|hpp)$. DuckDB follows the
    standard and reads no backslash escapes inside a single-quoted string, so
    doubling backslashes here would corrupt every mask."""
    assert sql_literal(r"\.[ch]$") == r"'\.[ch]$'"
    assert sql_literal("a\\") == "'a\\'"


def test_a_non_string_value_is_still_quoted():
    """The sidecar is meant to hold strings only, but a number arriving from a
    hand-edited JSON must not produce bare SQL."""
    assert sql_literal(300) == "'300'"


def test_the_field_list_is_twenty_nine_names_ending_in_the_manifest_pair():
    """The sidecar carries 29 fields. manifest_category and file_mask come
    last, and file_mask is the regex the project was actually tokenized with."""
    assert len(PROJECT_META_FIELDS) == 29
    assert len(set(PROJECT_META_FIELDS)) == 29
    assert PROJECT_META_FIELDS[:2] == ("clone_url", "provenance_status")
    assert PROJECT_META_FIELDS[-2:] == ("manifest_category", "file_mask")


def test_the_sql_block_names_every_field_once_in_order():
    """The sidecar JSON is written sort_keys=True, so iterating it would emit the
    columns alphabetically and the corpus contract would fail."""
    values = {f: "" for f in PROJECT_META_FIELDS}
    names = [line.split(" AS ")[1].rstrip(",")
             for line in project_meta_sql(values).splitlines()]
    assert tuple(names) == PROJECT_META_FIELDS


def test_every_value_in_the_sql_block_goes_through_sql_literal():
    values = {f: "o'brien" for f in PROJECT_META_FIELDS}
    block = project_meta_sql(values)
    assert block.count("'o''brien'") == len(PROJECT_META_FIELDS)
    assert "'o'brien'" not in block


def test_no_sidecar_means_every_metadata_field_is_empty():
    """An older caller that passes neither flag still gets the full column set."""
    meta = load_project_meta("", "anything")
    assert list(meta) == list(PROJECT_META_FIELDS)
    assert set(meta.values()) == {""}


def test_a_field_absent_from_the_sidecar_row_becomes_empty(tmp_path):
    path = tmp_path / "meta.json"
    path.write_text(json.dumps({"p": {"stratum": "foundation"}}))
    meta = load_project_meta(str(path), "p")
    assert meta["stratum"] == "foundation"
    assert meta["file_mask"] == ""
    assert list(meta) == list(PROJECT_META_FIELDS)


def test_a_key_absent_from_the_sidecar_fails_loudly(tmp_path):
    """A mistyped slug must not publish 29 blank columns in silence."""
    path = tmp_path / "meta.json"
    path.write_text(json.dumps({"real-project": {f: "x" for f in PROJECT_META_FIELDS}}))
    with pytest.raises(SystemExit, match="not in"):
        load_project_meta(str(path), "typo-project")


# --------------------------------------------------------------------------- #
# end to end, against DuckDB. The unit tests above prove the SQL text; only a
# written Parquet proves the column set, and 67 columns is the contract
# cregit-token-pipeline/validate_schema.py gates the corpus with.
# --------------------------------------------------------------------------- #

TOTAL_COLUMNS = 67                      # 38 token/commit columns + 29 metadata
SHA = "a" * 40


def build_tiny_project(tmp_path):
    """One file, one token, one commit: the smallest input main() accepts."""
    blame = tmp_path / "blame"
    blame.mkdir()
    (blame / "a.c.blame").write_text(f"{SHA};1;\tkeyword|int\n")
    src = tmp_path / "src"
    src.mkdir()
    (src / "a.c").write_text("int\n")

    cregit_db = tmp_path / "cregit.db"
    con = sqlite3.connect(cregit_db)
    con.executescript("""
        CREATE TABLE commits (cid CHAR(40) PRIMARY KEY, autname TEXT,
            autemail TEXT, autdate TEXT, comname TEXT, comemail TEXT,
            comdate TEXT, summary TEXT, ismerge BOOLEAN);
        CREATE TABLE commitmap (cid CHAR(40) PRIMARY KEY, originalcid CHAR(40),
            repo VARCHAR(254));
        CREATE TABLE footers (cid CHAR(40), idx INTEGER, key TEXT, value TEXT);
    """)
    con.execute("INSERT INTO commits VALUES (?,?,?,?,?,?,?,?,0)",
                (SHA, "A Dev", "a@example.com", "2020-01-01",
                 "A Dev", "a@example.com", "2020-01-01", "first"))
    con.commit()
    con.close()

    persons_db = tmp_path / "persons.db"
    con = sqlite3.connect(persons_db)
    con.executescript("""
        CREATE TABLE emails (recordid INTEGER PRIMARY KEY, personid TEXT,
            fullemail TEXT, emailaddr TEXT, emailname TEXT, lcemail TEXT,
            userid TEXT, domain TEXT, autcount INTEGER, comcount INTEGER,
            dateadded TEXT, checked BOOLEAN, notes TEXT);
        CREATE TABLE persons (personid TEXT PRIMARY KEY, personname TEXT);
    """)
    con.commit()
    con.close()
    return blame, src, cregit_db, persons_db


def generate(monkeypatch, tmp_path, repo_name="proj", argv_extra=(), seed=None):
    """Run main() for real and return the Parquet path.

    `seed(cregit_db, persons_db)` runs between the fixture and main(), for the
    tests that need a trailer or a person in the input.
    """
    import generate_dataset as gd

    blame, src, cregit_db, persons_db = build_tiny_project(tmp_path)
    if seed is not None:
        seed(cregit_db, persons_db)
    out = tmp_path / "out" / "proj-dataset.parquet"
    monkeypatch.setattr(gd.sys, "argv", [
        "generate_dataset.py",
        "--blame-dir", str(blame),
        "--source-dir", str(src),
        "--cregit-db", str(cregit_db),
        "--persons-db", str(persons_db),
        "--output", str(out),
        "--repo-name", repo_name,
        *argv_extra,
    ])
    gd.main()
    return out


def schema_of(duckdb, path):
    return [(r[0], r[1]) for r in
            duckdb.sql("describe select * from read_parquet(?)",
                       params=[str(path)]).fetchall()]


def test_a_call_with_no_metadata_flags_still_writes_all_columns(monkeypatch, tmp_path):
    """The flag is threaded through a runner and an orchestrator, so there is a
    window where an old caller invokes a new generator. That call must still
    produce a file the corpus gate accepts, with the metadata blank."""
    duckdb = pytest.importorskip("duckdb")
    out = generate(monkeypatch, tmp_path)

    schema = schema_of(duckdb, out)
    assert len(schema) == TOTAL_COLUMNS
    names = [n for n, _ in schema]
    assert names[0] == "repo_name"
    assert names[1:1 + len(PROJECT_META_FIELDS)] == list(PROJECT_META_FIELDS)
    assert names[1 + len(PROJECT_META_FIELDS)] == "file_path"
    assert {t for n, t in schema if n in PROJECT_META_FIELDS} == {"VARCHAR"}

    cols = ", ".join(PROJECT_META_FIELDS)
    row = duckdb.sql(f"select {cols} from read_parquet('{out}')").fetchone()
    assert set(row) == {""}


def test_the_sidecar_values_reach_the_parquet_intact(monkeypatch, tmp_path):
    """Including the two values that break a naive f-string: an apostrophe in
    history_shared_with and a backslash-laden regex in file_mask."""
    duckdb = pytest.importorskip("duckdb")
    mask = r"\.(c|cc|cp|cpp|cxx|h|hh|hpp)$"
    row = {f: "" for f in PROJECT_META_FIELDS}
    row.update(stratum="foundation", history_shared_with="o'brien/repo",
               file_mask=mask, clone_url="https://example.com/o'brien.git")
    meta = tmp_path / "meta.json"
    meta.write_text(json.dumps({"keyed-name": row}, sort_keys=True))

    out = generate(monkeypatch, tmp_path, repo_name="o'brien",
                   argv_extra=("--project-meta", str(meta),
                               "--project-key", "keyed-name"))

    got = duckdb.sql(
        "select repo_name, stratum, history_shared_with, file_mask, clone_url "
        f"from read_parquet('{out}')").fetchone()
    assert got == ("o'brien", "foundation", "o'brien/repo", mask,
                   "https://example.com/o'brien.git")
    assert len(schema_of(duckdb, out)) == TOTAL_COLUMNS


def test_the_project_key_defaults_to_the_repo_name(monkeypatch, tmp_path):
    duckdb = pytest.importorskip("duckdb")
    row = {f: "" for f in PROJECT_META_FIELDS}
    row["stratum"] = "community"
    meta = tmp_path / "meta.json"
    meta.write_text(json.dumps({"proj": row}))

    out = generate(monkeypatch, tmp_path,
                   argv_extra=("--project-meta", str(meta)))
    assert duckdb.sql(
        f"select stratum from read_parquet('{out}')").fetchone() == ("community",)


def test_an_unknown_project_key_stops_the_run_before_phase_1(monkeypatch, tmp_path):
    """Phase 1 inserts one row per token. A stale sidecar must fail first, and it
    must fail rather than write blank provenance."""
    pytest.importorskip("duckdb")
    meta = tmp_path / "meta.json"
    meta.write_text(json.dumps({"other": {f: "x" for f in PROJECT_META_FIELDS}}))
    with pytest.raises(SystemExit, match="not in"):
        generate(monkeypatch, tmp_path,
                 argv_extra=("--project-meta", str(meta)))
    assert not (tmp_path / "out" / "proj-dataset.parquet").exists()


# --------------------------------------------------------------------------- #
# footer trailers resolve to personids


def seed_trailer(value, *, email_rows=()):
    """Give the tiny project one Reviewed-by trailer and the emails rows given."""
    def seed(cregit_db, persons_db):
        con = sqlite3.connect(cregit_db)
        con.execute("INSERT INTO footers VALUES (?,?,?,?)",
                    (SHA, 0, "Reviewed-by", value))
        con.commit()
        con.close()

        con = sqlite3.connect(persons_db)
        for personid, emailaddr, emailname in email_rows:
            con.execute(
                "INSERT INTO emails (personid, fullemail, emailaddr, emailname, "
                "lcemail, userid, domain) VALUES (?,?,?,?,?,?,?)",
                (personid, f"{emailname} <{emailaddr}>", emailaddr, emailname,
                 emailaddr.lower(), personid, emailaddr.partition("@")[2]))
            con.execute("INSERT INTO persons VALUES (?,?)", (personid, emailname))
        con.commit()
        con.close()
    return seed


def footer_personids(duckdb, out):
    return duckdb.sql("select footer_personids from read_parquet(?)",
                      params=[str(out)]).fetchall()[0][0]


def test_a_trailer_carrying_a_bare_address_resolves_to_its_person(monkeypatch, tmp_path):
    duckdb = pytest.importorskip("duckdb")
    out = generate(monkeypatch, tmp_path, seed=seed_trailer(
        "Bob bob@example.com", email_rows=[("p_bob", "bob@example.com", "Bob")]))
    assert footer_personids(duckdb, out) == ["p_bob"]


def test_a_trailer_with_no_address_does_not_match_an_empty_emails_row(monkeypatch, tmp_path):
    duckdb = pytest.importorskip("duckdb")
    out = generate(monkeypatch, tmp_path, seed=seed_trailer(
        "Former-commit-id: 0123456789abcdef0123456789abcdef01234567",
        email_rows=[("p_empty", "", "Nameless")]))
    assert footer_personids(duckdb, out) == []
