#!/usr/bin/env python3
"""Extract every token that ever existed in a cregit repository.

One row per token and mainline interval; the README describes the columns."""
import argparse
import json
import multiprocessing as mp
import os
import re
import subprocess
import time
import traceback
from datetime import datetime

from replay import (HEADER, NULL_BLOB, GitError, PathReplay, diff_hunks,
                    git_out, parse_log, path_log)

MASK = re.compile(r"\.[ch]$")
SEP = "\x1f"
COLUMNS = ["file_path", "token_id", "token", "born_sha", "born_in_merge",
           "copy_of", "died_sha", "tip_index", "mainline_in_sha", "mainline_in_ts",
           "mainline_out_sha", "mainline_out_ts"]
RUN_COLUMNS = ["sha", "kind", "whole_file", "file_path", "token_ids"]


def log(msg):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}", flush=True)


def merge_parents(repo):
    out = git_out(repo, "rev-list", "--merges", "--parents", "HEAD")
    return {sha: parents for sha, *parents in map(str.split, out.splitlines())}


def mainline_changes(repo, pathspec):
    """{path: [(sha, ct, new blob)]} along the first-parent history."""
    proc = subprocess.Popen(
        ["git", "-C", repo, "log", "--first-parent", "--reverse", "-m",
         "--raw", "--no-abbrev", "--no-renames", f"--format={HEADER}%H %ct",
         "HEAD", "--", *pathspec],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        errors="replace")
    changes, sha, ct = {}, None, 0
    for line in proc.stdout:
        if line.startswith(HEADER):
            sha, ct = line[1:].split()
            ct = int(ct)
        elif line.startswith(":"):
            meta, path = line.rstrip("\n").split("\t", 1)
            if MASK.search(path):
                changes.setdefault(path, []).append((sha, ct, meta.split()[3]))
    if proc.wait():
        raise GitError(f"git log --first-parent exited {proc.returncode}: "
                       f"{proc.stderr.read().strip()[:300]}")
    return changes


