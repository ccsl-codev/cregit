"""Replay the history of one path of a cregit repository, token by token.

Each line of a cregit file is one token. A token is born in the commit whose
diff adds its line, and it keeps its identity while later diffs leave the line
unchanged. At a merge, a line takes its identity from the first parent that
has it unchanged, as `git blame` does; a line that no parent has is born in
the merge.
"""
import functools
import re
import subprocess
from dataclasses import dataclass, field

HEADER = "\x01"
NULL_BLOB = "0" * 40
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")
RAW = re.compile(r"^:\d+ \d+ ([0-9a-f]{40}) ([0-9a-f]{40}) ")


@dataclass
class Hunk:
    old_start: int
    old_len: int
    added: list = field(default_factory=list)


@dataclass
class Diff:
    old_blob: str = NULL_BLOB
    hunks: list = field(default_factory=list)


@dataclass
class Commit:
    sha: str
    parents: list
    ct: int
    blob: str = None
    diffs: list = field(default_factory=list)


def path_log(repo, path):
    return subprocess.run(
        ["git", "-C", repo, "log", "--full-history", "--simplify-merges",
         "--reverse", "--topo-order", "--parents", "-m", "-p", "-U0", "--raw",
         "--no-abbrev", "--no-renames", "--no-color",
         f"--format={HEADER}%H %P%x09%ct", "--", path],
        check=True, capture_output=True, text=True, errors="replace").stdout


def parse_log(text):
    """Commits in topological order; a merge holds one diff per parent."""
    commits, by_sha, cur, diff = [], {}, None, None
    for line in text.split("\n"):
        if line.startswith(HEADER):
            head, ct = line[1:].split("\t")
            sha, *parents = head.split()
            cur = by_sha.get(sha)
            if cur is None:
                cur = by_sha[sha] = Commit(sha, parents, int(ct))
                commits.append(cur)
            diff = Diff()
            cur.diffs.append(diff)
        elif cur is None:
            continue
        elif (m := RAW.match(line)):
            diff.old_blob, cur.blob = m.group(1), m.group(2)
        elif (m := HUNK.match(line)):
            old_len = 1 if m.group(2) is None else int(m.group(2))
            diff.hunks.append(Hunk(int(m.group(1)), old_len))
        elif diff.hunks and line.startswith("+") and not line.startswith("+++"):
            diff.hunks[-1].added.append(line[1:])
    return commits


class GitError(RuntimeError):
    """git exited with an error: the caller must not go on with no data."""


def git_out(repo, *args, check=True):
    """The output of one git command. check=False accepts a non-zero exit
    (for example rev-parse --verify of a missing object)."""
    proc = subprocess.run(["git", "-C", repo, *args], capture_output=True,
                          text=True, errors="replace")
    if check and proc.returncode:
        raise GitError(f"git {' '.join(args[:3])} exited {proc.returncode}: "
                       f"{proc.stderr.strip()[:300]}")
    return proc.stdout


def diff_hunks(repo, a, b):
    """The hunks from blob a to blob b. A null blob gives no hunks: git
    cannot diff it, and the replay treats that case as no change."""
    if NULL_BLOB in (a, b):
        return []
    return parse_hunks(git_out(repo, "diff", "-U0", "--no-color", a, b))


def parse_hunks(diff_text):
    return parse_log(f"{HEADER}x\t0\n{diff_text}")[0].diffs[0].hunks


def align(parent_len, hunks):
    """For each child line: the parent line index it keeps, or its new text."""
    child, pos = [], 0
    for h in hunks:
        start = h.old_start - 1 if h.old_len else h.old_start
        child.extend(range(pos, start))
        child.extend(("+", text) for text in h.added)
        pos = start + h.old_len
    child.extend(range(pos, parent_len))
    return child


@dataclass
class Token:
    text: str
    born: str
    born_in_merge: bool
    died: str = None
    copy_of: int = None


def alnum(texts):
    return sum(ch.isalnum() for text in texts for ch in text)


def unique(items):
    return list(dict.fromkeys(items))


