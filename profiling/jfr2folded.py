#!/usr/bin/env python3
"""Turn a JFR recording into folded stacks, one file per event kind.

    profiling/jfr2folded.py --out DIR recording.jfr [recording.jfr ...]
    profiling/jfr2folded.py --out DIR --json dump.json

Folded stacks are the input format every flamegraph renderer reads, so this is
the conversion step and not a report:

    flamegraph.pl DIR/ExecutionSample.folded > cpu.svg
    inferno-flamegraph < DIR/ExecutionSample.folded > cpu.svg

Neither renderer is installed here (PROFILING.md has the devenv.nix diff), and
speedscope.app reads a .folded file directly with nothing installed at all.

Four kinds answer four different questions:
    ExecutionSample        where CPU goes
    JavaMonitorEnter       time lost to a contended lock, weighted by duration
    ThreadPark             time lost waiting, which is not the same thing
    ObjectAllocationSample allocation pressure, weighted by bytes

The weight matters. A CPU flamegraph counts samples; a contention flamegraph
that counted samples would rank a lock taken often above a lock held long.
"""

import argparse
import json
import os
import subprocess
import sys
from collections import defaultdict

KINDS = {
    "jdk.ExecutionSample": ("ExecutionSample", None),
    "jdk.NativeMethodSample": ("NativeMethodSample", None),
    "jdk.JavaMonitorEnter": ("JavaMonitorEnter", "duration"),
    "jdk.ThreadPark": ("ThreadPark", "duration"),
    "jdk.ObjectAllocationSample": ("ObjectAllocationSample", "weight"),
}


def frames_of(event):
    trace = event.get("stackTrace") or {}
    for frame in reversed(trace.get("frames") or []):
        method = frame.get("method") or {}
        owner = (method.get("type") or {}).get("name") or ""
        name = method.get("name") or "?"
        yield f"{owner}.{name}" if owner else name


def weight_of(event, field):
    if field is None:
        return 1
    raw = event.get(field)
    if isinstance(raw, dict):
        raw = raw.get("value", raw.get("nanos", 0))
    if isinstance(raw, str):
        # jfr prints a duration as e.g. "3.14 ms"; nanoseconds keep the ranking.
        parts = raw.split()
        try:
            value = float(parts[0])
        except (ValueError, IndexError):
            return 1
        unit = parts[1] if len(parts) > 1 else "ns"
        scale = {"ns": 1, "us": 1e3, "ms": 1e6, "s": 1e9}.get(unit, 1)
        return max(1, int(value * scale))
    try:
        return max(1, int(raw))
    except (TypeError, ValueError):
        return 1


def events_of(doc):
    """Tolerate both jfr --json shapes: a recording object or a bare list."""
    if isinstance(doc, list):
        return doc
    recording = doc.get("recording")
    if isinstance(recording, dict):
        return recording.get("events") or []
    return doc.get("events") or []


def fold(events):
    out = defaultdict(lambda: defaultdict(int))
    for event in events:
        kind = event.get("type") or event.get("eventType")
        if kind not in KINDS:
            continue
        label, weight_field = KINDS[kind]
        values = event.get("values", event)
        stack = ";".join(frames_of(values))
        if not stack:
            continue
        out[label][stack] += weight_of(values, weight_field)
    return out


def dump_json(path):
    proc = subprocess.run(
        ["jfr", "print", "--json", "--events", ",".join(KINDS), path],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit(f"jfr print failed on {path}: {proc.stderr.strip()}")
    return json.loads(proc.stdout)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("recordings", nargs="*")
    ap.add_argument("--out", required=True)
    ap.add_argument("--json", help="a jfr print --json dump, instead of a .jfr")
    args = ap.parse_args()

    if not os.path.isabs(args.out):
        sys.exit("--out must be an absolute path")
    os.makedirs(args.out, exist_ok=True)

    totals = defaultdict(lambda: defaultdict(int))
    sources = []
    if args.json:
        with open(args.json) as fh:
            sources.append(json.load(fh))
    for path in args.recordings:
        sources.append(dump_json(path))
    if not sources:
        sys.exit("give at least one recording or --json")

    for doc in sources:
        for label, stacks in fold(events_of(doc)).items():
            for stack, weight in stacks.items():
                totals[label][stack] += weight

    if not totals:
        sys.exit("no stack-bearing events found; was the recording taken with "
                 "settings=profile?")

    for label, stacks in sorted(totals.items()):
        path = os.path.join(args.out, f"{label}.folded")
        with open(path, "w") as fh:
            for stack, weight in sorted(stacks.items(), key=lambda kv: -kv[1]):
                fh.write(f"{stack} {weight}\n")
        print(f"{path}  {len(stacks)} stacks  {sum(stacks.values())} weight")


if __name__ == "__main__":
    main()
