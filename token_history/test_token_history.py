import argparse

import pytest

from replay import NULL_BLOB, GitError, diff_hunks
from test_replay import commit, git, write
from token_history import list_paths, mainline_changes, merge_parents


@pytest.fixture
def not_a_repo(tmp_path):
    d = tmp_path / "empty"
    d.mkdir()
    return str(d)


def test_git_failures_stop_the_run(not_a_repo):
    with pytest.raises(GitError):
        merge_parents(not_a_repo)
    with pytest.raises(GitError, match="not a git repository"):
        mainline_changes(not_a_repo, [])
    with pytest.raises(GitError):
        list_paths(argparse.Namespace(paths=None, repo=not_a_repo,
                                      pathspec=[]))


def test_the_null_blob_diffs_to_no_hunks(tmp_path):
    r = tmp_path / "r"
    r.mkdir()
    git(r, "init", "-q", "-b", "main")
    write(r, "a.c", ["x", "y"])
    commit(r, "a", "2020-01-01T00:00:00")
    blob = git(r, "rev-parse", "HEAD:a.c").strip()
    assert diff_hunks(str(r), blob, NULL_BLOB) == []
    assert diff_hunks(str(r), NULL_BLOB, blob) == []
    with pytest.raises(GitError):
        diff_hunks(str(r), blob, "1" * 40)


def test_a_good_repo_still_works(tmp_path):
    r = tmp_path / "r"
    r.mkdir()
    git(r, "init", "-q", "-b", "main")
    write(r, "a.c", ["x"])
    sha = commit(r, "a", "2020-01-01T00:00:00")
    assert merge_parents(str(r)) == {}
    assert list(mainline_changes(str(r), [])) == ["a.c"]
    assert mainline_changes(str(r), [])["a.c"][0][0] == sha
    assert list_paths(argparse.Namespace(paths=None, repo=str(r),
                                         pathspec=[])) == ["a.c"]
    (tmp_path / "paths").write_text("a.c\nREADME\n")
    assert list_paths(argparse.Namespace(paths=tmp_path / "paths")) == ["a.c"]


def test_an_error_in_one_path_is_a_record_not_a_stop(monkeypatch):
    import token_history

    def broken(path):
        raise ValueError("bad path")
    monkeypatch.setattr(token_history, "replay_path", broken)
    r = token_history.work("a.c")
    assert r["path"] == "a.c" and "ValueError" in r["error"]


def test_a_dead_cat_file_stops_the_run(not_a_repo, monkeypatch):
    import token_history
    src = token_history.GitSource(not_a_repo, {})
    monkeypatch.setattr(token_history, "replay_path",
                        lambda path: src.blob_at("HEAD"))
    with pytest.raises(GitError):
        token_history.work("a.c")
