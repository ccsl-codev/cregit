#!/usr/bin/env python3
"""Build history-<mode>.parquet from the parts that token_history.py wrote.

The move mode is chosen here, not in the replay, so a run can switch between
off, renames and moves without replaying the history again.

Usage: build.py --out DIR [--mode moves] [--min-alnum 100]
"""
import argparse
import glob
import os
import sys
import time

import duckdb

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from moves import MODES, link  # noqa: E402
from token_history import COLUMNS, RUN_COLUMNS, SEP, log  # noqa: E402

TYPES = dict(token_id="BIGINT", born_in_merge="INTEGER", copy_of="BIGINT",
             tip_index="BIGINT", origin_token_id="BIGINT",
             mainline_in_ts="BIGINT", mainline_out_ts="BIGINT",
             whole_file="INTEGER")


# A whole-file run of a large generated header is one line of several MB.
MAX_LINE = 16_000_000


def read_tsv(con, name, pattern, columns, key=None):
    """Load TSV parts; drop repeated rows (a path written twice by a resume).

    key: the columns that identify a row; the full-row DISTINCT runs only
    when they repeat, since it is costly on a whole kernel.
    """
    cols = ", ".join(f"'{c}': '{TYPES.get(c, 'VARCHAR')}'" for c in columns)
    keep_text = ", force_not_null=['token']" if "token" in columns else ""
    con.execute(f"""
        CREATE TABLE {name} AS SELECT * FROM read_csv(
            '{pattern}', delim='{SEP}', header=false, quote='', escape='',
            nullstr='', auto_detect=false, max_line_size={MAX_LINE}{keep_text},
            columns={{{cols}}})""")
    key = ", ".join(key or columns)
    rows, keys = con.execute(f"SELECT count(*), count(DISTINCT ({key})) "
                             f"FROM {name}").fetchone()
    if rows != keys:
        con.execute(f"CREATE OR REPLACE TABLE {name} AS "
                    f"SELECT DISTINCT * FROM {name}")


def resolve_origins(con):
    """links(file_path, token_id -> origin) to the first identity of each chain.

    Pointer jumping: each round replaces an origin by that origin's own
    origin, so a chain of n moves needs about log2(n) rounds. A move always
    points to an older token, so chains end.
    """
    rounds = 0
    while True:
        con.execute("""
            CREATE OR REPLACE TABLE next_links AS
            SELECT l.file_path, l.token_id,
                   coalesce(u.origin_path, l.origin_path) AS origin_path,
                   coalesce(u.origin_token_id, l.origin_token_id)
                     AS origin_token_id,
                   u.file_path IS NOT NULL AS jumped
            FROM links l LEFT JOIN links u
              ON u.file_path = l.origin_path
             AND u.token_id = l.origin_token_id""")
        jumped = con.execute("SELECT count(*) FILTER (WHERE jumped) "
                             "FROM next_links").fetchone()[0]
        con.execute("CREATE OR REPLACE TABLE links AS SELECT file_path, "
                    "token_id, origin_path, origin_token_id FROM next_links")
        con.execute("DROP TABLE next_links")
        rounds += 1
        if not jumped:
            return rounds


