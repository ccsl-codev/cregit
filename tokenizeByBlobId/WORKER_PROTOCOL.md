# Persistent tokenizer worker protocol

`tokenizeByBlobId/tokenWorker.pl` replaces the per-blob chain
`tokenBySha.pl → sh → tokenize.pl → tokenizeSrcMl.pl` with one long-lived perl
process per pool slot. With `--tokenizer-worker=<path>` (pipelined modes only),
blobExec starts `parallelism` workers and talks to each over its stdin/stdout.
One request in flight per worker.

The output contract is byte-identical to `tokenBySha.pl`: same tokens on
stdout, same memo file written at `$BFG_MEMO_DIR/xx/yy/<sha1(contents)>`, same
exit-code meaning. A tokenized blob's id is a pure function of content, so the
worker must never change bytes.

## Startup

```
tokenWorker.pl            # no args; inherits blobExec's environment, see below
```

Worker writes `READY\n` to stdout once, then waits for requests. stderr is for
diagnostics only; blobExec forwards it.

## Request (JVM → worker stdin)

```
REQ <origSha> <bodyLen> <fnLen> <pathLen> <timeoutSecs>\n
<fnLen bytes filename><pathLen bytes fullPath><bodyLen bytes blob contents>
```

- `origSha`: hex id of the original blob (was `BFG_BLOB`).
- `filename`: basename with extension (was `BFG_FILENAME`).
- `fullPath`: repo-relative path (was `BFG_PATH`).
- Lengths are decimal byte counts. Strings are raw bytes, no escaping.
- `timeoutSecs`: per-request parser budget the worker must enforce itself.

## Response (worker stdout → JVM)

```
RES <exit> <outLen> <errLen>\n
<outLen bytes stdout><errLen bytes stderr>
```

- `exit`: what `tokenBySha.pl` would have exited with. `0` = tokens follow;
  `33` = parser crash (`$PARSER_CRASH_EXIT`); `124` = the worker killed the
  parser on timeout; any other non-zero = tokenizer error, stdout empty.
- On a non-zero exit nothing is memoized, exactly as `tokenBySha.pl` does.
- stderr holds the request's diagnostics, including what the persistent
  helpers wrote during it. blobExec logs it only when the blob fails.

## Timeout

The worker runs the parser chain in its own process group (`setpgrp` in the
child) and enforces `timeoutSecs` with `alarm`; on expiry it `kill -TERM` then
`-KILL`s the group and answers `RES 124 0 <errLen>`. blobExec keeps a backstop:
if no `RES` header arrives within `ChildRunner.maxLifetimeSeconds(timeoutSecs)`
(`timeoutSecs + 10`), it kills the worker and its children, starts a new one,
and reports the blob as a timeout (`Skip`, never `Replace`).

## Lifecycle

- EOF on stdin → worker exits 0.
- A malformed header → worker writes the reason to stderr and exits 2. blobExec
  respawns and reports that blob as a Skip.
- Each worker keeps one `ctags --_interactive` per language and one
  `srcml2token --server` alive between requests (`tokenize/CregitSrcMl.pm`),
  and starts them again after a timeout or a crash. Only `srcml` is spawned per
  blob. Request order is irrelevant.
- Languages that `tokenize.pl` does not send to `tokenizeSrcMl.pl`, and any
  `BFG_TOKENIZE_CMD` the worker does not recognise, still spawn
  `BFG_TOKENIZE_CMD` per blob.

## Environment

- `BFG_MEMO_DIR`, `BFG_TOKENIZE_CMD`: as for `tokenBySha.pl`.
- `BFG_WORKER_INPROC=0`: spawn `BFG_TOKENIZE_CMD` for each blob instead of
  the in-process srcML chain (A/B measurement only).
