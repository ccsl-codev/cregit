#!/usr/bin/env python3
"""Author, person and firm of every cregit commit, as the tip dataset has them.

A history parquet names commits only. This writes one row per cregit commit
with the same columns and the same joins as generate_dataset phase 2, so a
history token joins its born_sha (or origin_born_sha) to the author and firm
that the tip dataset gives a surviving token of the same commit.

Usage: attribute.py --cregit-db X-cregit.db --persons-db X-persons.db
                    --firm-map affiliation.merged.csv
                    [--firm-canonical firm_canonical.csv]
                    --out commits.parquet [--dataset X-dataset.parquet]

With --dataset, every commit that the dataset also has is compared on its
person and firm, and the counts are printed.
"""
import argparse
import json
import os
import sys

import duckdb

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                "..", "generate_dataset"))
from generate_dataset import firm_sql, sql_literal  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--cregit-db", required=True)
    ap.add_argument("--persons-db", required=True)
    ap.add_argument("--firm-map", required=True)
    ap.add_argument("--firm-canonical")
    ap.add_argument("--out", required=True)
    ap.add_argument("--dataset")
    ap.add_argument("--memory", default="4GB")
    args = ap.parse_args()
    con = duckdb.connect()
    con.execute(f"SET memory_limit='{args.memory}'")
    con.execute("INSTALL sqlite_scanner; LOAD sqlite_scanner;")
    con.execute(f"CALL sqlite_attach({sql_literal(args.cregit_db)})")
    con.execute(f"CALL sqlite_attach({sql_literal(args.persons_db)})")
    firm_select, firm_join = firm_sql(args.firm_map, args.firm_canonical)
    con.execute(f"""
        COPY (
            SELECT
                c.cid                         AS cregit_commit_sha,
                coalesce(m.originalcid, c.cid) AS original_commit_sha,
                c.autname                     AS author_name,
                c.autemail                    AS author_email,
                c.autdate                     AS author_date,
                c.comdate                     AS committer_date,
                e.personid,
                coalesce(p.personname, e.personid) AS person_name,
                e.emailaddr                   AS person_email,
                e.domain                      AS person_domain,
{firm_select}
                coalesce(m.repo, '')          AS repo_tag
            FROM commits c
            LEFT JOIN commitmap m         ON c.cid = m.cid
            LEFT JOIN emails e            ON (c.autname = e.emailname
                                         AND c.autemail = e.emailaddr)
            LEFT JOIN persons p           ON e.personid = p.personid
{firm_join}            ORDER BY c.cid
        ) TO {sql_literal(args.out)} (FORMAT parquet, COMPRESSION zstd)""")
    out = dict(commits=con.execute(
        f"SELECT count(*), count(DISTINCT cregit_commit_sha) "
        f"FROM read_parquet({sql_literal(args.out)})").fetchone())
    if args.dataset:
        out.update(zip(["dataset_commits", "same_person", "same_firm",
                        "same_original"], con.execute(f"""
            WITH d AS (
                SELECT DISTINCT cregit_commit_sha, original_commit_sha,
                       personid, firm
                FROM read_parquet({sql_literal(args.dataset)}))
            SELECT count(*),
                   count(*) FILTER (WHERE a.personid IS NOT DISTINCT FROM d.personid),
                   count(*) FILTER (WHERE a.firm IS NOT DISTINCT FROM d.firm),
                   count(*) FILTER (WHERE a.original_commit_sha
                                    = d.original_commit_sha)
            FROM d LEFT JOIN read_parquet({sql_literal(args.out)}) a
              USING (cregit_commit_sha)""").fetchone()))
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
