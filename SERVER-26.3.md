# Minecraft 26.3 compatibility spike

The headless bootstrap works on the locally supplied 26.3 server jar with `mosaic` and
core `orion5`. The full `orion5.9` patch set remains incompatible. The default server
symlink and scheduler defaults are unchanged.

## Reproduce

Keep the official jars outside the source tree at `../mojang/mojang-26-3-server.jar`
and `../mojang/mojang-26-1-1-server.jar`. Use Java 25 and a new output directory per run:

```bash
python3 findings/probe_headless_compat.py \
  --output findings/compat263_spike/core --reload --trace

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

## Results (2026-11-15)

| Jar / scheduler | Generated | Saved chunks checked | Official-server reload |
|---|---:|---:|---|
| 26.3 / mosaic | 256 / 256 | 256 / 256 | Passed |
| 26.3 / core orion5, tracing on | 256 / 256 | 256 / 256 | Passed |
| 26.3 / core orion5, repeat | 256 / 256 | 256 / 256 | Not repeated |
| 26.1.1 / orion5.9 regression | 256 / 256 | 256 / 256 | Passed |

Both 26.3 Orion runs reported zero overlaps, ordering violations, failed kicks, stuck
gates, and stalls. Their positional block and biome digests match on all 256 targets.
The traced run contains 400 `terrain` step records. Saved data versions are 5023 (26.3)
and 4788 (26.1.1). The saved-world inspector also rejected an empty target region with
`Missing saved chunk`, and the existing Gradle suite passed all eight tests.

Rows and digest comparison counts are in `findings/headless_compat_263.csv` and
`findings/headless_compat_263_digests.csv`. Raw artifacts remain local under
`findings/compat263_spike/` (gitignored). These runs used the system OpenJDK 25.0.5.0.3.

## API map

| Area | 26.3 change | Spike handling |
|---|---|---|
| Authentication | `YggdrasilAuthenticationService` is gone; `Services.create` takes `MinecraftServicesDiscoveryService` | `ServerBootstrap` selects the service factory from the actual signature |
| Server construction | `DedicatedServer` adds `ManagementServer` and `NotificationManager` constructor arguments | Supply no management listener, construct the notification manager, and call `setServer`, as vanilla does |
| Generation stages | `TERRAIN` replaces the separate `NOISE`, `SURFACE`, and `CARVERS` statuses | Core `ChunkMap.applyStep` interception still works; trace status names now include `terrain` |
| Density evaluation | The old root-package `DensityFunction`/`DensityFunctions`, `NoiseInterpolator`, and `FlatCache` are absent | Keep `DensityCellPatch`, `DensitySimdPatch`, and the DFC prototype disabled |
| Density replacement | `densityfunction.DensityFunctionCompiler`, `DensitySampler`, `DensityVolume`, and `DensityBufferPool` | Use vanilla evaluation; audit it before choosing replacement optimizations |
| Noise kernel | `synth.ImprovedNoise` is absent | Keep `ImprovedNoisePatch` disabled |
| Surface rules | `SurfaceSystem`, `SurfaceRules$Context`, and `SurfaceRules$SequenceRule` are absent; `material.MaterialSystem` is present | Keep the old surface and allocation patches disabled |
| Region persistence | Vanilla's blocking world save and `RegionFileStorage.read` work in the smoke checks | Validate saved data with vanilla APIs; C1GC remains unvalidated |

Class presence alone is not method compatibility. The inventory is in
`findings/server_263_class_presence.csv`; it records both supplied jar versions.

The new sampler API exposes `sampleVolume(...)` and float-valued `sampleValue(...)`.
The compiler caches samplers in a `ConcurrentHashMap` and protects compilation with a
`ReentrantLock`. `RandomState` manages reusable density-buffer pools. These observations
come from the local jar's `javap` output. They do not establish that an old optimization
is redundant, that every sampler is thread-safe, or that generation is faster.

## Limits and next work

This is an Overworld, one-seed, small-world check. It does not establish C1GC compatibility,
bounded memory on large runs, complete POI/entity correctness, all-dimension support, or
safe parallel generation across all structures. An official-server boot checks world
startup; the separate saved-chunk probe checks the target region data. Neither is a
gameplay test. Output equality across Minecraft versions is not expected or claimed.

Next: exercise more seeds, structures and other dimensions, audit
`TERRAIN` shared state and C1GC against the new jar, then profile the native volume/sampler
path. Carry over each v5.9 patch only after its assumptions and output have been checked.
