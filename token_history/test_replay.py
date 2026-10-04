import os
import re
import subprocess

import pytest

from replay import NULL_BLOB, PathReplay, align, Hunk, parse_log, path_log
from token_history import GitSource, mainline_changes, merge_parents


def git(repo, *args, date=None, check=True):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@x",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@x")
    if date:
        env["GIT_AUTHOR_DATE"] = env["GIT_COMMITTER_DATE"] = f"{date} +0000"
    return subprocess.run(["git", "-C", str(repo), *args], check=check,
                          capture_output=True, text=True, env=env).stdout


def init_repo(path):
    path.mkdir()
    git(path, "init", "-q", "-b", "main")
    return path


def write(repo, path, lines):
    (repo / path).write_text("".join(f"{x}\n" for x in lines))


def commit(repo, msg, date):
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", msg, date=date)
    return git(repo, "rev-parse", "HEAD").strip()


def blame(repo, path):
    out = git(repo, "blame", "--line-porcelain", path)
    return [line[:40] for line in out.splitlines()
            if re.match(r"^[0-9a-f]{40} \d+ \d+", line)]


@pytest.fixture
def repo(tmp_path):
    r = init_repo(tmp_path / "r")
    write(r, "a.c", ["int", "a", ";", "int", "b", ";"])
    commit(r, "c1", "2020-01-01T00:00:00")
    write(r, "a.c", ["int", "a", ";", "long", "b", ";", "x"])
    commit(r, "c2", "2020-02-01T00:00:00")
    git(r, "checkout", "-q", "-b", "side")
    write(r, "a.c", ["int", "a", ";", "long", "b", ";", "x", "side1"])
    commit(r, "s1", "2020-03-01T00:00:00")
    git(r, "checkout", "-q", "main")
    write(r, "a.c", ["main0", "int", "a", ";", "long", "b", ";", "x"])
    commit(r, "m1", "2020-04-01T00:00:00")
    git(r, "merge", "-q", "--no-commit", "side")
    write(r, "a.c", ["main0", "int", "a", ";", "long", "b", ";", "x",
                     "side1", "fix"])
    commit(r, "merge", "2020-05-01T00:00:00")
    write(r, "a.c", ["main0", "a", ";", "long", "b", ";", "x", "side1", "fix"])
    commit(r, "c3", "2020-06-01T00:00:00")
    return r


def replay(repo, path):
    return PathReplay(parse_log(path_log(str(repo), path))).run()


def real_replay(repo, path, mainline=None):
    """The replay of the runner, with the real parents and blobs of git."""
    return GitSource(str(repo), merge_parents(str(repo))).replay(
        path, mainline, None)


def tip_born(rp, repo, path):
    tip = git(repo, "rev-parse", f"HEAD:{path}").strip()
    ids = rp.state_of_blob(tip, 2**62)
    return [rp.tokens[t].born for t in ids], [rp.tokens[t].text for t in ids]


def test_align_insert_delete():
    assert align(3, [Hunk(2, 1, ["y"])]) == [0, ("+", "y"), 2]
    assert align(2, [Hunk(1, 0, ["z"])]) == [0, ("+", "z"), 1]
    assert align(2, [Hunk(0, 0, ["z"])]) == [("+", "z"), 0, 1]


def test_tip_matches_git_blame(repo):
    rp = replay(repo, "a.c")
    born, texts = tip_born(rp, repo, "a.c")
    assert texts == (repo / "a.c").read_text().split()
    assert born == blame(repo, "a.c")
    assert rp.missing_parents == 0 and rp.unaligned_merges == 0


def test_merge_resolution_line_is_born_in_merge(repo):
    rp = replay(repo, "a.c")
    merge = git(repo, "rev-parse", "HEAD~1").strip()
    fix = next(t for t in rp.tokens if t.text == "fix")
    assert fix.born == merge and fix.born_in_merge


def test_deleted_token_keeps_its_death(repo):
    rp = replay(repo, "a.c")
    c3 = git(repo, "rev-parse", "HEAD").strip()
    first_int = next(t for t in rp.tokens if t.text == "int")
    assert first_int.died == c3


def test_deleted_file_and_recreated(repo):
    git(repo, "rm", "-q", "a.c")
    commit(repo, "del", "2020-07-01T00:00:00")
    write(repo, "a.c", ["int", "new"])
    recreate = commit(repo, "re", "2020-08-01T00:00:00")
    rp = replay(repo, "a.c")
    born, _ = tip_born(rp, repo, "a.c")
    assert born == [recreate, recreate] == blame(repo, "a.c")
    assert all(t.died for t in rp.tokens if t.born != recreate)


