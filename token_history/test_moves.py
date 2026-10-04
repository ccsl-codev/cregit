import pytest

from moves import link, matching_blocks


RUN = ["int", "alpha_long_identifier", ";", "beta_long_identifier"]


def runs_and_text(moved_whole=True):
    tx = {}
    for i, t in enumerate(RUN):
        tx[("old.c", 10 + i)] = t
        tx[("new.c", i)] = t
    runs = [("died", moved_whole, "old.c", [10, 11, 12, 13]),
            ("born", moved_whole, "new.c", [0, 1, 2, 3])]
    return runs, tx.__getitem__


EACH_TOKEN = {("new.c", i): ("old.c", 10 + i) for i in range(4)}


@pytest.mark.parametrize("mode, whole, min_alnum, links", [
    ("off", True, 10, {}),
    ("moves", False, 10, EACH_TOKEN),
    ("moves", False, 10_000, {}),
    ("renames", False, 10, {}),
    ("renames", True, 10, EACH_TOKEN),
], ids=["off links nothing", "moves link each token",
        "short runs do not move", "renames need a deleted file",
        "renames link a whole file"])
def test_link_modes(mode, whole, min_alnum, links):
    runs, tx = runs_and_text(moved_whole=whole)
    assert link(runs, tx, mode, min_alnum) == links


def test_matching_blocks_on_a_long_repetitive_run():
    import time
    a = [";", "}", "x", ";"] * 20000 + ["unique_tail_token"] * 10
    b = ["new"] + a
    t = time.time()
    blocks = matching_blocks(a, b)
    assert time.time() - t < 5
    assert sum(n for _, _, n in blocks) == len(a)
    assert blocks[0][:2] == (0, 1)


def test_a_block_never_spans_two_died_runs():
    a = [f"a_token_{i}" for i in range(4)]
    b = [f"b_token_{i}" for i in range(4)]
    tx = {("a.c", i): t for i, t in enumerate(a)}
    tx |= {("b.c", i): t for i, t in enumerate(b)}
    tx |= {("n.c", i): t for i, t in enumerate(a + b)}
    runs = [("died", False, "a.c", [0, 1, 2, 3]),
            ("died", False, "b.c", [0, 1, 2, 3]),
            ("born", False, "n.c", list(range(8)))]
    got = link(runs, tx.__getitem__, "moves", 5)
    assert [got[("n.c", i)] for i in range(8)] == (
        [("a.c", i) for i in range(4)] + [("b.c", i) for i in range(4)])
