#!/usr/bin/env python3

import argparse
import hashlib
import os
import pathlib
import subprocess
import tempfile
import time


class Worker:
    def __init__(self, executable, memo_dir, tokenize_command):
        env = os.environ.copy()
        env["BFG_MEMO_DIR"] = str(memo_dir)
        env["BFG_TOKENIZE_CMD"] = tokenize_command
        self.process = subprocess.Popen(
            [str(executable)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        ready = self.process.stdout.readline()
        if ready != b"READY\n":
            raise AssertionError(f"worker startup failed: {ready!r}")

    def request(self, filename, full_path, contents, timeout_secs):
        filename_bytes = os.fsencode(filename)
        path_bytes = os.fsencode(full_path)
        orig_sha = hashlib.sha1(contents).hexdigest()
        header = (
            f"REQ {orig_sha} {len(contents)} {len(filename_bytes)} "
            f"{len(path_bytes)} {timeout_secs}\n"
        ).encode("ascii")
        self.process.stdin.write(header + filename_bytes + path_bytes + contents)
        self.process.stdin.flush()

        response = self.process.stdout.readline()
        parts = response.decode("ascii").rstrip("\n").split(" ")
        if len(parts) != 4 or parts[0] != "RES":
            raise AssertionError(f"invalid response header: {response!r}")
        exit_code, out_len, err_len = map(int, parts[1:])
        stdout = self._read_exact(out_len)
        stderr = self._read_exact(err_len)
        return exit_code, stdout, stderr

    def close(self):
        self.process.stdin.close()
        exit_code = self.process.wait(timeout=5)
        stderr = self.process.stderr.read()
        if exit_code != 0:
            raise AssertionError(
                f"worker exited {exit_code} on EOF: {stderr.decode(errors='replace')}"
            )

    def _read_exact(self, length):
        value = b""
        while len(value) < length:
            chunk = self.process.stdout.read(length - len(value))
            if not chunk:
                raise AssertionError("unexpected EOF from worker")
            value += chunk
        return value


def reference(token_by_sha, memo_dir, tokenize_command, fixture):
    contents = fixture.read_bytes()
    env = os.environ.copy()
    env.update(
        {
            "BFG_BLOB": hashlib.sha1(contents).hexdigest(),
            "BFG_FILENAME": fixture.name,
            "BFG_PATH": f"fixtures/{fixture.name}",
            "BFG_MEMO_DIR": str(memo_dir),
            "BFG_TOKENIZE_CMD": tokenize_command,
        }
    )
    return subprocess.run(
        [str(token_by_sha)],
        input=contents,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        check=False,
    )


def memo_file(memo_dir, contents):
    digest = hashlib.sha1(contents).hexdigest()
    return memo_dir / digest[:2] / digest[2:4] / digest


def assert_no_timeout_process(marker):
    time.sleep(0.2)
    result = subprocess.run(
        ["pgrep", "-f", marker],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 1:
        raise AssertionError(
            f"timeout process still running: {result.stdout.decode().strip()}"
        )


def in_process_checks(args, marker, expected_first):
    real_srcml = subprocess.run(["which", "srcml"], stdout=subprocess.PIPE, check=True).stdout.decode().strip()
    fake_srcml = args.temp_root / "fake-srcml.sh"
    fake_srcml.write_text(
        "#!/usr/bin/env bash\n"
        'src="${@: -3:1}"\n'
        f'grep -q SLEEP_MARKER "$src" && exec perl -e "sleep 100" {marker}\n'
        'grep -q ABORT_MARKER "$src" && kill -ABRT $$\n'
        f'exec {real_srcml} "$@"\n',
        encoding="utf-8",
    )
    fake_srcml.chmod(0o755)
    real_ctags = subprocess.run(["which", "ctags"], stdout=subprocess.PIPE, check=True).stdout.decode().strip()
    fake_ctags = args.temp_root / "fake-ctags.sh"
    fake_ctags.write_text(
        "#!/usr/bin/env bash\n"
        "echo HELPER_STDERR_MARKER >&2\n"
        f'exec {real_ctags} "$@"\n',
        encoding="utf-8",
    )
    fake_ctags.chmod(0o755)
    memo = args.temp_root / "inproc-memo"
    memo.mkdir()
    options = [o for o in args.tokenize_command.split() if not o.startswith(("--srcml=", "--ctags="))]
    command = " ".join(options + [f"--srcml={fake_srcml}", f"--ctags={fake_ctags}"])
    worker = Worker(args.worker, memo, command)

    started = time.monotonic()
    timed_out = worker.request("timeout.c", "fixtures/timeout.c", b"int SLEEP_MARKER;\n", 1)
    elapsed = time.monotonic() - started
    if timed_out[0] != 124 or elapsed > 3.0:
        raise AssertionError(f"in-process timeout response was {timed_out!r} after {elapsed:.2f}s")
    assert_no_timeout_process(marker)
    print(f"PASS in-process timeout exits 124 in {elapsed:.2f}s with no child left")

    crash = worker.request("crash.c", "fixtures/crash.c", b"int ABORT_MARKER;\n", 20)
    if crash[0] != 33 or b"killed by signal 6" not in crash[2]:
        raise AssertionError(f"srcml death was not reported as a parser crash: {crash!r}")
    if b"HELPER_STDERR_MARKER" not in crash[2]:
        raise AssertionError(f"helper stderr did not reach the request's stderr: {crash!r}")
    print("PASS in-process srcml crash exits 33 without output, with helper stderr")

    if any(memo.rglob("*")):
        raise AssertionError("failed in-process requests were memoized")
    recovered = worker.request(args.fixture[0].name, f"fixtures/{args.fixture[0].name}",
                               args.fixture[0].read_bytes(), 20)
    if recovered != (0, expected_first, b""):
        raise AssertionError(f"in-process worker did not recover: {recovered!r}")
    worker.close()
    print("PASS in-process worker recovers after timeout and crash")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", required=True, type=pathlib.Path)
    parser.add_argument("--token-by-sha", required=True, type=pathlib.Path)
    parser.add_argument("--tokenize-command", required=True)
    parser.add_argument("--fixture", action="append", required=True, type=pathlib.Path)
    parser.add_argument("--temp-root", required=True, type=pathlib.Path)
    args = parser.parse_args()

    worker_memo = args.temp_root / "worker-memo"
    worker_memo.mkdir()
    worker = Worker(args.worker, worker_memo, args.tokenize_command)

    expected_outputs = []
    for index, fixture in enumerate(args.fixture):
        reference_memo = args.temp_root / f"reference-memo-{index}"
        reference_memo.mkdir()
        expected = reference(
            args.token_by_sha, reference_memo, args.tokenize_command, fixture
        )
        if expected.returncode != 0:
            raise AssertionError(
                f"reference failed for {fixture}: "
                f"{expected.stderr.decode(errors='replace')}"
            )
        actual = worker.request(
            fixture.name,
            f"fixtures/{fixture.name}",
            fixture.read_bytes(),
            20,
        )
        if actual[0] != 0 or actual[1] != expected.stdout:
            raise AssertionError(f"worker output differs for {fixture}")
        if memo_file(worker_memo, fixture.read_bytes()).read_bytes() != expected.stdout:
            raise AssertionError(f"worker memo differs for {fixture}")
        expected_outputs.append(expected.stdout)
        print(f"PASS byte-identical: {fixture.name}")

    repeated = worker.request(
        args.fixture[0].name,
        f"fixtures/{args.fixture[0].name}",
        args.fixture[0].read_bytes(),
        20,
    )
    if repeated != (0, expected_outputs[0], b""):
        raise AssertionError("memo-hit response changed bytes")
    print("PASS repeated request uses identical memo bytes")

    unknown = worker.request("unknown.xyzzy", "fixtures/unknown.xyzzy", b"x", 20)
    if unknown[0] == 0 or b"unknown file extension" not in unknown[2]:
        raise AssertionError(f"unknown extension was not rejected: {unknown!r}")
    recovered = worker.request(
        args.fixture[0].name,
        f"fixtures/{args.fixture[0].name}",
        args.fixture[0].read_bytes(),
        20,
    )
    if recovered[0] != 0 or recovered[1] != expected_outputs[0]:
        raise AssertionError("worker did not recover after unknown extension")
    print("PASS unknown extension is non-fatal to worker")
    worker.close()

    timeout_script = args.temp_root / "timeout-parser.sh"
    marker = f"cregit-token-worker-timeout-{os.getpid()}"
    timeout_script.write_text(
        "#!/usr/bin/env bash\n"
        f"exec perl -e 'sleep 100' {marker}\n",
        encoding="utf-8",
    )
    timeout_script.chmod(0o755)
    timeout_memo = args.temp_root / "timeout-memo"
    timeout_memo.mkdir()
    timeout_worker = Worker(args.worker, timeout_memo, str(timeout_script))
    started = time.monotonic()
    timed_out = timeout_worker.request("timeout.c", "fixtures/timeout.c", b"int x;\n", 1)
    elapsed = time.monotonic() - started
    if timed_out[0] != 124 or elapsed > 3.0:
        raise AssertionError(f"timeout response was {timed_out[0]} after {elapsed:.2f}s")
    if any(timeout_memo.rglob("*")):
        raise AssertionError("timeout response was memoized")
    assert_no_timeout_process(marker)
    timeout_worker.close()
    print(f"PASS timeout exits 124 in {elapsed:.2f}s with no child left")

    in_process_checks(args, marker, expected_outputs[0])

    print("ALL TOKEN WORKER TESTS PASSED")


if __name__ == "__main__":
    main()
