#!/usr/bin/env python3
"""Post-processing script: generate unified Parquet dataset from CreGit pipeline outputs.

#
# This Python implementation replicates token-matching logic from:
#   prettyPrint/prettyPrint-author.pl
#
# Keep the following in sync with the Perl original:
#
#   SourceReader      <-> Read_Src_Char / Un_Read_Char / Location / Skip_Whitespace  (Perl: lines 690-753)
#   skip_token        <-> Skip_Token      (Perl: line 669)
#   skip_comment      <-> Skip_Comment    (Perl: line 598)
#   skip_literal      <-> Skip_Literal    (Perl: line 517)
#   skip_whitespace   <-> Skip_Whitespace (Perl: line 736)
#   classify_and_skip <-> main loop       (Perl: lines 350-408)
#

Usage:
  uv run python3 generate_dataset/generate_dataset.py \
      --blame-dir  ../cregit-files/blame \
      --source-dir ../cregit-files/jq-original \
      --cregit-db  ../cregit-files/jq-cregit.db \
      --persons-db ../cregit-files/jq-persons.db \
      --output     ../cregit-files/jq-dataset.parquet
"""

import argparse
import logging
import os
import re
import sqlite3
import sys
import tempfile
from pathlib import Path

logger = logging.getLogger(__name__)


# ===================================================================
# SourceReader — replicates prettyPrint-author.pl's Read_Src_Char
# ===================================================================


class SourceReader:
    # Perl equivalent: Read_Src_Char / Un_Read_Char / Location (prettyPrint-author.pl:702)
    def __init__(self, source_text: str):
        self.source = source_text
        self.pos = 0
        self.line = 1
        self.col = 1
        self._prev_col = 0
        self._last_char: str | None = None

    def read_char(self) -> str | None:
        if self._last_char is not None:
            ch = self._last_char
            self._last_char = None
        elif self.pos >= len(self.source):
            return None
        else:
            ch = self.source[self.pos]
            self.pos += 1
        if ch == "\n":
            self.line += 1
            self._prev_col = self.col
            self.col = 1
        else:
            self.col += 1
        return ch

    def unread_char(self, ch: str) -> None:
        self._last_char = ch
        if ch == "\n":
            self.line -= 1
            self.col = self._prev_col
        else:
            self.col -= 1

    def location(self) -> tuple[int, int]:
        return (self.line, self.col)


# ===================================================================
# Skip functions — replicate prettyPrint-author.pl's Skip_*
# ===================================================================


def is_ws(ch: str | None) -> bool:
    return ch is not None and ch in " \t\n\r"


def consume(s: str) -> tuple[str, str]:
    return s[0], s[1:]


def skip_token(token_value: str, reader: SourceReader) -> str:
    # Perl equivalent: Skip_Token (prettyPrint-author.pl:669)
    text = ""
    stripped = re.sub(r"\s", "", token_value)
    remaining = len(stripped)
    while remaining > 0:
        ch = reader.read_char()
        if ch is None:
            break
        text += ch
        if not is_ws(ch):
            remaining -= 1
    return text


def skip_comment(token_value: str, reader: SourceReader) -> str:
    # Perl equivalent: Skip_Comment (prettyPrint-author.pl:598)
    text = ""
    while token_value:
        ch = reader.read_char()
        while ch is not None and is_ws(ch):
            text += ch
            ch = reader.read_char()
        if ch is None:
            break
        cT, token_value = consume(token_value)
        while is_ws(cT) and token_value:
            cT, token_value = consume(token_value)
        text += ch
        if (cT != ch) and not (cT == " " and ch == "\n"):
            logger.warning("Comment mismatch: token=%r source=%r", cT, ch)
    return text


def skip_literal(token_value: str, reader: SourceReader) -> str:
    # Perl equivalent: Skip_Literal (prettyPrint-author.pl:517)
    text = ""
    while token_value:
        cT, token_value = consume(token_value)
        ch = reader.read_char()
        if ch is None:
            break
        if is_ws(ch) and not is_ws(cT):
            while ch is not None and is_ws(ch):
                text += ch
                ch = reader.read_char()
        # The loop above consumes to end-of-source, so `ch` may be None again.
        # The guard at the top of the iteration cannot cover that, and without
        # this one `text += ch` below raises:
        #   TypeError: can only concatenate str (not "NoneType") to str
        # microsoft/terminal died that way after 2,204s of work. skip_comment
        # already re-checks in the same place; skip_literal did not.
        if ch is None:
            break
        if not is_ws(ch) and is_ws(cT):
            while token_value and is_ws(cT):
                cT, token_value = consume(token_value)
        text += ch
        if (cT != ch) and not (cT == " " and ch == "\n"):
            logger.warning("Literal mismatch: token=%r source=%r", cT, ch)
    return text


