# srcMLtoken

`srcml2token` parses a C, C++ or Java file with libsrcml
([www.srcml.org](https://www.srcml.org)) and prints a tokenized view of its
srcML that Cregit uses.

## How to use

```sh
srcml2token -l C <filename>
```

It parses the file as `srcml -l C --position <filename>` does, and writes the
tokens to _stdout_. `srcml2token --libsrcml-path` prints the libsrcml that it
loads; `tokenize/tokenizerIdentity.pl` digests that file.

## How to build

It needs `xerces-c` and the libsrcml headers and library. Inside `devenv shell`,
`SRCML_PREFIX` names the srcML build of `nix/srcml.nix`. Run:

```sh
make && make test
```

## License

The code is mostly derived from the examples of Xerces, hence it is under the
Apache-2.0

Dependencies:
  - `xerces`: Apache-2.0
  - `libsrcml`: GPL-3.0, so the linked `srcml2token` binary is under GPL-3.0
