# WorldgenD: Findings From Committing Several Felonies Against `MinecraftServer`

Here's what happened when we pointed a `URLClassLoader` at Mojang's own jar, skipped past `initServer()` like a bouncer who owes us a favor, and started asking a `DedicatedServer` object that has never once been "started" to please make some chunks. It worked. It kept working. Then we got curious and started interrogating *why* it worked, with `jcmd` and bytecode disassembly instead of vibes, because "trust me bro" is not a methodology.

This is the science version. `TUTORIAL.md` has the narrative. Read both, believe neither, verify everything against `HeadlessWorldgen.kt` yourself like a functioning adult.

Environment: 8-core EPYC, OpenJDK 25, seed nailed to the number `69` because we are professionals with a sense of humor and nothing else.

## 1. The jar is naked and doesn't know it

`mojang-26-1-1-server.jar` is a bundler jar, and its payload — every `net/minecraft/**`, every `com/mojang/**` class — ships with **real, human-authored names**. Not proguard soup. Not `a.b.c.class`. `ChunkGenerator.class`, right there, spelled correctly, judging you. Confirmed with `jar tf` and `javap -p`, not assumed, not downloaded from a mappings repo somewhere. Mojang apparently decided obfuscation was more trouble than it was worth, which — fair, deobfuscating your own game to debug it does sound exhausting.

Net effect: zero deobfuscation tax. We reflect on the names Mojang's own engineers typed.

## 2. We wrote a program that contains none of their program

`WorldgenD` compiles to bytecode with **zero references** to any `net.minecraft.*` or `com.mojang.*` symbol — check `build.gradle.kts`, there's no dependency, static or otherwise. Every Mojang class we touch shows up in our source as a string literal handed to `Class.forName`, at runtime, against a jar YOU dropped in a folder, not one we shipped. Delete the jar and `WorldgenD` becomes a very ornate, very useless pile of reflection glue. We are not distributing Mojang's game. We are distributing a universal remote and hoping you already own the TV.

The whole discovery dance (`ServerRuntime.kt`):
1. Stare at `<cwd>/servers/` until a `.jar` shows up.
2. If it's a bundler jar, crack it open, extract the real inner server jar plus every bundled library into `servers/.cache/`, and keep the cache around so we don't do this twice.
3. `URLClassLoader` over the lot, parented to the system loader.

Bonus cheat code: `CompletableFuture`, `Optional`, `Executor`, `BooleanSupplier`, `Thread`, `File`, `Path` are JDK types, so they cross the classloader boundary for free via normal parent delegation — no reflection tax on those. Only the Mojang stuff needs the `Mc` helper (`Reflect.kt`) to babysit it.

## 3. The actual heist, step by step

How to get a fully real, generation-capable `ServerLevel` sitting in memory while **network, RCON, console thread, autosave, watchdog, and the entire tick loop stay stone dead**:

1. `SharedConstants.tryDetectVersion()`, `Bootstrap.bootStrap()` — normal so far, boring even.
2. Replay `net.minecraft.server.Main.main()`'s registry/datapack bootstrap via `WorldLoader.load(...)`, faking out its two functional-interface hooks (`WorldDataSupplier`, `ResultFactory`) with `java.lang.reflect.Proxy` since they're Mojang-defined interfaces, and handing it a dead-simple `Executor { it.run() }` instead of real background/game executors — so the entire bootstrap happens synchronously, on our thread, on our terms.
3. Don't reimplement "how do you make a new world" — steal it. Call Mojang's own private `Main.createNewWorldData(...)` via `setAccessible`. Their logic, our button press.
4. Construct a real `net.minecraft.server.dedicated.DedicatedServer` directly off its public constructor. **Never** `MinecraftServer.spin()` — that hands the object a fresh thread and immediately calls `run()`, which is precisely the "starting the server" part we are here to skip.
5. **The one crime scene we had to clean up ourselves**: `ServerLevel`'s constructor reaches into `getPlayerList().getViewDistance()`. Normally `initServer()` builds that list before anyone gets this far. Since we're never calling `initServer()`, we build a `DedicatedPlayerList` by hand and `setPlayerList()` it in ourselves. This is the single spot where "just start the server properly" would've quietly fixed something for us and we had to fix it manually instead. Everywhere else, Mojang's own machinery just works, blissfully unaware it's being used.
6. Call the protected `MinecraftServer.loadLevel()` directly. This is the whole trick, condensed into one method call: it builds a completely real `ServerLevel` + `ServerChunkCache`, generates real spawn chunks as a totally unbothered side effect, and at no point does `initServer()`, `run()`, or `tickServer()` so much as flinch.
7. Point a loaded gun at it: `ServerChunkCache.getChunkFuture(x, z, ChunkStatus.FULL, true)`.

**The one rule that will bite you if you ignore it**: step 7's futures get awaited via `MinecraftServer.managedBlock(BooleanSupplier)`, never a plain `.join()`. Some generation stages schedule their continuation back onto `MinecraftServer`'s own task queue — it's a `BlockableEventLoop` under the hood — and the only thing that ever pumps that queue is `managedBlock()`, because we murdered the tick loop that would normally do it. Swap it for `.join()` and you get a very quiet, very permanent deadlock, and you will deserve it.

## 4. The legal disclaimer that's actually load-bearing

Not one byte of Mojang's compiled game ends up inside `WorldgenD`'s repo or its own build output. The jar lives in a directory you control, gets read at runtime, gets thrown away conceptually the moment the process exits. `.gitignore` blacklists `/servers/*.jar` and `/servers/.cache/` on principle, not because anyone's watching — because it's the correct thing to do and also because 65MB of someone else's binary has no business in our git history.

## 5. Making the chaos reproducible (a seed, for once)

`level-seed=69` gets written into `servers/.run/server.properties` on every single launch, and `servers/.run/` gets nuked (`deleteRecursively()`) before that write happens. Why nuke it first: `createNewWorldData()` always builds fresh world *data* no matter what's on disk, but a stray region file left over from a previous, differently-seeded run would still get silently reloaded off disk instead of regenerated — meaning you'd think you pinned the seed and actually be looking at yesterday's leftovers wearing today's name tag.

**Verified**, not vibes: ran it twice, diffed the height/biome output. Identical down to the byte. Only the timestamps disagreed.

## 6. The Great 200% CPU Interrogation

**The crime**: hand the pool a solid contiguous block of chunks, one `managedBlock` gate, watch `top`. Eight cores standing by. The thing uses about two of them and looks pleased with itself.

Five suspects, questioned separately, alibis checked against live thread dumps instead of taken at their word:

