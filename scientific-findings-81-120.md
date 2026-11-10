# WorldgenD: Findings #81-120

**Continuation from [`scientific-findings-41-80.md`](scientific-findings-41-80.md).** Same methodology. Seed `69`, hardcoded, every run.

From here on, each entry leads with the plain answer and keeps exact flags and file paths in a **Method** block at the end.

---

## 81. Why CPU savings seemed not to shorten the run: they do, but the "invisible" wins never saved CPU

**Short answer.** Orion v5.7 is limited by how much work its 7 workers have to do. Every extra
CPU-second of worker work adds about 1/7 of a second to the run, the most it can cost with 7
workers. No single-threaded part of the pipeline is close to full. So any change that really
cuts worker CPU will shorten the run in proportion.

The earlier optimizations that "saved CPU but didn't speed anything up" mostly did not save CPU
in the first place. Their savings came from profiler sample shares or from flawed timing, not
from measured worker CPU.

### The question

Several past changes looked like clear CPU wins on a profile but did not move total time: the
Perlin port (#67), the allocation fixes (#68), and the `mapAll` memo (#75). One explanation was
that the run is held up by something single-threaded (a lock, a lane, the main thread) rather
than by the workers. If so, worker CPU savings would be soaked up by waiting and never show.

### What we did

1. **Traced every run.** For every chunk step we recorded when it was requested, when it could
   start, when it actually started, and when it finished. We also timed how busy each
   single-threaded part was: the light lane, the worldgen lane, the main thread, and the global
   structures lock.
2. **Added CPU on purpose.** This is the direct test. A worker spins for a fixed amount of CPU
   time inside every noise step (1, 2, 3 or 6 ms). If the run has slack, the extra work
   disappears into it and total time barely moves. If the workers are the limit, total time grows
   by the added CPU divided by 7.
3. **Added CPU inside the structures lock.** A 0.5 ms spin is placed while that lock is held. If
   the lock is a true bottleneck, every millisecond there adds a full millisecond to the run.

Every arm ran interleaved with a plain baseline in the same rounds (39 runs at 6,400 chunks).

### Result 1: extra worker CPU costs the full 1/7

![#81: extra worker CPU vs extra run time](findings/critpath81_dose.png)

| Extra work | Added worker CPU per run | Runs | Extra run time (mean ± SE) | Expected if workers are the limit |
|---|---:|---:|---:|---:|
| 1 ms per noise step | 7.0 s | 6 | +1.90 ± 1.16 s | +1.0 s |
| 2 ms per noise step (block 1) | 14.1 s | 3 | +0.84 ± 0.82 s | +2.0 s |
| 2 ms per noise step (block 2) | 14.1 s | 6 | +1.04 ± 0.97 s | +2.0 s |
| 3 ms per noise step | 21.1 s | 6 | +2.21 ± 1.06 s | +3.0 s |
| 6 ms per noise step | 42.2 s | 3 | +6.61 ± 1.08 s | +6.0 s |

- A straight-line fit gives **0.135 ± 0.022 s of run time per added CPU-second** (about 1/7.4).
  The full-saturation value for 7 workers is 1/7 (0.143).
- The spin adds the CPU it claims: measured worker CPU rose 1.08 s per injected CPU-second.
- Small doses sit on the same line within their error bars. There is no flat "free" region at
  the start, so there is no slack for small savings to vanish into.
- The 65 runs from #74 tell the same story without any injection. Total time rises 1/7.0 s per
  extra worker CPU-second, correlation 0.98.

### Result 2: nothing single-threaded is the bottleneck

![#81: busy time of serial parts, and where a chunk's time goes](findings/critpath81_where.png)

- **Single-threaded parts are mostly idle.** The light lane is busy 31% of the time, the
  structures lock is held 18%, the main thread 14%, the worldgen lane 12%.
- **Workers are nearly full.** On average 5.6 of 7 workers are running code and about 0.6 are
  parked waiting for the structures lock. That leaves under one worker's worth of idle time.
- **A requested chunk takes about 108 ms.** Most of that is waiting rather than working: about
  29 ms waiting for neighbors to release their area locks, 24 ms waiting to be scheduled, and
  the rest running steps (noise is the biggest at 14 ms). These waits are what queueing looks like
  when workers are busy. They are not a fixed delay.
- **The numbers add up.** With 16 chunks in flight at 108 ms each, 6,400 chunks should take
  43.0 s. The six baseline runs averaged 43.3 s.

### Result 3: the structures lock is a partial exception

The 0.5 ms spin inside the structures lock added 4.9 s of lock-held time per run. Total time
rose **1.88 ± 1.05 s**. That is more than spreading the same CPU over 7 workers would cost
(+0.7 s), and much less than a true bottleneck would cost (+4.9 s).

The reason is that workers wait for this lock by parking, and a parked worker can't do anything
else. Lock waiting rose from 25 to 40 worker-seconds, so each extra second of holding the lock
kept about 3 workers parked. The lock is not the run's limit, but it costs more than its own CPU.

### Result 4: the old "invisible" wins, re-read

| Change | What was reported | Did worker CPU actually drop? | Reading now |
|---|---|---|---|
| `mapAll` memo (#75) | "removes ~18 thread-s of constructor time" | No: 284.2 vs 285.7 worker CPU-s in its own A/B | The 18 s came from comparing against an instrumented baseline its author flagged as inflated. No CPU was saved, so nothing to see. |
| Perlin port (#67) | `ImprovedNoise.p` fell from 10.6% to 0% of samples | Not measured | A profiler share can fall while total CPU stays the same (work moves to the caller). The wall-time tie is consistent with no net saving. |
| Allocation fixes (#68) | Fewer young GCs | Not measured | An allocation change, not a CPU change. #77 already showed allocation is not the lever. |
| Surface/biome shortcuts (#75) | 3.5-4.5% less worker CPU | Yes | Total time moved -3.7% (n=16), exactly what 1/7 predicts. It was under-powered for the 3-4% bar, not invisible. |

### What this means

- **Judge compute changes by measured worker CPU-seconds.** Profiler sample shares can't show
  whether total CPU went down. The trace now records worker CPU on every run.
- **Expect to need many pairs.** In this session a baseline run varied by about 3% run to run,
  so one pair's difference varies by about 4.5%. Confirming a real 3% win takes roughly 20-30
  interleaved pairs, not 5-10.
- **The big targets are still the big CPU users.** Noise, surface and biomes cover most of a
  chunk's working time. Scheduler tricks can't buy much, because there is under one idle worker
  to recover.
- **The structures lock is the one structural lever left.** It ties up about 0.6 workers in
  parking. #75's lane experiment found the freed workers had nothing else ready to do, so the
  win is unproven, but a finer-grained lock remains the only place where scheduling might help.

### Caveats

- **Heap was 10 GiB, not the 16 GiB champion spec.** A Minecraft server elsewhere on the VM held ~16 GB,
  so a 16 GiB pretouched heap was OOM-killed. Tile 5 peaks at ~1.6 GB of old gen. Every comparison
  here is within this study only.
- **Tracing is on in all study runs.** Four traced/untraced pairs showed no measurable cost
  (42.37 s vs 42.33 s mean).
- **A spin is not real code.** It uses CPU but doesn't allocate or touch memory. A real change
  can also shift GC or cache behavior, which a spin can't imitate.

### Method

Orion v5.7, tile 5 (6,400 chunks), 7 workers, seed 69, Corretto from `/root/minecraft-server/runtime/java`,
2026-11-09. Common flags:

```text
-Xms10g -Xmx10g -XX:+AlwaysPreTouch -XX:+UseParallelGC -Dmax.bg.threads=7
-javaagent:build/libs/orion-agent.jar -Dorion.patchReentrancy=true
-Dorion.patchStructureGenState=true -Dorion.patchParallelSteps=true
-Dscheduler=orion5.7 -Dorion.maxinflight=16 -Dorion.c1gc.pressure=0.3
-Dorion.trace=<prefix>   (all study runs)
-Dorion.inject=noise:1000 | noise:2000 | noise:3000 | noise:6000 | structure_starts:500
```

- New code: `OrionTrace.kt` (tracing and injection), hooks in `OrionParallelSteps.kt` and `OrionV5.kt`,
  and an agent patch timing `AbstractConsecutiveExecutor.run` for lane busy time.
- Runner: `findings/run_critpath.py` (blocks `dose1`: 3 rounds; `dose2`: 6 rounds; arm order
  reversed every other round). Overhead pairs: tags `ovh_t1-4` / `ovh_n1-4`.
- Analysis: `findings/critpath_analysis.py` (utilization and waits), `findings/critpath_dag.py`
  (rebuilds the step dependency graph from vanilla 26.1.1's requirement radii and follows each
  chunk's slowest dependency back to its request), `findings/collect_critpath81.py` writes
  `findings/critpath81_runs.csv` and `findings/critpath81_attribution.csv`.
- The CPU-vs-time line for #74 uses `findings/orion57_runs.csv` (65 runs with worker CPU recorded).
- Raw traces (~200 MB) stay local under `findings/critpath_runs/` and are gitignored. Results
  files and the summary CSVs are kept.
- Rows: `orion_results.csv` configs `cp81_*`. On the leaderboard, baseline runs are filed as
  Orion v5.7 and injected runs as Experiment.

---

## 82. Orion v5.8: every generated chunk was being kept in memory forever; fixing it makes long runs 13-14% faster

**Short answer.** Vanilla hands the server thread a small follow-up task for every chunk. A real
server runs those tasks between ticks. WorldgenD never ticks, so the tasks were never run, and each
one kept its whole chunk in memory. Over a long run the heap filled up and the JVM spent about a
minute stuck in back-to-back full garbage collections.

Orion v5.8 is v5.7 plus one change: the poll thread now runs those tasks as they arrive. At 65,536
chunks it finishes **13.0% and 14.4% sooner** in two interleaved pairs (165.7 vs 144.1 and 141.9
chunks per second). At 6,400 chunks nothing changes, because the heap never gets full enough to matter.
Generated blocks are identical.

### How it was found

The 65,536-chunk run in #80 had 31 full GCs, 27 of them in the last 100 seconds. After each one
about 11 GB was still in use, even though C1GC reported every chunk as written to disk and released.

A heap dump taken partway through a run showed why. Of the 3,904 chunk noise objects still alive,
3,353 (86%) were reachable through the same chain:

```text
server task queue -> queued task -> ServerLevel.onStructureStartsAvailable -> ProtoChunk -> NoiseChunk
```

Each queued task holds the chunk's working copy (`ProtoChunk`). That copy holds the noise caches
used to build it, and it shares its block storage with the finished chunk. So nothing C1GC released
could actually be freed. The cost was about 180 KB per chunk, for every chunk ever generated.

Vanilla only runs a queued server task when the current tick has time left. With no tick loop that
is never true. The same queue also carries point-of-interest updates (village beds, job sites),
so those were never applied either.

### The fix

Before the fill starts, v5.8 tells the server its next tick is infinitely far away. From then on
vanilla's own check says "there is time", and the poll thread, which already calls the server's task
poll in its loop, runs each task within moments of it being queued. No scheduling logic changed,
which is why this is v5.8 and not v6.

### Result 1: memory stays low

![#82: heap in use after each GC, v5.7 vs v5.8, 65,536 chunks](findings/orion58_heap.png)

| At 65,536 chunks (mean of 2) | v5.7 | v5.8 |
|---|---:|---:|
| Tasks left in the queue at the end | 79,042 | 0 or 1 |
| Still in use after the last full GC | 11.6 GB | 1.9 GB |
| Full GCs | 30 | 5 |
| Time paused in full GCs | 66.0 s | 1.4 s |
| JVM CPU time | 3,058 s | 2,599 s |

Three of the full GCs in each column happen during startup, before generation begins.

In a separate 16,384-chunk check (10 GiB heap, reclamation always on), memory in use went from
1.2 GB to 2.7 GB between the 62 s and 103 s marks on v5.7. On v5.8 it went from 948 MB to 983 MB.

v5.8's heap still climbs between full GCs. That is ordinary garbage waiting to be collected: chunks
live long enough to be moved to the old part of the heap before they are released. One cheap full
GC (about 0.7 s) clears it.

### Result 2: long runs are faster, short runs are unchanged

![#82: v5.8 vs v5.7 at 6,400 and 65,536 chunks](findings/orion58_throughput.png)

| Size | Version | Runs | Median time | ms per chunk | Chunks per second |
|---|---|---:|---:|---:|---:|
| 6,400 | v5.7 | 12 | 40.74 s | 6.366 | 157.1 |
| 6,400 | v5.8 | 12 | 40.93 s | 6.396 | 156.4 |
| 65,536 | v5.7 | 2 | 458.4 s | 6.994 | 143.0 |
| 65,536 | v5.8 | 2 | 395.5 s | 6.035 | 165.7 |

- **6,400 chunks: a tie.** Across 12 pairs v5.8 is +0.5% (median), 5 of 12 faster, with a
  standard error of 0.76%. Young-GC pause time does fall from 0.92 s to 0.62 s per run, but that
  is under 1% of the run.
- **65,536 chunks: 13.0% and 14.4% less time.** The time saved (59 s and 66 s) matches the full-GC
  pause time removed (64 s and 65 s). Nothing else got faster.
- **Slow chunks are less slow.** The 99th-percentile chunk time at 65,536 chunks drops from about
  800 ms to about 375 ms, because chunks no longer wait behind 2-second pauses.
- **The gap should widen with size.** v5.7 needs about 180 KB of heap per chunk generated, so a
  16 GiB heap runs out somewhere past 65,536 chunks. v5.8 has no such limit in these runs, but
  sizes beyond 65,536 were not tested.

### Correctness

With deterministic ordering on, v5.7 and v5.8 produce the same SHA-256 for every chunk: 0 of 2,304
differ, checked with two runs of each. The target was shifted 100 chunks away from spawn. Chunks
next to spawn differ between any two runs of the same version (33 of 2,304 on v5.7 against itself),
because they are generated during startup before ordering is switched on (#65).

### What this changes about earlier findings

- **C1GC (#69-#73, #80) was tuned against a heap that could not shrink.** Its bookkeeping was
  correct, but the memory it released stayed reachable through the task queue. The "full GCs remain
  after reclamation" result in #73 and #80 was this leak.
- **The young-GC pause climb in #76 and #77** tracked the amount of live memory. That memory was
  mostly these retained chunks.
- **Saved worlds from v5.7 and earlier may be missing point-of-interest data**, since those updates
  were never applied. This was not checked.

### Caveats

- **Two large pairs.** Both agree closely (v5.8: 395.6 s and 395.5 s), but it is still n=2.
- **Memory is not perfectly flat.** The 16,384-chunk check shows about 5 KB per chunk of slow growth
  left, mostly entities and small per-chunk tables. That would be about 1.3 GB at 262,144 chunks.
- **The reclamation threshold was left alone.** C1GC still switches on when the old part of the heap
  is 30% full, which now mostly measures uncollected garbage. Whether it should switch on later, or
  at all on a 16 GiB heap, was not tested.

### Method

Orion v5.7 and v5.8, 7 workers, seed 69, Corretto from `/root/minecraft-server/runtime/java`,
2026-11-10, one session. Common flags:

```text
-Xms16g -Xmx16g -XX:+AlwaysPreTouch -XX:+UseParallelGC -Dmax.bg.threads=7
-javaagent:build/libs/orion-agent.jar -Dorion.patchReentrancy=true
-Dorion.patchStructureGenState=true -Dorion.patchParallelSteps=true
-Dorion.maxinflight=16 -Dsaveworld=false
-Dscheduler=orion5.7 | orion5.8      -Dmosaic.tile=5 | 16
```

- The change: `HeadlessWorldgen.kt` sets `MinecraftServer.nextTickTimeNanos` to `Long.MAX_VALUE`
  before `fill()`. `-Dorion.serverTasks=drain|hold` overrides it on any v5 scheduler (default
  `drain` on v5.8, `hold` otherwise). The result file's `serverTasks` line reports the mode and how
  many tasks were still queued when the fill ended.
- Runner: `findings/run_orion58.py` (arm order reversed every other round; fresh JVM and world per
  run). Blocks `short` (12 rounds, tile 5) and `large` (2 rounds, tile 16).
- Collection: `findings/collect_orion58.py` writes `findings/orion58_runs.csv` and
  `findings/orion58_heap.csv` (one row per GC of the large runs). Rows in `orion_results.csv` are
  configs `o58_*`.
- Heap dump: `jcmd <pid> GC.heap_dump` at about 3,000 of 6,400 chunks, 6 GiB heap, reclamation
  always on. `findings/HprofPath.java` prints the chains that keep a given class alive.
- Correctness runs: tile 3, `-Dorion.deterministicFeatures=region -Dorion.patchWorldgenLight=true
  -Dorion.parallelSteps.verify=true -Dorion.targetShift=100 -Ddescribe.histogramfile=<path>`.
- Raw GC logs and progress files stay local under `findings/orion58_runs/` and are gitignored.

---

## Open questions / where you pick this up

The list from [`scientific-findings-41-80.md`](scientific-findings-41-80.md#open-questions--where-you-pick-this-up) still applies. New since #81:

- **#81 shows run time is worker CPU divided by about 7.** Re-test the surface/biome shortcuts
  (#75) on top of v5.7 with 20-30 interleaved pairs and worker CPU recorded. #81 predicts about
  -3.5%.
- **The structures lock keeps about 0.6 workers parked.** Worth testing whether a finer-grained
  lock (per structure, or per region) gives that time back, given that #75's lane version found
  no other ready work.
- **Retired-instruction counts would be a steadier yardstick than wall time.** The CPU's
  hardware counters are exposed, but `perf` is not installed.
- **The 16 GiB champion heap couldn't be pretouched on 2026-11-09** because a Minecraft server on
  the VM held ~16 GB. Stop it (or check free memory) before the next champion-spec run.
- **#82's v5.8 removes the per-chunk memory leak, and long runs are 13-14% faster (n=2 pairs).**
  Still open: a run well past 65,536 chunks to confirm memory stays bounded; whether C1GC's 30%
  threshold should move now that the heap can shrink; whether saved worlds from earlier versions
  lack point-of-interest data; and the remaining ~5 KB per chunk of slow growth.
- **#81's 16 GiB note is resolved:** the host had its full 32 GB on 2026-11-10 and #82 ran at the
  champion heap.