def test_mainline_intervals(repo):
    rp = replay(repo, "a.c")
    changes = mainline_changes(str(repo), [])["a.c"]
    iv = rp.mainline_intervals(changes)
    side1 = next(i for i, t in enumerate(rp.tokens) if t.text == "side1")
    merge = git(repo, "rev-parse", "HEAD~1").strip()
    assert iv[side1][0][0] == merge and iv[side1][0][2] is None
    first_int = next(i for i, t in enumerate(rp.tokens) if t.text == "int")
    assert iv[first_int][0][2] == git(repo, "rev-parse", "HEAD").strip()
    assert NULL_BLOB not in {c[2] for c in changes}


def test_merge_of_equal_parents_keeps_the_blob(repo):
    git(repo, "checkout", "-q", "-b", "twin")
    write(repo, "b.c", ["other"])
    commit(repo, "twin only touches b.c", "2020-07-01T00:00:00")
    git(repo, "checkout", "-q", "main")
    write(repo, "c.c", ["main only touches c.c"])
    commit(repo, "main only touches c.c", "2020-07-02T00:00:00")
    git(repo, "merge", "-q", "--no-edit", "twin", date="2020-07-03T00:00:00")
    write(repo, "a.c", ["main0", "a", ";", "long", "b", ";", "x", "side1",
                        "fix", "after"])
    commit(repo, "after merge", "2020-07-04T00:00:00")
    rp = replay(repo, "a.c")
    born, _ = tip_born(rp, repo, "a.c")
    assert born == blame(repo, "a.c")
    assert rp.unaligned_merges == 0


def test_merge_follows_real_parent_order(repo):
    base = ["main0", "a", ";", "long", "b", ";", "x", "side1", "fix"]
    git(repo, "checkout", "-q", "-b", "redo")
    write(repo, "a.c", [t for t in base if t != "long"])
    commit(repo, "drop long", "2020-07-01T00:00:00")
    write(repo, "a.c", base + ["tail"])
    commit(repo, "re-add long", "2020-07-02T00:00:00")
    git(repo, "checkout", "-q", "main")
    git(repo, "merge", "-q", "--no-ff", "--no-commit", "redo")
    write(repo, "a.c", base + ["tail", "merged"])
    commit(repo, "merge redo", "2020-07-03T00:00:00")
    rp = real_replay(repo, "a.c")
    born, _ = tip_born(rp, repo, "a.c")
    assert born == blame(repo, "a.c")


def test_merge_equal_to_second_parent_takes_it_whole(repo):
    base = ["main0", "a", ";", "long", "b", ";", "x", "side1", "fix"]
    git(repo, "checkout", "-q", "-b", "same")
    write(repo, "a.c", base + ["s"])
    commit(repo, "side adds s", "2020-07-01T00:00:00")
    git(repo, "checkout", "-q", "main")
    write(repo, "a.c", [t for t in base if t != "long"])
    commit(repo, "main drops long", "2020-07-02T00:00:00")
    write(repo, "a.c", base)
    commit(repo, "main re-adds long", "2020-07-03T00:00:00")
    write(repo, "c.c", ["other"])
    commit(repo, "main touches c.c", "2020-07-04T00:00:00")
    git(repo, "merge", "-q", "--no-ff", "--no-commit", "same")
    write(repo, "a.c", base + ["s"])
    commit(repo, "merge same", "2020-07-05T00:00:00")
    rp = real_replay(repo, "a.c")
    born, _ = tip_born(rp, repo, "a.c")
    assert born == blame(repo, "a.c")


def test_freeing_states_changes_nothing(repo):
    test_merge_follows_real_parent_order(repo)
    changes = mainline_changes(str(repo), [])["a.c"]
    full = real_replay(repo, "a.c")
    lean = real_replay(repo, "a.c", changes)
    assert len(lean.state) < len(full.state)
    assert lean.tokens == full.tokens
    assert lean.mainline_intervals(changes) == full.mainline_intervals(changes)


