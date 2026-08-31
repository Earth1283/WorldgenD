# WorldgenD

Headless, real-vanilla Minecraft chunk generation. No network, no RCON, no tick loop —
just Mojang's own `net.minecraft.server.dedicated.DedicatedServer` reflectively pried open
far enough to call `loadLevel()` and then `ServerChunkCache.getChunkFuture(x, z, FULL, true)`
directly, in parallel, using nothing but the official server jar.

WorldgenD's own compiled classes contain **zero references** to any `net.minecraft.*` or
`com.mojang.*` symbol — no Mojang dependency in `build.gradle.kts`, static or otherwise.
Every Mojang class it touches is a string literal fed to `Class.forName` at runtime, against
a jar *you* provide. Delete the jar and WorldgenD is an inert pile of reflection glue. See
[`TUTORIAL.md`](TUTORIAL.md) for the full how-and-why.

## Requirements

- The official Mojang dedicated server jar. Legally obtained, by you, for you. Not bundled,
  not compiled against, not checked into git (`/servers/*.jar` is gitignored on principle).
- Java 25 (the build pins `jvmToolchain(25)`).

## Setup

```
WorldgenD/
  servers/
    mojang-26-1-1-server.jar   <- put it here, or symlink it here
```

That's the whole install.

## Running

```
./gradlew run
```

Watch the logs for the mosaic fill — a gapless 96x96 block (9216 chunks) tiled across 256
phases, each one provably independent by construction (see `scientific-findings.md` #7-#8
for why 16 is the magic modulus). Every run ends with two summary lines:

```
Done: 9216 chunks generated, 0 failed in NNms across 256 phases (fastest=NNms, slowest=NNms).
MSPC (ms/chunk, n=9216): min=NN p1=NN p25=NN p50=NN p75=NN p99=NN max=NN
```

The second line is **MSPC** (milliseconds per chunk) — per-chunk submission-to-completion
latency, reported as a full percentile spread rather than one misleading average.

Scheduler modes are selected with `-Dscheduler=mosaic|orion|orion2|orion2.1|orion2.2`.
Orion v2.1 uses raster target order; Orion v2.2 is the explicitly scatter-ordered variant.

**In plain terms**: picture ordering 9,216 coffees one at a time and timing every single
cup, from "I'll have a latte" to it hitting the counter. Most come out fast; a few get
unlucky and land behind a rush. MSPC is that stopwatch, run per chunk instead of per
coffee — reported as fastest, typical, and worst-1%, instead of one average that blurs
the good cups and the bad one together. Smaller is better, everywhere. No CPU-architecture
knowledge required. Full formal definition in `scientific-findings.md` #11.

![Typical milliseconds per chunk, naive solid-block fill vs the mosaic algorithm at two tile sizes — trending down](findings/mspc_progress.png)

That's the whole point of building MSPC in the first place: a number you can watch go
down as the fill algorithm improves, instead of an average that hides whether it
actually did. See `scientific-findings.md` #13 for the thread-pool experiment that
motivated bumping the mosaic tile size, #14 for a three-way GC comparison (default G1
vs. a throughput-tuned G1 vs. ZGC, all at a fixed pretouched heap), #15 for what
happens when GC choice is crossed with a smaller worker pool (short version: the
7-worker GC ranking from #14 flips at 4 workers), #16 for a JFR profile of the champion config
(reflection costs ~0.02% of runtime — already invisible — and "obviously faster" `MethodHandle`s
turned out to be an 8% regression on this JDK), #17 for an attempt to tune ParallelGC harder
(the result was indistinguishable from this box's own ~9% run-to-run noise — there was no
headroom left after #16 already proved GC costs under 0.4% of wall time), #18 for the
uncomfortable one (a real, unmodified Paper server with the Chunky plugin beats this whole
project's own best-tuned config by ~53% throughput, using *fewer* dedicated worker threads —
strong evidence that Paper's fork-level generator patches, not concurrency tuning, are where
the real headroom was this whole time), #19 for the same comparison at ~9x the scale (58081
chunks) — confirms WorldgenD's own throughput holds steady as the job grows, and gives Leaf a
big enough sample to show a real ~6% edge over Paper that #18's smaller run couldn't tell apart
from noise — #20 for the full chart set, #21 for what happened when we tried stealing Paper's Moonrise chunk-scheduler idea directly — calling the vanilla, unmodified `managedBlock()` from more than one thread at once (no timing win, left in as an opt-in `-Dpump.threads=N` flag, off by default), #22 for the correction: the terrain divergence we first blamed on that turned out to be almost entirely [MC-55596](https://bugs.mojang.com/browse/MC-55596), a real, long-standing Mojang bug (background-thread generation order affects same-seed output, independent of anything this project does) — with the real, much smaller, bytecode-confirmed data race (`blockingCount` in `BlockableEventLoop`) isolated separately via `jcmd` and targeted instrumentation — and #23-#26 for **Orion**, a second scheduler built alongside the mosaic rather than replacing it (`-Dscheduler=orion|orion2`, default `mosaic`): #23-24 found that `getChunkFuture()` only blocks when called from vanilla's own designated "main thread," and is genuinely non-blocking called from anywhere else — a real, intended API surface, not a felony; #25 built a multi-threaded dispatcher on exactly that and hit a real, confirmed, still-unexplained correctness bug in a third-party area lock under full integration (isolated tests of the same lock and the same retry logic came back clean); #26 (`OrionV2.kt`) sidesteps it architecturally — one thread owns all conflict-tracking state, worker threads never touch it — and lands the first real win in the whole investigation: **~24% faster wall-clock than the mosaic at champion scale**, trading much higher per-chunk latency (queued behind the real 4-worker ceiling) for a total time the mosaic's own phase barriers never let it reach.

To test a different `Util.getMaxThreads()` cap without editing code:

```
./gradlew run -PmaxBgThreads=4
```

This forwards `-Dmax.bg.threads=N` to the forked JVM (wired up in `build.gradle.kts`). Note
it's a **ceiling**, not a target: `threads = clamp(availableProcessors() - 1, 1, N)`, so on
an 8-core box any `N ≥ 7` clamps to the same 7 workers you already had — only `N < 7` (like
the `4` above) actually changes anything. Verified live with `jcmd <pid> Thread.print` in
`scientific-findings.md` #13, which also has the more interesting follow-up: cutting workers
from 7 to 4 didn't cost any measurable throughput either.

To try a different garbage collector (or any other raw JVM flags):

```
./gradlew run -PgcArgs="-Xms16g -Xmx16g -XX:+AlwaysPreTouch -XX:+UseZGC"
```

`-PgcArgs` is a plain string, space-split into `jvmArgs` — pass whatever flags you want.
Every run now logs `Active GC(s): ...` at startup (via `ManagementFactory.getGarbageCollectorMXBeans()`),
so you can confirm what actually loaded instead of trusting the flag. `scientific-findings.md`
#14 ran this three ways (default G1, a throughput-tuned G1, and generational ZGC) at a fixed
16GB pretouched heap, all at 7 workers — ZGC came out ~6% ahead across every MSPC percentile,
G1's own tuning knob made basically no difference. #15 reran the same collectors (plus a fourth,
ParallelGC) at 4 workers instead of 7, and the ranking flipped: ZGC dropped to *last* place and
tuned-G1/ParallelGC tied for fastest. GC choice and worker count interact — neither axis is safe
to tune in isolation.

