import duckdb
import pytest

from build import commit_runs, load, resolve_origins
from token_history import SEP


def test_a_token_with_two_mainline_intervals_is_one_place_in_its_run():
    # tokens has one row per mainline interval; token 1 left the mainline
    # and came back, so it has two rows
    con = duckdb.connect()
    con.execute("CREATE TABLE tokens (file_path VARCHAR, token_id BIGINT, "
                "token VARCHAR, mainline_in_sha VARCHAR)")
    con.execute("INSERT INTO tokens VALUES ('a.c', 0, 'x', 'm1'), "
                "('a.c', 1, 'y', 'm1'), ('a.c', 1, 'y', 'm3'), "
                "('a.c', 2, 'z', 'm1')")
    con.execute("CREATE TABLE runs (sha VARCHAR, kind VARCHAR, "
                "whole_file INTEGER, file_path VARCHAR, token_ids VARCHAR)")
    con.execute("INSERT INTO runs VALUES ('c1', 'born', 0, 'a.c', '0,1,2'), "
                "('c1', 'died', 0, 'a.c', '0,1,2')")
    (sha, runs, texts), = list(commit_runs(con))
    assert sha == "c1"
    assert [ids for _, _, _, ids in runs] == [[0, 1, 2], [0, 1, 2]]
    assert texts[("a.c", 1)] == "y"


def test_a_path_written_again_by_a_resume_is_loaded_once(tmp_path):
    # part-1 has a.c from a run that stopped before paths-done.txt; the
    # resume wrote a.c again in part-2, here after a change of the replay
    def row(path, born):
        return SEP.join([path, "0", "x", born, "0", "", "", "", "m", "1", "",
                         ""]) + "\n"
    (tmp_path / "part-1.tsv").write_text(row("a.c", "b1") + row("b.c", "b1"))
    (tmp_path / "part-2.tsv").write_text(row("a.c", "b2"))
    con = duckdb.connect()
    load(con, str(tmp_path))
    assert con.execute("SELECT count(*), count(DISTINCT (file_path, token_id))"
                       " FROM tokens").fetchone() == (2, 2)
    assert len(con.execute("SELECT * FROM tokens").description) == 12


def test_no_part_files_is_a_clear_error(tmp_path):
    with pytest.raises(SystemExit, match="no part"):
        load(duckdb.connect(), str(tmp_path))


def test_origins_follow_chains():
    con = duckdb.connect()
    con.execute("CREATE TABLE links (file_path VARCHAR, token_id BIGINT, "
                "origin_path VARCHAR, origin_token_id BIGINT)")
    con.execute("INSERT INTO links VALUES ('c', 1, 'b', 1), ('b', 1, 'a', 1)")
    resolve_origins(con)
    assert sorted(con.execute("FROM links").fetchall()) == [
        ("b", 1, "a", 1), ("c", 1, "a", 1)]
