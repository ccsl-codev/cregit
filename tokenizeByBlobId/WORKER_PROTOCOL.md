# Persistent tokenizer worker protocol

`tokenizeByBlobId/tokenWorker.pl` replaces the per-blob chain
`timeout → sh → tokenize.pl → tokenBySha.pl → tokenizeSrcMl.pl` with one
long-lived perl process per pool slot. blobExec starts `parallelism` workers and
talks to each over its stdin/stdout. One request in flight per worker.

The output contract is byte-identical to `tokenBySha.pl`: same tokens on
stdout, same memo file written at `$BFG_MEMO_DIR/xx/yy/<sha1(contents)>`, same
exit-code meaning. A tokenized blob's id is a pure function of content, so the
worker must never change bytes.

## Startup

```
tokenWorker.pl            # no args; reads BFG_MEMO_DIR and BFG_TOKENIZE_CMD from env
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

## Timeout

The worker runs the parser chain in its own process group (`setpgrp` in the
child) and enforces `timeoutSecs` with `alarm`; on expiry it `kill -TERM` then
`-KILL`s the group and answers `RES 124 0 <errLen>`. blobExec keeps a backstop:
if no `RES` header arrives within `timeoutSecs + grace + slack`, it destroys the
worker, respawns one, and reports the blob as a timeout (`Skip`, never `Replace`).

## Lifecycle

- EOF on stdin → worker exits 0.
- A malformed header → worker writes the reason to stderr and exits 2. blobExec
  respawns and reports that blob as a Skip.
- Workers hold no cross-request state except the memo dir. Order of requests is
  irrelevant.

## Stages

- Stage A (this branch): worker does dispatch + sha1 + memo + temp file
  in-process and spawns only the language parser (`tokenizeSrcMl.pl`, which in
  turn runs ctags and `srcml | srcml2token`). Removes `timeout`, `sh`,
  `tokenize.pl`, `tokenBySha.pl` from every blob.
- Stage B (later): fold `tokenizeSrcMl.pl` into the worker as a module so only
  ctags, srcml and srcml2token are spawned per blob.
