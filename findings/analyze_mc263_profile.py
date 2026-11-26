#!/usr/bin/env python3
"""Analyze Phase 1 A/B, Phase 2 OrionTrace, and Phase 2 JFR data for 26.3 vs 26.1.1 (Finding #88)."""
import collections
import csv
import json
import re
import statistics
import subprocess
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FINDINGS = ROOT / "findings"
RUNS = FINDINGS / "profile_263_runs"

# Ordered from most specific inner-operation markers to outer chunk-stage markers.
SUBSYSTEM_RULES = (
    ("NoiseChunk init", (
        "NoiseChunk.<init>", "createNoiseChunk", "NoiseChunk.forChunk",
        "DensityBufferPool", "DensityFunctionCompiler", "DensitySamplerSet",
    )),
    ("Aquifer", ("Aquifer", "FluidStatus")),
    ("Surface / Material", (
        "SurfaceSystem", "SurfaceRules", "buildSurface",
        "MaterialSystem", "MaterialRule", "MaterialRuleContext", "material.rule.",
    )),
    ("Carvers", ("carver.", "applyCarvers", "CarvingMask", "WorldCarver")),
    ("Biomes", (
        "createBiomes", "fillBiomesFromNoise", "Climate$", "BiomeManager",
        "ResettableBiomeSupplier", "getNoiseBiome", "NoiseBiomeSource",
    )),
    ("Features", (
        "feature.", "PlacedFeature", "generateFeatures", "applyBiomeDecoration",
        "ConfiguredFeature", "WorldGenLevel", "Vegetation", "FeaturePlacer",
        "blockpredicates.", "levelgen.placement.",
    )),
    ("Structures", (
        "createStructures", "generateStructureStarts", "createReferences",
        "StructureStart", "StructurePiece", "JigsawPlacement", "StructureTemplate",
        "StructureCheck", "StructureManager", "Beardifier.forStructuresInChunk",
    )),
    ("Light", ("lighting.", "initializeLight", "LightEngine", "LightEventListener", "DataLayerStorageMap")),
    ("Density / Noise eval", (
        "doFill", "fillFromNoise", "NoiseChunk", "DensityFunctions", "densityfunction.",
        "BlendedNoise", "ImprovedNoise", "NormalNoise", "PerlinNoise", "GradientNoise",
        "NoiseStack", "Beardifier", "SimplexNoise", "CubicSpline", "DensityBuffer",
    )),
    ("Terrain container / Heightmap", (
        "PalettedContainer", "LevelChunkSection", "ProtoChunk.setBlockState",
        "ProtoChunk.markPosForPostProcessing", "Heightmap", "WorldGenRegion",
        "BitStorage", "SimpleBitStorage", "LinearPalette", "HashMapPalette",
    )),
    ("C1GC / Chunk IO", ("c1gc.", "SerializableChunkData", "RegionFile", "IOWorker", "ChunkSerializer")),
    ("Spawn / Entities", ("spawnOriginalMobs", "NaturalSpawner")),
    ("Scheduler / FJP", (
        "OrionParallelSteps", "OrionV5", "ForkJoinPool", "ForkJoinTask",
        "ChunkMap", "ChunkStatusTasks", "ChunkGenerationTask", "GenerationChunkHolder",
    )),
)


def jfr_events(jfr_path, name):
    out = subprocess.check_output(["jfr", "print", "--json", "--events", f"jdk.{name}", str(jfr_path)])
    return json.loads(out)["recording"]["events"]


def ts(value):
    return datetime.fromisoformat(value).timestamp()


def seconds(value):
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", value or "")
    if not match:
        return 0.0
    h, m, s = match.groups()
    return 3600 * int(h or 0) + 60 * int(m or 0) + float(s or 0)


def frame_name(frame):
    method = frame["method"]
    return method["type"]["name"].replace("/", ".") + "." + method["name"]


def type_name(obj):
    return obj["name"].replace("/", ".") if obj else "?"


