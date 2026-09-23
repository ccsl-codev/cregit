# Profiling cregit

Nothing in this document runs during an ordinary corpus run. Every profiler here
is off unless a variable is set or a script in `profiling/` is invoked directly,
and a normal run pays one string comparison per hot step.

Two steps are worth instrumenting. Step 2 (tokenize) is 88-95% of wall time on a
full run. Step 7 (blame) became a second hot spot when `git blame` gained
`-C100`, and its cost tracks the individual file rather than the project.

## Why this exists

Every performance claim in this project was obtained by wall clock, `top`, `ps`,
`load1`, or by reading source. No flamegraph, no per-function attribution and no
allocation profile has ever been taken. Two of the most-cited figures were later
withdrawn for exactly that reason: the "~1.7 of 16 cores" number was a `top`
per-process sum that could not see processes with a ~30 ms median lifetime, and
the sharding A/B ran on a differently-loaded box. The purpose of this harness is
that the next optimisation is chosen from a profile.

## Turning each profiler on

| layer | how | dependency |
| --- | --- | --- |
| JVM (`blobExec`) | `CREGIT_PROFILE=tokenize`, or `profiling/profile-tokenize.sh` | none, JFR is in JDK 21 |
| perl (steps 2 and 7) | `CREGIT_PROFILE=blame`, or `profiling/profile-blame.sh` | `Devel::NYTProf`, absent — degrades to a no-op |
| C++ (`srcml2token`) | `profiling/profile-native.sh` | `perf`, absent — falls back to callgrind |
| python (`generate_dataset`) | `profiling/profile-dataset.sh` | none, `cProfile` is standard library |
| the per-blob chain | `profiling/chain-cost.pl` | none |

Three of the four suggested tools turned out not to be installed on this host:
`perf` is not packaged and not in the nix store, `Devel::NYTProf` is not in
`perlEnv`, and no flamegraph renderer (`flamegraph.pl`, `inferno-flamegraph`)
exists either. The proposed `devenv.nix` diff is at the end. Everything else
works today, and `chain-cost.pl` — which answers the highest-value question —
needs no profiler at all.

### In a pipeline run

```sh
CREGIT_PROFILE=tokenize CREGIT_PROFILE_DIR=/scratch/prof/dpdk \
    ./run_pipeline_process.sh --repo-url ... --work /scratch/work/dpdk 2
```

`CREGIT_PROFILE` takes `tokenize`, `blame` or `both`. `CREGIT_PROFILE_DIR` must
be absolute and must not be inside the work directory: step 1 deletes `$WORK`,
so a recording written there is a recording lost. Both are refused with a
message rather than silently accepted.

`CREGIT_PROFILE=tokenize` arms JFR through `JDK_JAVA_OPTIONS`, which the JDK
launcher reads for itself. That is why no `java` command line in
`run_pipeline_process.sh` changes — and also why every JVM from step 2 onward is
recorded, one `.jfr` per pid.

### One line per layer, outside a pipeline run

```sh
# step 2, one repository, JFR on; --parallelism pins the JVM pool
profiling/profile-tokenize.sh --src /scratch/orig.git --scratch /scratch/tok \
    --out /scratch/prof/tok --perl --parallelism 16

# step 7, one working clone; per-file cost table always, NYTProf when present
profiling/profile-blame.sh --repo /scratch/cregit --blame-dir /scratch/blame \
    --out /scratch/prof/blame --jobs 4

# the C++ transcoder on one blob
make -C tokenize/srcMLtoken srcml2token-profile
profiling/profile-native.sh --input some.c --out /scratch/prof/native

# step 10
profiling/profile-dataset.sh --out /scratch/prof/ds -- --cregit-db ... --output ...

# the per-blob cost ladder: eight rungs, each adding one layer of the chain
profiling/chain-cost.pl --input some.c --out /scratch/prof/chain --reps 20
```

## Where output lands

Only where you said. Every script takes `--out`, requires an absolute path, and
refuses a path inside the work or blame directory it is profiling.

| file | what it is |
| --- | --- |
| `<out>/tokenize.<pid>.jfr` | JFR recording, one per JVM |
| `<out>/ExecutionSample.folded` | folded stacks, CPU |
| `<out>/JavaMonitorEnter.folded` | folded stacks, lock contention, weighted by nanoseconds held |
| `<out>/ThreadPark.folded` | folded stacks, waiting — not the same as contention |
| `<out>/ObjectAllocationSample.folded` | folded stacks, allocation, weighted by bytes |
| `<out>/nytprof.<tag>.out.<pid>` | NYTProf raw, one per worker |
| `<out>/blame.perfile.tsv` | wall and CPU seconds per file, sorted |
| `<out>/chain-cost.tsv` | the per-blob ladder and its differences |
| `<out>/srcml2token.folded` or `callgrind.annotated` | the native layer |
| `<out>/dataset.pstats`, `dataset.txt` | cProfile |
| `<out>/<tag>.times.txt` | wall against children CPU, from the bash `times` builtin |

