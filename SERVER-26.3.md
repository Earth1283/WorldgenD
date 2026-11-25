# Minecraft 26.3 compatibility and v5.9 support

The headless bootstrap and `orion5.9` scheduler fully support Minecraft 26.3 (`dataVersion=5023`)
while preserving 26.1.1 (`dataVersion=4788`) compatibility from the same build. The default server
symlink (`mojang-26-1-1-server.jar`) and scheduler defaults are unchanged.

## Reproduce

Keep the official jars outside the source tree at `../mojang/mojang-26-3-server.jar`
and `../mojang/mojang-26-1-1-server.jar`. Use Java 25 and a new output directory per run:

```bash
python3 findings/probe_headless_compat.py --scheduler orion5.9 \
  --output findings/compat263_spike/v59 --reload

python3 findings/probe_headless_compat.py --scheduler mosaic \
  --output findings/compat263_spike/mosaic --reload

python3 findings/probe_headless_compat.py --version 26.1.1 --scheduler orion5.9 \
  --output findings/compat263_spike/legacy59 --reload
```

These are correctness diagnostics, not performance comparisons. Defaults are seed 69,
`-Xms1g -Xmx4g -XX:+UseParallelGC`, two background workers, tile 1 (256 target chunks),
and eight chunks in flight for Orion. No pretouch is requested. Orion targets are shifted
100 chunks in both axes to avoid chunks generated during spawn setup, before deterministic
feature ordering is enabled. The probe enables deterministic FEATURES, fixed worldgen-light
reads, overlap/order verification, server-task draining, and poll-thread recognition.

Each probe uses its own server directory and a symlink to the supplied jar. It checks:

- all target chunks complete with zero failures;
- Orion reports zero overlap/order violations, failed feature kicks, stuck gates, and stalls;
- positional block and biome digests cover every Orion target coordinate;
- a completion marker is written after the blocking save returns;
- a separate JVM reads every saved target through that version's `RegionFileStorage`, checking
  coordinates, exact data version, `minecraft:full`, and block/biome section presence;
- with `--reload`, a copy of the world boots in the official server and exits cleanly after
  `stop`, without world-metadata fallback or load errors. That temporary server binds to
  loopback on an ephemeral port.

The headless JVM has non-daemon threads after `main` returns. The probe terminates it only
after the completion marker, rather than treating the generation result as proof the save
finished. `-Dworldgen.completionfile=<path>` is optional for other launchers too.

`results.csv` contains correctness outcomes and jar hashes. Raw generation timings remain
in the local result files; they are not added to the performance leaderboard.

## Results (2026-11-15 spike & 2026-11-25 v5.9 port)

| Jar / scheduler | Generated | Saved chunks checked | Official-server reload |
|---|---:|---:|---|
| 26.3 / mosaic | 256 / 256 | 256 / 256 | Passed |
| 26.3 / core orion5, tracing on | 256 / 256 | 256 / 256 | Passed |
| 26.3 / core orion5, repeat | 256 / 256 | 256 / 256 | Not repeated |
| 26.3 / ported orion5.9 | 256 / 256 | 256 / 256 | Passed |
| 26.1.1 / orion5.9 regression | 256 / 256 | 256 / 256 | Passed |

Both 26.3 Orion runs and the ported `orion5.9` run reported zero overlaps, ordering violations,
failed kicks, stuck gates, and stalls (`stagePatch failed=[]`). Their positional block and biome
digests match on all 256 targets (`0/256` diffs between 26.3 `orion5.9` and 26.3 core `orion5`).
The traced run contains 400 `terrain` step records. Saved data versions are 5023 (26.3)
and 4788 (26.1.1). The saved-world inspector also rejected an empty target region with
`Missing saved chunk`, and the existing Gradle suite passed all eight tests.

Rows and digest comparison counts are in `findings/headless_compat_263.csv` and
`findings/headless_compat_263_digests.csv`. Raw artifacts remain local under
`findings/compat263_spike/` (gitignored). These runs used the system OpenJDK 25.0.5.0.3.

## API map

