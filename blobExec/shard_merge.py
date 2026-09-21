#!/usr/bin/env python3
"""Tier-3 shard merge + serial re-fold for cregit blobExec.

Combine N tree-only shards -- each produced by

    blobExec --shard=k/N <src.git> <shard_k/dst.git> <shard_k/blobmap.db> <cmd> <mask>

-- into a single destination repo + mapping DB whose commit_map is
BYTE-IDENTICAL to a single-process serial run, then rebuild that commit_map
with one stock serial re-fold.

Why this is correct:
  * blob_map and tree_map are content-addressed (tokenizer output depends only
    on blob content + file extension), so the UNION of every shard's maps
    equals a single full run's maps -- INSERT OR IGNORE is conflict-free.
  * The only cross-commit dependency is the commit hash chain (a rewritten
    commit embeds its rewritten parents' SHAs). Shards omit the fold entirely.
  * With a fully warmed tree_map, a stock serial run short-circuits every tree
    at the top of buildTreePlan (TreeExisting), so the re-fold tokenizes NOTHING
    and only does getTree + parent lookup + buildCommit + putCommit per commit,
    reproducing the exact serial fold -> identical commit SHAs.

Steps:
  1. final/blobmap.db:
       cold (no --warm-db): copy shard 0's DB for its schema, then wipe
                            commit_map + ref_map (the re-fold rebuilds them).
       warm (--warm-db D):  copy D verbatim -- its commit_map/ref_map prefix is
                            preserved so the re-fold is INCREMENTAL (only folds
                            the not-yet-mapped suffix).
     Then INSERT OR IGNORE every shard's blob_map + tree_map.
  2. final/dst.git: git init --bare, then physically union loose objects
     (content-addressed -> cp -n safe) + any packs (renamed) from the optional
     warm dst.git and every shard's dst.git. Shards are NOT gc/pruned first
     (their trees/blobs are unreferenced until the re-fold writes commits).
  3. Re-fold: stock serial `blobExec <src> final/dst.git final/blobmap.db cmd
     mask` (no --shard/--pipeline). WARNS (non-fatal) if blobsRunThroughCommand
     != 0 -- a non-zero count means a tree_map gap (shards did not cover every
     commit); the run still self-heals by re-tokenizing deterministically.
  4. git repack -adf + fsck the final repo so it is self-contained.

Run inside `devenv shell` so the re-fold has perl/srcml/ctags on PATH (only
actually needed if step 3 has to re-tokenize a gap; a complete merge does not).
"""
import argparse
import glob
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time


BLOBEXEC_TIMEOUT_RC = 4
BLOBEXEC_STALL_RC = 5


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def log(msg):
    print(f"[shard_merge] {msg}", flush=True)


def build_final_db(final_db, shard_dbs, warm_db):
    """Seed the final DB then union every shard's content-addressed maps."""
    if warm_db:
        log(f"seeding final DB from warm base {warm_db} (incremental re-fold)")
        shutil.copyfile(warm_db, final_db)
    else:
        log(f"seeding final DB schema from shard 0 {shard_dbs[0]} (cold re-fold)")
        shutil.copyfile(shard_dbs[0], final_db)

    con = sqlite3.connect(final_db)
    try:
        if not warm_db:
            # Cold merge: the re-fold must rebuild the whole hash chain, so start
            # commit_map/ref_map empty (shards never wrote them, but be explicit).
            con.execute("DELETE FROM ref_map")
            con.execute("DELETE FROM commit_map")
            con.commit()
        for i, sdb in enumerate(shard_dbs):
            con.execute("ATTACH ? AS s", (os.path.abspath(sdb),))
            con.execute("INSERT OR IGNORE INTO blob_map SELECT * FROM s.blob_map")
            con.execute("INSERT OR IGNORE INTO tree_map SELECT * FROM s.tree_map")
            con.commit()
            con.execute("DETACH s")
            log(f"unioned shard {i} maps from {sdb}")
        counts = {
            t: con.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
            for t in ("commit_map", "blob_map", "tree_map", "ref_map")
        }
    finally:
        con.close()
    log(f"final DB seeded: {counts}")
    return counts


_HEX2 = re.compile(r"^[0-9a-f]{2}$")


def union_objects(final_git, object_dirs):
    """Physically union loose objects (+ any packs) from each source repo's
    objects/ dir into a fresh bare final repo. Loose objects are SHA-1
    content-addressed, so identical objects have identical paths -> cp -n is a
    safe, conflict-free union. Packs (rare for jgit, which writes loose) are
    copied under unique names."""
    run(["git", "init", "--bare", "-q", final_git])
    dst_obj = os.path.join(final_git, "objects")
    os.makedirs(os.path.join(dst_obj, "pack"), exist_ok=True)
    for src_obj in object_dirs:
        if not os.path.isdir(src_obj):
            log(f"WARNING: object dir missing, skipping: {src_obj}")
            continue
        tag = os.path.basename(os.path.dirname(os.path.abspath(src_obj)))
        # loose fanout dirs (00..ff)
        for d in sorted(os.listdir(src_obj)):
            if _HEX2.match(d):
                run(["cp", "-rn", os.path.join(src_obj, d), dst_obj + os.sep])
        # packs, renamed to avoid cross-source basename clashes
        packdir = os.path.join(src_obj, "pack")
        if os.path.isdir(packdir):
            for pk in glob.glob(os.path.join(packdir, "*.pack")):
                base = os.path.basename(pk)[:-5]  # strip .pack
                for ext in (".pack", ".idx"):
                    s = pk[:-5] + ext
                    if os.path.exists(s):
                        shutil.copyfile(
                            s, os.path.join(dst_obj, "pack", f"pack-{tag}-{base}{ext}")
                        )
        log(f"unioned objects from {src_obj}")


