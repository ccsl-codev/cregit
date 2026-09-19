# CreGit Parquet Dataset

## Overview

`generate_dataset.py` produces a unified Parquet dataset containing every token from a
tokenized git repository, annotated with commit metadata, authorship information, and
person identity.  It is the final output of the CreGit pipeline (Step 11).

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
| `file_mask` | The regex this project was tokenized with. Without it nobody can tell which rows came from which mask after a mask widening. |

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
