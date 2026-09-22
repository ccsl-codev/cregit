// Cross-tokenizer framing contract: every tokenizer must emit the same line
// framing, because one downstream parser reads all of their output. Consumers
// split on `|` with a non-greedy first group, so an extra leading field silently
// shifts every column instead of failing.
//
// The reference is tokenizeSrcMl.pl's committed golden output, so these tests need
// neither srcml nor a C compiler. srcMLtoken's expected/*.token files are NOT the
// reference: they are srcml2token's TAB-separated intermediate output.

use std::collections::BTreeSet;
use std::fs;
use std::process::Command;

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_rust_tokenizer")
}

// Paths are relative to the crate root, which is cargo's CWD for integration tests.
const SRCML_GOLDEN_NOPOS: &str = "../../tests/t/expected/main.c.nopos.token";
const SRCML_GOLDEN_POS: &str = "../../tests/t/expected/main.c.token";
const LANGUAGES_PM: &str = "../CregitLanguages.pm";

fn read(path: &str) -> String {
    fs::read_to_string(path).unwrap_or_else(|e| panic!("cannot read {}: {}", path, e))
}

fn rust_tokenize(args: &[&str]) -> String {
    let out = Command::new(bin())
        .args(args)
        .output()
        .expect("failed to run rust_tokenizer");
    assert!(out.status.success(), "rust_tokenizer exited with {}", out.status);
    String::from_utf8(out.stdout).expect("stdout is not utf-8")
}

#[derive(Debug, PartialEq, Eq)]
enum Mode {
    Bare,
    Positioned,
}

/// A TAB is srcml2token's intermediate separator and must never reach a consumer.
fn reject_intermediate_tab_separator(lines: &[&str]) -> Result<(), String> {
    for (i, l) in lines.iter().enumerate() {
        if l.contains('\t') {
            return Err(format!("line {} contains a TAB: {:?}", i + 1, l));
        }
    }
    Ok(())
}

fn check_framing(stream: &str, mode: &Mode) -> Result<(), String> {
    let lines: Vec<&str> = stream.lines().collect();
    if lines.len() < 3 {
        return Err(format!("stream has only {} lines", lines.len()));
    }

    reject_intermediate_tab_separator(&lines)?;

    let first = lines[0];
    let (prefix, body) = split_prefix(first, mode)?;
    if prefix != "-:-" && mode == &Mode::Positioned {
        return Err(format!("begin_unit prefix is {:?}, expected \"-:-\"", prefix));
    }
    if !body.starts_with("begin_unit|revision:") {
        return Err(format!("first line body is {:?}, expected begin_unit|revision:…", body));
    }
    for key in ["language:", "cregit-version:"] {
        if !body.contains(key) {
            return Err(format!("begin_unit record is missing {:?}: {:?}", key, body));
        }
    }

    let n = lines.len();
    let (end_prefix, end_body) = split_prefix(lines[n - 2], mode)?;
    if end_body != "end_unit" {
        return Err(format!("second-to-last line body is {:?}, expected \"end_unit\"", end_body));
    }
    let expected_marker = match mode {
        Mode::Positioned => "-:-|",
        Mode::Bare => "",
    };
    if lines[n - 1] != expected_marker {
        return Err(format!(
            "last line is {:?}, expected the bare marker {:?}",
            lines[n - 1], expected_marker
        ));
    }
    if mode == &Mode::Positioned && end_prefix != "-:-" {
        return Err(format!("end_unit prefix is {:?}, expected \"-:-\"", end_prefix));
    }

    for (i, l) in lines.iter().enumerate() {
        if l.is_empty() || *l == "-:-|" {
            continue;
        }
        let (p, b) = split_prefix(l, mode).map_err(|e| format!("line {}: {}", i + 1, e))?;
        if b.is_empty() {
            return Err(format!("line {} has an empty body: {:?}", i + 1, l));
        }
        match mode {
            Mode::Bare => {
                if looks_like_position(b.split('|').next().unwrap_or("")) {
                    return Err(format!(
                        "line {} leads with a position field {:?} in a bare stream: {:?}",
                        i + 1,
                        b.split('|').next().unwrap(),
                        l
                    ));
                }
            }
            Mode::Positioned => {
                if p != "-:-" && !looks_like_position(p) {
                    return Err(format!("line {} has a bad position prefix {:?}", i + 1, p));
                }
            }
        }
    }
    Ok(())
}

