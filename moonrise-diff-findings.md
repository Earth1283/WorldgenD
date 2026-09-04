# Stealing from Paper/Leaf: a real bytecode diff against vanilla

Investigation only, standalone doc like `static-analysis-findings.md` — not a numbered
`benching.md` finding, nothing implemented or benchmarked here. Evidence: Vineflower
decompiles of `net.minecraft.*` chunk-gen classes pulled from three matched 26.1.2 jars
(`control/cache/mojang_26.1.2.jar`, `control/versions/26.1.2/paper-26.1.2.jar`,
`control/versions/26.1.2/leaf-26.1.2.jar`), diffed with `diff -u`. `WorldgenD`'s own jar
(26.1.1) was not touched — one patch version off, kept out of the diff on purpose.

## Verdict

Part 1 (generator math itself): almost entirely noise or Bukkit/plugin-API surface, not
algorithmic wins — **confirms #53's implication that vanilla's generator math has no
easy fruit left.** One real, small, patchable exception: `NoiseBasedChunkGenerator`
drops two `Util.backgroundExecutor()` async hops (`createBiomes`, `fillFromNoise`) in
favor of synchronous `Runnable::run` execution. Worth a real A/B — untested here.

Part 2 (Moonrise's chunk_system): a real, much bigger scheduling architecture — but
almost all of what it solves (player-ticket priority, disk IO overlap during a live
server, tick-thread safety across many concurrent players) is a problem WorldgenD's
single-dimension bulk fill doesn't have. One primitive inside it, `AreaDependentQueue`,
is genuinely relevant: a radius/dependency-aware task queue instead of Orion's
single-thread-owns-conflict-state coarse lock — and it's built on `concurrentutil`, a
library WorldgenD already depends on for `ReentrantAreaLock`. Speculative, biggest
lift, biggest ceiling of anything on this list.

## Part 1: generator classes, Paper/Leaf vs vanilla (26.1.2, matched triad)

| file | paper diff | leaf diff | verdict |
|---|---|---|---|
| `Climate.java` | 112 lines | 112 lines | **100% noise** — every hunk is `final` parameter stripping, one `interface` method's param names becoming `var1`/`var2`. Zero behavioral change. |
| `MultiNoiseBiomeSource.java` | 16 | 16 | same `final`-stripping noise. |
| `SurfaceSystem.java` | 63 | 63 | mostly decompiler reformatting + one `DimensionType.WAY_BELOW_MIN_Y` constant inlined to its literal `-32512` (same value, not a behavior change) + constructor signature widened to also take `WorldGenerationContext`/world refs for Paper's per-world custom rule config. No algorithm change. |
| `NoiseChunk.java` | 112 | 112 | same class, reformatted; every "added" line in a naive diff is a method Vineflower wrapped across a different number of lines on each side, not new code — confirmed by re-diffing with `-b -w` (whitespace-insensitive), which collapses to near-zero. |
| `ChunkGeneratorStructureState.java` | 160 | 170 | Bukkit structure-search plumbing (`StructuresLocateEvent`) + one Moonrise hook, see below. |
| `ChunkGenerator.java` | 281 | 378 | **the real content is here** — see next section. |
| `NoiseBasedChunkGenerator.java` | 89 | 118 | **one real find**, see below. |

### The one real generator-math-adjacent find: executor bypass

`NoiseBasedChunkGenerator.createBiomes()` and `.fillFromNoise()`, vanilla:

```java
return CompletableFuture.supplyAsync(() -> {
   this.doCreateBiomes(blender, randomState, structureManager, protoChunk);
   return protoChunk;
}, Util.backgroundExecutor().forName("init_biomes"));
```

Paper (identical in Leaf):

```java
return CompletableFuture.supplyAsync(() -> {
   this.doCreateBiomes(blender, randomState, structureManager, protoChunk);
   return protoChunk;
}, Runnable::run);
```

Same swap on `fillFromNoise`'s `"wgen_fill_noise"` executor. Vanilla re-dispatches both
phases onto its own shared background thread pool (`Util.backgroundExecutor()`, a
`ForkJoinPool`/named-thread wrapper used for many unrelated named tasks across the whole
game); Paper runs them synchronously on whatever thread already called
`createBiomes`/`fillFromNoise` — no second thread hop, no shared-pool queueing. This is
real and unambiguous, not decompiler noise (confirmed via `javap -c`: vanilla's
bytecode loads a static `Util.backgroundExecutor` field then invokes `.forName`, Paper's
loads a static synthetic `Runnable::run` lambda reference instead).

**Relevance to WorldgenD**: `getChunkFuture()` walks through exactly these two vanilla
methods for every chunk, on every scheduler generation this project has shipped
(mosaic through orion3). Every call currently pays a hop through vanilla's shared
`Util.backgroundExecutor()`, on top of whatever WorldgenD's own scheduler
(mosaic/orion/orion2.x/orion3) already did to get the calling thread scheduled. Whether
that hop is measurable is unknown — #16's JFR profile of the champion config didn't
call out `Util.backgroundExecutor`/`ForkJoinPool` frames as hot, but that profile
predates any scheduler generation past champion-era orion2.1, and reflection call
overhead was separately shown to be ~0.02% there, so it's plausible this is in the same
"technically real, practically invisible" bucket #53 landed in. Genuinely untested.

### Everything else in `ChunkGenerator.java`/`ChunkGeneratorStructureState.java`

The bulk of both files' diffs are Bukkit/CraftBukkit plugin-API surface with zero
relevance to a plugin-free headless harness: `StructuresLocateEvent`,
`AsyncStructureSpawnEvent`, `BlockPopulator` support, per-world `paperConfig()` feature
seed overrides, world-border-aware structure search, and defensive
`CrashReport`-wrapped try/catch around feature/biome decoration (error handling, not
an optimization). One Moonrise hook appears inline —
`level.moonrise$syncLoadNonFull(chunkTarget.x(), chunkTarget.z(), ChunkStatus.STRUCTURE_STARTS)`
replacing vanilla's structure-search neighbor-chunk load — but it's a rename/redirect
into Moonrise's own chunk system (Part 2 below), not a generator-math change in this
file. None of this is a WorldgenD candidate.

## Part 2: Moonrise's `chunk_system` scheduling architecture

Confirmed present: `ca/spottedleaf/moonrise/patches/chunk_system/scheduling/`
(`ChunkTaskScheduler`, `NewChunkHolder`, `ThreadedTicketLevelPropagator`,
`PriorityHolder`, per-task classes) and `.../io/` (`MoonriseRegionFileIO`, its own
region-file data controllers). Decompiled `ChunkTaskScheduler.java` (881 lines).

The mechanism, in plain terms:

```java
this.parallelGenExecutor = MoonriseCommon.SERVER_GROUP.createExecutor();
this.loadExecutor = MoonriseCommon.SERVER_GROUP.createExecutor();
this.radiusAwareScheduler = new AreaDependentQueue(this.parallelGenExecutor, 4);
this.ioExecutor = MoonriseCommon.SERVER_IO_GROUP.createExecutor();
this.compressionExecutor = MoonriseCommon.SERVER_GROUP.createExecutor();
this.saveExecutor = MoonriseCommon.SERVER_GROUP.createExecutor();
```

Five **separate** priority-aware executor pools (from `ca.spottedleaf.concurrentutil`'s
`PrioritisedExecutor`/`BalancedPrioritisedThreadPool` — a library WorldgenD already
depends on for `ReentrantAreaLock`, per `build.gradle.kts`'s own comment) instead of
vanilla's one shared `Util.backgroundExecutor()`: generation, chunk loading, disk IO,
compression, and saving never contend with each other's queue. On top of that, every
chunk task carries a `Priority` (`raisePriority`/`lowerPriority`/`setPriority`,
propagated via `ThreadedTicketLevelPropagator`) driven by which players are near which
chunks — live-server-only concept, meaningless for a one-shot bulk fill where every
target chunk is equally wanted from the start.

The one piece that *is* directly relevant: **`AreaDependentQueue`** wrapping
`parallelGenExecutor`. This is Moonrise's answer to the exact problem Orion v1-v3 have
spent their whole arc on (`cursed-scientific-advancements.md` Act 3-11): letting
independent chunk-generation tasks run fully concurrently while chunks within each
other's dependency radius (the confirmed radius-8 ceiling from `scientific-findings`
#6-#7) stay correctly ordered. Orion v2's answer was architectural avoidance — one
thread owns all conflict-tracking state, workers never touch it. Moonrise's answer is a
purpose-built queue data structure that appears to encode the area-conflict check
directly into scheduling admission, rather than gating it behind a single owning
thread or (Orion v3's `-Dorion.patchReentrancy`) a coarse lock with a reentrancy patch.
Not fully decompiled/traced here (its internals weren't read past the constructor
wiring above) — whether it's actually a lock-free/wait-free structure or just a
differently-shaped coarse lock is unconfirmed.

**Honest scope check**: everything else in `chunk_system` — the IO pipeline split, the
ticket/priority propagation, `NewChunkHolder`'s multi-stage load/generate/light/save
state machine — solves live-server problems (many concurrent players, persistent disk
IO overlapping with generation, chunk unload under memory pressure) that a headless,
single-dimension, generate-and-discard bulk fill does not have. Grafting the whole
chunk_system in would be solving problems WorldgenD doesn't need solved. The narrow,
honest steal candidate is `AreaDependentQueue` alone, as a scheduling primitive to swap
in under Orion's dispatch layer — not the surrounding machinery.

## Candidate patches worth A/B testing, ranked

1. **Bypass `Util.backgroundExecutor()` in `createBiomes`/`fillFromNoise`** (steal
   Paper's `Runnable::run` swap, 2 call sites in `NoiseBasedChunkGenerator`). Cheapest
   to try — same bytecode-patch pattern `OrionPatchAgent.kt` already uses. Guess: could
   help if concurrent `getChunkFuture()` calls are currently queueing behind each other
   on vanilla's shared background pool; could just as easily be another #53-style null
   result if that pool was never the bottleneck. No JFR evidence either way yet —
   `champion_baseline.jfr` wasn't checked for `ForkJoinPool`/`Util$Executor` frames in
   this pass.
2. **Adopt `AreaDependentQueue` as Orion's scheduling primitive**, replacing the
   coarse-lock-plus-reentrancy-patch design from `OrionV3`/`OrionPatchAgent.kt`. Biggest
   lift by far — needs its internals actually traced, not just its constructor wiring —
   and the biggest theoretical ceiling, since it's the one mechanism in this whole diff
   that's a genuinely different concurrency *design*, not a faster version of what
   vanilla/Orion already do. Also the most likely candidate to actually explain #18/#19's
   real-server edge, if that edge is scheduling-side rather than generator-math-side (and
   Part 1 above is evidence it's not generator-math-side).
3. **Everything Bukkit/plugin-API-shaped** (events, populators, config-driven feature
   seeds) and everything ticket/priority-shaped (player-driven chunk priority, IO/save
   pipeline separation) — not candidates. Solves problems this project's use case
   doesn't have.

## Part 3: `AreaDependentQueue` traced, and a projection

Answers the first open question below, then projects a benefit range from a synthetic
microbenchmark. **This is a projection, not a measured result** — no integration into
Orion, no A/B against a real WorldgenD run.

### Mechanism verdict: event-driven, sharded lock, no polling

Full trace of `AreaDependentQueue` (decompiled, `ca.spottedleaf.concurrentutil.executor.queue`,
1027 lines). The constructor's `int lockShift` argument (the "`4`" from the Moonrise
wiring, `6` in this bench) sizes a `ReentrantAreaLock` — coordinate-space sharding, not a
single global lock. Each `PositionedTask` covers an AABB (`createTask(x, y, radius, ...)`
expands to `[x-radius, x+radius] x [y-radius, y+radius]`, matching the radius-8 rule
directly). Admission is a `ConcurrentChainedLong2ReferenceHashTable<Position>` keyed per
grid cell, each `Position` holding a `PriorityQueue<QueuedTask>` — a task is admitted
(`state -> SCHEDULED`, handed to the executor) only when it's first-in-queue at *every*
cell its AABB touches (`Position.addTask`, `AreaDependentQueue.java:177-192`). Completion
(`PositionedTask.finishTask`, line 340) re-locks just its own AABB and directly calls
`.queue()` on any newly-first dependent — **no poll loop, no timeout, anywhere in the
class** (confirmed by reading every method, including `cancel()`/`setPriority()`'s
lock-and-requeue paths). This is a structurally different design from Orion v3's coarse
lock + `-Dorion.patchReentrancy` + `claimOrWait`'s 50ms-timeout poll loop (`#50`).

### Microbenchmark: `WorldgenD/scratch/AdqBench.java`

Standalone, `concurrentutil` only (no Mojang jar, no Gradle project — `javac`/`java`
directly against `concurrentutil-0.0.10.jar`, already a WorldgenD dependency). Two
admission mechanisms race the same synthetic workload — real `AreaDependentQueue` vs. a
hand-rolled single-`ReentrantLock` model of Orion's coarse-lock design (`Condition`-signalled
on release, *not* polled — an idealized coarse lock, more favorable to that side than
Orion3's real 50ms-timeout `claimOrWait`, see caveat below):

```java
static boolean conflicts(List<int[]> active, int x, int y) {
    for (int[] a : active) {
        if (Math.abs(a[0] - x) <= 2 * RADIUS && Math.abs(a[1] - y) <= 2 * RADIUS) return true;
    }
    return false;
}
```

Both variants: 7 workers (champion count), radius 8 (real dependency ceiling), 80x80 grid
(6400 tasks — true champion footprint). **Gotcha hit and fixed**: `BalancedPrioritisedThreadPool`'s
constructor does not start worker threads — needs an explicit `adjustThreadCount(WORKERS)`
call, confirmed by the first attempt hanging indefinitely (6400 tasks queued against a
zero-worker pool) until this was added.

Three trials, escalating toward real champion parameters:

| grid | radius | work/task | ADQ | coarse (idealized) | delta |
|---|---|---|---|---|---|
| 30x30 (900 tasks) | 8 | 5ms | 3359ms | 2825ms | ADQ **19% slower** |
| 80x80 (6400, true geometry) | 8 | 3ms | 4348ms | 4262ms | ADQ 2% slower |
| 80x80 (6400, true geometry) | 8 | 20ms (true champion eMSPC) | 26806ms | 27260ms | ADQ 1.7% **faster** |

At true champion scale and per-task cost, the two mechanisms are a statistical wash —
both land close to the ~18.3s ideal-parallel lower bound (6400 x 20ms / 7), and the sign
of the delta flips between trials at a magnitude smaller than this project's own ~9%
run-to-run noise floor (`#16`/`#17`). The 30x30 trial's 19% ADQ loss is a real artifact,
not noise: at that scale the radius-16 exclusion zone (AABB overlap test) barely fits
twice across the grid, so the workload is close to fully serialized either way, and
`AreaDependentQueue`'s O(area)=289-cell bookkeeping per admit/remove costs more than the
coarse model's O(active count <= 7) scan when there's no real parallelism headroom to
spend it on.

### Projection

The idealized coarse-lock baseline used here already eliminates the one thing `#50`
actually measured as expensive — the 86% dispatch-thread park time against `claimOrWait`'s
blind 50ms poll timeout — by signalling on release instead of polling. That it still ties
`AreaDependentQueue` is the load-bearing result: **it suggests #50's park-time cost is a
property of Orion3's specific poll-with-timeout implementation, not of "coarse lock" as an
architecture.** If that holds, most of the reclaimable benefit doesn't require adopting
`AreaDependentQueue`'s full machinery (sharded lock, per-cell priority queues, O(area)
bookkeeping) — a narrower patch that replaces `claimOrWait`'s poll with a `Condition`/notify
wakeup on Orion3's *existing* coarse lock should capture most of the same win at a fraction
of the implementation cost, per this benchmark's own coarse-lock-with-signalling numbers.

Best-guess range, **low confidence**: if poll-interval artifact explains most of `#50`'s
86% park figure (plausible but unconfirmed — `#50` didn't decompose park time into
"genuinely no work available" vs. "waiting out a timeout that could have been signalled
immediately"), an event-driven wakeup (either `AreaDependentQueue` or a cheaper
signal-based patch to the existing coarse lock) could plausibly close a double-digit
percentage of that park time, but with 7 workers already only ~55-58% utilized even at
best (`#33`) and champion-scale total time bounded by real radius-8 scarcity at times
(irreducible, per both this benchmark and `#50`/`#51`'s own CPU traces), a rough guess is
**low single digits to perhaps 10% off champion eMSPC** (20.2ms) — i.e. plausibly inside
or just outside the ~9% noise band, similar in character to `#53`'s outcome, not a
transformative win. This is a projection from a synthetic microbenchmark plus documented
bottleneck data, not a measured result. A real integration + A/B (replacing `claimOrWait`'s
poll with a signalled wakeup first, since this benchmark suggests that's where the real
lever is, before attempting the larger `AreaDependentQueue` rewrite) would be needed to
confirm or kill this.

## Open questions

- Whether `champion_baseline.jfr` or a fresh profile shows any `Util.backgroundExecutor`/
  `ForkJoinPool` time worth chasing before spending effort on candidate #1.
- The `moonrise$syncLoadNonFull` hook in `ChunkGeneratorStructureState` (structure
  search's neighbor-chunk loading) wasn't traced into Moonrise's `chunk_system` to see
  if it's doing anything algorithmically different from vanilla's own structure-search
  loading, versus just being redirected through Moonrise's holder machinery for
  bookkeeping reasons.
