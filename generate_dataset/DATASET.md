# CreGit Parquet Dataset

## Overview

`generate_dataset.py` produces a unified Parquet dataset containing every token from a
tokenized git repository, annotated with commit metadata, authorship information,
person identity, and the commit's git trailers.  It is the final
output of the CreGit pipeline (Step 10).

**The schema is 67 columns**, in this order:

| Block | Columns | # |
|-------|---------|---|
| [Token data](#token-data) | `repo_name`, then `file_path` … `is_structural` | 1 + 8 |
| [Per-project provenance](#per-project-provenance) | `clone_url` … `file_mask` | 29 |
| [Git commit metadata](#git-commit-metadata) | `cregit_commit_sha` … `commit_summary` | 9 |
| [Person identity](#person-identity) | `personid` … `repo_tag` | 5 |
| [Commit trailers](#commit-trailers-footers) | `footer_*` | 15 |
| | **total** | **67** |

`repo_name` is column 1 and the 29 provenance columns are 2-30, so `file_path` is
column 31.

The contract is **not this document**: it is `EXPECTED_COLUMNS` in
`cregit-token-pipeline/validate_schema.py`, which every project's Parquet is
checked against by name, by type and in order, and a drift is a non-zero exit.
If you change the generator's output, change that file in the same commit and
this one immediately after.  `cregit-token-pipeline/docs/DATASET-SCHEMA.md` draws
the same 67 columns beside the on-disk schema they collapse from.

## Quick start

```sql
-- Most-used query: top contributors by token count for a file
SELECT person_name, COUNT(*) AS tokens
FROM 'dataset.parquet'
WHERE file_path = 'src/parser.c' AND is_structural = 0
GROUP BY person_name
ORDER BY tokens DESC;

-- Tokens authored by a specific person
SELECT file_path, source_line, source_text, token_type, token_value
FROM 'dataset.parquet'
WHERE person_name = 'Linus Torvalds'
ORDER BY file_path, token_index;
```

## Columns

### Token data

| Column | Type | Source | Description |
|--------|------|--------|-------------|
| `repo_name` | `TEXT` | CLI arg | Repository name (from `--repo-name` or inferred from output filename). |
| `file_path` | `TEXT` | blame file | Relative path of the source file within the repository. |
| `token_index` | `INTEGER` | blame file | 0-based position of this token in the file's blame stream. Monotonically increasing per file. |
| `source_line` | `INTEGER` | source file | 1-based line number in the original source file where the text of this token starts. |
| `source_col` | `INTEGER` | source file | 1-based column number in the original source file. |
| `source_text` | `TEXT` | source file | The actual source code characters consumed by this token, including surrounding whitespace. This is the raw text as it appears in the source. |
| `token_type` | `TEXT` | srcml2token | Classification of the token (see [token_type domain](#token_type-domain) below). |
| `token_value` | `TEXT` | srcml2token | Normalised token value with whitespace stripped. For content tokens this is the text after the `\|` separator (e.g. `main`, `int`, `"hello"`). For structural tokens it may be identical to `token_type`. |
| `is_structural` | `INTEGER` | computed | `1` for structural markers (file boundaries, function boundaries, declarations), `0` for actual code content. Use `WHERE is_structural = 0` to count only real code tokens. |

### Per-project provenance

Written only when `--project-meta` names a JSON sidecar (produced by
`cregit-token-pipeline/project_meta.py`) and the project is keyed in it by
`--project-key`; otherwise every column here is the empty string, so the column
set does not depend on the caller. All 29 are `TEXT`, they are constants per
file, and they repeat per row by design: a reader can filter a corpus without a
second join against `candidates.csv`.

`TEXT` is the contract, not an oversight: the sidecar is JSON written by
`cregit-token-pipeline/project_meta.py` and checked by its `validate_schema.py`,
and both treat every one of the 29 as a string. So `commits`, `size_kb`, `stars`,
`pushed_at`, `archived` and `fork` are strings here even though they read as
numbers or booleans. Cast at the query: `CAST(size_kb AS BIGINT)`, or
`ORDER BY size_kb` sorts lexicographically.

| Column | Description |
|--------|-------------|
| `clone_url` | Clone URL the project was selected by. `repo_name` is a lossy slug; this is not. |
| `provenance_status` | `candidates.csv` for a corpus project, or a flag such as `fixture-needs-rework` for a development fixture that shares the output directory. |
| `source` | Which roster or search found the project. |
| `stratum` | Sampling stratum the project was assigned. |
| `fact` | The evidence for that assignment. |
| `contested` | Whether two sources disagreed on the stratum. |
| `label_date` | When the stratum was assigned. |
| `owner`, `repo` | GitHub owner and repository name. |
| `roster_name`, `roster_lang` | Name and language as the roster spelled them. |
| `language` | Primary language reported by GitHub. |
| `commits`, `size_class`, `size_kb`, `stars`, `pushed_at` | Repository metrics at selection time. |
| `license`, `owner_type`, `archived`, `fork` | Repository attributes at selection time. |
| `history_cluster`, `history_shared_with`, `history_relation`, `history_includes`, `history_first`, `history_created` | Shared-history analysis: projects that share commit history are not independent observations. |
| `manifest_category` | The category column of the corpus manifest row. |
| `file_mask` | The regex this project was tokenized with. Without it nobody can tell which rows came from which mask after a mask widening. One universal mask is used for every project since 2026-09-19 — `(?i)\.(c\|c\+\+\|cc\|cp\|cpp\|cxx\|h\|h\+\+\|hh\|hpp\|hxx\|java\|rs\|tcc)$` — so it no longer discriminates between projects, only between runs. |

### Git commit metadata

| Column | Type | Source | Description |
|--------|------|--------|-------------|
| `cregit_commit_sha` | `CHAR(40)` | blame file | Git commit SHA from the tokenised (cregit) repository blame. This is the commit in the cregit-view repo that last touched this token. |
| `original_commit_sha` | `CHAR(40)` | commitmap | Original commit SHA. When commit mapping exists (remapCommits step), this is the corresponding commit in the original repository. Falls back to `cregit_commit_sha` when no mapping exists. |
| `author_name` | `TEXT` | commits | Raw git commit author name (`autname`). This is the literal string from the commit metadata. |
| `author_email` | `TEXT` | commits | Raw git commit author email. |
| `author_date` | `TEXT` | commits | Author date string (git format). |
| `committer_name` | `TEXT` | commits | Committer name. |
| `committer_email` | `TEXT` | commits | Committer email. |
| `committer_date` | `TEXT` | commits | Committer date string. |
| `commit_summary` | `TEXT` | commits | First line of the commit message. |

### Person identity

| Column | Type | Source | Description |
|--------|------|--------|-------------|
| `personid` | `TEXT` | emails → persons | Unified person identifier. Multiple email addresses can map to the same person ID. |
| `person_name` | `TEXT` | persons | Canonical display name for this person. Derived via `coalesce(p.personname, e.personid)`. If neither is available, this is `NULL`. |
| `person_email` | `TEXT` | emails | Email address that matched this commit's author name/email pair. |
| `person_domain` | `TEXT` | emails | Domain part of the email address. |
| `repo_tag` | `TEXT` | commitmap | Repository tag indicating the origin repository. Values: `'p'` (pre-history), `'b'` (BitKeeper), `'l'` (Linux), or `''` (unknown/single repo). |

### Commit trailers (footers)

Fifteen columns, all `LIST(TEXT)` — `VARCHAR[]` as DuckDB and the Parquet report
them.  They are **never NULL**: a commit with no trailer of that key gets an
empty list, through `coalesce(…, [])`, so `len(footer_reviewed_by) = 0` is the
test for absence and a NULL check is not needed.

Source: the `footers` table of `cregit.db` (one row per trailer occurrence, with
`cid`, `idx`, `key`, `value`), grouped by commit.  Keys are matched
**case-insensitively** (`LOWER(f.key) = 'signed-off-by'`), and each list keeps the
order the trailers appeared in the message, by `footers.idx`.

| Column | Trailer key |
|--------|-------------|
| `footer_signed_off_by` | `Signed-off-by` |
| `footer_co_authored_by` | `Co-authored-by` |
| `footer_co_developed_by` | `Co-developed-by` |
| `footer_reviewed_by` | `Reviewed-by` |
| `footer_acked_by` | `Acked-by` |
| `footer_tested_by` | `Tested-by` |
| `footer_reported_by` | `Reported-by` |
| `footer_suggested_by` | `Suggested-by` |
| `footer_based_on_patch_by` | `Based-on-patch-by` |
| `footer_helped_by` | `Helped-by` |
| `footer_mentored_by` | `Mentored-by` |
| `footer_assisted_by` | `Assisted-by` |
| `footer_thanks_to` | `Thanks-to` |

Each value is the raw trailer text as the commit wrote it — typically
`Name <email>` — not a resolved identity.  Two further columns resolve them:

| Column | Meaning |
|--------|---------|
| `footer_personids` | the distinct `personid`s behind **every** trailer on the commit: the address inside `<…>` is pulled out of the trailer text with `regexp_extract` and matched against `emails.emailaddr` |
| `footer_person_names` | the resolved names for those ids, `coalesce(personname, personid)` |

Those two are `DISTINCT` and sorted: they are a **set over all trailers**, not a
list aligned with the 13 typed columns, and they cannot be zipped with them.  A
trailer whose address matches no `emails` row contributes nothing to either, so
`footer_signed_off_by` can be non-empty while `footer_personids` is empty.

**Known limitation: a trailer with no angle brackets never resolves.**  The
generator tries `<([^>]+)>` first and a bare-address pattern second, through a
`coalesce`, but DuckDB's `regexp_extract` returns the **empty string** on no match
rather than NULL, so the `coalesce` never reaches the second pattern — and that
second pattern has no capture group, so asking it for group 1 would also return
`''`.  Measured:

```sql
SELECT regexp_extract('Bob <b@x.com>', '<([^>]+)>', 1),        -- 'b@x.com'
       regexp_extract('Bob b@x.com',   '<([^>]+)>', 1);        -- ''
```

So `Reviewed-by: bob@example.com` (a real, if less common, spelling) lands in
`footer_reviewed_by` but contributes nothing to `footer_personids`.  Use the 13
raw columns, not the resolved pair, if you need every named contributor.

These columns are why the dataset can say anything about contribution that is not
authorship.  `Signed-off-by`, `Reviewed-by` and `Co-authored-by` carry attribution
that the author fields do not, and in a mailing-list project they carry most of it.

```sql
-- Reviewers by review count, independent of who authored the code
SELECT r AS reviewer, COUNT(*) AS reviews
FROM (SELECT DISTINCT original_commit_sha, unnest(footer_reviewed_by) AS r
      FROM 'dataset.parquet' WHERE len(footer_reviewed_by) > 0)
GROUP BY r ORDER BY reviews DESC;
```

Note the `DISTINCT original_commit_sha` in that query: the grain of this table is
the **token**, so a commit's trailers repeat on every token it touched.  Any
commit-level count over `footer_*` must deduplicate by commit first.

## token_type domain

### Structural tokens (`is_structural = 1`)

These tokens have **empty** `source_text`.  They mark boundaries and structural
elements in the code.

| token_type | Origin | Meaning |
|------------|--------|---------|
| `begin_unit` | srcML `<unit>` | Start of a source file/translation unit. |
| `begin_function` | srcML `<function>` | Start of a function definition. |
| `end_function` | srcML `</function>` | End of a function definition. |
| `begin_<tag>` | srcML depth ≤ 1 | Any other structural begin marker emitted by srcML. Common examples: `begin_class`, `begin_if`, `begin_while`, `begin_for`, `begin_block`, `begin_struct`, `begin_enum`, `begin_union`, `begin_try`, `begin_catch`, `begin_template`, `begin_namespace`. |
| `end_<tag>` | srcML depth ≤ 1 | Corresponding end marker for the above. |
| `DECL` | ctags | Declaration (function or variable) extracted by ctags during tokenisation. The `token_value` contains the full `DECL\|type\|name` string. The `func_name` column holds the extracted name. |
| `""` (empty) | pipeline | Empty delimiter token from a bare `\|` in the blame stream. |
| `blank` | pipeline | Blank/empty token line. |
| `unknown` | fallback | Token content that did not match any known format. |

### Content tokens (`is_structural = 0`)

These tokens have **non-empty** `source_text` and represent actual code content.
The `token_type` is the srcML XML tag name that wraps the content.

Common types observed in C/C++/Java:

| token_type | Meaning | Examples |
|------------|---------|---------|
| `name` | Identifier | `main`, `count`, `buf`, `printf` |
| `type` | Type name | `int`, `char`, `void`, `size_t`, `FILE` |
| `operator` | Operator | `+`, `-`, `*`, `->`, `==`, `<<`, `++` |
| `keyword` | Language keyword | `if`, `while`, `return`, `for`, `break`, `continue` |
| `literal` | String/character/number literal | `"hello"`, `'x'`, `42`, `3.14`, `NULL` |
| `comment` | Block or line comment | `/* TODO: fix */`, `// note` |
| `specifier` | Storage class specifier | `static`, `extern`, `register`, `inline`, `typedef` |
| `modifier` | Type modifier | `const`, `unsigned`, `volatile`, `signed`, `long` |
| `control` | Control flow | `if`, `else`, `switch`, `case`, `default` (when nested) |
| `expr` | Expression wrapper | Groups sub-expressions |
| `call` | Function call | `printf(...)`, `foo()` |
| `argument` | Argument list or individual argument | `(arg1, arg2)` |
| `argument_list` | Argument list | `(int x, char y)` |
| `condition` | Condition expression | `(x > 0)`, `ptr != NULL` |
| `init` | Initializer | `= 0`, `{1, 2, 3}` |
| `decl` | Declaration (within function) | `int x;` |
| `decl_stmt` | Declaration statement | wrapper for `int x;` |
| `expr_stmt` | Expression statement | wrapper for `x++;` |
| `param` | Parameter declaration | `int argc`, `char **argv` |
| `block` | Block content | content inside `{ }` |
| `cpp:include` | Preprocessor include directive | `#include` |
| `cpp:directive` | Preprocessor directive | `#define`, `#ifdef`, `#ifndef`, `#endif`, `#undef` |
| `cpp:file` | Preprocessor file reference | `<stdio.h>`, `"util.h"` |
| `cpp:define` | Preprocessor macro definition | macro name |
| `macro` | Macro usage / expansion | `NULL`, `EOF`, `MAX(a,b)` |
| `enum` | Enum specific content | enumerator names |
| `struct` | Struct specific content | member declarations |
| `union` | Union specific content | member declarations |
| `template` | Template parameters | `<typename T>` |
| `class` | Class content | member declarations |
| `range` | Range expression | `..` |

> **Note:** The complete set of `token_type` values depends on the source language and
> srcML version. Any srcML XML tag name can appear as a `token_type`. The list above
> covers the most common cases for C, C++, and Java.

## Author identity: raw vs unified

The dataset provides ***two*** author identity systems:

| Column | What it tracks | Example |
|--------|---------------|---------|
| `author_name` | Raw git commit author name | `"Gabriel R."`, `"Gabriel R"`, `"Gabriel R. Filho"` |
| `person_name` / `personid` | Unified person identity | `"Gabriel R."` (same across all emails) |

### Why they differ

A single person often commits with different author names or emails (different
machines, git config changes, typos, etc.). The **persons DB** (`persons`/`emails`
tables) maps multiple email addresses to a single `personid`.

### The JOIN path

```
token_map.commit_sha → commits.cid
                    → commitmap.cid     → commitmap.originalcid (original commit SHA)
                    → emails.emailname  → persons.personid      → persons.personname
                      \_ (matched on autname + autemail)
```

### How the HTML output groups authors

The Perl `prettyPrint-author.pl` script uses:

```perl
coalesce(personname, personid, 'Unknown')
```

This is the Perl equivalent of the dataset's `person_name` column.

### Correct query pattern

**WRONG** — raw name will split the same person:

```sql
SELECT author_name, COUNT(*) AS tokens
FROM 'dataset.parquet'
GROUP BY author_name
ORDER BY tokens DESC;
-- Gabriel R.      → 1500
-- Gabriel R. F.   → 300   ← same person, different name string
```

**RIGHT** — use unified identity:

```sql
SELECT person_name, personid, COUNT(*) AS tokens
FROM 'dataset.parquet'
WHERE is_structural = 0
GROUP BY person_name, personid
ORDER BY tokens DESC;
-- Gabriel R.  | person_abc123 | 1800  ← all 1800 tokens unified
```

## What is not in the dataset

### `person_email` is published, and there is no anonymiser — an open decision

`person_email` and `person_domain` are real columns of the output, so **writing a
Parquet and publishing it publishes contributors' e-mail addresses.**  The design
for this corpus names a weak anonymiser for exactly this purpose
(`cregit-token-pipeline/docs/DESIGN.md` §7, stage 2: `anonymize.py`, a salted
stable hash with the salt and the reverse mapping kept local) and **it has not
been written.**  Nothing in this generator drops, hashes or redacts the column,
and there is no flag that does.

Recorded here as an **open decision that blocks publication, not the pipeline.**
Drop the column, hash it, or publish it deliberately with an ethics statement —
those are three different positions and none of them is the default.  Note that
`person_domain` is what an affiliation study resolves through, so removing
`person_email` alone does not remove domain-level identifiability.

### Files that produce no rows at all

The generator writes a row for every token it is given, so what is missing from
the Parquet is whatever never reached the tokenizer.  Three mechanisms upstream
(in `blobExec`, step 2) exclude a blob, and in all three cases the file is
**absent from the tokenized repository rather than present as raw source** — so it
produces no blame and no dataset row, rather than rows of unparsed text:

| Mechanism | Recorded as | Effect here |
|---|---|---|
| the **blob denylist**, `blobExec/src/main/resources/cregit/blobexec/blob-denylist.tsv` | `blobsDenylisted`, plus one `EXCLUDED denylisted blob` line per blob naming its sha, path and cited reason | no rows for those blobs. The list is a data file so a paper can cite it; nothing at run time can extend or override it |
| **oversized** blobs (at or above JGit's stream-file threshold) | `blobsOversized`, plus one `EXCLUDED oversized blob` line each | no rows for those blobs |
| a blob the tokenizer **timed out** on | `blobsTimedOut`, exit 4 | **no Parquet at all**: steps 3-10 never run, so this generator is never reached and the project cannot publish while a timeout is unexplained |

The denylist currently holds **four blob ids, and they are four blobs of one
file** — `TestNewCastArray.java`, an OpenJDK langtools regression test, at two
paths (the path moved in a repository reorganisation) across four revisions.  It
is keyed by content rather than by path on purpose.  srcML 1.1.0 does not
terminate on it: 0 bytes of output at every budget from 5 s to 600 s, upstream
`srcML/srcML#2361`, open, no patch, no newer release.  Read the header of the
denylist file; it carries the measurements and the citation.

Also outside the dataset, by mask rather than by exclusion: M4 (`.am`, `.ac`),
whose tokenizer's lexer is not fit for real autotools input, and `.ixx`, `.inl`,
`.cppm`, `.cxxm`, `.ipp`, for which srcML emits **zero tokens and exits 0**.
`file_mask` on every row records the mask that decided this.

### Computed and then dropped, or never typed

- `func_name` is computed in Phase 1 for `DECL` tokens and is **not** carried into
  the Parquet.  Function-level attribution is not in the published columns.
- `author_date` and `committer_date` are git strings, not timestamps.  Cast before
  comparing.
- `JOIN commits` is an **inner** join, so a blamed commit missing from
  `cregit.db :: commits` drops its tokens with no report; and `emails` is matched
  on the exact pair `(autname, autemail)`, so `personid` and the three columns
  after it can be NULL for a commit whose author spelling matches no `emails` row.

## How the dataset is built

```
blame files (.blame) ──┐
                        │
source files ───────────┤
                        │
      Phase 1: ────────▶ SQLite token_map table
      (Python)           (file_path, token_index, commit_sha,
                          token_type, token_value, source_text,
                          source_line, source_col, is_structural, func_name)
                        │
cregit.db ──────────────┤
(commits, commitmap)    │
                        │
persons.db ─────────────┤
(emails, persons)       │
                        │
      Phase 2: ────────▶ DuckDB JOIN ──▶ Parquet
      (Python)
```

### Phase 1: Sync blame → token_map

For each `.blame` file:
1. Parse each line as `commit_sha;token_content`
2. Walk through the original source file character-by-character to match tokens
3. Classify each token (structural vs content, type, value)
4. Insert into SQLite `token_map`

### Phase 2: DuckDB JOIN → Parquet

Join `token_map` with the three SQLite databases:
- `commits` — commit metadata (author, committer, dates, summary)
- `commitmap` — cregit → original commit SHA mapping
- `emails` / `persons` — person identity resolution

Output is written as ZSTD-compressed Parquet.

## Usage

```
uv run python generate_dataset/generate_dataset.py \
    --blame-dir  ../cregit-files/blame \
    --source-dir ../cregit-files/jq-original \
    --cregit-db  ../cregit-files/jq-cregit.db \
    --persons-db ../cregit-files/jq-persons.db \
    --output     ../cregit-files/jq-dataset.parquet \
    --repo-name  jq \
    --verbose
```

### Options

| Option | Required | Description |
|--------|----------|-------------|
| `--blame-dir` | yes | Directory containing `.blame` files (Step 8 output). |
| `--source-dir` | yes | Root of the original source tree. |
| `--cregit-db` | yes | Path to `cregit.db` (Step 5 output). |
| `--persons-db` | yes | Path to `persons.db` (Step 6 output). |
| `--output` | yes | Output Parquet file path. |
| `--repo-name` | no | Repository name. Default: inferred from output filename. |
| `--project-meta` | no | JSON sidecar of per-project provenance, from `project_meta.py`. Omit and those columns are written empty. |
| `--project-key` | no | Which key of the sidecar this project is. Default: `--repo-name`. A key the sidecar does not hold is an error, not a blank row. |
| `--verbose` | no | Enable info-level logging to stderr. |

## Cross-reference: Perl ↔ Python

The token-source character-matching logic is replicated from
`prettyPrint/prettyPrint-author.pl`. See the header of
`generate_dataset.py` for a complete mapping table.
