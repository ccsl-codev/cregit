# rustTokenizer

`rust_tokenizer` tokenizes Rust source into the line-oriented view used by Cregit, the
same format produced by the other tokenizers (`srcMLtoken`, `goTokenizer`) and consumed
by `blameRepo` and `prettyPrint`.

It uses [`ra-ap-rustc_lexer`](https://crates.io/crates/ra-ap-rustc_lexer), the lexer
`rustc` itself uses, so raw strings, byte literals, lifetimes vs. char literals, and
unicode identifiers are all handled correctly.

## Requirements

A Rust toolchain (`cargo`).

## How to use

`rust_tokenizer` reads a source file and writes the token stream to _stdout_:

```sh
rust_tokenizer <source.rs>
```

The flags `--language=Rust` and `--verbose` are accepted and ignored, so the
`tokenize.pl` dispatcher can call it the same way as the other tokenizers. `--position`
is **honored**: it adds the position prefix described below.

## Output format

Without `--position` — this is what the pipeline uses
(`run_pipeline_process.sh` builds `BFG_TOKENIZE_CMD` without the flag):

```
begin_unit|revision:...;language:Rust;cregit-version:...
kind|value
...
end_unit
<blank line>
```

With `--position`:

```
-:-|begin_unit|revision:...;language:Rust;cregit-version:...
LINE:COL|kind|value
...
-:-|end_unit
-:-|
```

One token per line. `kind` is one of `keyword`, `identifier`, `lifetime`, `literal`,
`comment`, `op`, or `unknown`. The `value` is emitted verbatim, with embedded newlines in
literals and block comments folded to spaces so each token stays on one line. Whitespace
produces no line. Columns count code points, not bytes.

### The separator is `|`, and the position prefix is opt-in

Both are load-bearing, and both were wrong until fixed: this tokenizer used a TAB and
emitted the prefix unconditionally, which corrupted 36,534,136 published dataset rows
across 44 projects.

The format is defined by `tokenizeSrcMl.pl`, the only other tokenizer the pipeline routes
to, and its committed golden output is the reference:

| file | shape |
| --- | --- |
| `tests/t/expected/main.c.nopos.token` | `begin_unit\|…`, `comment\|/* … */` |
| `tests/t/expected/main.c.token` | `-:-\|begin_unit\|…`, `1:1\|comment\|/* … */` |

Both downstream consumers split on `|`:

- `generate_dataset/generate_dataset.py` — `re.match(r"^(.+?)\|(.+)$", token_content)`
- `prettyPrint/prettyPrint-author.pl` — `split('\|', $value)`

The first capture group is **non-greedy**, so any extra leading field silently becomes
`token_type` and shifts `token_value`, `source_text` and `is_structural` by one. Nothing
errors; the data is just wrong.

Do **not** take `tokenize/srcMLtoken/tests/expected/*.token` as the reference. It is
TAB-separated, but it is `srcml2token`'s *intermediate* output, which `tokenizeSrcMl.pl`
consumes with `/^([0-9]+|-):([0-9]+|-)\s+(.+)$/` and re-emits with `|`. It reaches no
consumer. `tests/framing.rs` pins this contract across tokenizers.

## How to build

```sh
cargo build --release
```

This produces `target/release/rust_tokenizer`, the path `tokenize.pl` expects.

## How to test

```sh
cargo test
```

## License

GPL-3.0+