def commit_runs(con):
    """Yield (sha, runs, texts) for commits that have both kinds of run."""
    cur = con.execute("""
        WITH r AS (
            SELECT sha, kind, whole_file, file_path, token_ids,
                   -- a fixed order: with repeated code, the order of the
                   -- runs decides which copy a moved token links to
                   row_number() OVER (
                       ORDER BY sha, kind, file_path,
                                string_split(token_ids, ',')[1]::BIGINT)
                     AS run_no
            FROM runs
            WHERE sha IN (SELECT sha FROM runs GROUP BY sha
                          HAVING count(DISTINCT kind) = 2)),
        t AS (
            SELECT r.*, unnest(string_split(token_ids, ','))::BIGINT AS tid,
                   generate_subscripts(string_split(token_ids, ','), 1) AS pos
            FROM r)
        SELECT t.sha, t.run_no, t.kind, t.whole_file, t.file_path, t.tid,
               tok.token
        FROM t JOIN tokens tok
          ON tok.file_path = t.file_path AND tok.token_id = t.tid
        ORDER BY t.sha, t.run_no, t.pos""")
    sha, runs, texts, cur_run = None, [], {}, None
    while batch := cur.fetchmany(100_000):
        for s, run_no, kind, whole, path, tid, token in batch:
            if s != sha:
                if sha is not None:
                    yield sha, runs, texts
                sha, runs, texts, cur_run = s, [], {}, None
            if run_no != cur_run:
                runs.append((kind, bool(whole), path, []))
                cur_run = run_no
            runs[-1][3].append(tid)
            texts[(path, tid)] = token
    if sha is not None:
        yield sha, runs, texts


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", choices=MODES, default="moves")
    ap.add_argument("--min-alnum", type=int, default=100)
    ap.add_argument("--memory", default="6GB")
    ap.add_argument("--threads", type=int, default=4)
    args = ap.parse_args()
    t0 = time.time()
    con = duckdb.connect(os.path.join(args.out, "build.duckdb"))
    con.execute(f"SET memory_limit='{args.memory}'")
    con.execute(f"SET temp_directory='{os.path.join(args.out, 'tmp')}'")
    con.execute(f"SET threads={args.threads}")
    con.execute("SET preserve_insertion_order=false")
    for name in ("tokens", "runs", "links"):
        con.execute(f"DROP TABLE IF EXISTS {name}")
    read_tsv(con, "tokens", os.path.join(args.out, "part-*.tsv"), COLUMNS,
             key=["file_path", "token_id", "mainline_in_sha"])
    if glob.glob(os.path.join(args.out, "runs-*.tsv")):
        read_tsv(con, "runs", os.path.join(args.out, "runs-*.tsv"),
                 RUN_COLUMNS)
    else:
        con.execute("CREATE TABLE runs (sha VARCHAR, kind VARCHAR, "
                    "whole_file INTEGER, file_path VARCHAR, token_ids VARCHAR)")
    log(f"build: {con.execute('SELECT count(*) FROM tokens').fetchone()[0]:,}"
        f" token rows, {con.execute('SELECT count(*) FROM runs').fetchone()[0]:,}"
        " runs")

    # Links go to disk commit by commit: on a whole kernel they do not fit
    # in memory. A born token has one birth commit, so it has one link.
    links_file = os.path.join(args.out, f"links-{args.mode}.tsv")
    linked = commits = 0
    with open(links_file, "w", encoding="utf-8") as f:
        f.write(f"{SEP}{SEP}{SEP}\n")
        if args.mode != "off":
            for sha, runs, texts in commit_runs(con):
                for (p, t), (op, ot) in link(runs, texts.__getitem__,
                                             args.mode,
                                             args.min_alnum).items():
                    f.write(f"{p}{SEP}{t}{SEP}{op}{SEP}{ot}\n")
                    linked += 1
                commits += 1
                if commits % 20_000 == 0:
                    log(f"build: {commits:,} commits, {linked:,} links")
    log(f"build: mode {args.mode}, {linked:,} tokens linked in "
        f"{commits:,} commits")
    read_tsv(con, "links", links_file,
             ["file_path", "token_id", "origin_path", "origin_token_id"])
    con.execute("DELETE FROM links WHERE file_path IS NULL")
    log(f"build: move chains resolved in {resolve_origins(con)} rounds")

    target = os.path.join(args.out, f"history-{args.mode}.parquet")
    con.execute(f"""
        COPY (
            SELECT t.*,
                   coalesce(l.origin_path, t.file_path) AS origin_path,
                   coalesce(l.origin_token_id, t.token_id) AS origin_token_id,
                   coalesce(o.born_sha, t.born_sha) AS origin_born_sha,
                   '{args.mode}' AS move_mode
            FROM tokens t
            LEFT JOIN links l USING (file_path, token_id)
            LEFT JOIN (SELECT DISTINCT file_path, token_id, born_sha
                       FROM tokens) o
              ON o.file_path = l.origin_path AND o.token_id = l.origin_token_id
            ORDER BY t.file_path, t.token_id, t.mainline_in_ts
        ) TO '{target}' (FORMAT parquet, COMPRESSION zstd)""")
    log(f"build: wrote {target} in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