| # | Suspect | Verdict | How we know |
|---|---|---|---|
| A | The executor pool is just too small | **Not guilty.** | `Util.getMaxThreads()`, read straight out of the disassembled bytecode (`javap -c -p`), computes `Mth.clamp(availableProcessors() - 1, 1, propertyOrDefault255)`. Eight cores, seven workers, exactly as advertised — confirmed by literally counting `Worker-Main-1` through `Worker-Main-7` in a live `jcmd <pid> Thread.print`. Tunable, if you must, via `-Dmax.bg.threads=N`. |
| C | Threads are fighting over a lock | **Not guilty.** | Every idle worker's live stack said `WAITING (parking)` inside `ForkJoinPool.awaitWork()` — genuinely nothing queued, not one single thread caught `BLOCKED` on a monitor, across multiple snapshots. Nothing was locked because there was nothing to fight over. |
| D | The heavy lifting is secretly pinned to our one calling thread | **Not guilty.** | Caught `Beardifier.getBuryContribution`, `SurfaceRules$TestRule.tryApply`, and `DensityFunctions$PureTransformer.compute` all `RUNNABLE` — on the worker pool, where they belong, never on the thread babysitting `managedBlock()`. |
| B/E | The dependency graph itself is the bottleneck | **Guilty as charged.** | Three `top -H` snapshots of the exact same run: 5/7 busy, then 1/7, then 1/7 again. A solid block only ever exposes a thin *ring* of chunks that are simultaneously eligible for whatever `ChunkStatus` is currently being chased. Everyone else just... waits. |

**The fix we tried**: stop handing it one blob. Nine islands (`CLUSTER_RADIUS = 4`, spaced 69 chunks apart — had to pick a number, it may as well be a good one) instead of one contiguous slab. Resampled: 5/7, 6/7, 7/7, 5/7 busy. Troughs, gone.

## 7. We asked the jar how far "far enough" actually is, and it told us: 8

`ChunkPyramid.GENERATION_PYRAMID`'s static initializer is a chain of `Builder.step(STATUS, b -> b.addRequirement(OTHER_STATUS, radius))` calls. Disassemble it (`javap -c -p`, no decompiler needed, no decompiler *available*, we checked) and every single `addRequirement` pushes `iconst_1` — except exactly one. `STRUCTURE_STARTS` pushes `bipush 8`.

That's it. That's the entire dependency graph's absolute ceiling, straight out of the constant pool, not guessed, not padded defensively because "safer number, whatever": **8 chunks.** We had previously been using `69` as a spacing constant purely for comedic value and it turns out we were over-provisioned by a factor of 8.6x. The bit was funnier than we deserved.

## 8. The mosaic: turning "guaranteed independence" into an actual algorithm

Since #7 gives us a hard number, any modulus `N > 8` makes this provably safe:

```
phase(cx, cz) = (cx mod N) + N * (cz mod N)
```

Two chunks sharing a phase differ by a multiple of `N` on *both* axes — meaning the absolute closest they can ever be is exactly `N` apart on one axis, zero on the other. Pick `N = 16` (a comfortable, paranoid 2x over the confirmed 8) and every chunk that shares a phase is mathematically, provably, bytecode-backed independent of every other chunk in that phase. Not "should be fine." Guaranteed. Fill a solid area phase-by-phase, real `managedBlock` barrier between each wave, and you've converted "pray the scheduler finds parallel work" into "there is categorically no dependent work left to stall on."

**Ran it.** `MOSAIC_TILE = 3` → a solid, gapless 48x48 block, 2304 chunks, 256 phases. **2304/2304. Zero failures.**

