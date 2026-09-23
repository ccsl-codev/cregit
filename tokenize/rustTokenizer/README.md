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

Without `--position`, which is what the pipeline uses:

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