fn split_prefix<'a>(line: &'a str, mode: &Mode) -> Result<(&'a str, &'a str), String> {
    match mode {
        Mode::Bare => Ok(("", line)),
        Mode::Positioned => match line.split_once('|') {
            Some((p, b)) => Ok((p, b)),
            None => Err(format!("no `|` separator in positioned line {:?}", line)),
        },
    }
}

/// A position prefix is `LINE:COL`, each component digits or a literal `-`.
/// All three of `N:N`, `N:-` and `-:-` occur in tokenizeSrcMl.pl's output.
fn looks_like_position(s: &str) -> bool {
    fn component(c: &str) -> bool {
        c == "-" || (!c.is_empty() && c.bytes().all(|b| b.is_ascii_digit()))
    }
    match s.split_once(':') {
        Some((l, c)) => component(l) && component(c),
        None => false,
    }
}

#[test]
fn srcml_golden_streams_satisfy_the_contract() {
    check_framing(&read(SRCML_GOLDEN_NOPOS), &Mode::Bare)
        .unwrap_or_else(|e| panic!("{} violates the contract: {}", SRCML_GOLDEN_NOPOS, e));
    check_framing(&read(SRCML_GOLDEN_POS), &Mode::Positioned)
        .unwrap_or_else(|e| panic!("{} violates the contract: {}", SRCML_GOLDEN_POS, e));
}

#[test]
fn rust_tokenizer_agrees_with_the_srcml_golden_framing() {
    for fixture in ["tests/fixtures/hello.rs", "tests/fixtures/edge_cases.rs"] {
        check_framing(&rust_tokenize(&[fixture]), &Mode::Bare)
            .unwrap_or_else(|e| panic!("{} (bare) violates the contract: {}", fixture, e));
        check_framing(&rust_tokenize(&["--position", fixture]), &Mode::Positioned)
            .unwrap_or_else(|e| panic!("{} (--position) violates the contract: {}", fixture, e));
    }
}

#[test]
fn the_two_tokenizers_use_the_same_separator_and_markers() {
    let c = read(SRCML_GOLDEN_NOPOS);
    let r = rust_tokenize(&["tests/fixtures/hello.rs"]);
    assert_eq!(
        c.lines().next().unwrap().split('|').next(),
        r.lines().next().unwrap().split('|').next(),
        "begin_unit records start differently"
    );
    let tail = |s: &str| -> Vec<String> { s.lines().rev().take(2).map(String::from).collect() };
    assert_eq!(tail(&c), tail(&r), "end-of-unit framing differs");

    let cp = read(SRCML_GOLDEN_POS);
    let rp = rust_tokenize(&["--position", "tests/fixtures/hello.rs"]);
    assert_eq!(tail(&cp), tail(&rp), "end-of-unit framing differs under --position");
    let sep = |s: &str| -> Option<char> {
        s.lines()
            .find(|l| looks_like_position(l.split(['|', '\t']).next().unwrap_or("")))
            .and_then(|l| l.chars().find(|c| *c == '|' || *c == '\t'))
    };
    assert_eq!(sep(&cp), Some('|'), "srcml golden separator changed");
    assert_eq!(sep(&rp), sep(&cp), "rust_tokenizer separator differs from srcml's");
}

