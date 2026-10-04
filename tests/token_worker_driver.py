#!/usr/bin/env python3
"""Checks tokenizeByBlobId/tokenWorker.pl against tokenizeByBlobId/tokenBySha.pl."""

import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import time

TIMEOUT_EXIT = 124
PARSER_CRASH_EXIT = 33


class Worker:
    def __init__(self, executable, memo_dir, tokenize_command):
        env = dict(os.environ, BFG_MEMO_DIR=str(memo_dir), BFG_TOKENIZE_CMD=tokenize_command)
        self.process = subprocess.Popen(
            [str(executable)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env
        )
        ready = self.process.stdout.readline()
        if ready != b"READY\n":
            raise AssertionError(f"worker did not start: {ready!r}")

    def request(self, filename, contents, timeout_secs=20):
        name = os.fsencode(filename)
        header = f"REQ {len(contents)} {len(name)} {timeout_secs}\n".encode()
        self.process.stdin.write(header + name + contents)
        self.process.stdin.flush()
        response = self.process.stdout.readline().decode().split()
        if len(response) != 4 or response[0] != "RES":
            raise AssertionError(f"malformed response header: {response!r}")
        exit_code, out_len, err_len = map(int, response[1:])
        return exit_code, self.process.stdout.read(out_len), self.process.stdout.read(err_len)

    def close(self):
        self.process.stdin.close()
        if self.process.wait(timeout=5) != 0:
            raise AssertionError(f"worker exited {self.process.returncode} on EOF")


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print(f"PASS {message}")


def token_by_sha(args, memo_dir, fixture):
    env = dict(
        os.environ,
        BFG_FILENAME=fixture.name,
        BFG_MEMO_DIR=str(memo_dir),
        BFG_TOKENIZE_CMD=args.tokenize_command,
    )
    return subprocess.run(
        [str(args.token_by_sha)], input=fixture.read_bytes(), capture_output=True, env=env
    )


def memo_file(memo_dir, contents):
    digest = hashlib.sha1(contents).hexdigest()
    return memo_dir / digest[:2] / digest[2:4] / digest


def new_dir(args, name):
    path = args.temp_root / name
    path.mkdir()
    return path


def write_script(path, body):
    path.write_text("#!/usr/bin/env bash\n" + body, encoding="utf-8")
    path.chmod(0o755)
    return path


def no_process_left(marker):
    time.sleep(0.2)
    return subprocess.run(["pgrep", "-f", marker], capture_output=True).returncode == 1


def timed_request(worker, filename, contents):
    started = time.monotonic()
    response = worker.request(filename, contents, timeout_secs=1)
    return response, time.monotonic() - started


def check_same_as_token_by_sha(args):
    memo = new_dir(args, "worker-memo")
    worker = Worker(args.worker, memo, args.tokenize_command)
    for index, fixture in enumerate(args.fixture):
        expected = token_by_sha(args, new_dir(args, f"reference-memo-{index}"), fixture)
        actual = worker.request(fixture.name, fixture.read_bytes())
        check(actual[0] == expected.returncode, f"{fixture.name}: exit {expected.returncode}")
        if expected.returncode == 0:
            check(actual[1] == expected.stdout, f"{fixture.name}: same tokens")
            check(
                memo_file(memo, fixture.read_bytes()).read_bytes() == expected.stdout,
                f"{fixture.name}: same memo file",
            )

    first = args.fixture[0]
    expected = memo_file(memo, first.read_bytes()).read_bytes()
    check(worker.request(first.name, first.read_bytes()) == (0, expected, b""), "a memo hit gives the same bytes")
    unknown = worker.request("unknown.xyzzy", b"x")
    check(unknown[0] == 255 and b"unknown file extension" in unknown[2], "an unknown extension fails")
    check(worker.request(first.name, first.read_bytes())[0] == 0, "the worker serves the next request")
    worker.close()


def check_timeout(args, marker):
    memo = new_dir(args, "timeout-memo")
    script = write_script(args.temp_root / "hang.sh", f"exec perl -e 'sleep 100' {marker}\n")
    worker = Worker(args.worker, memo, str(script))
    (exit_code, out, _), elapsed = timed_request(worker, "hang.c", b"int x;\n")
    check(exit_code == TIMEOUT_EXIT and out == b"" and elapsed < 3, f"a hung tokenizer exits {TIMEOUT_EXIT}")
    check(no_process_left(marker), "the timeout kills the tokenizer's process group")
    check(not any(memo.rglob("*")), "a timeout memoizes nothing")
    worker.close()


def in_process_command(args, marker):
    options = args.tokenize_command.split()
    real = next(o.split("=", 1)[1] for o in options if o.startswith("--srcml2token="))
    srcml2token = write_script(
        args.temp_root / "srcml2token.sh",
        'src="${@: -1}"\n'
        f'grep -qs HANG_MARKER "$src" && exec perl -e "sleep 100" {marker}\n'
        'grep -qs ABORT_MARKER "$src" && kill -ABRT $$\n'
        f'exec {real} "$@"\n',
    )
    ctags = write_script(
        args.temp_root / "ctags.sh", f'echo CTAGS_STDERR >&2\nexec {shutil.which("ctags")} "$@"\n'
    )
    options = [o for o in options if not o.startswith(("--srcml2token=", "--ctags="))]
    return " ".join(options + [f"--srcml2token={srcml2token}", f"--ctags={ctags}"])


def check_in_process_failures(args, marker):
    memo = new_dir(args, "in-process-memo")
    worker = Worker(args.worker, memo, in_process_command(args, marker))
    (exit_code, _, _), elapsed = timed_request(worker, "hang.c", b"int HANG_MARKER;\n")
    check(exit_code == TIMEOUT_EXIT and elapsed < 3, f"in process, a hung srcml2token exits {TIMEOUT_EXIT}")
    check(no_process_left(marker), "and is killed")

    exit_code, out, err = worker.request("abort.c", b"int ABORT_MARKER;\n")
    check(exit_code == PARSER_CRASH_EXIT and out == b"", f"in process, a srcml2token signal death exits {PARSER_CRASH_EXIT}")
    check(b"killed by signal 6" in err and b"CTAGS_STDERR" in err, "with the srcml2token and ctags stderr")
    check(not any(memo.rglob("*")), "and nothing is memoized")

    fixture = args.fixture[0]
    expected = token_by_sha(args, new_dir(args, "in-process-reference"), fixture).stdout
    check(worker.request(fixture.name, fixture.read_bytes())[1] == expected, "the worker recovers")
    worker.close()


def check_stale_srcml2token(args):
    stale = write_script(args.temp_root / "srcml2token", "exit 0\n")
    command = f"{args.tokenize_command} --srcml2token={stale}"
    env = dict(os.environ, BFG_MEMO_DIR=str(new_dir(args, "stale-memo")), BFG_TOKENIZE_CMD=command)
    started = subprocess.run([str(args.worker)], input=b"", capture_output=True, env=env)
    check(started.returncode != 0 and b"READY" not in started.stdout, "a srcml2token without --libsrcml-path stops the start")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", required=True, type=pathlib.Path)
    parser.add_argument("--token-by-sha", required=True, type=pathlib.Path)
    parser.add_argument("--tokenize-command", required=True)
    parser.add_argument("--fixture", action="append", required=True, type=pathlib.Path)
    parser.add_argument("--temp-root", required=True, type=pathlib.Path)
    args = parser.parse_args()

    marker = f"cregit-token-worker-{os.getpid()}"
    check_same_as_token_by_sha(args)
    check_timeout(args, marker)
    check_in_process_failures(args, marker)
    check_stale_srcml2token(args)
    print("ALL TOKEN WORKER TESTS PASSED")


if __name__ == "__main__":
    main()
