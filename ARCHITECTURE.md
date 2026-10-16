# Architecture

How a WorldgenD run is put together, and what has bitten people before. For the narrative see
[`TUTORIAL.md`](TUTORIAL.md); for evidence see the findings files (cited as `#N`).

## Run flow

1. `HeadlessWorldgen.kt` loads the server jar through `ServerRuntime.kt` (a `URLClassLoader`),
   replays `Bootstrap.bootStrap()`, and builds a real `DedicatedServer` and `ServerLevel` by
   reflection (`Reflect.kt`). No tick loop ever runs.
2. `-Dscheduler` picks a fill strategy. `mosaic` is the original 256-phase tiling; `orion*` are
   the frontier schedulers.
3. The fill submits `getChunkFuture(..., true)` for every chunk in a `-Dmosaic.tile` square and
   waits with `managedBlock()`. A plain `join()` deadlocks.
4. The run ends by writing the result file (`orion_result.txt`: scheduler, ok/failed counts, total
   time, MSPC percentiles, and one line per active patch or C1GC). Read results from this file.

## Scheduler lineage

| Scheduler | Role |
|---|---|
| `mosaic` | Fixed 16-modulus phases; independence by construction (#7-#8) |
| `orion` .. `orion3` (`Orion*.kt`) | Dispatch a chunk when `isSafe()` says no in-flight chunk is within the dependency radius; v2.1+ narrow candidates with `PendingSpatialIndex`/`HeldCenterIndex`. Baselines only |
| `orion4` | v3 plus the structure-generator thread-safety patch |
| `orion5` .. `orion5.7` (`OrionV5.kt`, `OrionParallelSteps.kt`) | Admission window of `-Dorion.maxinflight` chunks; each generation step runs on the background pool instead of vanilla's single `worldgen` executor (#62) |

Within v5.x each version is the previous one plus patches, so a result file's patch lines show
what actually loaded.

## The agent

`OrionPatchAgent.kt` is the `Premain-Class` of the agent jar (`./gradlew agentJar`, output
`build/libs/orion-agent.jar`). It rewrites vanilla classes with javassist as they load, gated by
flags. v5 and later need all three of:

- `-Dorion.patchReentrancy=true`
- `-Dorion.patchStructureGenState=true`
- `-Dorion.patchParallelSteps=true`

The compute patches (`DensitySimdPatch`, `ImprovedNoisePatch`, `AllocationPatch`,
`DensityCellPatch`, `RegionChunkMemo`) are enabled automatically by the scheduler name; the
`-Dorion.patch*` properties force them on for other schedulers. `HeadlessWorldgen` calls each
patch's `requireInstalled`, so a missing agent fails fast instead of silently running vanilla.

## C1GC

From v5.4, finished chunks are reclaimed so a 65,536-chunk run fits in memory. Per-chunk
lifecycle (`c1gc/ChunkLifecycle.kt`): `ACTIVE -> QUARANTINED -> COLLECTIBLE -> DETACHED ->
PERSISTED`, and illegal transitions throw.

- QUARANTINED: outside `RETAIN_RADIUS` (12) of the frontier, not yet proven safe.
- COLLECTIBLE: tickets, POI sections and distance-manager entries cleared. Ticket removals are
  batched (`BATCH_SIZE = 64`) so the distance graph settles once per batch (#69).
- DETACHED: heavy state copied out, the chunk is garbage-collectable.
- PERSISTED: written to vanilla `.mca` files under `servers/.run/headless/dimensions/`.

Reclamation arms only when old-gen usage passes `-Dorion.c1gc.pressure` (0.5 on v5.5, 0.3 on
v5.6+, 0 means always on). Other knobs: `orion.c1gc.compression` (`lz4` default),
`orion.c1gc.ringCapacity` (512), `orion.c1gc.maxIoWorkers`. Wipe the dimensions directory between
runs. Full story in `src/main/kotlin/io/github/eath1283/worldgend/c1gc/README.md` and #69-#71.

## Correctness checks

Timing runs leave verification off. For correctness use a small tile with
`-Dorion.deterministicFeatures=region`, `-Dorion.patchWorldgenLight=true`,
`-Dorion.parallelSteps.verify=true`, `-Dorion.simd.verify=true` and
`-Ddescribe.histogramfile=<path>` (SHA-256 over every block position, #66). "Bit-exact" claims in
the docs mean equal digests in this mode.

## Gotchas

- **Console output vanishes after `Bootstrap.bootStrap()`** (#56; root cause unisolated). Do not
  rely on stdout or stderr after bootstrap. Use the result file or `-Dworldgen.progressfile`.
- **One `pollTask` caller.** Only the poll thread (`orion5-poll`) may call `pollTask` (#34).
  Main-thread-only vanilla work (ticket removal, `runDistanceManagerUpdates`, `processUnloads`)
  goes through the `onPoll` hook. `onComplete` fires on whichever thread finished the chunk;
  doing that work there races the poll thread and corrupts fastutil sets
  (`AIOOBE` in `LongLinkedOpenHashSet.fixPointers`) (#70).
- **Never gate persistence on `ChunkAccess.tryMarkSaved()` or `isUnsaved()`.** With no tick
  loop, the flag is already false for nearly every chunk (63 of 64 in a smoke test), and false
  does not mean the data reached disk. Treat every chunk as needing persistence. Revisit this
  entirely if the code ever runs in a ticking server.
- **ParallelGC `collectionUsage` lags.** It refreshes for Old Gen only after a full GC, which
  happens when old gen is already full. A pressure gate built on it armed at 10.6 GB and cost 698 s
  of full GCs. Use current `usage.used` (#70).
- **Validate memory triggers at tile 16.** Both traps above passed small smoke tests and showed
  up only at 65,536 chunks or with `pressure=0` repeated.
- **Noise floor is ~9%.** Run-to-run variance on the reference box (#16/#17). Pair and rotate
  runs, and report medians. Several shipped "wins" (v5.1, v5.2, v5.3) are inside it.
- **The seed defaults to 69** (`-Dworldgen.seed` overrides). Changing it changes every digest.
- **The light engine is not a lever.** v5.7's top allocator is `swapSectionMap`, but removing
  its copy did not speed anything up (#76, #77).

## Adding a scheduler or patch

Add the name to `HeadlessWorldgen.kt`'s scheduler checks (patch install, `maxinflight` default,
C1GC pressure default, result-file lines), to `OrionPatchAgent.kt`'s patch selection, to
`../harness/src/config.rs` (`SCHEDULERS` and the per-scheduler flag lists), and to the `-Dscheduler`
list in `README.md`. Then add a finding and a row to `findings/leaderboard_entries.csv`.
