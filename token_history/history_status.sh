#!/usr/bin/env bash
# Print the progress of a token_history run: history_status.sh <out-dir>
set -euo pipefail
f="${1:?usage: history_status.sh <out-dir>}/history-progress.json"
[ -f "$f" ] || { echo "no progress file at $f"; exit 1; }
python3 - "$f" <<'PY'
import json, sys, time
p = json.load(open(sys.argv[1]))
pct = 100 * p["paths_done"] / max(p["paths_total"], 1)
eta = p.get("eta_s")
print(f"state      {p['state']} (updated {p['updated']})")
print(f"paths      {p['paths_done']:,} / {p['paths_total']:,} ({pct:.1f}%)")
print(f"tokens     {p['tokens']:,} in {p['rows']:,} rows")
print(f"rate       {p['paths_per_min']} paths/min, elapsed {p['elapsed_s'] / 3600:.1f} h")
print(f"eta        {'?' if eta is None else f'{eta / 3600:.1f} h'}")
print(f"flags      missing parents {p['missing_parents']}, unaligned merges "
      f"{p['unaligned_merges']}, unmapped mainline {p['unmapped_mainline']}")
for s, path in p["slowest"]:
    print(f"slow       {s:7.1f} s  {path}")
PY
