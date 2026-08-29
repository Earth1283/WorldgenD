# WorldgenD: How To Gaslight A Minecraft Server Into Doing Your Bidding

So you've got `mojang-26-1-1-server.jar` sitting there, minding its own business, thinking its whole personality is "accept TCP connections on 25565 and run a tick loop 20 times a second forever." Adorable. We're not doing that. We're going to walk up to `DedicatedServer.class`, skip past `initServer()` like it owes us money, and manually hand-crank the one part of it we actually care about: **making real, honest-to-god chunks**. No players. No network. No tick loop. Just you, a `URLClassLoader`, and several hundred method handles held together by `setAccessible(true)`.

This is not a joke tool. It generates *actual* vanilla terrain using Mojang's *actual* generator code, loaded straight out of Mojang's *actual* jar, at runtime, without us ever redistributing a single byte of their bytecode. It is, and I cannot stress this enough, deeply funny that this works.

## The Vibe

1. You drop the real server jar in a folder.
2. WorldgenD finds it, unzips the bundler payload (same thing the jar does to itself when you run it normally), and builds a `URLClassLoader` pointed at it.
3. We reflectively replay the *first half* of `net.minecraft.server.Main.main()` — the boring, respectable half that sets up registries, loads the datapack, builds a `WorldStem`.
4. Then, instead of letting `DedicatedServer.initServer()` open a socket and start yelling "Done! For help, type help", we construct the `DedicatedServer` object ourselves, wire up JUST ENOUGH of its internals by hand (looking at you, `PlayerList`), and call the protected `loadLevel()` method directly like we own the place.
5. `loadLevel()` builds a completely real `ServerLevel` with a completely real `ServerChunkCache`. At no point does anything resembling a game loop exist. There is no `runServer()`. There is no `tickServer()`. There is just us, `managedBlock()`, and a `CompletableFuture`.
6. We then drive `ServerChunkCache.getChunkFuture(x, z, ChunkStatus.FULL, true)` in a grid around the origin, which pushes every single chunk through the *entire real generation pipeline* — biomes, noise, surface, carvers, features, structures, light, spawn — because that pipeline doesn't know or care that nobody's watching.

We are, functionally, a fake player standing behind the server going "psst, hey, make me some chunks" and the server, having no idea it was never actually started, just... does it.

## Setup

You need exactly one thing: the official server jar. Not a mod. Not a decompiled fork. The actual jar Mojang ships. Legally obtained, by you, for you.

```
WorldgenD/
  servers/
    mojang-26-1-1-server.jar   <- put it here, or symlink it here
```

That's it. That's the whole install process. We do NOT bundle it into the repo, we do NOT compile against it, we do NOT check it into git — go look at `.gitignore`, `/servers/*.jar` is blacklisted on principle. This is load-at-runtime-or-don't-load-at-all. If there's more than one jar in there we just pick one and yell about it in the logs, because we are not your keeper.

## Running It

```
./gradlew run
```

Watch the logs. You will see, in order:

- `Hammering /path/to/server.jar (39 bundled libraries)` — we found it, we unpacked its guts into `servers/.cache/`, we're loading it.
- `Loaded 1515 recipes` / `Loaded 1617 advancements` — yes, this happens. No, we don't need it. It's a side effect of reusing Mojang's own datapack loader instead of reimplementing it ourselves like chumps. We let their code do their job.
- `Constructed DedicatedServer without run()/initServer() — calling loadLevel() directly.` — this is the whole heist, right here, in one log line. A concrete `DedicatedServer` object exists in memory. It has never been started. It never will be.
- `Selecting global world spawn...` / `Preparing spawn area: 100%` — this is the server, unprompted, generating its own spawn chunks as an honest side effect of `loadLevel()`, because that's just what that method does and we didn't stop it.
- A wall of `[x,z] FULL height=NN biome=minecraft:whatever` and `phase N/255: 9 chunks in NNms` lines — this is us tiling a solid 48x48 block, 2304 chunks, in 256 provably-independent batches. See "The Mosaic" below for why it's shaped like that instead of one big grid.
- `Done: 2304 chunks generated, 0 failed in NNms across 256 phases. No network, no RCON, no tick loop ever ran.` — the mission statement, confirmed empirically.

Every single one of those height/biome pairs came out of the *real* noise router, the *real* climate sampler, the *real* biome source. If it says `frozen_ocean` at height 62, that's because the actual overworld noise settings, for the actual random seed that got picked, actually produced ocean there. We didn't fake a single number.

## Why This Doesn't Involve Copying Mojang's Code

Worth saying out loud since it's the whole design constraint: **WorldgenD's own compiled classes contain zero references to any `net.minecraft.*` or `com.mojang.*` symbol.** Go check `build.gradle.kts` — there is no Mojang dependency, static or otherwise. Everything you see in `HeadlessWorldgen.kt` is string literals (`"net.minecraft.server.MinecraftServer"`) fed into `Class.forName`, plus `Method`/`Constructor` objects fetched off of classes that only exist because *you* put a real, licensed jar in `servers/` at runtime. Delete that jar and WorldgenD is an inert pile of reflection glue that does nothing. We're not distributing their game. We're distributing a very elaborate TV remote, and you still need to own the TV.

