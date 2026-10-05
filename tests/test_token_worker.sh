#!/usr/bin/env bash
# Run inside `devenv shell`: it needs perl, python3, srcml, ctags and cargo.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRCML2TOKEN="$ROOT/tokenize/srcMLtoken/srcml2token"
[[ -x "$SRCML2TOKEN" ]] || make -C "$ROOT/tokenize/srcMLtoken"
[[ -x "$ROOT/tokenize/rustTokenizer/target/release/rust_tokenizer" ]] || make -C "$ROOT/tokenize/rustTokenizer"

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/cregit-token-worker.XXXXXX")
trap 'rm -rf "$TEMP_ROOT"' EXIT

FIXTURES=("$ROOT"/tests/fixtures/* "$ROOT/tokenize/t/fixtures/srcml-position-crash.c")
python3 "$ROOT/tests/token_worker_driver.py" \
    --worker "$ROOT/tokenizeByBlobId/tokenWorker.pl" \
    --token-by-sha "$ROOT/tokenizeByBlobId/tokenBySha.pl" \
    --tokenize-command "$ROOT/tokenize/tokenize.pl --srcml2token=$SRCML2TOKEN --ctags=$(command -v ctags)" \
    "${FIXTURES[@]/#/--fixture=}" \
    --temp-root "$TEMP_ROOT"