| Area | 26.3 change | 26.3 handling |
|---|---|---|
| Authentication | `YggdrasilAuthenticationService` is gone; `Services.create` takes `MinecraftServicesDiscoveryService` | `ServerBootstrap` selects the service factory from the actual signature |
| Server construction | `DedicatedServer` adds `ManagementServer` and `NotificationManager` constructor arguments | Supply no management listener, construct the notification manager, and call `setServer`, as vanilla does |
| Generation stages | `TERRAIN` replaces the separate `NOISE`, `SURFACE`, and `CARVERS` statuses | Core `ChunkMap.applyStep` interception still works; trace status names now include `terrain` |
| Density evaluation | The old root-package `DensityFunction`/`DensityFunctions`, `NoiseInterpolator`, and `FlatCache` are absent | `OrionPatchAgent` and `HeadlessWorldgen` gate `DensityCellPatch`, `DensitySimdPatch`, and `StagePatch.biomeFlat` on legacy class presence |
| Density replacement | `densityfunction.DensityFunctionCompiler`, `DensitySampler`, `DensityVolume`, `DensityBufferPool`, and `SLICE_UNIFORM_AXES` | Superseded legacy density/cell/climate-flat patches; thread safety audited via decompilation (`compileLock`, `ConcurrentHashMap`, `ReentrantLock`-guarded buffer pools) |
| Noise kernel | `synth.ImprovedNoise` is absent; replaced by volume-batched float `synth.PerlinNoise.addToVolume` | `ImprovedNoisePatch` gated on class presence; superseded by 26.3's native kernel |
| Surface rules | `SurfaceSystem`, `SurfaceRules$Context`, and `SurfaceRules$SequenceRule` are absent; replaced by `material.MaterialSystem`, `MaterialRuleContext`, and `material.rule.SequenceRule` | `StagePatch` `surfaceColumn`/`surfaceSet` ported to `MaterialSystem`/`MaterialSystem$1` (with `markPosForPostProcessing` and `minY`/`maxY` check); underground Y-range shortcut omitted because 26.3 `overworld.json` runs ore veins and sulfur caves underground |
| Biomes & fluids | `BiomeManager` splits `getBiome(BlockPos)` into `getBiome(int, int, int)`; `FluidState.isEmpty` unchanged | `StagePatch` `biomeUniform` hooks the 3-int overload on 26.3; `fluidEmpty` unchanged |
| Aquifer allocations | `Aquifer$NoiseBasedAquifer.computeSubstance` still allocates `new MutableDouble(NaN)` | `AllocationPatch` scopes `requireInstalled` to present targets (`Aquifer$NoiseBasedAquifer` active on 26.3) |
| Region persistence & scan | `RegionFileStorage.getRegionFile(ChunkPos, boolean)` adds `create` flag; `C1GC` + LZ4 (`id=4`) compatible | `OldChunkScan` passes `create=false` and treats `null` as empty; C1GC armed (`pressure=0` and `0.3` at tile 16) validated with `SavedWorldProbe` and official 26.3 reload |

Class presence alone is not method compatibility. The inventory is in
`findings/server_263_class_presence.csv`; it records both supplied jar versions.

## Port validation & throughput (2026-11-25, #87)

- **Correctness**: 26.3 `orion5.9` matches 26.3 core `orion5` on **256/256** block and biome digests (0 mismatches), passes `SavedWorldProbe` (`256/256` at tile 1 and `1,024/1,024` with C1GC armed at `pressure=0`), reloads cleanly in the official 26.3 server, and preserves 26.1.1 `orion5.9` (`256/256` + reload).
- **Throughput** (Corretto 25.0.4.1, `-Xms16g -Xmx16g -XX:+AlwaysPreTouch -XX:+UseParallelGC`, 7 workers, seed 69):
  - Tile 5 (6,400 chunks, 3 interleaved rounds): `orion5.9` **31.12 s** mean (`4.862 eMSPC`, **205.7 CPS**, p50 `123 ms`) vs 26.3 core `orion5` **34.60 s** (`5.407 eMSPC`, **185.0 CPS**, p50 `269 ms`) — **-10.1% wall time (+11.2% CPS)**.
  - Tile 16 (65,536 chunks, n=1): `orion5.9` **276.74 s** (`4.223 eMSPC`, **236.81 CPS**, p50 `120.47 ms`, p99 `291.36 ms`, C1GC armed at chunk `29,184`, `62,464/62,464` persisted, 5 full GCs totaling `1.77 s`).

## Next work

26.3 `orion5.9` has been validated on the Overworld (seed 69) up to 65,536 chunks (`tile=16`).
Next: run an interleaved 26.1.1 vs 26.3 `orion5.9` comparison, exercise additional seeds and
non-Overworld dimensions alongside a 262,144-chunk run, and profile 26.3's native
`TERRAIN`/`DensityVolume`/`MaterialSystem` path for 26.3-specific optimizations.
