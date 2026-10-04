"""Link tokens that moved between files, so they keep their first identity:
within one commit, a run of born tokens that equals a run of died tokens is a
move. The README describes the modes."""
from collections import defaultdict

from replay import alnum

MODES = ("off", "renames", "moves")
ANCHOR = 4
MAX_CANDIDATES = 64


def matching_blocks(a, b):
    """Maximal equal blocks (i, j, size) of b in a, grown from k-token anchors
    that prefer to continue the previous block. Linear in practice, where
    difflib is quadratic on runs full of repeated tokens."""
    k = min(ANCHOR, len(a), len(b))
    if not k:
        return []
    index = defaultdict(list)
    for i in range(len(a) - k + 1):
        cands = index[tuple(a[i:i + k])]
        if len(cands) < MAX_CANDIDATES:
            cands.append(i)
    blocks, j, next_i = [], 0, None
    while j <= len(b) - k:
        cands = index.get(tuple(b[j:j + k]))
        if not cands:
            j += 1
            continue
        i = next_i if next_i in cands else cands[0]
        n = k
        while i + n < len(a) and j + n < len(b) and a[i + n] == b[j + n]:
            n += 1
        blocks.append((i, j, n))
        j, next_i = j + n, i + n
    return blocks


SEPARATOR = object()


def link(runs, text_of, mode, min_alnum):
    """{(path, born id): (path, died id)} for one commit's runs.
    runs: [(kind, whole_file, path, ids)]; text_of((path, id)): its text."""
    if mode == "off":
        return {}
    eligible = [r for r in runs if mode == "moves" or r[1]]
    died = died_sequence(eligible, text_of)
    if not died[0]:
        return {}
    return longest_first([
        block for kind, _, path, ids in eligible if kind == "born"
        for block in born_blocks(path, ids, died, text_of, mode, min_alnum)])


def born_blocks(path, ids, died, text_of, mode, min_alnum):
    """(size, died keys, born keys) of each move into one born run."""
    died_text, died_key = died
    born_text = [text_of((path, t)) for t in ids]
    return [(n, died_key[i:i + n], [(path, t) for t in ids[j:j + n]])
            for i, j, n in matching_blocks(died_text, born_text)
            if is_move(mode, min_alnum, died_key[i][0], path,
                       died_text[i:i + n])]


def died_sequence(runs, text_of):
    """The texts and keys of every died run, a SEPARATOR between two runs."""
    texts, keys = [], []
    for kind, _, path, ids in runs:
        if kind == "died":
            texts += [text_of((path, t)) for t in ids] + [SEPARATOR]
            keys += [(path, t) for t in ids] + [None]
    return texts, keys


def is_move(mode, min_alnum, died_path, born_path, texts):
    if mode == "renames" and died_path == born_path:
        return False
    return mode != "moves" or alnum(texts) >= min_alnum


def longest_first(blocks):
    """Link the longest blocks first, each died and born token once."""
    links, used = {}, set()
    for _, died, born in sorted(blocks, key=lambda x: -x[0]):
        for d, b in zip(died, born):
            if d not in used and b not in links:
                links[b] = d
                used.add(d)
    return links