#[test]
fn the_contract_check_rejects_the_defect_it_was_written_for() {
    let tab_separated_and_always_positioned = "-:-\tbegin_unit|revision:0.0.1;language:Rust;cregit-version:0.0.1\n\
                    1:1\tkeyword|fn\n\
                    -:-\tend_unit\n";
    assert!(
        check_framing(tab_separated_and_always_positioned, &Mode::Bare).is_err(),
        "the TAB-separated, unconditionally-positioned stream must be rejected"
    );
    assert!(
        check_framing(tab_separated_and_always_positioned, &Mode::Positioned).is_err(),
        "the TAB-separated stream must be rejected in positioned mode too"
    );
    let bare_stream_leaking_a_position = "begin_unit|revision:0.0.1;language:Rust;cregit-version:0.0.1\n\
                  1:1|keyword|fn\n\
                  end_unit\n\n";
    assert!(
        check_framing(bare_stream_leaking_a_position, &Mode::Bare).is_err(),
        "a bare stream carrying position fields must be rejected"
    );
    let missing_end_of_unit_marker = "begin_unit|revision:0.0.1;language:Rust;cregit-version:0.0.1\n\
                     keyword|fn\n\
                     end_unit\n";
    assert!(
        check_framing(missing_end_of_unit_marker, &Mode::Bare).is_err(),
        "a stream with no end-of-unit marker line must be rejected"
    );
}

/// Extract a Perl `our %NAME = ( 'k' => 'v', … );` block as key/value pairs.
/// One pair per line only: a `map`/`qw` one-liner reads as an empty hash here.
fn perl_hash(src: &str, name: &str) -> Vec<(String, String)> {
    let start = src
        .find(&format!("our %{} = (", name))
        .unwrap_or_else(|| panic!("no %{} in {}", name, LANGUAGES_PM));
    let rest = &src[start..];
    let end = rest.find("\n);").unwrap_or_else(|| panic!("unterminated %{}", name));
    let mut out = Vec::new();
    for line in rest[..end].lines().skip(1) {
        let line = line.split('#').next().unwrap_or("").trim();
        let Some((k, v)) = line.split_once("=>") else { continue };
        let k = k.trim().trim_matches('\'').trim();
        let v = v.trim().trim_end_matches(',').trim().trim_matches('\'').trim();
        if !k.is_empty() && !v.is_empty() {
            out.push((k.to_string(), v.to_string()));
        }
    }
    out
}

#[test]
fn every_masked_language_routes_to_a_tokenizer_this_file_pins() {
    let pm = read(LANGUAGES_PM);
    let masked: BTreeSet<String> = perl_hash(&pm, "MASKED_LANGUAGES")
        .into_iter()
        .filter(|(_, v)| v == "1")
        .map(|(k, _)| k)
        .collect();
    let parsers: Vec<(String, String)> = perl_hash(&pm, "LANG_PARSER_REL");

    let pinned = [
        "tokenizeSrcMl.pl",
        "rustTokenizer/target/release/rust_tokenizer",
    ];

    assert!(!masked.is_empty(), "parsed no masked languages");
    for lang in &masked {
        let parser = parsers
            .iter()
            .find(|(k, _)| k == lang)
            .map(|(_, v)| v.as_str())
            .unwrap_or_else(|| panic!("masked language {:?} has no parser", lang));
        assert!(
            pinned.contains(&parser),
            "masked language {:?} routes to {:?}, whose output framing is not pinned by \
             tests/framing.rs. Add a check_framing case for it before masking it in: an \
             unpinned tokenizer is how 36.5M rows were corrupted.",
            lang,
            parser
        );
    }

    for (lang, parser) in &parsers {
        if parser.contains("m4Tokenizer") || parser.contains("goTokenizer") {
            assert!(
                !masked.contains(lang),
                "{} still prints the TAB framing (m4Tokenizer/m4.py:311, \
                 goTokenizer/gotoken.go:39) and must not be masked in until it emits `|`",
                parser
            );
        }
    }
}