`/usr/bin/time` is not installed on this host, which is why CPU accounting goes
through `times` in bash and `times()` in perl instead.

## Getting to a flamegraph

Each layer reaches folded stacks, the one format every renderer reads. Render
with `flamegraph.pl file.folded > out.svg`, or `inferno-flamegraph < file.folded`,
or by opening the `.folded` file at speedscope.app, which installs nothing.

| layer | recording | conversion |
| --- | --- | --- |
| JVM | `.jfr` | `profiling/jfr2folded.py --out DIR rec.jfr`, which shells out to `jfr print --json` |
| C++ | `perf.data` | `perf script \| stackcollapse-perf.pl > x.folded`, done by `profile-native.sh` |
| C++, no perf | `callgrind.out` | `callgrind_annotate` — a ranked function table, **not** a flamegraph |
| perl | `nytprof.*.out` | `nytprofhtml -f nytprof.blame.out.<pid>`, whose HTML report embeds a subroutine flamegraph |
| python | `dataset.pstats` | `pstats` table; `gprof2dot` for a call graph |

The weighting is the part that is easy to get wrong. A CPU flamegraph counts
samples, but a contention flamegraph that counted samples would rank a lock
taken often above a lock held long. `jfr2folded.py` weights
`JavaMonitorEnter` and `ThreadPark` by duration in nanoseconds and
`ObjectAllocationSample` by bytes.

JFR is armed with `settings=profile` plus explicit overrides, not a `.jfc` file,
so the whole configuration is one greppable string in `profiling/lib.sh`. The
monitor and park thresholds are lowered from 10 ms to 1 ms: a lock held briefly
but taken once per blob is invisible at 10 ms, and that is exactly the shape
being looked for in `Walker.scala`'s `dbLock`.

## Ranked measurement plan

Run these on an idle box, in this order. Each names the command, and the
decision it settles.

### 1. Is the 82.2 ms per-blob fixed cost real, and is 54.8 ms of it perl and shell startup?

```sh
profiling/chain-cost.pl --input <a median .c blob> --out /scratch/prof/chain --reps 30
profiling/chain-cost.pl --input <a large .c blob>  --out /scratch/prof/chain-big --reps 10
```

Decides whether to build the persistent tokenizer worker — the only
medium-effort lever with a claimed 1.4-1.6x. That estimate is arithmetic on
54.8/82.2, and no derivation of either number is recorded anywhere in the
project. `chain-cost.pl` derives them: `bysha - native` is precisely the perl and
shell overhead a persistent worker would remove. If it comes out near 54.8 ms,
build the worker. If it comes out at 15 ms, the lever is worth ~1.1x and the
effort belongs elsewhere.

This is first, ahead of the idle-core question, because the idle-core premise has
already been withdrawn in this project's own record — the `/proc/stat` sampler
found 64.7% of pipeline samples at or above 90% CPU, and `torvalds__linux`
tokenized at ~43 processes in its tree. Nothing has withdrawn the 82.2 ms claim,
and it is the one that currently decides where engineering effort goes.

### 2. Does `ctags` cost a second full parse of every blob?

Same two commands as question 1; read `ctags_and_wrapper_ms`, which is
`srcmlpl - native`.

Found by reading source, never measured. `tokenizeSrcMl.pl` calls `ctags` in
`Read_Declarations` and `srcml` in `Tokenize`, so every blob is parsed twice by
two different parsers. If the `ctags` half is a third of chain cost, caching or
eliminating it is a larger and simpler win than the persistent worker. Cheap to
answer because the same run answers question 1.

### 3. Where does the JVM pool's time go, and what bounds it?

```sh
profiling/profile-tokenize.sh --src /scratch/orig.git --scratch /scratch/tok \
    --out /scratch/prof/p16 --parallelism 16
profiling/jfr2folded.py --out /scratch/prof/p16 /scratch/prof/p16/tokenize.*.jfr
```

Then compare the four folded files. This is the reframed version of the
idle-core question, and it is the only measurement that tells the four standing
candidate causes apart:

- serialisation inside the JVM → `JavaMonitorEnter.folded` dominated by
  `Mapping.*`, the `dbLock` monitor in `Walker.scala`;
- the queue starving or stalling → `ThreadPark.folded` dominated by
  `ArrayBlockingQueue.take` or `put`;
- I/O wait on the object store → `jdk.FileRead` events, and jgit frames in
  `ExecutionSample.folded`;
- process spawn cost → `ProcessBuilder`/`ProcessImpl` frames in
  `ExecutionSample.folded`, cross-checked against question 1.

Decides whether to widen the pool, shrink the critical section, batch the SQLite
writes, or leave the JVM alone and work on the chain.

Run it twice, `--parallelism 1` and `--parallelism 16`, on the same input. If
per-blob cost is flat between the two, the chain is the bound and the JVM is not.

### 4. Which files dominate step 7, and by how much?

```sh
profiling/profile-blame.sh --repo /scratch/cregit --blame-dir /scratch/blame \
    --out /scratch/prof/blame --jobs 4 --overwrite
sort -rn /scratch/prof/blame/blame.perfile.tsv | head -40
```

The observed spread across projects is 44x and per-project size does not predict
it; one 1,666,007-line generated file consumed over six hours of CPU. A step
total says almost nothing here. The per-file table says whether the tail is a
handful of generated files, in which case the decision is about those files and
not about `git blame`, and it needs no profiler at all.

Suggested first targets, from the 15-second resource samples in
`cregit-token-pipeline/resources.tsv`: `allegro__hermes` (~13 min, exercises
both the parallel tokenize regime and the serial blame tail),
`util-linux__util-linux` (~21 min, heavier on tokenize), `pygame__pygame`
(~62 min, blame-dominated). Not `torvalds__linux` (~52 h) or
`tencent__tencentkona-21` (~44 h).

### 5. Where does `git blame -C100` spend its time on one pathological file?

```sh
profiling/profile-blame.sh --repo /scratch/cregit --blame-dir /scratch/blame \
    --out /scratch/prof/one --jobs 1 --mask 'unicode_db\.c$' --overwrite
```

With `Devel::NYTProf` installed this separates `formatBlame.pl`'s own line
parsing from the `git blame` child. Decides whether any of the cost is ours: if
`formatBlame.pl` is 2% and `git blame` is 98%, the perl is not worth touching and
`-C100` is a decision about git, not about this repository. Lower than question 4
because the answer is already strongly suspected and the run is expensive.

### 6. What fraction of step 10 is python rather than DuckDB?

```sh
profiling/profile-dataset.sh --out /scratch/prof/ds -- <the step 10 arguments>
```

Step 10 is 1-5% of a full run, so this is last. It is worth one run because
DuckDB appears to `cProfile` as a single C call, which immediately bounds how
much of the step python could ever be responsible for.

### Not on this list

`--gc none` is already decided: `git gc --prune=now` sits inside the step-2
timer and accounts for roughly 10-11% of it, measured. That is a flag flip, not
a profiling question.

History sharding is also settled. It was withdrawn, and the A/B that appeared to
show it 1.9x slower was itself contaminated. Its purpose is bounding resident
memory, not speed.

## Proposed devenv.nix diff

Not applied: `devenv.nix` pins srcML 1.1.0 and is off limits in this change. Each
of the three additions unblocks one row of the table above.

```diff
@@ perlEnv @@
   perlEnv = pkgs.perl.withPackages (p: [
     p.DBI
     p.DBDSQLite
+    # Statement and subroutine profiling for the perl layers. Optional by
+    # construction: profiling/lib.sh probes for it and degrades to a no-op, so
+    # step 7 keeps running on core perl and git on a machine without it.
+    p.DevelNYTProf
     EmailFind
     HTMLFromText
     p.HTMLParser
     p.SetScalar
     p.TextAutoformat
   ]);
@@ packages @@
     srcml
     pkgs.universal-ctags
     pkgs.xercesc
     pkgs.sbt
+
+    # Profiling only; nothing in the pipeline calls either.
+    # perf samples srcml2token; flamegraph renders the folded stacks every
+    # layer of the harness emits.
+    pkgs.linuxPackages.perf
+    pkgs.flamegraph
```

`perf` also needs `kernel.perf_event_paranoid` at 2 or lower to sample a
user-space process you own. It is already 2 on this host, so user-space stacks
work and kernel symbols do not. Until `perf` lands, `profile-native.sh` falls
back to callgrind, which is installed, gives a ranked function table, and does
not give a flamegraph.