def skip_whitespace(reader: SourceReader) -> str:
    # Perl equivalent: Skip_Whitespace (prettyPrint-author.pl:736)
    text = ""
    while True:
        ch = reader.read_char()
        if ch is None or not is_ws(ch):
            if ch is not None:
                reader.unread_char(ch)
            break
        text += ch
    return text


def classify_and_skip(token_content: str, reader: SourceReader) -> dict:
    # Perl equivalent: token-classification logic in main loop (prettyPrint-author.pl:350)
    before_line, before_col = reader.location()

    if token_content.startswith("begin_unit"):
        return {
            "token_type": "begin_unit",
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if token_content in ("begin_function", "end_function"):
        return {
            "token_type": token_content,
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if token_content.startswith("DECL|"):
        parts = token_content.split("|")
        return {
            "token_type": "DECL",
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": parts[-1] if len(parts) >= 3 else None,
        }

    if token_content == "|":
        return {
            "token_type": "",
            "token_value": "",
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if not token_content:
        return {
            "token_type": "blank",
            "token_value": "",
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    if re.match(r"^(begin|end)_[a-z_]+$", token_content):
        return {
            "token_type": token_content,
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    m = re.match(r"^(.+?)\|(.+)$", token_content)
    if not m:
        return {
            "token_type": "unknown",
            "token_value": token_content,
            "source_text": "",
            "source_line": before_line,
            "source_col": before_col,
            "is_structural": 1,
            "func_name": None,
        }

    tok_type = m.group(1)
    tok_value = m.group(2)

    if tok_type == "comment":
        source_text = skip_comment(tok_value, reader)
    elif tok_type == "literal":
        source_text = skip_literal(tok_value, reader)
    else:
        source_text = skip_token(tok_value, reader)

    ws = skip_whitespace(reader)
    source_text += ws

    return {
        "token_type": tok_type,
        "token_value": tok_value,
        "source_text": source_text,
        "source_line": before_line,
        "source_col": before_col,
        "is_structural": 0,
        "func_name": None,
    }


# ===================================================================
# Blame file processing
# ===================================================================


def parse_blame_line(line: str) -> tuple[str, str] | None:
    line = line.rstrip("\n")
    if not line:
        return None
    parts = line.split(";", 2)
    if len(parts) < 2:
        return None
    commit_sha = parts[0]
    token_content = parts[2].lstrip("\t") if len(parts) > 2 else ""
    return commit_sha, token_content


def process_blame_file(
    blame_path: Path, source_path: Path, rel_path: str, db_cursor
) -> int:
    with open(blame_path, encoding="utf-8", errors="replace") as f:
        blame_lines = f.readlines()

    with open(source_path, encoding="utf-8", errors="replace") as f:
        source_text = f.read()

    reader = SourceReader(source_text)
    counted = [0]

    def rows():
        # Order matters. classify_and_skip advances `reader`, so the tokens have
        # to be consumed in file order. executemany walks this generator
        # sequentially, which keeps that order.
        for token_index, bline in enumerate(blame_lines):
            parsed = parse_blame_line(bline)
            if parsed is None:
                continue
            commit_sha, token_content = parsed

            info = classify_and_skip(token_content, reader)
            counted[0] += 1
            yield (
                rel_path,
                token_index,
                commit_sha,
                info["token_type"],
                info["token_value"],
                info["source_text"],
                info["source_line"],
                info["source_col"],
                info["is_structural"],
                info["func_name"],
            )

    # executemany over a generator, not one execute per token. Linux reaches
    # this step with hundreds of millions of tokens, and at that scale the
    # per-call Python overhead dominates. A generator keeps the memory bounded,
    # so a single large file cannot be held in a list.
    db_cursor.executemany(
        """INSERT INTO token_map
           (file_path, token_index, commit_sha, token_type, token_value,
            source_text, source_line, source_col, is_structural, func_name)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        rows(),
    )

    return counted[0]


# ===================================================================
# Main
# ===================================================================

# DuckDB's own default memory limit is 80% of *total* RAM. On a shared box the
# rest of that RAM is already taken, so 80% is more than what is free: the
# kernel kills the process before DuckDB decides to spill. The Linux run died
# that way on 2026-09-14, at 17.5 GB resident on a 30 GB host, in step 10.
# So the limit is explicit here.
DEFAULT_MEMORY_LIMIT = "8GB"

_MEMORY_LIMIT_RE = re.compile(
    r"^\d+(?:\.\d+)?\s?(?:B|K|M|G|T|KB|MB|GB|TB|KIB|MIB|GIB|TIB)$"
)


def parse_memory_limit(text):
    """Return text when DuckDB can read it as an absolute size, else raise.

    A percentage is refused on purpose. A percentage measures total RAM, and
    the free part is the part that matters. Accepting one reintroduces the
    setting that let the OOM killer win.
    """
    cleaned = text.strip()
    if cleaned.endswith("%"):
        raise ValueError(
            "--memory-limit takes an absolute size, not a percentage. "
            "A percentage measures total RAM, but only the free part is "
            "usable. Example: 8GB"
        )
    if not _MEMORY_LIMIT_RE.match(cleaned.upper()):
        raise ValueError(f"cannot read {text!r} as a memory size. Example: 8GB")
    return cleaned


# The 29 per-project constants, in dataset order. This list mirrors META_FIELDS
# in cregit-token-pipeline/project_meta.py, which writes the sidecar, and
# EXPECTED_COLUMNS in cregit-token-pipeline/validate_schema.py, which gates the
# corpus. All three must carry the same names in the same order; the pipeline
# repo has a test (tests/test_meta_field_drift.py) that reads this tuple out of
# this file and fails if it drifts, because the two copies live in different
# repositories and nothing else keeps them in step.
#
# The sidecar is written with sort_keys=True, so it is alphabetical. Iterate this
# tuple, never the JSON, or the columns come out in the wrong order.
PROJECT_META_FIELDS = (
    "clone_url", "provenance_status",
    "source", "stratum", "fact", "contested", "label_date",
    "owner", "repo", "roster_name", "roster_lang",
    "language", "commits", "size_class", "size_kb", "stars", "pushed_at",
    "license", "owner_type", "archived", "fork",
    "history_cluster", "history_shared_with", "history_relation",
    "history_includes", "history_first", "history_created",
    "manifest_category", "file_mask",
)


def sql_literal(value) -> str:
    """A single-quoted SQL literal, with embedded quotes doubled.

    The COPY query is built by f-string, and these values are free text from
    candidates.csv. An unescaped apostrophe in history_shared_with would end the
    literal early, turning the rest of the value into SQL. A backslash is left
    alone on purpose: DuckDB follows the standard and does not read backslash
    escapes inside a single-quoted string, and file_mask is a regex full of them.
    """
    return "'" + str(value).replace("'", "''") + "'"


def load_project_meta(meta_path, project_key) -> dict:
    """The project's constants, or empty strings when no sidecar was given.

    An absent sidecar is allowed, so an older caller still produces a file with
    the full column set. A sidecar that does not hold the key is not: that is a
    stale sidecar or a mistyped slug, and falling back to empty strings would
    publish 29 blank columns without saying so.
    """
    if not meta_path:
        return {f: "" for f in PROJECT_META_FIELDS}
    import json

    with open(meta_path) as fh:
        meta = json.load(fh)
    if project_key not in meta:
        raise SystemExit(
            f"{project_key} is not in {meta_path}. Regenerate the sidecar with "
            "project_meta.py, or the dataset would carry blank provenance."
        )
    row = meta[project_key]
    return {f: row.get(f, "") for f in PROJECT_META_FIELDS}


def project_meta_sql(project_meta) -> str:
    """The metadata columns as SQL, one per line, in PROJECT_META_FIELDS order.

    Every value goes through sql_literal. Each line ends in a comma, so the
    block drops straight into the SELECT list.
    """
    return "".join(
        f"                {sql_literal(project_meta[f])} AS {f},\n"
        for f in PROJECT_META_FIELDS
    )


def main():
    parser = argparse.ArgumentParser(
        description="Generate unified Parquet dataset from CreGit pipeline outputs"
    )
    parser.add_argument(
        "--blame-dir", required=True, help="Directory with .blame files (Step 8 output)"
    )
    parser.add_argument(
        "--source-dir", required=True, help="Original source tree root (jq-original/)"
    )
    parser.add_argument(
        "--cregit-db", required=True, help="Path to cregit.db (Step 5 output)"
    )
    parser.add_argument(
        "--persons-db", required=True, help="Path to persons.db (Step 6 output)"
    )
    parser.add_argument("--output", required=True, help="Output Parquet file path")
    parser.add_argument(
        "--repo-name",
        default="",
        help="Repository name (default: inferred from output filename)",
    )
    parser.add_argument(
        "--project-meta",
        default="",
        metavar="PATH",
        help="JSON sidecar of per-project constants, from project_meta.py. "
        "Empty means emit the metadata columns as empty strings, so an older "
        "caller still produces a schema-valid file.",
    )
    parser.add_argument(
        "--project-key",
        default="",
        metavar="NAME",
        help="key into --project-meta (the manifest name). Defaults to --repo-name.",
    )
    parser.add_argument(
        "--memory-limit",
        default=DEFAULT_MEMORY_LIMIT,
        metavar="SIZE",
        help=f"cap DuckDB's heap (default: {DEFAULT_MEMORY_LIMIT}). DuckDB's own "
        "default is 80%% of total RAM, which the OOM killer reaches on a "
        "shared box. DuckDB spills to the temp directory instead.",
    )
    parser.add_argument(
        "--duckdb-threads",
        type=int,
        default=0,
        metavar="N",
        help="cap DuckDB's worker threads (default: 0, meaning DuckDB decides). "
        "Every sorting thread holds its own buffers, so N bounds peak memory "
        "as well as CPU.",
    )
    parser.add_argument(
        "--verbose", action="store_true", help="Verbose output (info-level logging)"
    )
    args = parser.parse_args()

    # Check both caps before Phase 1. Phase 1 costs half an hour on a large
    # repository, and a typo in a size string must not surface after that.
    try:
        memory_limit = parse_memory_limit(args.memory_limit)
    except ValueError as exc:
        parser.error(str(exc))
    if args.duckdb_threads < 0:
        parser.error(f"--duckdb-threads cannot be negative (got {args.duckdb_threads})")

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.WARNING,
        format="%(levelname)s: %(message)s",
        stream=sys.stderr,
    )

    blame_root = Path(args.blame_dir)
    source_root = Path(args.source_dir)
    output_path = Path(args.output)

    for p, label in [(blame_root, "blame-dir"), (source_root, "source-dir")]:
        if not p.is_dir():
            print(f"ERROR: {label} not found: {p}", file=sys.stderr)
            sys.exit(1)

    for p, label in [(args.cregit_db, "cregit-db"), (args.persons_db, "persons-db")]:
        if not Path(p).is_file():
            print(f"ERROR: {label} not found: {p}", file=sys.stderr)
            sys.exit(1)

    if not args.repo_name:
        args.repo_name = output_path.stem.replace("-dataset", "")

    # Resolve the sidecar before Phase 1, for the same reason as the two caps
    # above: a missing key must not surface after half an hour of inserts.
    project_meta = load_project_meta(
        args.project_meta, args.project_key or args.repo_name)

    blame_files = sorted(blame_root.rglob("*.blame"))
    if not blame_files:
        print("WARNING: no .blame files found", file=sys.stderr)

    print(f"Found {len(blame_files)} .blame files")
    print(f"Repo name: {args.repo_name}")
    print(f"Output:    {output_path}")

    # ------------------------------------------------------------------
    # Phase 1: sync blame → token_map (SQLite)
    # ------------------------------------------------------------------
    # Put the scratch DB beside the output, not in TMPDIR.
    #
    # Phase 1 inserts one row per token, so this file grows with the repository.
    # On this host /tmp is a 16 GB tmpfs, which is RAM: a large project would
    # fill it, and filling a tmpfs also exhausts system memory. The output
    # directory is the one place the caller has already sized for this project,
    # because the Parquet file lands there.
    scratch_dir = output_path.parent
    scratch_dir.mkdir(parents=True, exist_ok=True)
    sync_db_fd, sync_db_path = tempfile.mkstemp(
        suffix=".db", prefix="sync_", dir=scratch_dir
    )
    os.close(sync_db_fd)

    sync_conn = sqlite3.connect(sync_db_path)
    # This DB is scratch: Phase 2 reads it once and the file is deleted below.
    # So crash durability buys nothing, and a rollback journal doubles the write
    # volume for a table with one row per token.
    sync_conn.execute("PRAGMA journal_mode=OFF")
    sync_conn.execute("PRAGMA synchronous=OFF")
    sync_conn.execute("""
        CREATE TABLE IF NOT EXISTS token_map (
            file_path    TEXT,
            token_index  INTEGER,
            commit_sha   CHAR(40),
            token_type   TEXT,
            token_value  TEXT,
            source_text  TEXT,
            source_line  INTEGER,
            source_col   INTEGER,
            is_structural INTEGER,
            func_name    TEXT
        )
    """)
    cursor = sync_conn.cursor()
    total_tokens = 0
    files_processed = 0

    for bf in blame_files:
        rel = bf.relative_to(blame_root)
        rel_str = str(rel)
        if rel_str.endswith(".blame"):
            rel_str = rel_str[:-6]
        source_path = source_root / rel_str

        if not source_path.is_file():
            print(f"  WARNING: source not found: {source_path}, skipping {bf.name}")
            continue

        if args.verbose:
            print(f"  {bf.name} -> {rel_str}")
        count = process_blame_file(bf, source_path, rel_str, cursor)
        total_tokens += count
        files_processed += 1

    sync_conn.commit()
    # Index after the inserts, not before. Phase 1 only inserts, so nothing reads
    # the index until Phase 2. Building it up front makes SQLite maintain a
    # B-tree for every one of the rows above; building it once at the end is a
    # single sort.
    print("Indexing token_map...")
    sync_conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_tm_file ON token_map(file_path, token_index)"
    )
    sync_conn.commit()
    sync_conn.close()

    print(f"Synced {files_processed} files, {total_tokens} tokens")

    if total_tokens == 0:
        print("ERROR: no tokens processed, nothing to output", file=sys.stderr)
        Path(sync_db_path).unlink(missing_ok=True)
        sys.exit(1)

    # ------------------------------------------------------------------
    # Phase 2: DuckDB JOIN → Parquet
    # ------------------------------------------------------------------
    try:
        import duckdb
    except ImportError:
        print(
            "ERROR: duckdb is required. Install with: uv pip install duckdb",
            file=sys.stderr,
        )
        Path(sync_db_path).unlink(missing_ok=True)
        sys.exit(1)

    con = duckdb.connect()
    # Cap the heap before the query runs. DuckDB spills to temp_directory when it
    # reaches this limit. It cannot spill after the kernel has killed it, which
    # is what its 80%-of-total-RAM default invites on a shared host.
    con.execute(f"SET memory_limit='{memory_limit}'")
    if args.duckdb_threads:
        con.execute(f"SET threads={args.duckdb_threads}")
    # Spill to the output volume for the same reason as the scratch DB above.
    # The query ends in ORDER BY over every token in the repository, so DuckDB
    # spills whenever the sort does not fit in memory. Its default temp
    # directory would follow TMPDIR onto the tmpfs.
    con.execute(f"SET temp_directory='{str(scratch_dir).replace(chr(39), chr(39) * 2)}'")
    con.execute("INSTALL sqlite_scanner; LOAD sqlite_scanner;")

    con.execute(f"CALL sqlite_attach('{sync_db_path}')")
    con.execute(f"CALL sqlite_attach('{args.cregit_db}')")
    con.execute(f"CALL sqlite_attach('{args.persons_db}')")

    meta_sql = project_meta_sql(project_meta)

    query = f"""
        COPY (
            SELECT
                {sql_literal(args.repo_name)} AS repo_name,
{meta_sql}                t.file_path,
                t.token_index,
                t.source_line,
                t.source_col,
                t.source_text,
                t.token_type,
                t.token_value,
                t.is_structural,

                t.commit_sha                  AS cregit_commit_sha,
                coalesce(m.originalcid, t.commit_sha) AS original_commit_sha,

                c.autname                     AS author_name,
                c.autemail                    AS author_email,
                c.autdate                     AS author_date,
                c.comname                     AS committer_name,
                c.comemail                    AS committer_email,
                c.comdate                     AS committer_date,
                c.summary                     AS commit_summary,

                e.personid,
                coalesce(p.personname, e.personid) AS person_name,
                e.emailaddr                   AS person_email,
                e.domain                      AS person_domain,

                coalesce(m.repo, '')          AS repo_tag,

                coalesce(ftr.footer_signed_off_by, [])        AS footer_signed_off_by,
                coalesce(ftr.footer_co_authored_by, [])      AS footer_co_authored_by,
                coalesce(ftr.footer_co_developed_by, [])     AS footer_co_developed_by,
                coalesce(ftr.footer_reviewed_by, [])         AS footer_reviewed_by,
                coalesce(ftr.footer_acked_by, [])            AS footer_acked_by,
                coalesce(ftr.footer_tested_by, [])           AS footer_tested_by,
                coalesce(ftr.footer_reported_by, [])         AS footer_reported_by,
                coalesce(ftr.footer_suggested_by, [])        AS footer_suggested_by,
                coalesce(ftr.footer_based_on_patch_by, [])   AS footer_based_on_patch_by,
                coalesce(ftr.footer_helped_by, [])           AS footer_helped_by,
                coalesce(ftr.footer_mentored_by, [])         AS footer_mentored_by,
                coalesce(ftr.footer_assisted_by, [])        AS footer_assisted_by,
                coalesce(ftr.footer_thanks_to, [])           AS footer_thanks_to,
                coalesce(ftr.footer_personids, [])           AS footer_personids,
                coalesce(ftr.footer_person_names, [])        AS footer_person_names

            FROM token_map t
            JOIN commits c                ON t.commit_sha = c.cid
            LEFT JOIN commitmap m         ON c.cid = m.cid
            LEFT JOIN emails e            ON (c.autname = e.emailname
                                         AND c.autemail = e.emailaddr)
            LEFT JOIN persons p           ON e.personid = p.personid
            LEFT JOIN (
                SELECT
                    f.cid,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'signed-off-by')       AS footer_signed_off_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'co-authored-by')      AS footer_co_authored_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'co-developed-by')     AS footer_co_developed_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'reviewed-by')         AS footer_reviewed_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'acked-by')            AS footer_acked_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'tested-by')           AS footer_tested_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'reported-by')         AS footer_reported_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'suggested-by')        AS footer_suggested_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'based-on-patch-by')   AS footer_based_on_patch_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'helped-by')           AS footer_helped_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'mentored-by')         AS footer_mentored_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'assisted-by')         AS footer_assisted_by,
                    list(f.value ORDER BY f.idx) FILTER (WHERE LOWER(f.key) = 'thanks-to')           AS footer_thanks_to,
                    list(DISTINCT fe.personid ORDER BY fe.personid)
                        FILTER (WHERE fe.personid IS NOT NULL)                                           AS footer_personids,
                    list(DISTINCT coalesce(fpn.personname, fe.personid)
                        ORDER BY coalesce(fpn.personname, fe.personid))
                        FILTER (WHERE coalesce(fpn.personname, fe.personid) IS NOT NULL)                AS footer_person_names
                FROM footers f
                LEFT JOIN emails fe
                    ON fe.emailaddr = coalesce(
                        regexp_extract(f.value, '<([^>]+)>', 1),
                        regexp_extract(f.value, '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{{2,}}', 1)
                    )
                LEFT JOIN persons fpn ON fe.personid = fpn.personid
                GROUP BY f.cid
            ) ftr ON c.cid = ftr.cid

            ORDER BY t.file_path, t.token_index
        ) TO '{output_path}'
        (FORMAT PARQUET, COMPRESSION ZSTD)
    """

    print("Running JOIN query and writing Parquet...")
    try:
        con.execute(query)
    except Exception as e:
        print(f"ERROR during DuckDB query: {e}", file=sys.stderr)
        Path(sync_db_path).unlink(missing_ok=True)
        con.close()
        sys.exit(1)

    result = con.execute(f"SELECT COUNT(*) FROM read_parquet('{output_path}')")
    row_count = result.fetchone()[0]
    con.close()

    # Cleanup temp sync DB
    Path(sync_db_path).unlink(missing_ok=True)

    print(f"Done — {row_count:,} rows written to {output_path}")


if __name__ == "__main__":
    main()