class PathReplay:
    """Token identities along the history of one path.

    real_parents, blob_at and diff_blobs let a merge try its real parents in
    their real order, as `git blame` does, when the simplified history dropped
    one of them.
    """

    def __init__(self, commits, real_parents=None, blob_at=None,
                 diff_blobs=None, run_min_alnum=None):
        self.commits = commits
        self.tokens = []
        self.state = {}
        self.blob_nodes = {}
        self.order = {c.sha: i for i, c in enumerate(commits)}
        self.real_parents = real_parents
        self.blob_at = blob_at and functools.cache(blob_at)
        self.diff_blobs = diff_blobs
        self.run_min_alnum = run_min_alnum
        self.runs = []
        self.missing_parents = 0
        self.unaligned_merges = 0

    def born(self, text, commit, in_merge):
        self.tokens.append(Token(text, commit.sha, in_merge))
        return len(self.tokens) - 1

    def copy(self, t):
        """A second line with the same identity: a new token, same birth."""
        orig = self.tokens[t]
        self.tokens.append(Token(orig.text, orig.born, orig.born_in_merge,
                                 copy_of=t))
        return len(self.tokens) - 1

    def parent_ids(self, sha):
        if sha not in self.state:
            self.missing_parents += 1
            return []
        return self.state[sha]

    def blob_of(self, sha):
        i = self.order.get(sha)
        return NULL_BLOB if i is None else self.commits[i].blob

    def parents_of(self, c):
        return self.real_parents(c.sha) if self.real_parents else c.parents

    def merge_blobs(self, c, real):
        blobs = unique(self.blob_of(p) for p in c.parents)
        if real == c.parents:
            return blobs
        real_blobs = unique(self.blob_at(p) for p in real)
        return blobs if real_blobs == blobs else real_blobs

    def ids_of(self, blob, c):
        for p in c.parents:
            if self.blob_of(p) == blob:
                return self.parent_ids(p)
        return self.state_of_blob(blob, c.ct)

    def sources(self, c):
        """(parent ids, alignment) per parent, in the order blame tries them."""
        real = self.parents_of(c)
        if len(real) <= 1 or not c.parents:
            ids = self.parent_ids(c.parents[0]) if c.parents else []
            return [(ids, align(len(ids), c.diffs[0].hunks))]
        blobs = self.merge_blobs(c, real)
        if c.blob in blobs:
            ids = self.ids_of(c.blob, c)
            if ids is not None:
                return [(ids, align(len(ids), []))]
        by_blob = {d.old_blob: d.hunks for d in c.diffs}
        out = []
        for blob in blobs:
            if blob == NULL_BLOB:
                continue
            ids = self.ids_of(blob, c)
            if blob in by_blob:
                hunks = by_blob[blob]
            elif blob == c.blob:
                hunks = []
            elif self.diff_blobs and ids is not None:
                hunks = self.diff_blobs(blob, c.blob)
            else:
                ids = None
            if ids is None:
                self.unaligned_merges += 1
                continue
            out.append((ids, align(len(ids), hunks)))
        if not out:
            ids = self.parent_ids(c.parents[0])
            out = [(ids, align(len(ids), c.diffs[0].hunks))]
        return out

    def step(self, c):
        sources = self.sources(c)
        in_merge = len(sources) > 1 or len(c.parents) > 1
        base_ids, base = sources[0]
        ids, used = [], set()
        for i, line in enumerate(base):
            if not isinstance(line, tuple):
                kept = base_ids[line]
            else:
                kept = next((s_ids[m[i]] for s_ids, m in sources[1:]
                             if i < len(m) and not isinstance(m[i], tuple)),
                            None)
            if kept is None:
                kept = self.born(line[1], c, in_merge)
            elif kept in used:
                kept = self.copy(kept)
            used.add(kept)
            ids.append(kept)
        if not in_merge:
            alive = set(ids)
            for t in base_ids:
                if t not in alive and self.tokens[t].died is None:
                    self.tokens[t].died = c.sha
            self.add_runs(c, "died", [(j, t) for j, t in enumerate(base_ids)
                                      if t not in alive])
        self.add_runs(c, "born", [(i, t) for i, t in enumerate(ids)
                                  if self.tokens[t].born == c.sha])
        self.state[c.sha] = ids

    def resolve_blobs(self):
        for c in self.commits:
            if c.blob is None:
                c.blob = self.blob_of(c.parents[0]) if c.parents else NULL_BLOB

    def latest_node(self, blob, before_ct):
        nodes = [s for s in self.blob_nodes.get(blob, ())
                 if self.commits[self.order[s]].ct <= before_ct]
        nodes = nodes or self.blob_nodes.get(blob, ())
        return max(nodes, key=self.order.get) if nodes else None

    def add_runs(self, c, kind, positioned):
        """Record runs of adjacent tokens that are long enough to move.

        positioned: [(position in the file, token id)], in file order.
        """
        if self.run_min_alnum is None:
            return
        if kind == "died":
            whole = c.blob == NULL_BLOB
        else:
            whole = all(self.blob_of(p) == NULL_BLOB for p in c.parents)
        run, last = [], None
        for pos, t in positioned + [(None, None)]:
            if run and (pos is None or pos != last + 1):
                if alnum(self.tokens[i].text for i in run) >= self.run_min_alnum:
                    self.runs.append((c.sha, kind, whole, run))
                run = []
            if t is not None:
                run.append(t)
            last = pos

    def run(self, mainline=None):
        """Replay every commit. With mainline changes, free unneeded states."""
        self.resolve_blobs()
        for c in self.commits:
            self.blob_nodes.setdefault(c.blob, []).append(c.sha)
        if mainline is None:
            for c in self.commits:
                self.step(c)
            return self
        keep = {self.latest_node(b, ct) for _, ct, b in mainline}
        for c in self.commits:
            real = self.parents_of(c)
            if len(real) > 1 and real != c.parents:
                keep |= {self.latest_node(self.blob_at(p), c.ct)
                         for p in real}
        pending = {}
        for c in self.commits:
            for p in c.parents:
                pending[p] = pending.get(p, 0) + 1
        for c in self.commits:
            self.step(c)
            for p in c.parents:
                pending[p] -= 1
                if not pending[p] and p not in keep:
                    self.state.pop(p, None)
        return self

    def state_of_blob(self, blob, before_ct):
        """The latest node with this blob, committed at or before a time."""
        if blob == NULL_BLOB:
            return []
        node = self.latest_node(blob, before_ct)
        return None if node is None else self.state.get(node)

    def tip_positions(self, changes):
        """{token: position} in the path's file at the last mainline change."""
        if not changes:
            return {}
        sha, ct, blob = changes[-1]
        ids = self.state_of_blob(blob, ct) or []
        return {t: i for i, t in enumerate(ids)}

    def mainline_intervals(self, changes):
        """Intervals on the mainline: {token: [(in_sha, in_ct, out_sha, out_ct)]}.

        changes: [(sha, ct, blob)] of the first-parent commits that changed
        this path, oldest first.
        """
        intervals, alive, unmapped = {}, set(), 0
        for sha, ct, blob in changes:
            ids = self.state_of_blob(blob, ct)
            if ids is None:
                unmapped += 1
                continue
            now = set(ids)
            for t in now - alive:
                intervals.setdefault(t, []).append([sha, ct, None, None])
            for t in alive - now:
                intervals[t][-1][2:] = [sha, ct]
            alive = now
        self.unmapped_mainline = unmapped
        return intervals