def parse_stats(stdout):
    m = re.search(r"blobExec done: (.*)", stdout)
    if not m:
        return {}
    return dict(
        kv.split("=", 1) for kv in m.group(1).split() if "=" in kv
    )


def refold(java, jar, src, final_git, final_db, command, mask, tok_cmd, memo,
           blob_timeout=None, stall_timeout=None):
    env = dict(os.environ)
    env["BFG_TOKENIZE_CMD"] = tok_cmd
    env["BFG_MEMO_DIR"] = memo
    os.makedirs(memo, exist_ok=True)
    flags = []
    if blob_timeout:
        flags.append(f"--blob-timeout={blob_timeout}")
    if stall_timeout:
        flags.append(f"--stall-timeout={stall_timeout}")
    argv = [java, "-jar", jar] + flags + [src, final_git, final_db, command, mask]
    log("re-fold: " + " ".join(argv))
    t0 = time.time()
    r = subprocess.run(argv, env=env, capture_output=True, text=True)
    dt = time.time() - t0
    sys.stdout.write(r.stdout)
    if r.stderr:
        sys.stderr.write(r.stderr)
    if r.returncode in (BLOBEXEC_TIMEOUT_RC, BLOBEXEC_STALL_RC):
        log(f"re-fold exited {r.returncode} (incomplete but resumable) after {dt:.1f}s")
        sys.exit(r.returncode)
    r.check_returncode()
    stats = parse_stats(r.stdout)
    log(f"re-fold done in {dt:.1f}s: {stats}")
    ran = int(stats.get("blobsRunThroughCommand", "-1"))
    if ran > 0:
        log(
            f"WARNING: re-fold tokenized {ran} blobs -- tree_map was NOT fully "
            "warm (a shard gap). Output is still correct (deterministic "
            "re-tokenization) but the shards did not cover every commit."
        )
    return dt, stats


def finalize_repo(final_git):
    log("repack -adf + fsck")
    run(["git", "-C", final_git, "repack", "-adf"])
    run(["git", "-C", final_git, "fsck", "--full", "--connectivity-only"])


def main():
    ap = argparse.ArgumentParser(description="Tier-3 shard merge + serial re-fold")
    ap.add_argument("--src", required=True, help="bare source repo (read-only)")
    ap.add_argument(
        "--shard",
        dest="shards",
        action="append",
        required=True,
        metavar="DIR",
        help="shard dir containing dst.git + blobmap.db (repeat per shard)",
    )
    ap.add_argument("--final", required=True, help="output dir (creates dst.git + blobmap.db)")
    ap.add_argument("--jar", required=True, help="blobExec assembly jar")
    ap.add_argument("--command", required=True, help="per-blob tokenizer script")
    ap.add_argument("--mask", required=True, help="file mask regex (same as shards)")
    ap.add_argument("--tok-cmd", required=True, help="BFG_TOKENIZE_CMD for the re-fold")
    ap.add_argument("--memo", required=True, help="BFG_MEMO_DIR for the re-fold")
    ap.add_argument("--java", default=shutil.which("java") or "java")
    ap.add_argument("--warm-db", default=None, help="frozen prior blobmap.db to seed (incremental)")
    ap.add_argument("--warm-git", default=None, help="frozen prior dst.git to union objects from")
    ap.add_argument("--blob-timeout", default=None, metavar="SECONDS",
                    help="blobExec --blob-timeout for the re-fold (default: blobExec's own)")
    ap.add_argument("--stall-timeout", default=None, metavar="SECONDS",
                    help="blobExec --stall-timeout for the re-fold (default: blobExec's own)")
    args = ap.parse_args()

    shard_dirs = [os.path.abspath(d) for d in args.shards]
    shard_dbs = [os.path.join(d, "blobmap.db") for d in shard_dirs]
    shard_gits = [os.path.join(d, "dst.git") for d in shard_dirs]
    for db in shard_dbs:
        if not os.path.isfile(db):
            sys.exit(f"missing shard DB: {db}")

    final_dir = os.path.abspath(args.final)
    os.makedirs(final_dir, exist_ok=True)
    final_db = os.path.join(final_dir, "blobmap.db")
    final_git = os.path.join(final_dir, "dst.git")
    for stale in (final_db, final_git):
        if os.path.isdir(stale):
            shutil.rmtree(stale)
        elif os.path.exists(stale):
            os.remove(stale)

    t0 = time.time()
    build_final_db(final_db, shard_dbs, args.warm_db)

    object_dirs = []
    if args.warm_git:
        object_dirs.append(os.path.join(os.path.abspath(args.warm_git), "objects"))
    object_dirs += [os.path.join(g, "objects") for g in shard_gits]
    union_objects(final_git, object_dirs)
    union_dt = time.time() - t0

    refold_dt, stats = refold(
        args.java, os.path.abspath(args.jar), os.path.abspath(args.src),
        final_git, final_db, os.path.abspath(args.command), args.mask,
        args.tok_cmd, os.path.abspath(args.memo),
        blob_timeout=args.blob_timeout, stall_timeout=args.stall_timeout,
    )
    finalize_repo(final_git)

    log(f"MERGE COMPLETE: union={union_dt:.1f}s refold={refold_dt:.1f}s")
    log(f"final DB:  {final_db}")
    log(f"final git: {final_git}")
    print("MERGE_REFOLD_STATS " + " ".join(f"{k}={v}" for k, v in stats.items()))


if __name__ == "__main__":
    main()
