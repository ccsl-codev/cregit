// Smoke test: run the built binary on a fixture and check the stream is well-formed.

use std::process::{Command, Output};

fn bin() -> &'static str {
    env!("CARGO_BIN_EXE_rust_tokenizer")
}

// Run the binary with arbitrary args and return the raw Output (for exit-code checks).
fn run_args(args: &[&str]) -> Output {
    Command::new(bin())
        .args(args)
        .output()
        .expect("failed to run rust_tokenizer")
}

fn run(fixture: &str) -> String {
    let out = run_args(&[&format!("tests/fixtures/{}", fixture)]);
    assert!(out.status.success(), "binary exited with {}", out.status);
    String::from_utf8(out.stdout).expect("stdout is not utf-8")
}

fn run_pos(fixture: &str) -> String {
    let out = run_args(&["--position", &format!("tests/fixtures/{}", fixture)]);
    assert!(out.status.success(), "binary exited with {}", out.status);
    String::from_utf8(out.stdout).expect("stdout is not utf-8")
}

// Assert on WHOLE lines, not substrings. The bug this file failed to catch was an extra
// leading `line:col<TAB>` field, and a substring assertion cannot see a line's prefix.
#[track_caller]
fn assert_has_line(s: &str, expected: &str) {
    assert!(
        s.lines().any(|l| l == expected),
        "no line equal to {:?} in output:\n{}",
        expected,
        s
    );
}

#[test]
fn hello_has_unit_markers_and_expected_tokens() {
    let s = run("hello.rs");
    let mut lines = s.lines();
    // No position prefix without --position: this is the format the pipeline consumes.
    assert_eq!(
        lines.next().unwrap(),
        "begin_unit|revision:0.0.1;language:Rust;cregit-version:0.0.1"
    );
    // ... and it closes with `end_unit` plus the bare end-of-unit marker line.
    let tail: Vec<&str> = s.lines().rev().take(2).collect();
    assert_eq!(tail, vec!["", "end_unit"]);
    // values are emitted raw (no quoting), matching what prettyPrint expects
    assert_has_line(&s, "keyword|fn");
    assert_has_line(&s, "identifier|main");
    assert_has_line(&s, "literal|\"hello, world\"");
}

#[test]
fn hello_under_position_prefixes_every_line_with_pipe() {
    let s = run_pos("hello.rs");
    let mut lines = s.lines();
    assert_eq!(
        lines.next().unwrap(),
        "-:-|begin_unit|revision:0.0.1;language:Rust;cregit-version:0.0.1"
    );
    let tail: Vec<&str> = s.lines().rev().take(2).collect();
    assert_eq!(tail, vec!["-:-|", "-:-|end_unit"]);
    assert_has_line(&s, "2:1|keyword|fn");
    assert_has_line(&s, "2:4|identifier|main");
    assert_has_line(&s, "3:15|literal|\"hello, world\"");
}

#[test]
fn edge_cases_classify_correctly() {
    let s = run("edge_cases.rs");
    // lifetimes vs char literals
    assert_has_line(&s, "lifetime|'a");
    assert_has_line(&s, "literal|'x'");
    // raw / byte literals — all unified under `literal|` for prettyPrint
    assert_has_line(&s, "literal|r#\"raw \"quoted\" string\"#");
    assert_has_line(&s, "literal|b'y'");
    assert_has_line(&s, "literal|b\"bytes\"");
    assert_has_line(&s, "literal|br#\"raw bytes\"#");
    // raw and unicode identifiers
    assert_has_line(&s, "identifier|r#type");
    assert_has_line(&s, "identifier|café");
    // nested block comment kept as one token, raw text
    assert_has_line(&s, "comment|/* outer /* inner */ outer */");
    // numeric literals with suffixes/underscores
    assert_has_line(&s, "literal|1_000_000u64");
    assert_has_line(&s, "literal|2.5f64");
}

#[test]
fn unicode_identifier_advances_columns_by_code_point_not_byte() {
    // `fn café()` is on line 18; `café` is 4 chars / 5 bytes. The `(` must be at col 8
    // (1 + "fn " + 4 chars), not col 9 — which is what counting bytes would give.
    let s = run_pos("edge_cases.rs");
    assert_has_line(&s, "18:8|op|(");
}

// --- unhappy paths: the binary must report errors with the documented exit codes ---

#[test]
fn missing_input_file_exits_1() {
    let out = run_args(&["does/not/exist.rs"]);
    assert_eq!(out.status.code(), Some(1));
    let err = String::from_utf8_lossy(&out.stderr);
    assert!(err.contains("cannot read"), "stderr was: {}", err);
}

#[test]
fn unknown_option_exits_2() {
    let out = run_args(&["--bogus", "tests/fixtures/hello.rs"]);
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("unknown option"));
}

#[test]
fn extra_positional_argument_exits_2() {
    let out = run_args(&["tests/fixtures/hello.rs", "tests/fixtures/edge_cases.rs"]);
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("extra positional"));
}

#[test]
fn no_path_prints_usage_and_exits_2() {
    let out = run_args(&[]);
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).contains("Usage"));
}

#[test]
fn dispatcher_flags_are_accepted() {
    // tokenize.pl passes these; the binary must accept them and still succeed. Note
    // --position is HONORED, not ignored: tokenizeSrcMl.pl gates its position prefix on
    // the same flag, and the pipeline (run_pipeline_process.sh:891) passes neither.
    let out = run_args(&["--language=Rust", "--position", "--verbose", "tests/fixtures/hello.rs"]);
    assert!(out.status.success(), "binary exited with {}", out.status);
    assert_has_line(&String::from_utf8_lossy(&out.stdout), "2:1|keyword|fn");
}

#[test]
fn language_and_verbose_alone_do_not_add_positions() {
    let out = run_args(&["--language=Rust", "--verbose", "tests/fixtures/hello.rs"]);
    assert!(out.status.success(), "binary exited with {}", out.status);
    assert_has_line(&String::from_utf8_lossy(&out.stdout), "keyword|fn");
}
