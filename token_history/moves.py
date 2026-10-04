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
    died_text, died_key = [], []
    for kind, _, path, ids in eligible:
        if kind == "died":
            for t in ids:
                died_text.append(text_of((path, t)))
                died_key.append((path, t))
            died_text.append(SEPARATOR)
            died_key.append(None)
    blocks = []
    for kind, _, b_path, b_ids in eligible:
        if kind != "born" or not died_text:
            continue
        b_text = [text_of((b_path, t)) for t in b_ids]
        for i, j, n in matching_blocks(died_text, b_text):
            if mode == "renames" and died_key[i][0] == b_path:
                continue
            if mode == "moves" and alnum(died_text[i:i + n]) < min_alnum:
                continue
            blocks.append((n, died_key[i:i + n],
                           [(b_path, t) for t in b_ids[j:j + n]]))
    links, used = {}, set()
    for _, died, born in sorted(blocks, key=lambda x: -x[0]):
        for d, b in zip(died, born):
            if d not in used and b not in links:
                links[b] = d
                used.add(d)
    return links
