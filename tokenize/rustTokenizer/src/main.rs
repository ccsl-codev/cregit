// rust_tokenizer: Rust source tokenizer for the cregit pipeline. Emits one token per
// line for blameRepo and prettyPrint. See README.md for the output format and behavior.
//
// CLI: rust_tokenizer [--language=Rust] [--position] [--verbose] <source.rs>

use ra_ap_rustc_lexer::{tokenize, FrontmatterAllowed, TokenKind};
use std::env;
use std::fs;
use std::process::exit;

const REVISION: &str = "0.0.1";
const CREGIT_VERSION: &str = "0.0.1";

// Rust 2021 strict keywords: tokens in this set are emitted as `keyword|...`, all other
// identifiers as `identifier|...`.
static KEYWORDS: phf::Set<&'static str> = phf::phf_set! {
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "union", "unsafe", "use", "where", "while",
};

fn main() {
    let (path, emit_positions) = parse_args();
    let src = match fs::read_to_string(&path) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("rust_tokenizer: cannot read {}: {}", path, e);
            exit(1);
        }
    };
    print_pipe_separated_tokens(&src, emit_positions);
}

fn parse_args() -> (String, bool) {
    let mut path: Option<String> = None;
    let mut emit_positions = false;
    for arg in env::args().skip(1) {
        if arg == "--position" {
            emit_positions = true;
            continue;
        }
        if arg.starts_with("--language=") || arg == "--verbose" {
            continue;
        }
        if arg.starts_with("--") {
            eprintln!("rust_tokenizer: unknown option [{}]", arg);
            exit(2);
        }
        if path.is_none() {
            path = Some(arg);
        } else {
            eprintln!("rust_tokenizer: extra positional argument [{}]", arg);
            exit(2);
        }
    }
    let path = path.unwrap_or_else(|| {
        eprintln!("Usage: rust_tokenizer [--language=Rust] [--position] <source.rs>");
        exit(2);
    });
    (path, emit_positions)
}

fn print_pipe_separated_tokens(src: &str, emit_positions: bool) {
    let print_positionless_line = |body: &str| {
        if emit_positions {
            println!("-:-|{}", body);
        } else {
            println!("{}", body);
        }
    };

    print_positionless_line(&format!(
        "begin_unit|revision:{};language:Rust;cregit-version:{}",
        REVISION, CREGIT_VERSION
    ));

    let mut byte = 0usize;
    let mut line = 1usize;
    let mut col = 1usize;

    for tok in tokenize(src, FrontmatterAllowed::No) {
        let end = byte + tok.len as usize;
        let slice = &src[byte..end];

        if let Some(out) = classify(&tok.kind, slice) {
            if emit_positions {
                println!("{}:{}|{}", line, col, out);
            } else {
                println!("{}", out);
            }
        }

        // positions are counted in code points, not bytes
        for ch in slice.chars() {
            if ch == '\n' {
                line += 1;
                col = 1;
            } else {
                col += 1;
            }
        }
        byte = end;
    }

    print_positionless_line("end_unit");
    print_positionless_line("");
}

// None skips output (whitespace / Eof); the caller still advances the cursor. Every
// other kind emits one `kind|value` line so prettyPrint stays in sync.
fn classify(kind: &TokenKind, slice: &str) -> Option<String> {
    use TokenKind::*;
    match kind {
        Whitespace | Eof => None,

        Ident | RawIdent => {
            let label = if KEYWORDS.contains(slice) { "keyword" } else { "identifier" };
            Some(format!("{}|{}", label, slice))
        }
        InvalidIdent | UnknownPrefix => Some(format!("identifier|{}", slice)),

        Lifetime { .. } | RawLifetime | UnknownPrefixLifetime => {
            Some(format!("lifetime|{}", slice))
        }

        Literal { .. } => Some(format!("literal|{}", flatten(slice))),

        LineComment { .. } => Some(format!("comment|{}", slice)),
        BlockComment { .. } | Frontmatter { .. } => Some(format!("comment|{}", flatten(slice))),

        Semi | Comma | Dot | OpenParen | CloseParen | OpenBrace | CloseBrace
        | OpenBracket | CloseBracket | At | Pound | Tilde | Question | Colon | Dollar
        | Eq | Bang | Lt | Gt | Minus | And | Or | Plus | Star | Slash | Caret | Percent => {
            Some(format!("op|{}", slice))
        }

        GuardedStrPrefix | Unknown => Some(format!("unknown|{}", slice)),
    }
}

fn flatten(s: &str) -> String {
    s.replace(['\n', '\r'], " ")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn classify_all(src: &str) -> Vec<String> {
        let mut out = Vec::new();
        let mut byte = 0usize;
        for tok in tokenize(src, FrontmatterAllowed::No) {
            let end = byte + tok.len as usize;
            if let Some(line) = classify(&tok.kind, &src[byte..end]) {
                out.push(line);
            }
            byte = end;
        }
        out
    }

    #[test]
    fn keywords_are_split_from_identifiers() {
        assert_eq!(classify_all("fn main"), ["keyword|fn", "identifier|main"]);
        // case-sensitive: both spellings are distinct keywords
        assert_eq!(classify_all("self Self"), ["keyword|self", "keyword|Self"]);
        // a raw identifier is never a keyword even when it spells one
        assert_eq!(classify_all("r#fn"), ["identifier|r#fn"]);
    }

    #[test]
    fn whitespace_and_eof_emit_nothing() {
        assert_eq!(classify_all("a   b"), ["identifier|a", "identifier|b"]);
        assert!(classify_all("   \n\t").is_empty());
    }

    #[test]
    fn lifetimes_are_distinguished_from_char_literals() {
        assert_eq!(classify_all("'a"), ["lifetime|'a"]);
        assert_eq!(classify_all("'x'"), ["literal|'x'"]);
        assert_eq!(classify_all("'r#foo"), ["lifetime|'r#foo"]);
    }

    #[test]
    fn literals_and_comments_fold_embedded_newlines() {
        assert_eq!(classify_all("\"a\nb\""), ["literal|\"a b\""]);
        assert_eq!(classify_all("// hi"), ["comment|// hi"]);
        assert_eq!(classify_all("/* a\nb */"), ["comment|/* a b */"]);
    }

    #[test]
    fn operators_emit_op_lines() {
        assert_eq!(classify_all("!"), ["op|!"]);
        // multi-char operators are split into single-char tokens
        assert_eq!(classify_all("::"), ["op|:", "op|:"]);
    }

    #[test]
    fn newer_and_unrecognised_kinds_stay_in_sync() {
        assert_eq!(classify_all("x🦀"), ["identifier|x🦀"]); // InvalidIdent
        assert_eq!(classify_all("f\"x\""), ["identifier|f", "literal|\"x\""]); // UnknownPrefix
        assert_eq!(classify_all("##"), ["unknown|##"]); // GuardedStrPrefix
        assert_eq!(classify_all("№"), ["unknown|№"]); // Unknown
    }
}