## The Leaderboard (deeply, deeply cursed)

Every scheduler this project has ever shipped, plus real unmodified Paper, Leaf, and
Leaf-with-crack-flags, ranked by **effective MSPC** (`total_ms / chunks` — the same "true
average" metric `scientific-findings.md` #18 used to compare against real servers, distinct
from the percentile-spread MSPC everywhere else on this page). Every row is one *individual*
run, not an average — 31 of them, sorted best to worst, generated straight from
`findings/leaderboard_entries.csv` rather than typed by hand:

```
python3 findings/generate_leaderboard.py   # regenerates findings/leaderboard.html
```

Open `findings/leaderboard.html` — sortable by any column, filterable by engine. **It is not a
rigorous ranking and isn't trying to be one**: rows span different sessions and box states, and
two of them are a genuinely different chunk count and scale entirely. `scientific-findings.md`
#16/#17 already put this box's own run-to-run noise at ~9%, and #32 exists specifically because
block-sequential comparisons like most of this table can't be trusted at face value — Orion v2.1
shows up at rank #3 *and* rank #18, sandwiched between two Paper legs, which is the whole point
of leaving every run in separately instead of averaging them away. The rigorous version, with
every caveat intact, is `scientific-findings.md` #1-#35 and its unhinged sibling
`cursed-scientific-advancements.md`.

## Project layout

| File | What it is |
|---|---|
| `src/main/kotlin/io/github/eath1283/worldgend/HeadlessWorldgen.kt` | The whole heist: bootstrap replay, `DedicatedServer` construction, the mosaic fill loop, MSPC instrumentation |
| `src/main/kotlin/io/github/eath1283/worldgend/ServerRuntime.kt` | Finds the server jar, unpacks the bundler payload, builds the `URLClassLoader` |
| `src/main/kotlin/io/github/eath1283/worldgend/Reflect.kt` | Thin `Class`/`Method`/`Constructor` lookup helpers (`Mc`) |
| `TUTORIAL.md` | The narrative: what this does and why it works, written for a human |
| `scientific-findings.md` | The lab notebook: every empirical claim above, backed by `jcmd` thread dumps and `javap` bytecode disassembly instead of vibes |
| `findings/` | Raw data (`mspc_results.csv`, `algorithm_progress.csv`, `gc_results.csv`, `gc_4w_results.csv`, ...) and the matplotlib script (`plot_results.py`) that generates every chart in this README and in `scientific-findings.md` |
| `findings/leaderboard_entries.csv`, `findings/generate_leaderboard.py` | Source data and generator for `findings/leaderboard.html`, the sortable cursed leaderboard |
| `cursed-scientific-advancements.md` | The highlight reel: the whole Orion arc, same receipts, deliberately unhinged tone |

## Docs

- **[`TUTORIAL.md`](TUTORIAL.md)** — start here. How the reflection heist works, step by
  step, and the ground rules that keep it correct (`managedBlock()` is load-bearing, the
  seed is pinned to `69`, etc).
- **[`scientific-findings.md`](scientific-findings.md)** — the evidence. Thread-pool sizing,
  the confirmed radius-8 chunk dependency ceiling (straight out of the jar's bytecode), the
  mosaic algorithm it justifies, the MSPC metric, and an open-questions list for anyone
  picking this up next.

## Legal

Not one byte of Mojang's compiled game ships in this repo or its build output. The jar lives
in a directory you control, is read at runtime, and is never redistributed. This is a remote
control, not a copy of the TV.
