#!/usr/bin/env python3
"""Build history-<mode>.parquet from the parts that token_history.py wrote."""
import argparse
import glob
import os
import shutil
import time
from itertools import groupby
from operator import itemgetter

import duckdb

from moves import MODES, link
from token_history import COLUMNS, RUN_COLUMNS, SEP, log

TYPES = dict(token_id="BIGINT", born_in_merge="INTEGER", copy_of="BIGINT",
             tip_index="BIGINT", origin_token_id="BIGINT",
             mainline_in_ts="BIGINT", mainline_out_ts="BIGINT",
             whole_file="INTEGER")


# A whole-file run of a large generated header is one line of several MB.
MAX_LINE = 16_000_000


def read_tsv(con, name, pattern, columns):
    """Load TSV parts. A run that stopped after the rows of a path, before
    paths-done.txt, writes the path again on resume, in a new part file:
    keep each path from one file only, the one with the most rows."""
    cols = ", ".join(f"'{c}': '{TYPES.get(c, 'VARCHAR')}'" for c in columns)
    keep_text = ", force_not_null=['token']" if "token" in columns else ""
    con.execute(f"""
        CREATE TABLE {name} AS SELECT * FROM read_csv(
            '{pattern}', delim='{SEP}', header=false, quote='', escape='',
            nullstr='', auto_detect=false, max_line_size={MAX_LINE}{keep_text},
            filename=true, columns={{{cols}}})""")
    con.execute(f"""
        DELETE FROM {name} t USING (
            SELECT file_path, arg_max(filename, n) AS keep
            FROM (SELECT file_path, filename, count(*) AS n
                  FROM {name} GROUP BY ALL)
            GROUP BY file_path HAVING count(*) > 1) d
        WHERE t.file_path = d.file_path AND t.filename <> d.keep""")
    con.execute(f"ALTER TABLE {name} DROP COLUMN filename")


def resolve_origins(con):
    """Point each link to the first identity of its chain by pointer jumping:
    about log2(n) rounds for a chain of n moves. A move always points to an
    older token, so chains end."""
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
    """Tokens with their resolved origin, in chunks of paths: on a whole kernel
    one join of every token with every link does not fit in memory. The chunk
    parquets are then copied into one (no row order is kept)."""
    fill_origins(con, chunks)
    parts = os.path.join(os.path.dirname(target),
                         f".{os.path.basename(target)}.parts")
    shutil.rmtree(parts, ignore_errors=True)
    os.makedirs(parts)
    for k in range(chunks):
        copy_chunk(con, os.path.join(parts, f"{k:03d}.parquet"), mode, k,
                   chunks)
        log(f"build: write chunk {k + 1}/{chunks}")
    con.execute(f"COPY (SELECT * FROM read_parquet('{parts}/*.parquet')) "
                f"TO '{target}' (FORMAT parquet, COMPRESSION zstd)")
    shutil.rmtree(parts)
    con.execute("DROP TABLE origins")


def fill_origins(con, chunks):
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


def copy_chunk(con, part, mode, k, chunks):
    con.execute(f"""
        COPY (
            SELECT t.*,
                   coalesce(o.origin_path, t.file_path) AS origin_path,
                   coalesce(o.origin_token_id, t.token_id) AS origin_token_id,
                   coalesce(o.origin_born_sha, t.born_sha) AS origin_born_sha,
                   '{mode}' AS move_mode
            FROM (SELECT * FROM tokens
                  WHERE {in_chunk('file_path', k, chunks)}) t
            LEFT JOIN (SELECT * FROM origins
                       WHERE {in_chunk('file_path', k, chunks)}) o
              USING (file_path, token_id)
        ) TO '{part}' (FORMAT parquet, COMPRESSION zstd)""")


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
    # tokens has a row per mainline interval: keep the first row of each
    # place (the sort puts them together), a DISTINCT that needs no memory
    places = (next(rows) for _, rows in
              groupby(fetch_rows(cur), key=itemgetter(0, 1, 7)))
    for sha, rows in groupby(places, key=itemgetter(0)):
        runs, texts = [], {}
        for _, run in groupby(rows, key=itemgetter(1)):
            run = list(run)
            _, _, kind, whole, path = run[0][:5]
            runs.append((kind, bool(whole), path, [r[5] for r in run]))
            texts.update(((path, r[5]), r[6]) for r in run)
        yield sha, runs, texts


def fetch_rows(cur):
    while batch := cur.fetchmany(100_000):
        yield from batch


def load(con, out):
    if not glob.glob(os.path.join(out, "part-*.tsv")):
        raise SystemExit(f"build: no part-*.tsv in {out}: run token_history.py")
    read_tsv(con, "tokens", os.path.join(out, "part-*.tsv"), COLUMNS)
    if glob.glob(os.path.join(out, "runs-*.tsv")):
        read_tsv(con, "runs", os.path.join(out, "runs-*.tsv"), RUN_COLUMNS)
    else:
        con.execute("CREATE TABLE runs (sha VARCHAR, kind VARCHAR, "
                    "whole_file INTEGER, file_path VARCHAR, token_ids VARCHAR)")


def write_links(con, links_file, mode, min_alnum):
    """Links go to disk commit by commit: on a whole kernel they do not fit
    in memory. A born token has one birth commit, so it has one link."""
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


def parse_args():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", choices=MODES, default="moves")
    ap.add_argument("--min-alnum", type=int, default=100)
    ap.add_argument("--memory", default="6GB")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--chunks", type=int, default=16,
                    help="path chunks for the final write")
    ap.add_argument("--reuse", action="store_true",
                    help="keep the loaded tables and a finished links file")
    return ap.parse_args()


def connect(args):
    con = duckdb.connect(os.path.join(args.out, "build.duckdb"))
    con.execute(f"SET memory_limit='{args.memory}'")
    con.execute(f"SET temp_directory='{os.path.join(args.out, 'tmp')}'")
    con.execute(f"SET threads={args.threads}")
    con.execute("SET preserve_insertion_order=false")
    return con


def load_tables(con, out, reuse):
    """Load tokens and runs unless reuse finds both; True when it did."""
    tables = {n for n, in con.execute("SELECT table_name FROM "
                                      "duckdb_tables()").fetchall()}
    reuse = reuse and {"tokens", "runs"} <= tables
    for name in ("links",) if reuse else ("tokens", "runs", "links"):
        con.execute(f"DROP TABLE IF EXISTS {name}")
    if not reuse:
        load(con, out)
    log(f"build: {con.execute('SELECT count(*) FROM tokens').fetchone()[0]:,}"
        f" token rows, {con.execute('SELECT count(*) FROM runs').fetchone()[0]:,}"
        " runs")
    return reuse


def load_links(con, args, reuse):
    links_file = os.path.join(args.out, f"links-{args.mode}.tsv")
    if reuse and os.path.exists(links_file):
        log(f"build: reuse {links_file}")
    else:
        write_links(con, links_file, args.mode, args.min_alnum)
    read_tsv(con, "links", links_file,
             ["file_path", "token_id", "origin_path", "origin_token_id"])
    con.execute("DELETE FROM links WHERE file_path IS NULL")


def main():
    args = parse_args()
    t0 = time.time()
    con = connect(args)
    load_links(con, args, load_tables(con, args.out, args.reuse))
    log(f"build: move chains resolved in {resolve_origins(con)} rounds")
    target = os.path.join(args.out, f"history-{args.mode}.parquet")
    write_history(con, target, args.mode, args.chunks)
    log(f"build: wrote {target} in {time.time() - t0:.0f} s")


if __name__ == "__main__":
    main()
