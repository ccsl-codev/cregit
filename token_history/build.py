#!/usr/bin/env python3
"""Build history-<mode>.parquet from the parts that token_history.py wrote.

The move mode is chosen here, not in the replay, so a run can switch between
off, renames and moves without replaying the history again.

Usage: build.py --out DIR [--mode moves] [--min-alnum 100] [--reuse]

--reuse keeps the tokens and runs tables of DIR/build.duckdb and a finished
links-<mode>.tsv, so a build that failed after the link pass restarts at the
chain resolution.
"""
import argparse
import glob
import os
import shutil
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


def in_chunk(column, k, chunks):
    return f"hash({column}) % {chunks} = {k}"


def write_history(con, target, mode, chunks):
    """Tokens with their resolved origin, written in chunks of paths.

    On a whole kernel, one join of every token with every link does not fit
    in memory. Each chunk joins only the paths whose hash falls in it; the
    parts are then copied into one parquet (no row order is kept).
    """
    con.execute("CREATE OR REPLACE TABLE origins (file_path VARCHAR, "
                "token_id BIGINT, origin_path VARCHAR, origin_token_id BIGINT, "
                "origin_born_sha VARCHAR)")
    # A token has one born_sha on all its rows, so the DISTINCT runs on the
    # join output (a few rows per link), not on the tokens of the chunk.
    for k in range(chunks):
        con.execute(f"""
            INSERT INTO origins
            SELECT DISTINCT l.file_path, l.token_id, l.origin_path,
                   l.origin_token_id, o.born_sha
            FROM (SELECT * FROM links
                  WHERE {in_chunk('origin_path', k, chunks)}) l
            JOIN (SELECT file_path, token_id, born_sha FROM tokens
                  WHERE {in_chunk('file_path', k, chunks)}) o
              ON o.file_path = l.origin_path
             AND o.token_id = l.origin_token_id""")
        log(f"build: origins chunk {k + 1}/{chunks}")
    parts = os.path.join(os.path.dirname(target),
                         f".{os.path.basename(target)}.parts")
    shutil.rmtree(parts, ignore_errors=True)
    os.makedirs(parts)
    for k in range(chunks):
        con.execute(f"""
            COPY (
                SELECT t.*,
                       coalesce(o.origin_path, t.file_path) AS origin_path,
                       coalesce(o.origin_token_id, t.token_id)
                         AS origin_token_id,
                       coalesce(o.origin_born_sha, t.born_sha)
                         AS origin_born_sha,
                       '{mode}' AS move_mode
                FROM (SELECT * FROM tokens
                      WHERE {in_chunk('file_path', k, chunks)}) t
                LEFT JOIN (SELECT * FROM origins
                           WHERE {in_chunk('file_path', k, chunks)}) o
                  USING (file_path, token_id)
            ) TO '{os.path.join(parts, f"{k:03d}.parquet")}'
              (FORMAT parquet, COMPRESSION zstd)""")
        log(f"build: write chunk {k + 1}/{chunks}")
    con.execute(f"COPY (SELECT * FROM read_parquet('{parts}/*.parquet')) "
                f"TO '{target}' (FORMAT parquet, COMPRESSION zstd)")
    shutil.rmtree(parts)
    con.execute("DROP TABLE origins")


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
               tok.token, t.pos
        FROM t JOIN tokens tok
          ON tok.file_path = t.file_path AND tok.token_id = t.tid
        ORDER BY t.sha, t.run_no, t.pos""")
    # tokens has one row per mainline interval, so a token with two
    # intervals comes twice at one place of its run. The rows of one place
    # are next to each other (the sort), and a token id has one text, so
    # keeping the first row of each place is a DISTINCT that costs no
    # memory (a DISTINCT over the 408 M kernel token rows does not fit).
    sha, runs, texts, cur_run, last = None, [], {}, None, None
    while batch := cur.fetchmany(100_000):
        for s, run_no, kind, whole, path, tid, token, pos in batch:
            if (s, run_no, pos) == last:
                continue
            last = (s, run_no, pos)
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


def load(con, out):
    read_tsv(con, "tokens", os.path.join(out, "part-*.tsv"), COLUMNS,
             key=["file_path", "token_id", "mainline_in_sha"])
    if glob.glob(os.path.join(out, "runs-*.tsv")):
        read_tsv(con, "runs", os.path.join(out, "runs-*.tsv"), RUN_COLUMNS)
    else:
        con.execute("CREATE TABLE runs (sha VARCHAR, kind VARCHAR, "
                    "whole_file INTEGER, file_path VARCHAR, token_ids VARCHAR)")


def write_links(con, links_file, mode, min_alnum):
    """The link pass, to a .part file that is renamed only at the end."""
    linked = commits = 0
    with open(links_file + ".part", "w", encoding="utf-8") as f:
        f.write(f"{SEP}{SEP}{SEP}\n")
        if mode != "off":
            for sha, runs, texts in commit_runs(con):
                for (p, t), (op, ot) in link(runs, texts.__getitem__, mode,
                                             min_alnum).items():
                    f.write(f"{p}{SEP}{t}{SEP}{op}{SEP}{ot}\n")
                    linked += 1
                commits += 1
                if commits % 20_000 == 0:
                    log(f"build: {commits:,} commits, {linked:,} links")
    os.replace(links_file + ".part", links_file)
    log(f"build: mode {mode}, {linked:,} tokens linked in {commits:,} commits")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", choices=MODES, default="moves")
    ap.add_argument("--min-alnum", type=int, default=100)
    ap.add_argument("--memory", default="6GB")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--chunks", type=int, default=16,
                    help="path chunks for the final write")
    ap.add_argument("--reuse", action="store_true",
                    help="keep the loaded tables and a finished links file")
    args = ap.parse_args()
    t0 = time.time()
    con = duckdb.connect(os.path.join(args.out, "build.duckdb"))
    con.execute(f"SET memory_limit='{args.memory}'")
    con.execute(f"SET temp_directory='{os.path.join(args.out, 'tmp')}'")
    con.execute(f"SET threads={args.threads}")
    con.execute("SET preserve_insertion_order=false")
    tables = {n for n, in con.execute("SELECT table_name FROM "
                                      "duckdb_tables()").fetchall()}
    reuse = args.reuse and {"tokens", "runs"} <= tables
    for name in ("links",) if reuse else ("tokens", "runs", "links"):
        con.execute(f"DROP TABLE IF EXISTS {name}")
    if not reuse:
        load(con, args.out)
    log(f"build: {con.execute('SELECT count(*) FROM tokens').fetchone()[0]:,}"
        f" token rows, {con.execute('SELECT count(*) FROM runs').fetchone()[0]:,}"
        " runs")

    # Links go to disk commit by commit: on a whole kernel they do not fit
    # in memory. A born token has one birth commit, so it has one link.
    links_file = os.path.join(args.out, f"links-{args.mode}.tsv")
    if args.reuse and os.path.exists(links_file):
        log(f"build: reuse {links_file}")
    else:
        write_links(con, links_file, args.mode, args.min_alnum)
    read_tsv(con, "links", links_file,
             ["file_path", "token_id", "origin_path", "origin_token_id"])
    con.execute("DELETE FROM links WHERE file_path IS NULL")
    log(f"build: move chains resolved in {resolve_origins(con)} rounds")

    target = os.path.join(args.out, f"history-{args.mode}.parquet")
    write_history(con, target, args.mode, args.chunks)
    log(f"build: wrote {target} in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
