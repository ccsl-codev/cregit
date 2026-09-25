#!/usr/bin/env bash

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export PATH="/home/ellianco/Projects/cregit-workspace/cregit-issue61/.devenv/profile/bin:$PATH"

SRCML2TOKEN="$ROOT/tokenize/srcMLtoken/srcml2token"
if [[ ! -x "$SRCML2TOKEN" ]]; then
    if ! pkg-config --exists xerces-c; then
        for candidate in /nix/store/*-xerces-c-*/lib/pkgconfig/xerces-c.pc; do
            if [[ -f "$candidate" ]]; then
                export PKG_CONFIG_PATH="$(dirname "$candidate")${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
                break
            fi
        done
    fi
    make -C "$ROOT/tokenize/srcMLtoken"
fi

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
    --temp-root "$TEMP_ROOT"
