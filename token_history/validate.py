#!/usr/bin/env python3
"""Compare the tip of a history parquet with the tip dataset parquet.

Every token alive at the tip must be a row of the dataset at the same file and
token_index, with the same text; the share whose origin commit equals the
dataset's cregit_commit_sha is the agreement with its blame.

Usage: validate.py --history history-moves.parquet --dataset X-dataset.parquet
"""
import argparse
import json

import duckdb


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[1])
    ap.add_argument("--history", required=True)
    ap.add_argument("--dataset", required=True)
    args = ap.parse_args()
    con = duckdb.connect()
    con.execute("SET memory_limit='6GB'")
    con.execute(f"""
        CREATE VIEW h AS SELECT * FROM read_parquet('{args.history}')
        WHERE tip_index IS NOT NULL AND mainline_out_sha IS NULL""")
    con.execute(f"""
        CREATE VIEW d AS SELECT file_path, token_index, cregit_commit_sha,
               token_type, token_value
        FROM read_parquet('{args.dataset}')
        WHERE file_path IN (SELECT DISTINCT file_path FROM h)""")
    row = con.execute("""
        SELECT
          (SELECT count(*) FROM h) AS history_tip,
          (SELECT count(*) FROM d) AS dataset_rows,
          count(*) AS joined,
          count(*) FILTER (WHERE h.token IN (d.token_type, d.token_value,
                                              d.token_type || '|' || d.token_value))
            AS same_text,
          count(*) FILTER (WHERE h.origin_born_sha = d.cregit_commit_sha)
            AS same_origin,
          count(*) FILTER (WHERE h.born_sha = d.cregit_commit_sha) AS same_born
        FROM h JOIN d ON d.file_path = h.file_path
                     AND d.token_index = h.tip_index""").fetchone()
    names = ["history_tip", "dataset_rows", "joined", "same_text",
             "same_origin", "same_born"]
    out = dict(zip(names, row))
    out["origin_agreement_pct"] = round(100 * out["same_origin"]
                                        / max(out["joined"], 1), 2)
    out["files"] = con.execute(
        "SELECT count(DISTINCT file_path) FROM h").fetchone()[0]
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