def thread_group(thread):
    name = (thread or {}).get("javaName") or (thread or {}).get("osName") or "?"
    if name.startswith("Worker-Main"):
        return "worker"
    if name.startswith("IO-Worker") or name.startswith("c1gc"):
        return "io"
    return name


def classify_stack(names):
    # Walk from leaf (index 0) upward so Aquifer/NoiseChunk init inside doFill gets its own bucket.
    for name in names:
        for bucket, markers in SUBSYSTEM_RULES:
            if any(m in name for m in markers):
                return bucket
    return "other"


def load_csv(path):
    with path.open() as f:
        return list(csv.DictReader(f))


def write_csv(path, fieldnames, rows):
    with path.open("w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        w.writerows(rows)


def analyze_ab():
    rows = load_csv(RUNS / "ab_runs.csv")
    write_csv(FINDINGS / "profile88_ab_runs.csv", list(rows[0].keys()), rows)
    print("=== PHASE 1: INTERLEAVED A/B RESULTS ===")
    for tile in ("5", "16"):
        sub = [r for r in rows if r["tile"] == tile]
        for ver in ("26.3", "26.1.1"):
            vr = [r for r in sub if r["version"] == ver]
            if not vr:
                continue
            ms = [int(r["total_ms"]) for r in vr]
            cps = [float(r["cps"]) for r in vr]
            wcpu = [float(r["worker_cpu_s"]) for r in vr]
            fcpu = [float(r["fill_cpu_s"]) for r in vr]
            p50 = [float(r["mspc_p50"]) for r in vr]
            p99 = [float(r["mspc_p99"]) for r in vr]
            ygc = [int(r["young_gcs"]) for r in vr]
            yms = [float(r["young_pause_ms"]) for r in vr]
            fms = [float(r["full_pause_ms"]) for r in vr]
            print(
                f"  tile={tile} ver={ver} n={len(vr)}: wall={statistics.mean(ms)/1000:.2f}s "
                f"cps={statistics.mean(cps):.2f} worker_cpu={statistics.mean(wcpu):.2f}s "
                f"fill_cpu={statistics.mean(fcpu):.2f}s p50={statistics.mean(p50):.1f}ms "
                f"p99={statistics.mean(p99):.1f}ms yGC={statistics.mean(ygc):.1f}({statistics.mean(yms):.0f}ms) "
                f"fGC_ms={statistics.mean(fms):.0f}ms"
            )


def analyze_traces():
    trace_rows = []
    print("\n=== PHASE 2A: ORIONTRACE STAGE & WAIT BREAKDOWN (TILE 5) ===")
    for label, ver in (("trace_t5_mc263", "26.3"), ("trace_t5_mc2611", "26.1.1")):
        base = RUNS / label / "trace"
        steps = load_csv(Path(f"{base}.steps.csv"))
        samples = load_csv(Path(f"{base}.samples.csv"))[1:]
        end = max(int(r["t_ms"]) for r in samples)
        steady = [r for r in samples if 0.1 * end <= int(r["t_ms"]) <= 0.9 * end]
        n = len(steady)
        pool_cores = sum(int(r["pool_cpu_ns"]) for r in steady) / (n * 1e8)
        starved = sum(1 for r in steady if int(r["fjp_queued_submissions"]) + int(r["fjp_queued_tasks"]) == 0) / n
        print(f"  {label} ({ver}): steps={len(steps)} steady_pool_cores={pool_cores:.2f}/7 starved={100*starved:.1f}%")

        comp = collections.defaultdict(lambda: collections.defaultdict(list))
        for r in steps:
            st = r["status"]
            sub = int(r["submit_us"])
            rdy = int(r["ready_us"]) if r["ready_us"] else None
            sta = int(r["start_us"]) if r["start_us"] else None
            lck = int(r["locked_us"]) if r["locked_us"] else None
            be = int(r["body_end_us"])
            end_us = int(r["end_us"]) if r["end_us"] else None
            c = comp[st]
            if rdy is not None:
                c["area_wait"].append(rdy - sub)
                c["exec_wait"].append(sta - rdy)
                c["lock_wait"].append((lck - sta) if lck else 0)
                c["body"].append(be - (lck or sta))
            else:
                c["body"].append(be - sub)
            if end_us is not None:
                c["async_tail"].append(end_us - be)

        for st, c in sorted(comp.items(), key=lambda kv: -(sum(kv[1]["body"]) + sum(kv[1].get("async_tail", [])))):
            cnt = len(c["body"])
            body_s = sum(c["body"]) / 1e6
            lock_s = sum(c.get("lock_wait", [])) / 1e6
            area_s = sum(c.get("area_wait", [])) / 1e6
            exec_s = sum(c.get("exec_wait", [])) / 1e6
            tail_s = sum(c.get("async_tail", [])) / 1e6
            trace_rows.append({
                "label": label, "version": ver, "status": st, "count": cnt,
                "body_s": round(body_s, 3), "lock_wait_s": round(lock_s, 3),
                "area_wait_s": round(area_s, 3), "exec_wait_s": round(exec_s, 3),
                "async_tail_s": round(tail_s, 3),
                "mean_body_ms": round(1000 * body_s / cnt, 3),
                "mean_area_wait_ms": round(1000 * area_s / cnt, 3),
            })
            print(
                f"    {st:22s} n={cnt:5d} body={body_s:6.2f}s area_wait={area_s:6.2f}s "
                f"exec_wait={exec_s:5.2f}s lock={lock_s:4.2f}s tail={tail_s:6.2f}s"
            )
    write_csv(FINDINGS / "profile88_trace_stages.csv", list(trace_rows[0].keys()), trace_rows)


def analyze_jfr():
    jfr_runs = {r["label"]: r for r in load_csv(RUNS / "jfr_runs.csv")}
    stage_rows = []
    frame_rows = []
    alloc_rows = []

    print("\n=== PHASE 2B: JFR WORKER CPU, HOT FRAMES, AND ALLOCATIONS ===")
    for label in ("jfr_t5_mc263", "jfr_t5_mc2611", "jfr_t16_mc263"):
        meta = jfr_runs[label]
        ver = meta["version"]
        tile = int(meta["tile"])
        chunks = int(meta["chunks"])
        worker_cpu_s = float(meta["worker_cpu_s"])
        jfr_path = RUNS / label / "profile.jfr"

        samples = jfr_events(jfr_path, "ExecutionSample")
        worker_times = [ts(e["values"]["startTime"]) for e in samples if thread_group(e["values"]["sampledThread"]) == "worker"]
        t0, t1 = min(worker_times), max(worker_times)
        inside = lambda ev: t0 <= ts(ev["values"]["startTime"]) <= t1

        buckets = collections.Counter()
        leaf_counts = collections.Counter()
        incl_counts = collections.Counter()
        other_leaves = collections.Counter()
        worker_total = 0

        for ev in samples:
            v = ev["values"]
            if thread_group(v["sampledThread"]) != "worker" or not inside(ev):
                continue
            frames = (v.get("stackTrace") or {}).get("frames", [])
            if not frames:
                continue
            names = [frame_name(f) for f in frames]
            worker_total += 1
            b = classify_stack(names)
            buckets[b] += 1
            leaf_counts[names[0]] += 1
            if b == "other":
                other_leaves[names[0]] += 1
            for unique_name in set(names):
                incl_counts[unique_name] += 1

        print(f"\n  --- {label} (ver={ver}, tile={tile}, worker_cpu={worker_cpu_s:.2f}s, samples={worker_total}) ---")
        for b, cnt in buckets.most_common():
            pct = 100.0 * cnt / worker_total
            est_cpu = worker_cpu_s * (cnt / worker_total)
            stage_rows.append({
                "label": label, "version": ver, "tile": tile, "subsystem": b,
                "samples": cnt, "total_worker_samples": worker_total,
                "share_pct": round(pct, 2), "est_worker_cpu_s": round(est_cpu, 2),
                "est_worker_cpu_ms_per_chunk": round(1000 * est_cpu / chunks, 3),
            })
            print(f"    {b:30s} {pct:6.2f}%  {est_cpu:7.2f}s  ({1000*est_cpu/chunks:6.3f} ms/chunk)")
        if other_leaves:
            print("    [other top leaves]:", ", ".join(f"{k}:{v}" for k, v in other_leaves.most_common(8)))

        for m, cnt in leaf_counts.most_common(35):
            pct = 100.0 * cnt / worker_total
            frame_rows.append({
                "label": label, "version": ver, "tile": tile, "kind": "leaf",
                "method": m, "samples": cnt, "share_pct": round(pct, 2),
                "est_worker_cpu_s": round(worker_cpu_s * cnt / worker_total, 2),
            })
        for m, cnt in incl_counts.most_common(30):
            pct = 100.0 * cnt / worker_total
            frame_rows.append({
                "label": label, "version": ver, "tile": tile, "kind": "inclusive",
                "method": m, "samples": cnt, "share_pct": round(pct, 2),
                "est_worker_cpu_s": round(worker_cpu_s * cnt / worker_total, 2),
            })

        # Allocations
        alloc_class = collections.Counter()
        alloc_site = collections.Counter()
        for ev in jfr_events(jfr_path, "ObjectAllocationSample"):
            if not inside(ev):
                continue
            v = ev["values"]
            if thread_group(v.get("eventThread")) != "worker":
                continue
            w = int(v["weight"])
            alloc_class[type_name(v["objectClass"])] += w
            frames = (v.get("stackTrace") or {}).get("frames", [])
            site = next((frame_name(f) for f in frames if "levelgen" in frame_name(f) or "worldgend" in frame_name(f)), None)
            alloc_site[site or (frame_name(frames[0]) if frames else "?")] += w
        tot_w = sum(alloc_class.values())
        print(f"    Worker sampled alloc weight: {tot_w/1e9:.2f} GB ({tot_w/1e6/chunks:.3f} MB/chunk)")
        for cls, w in alloc_class.most_common(12):
            alloc_rows.append({
                "label": label, "version": ver, "tile": tile, "kind": "class",
                "name": cls, "weight_mb": round(w / 1e6, 2),
                "share_pct": round(100 * w / tot_w, 2) if tot_w else 0.0,
                "mb_per_chunk": round(w / 1e6 / chunks, 4),
            })
        for site, w in alloc_site.most_common(18):
            alloc_rows.append({
                "label": label, "version": ver, "tile": tile, "kind": "site",
                "name": site, "weight_mb": round(w / 1e6, 2),
                "share_pct": round(100 * w / tot_w, 2) if tot_w else 0.0,
                "mb_per_chunk": round(w / 1e6 / chunks, 4),
            })
            if w / tot_w >= 0.02:
                print(f"      alloc site {100*w/tot_w:5.1f}% ({w/1e6:7.1f} MB) {site}")

        # Parks / Monitors / Deopts
        parks = collections.Counter()
        park_ms = collections.Counter()
        for kind, evname, field in (("park", "ThreadPark", "parkedClass"), ("monitor", "JavaMonitorEnter", "monitorClass")):
            for ev in jfr_events(jfr_path, evname):
                if not inside(ev):
                    continue
                v = ev["values"]
                if thread_group(v.get("eventThread")) != "worker":
                    continue
                key = (kind, type_name(v[field]))
                parks[key] += 1
                park_ms[key] += 1000 * seconds(v["duration"])
        for key, ms_val in park_ms.most_common(5):
            print(f"      sync {key[0]} {key[1]}: {parks[key]} events, {ms_val:.1f} ms")

    write_csv(FINDINGS / "profile88_jfr_stages.csv", list(stage_rows[0].keys()), stage_rows)
    write_csv(FINDINGS / "profile88_jfr_hotframes.csv", list(frame_rows[0].keys()), frame_rows)
    write_csv(FINDINGS / "profile88_jfr_alloc.csv", list(alloc_rows[0].keys()), alloc_rows)


if __name__ == "__main__":
    analyze_ab()
    analyze_traces()
    analyze_jfr()
