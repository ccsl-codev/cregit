# Tokenizer worker protocol

`tokenWorker.pl` is a persistent `tokenBySha.pl`. With
`--tokenizer-worker=<path>`, blobExec starts one worker per processor and
sends each blob to an idle worker. A worker handles one request at a time.

The worker reads `BFG_MEMO_DIR` and `BFG_TOKENIZE_CMD` from its environment,
as `tokenBySha.pl` does. Its tokens, its memo files and its exit statuses are
the same as those of `tokenBySha.pl`.

## Messages

At start, the worker writes `READY\n` to stdout. Then, for each request:

```
JVM -> worker:  REQ <bodyLength> <nameLength> <timeoutSeconds>\n<name><body>
worker -> JVM:  RES <exit> <outLength> <errLength>\n<out><err>
```

- `name` is the file name of the blob. Its extension selects the language.
- Lengths are byte counts. The strings are raw bytes.
- `exit` is the status that `tokenBySha.pl` gives: `0` (the tokens are in
  `out`), `33` (parser crash) or another status (tokenizer error, `out` is
  empty). `124` means that the tokenizer did not finish in `timeoutSeconds`.
- The worker memoizes only a successful tokenization.

## Failures

- The worker runs the tokenizer in its own process group. At the timeout it
  kills the group and answers `124`.
- If no answer comes in `timeoutSeconds + 5` seconds, blobExec kills the
  worker and its children, starts a new worker, and reports a timeout.
- If the worker stops in a request, blobExec reports a tokenizer error for
  that blob and starts a new worker.
- At EOF on stdin, the worker exits 0. A malformed request makes it exit 2.
