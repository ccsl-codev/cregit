#!/usr/bin/env bash
# Byte-identity and lifecycle tests for tokenizeByBlobId/tokenWorker.pl.
# Run inside `devenv shell` (needs perl, python3, srcml, ctags on PATH).
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

SRCML2TOKEN="$ROOT/tokenize/srcMLtoken/srcml2token"
[[ -x "$SRCML2TOKEN" ]] || make -C "$ROOT/tokenize/srcMLtoken"

SRCML=$(command -v srcml)
CTAGS=$(command -v ctags)
TOKENIZE_COMMAND="$ROOT/tokenize/tokenize.pl --srcml2token=$SRCML2TOKEN --srcml=$SRCML --ctags=$CTAGS"
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/cregit-token-worker.XXXXXX")
trap 'rm -rf "$TEMP_ROOT"' EXIT

python3 "$ROOT/tests/token_worker_driver.py" \
    --worker "$ROOT/tokenizeByBlobId/tokenWorker.pl" \
    --token-by-sha "$ROOT/tokenizeByBlobId/tokenBySha.pl" \
    --tokenize-command "$TOKENIZE_COMMAND" \
    --fixture "$ROOT/tests/fixtures/tiny.c" \
    --fixture "$ROOT/tests/fixtures/quote'name.c" \
    --fixture "$ROOT/tests/fixtures/tiny.cpp" \
    --fixture "$ROOT/tests/fixtures/Tiny.java" \
    --fixture "$ROOT/tests/fixtures/empty.c" \
    --in-process \
    --temp-root "$TEMP_ROOT"