## The Small Print Nobody Reads But You Should

- **The seed is pinned to `69`**, via `level-seed=69` written into `servers/.run/server.properties` before `DedicatedServerSettings` ever reads it. `.run/` gets `deleteRecursively()`'d at the top of every `main()` on purpose — `createNewWorldData()` always builds fresh world *data* regardless of what's on disk, but a leftover region file from a previous (different-seed) run would still get loaded back over it, silently serving you stale terrain under the new seed's name. Change the string in `HeadlessWorldgen.kt` if 69 isn't sacred enough for you.
- **`servers/.run/` and `servers/.cache/`** are scratch space — extracted libraries and a throwaway "world" save folder. Nuke them any time; they get rebuilt (and `.run/` rebuilds itself unconditionally now, see above).
- **`managedBlock()` is load-bearing.** If you ever refactor this and swap it for a plain `.join()` on the chunk future, you *will* deadlock, because some generation steps schedule continuations back onto the server's own task queue — and nothing is pumping that queue except `managedBlock()`, because there is no tick loop to do it for you. This is, again, the entire point.
- **We construct a `DedicatedPlayerList` by hand** because `ServerLevel`'s constructor reaches into `getPlayerList().getViewDistance()`, and normally that list gets built inside `initServer()`, which we are pointedly not calling. This is the one place we had to manually patch a hole that "starting the server properly" would've patched for us. Everywhere else, we just let Mojang's own code do Mojang's own job.

## Why It Only Used 200% CPU On An 8-Core Box (And Whose Fault That Was)

First hammer run: a solid 25x25 block of chunks, submitted all at once, one `managedBlock()` gate, `CompletableFuture.allOf`. Watched `top`. Eight cores available. Server used about two of them. Rude.

Five suspects got lined up:

