import duckdb

from build import commit_runs


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
