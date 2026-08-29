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

## 14. Charts, for posterity

Every number in #12 and #13, plotted. Raw data lives in `findings/mspc_results.csv`; the charts regenerate with:

```
python3 findings/plot_results.py
```

(needs `matplotlib` — on Debian/Ubuntu, `apt-get install python3-matplotlib`)

![MSPC percentiles across all five experiment runs, grouped bar chart, log scale](findings/mspc_percentiles.png)

The shape that matters: every run's bars trace roughly the same staircase from `min` to `max`. If a config's bars sat visibly higher or lower than the rest across the *whole* staircase, that would be a real difference. None do — the differences here are noise, not signal.

![Total wall-clock time and jcmd-confirmed worker count per experiment run](findings/run_summary.png)

This is #13's finding in one picture: the worker-count panel shows 7, 7, 7, 7, 4 — not 7, 16, 7, 16, 4, because the flag only ever lowers the pool — and the total-time panel shows no relationship between that count and how long the run took, once tile size is held fixed. Add a row to the CSV and rerun the script to keep this current as new experiments land.

## Open questions / where you pick this up

- **Nail down the exit-delay theory from #10.** Grab a `jcmd Thread.print` right before natural exit, check which live threads are missing the `daemon` flag, and consider reflectively calling `Util.shutdownExecutors()` at the end of `main()` for a fast, clean death without ever going near `stopServer()`/`halt()`.
- **We've only ever sampled height + biome** (`describe()` in `HeadlessWorldgen.kt`). Real block data is one more reflective hop away — `ChunkAccess.getBlockState(BlockPos)` — completely untried.
- **Overworld only.** Nether/End just need `LevelStem` lookups against `Level.NETHER` / `Level.END` instead of `overworld()`. Structurally trivial. Nobody's bothered yet.
- **Why does cutting the worker pool from 7 to 4 not cost anything at `MOSAIC_TILE = 5`?** #13 confirmed (live, via `jcmd`) that `-Dmax.bg.threads` really does clamp the pool up and down as designed, then found that 4 vs 7 workers against a 25-chunk-per-phase mosaic produced statistically identical throughput — which nothing in this document currently explains. Sample thread *states* (not just counts) mid-run with `jcmd <pid> Thread.print`, same technique as #6: if all 4 workers sit `RUNNABLE` the whole time, throughput is genuinely compute-bound at this chunk density and more threads were never going to help; if there's real idle time, something below the explicit per-phase chunk count is still throttling parallelism.
- **The #9 warmup batch was itself a dumb solid block**, paying the exact wavefront-stall tax described in #6 (83.6s for 1681 chunks, ~20cps average, for a phase whose own speed nobody cared about). A mosaic-shaped warmup would probably get JIT-hot faster and cheaper. Untried purely out of laziness.
- **No decompiler was ever available here.** Every finding above came from `javap -p` (signatures) and `javap -c -p` (raw bytecode disassembly, read by hand, for constants like the radius-8 discovery in #7) — not recovered source. If a decompiler shows up later, it would be worth double-checking the manual bytecode archaeology in #6/#7 against real source, if only to confirm we didn't misread an `iconst` somewhere out of overconfidence.
- **Nothing bigger than `MOSAIC_TILE = 3` (2304 chunks) has been attempted.** No idea how memory/GC behaves over tens of thousands of chunks in one process that never calls a single save-or-unload path. Could be fine. Could be a very educational `OutOfMemoryError`. Someone should find out.