class GitSource:
    """Real parents from a shared map, blobs from one cat-file per worker."""

    def __init__(self, repo, merges):
        self.repo, self.merges = repo, merges
        self.cat = subprocess.Popen(
            ["git", "-C", repo, "cat-file", "--batch-check=%(objectname)"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        self.path = None

    def real_parents(self, sha):
        return self.merges.get(sha) or self.rewritten[sha]

    def blob_at(self, sha):
        try:
            self.cat.stdin.write(f"{sha}:{self.path}\n")
            self.cat.stdin.flush()
            line = self.cat.stdout.readline()
        except BrokenPipeError:
            line = ""
        if not line:  # cat-file died: every later path would fail too
            raise GitError(f"git cat-file exited {self.cat.wait()}")
        out = line.split()[0]
        return out if len(out) == 40 else NULL_BLOB

    def replay(self, path, mainline, run_min_alnum):
        self.path = path
        commits = parse_log(path_log(self.repo, path))
        self.rewritten = {c.sha: c.parents for c in commits}
        return PathReplay(commits, real_parents=self.real_parents,
                          blob_at=self.blob_at,
                          diff_blobs=lambda a, b: diff_hunks(self.repo, a, b),
                          run_min_alnum=run_min_alnum).run(mainline)


def rows(path, rp, intervals, tip):
    for i, t in enumerate(rp.tokens):
        base = [path, i, t.text, t.born, int(t.born_in_merge),
                "" if t.copy_of is None else t.copy_of, t.died or "",
                tip.get(i, "")]
        for span in intervals.get(i) or [["", "", "", ""]]:
            yield base + ["" if v is None else v for v in span]


WORKER = {}


def init_worker(repo, merges, changes, out_dir, run_min_alnum):
    # unique per worker, even when a later run reuses a pid
    tag = f"{os.getpid()}-{time.time_ns()}"
    WORKER.update(
        src=GitSource(repo, merges), changes=changes,
        run_min_alnum=run_min_alnum,
        out=open(os.path.join(out_dir, f"part-{tag}.tsv"), "a",
                 encoding="utf-8"),
        runs=open(os.path.join(out_dir, f"runs-{tag}.tsv"), "a",
                  encoding="utf-8"))


def clean(value):
    return str(value).replace(SEP, " ").replace("\n", " ")


def work(path):
    try:
        return replay_path(path)
    except GitError:
        raise  # git failed, not the path: stop the run
    except Exception:
        return dict(path=path, error=traceback.format_exc())


def replay_path(path):
    t0 = time.time()
    mainline = WORKER["changes"].get(path, [])
    rp = WORKER["src"].replay(path, mainline, WORKER["run_min_alnum"])
    lines = [SEP.join(map(clean, r)) + "\n"
             for r in rows(path, rp, rp.mainline_intervals(mainline),
                           rp.tip_positions(mainline))]
    runs = [SEP.join([sha, kind, str(int(whole)), clean(path),
                      ",".join(map(str, ids))]) + "\n"
            for sha, kind, whole, ids in rp.runs]
    WORKER["out"].write("".join(lines))
    WORKER["runs"].write("".join(runs))
    WORKER["out"].flush()
    WORKER["runs"].flush()
    return dict(path=path, tokens=len(rp.tokens), rows=len(lines),
                commits=len(rp.commits), seconds=round(time.time() - t0, 2),
                missing_parents=rp.missing_parents,
                unaligned_merges=rp.unaligned_merges,
                unmapped_mainline=rp.unmapped_mainline)


class Progress:
    def __init__(self, path, total, ping):
        self.path, self.total, self.ping = path, total, ping
        self.start = self.last = time.time()
        self.done, self.tokens, self.rows = 0, 0, 0
        self.flags = dict(missing_parents=0, unaligned_merges=0,
                          unmapped_mainline=0, errors=0)
        self.slowest = []

    def add(self, r):
        self.done += 1
        if "error" in r:
            self.flags["errors"] += 1
            r = dict(r, tokens=0, rows=0, seconds=0)
        self.tokens += r["tokens"]
        self.rows += r["rows"]
        for k in self.flags:
            self.flags[k] += r.get(k, 0)
        self.slowest = sorted(self.slowest + [(r["seconds"], r["path"])])[-5:]
        if time.time() - self.last >= self.ping or self.done == self.total:
            self.write()

    def write(self, state="running"):
        self.last = time.time()
        elapsed = self.last - self.start
        rate = self.done / elapsed if elapsed else 0
        eta = (self.total - self.done) / rate if rate else None
        snap = dict(state=state, paths_done=self.done,
                    paths_total=self.total, tokens=self.tokens,
                    rows=self.rows, elapsed_s=round(elapsed),
                    paths_per_min=round(60 * rate, 1),
                    eta_s=None if eta is None else round(eta),
                    updated=datetime.now().isoformat(timespec="seconds"),
                    **self.flags, slowest=self.slowest[::-1])
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(snap, f, indent=1)
        os.replace(tmp, self.path)
        eta_txt = "?" if eta is None else f"{eta / 3600:.1f} h"
        log(f"token_history: {self.done}/{self.total} paths, "
            f"{self.tokens:,} tokens, {60 * rate:.1f} paths/min, ETA {eta_txt}")


def list_paths(args):
    out = open(args.paths).read() if args.paths else git_out(
        args.repo, "log", "--diff-filter=A", "--name-only", "--no-renames",
        "-m", "--format=", "HEAD", "--", *args.pathspec)
    return sorted({p for p in out.splitlines() if MASK.search(p)})


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--paths")
    ap.add_argument("--jobs", type=int, default=8)
    ap.add_argument("--ping", type=int, default=60)
    ap.add_argument("--run-min-alnum", type=int, default=100,
                    help="shortest run that the move pass may link")
    ap.add_argument("pathspec", nargs="*")
    return ap.parse_args()


def read_done(done_file):
    if not os.path.exists(done_file):
        return set()
    return set(open(done_file).read().split("\n"))


def record(r, out_dir, done_out):
    if "error" in r:
        with open(os.path.join(out_dir, "errors.txt"), "a") as err:
            err.write(f"== {r['path']}\n{r['error']}\n")
        return
    done_out.write(r["path"] + "\n")
    done_out.flush()


def main():
    args = parse_args()
    os.makedirs(args.out, exist_ok=True)
    done_file = os.path.join(args.out, "paths-done.txt")
    done = read_done(done_file)
    paths = [p for p in list_paths(args) if p not in done]
    log(f"token_history: {len(paths)} paths to replay ({len(done)} done)")
    merges = merge_parents(args.repo)
    changes = mainline_changes(args.repo, args.pathspec)
    log(f"token_history: {len(merges):,} merges, mainline changes for "
        f"{len(changes):,} paths")
    progress = Progress(os.path.join(args.out, "history-progress.json"),
                        len(paths), args.ping)
    progress.write("starting")
    with open(done_file, "a") as done_out, mp.Pool(
            args.jobs, init_worker,
            (args.repo, merges, changes, args.out,
             args.run_min_alnum)) as pool:
        for r in pool.imap_unordered(work, paths):
            record(r, args.out, done_out)
            progress.add(r)
    progress.write("done")


if __name__ == "__main__":
    main()