Phase timing (9 chunks per phase, because that's the shape the geometry gives you):

| Phase | ms | chunks/sec |
|---|---|---|
| 0 (cold) | 9411 | 0.96 |
| 32 | 968 | 9.30 |
| 64 | 966 | 9.32 |
| 96 | 997 | 9.03 |
| 128 | 1043 | 8.63 |
| 160 | 893 | 10.08 |
| 192 | 425 | 21.18 |
| 224 | 352 | 25.57 |
| 255 | 99 | 90.91 |
| fastest single phase | 69 | 130.43 |

~136x, cold to warm, and it **never relapses** — no mid-run spike back to seconds-per-phase the way the solid block had troughs. That flat, monotone drop is the actual fingerprint of "no scheduling stalls left, only JIT catching up." The whole-run average — 2304 / 103.4s ≈ 22.3 cps — is technically true and also a lie by omission, since it's dragged down hard by one 9.4-second cold phase and undersells steady-state throughput by 4-6x.

## 9. Is it HotSpot turning Mojang into an ICBM, or is the world just getting cheaper to make?

**The question**: that 90-130 cps warm tail in #8 — is that pure global JIT compilation (location shouldn't matter once it's hot), or is some part of it secretly a per-region cache making *previously-visited* ground cheaper than virgin territory?

**The experiment**:
1. Warm the JVM on 1681 disposable chunks near `(300,300)` — nowhere else in this experiment goes anywhere near it. Its own timing is thrown in the bin, we only need the JIT compiler to wake up.
2. Time four separate 729-chunk batches (9 islands, `CLUSTER_RADIUS = 4`, 20 chunks apart internally — deliberately tighter than the gap *between* anchors so no batch could accidentally reuse another's homework) at four anchors chosen to split near from far, and to never overlap the warmup zone or each other: `(69,69)`, `(-69,69)`, `(6969,6969)`, `(-6969,-6969)`.

**The verdict, in numbers:**

| Anchor | Chunks | Time | chunks/sec |
|---|---|---|---|
| near NE (69,69) | 729 | 43612ms | 16.7 |
| near NW (-69,69) | 729 | 50497ms | 14.4 |
| far NE (6969,6969) | 729 | 46905ms | 15.5 |
| far SW (-6969,-6969) | 729 | 44945ms | 16.2 |

**It's the ICBM.** All four numbers huddle together in a tight 14.4-16.7 band with zero relationship to distance whatsoever — the slowest run of the whole experiment is a *near* anchor, and both 6969-chunks-into-the-void anchors, territory the process had never so much as glanced at, land smack in the middle of the pack, not trailing behind. If per-region caching were pulling any real weight, "far" should lose to "near" every single time. It doesn't lose once. Once the JIT is hot, walking 6969 chunks into unknown territory costs exactly nothing extra — the entire #8 speedup was HotSpot compiling code, 0% about *where* that code happened to run.

**Plot twist buried in the same data**: 14-17 cps is 5-8x *slower* than the mosaic's warm tail, despite an equally hot JIT in both cases. Not a contradiction — a shape problem. `CLUSTER_RADIUS = 4` makes each island a solid 9x9 block, and 9 chunks wide is barely bigger than the radius-8 dependency we confirmed in #7 — so even though the 9 islands genuinely don't depend on each other, each individual island still can't expose more than a sliver of its own 81 chunks as simultaneously eligible. Mosaic phases hit triple digits because *every single chunk in a phase* is guaranteed eligible by construction. Islands only guarantee that property *between* islands, never within one. Shape matters more than heat, past a certain point.

## 10. Things that lied to us with a straight face

- **`System.out` goes fully buffered, not line-buffered, the second you redirect it to a file.** A long run's later `println`s can sit in a JVM-internal buffer touching nothing on disk until the process actually dies. A log that looks frozen for four minutes might already be finished — before you panic, diff two `jcmd <pid> Thread.print` snapshots a few seconds apart and see if worker CPU time is still climbing. If it's flat, the work is done; your patience is just outrunning `PrintStream`'s flush policy.
- **The process itself hangs around suspiciously long after the last chunk is done.** In the #9 experiment, worker-thread CPU had visibly stopped climbing well before the JVM actually exited. Leading theory (not yet nailed down): `Util.ioPool()` and friends may include non-daemon threads — Mojang's own `makeIoExecutor(String, boolean)` literally takes a daemon-or-not flag as an argument — and we never call `Util.shutdownExecutors()` or touch any part of the real shutdown path, because touching the real shutdown path was never the point. See Open Questions; someone should go actually confirm this instead of just being smug about it in a markdown file.

## 11. MSPC: formalizing "milliseconds per chunk"

**In plain terms, before the formal version**: imagine ordering 6,400 coffees, one at a time, from a shop with a handful of baristas, and writing down — for *every single coffee* — exactly how long it took from "I'll have a latte" to the cup hitting the counter. Most cups come out fast. A few get unlucky and land behind a rush, and take way longer. MSPC is that stopwatch, run once per chunk of land instead of per coffee. We don't just report one "average" time, because an average is one number trying to speak for both the good cups and the one bad cup at once — it always blurs the two together. So instead we report: how fast was the fastest cup, how fast was a typical cup, and how bad did the unluckiest 1-in-100 cup get. Smaller numbers are better, everywhere. A big gap between "typical" and "worst" just means some chunks got stuck waiting their turn — nothing here requires knowing what a thread or a CPU core is.

Now the formal version. "chunks/sec" was always the wrong shape of number for a run built out of 256 barriers with wildly different queue depths — an average over the whole run drowns the one cold phase exactly the way #8 already complained about (`22.3 cps` average vs `90-130 cps` warm tail, same run). MSPC (**m**illi**s**econds **p**er **c**hunk) replaces it with a per-*chunk* latency sample instead of a per-*phase* rate.

**Definition**: for every chunk, stamp `System.nanoTime()` the instant `getChunkFuture(x, z, FULL, true)` is called, and again the instant that specific future completes — via `.whenComplete {}`, which fires on whichever worker thread actually finishes it, not when the phase barrier releases the calling thread. `mspc = (completionNanos - submitNanos) / 1_000_000.0`. One sample per chunk: 2304 of them for the standard `MOSAIC_TILE = 3` run, not 256 phase averages.

**Reported as a distribution, not a mean**, because the mean is exactly the lie-by-omission #8 already flagged for whole-run cps:

| Stat | What it is |
|---|---|
| min | fastest single chunk in the run |
| p1 | fastest 1% |
| p25 | |
| p50 | median |
| p75 | |
| p99 | slowest 1% — cold-JIT and pool-saturation stragglers live here |
| max | single worst chunk (almost always phase 0, before anything's compiled) |

Percentiles use linear interpolation, `rank = p/100 * (n-1)` interpolated between the two bracketing samples — the same convention most stats tooling (numpy's default, `PERCENTILE.INC`) uses, so these numbers compare directly elsewhere without translation.

Lives in `HeadlessWorldgen.kt` as `chunkMspc` (a `Collections.synchronizedList`, since worker threads append to it concurrently), `percentile()`, and `mspcSummary()`. Printed automatically at the end of every run, right after the `Done:` line.

## 12. Reopening #131: does `-Dmax.bg.threads=16` actually do anything at `MOSAIC_TILE = 3`? (superseded, see #13)

Ran the standard 48x48/2304-chunk mosaic twice, everything identical except the JVM flag, both instrumented with MSPC:

| Config | Total | MSPC min | p1 | p25 | p50 | p75 | p99 | max |
|---|---|---|---|---|---|---|---|---|
| default (7 workers) | 97718ms | 0.07 | 1.51 | 8.67 | 41.82 | 49.40 | 141.90 | 2230.50 |
| `-Dmax.bg.threads=16` | 111224ms | 0.03 | 1.56 | 8.65 | 48.25 | 57.13 | 171.08 | 2191.96 |

Not a win — slightly worse across the board (p50 +15%, p99 +21%, total +14%), one trial each, no replication.

At the time this was written down as "the tile is too small to expose a difference." **That diagnosis was wrong**, corrected in #13: the real reason both rows are identical-ish is that `-Dmax.bg.threads=16` never changed the worker count at all. Left here for the record instead of rewritten, because the wrong turn is itself worth keeping — see #13 for why, and for the actual proof.

## 13. `-Dmax.bg.threads` is a ceiling, not a target — proven both directions with `jcmd`, at `MOSAIC_TILE = 5`

The formula was in this document since #6 and got misread anyway: `Util.getMaxThreads()` computes

```
threads = clamp(availableProcessors() - 1, 1, max.bg.threads)
        = max(1, min(availableProcessors() - 1, max.bg.threads))
```

`max.bg.threads` is the **upper bound** of the clamp, not the value it produces. On this 8-core box, `availableProcessors() - 1 = 7` sits below any `max.bg.threads ≥ 7` — so `-Dmax.bg.threads=16`, `=255` (the default), or omitting the flag entirely all clamp to the identical `min(7, N) = 7`. #12's two rows were never two configurations; they were the same 7-worker pool measured twice. That's *why* they looked the same — not because the tile was too small.

Bumped `MOSAIC_TILE` from 3 to 5 (25 chunks/phase, 6400-chunk mosaic, 80x80 block) and ran three configs, catching a live `jcmd <pid> Thread.print` mid-run for the two flagged ones to check the actual worker count instead of trusting the arithmetic:

| Config | Workers, `jcmd`-confirmed | Total | MSPC min | p1 | p25 | p50 | p75 | p99 | max |
|---|---|---|---|---|---|---|---|---|---|
| default | 7 (formula; not re-dumped this run, already confirmed in #6/#12) | 235167ms | 0.04 | 1.57 | 8.20 | 35.80 | 44.05 | 137.03 | 1492.91 |
| `-Dmax.bg.threads=16` | **7** — live dump showed exactly `Worker-Main-1` through `Worker-Main-7`, nothing higher | 256587ms | 0.04 | 1.53 | 8.49 | 40.45 | 48.95 | 149.94 | 1571.50 |
| `-Dmax.bg.threads=4` | **4** — live dump showed exactly `Worker-Main-1` through `Worker-Main-4`, nothing higher | 233174ms | 0.06 | 1.48 | 7.77 | 35.24 | 43.18 | 138.77 | 1496.63 |

**First result, now airtight**: the flag genuinely only ever lowers the pool below `cores - 1`. Setting it above does nothing — verified live, not inferred from timing — because the clamp's own ceiling was already the smaller number.

**Second result, unexpected and still unexplained**: 4 workers finished in 233174ms — statistically identical to 7 workers' 235167ms, MSPC medians 35.24 vs 35.80. A 43% cut in worker count against a phase with 25 chunks in flight (comfortably more than either thread count) produced no measurable slowdown. If worker count between 4 and 7 doesn't move throughput at all here, something else is the active ceiling in that range — not scheduling, not the radius-8 dependency graph (25 chunks/phase already clears that), something else. Two live candidates, neither checked yet: (a) genuinely compute-bound — each chunk's noise/surface/feature math costs enough raw CPU time that 4 saturated cores already match 7, with the other cores going to JIT (C1/C2) and GC threads that compete for the same 8 physical cores regardless of worker-pool size; (b) the explicit "25 chunks requested per phase" undercounts the real task graph — `getChunkFuture` recursively pulls in neighbor chunks at lower statuses as prerequisites, and those recursive sub-tasks may not decompose into as many truly-parallel units as the requested-chunk count suggests. Neither is confirmed. The next diagnostic is the same one #6 already used and it wasn't rerun here: sample thread *states* (`RUNNABLE` vs `WAITING`) live during a 4-worker run — if all 4 are pegged `RUNNABLE` the whole time, it's (a); if there's real idle time even at 4 workers, it's something closer to (b) or a third thing nobody's looked for yet.

## 14. Is G1 the reason 4 workers keeps up with 7? Tried three collectors to find out

#13 left one real open question: cutting the worker pool 7→4 at `MOSAIC_TILE = 5` cost nothing measurable, and nothing in this document explained why. One live candidate: G1's own background threads (concurrent marking, refinement, remembered-set scanning) compete for the same 8 physical cores as the generation workers, so maybe the *collector* — not the dependency graph, not the pool size — is the thing quietly capping throughput regardless of worker count.

Controlled for a variable that had been silently uncontrolled up to now, too: every prior run let the JVM pick its own heap size and grow it on demand. That's its own confound — heap-resize pauses and lazy page commits cost real time and have nothing to do with GC algorithm choice. So every run below fixes `-Xms16g -Xmx16g` (half this box's 32GB) plus `-XX:+AlwaysPreTouch` (commit and zero every page up front, so no first-touch page faults happen mid-run) — same heap footprint across all three, only the collector changes. Confirmed per-run via `ManagementFactory.getGarbageCollectorMXBeans()`, logged as `Active GC(s):` at the top of every run now (`HeadlessWorldgen.kt`), the same "ask the JVM, don't trust the flag" discipline #13 established for thread counts.

Three configs, same `MOSAIC_TILE = 5` mosaic, same 7-worker pool (no `-Dmax.bg.threads` involved this time — that's a separate axis, not crossed with this one yet):

| Config | Confirmed GC | Total | MSPC min | p1 | p25 | p50 | p75 | p99 | max |
|---|---|---|---|---|---|---|---|---|---|
| default (no GC flag) | `G1 Young Generation, G1 Concurrent GC, G1 Old Generation` | 252285ms | 0.05 | 1.68 | 7.91 | 40.05 | 48.28 | 144.33 | 1561.90 |
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=1000` (throughput-tuned: a loose pause budget lets G1 batch bigger, less frequent collections instead of chasing a tight pause target) | `G1 Young Generation, G1 Concurrent GC, G1 Old Generation` | 248592ms | 0.06 | 1.72 | 7.85 | 39.74 | 47.29 | 138.27 | 1513.73 |
| `-XX:+UseZGC` (generational — the only mode ZGC has on JDK 24+; `-XX:+ZGenerational` itself was removed, confirmed by `java -XX:+ZGenerational -version` printing "Ignoring option ZGenerational; support was removed in 24.0") | `ZGC Minor Cycles, ZGC Minor Pauses, ZGC Major Cycles, ZGC Major Pauses` | 237127ms | 0.05 | 1.76 | 8.38 | 37.21 | 44.72 | 134.12 | 1428.92 |

**G1-default vs. G1-throughput-tuned: no real difference** (248592ms vs 252285ms, p50 39.74 vs 40.05) — unsurprising in hindsight, since on this box's actual heap-occupancy pattern (6400 short-lived chunk objects, 16GB pretouched headroom) G1 apparently wasn't hitting its pause-time target often enough for loosening that target to matter.

**ZGC is the one real signal here**: ~6% faster total (237127ms vs 252285ms) and better at every single percentile, not just the median — p50 down 7%, p99 down 7%, max down 8%. Consistent with ZGC's actual design difference from G1: its marking and relocation work runs concurrently on its own threads with much shorter, more predictable pauses, so it should show up exactly where a mild, real GC-pressure signal would show up — the whole distribution shifting down slightly, not one outlier phase getting fixed.

**But it doesn't close #13's question.** A 6-8% gain from switching collectors entirely is nowhere near enough to explain how a 43% cut in worker count (7→4) produced a *0%* throughput cost. If G1's background threads were the dominant reason more workers weren't helping, removing G1 entirely should have unmasked a much bigger jump — not a single-digit-percent one. So: GC pressure is real and measurable (ZGC's clean sweep across every percentile proves that), but it's a minor contributor, not *the* answer to why 4 and 7 workers tie. That still points back at #13's other live candidate — the explicit 25-chunks-per-phase count undercounting the real internal task graph — as the more likely explanation, now with one confound (GC choice) ruled out as the primary cause instead of just unexamined.

**Not yet tried**: crossing this with worker count — rerun `-Dmax.bg.threads=4` *under ZGC* specifically, to see if a cheaper collector changes whether cutting workers costs anything. If GC pressure were secretly protecting the 4-worker run from looking worse, ZGC (less background contention) should make the 4-vs-7 gap *reappear*. If it still doesn't, that's stronger evidence the real ceiling is upstream of the collector entirely.

## 15. Crossing GC choice with the 4-worker experiment — plus a fourth collector, ParallelGC

#14 explicitly flagged this as untried: rerun `-Dmax.bg.threads=4` *under* each collector to see whether a cheaper GC changes anything about #13's 4-vs-7-worker tie. Also added a fourth collector on request — ParallelGC, the plain stop-the-world throughput collector, no concurrent phases at all — and a set of ZGC-specific throughput flags (`-XX:-ZProactive` to stop periodic idle collections, `-XX:ZAllocationSpikeTolerance=4` to make it wait longer before reacting to an allocation spike), the ZGC analogue of #14's `-XX:MaxGCPauseMillis=1000` G1 knob.

Same `MOSAIC_TILE = 5` mosaic, same `-Xms16g -Xmx16g -XX:+AlwaysPreTouch`, four configs, all pinned to `-Dmax.bg.threads=4` (jcmd-confirmed exactly `Worker-Main-1` through `Worker-Main-4` on the first row; the clamp mechanism proven bijective in #13 covers the rest):

| Config | Confirmed GC | Total | MSPC min | p1 | p25 | p50 | p75 | p99 | max |
|---|---|---|---|---|---|---|---|---|---|
| `-XX:+UseZGC -XX:-ZProactive -XX:ZAllocationSpikeTolerance=4` | `ZGC Minor/Major Cycles/Pauses` | 241169ms | 0.05 | 1.80 | 8.21 | 37.34 | 44.82 | 141.52 | 1580.83 |
| `-XX:+UseZGC` (plain) | `ZGC Minor/Major Cycles/Pauses` | 241066ms | 0.03 | 1.76 | 8.20 | 37.44 | 44.55 | 134.74 | 1736.96 |
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=1000` | `G1 Young/Concurrent/Old` | 226112ms | 0.06 | 1.56 | 7.80 | 34.77 | 41.85 | 136.38 | 1588.86 |
| `-XX:+UseParallelGC` | `PS Scavenge, PS MarkSweep` | 225922ms | 0.05 | 1.55 | 7.84 | 34.78 | 41.84 | 135.56 | 1428.65 |

**ZGC's own throughput flags did nothing** (241169ms vs 241066ms plain — inside noise), the same pattern #14 already saw when G1 got a looser pause target: loosening an already-loose collector's own safety margins doesn't move this workload.

**G1-throughput and ParallelGC are now statistically tied** (226112ms vs 225922ms, 0.1% apart) — and that itself is informative. `-XX:MaxGCPauseMillis=1000` sets G1's pause budget so loose that it starts behaving like a plain stop-the-world collector, and at 4 workers it lands exactly where ParallelGC (which never had a pause target to begin with) already sits.

**The real finding: rank order flipped.** At 7 workers (#14), ZGC was the *fastest* of the collectors tested. Here, at 4 workers, ZGC is the *slowest* of all four — beaten by both G1-throughput and ParallelGC by ~6%. Nothing about "ZGC is faster" survived the switch from 7 to 4 workers; it reversed.

This also overturns half of #13's own conclusion. #13 found that cutting workers 7→4 cost *nothing* — but that test used default ergonomic G1 at both worker counts. Compared against #14's G1-throughput row at 7 workers (248592ms), this same G1-throughput config at 4 workers (226112ms) is **~9% faster**, not a tie. Worker count and GC choice interact; "cutting workers is free" isn't a property of the mosaic algorithm in isolation, it's true for some collectors and false for others.

**A candidate mechanism, not yet verified**: ZGC dedicates real CPU cores to concurrent marking/relocation threads that run *alongside* the mutator, whether or not the mutator has spare cores to give up. At 7 workers there's only 1 idle core on this 8-core box — overlapping GC work with mutator work is worth it there, since fully pausing all 7 workers for a G1/Parallel-style STW collection would cost more wall-clock than ZGC's small continuous tax. At 4 workers there are 4 idle cores — a STW pause is now cheap (only 4 live workers to interrupt, plenty of headroom to blast through the collection fast) while ZGC keeps paying its fixed concurrent-thread cost regardless of how few mutator threads it's overlapping with. Not confirmed by a live trace — the direct test is sampling `jcmd <pid> Thread.print` thread *states* for ZGC's concurrent GC threads specifically at both worker counts, to see if they're doing less useful work per core at 4 workers than at 7. Untried.

**Still open**: this doesn't fully explain #13's original 4-vs-7 tie under *default* ergonomic G1 (233174ms vs 235167ms) — that pairing is unaffected by anything tested here, since neither of today's runs used unmodified default G1. What today's experiment does establish is that the tie isn't a fixed, GC-independent property of the mosaic — it's real for default G1, disappears (turns into a 9% win) for tuned G1, and reverses into a small loss for ZGC.

## 16. JFR says the reflection heist is already free — we "optimized" it anyway and made it slower

Every experiment above measured wall-clock time. That answers "how long did it take," not "where did the time go" — so this one brings in `-XX:StartFlightRecording` against the current champion (ParallelGC, 4 workers, 16GB pretouched heap, `MOSAIC_TILE = 5`), `delay=45s` to skip past JIT warmup, `settings=profile` to get CPU (`jdk.ExecutionSample`), allocation (`jdk.ObjectAllocationSample`), lock (`jdk.JavaMonitorEnter`/`Wait`), and GC events in one recording. Every number below comes from `jfr summary` and grep/awk aggregation over `jfr print --events <type>` output — never a raw, unaggregated `jfr print` dump, which for this recording would have been tens of thousands of lines.

**CPU**: 23101 execution samples over the 260s recording. The top 30 leaf frames are 100% `net.minecraft.*`/`com.mojang.*`/`it.unimi.dsi.fastutil.*` — `ImprovedNoise.p`/`.noise`, `SurfaceRules$TestRule.tryApply`, `Aquifer$NoiseBasedAquifer.computeSubstance`, `NoiseChunk`, `BiomeManager.getBiome`, `PalettedContainer`. Exactly 5 samples had `io.github.eath1283.worldgend.*` as the true leaf (currently-executing) frame — all on the `main` thread, none on the 4 worker threads. At the JDK's own documented 10ms `jdk.ExecutionSample` period (confirmed straight from `$JAVA_HOME/lib/jfr/profile.jfc`, not assumed), that's **5 × 10ms = 50ms of WorldgenD's own code actually executing, out of 260,000ms recorded — 0.019%.** Two of those five samples sit at source lines beyond the 301-line file's own length (Kotlin's SMAP attributing inlined-stdlib bytecode, e.g. `mspcSummary`'s `String.format` calls, to synthetic line numbers — a real but harmless compiler artifact, not a bug). A deeper stack dump (depth 8) shows many more samples with `ReflectKt.call`/`Method.invoke` as *callers*, but the leaf beneath them is genuine Mojang code (`DistanceManager.runAllUpdates`, `ChunkMap.runGenerationTasks`, `TicketStorage.addTicket`) — that's the main thread driving `managedBlock()` through Mojang's own single-threaded scheduling, invoked reflectively; the trampoline frame costs nothing, the real work beneath it is 100% Mojang's.

**Allocations**: top 25 `jdk.ObjectAllocationSample` classes are all genuine Mojang scratch objects (`long[]`/`double[]` noise buffers, `SurfaceRules$Context` lambdas, `BlockPos`, `DensityFunctions$*`) — no `Method[]`, no boxed `Integer`, nothing reflection-shaped anywhere in the list.

**Locks**: zero `jdk.JavaMonitorEnter` events above the profile preset's 10ms threshold; 4 `jdk.JavaMonitorWait` events total. No meaningful lock contention in this workload.

**GC**: 17 young collections (`PS Scavenge`, confirming ParallelGC), totaling ~845ms of pause time across the 260s window — under 0.4% of wall time. Consistent with #14/#15: a 16GB pretouched heap for a 6400-chunk run barely troubles the collector regardless of which one it is.

So before touching any code, the profile already answered the question it was built to answer: WorldgenD's own reflection layer costs about 50 milliseconds out of a 230-second run. There was no real problem to fix. Went and looked anyway, because a genuine bug was visible straight from the source: `mc.publicMethod(result.javaClass, "isSuccess").call(result)` re-resolves that method via a fresh `Class.getMethod()` lookup on every one of 6400 chunks, every run, instead of once. Fixed it with a `ConcurrentHashMap<Pair<Class<*>, String>, Method>` cache (`Mc.publicMethodCached()`), and — since the JFR write-up was explicitly asked to bring "the heavy machinery" — also converted the two genuinely hot-path calls (`getChunkFuture`, 6400/run; `managedBlock`, 256/run) from `Method.invoke()` to raw `MethodHandle.invoke()`, on the theory that skipping `Object[]` boxing for `cx`/`cz` would help.

It didn't. Three fresh, back-to-back runs at identical config (no stale numbers reused — an earlier attempt at reusing a 30-minutes-prior baseline number produced a contradiction that turned out to be ordinary session-to-session system drift, not a real regression, which is exactly why this round re-ran everything paired and adjacent-in-time instead of trusting old numbers):

| Variant | Total | MSPC p50 |
|---|---|---|
| Baseline (uncached `isSuccess`, plain `Method.invoke()`) | 229728ms | 35.35ms |
| `MethodHandle` conversion for `getChunkFuture`/`managedBlock` | 247528ms | 39.34ms |
| Cached `Method` (uncached bug fixed, no `MethodHandle`s) — **shipped** | 229674ms | 35.44ms |

The `MethodHandle` version was reproducibly ~7-8% *slower*, not faster (a second, less-controlled pair of runs earlier in this session showed the same direction, ~4-9% slower). The likely reason: since JDK 18 (JEP 416, "Reimplement Core Reflection with Method Handles"), `Method.invoke()` is *already* implemented internally via a generated `MethodHandle`-backed accessor (`jdk.internal.reflect.DirectMethodHandleAccessor`, visible directly in the JFR stack traces above) — so converting our own call sites to a raw `MethodHandle.invoke()` didn't remove a layer of indirection, it added a second, differently-shaped one. Kotlin's polymorphic-signature call sites erase arguments to `Object` (the call sites here pass `chunkSource`/`fullStatus`/`dedicatedServer` all typed as `Any`), so every `invoke()` does a checked-cast/adaptation dance the JDK's own already-specialized accessor apparently avoids. Reverted; kept only the `Method` cache, which is statistically identical to baseline (229674ms vs 229728ms — 0.02% apart, noise) because that's exactly what the profile predicted a reflection fix would look like: correct, and invisible.

**The actual lesson, not the one going in expecting it**: "cache reflection, use MethodHandles" is folk wisdom that doesn't automatically hold on a modern JDK where `Method.invoke()` is already MethodHandle-shaped under the hood. Measure before assuming an "obviously faster" primitive actually is, on this JDK, for this call shape.

## 17. Tuning ParallelGC to "leverage the 8 EPYC cores" — and discovering the box's own noise floor instead

The champion is untouched default ParallelGC (#15). Natural next question: can explicit flags make it collect faster by using this box's 8 cores harder? Checked the ergonomic defaults first instead of guessing (`java -XX:+UseParallelGC -XX:+PrintFlagsFinal -version`) — `ParallelGCThreads=8` and `ParallelRefProcEnabled=true` are *already* the defaults on this box, so pinning them explicitly would be the exact same no-op #13 already caught once with `-Dmax.bg.threads`. The one default actually worth challenging: `UseDynamicNumberOfGCThreads=true`, which lets ParallelGC use *fewer* than 8 threads for small collections — the opposite of "leverage 8 cores." Tested `-XX:-UseDynamicNumberOfGCThreads -XX:GCTimeRatio=199` (force full 8-thread parallelism on every collection, plus a throughput target twice as aggressive as the 99:1 default) against fresh, un-reused baseline runs (#16 already burned the lesson about trusting old numbers across a long session):

| Run (in order) | Total | MSPC p50 |
|---|---|---|
| Default ParallelGC (sample 1) | 247739ms | 39.64ms |
| Forced full parallelism + `GCTimeRatio=199` | 231527ms | 35.37ms |
| Default ParallelGC (sample 2) | 225381ms | 34.81ms |

Read in isolation, the tuned run looks 6.5% faster than the baseline immediately before it. Read against *both* baselines, it looks unremarkable: **two untouched, unmodified default-ParallelGC runs, back to back, spanned 225381-247739ms — a 9% range with zero code or flag changes anywhere.** The tuned sample (231527ms) sits inside that range, not below it. There is no evidence here that the flags did anything; there is good evidence that this box has ~9% run-to-run noise on this workload, which the earlier `MethodHandle` finding in #16 happened to clear (that one showed the same direction twice, in independently-paired comparisons) but this one doesn't even attempt to clear, at n=1 tuned sample.

This is the expected outcome once you already know #16: JFR proved GC costs under 0.4% of wall time on this workload. There is no headroom left to tune away — forcing more parallelism onto collections that already take single-digit milliseconds and happen 17 times in a quarter-hour run cannot move a number that small. The honest conclusion is a null result, not a negative one: **ParallelGC was already about as fast as this workload lets a garbage collector be**, and the interesting number tonight turned out to be the box's own noise floor, not anything about the collector.

## 18. The drag race: WorldgenD vs. real Paper/Leaf servers, and WorldgenD loses badly

Everything above compared WorldgenD against itself. This section compares it against the thing it was never trying to be — a real, ticking Minecraft server — to find out how much of #17's "there's no headroom left" conclusion was true in an absolute sense versus only true *relative to WorldgenD's own ceiling*.

**Setup**: a separate `control/` directory (sibling of `WorldgenD/`), holding unmodified `paper.jar` and `leaf.jar` (Leaf is a Paper fork), the Chunky-Bukkit pre-generation plugin, and three launch scripts — `start-paper.sh`, `start-leaf.sh`, `start-leaf-with-crack.sh` — all three pinned to `-Xms16384M -Xmx16384M` plus the community-standard "Aikar's flags" G1GC tuning (`MaxGCPauseMillis=200`, tuned `G1NewSizePercent`/`G1HeapRegionSize`/`InitiatingHeapOccupancyPercent`, etc. — a different, more elaborate G1 tune than #14's simple `MaxGCPauseMillis=1000` experiment). The crack variant adds `-DLeaf.enableFMA=true -DLeaf.disable-vanilla-profiler=true -DLeaf.disable-vanilla-debug-feature=true`. Driven entirely over RCON (a from-scratch ~30-line Source RCON client, since no `mcrcon` binary was available) rather than piping a console — real servers, real commands, no reflection heist involved at all here.

Chunky configured identically for every run, verified via `chunky selection` after each reboot before starting:

```
chunky world world
chunky shape square
chunky center 0 0
chunky radius 640
```

That's a radius-640 square — 6561 chunks (81×81, since Chunky's radius is inclusive of the center chunk; **not** the same chunk count as WorldgenD's own 6400, a real, acknowledged mismatch, not an oversight). World wiped (`control/wipe-world.sh`) and the server rebooted fresh before every single run, so no run ever benefited from a previous run's cached spawn chunks. Timing taken three independent ways per run and cross-checked: the wall-clock instant `chunky start` was sent via RCON, the server log's own timestamps, and Chunky's self-reported `Total time:` line — all three agreed to within a second on every run. Full `[Chunky]`-tagged log output for all three runs saved in `findings/paper_chunky_run1.log`, `findings/leaf_chunky_run1.log`, `findings/leafcrack_chunky_run1.log`.

| Config | Chunks | Total time | Avg ms/chunk | Chunks/sec |
|---|---|---|---|---|
| **WorldgenD** (headless, ParallelGC, 4 workers — the #15/#16 champion) | 6400 | 229674ms | 35.89 | 27.87 |
| Paper (Aikar's flags) | 6561 | 153770ms | 23.44 | 42.67 |
| Leaf (Aikar's flags, no crack flags) | 6561 | 154670ms | 23.57 | 42.42 |
| Leaf-with-crack (+ FMA/profiler-disable, `/tick freeze` active) | 6561 | 151760ms | 23.13 | 43.23 |

**WorldgenD is ~53% slower, throughput-normalized, than plain unmodified Paper.** Not close, not within this box's demonstrated ~9% noise band (#17) — a real, large gap. All three real-server configs land within 2% of each other (matching #15's own G1-vs-Parallel-vs-ZGC pattern of "GC/flag differences are single-digit-percent, not the dominant effect"); the interesting comparison isn't Paper-vs-Leaf-vs-crack, it's any-of-them-vs-WorldgenD.

**The obvious hypothesis — "Paper just uses more cores" — is wrong, and checkably so.** The server log states plainly: `[MoonriseCommon] Paper is using 2 worker threads, 1 I/O threads`. That's Paper's own auto-sized (`worker-threads: -1` in `paper-global.yml`, confirmed unmodified — not something tuned down for this test) dedicated chunk-generation pool, on this same 8-core box, and it beat WorldgenD's *4* workers while itself using *2*. Whatever is faster here, it isn't "more parallelism."

**Working theory, not yet proven with a source diff**: Paper and its forks carry real, substantial patches to the chunk-generation pipeline itself — caching, restructured data layouts, region-based scheduling that reduces redundant lookups across neighboring chunks — layered *on top of* the same underlying vanilla Mojang algorithms. WorldgenD's entire design constraint, stated since #1, is that it will never touch or replace a single line of Mojang's own generator or concurrency code — it drives the *unmodified* official jar, by design. That constraint is exactly what caps it here: Paper isn't winning by using the hardware differently, it's winning because its generator does measurably less work per chunk than vanilla's, and WorldgenD, being deliberately vanilla, cannot access that speedup without ceasing to be what it is. This reframes #13-#17's whole thread-count/GC-tuning investigation: all of that tuning was optimizing around the edges of a generator implementation that a widely-used fork had already found ~53% more headroom in, through changes WorldgenD's own founding constraint rules out by definition.

**Caveats on the numbers themselves**: (a) 6561 vs 6400 chunks — the chunks/sec column normalizes for this, the raw total-time column doesn't. (b) Chunky exposes no per-chunk timing, only a periodic `Rate: X cps` line roughly once a second — the "avg ms/chunk" above is `total_ms / chunks`, a true average, not a percentile distribution the way WorldgenD's MSPC is. A rate-derived pseudo-distribution was computed for each run (inverting each `Rate:` sample to ms/chunk and taking percentiles across those ~140 per-run samples) but is deliberately not shown side-by-side with WorldgenD's real per-chunk percentiles in a single chart, since a distribution of ~1-second window averages and a distribution of individual chunk completions are not the same statistic — putting them on one staircase chart would imply a comparability that isn't there. (c) `/tick freeze` was active only for the crack run, an attempt to strip tick-loop overhead closer to WorldgenD's own "no tick loop ever ran" baseline; its effect size here (151760ms vs Leaf's 154670ms with ticking active) is small enough to be inside noise, not a proven contributor — a fourth run (Leaf, plain flags, tick frozen) would be needed to isolate tick-freeze's own effect from the FMA/profiler flags', which was not run tonight.

**Open question this leaves**: is the ~53% gap really the generator patches, or does some of it come from something checkable tonight but not checked — e.g. Chunky's own chunk-request ordering/pattern differing from the mosaic's residue-class order in a way that matters for cache locality? The thread-count evidence rules out raw parallelism specifically; it doesn't rule out every other explanation.

## 19. Sustained test: tripling the workload, and Leaf finally pulls ahead of Paper

#18 ran a comparatively small job (6561 chunks) — small enough that a real difference between Paper and Leaf could plausibly hide inside noise the way #17 demonstrated this box is capable of. This one triples Chunky's linear radius (640 → 1920, so ~9x the chunks: 58081) for Paper and Leaf, and separately doubles WorldgenD's own linear scale (`MOSAIC_TILE` 5 → 10, 6400 → 25600 chunks) plus reruns the original tile-5 size fresh, specifically to check whether WorldgenD's own throughput holds steady as the job gets bigger — a distinct question from #18's cross-engine comparison.

Same methodology as #18: world wiped and server rebooted fresh before every run, Chunky's `world`/`shape square`/`center 0,0`/`radius 1920` selection reapplied and verified each time, timing cross-checked between the RCON-send instant and the log/self-report (agreement within 1s on both runs, same as #18). Leaf ran with its *plain* Aikar's-flags config — no crack flags, no `/tick freeze` — specifically to get a bigger, more statistically trustworthy sample of "Leaf vs. Paper with nothing else different" than #18's single small run could give.

| Config | Chunks | Total time | Avg ms/chunk | Chunks/sec |
|---|---|---|---|---|
| WorldgenD (`MOSAIC_TILE=5`, fresh sample) | 6400 | 244316ms | 38.17 | 26.20 |
| WorldgenD (`MOSAIC_TILE=10`, doubled) | 25600 | 980512ms | 38.30 | 26.11 |
| Paper (radius 1920) | 58081 | 1218200ms | 20.97 | 47.68 |
| Leaf, plain flags (radius 1920) | 58081 | 1147310ms | 19.75 | 50.62 |

**WorldgenD's own throughput is scale-invariant**: 26.20 vs 26.11 chunks/sec at 4x the chunk count — a 0.3% difference, well inside this box's noise band, confirming the fixed 16GB pretouched heap and 4-worker pool don't start straining at 4x the job size. Good news for anyone about to run WorldgenD on something bigger than a demo-sized mosaic (see the last Open Questions bullet about untested scale).

**Leaf pulls ahead of Paper for real this time**: 50.62 vs 47.68 chunks/sec, a ~6% gap — and unlike #18's statistically-tied 42.42-vs-42.67 result at the smaller sample size, this one's built on a run nearly 9x longer, which is exactly the kind of larger sample that should surface a real small effect #18 didn't have the statistical power to see. Notably, this is **plain Leaf**, no crack flags, no tick freeze — meaning whatever Leaf does differently from Paper by default (not the opt-in `-DLeaf.*` extras from #18) is worth a real ~6% on a job this size.

**The headline from #18 stands, and gets more data behind it**: both real servers are still roughly double WorldgenD's throughput (47.68/50.62 vs ~26.1-26.2 chunks/sec) at a scale nearly 10x larger than #18's, ruling out "the earlier gap was some small-N fluke" as an explanation. Whatever Paper/Leaf's generator patches are doing, they keep doing it at scale.

**Also along the way**: confirmed empirically (not assumed) that WorldgenD's generated chunks never actually reach disk. `servers/.run/.../region/*.mca` files exist after a run — Minecraft's storage layer creates the region-file handle the moment a chunk load is requested — but every single one is **0 bytes**, because WorldgenD never calls `save()` and never runs the tick loop whose autosave would normally flush chunks to disk. Generated chunks live only in the in-memory `ChunkMap` and vanish the instant the JVM exits. Worth knowing before anyone assumes a completed WorldgenD run leaves usable region files behind — it doesn't.

Raw Chunky logs: `findings/paper_chunky_sustained.log`, `findings/leaf_chunky_sustained.log`. Data: `findings/sustained_results.csv`.

## 20. Charts, for posterity

Every number in #12 through #19, plotted. Raw data lives in `findings/mspc_results.csv`, `findings/gc_results.csv`, `findings/gc_4w_results.csv`, `findings/jfr_ab_results.csv`, `findings/pgc_tuning_results.csv`, `findings/drag_race_results.csv`, and `findings/sustained_results.csv`; every chart regenerates with:

```
python3 findings/plot_results.py
```

(needs `matplotlib` — on Debian/Ubuntu, `apt-get install python3-matplotlib`)

![MSPC percentiles across all five thread-count experiment runs, grouped bar chart, log scale](findings/mspc_percentiles.png)

The shape that matters: every run's bars trace roughly the same staircase from `min` to `max`. If a config's bars sat visibly higher or lower than the rest across the *whole* staircase, that would be a real difference. None do — the differences here are noise, not signal.

![Total wall-clock time and jcmd-confirmed worker count per experiment run](findings/run_summary.png)

This is #13's finding in one picture: the worker-count panel shows 7, 7, 7, 7, 4 — not 7, 16, 7, 16, 4, because the flag only ever lowers the pool — and the total-time panel shows no relationship between that count and how long the run took, once tile size is held fixed.

![MSPC percentiles across the three GC configs, grouped bar chart, log scale](findings/gc_percentiles.png)

Same staircase shape again, all three collectors — but look closely and ZGC (green) sits a hair below the other two at every single bar past `p1`, not just one lucky phase. That consistent, whole-distribution shift is what #14 calls a real signal instead of noise.

![Total wall-clock time and MSPC median per GC config](findings/gc_summary.png)

The ~6% ZGC edge from #14, in one picture — real, but nowhere near large enough on its own to explain the 4-vs-7-worker tie from #13.

![MSPC percentiles across four GC configs at 4 workers, grouped bar chart, log scale](findings/gc_4w_percentiles.png)

Same four collectors as #15, but now ZGC (blue and orange, plain and throughput-tuned) sits a hair *above* G1-throughput and ParallelGC (aqua and yellow) at every bar past `min` — the exact opposite direction from #14's 7-worker chart. Nothing changed about the collectors between these two charts; only the worker count did.

![Total wall-clock time and MSPC median per GC config, 4 workers](findings/gc_4w_summary.png)

#15's headline in one picture: G1-throughput and ParallelGC land on top of each other (226s/34.8ms vs 225s/34.8ms), both a clear ~6% ahead of either ZGC variant — the reverse of the 7-worker ranking two charts up.

![MSPC percentiles across the JFR-guided reflection A/B, grouped bar chart, log scale](findings/jfr_ab_percentiles.png)

All three bars trace the same staircase within noise except one: the `MethodHandle` conversion (orange) sits visibly above the other two from `p25` through `max`, not just at one percentile. That's #16's regression made visible — a consistent shift, the same signature a real effect leaves (and the same one ZGC's genuine edge left in the chart three up).

![Total wall-clock time and MSPC median for the JFR-guided reflection A/B](findings/jfr_ab_summary.png)

Baseline and the shipped cached-`Method` fix land on identical bars (230s/35.4ms both); the `MethodHandle` attempt cost ~8%. #16's whole point in one picture: the "optimization" that looked obviously correct on paper is the one that lost.

![Total wall-clock time and MSPC median across the ParallelGC tuning attempt, in run order](findings/pgc_tuning_summary.png)

#17's whole point in one picture: three bars, roughly evenly spaced downward, and the tuned config is the middle one — not because it's between two genuinely different regimes, but because two *identical* default-config runs bracket it just as far apart as the "improvement" itself. If this were a real effect, the two baseline bars would sit together and the tuned bar would stand apart. They don't.

![Total wall-clock time and normalized throughput, WorldgenD vs. real Paper/Leaf servers](findings/drag_race_summary.png)

#18 in one picture, and the one chart in this whole document where the bars don't look close. Every prior chart here was hunting for a real signal inside a small noise band; this is the opposite problem — the WorldgenD bar isn't subtly different from the other three, it's in a different regime entirely, on both panels, and no amount of the tuning explored in #13-#17 gets it there.

![Total wall-clock time and normalized throughput at 9x the scale, WorldgenD vs. real Paper/Leaf servers](findings/sustained_summary.png)

#19 in one picture: WorldgenD's two bars (different scales, same throughput) sit right on top of each other on the right panel — the scale-invariance claim, visibly true, not just asserted. Leaf edges out Paper here in a way #18's smaller sample couldn't distinguish from noise; both still sit roughly double WorldgenD's bar, same as #18, now backed by a run 9x bigger.

Add a row to the relevant CSV and rerun the script to keep all of this current as new experiments land.

## Open questions / where you pick this up

- **#18's ~53% gap to Paper/Leaf is unexplained beyond "probably the generator patches."** Thread-count is ruled out (Paper used 2 dedicated workers to WorldgenD's 4 and still won). The leading theory — Paper/Leaf's fork-level chunk-generation patches doing genuinely less work per chunk than vanilla — has never been checked against an actual source or bytecode diff the way #6/#7's claims were. Also untested: a fourth drag-race run (plain Leaf flags, `/tick freeze` active, no FMA/profiler-disable flags) to isolate tick-freeze's own contribution from the crack-specific flags', since #18's single crack sample can't separate the two.
- **Nail down the exit-delay theory from #10.** Grab a `jcmd Thread.print` right before natural exit, check which live threads are missing the `daemon` flag, and consider reflectively calling `Util.shutdownExecutors()` at the end of `main()` for a fast, clean death without ever going near `stopServer()`/`halt()`.
- **We've only ever sampled height + biome** (`describe()` in `HeadlessWorldgen.kt`). Real block data is one more reflective hop away — `ChunkAccess.getBlockState(BlockPos)` — completely untried.
- **Overworld only.** Nether/End just need `LevelStem` lookups against `Level.NETHER` / `Level.END` instead of `overworld()`. Structurally trivial. Nobody's bothered yet.
- **Why does cutting the worker pool from 7 to 4 not cost anything under default G1 at `MOSAIC_TILE = 5`, but turn into a 9% win under tuned G1/ParallelGC and a small loss under ZGC?** #13 found the 7-vs-4 tie under default ergonomic G1. #15 crossed GC choice with the 4-worker count directly and found the tie isn't universal: G1-throughput and ParallelGC at 4 workers (226112/225922ms) beat their own 7-worker numbers by ~9% (#14's 248592ms G1-throughput row), while ZGC at 4 workers (241066-241169ms) is *slower* than ZGC at 7 workers (237127ms) — a full rank reversal from #14, where ZGC was the fastest collector tested. Leading candidate, untested: ZGC's concurrent GC threads have a fixed CPU cost that doesn't shrink just because there are fewer mutator threads to overlap with, so they start actively competing with workers once cores are freed up by dropping to 4; a STW collector, by contrast, gets *cheaper* pauses at 4 workers (fewer live threads to interrupt) with idle cores to burn through the pause fast. Still untested: default (untuned) G1 specifically at 4 workers — the one cell in this GC × worker-count grid nobody's run yet — plus a live `jcmd <pid> Thread.print` thread-*state* sample comparing ZGC's concurrent-thread utilization at 4 vs 7 workers, to confirm or kill the mechanism above directly.
- **The #9 warmup batch was itself a dumb solid block**, paying the exact wavefront-stall tax described in #6 (83.6s for 1681 chunks, ~20cps average, for a phase whose own speed nobody cared about). A mosaic-shaped warmup would probably get JIT-hot faster and cheaper. Untried purely out of laziness.
- **No decompiler was ever available here.** Every finding above came from `javap -p` (signatures) and `javap -c -p` (raw bytecode disassembly, read by hand, for constants like the radius-8 discovery in #7) — not recovered source. If a decompiler shows up later, it would be worth double-checking the manual bytecode archaeology in #6/#7 against real source, if only to confirm we didn't misread an `iconst` somewhere out of overconfidence.
- **Scale up to 25600 chunks (`MOSAIC_TILE = 10`) came back clean in #19** — same throughput as the 6400-chunk baseline, no sign of memory pressure on a fixed 16GB pretouched heap. Still untested: an order of magnitude beyond that. This process never calls a single save-or-unload path, so the in-memory `ChunkMap` only ever grows — at some size, on some heap, that stops being fine. Where exactly is still unknown.