- **A: the executor's undersized.** Nope. `Util.getMaxThreads()` — real method, real jar, `net/minecraft/util/Util.class` — clamps `availableProcessors() - 1` between 1 and `max.bg.threads` (default 255). On 8 cores that's **exactly 7 worker threads**, confirmed by counting `Worker-Main-1` through `Worker-Main-7` in an actual `jcmd <pid> Thread.print`. The pool was sized correctly the whole time. (It's tunable too — `-Dmax.bg.threads=N` — if you ever want fewer.)
- **C: lock contention.** Nope. Every idle worker thread's stack, sampled live mid-run, showed `WAITING (parking)` inside `ForkJoinPool.awaitWork()` — genuinely nothing queued for it. Not one was `BLOCKED` on a monitor. Nothing to unlock; nothing was locked.
- **D: some stages pinned to the main thread.** Nope. `Beardifier.getBuryContribution`, `SurfaceRules$TestRule.tryApply`, `DensityFunctions$PureTransformer.compute` — all caught RUNNABLE *on the worker pool*, not on the thread calling `managedBlock()`.
- **B + E: the dependency graph.** This one. Three consecutive `top -H` snapshots during the same run: 5/7 busy, then 1/7, then 1/7. `ChunkStatus` stages require a neighbor radius already past an earlier status before a chunk can advance — so a solid block only ever has a thin *ring* of chunks simultaneously eligible for whatever stage is currently in play. Fill it in one blob and the pool alternates between "everyone's fed" and "five guys parked, one guy grinding noise math for the whole street."

Fix, take one: stop submitting one blob. Scattered 9 islands, `CLUSTER_RADIUS = 4` each, `CLUSTER_SPACING = 69` chunks apart — far enough that no island's dependency ring could possibly touch another's. Same live-snapshot technique afterward: 5/7, 6/7, 7/7, 5/7. Troughs gone. Nine independent wavefronts beat one big one.

## The Mosaic

Islands prove the point but leave gaps — great for a demo, useless if you actually want a contiguous map. So: how far apart does "independent" really need to be?

Turns out the jar tells you, if you ask its bytecode nicely. `ChunkPyramid.GENERATION_PYRAMID`'s static initializer is a wall of `Builder.step(STATUS, b -> b.addRequirement(OTHER_STATUS, radius))` calls. Disassemble it (`javap -c -p`) and every single `addRequirement` pushes `iconst_1` — except one: `STRUCTURE_STARTS` gets `bipush 8`. That's it. That's the whole dependency graph's ceiling. **8 chunks**, confirmed straight out of the constant pool, not folklore.

So: pick a modulus `N` bigger than 8 — we used `MOSAIC_N = 16`, a comfortable 2x margin — and tile chunk space by residue:

```
phase(cx, cz) = (cx mod N) + N * (cz mod N)
```

Any two chunks sharing a phase differ by a multiple of `N` in both axes, so the closest they can possibly be is `N` apart on one axis with zero offset on the other. Since `N = 16 > 8`, **every chunk in a phase is mathematically guaranteed independent of every other chunk in that phase** — not "probably far enough," guaranteed, by the same number the game itself uses to decide when chunks need to wait on each other. `MOSAIC_TILE = 3` multiples per axis gives a solid, gapless 48x48 block (2304 chunks), visited as 256 phases (`N * N`), each phase submitted, `managedBlock()`'d, and drained before the next one starts — a real barrier between waves, not just a reordered loop.

Ran it. 2304/2304, zero failures, phase durations logged the whole way:

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

~136x from cold to warm, and — this is the part that matters — it never relapses. No mid-run spike back to seconds-per-phase the way the solid-block run had troughs. That flat, monotonically-dropping tail is the actual signature of "no scheduling stalls left, only JIT warmup." Compare that to the *average* across the whole run — 2304 chunks / 103.4s ≈ 22.3 chunks/sec — which is a real number but a misleading one: it's dragged down hard by one 9.4-second cold phase, and undersells what steady-state throughput on this box actually is (90-130/sec) once C2 has compiled `Beardifier`/`SurfaceRules`/`DensityFunctions`.

`MOSAIC_N`, `MOSAIC_TILE`, and the phase loop all live in `HeadlessWorldgen.kt`. Want a bigger map? Bump `MOSAIC_TILE`. Want tighter safety margin? You now know the real number is 8 — go argue with the bytecode if you think that's changed in a later version.

## ICBM Or Just Cheap Mosaics? A Controlled Experiment

The 90-130 cps tail of the mosaic run raises an obvious question: is that number about the *code* (HotSpot finally JIT-compiling `Beardifier`/`SurfaceRules`/`DensityFunctions` into native machine code, a one-time global tax) or about the *location* (some kind of per-region cache — structure placement, noise parameters, whatever — making chunks near already-visited ground cheaper)? Global warmup says speed shouldn't care where you point it once the JVM is hot. Locality caching says it should — virgin territory should cost more than a re-tread.

Ran a controlled version to find out:

1. **Warm the JVM first and throw the number away.** 1681 chunks in a solid block near (300,300) — a location nothing else in the experiment ever touches — purely to get past JIT compilation thresholds. 83.6s, who cares, not the measurement.
2. **Then time four separate 729-chunk batches** (9 islands of `CLUSTER_RADIUS = 4` each, same shape as the original parallelism proof) at anchors chosen specifically to separate "near" from "far": `(69,69)`, `(-69,69)`, `(6969,6969)`, `(-6969,-6969)`. Every anchor's footprint kept clear of every other's and of the warmup zone, so nothing gets to reuse anyone else's work.

Results, JIT already fully warm for all four:

| Anchor | Chunks | Time | chunks/sec |
|---|---|---|---|
| near NE (69,69) | 729 | 43612ms | 16.7 |
| near NW (-69,69) | 729 | 50497ms | 14.4 |
| far NE (6969,6969) | 729 | 46905ms | 15.5 |
| far SW (-6969,-6969) | 729 | 44945ms | 16.2 |

**HotSpot ICBM confirmed.** All four sit in a tight 14.4-16.7 band with zero correlation to distance — the *slowest* anchor is a "near" one, and the two 6969-away anchors (noise/structure territory the process had never touched) land squarely in the middle of the pack, not at the bottom. If per-region caching were doing real work, far should lose to near every time; it doesn't lose at all. Once the JIT is warm, teleporting 6969 chunks away costs nothing extra — the speedup we saw in the mosaic run was 100% HotSpot compiling hot code, 0% about *where* that code ran.

Bonus finding hiding in the same numbers: 14-17 cps is 5-8x *slower* than the mosaic's warm tail (90-130 cps), despite both runs having an equally hot JIT. That's not a contradiction, it's a batch-shape tax: `CLUSTER_RADIUS = 4` makes each island a solid 9x9 block, and 9 chunks wide is barely bigger than the confirmed radius-8 dependency itself — so even though the 9 islands don't depend on *each other*, each individual island still can't get more than a thin sliver of its own 81 chunks simultaneously eligible. Mosaic phases hit triple digits because every chunk in a phase is guaranteed eligible by construction; a 9-wide island only guarantees that between islands, never within one.

Also: don't redirect this experiment's stdout to a file and then panic when the log looks frozen for four minutes. `System.out` is fully buffered (not line-buffered) once it's pointed at a file instead of a terminal, so a big run's later `println`s can sit in a JVM-internal buffer that never touches disk until the process actually exits — the log isn't stalled, your patience is just outrunning `PrintStream`'s flush policy.

## Extending It

Want a bigger fill? Bump `MOSAIC_TILE`. Want a specific dimension instead of the overworld? Swap `overworld()` for `getLevel(ResourceKey)` and look up `Level.NETHER` / `Level.END`. Want to actually inspect block data instead of just height + biome? `chunk` in the generation loop is a real `ChunkAccess` — reflectively call `getBlockState(BlockPos)` on it same as everything else here. The whole file is just "look up the method, call the method." You already know how to do that now.

Go make some land.