def test_runs_mark_a_whole_file_move(repo):
    body = ["int", "alpha_long_identifier", ";", "int", "beta_long_identifier",
            ";", "return", "gamma_long_identifier", ";"]
    write(repo, "m.c", body)
    commit(repo, "add m.c", "2020-07-01T00:00:00")
    git(repo, "mv", "m.c", "n.c")
    moved = commit(repo, "move m.c to n.c", "2020-07-02T00:00:00")
    runs = {}
    for path in ("m.c", "n.c"):
        rp = PathReplay(parse_log(path_log(str(repo), path)),
                        run_min_alnum=20).run()
        runs[path] = [(sha, kind, whole, [rp.tokens[t].text for t in ids])
                      for sha, kind, whole, ids in rp.runs if sha == moved]
    assert runs["m.c"] == [(moved, "died", True, body)]
    assert runs["n.c"] == [(moved, "born", True, body)]


def test_a_line_kept_from_two_parents_becomes_a_copy(tmp_path):
    r = init_repo(tmp_path / "dup")
    write(r, "d.c", ["A"])
    first = commit(r, "base", "2021-01-01T00:00:00")
    git(r, "checkout", "-q", "-b", "keep")
    write(r, "d.c", ["Q", "A"])
    commit(r, "keep adds Q before A", "2021-01-02T00:00:00")
    git(r, "checkout", "-q", "main")
    write(r, "d.c", ["A", "P"])
    commit(r, "main adds P after A", "2021-01-03T00:00:00")
    git(r, "merge", "-q", "--no-ff", "--no-commit", "keep", check=False)
    assert (r / ".git" / "MERGE_HEAD").exists()
    write(r, "d.c", ["A", "P", "Q", "A"])
    commit(r, "merge keeps both", "2021-01-04T00:00:00")
    rp = real_replay(r, "d.c")
    for ids in rp.state.values():
        assert len(ids) == len(set(ids))
    born, _ = tip_born(rp, r, "d.c")
    assert born == blame(r, "d.c")
    assert any(t.copy_of is not None and t.born == first for t in rp.tokens)


def test_merge_that_brings_a_new_file_to_main(tmp_path):
    r = init_repo(tmp_path / "newfile")
    write(r, "other.c", ["o"])
    commit(r, "base", "2022-01-01T00:00:00")
    git(r, "checkout", "-q", "-b", "topic")
    write(r, "n.c", ["a", "b", "c"])
    commit(r, "topic adds n.c", "2022-01-02T00:00:00")
    git(r, "checkout", "-q", "main")
    write(r, "other.c", ["o", "p"])
    commit(r, "main changes other.c", "2022-01-03T00:00:00")
    git(r, "merge", "-q", "--no-ff", "--no-commit", "topic")
    write(r, "n.c", ["a", "b", "c", "fixed in merge"])
    commit(r, "merge topic with a fix", "2022-01-04T00:00:00")
    write(r, "n.c", ["a", "b", "c", "fixed in merge", "later"])
    commit(r, "later", "2022-01-05T00:00:00")
    rp = real_replay(r, "n.c")
    born, _ = tip_born(rp, r, "n.c")
    assert born == blame(r, "n.c")


def test_path_log_drops_a_merge_that_brings_no_change(tmp_path):
    # --simplify-merges: a side branch that never touches the path adds no
    # merge to its log, so no merge is replayed for nothing
    r = init_repo(tmp_path / "r")
    write(r, "a.c", ["a"])
    commit(r, "base", "2020-01-01T00:00:00")
    git(r, "checkout", "-q", "-b", "side")
    write(r, "o.c", ["o"])
    commit(r, "side", "2020-01-02T00:00:00")
    git(r, "checkout", "-q", "main")
    write(r, "a.c", ["a", "m"])
    commit(r, "main", "2020-01-03T00:00:00")
    git(r, "merge", "-q", "--no-ff", "-m", "merge", "side")
    assert [len(c.parents) for c in parse_log(path_log(str(r), "a.c"))] \
        == [0, 1]


def test_a_commit_with_no_diff_keeps_the_parent_blob():
    # git log can list a commit with no diff for the path; it has the
    # parent's content, not an empty file
    a, b = "a" * 40, "b" * 40
    blob = "1" * 40
    text = (f"\x01{a} \t1\n:000000 100644 {NULL_BLOB} {blob} A\ta.c\n"
            "@@ -0,0 +1,2 @@\n+x\n+y\n"
            f"\x01{b} {a}\t2\n")
    commits = parse_log(text)
    rp = PathReplay(commits)
    rp.run()
    assert commits[1].blob == blob
    assert rp.state[b] == rp.state[a]
