#!/usr/bin/env python3
"""Detailed JFR breakdown for an Orion run: stages, hot frames, GC, allocation, deopts, sync."""
import argparse
import collections
import csv
import json
import re
import subprocess
from datetime import datetime
from pathlib import Path

STAGES = (
    ("NoiseChunk construction", ("NoiseChunk.<init>", "createNoiseChunk")),
    ("surface", ("SurfaceSystem", "SurfaceRules", "NoiseBasedChunkGenerator.buildSurface")),
    ("noise fill", ("doFill", "fillFromNoise", "NoiseChunk", "DensityFunctions", "Aquifer", "BlendedNoise",
                    "ImprovedNoise", "NormalNoise", "PerlinNoise", "Beardifier")),
    ("biomes", ("createBiomes", "Climate", "BiomeManager", "ResettableBiomeSupplier", "getNoiseBiome")),
    ("features", ("Feature.", "PlacedFeature", "generateFeatures", "applyBiomeDecoration")),
    ("carvers", ("carver", "applyCarvers")),
    ("structure starts", ("createStructures", "generateStructureStarts", "StructureStart")),
    ("light", ("lighting.", "initializeLight")),
)


def events(jfr, name):
    out = subprocess.check_output(["jfr", "print", "--json", "--events", f"jdk.{name}", str(jfr)])
    return json.loads(out)["recording"]["events"]


def ts(value):
    return datetime.fromisoformat(value).timestamp()


def seconds(value):
    # ISO-8601 duration as printed by jfr, e.g. PT0.0112S or PT1M0.0001S
    match = re.fullmatch(r"PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?", value or "")
    if not match:
        return 0.0
    hours, minutes, secs = match.groups()
    return 3600 * int(hours or 0) + 60 * int(minutes or 0) + float(secs or 0)


def frame_name(frame):
    method = frame["method"]
    return method["type"]["name"].replace("/", ".") + "." + method["name"]


def type_name(obj):
    return obj["name"].replace("/", ".") if obj else "?"


def group(thread):
    name = (thread or {}).get("javaName") or (thread or {}).get("osName") or "?"
    if name.startswith("Worker-Main"):
        return "worker"
    if name.startswith("IO-Worker"):
        return "io-worker"
    return name


def stage_of(names):
    for name in reversed(names):
        for stage, markers in STAGES:
            if any(marker in name for marker in markers):
                return stage
    return "other"


def write(path, header, rows):
    with path.open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("recording", type=Path)
    parser.add_argument("outdir", type=Path)
    parser.add_argument("--prefix", default="orion57_jfr")
    args = parser.parse_args()
    args.outdir.mkdir(parents=True, exist_ok=True)
    out = lambda suffix: args.outdir / f"{args.prefix}_{suffix}.csv"

    samples = events(args.recording, "ExecutionSample")
    worker_times = [ts(e["values"]["startTime"]) for e in samples if group(e["values"]["sampledThread"]) == "worker"]
    t0, t1 = min(worker_times), max(worker_times)
    inside = lambda event: t0 <= ts(event["values"]["startTime"]) <= t1
    window = t1 - t0

    stage_count = collections.Counter()
    leaf = collections.Counter()
    per_second = collections.Counter()
    worker_total = 0
    for event in samples:
        values = event["values"]
        if group(values["sampledThread"]) != "worker" or not inside(event):
            continue
        frames = (values.get("stackTrace") or {}).get("frames", [])
        if not frames:
            continue
        names = [frame_name(f) for f in frames]
        worker_total += 1
        stage_count[stage_of(names)] += 1
        leaf[names[0]] += 1
        per_second[int(ts(values["startTime"]) - t0)] += 1

    write(out("stages"), ("stage", "samples", "share_pct"),
          [(s, c, round(100 * c / worker_total, 2)) for s, c in stage_count.most_common()])
    write(out("leaf_frames"), ("method", "samples", "share_pct"),
          [(m, c, round(100 * c / worker_total, 2)) for m, c in leaf.most_common(60)])

    cpu = []
    for event in events(args.recording, "CPULoad"):
        v = event["values"]
        cpu.append((ts(v["startTime"]) - t0, float(v["jvmUser"]), float(v["jvmSystem"]), float(v["machineTotal"])))
    write(out("timeline"), ("t_sec", "worker_samples", "jvm_user", "jvm_system", "machine_total"),
          [(s, per_second.get(s, 0), *next(((u, y, m) for t, u, y, m in cpu if int(t) == s), ("", "", ""))) for s in range(int(window) + 1)])

    gc_rows = []
    for event in events(args.recording, "GarbageCollection"):
        v = event["values"]
        gc_rows.append((round(ts(v["startTime"]) - t0, 2), v["name"], v["cause"],
                        round(1000 * seconds(v["sumOfPauses"]), 1), round(1000 * seconds(v["longestPause"]), 1)))
    write(out("gc"), ("t_sec", "collector", "cause", "pause_ms", "longest_ms"), gc_rows)

    alloc_class = collections.Counter()
    alloc_site = collections.Counter()
    for event in events(args.recording, "ObjectAllocationSample"):
        if not inside(event):
            continue
        v = event["values"]
        weight = int(v["weight"])
        alloc_class[type_name(v["objectClass"])] += weight
        frames = (v.get("stackTrace") or {}).get("frames", [])
        site = next((frame_name(f) for f in frames if "levelgen" in frame_name(f) or "worldgend" in frame_name(f)), None)
        alloc_site[site or (frame_name(frames[0]) if frames else "?")] += weight
    total_alloc = sum(alloc_class.values())
    write(out("alloc_class"), ("class", "weight_mb", "share_pct"),
          [(c, round(w / 1e6, 1), round(100 * w / total_alloc, 2)) for c, w in alloc_class.most_common(40)])
    write(out("alloc_site"), ("site", "weight_mb", "share_pct"),
          [(c, round(w / 1e6, 1), round(100 * w / total_alloc, 2)) for c, w in alloc_site.most_common(40)])

    deopt = collections.Counter()
    for event in events(args.recording, "Deoptimization"):
        if inside(event):
            v = event["values"]
            deopt[(frame_name({"method": v["method"]}), v["reason"], v["action"], v["compiler"])] += 1
    write(out("deopt"), ("method", "reason", "action", "compiler", "count"),
          [(*k, c) for k, c in deopt.most_common(30)])

    exceptions = collections.Counter()
    for event in events(args.recording, "JavaExceptionThrow"):
        v = event["values"]
        exceptions[(group(v["eventThread"]), type_name(v["thrownClass"]), inside(event))] += 1
    write(out("exceptions"), ("thread", "class", "in_fill_window", "count"),
          [(*k, c) for k, c in exceptions.most_common(30)])

    sync = collections.defaultdict(lambda: [0, 0.0])
    for kind, field in (("park", "parkedClass"), ("monitor", "monitorClass")):
        for event in events(args.recording, "ThreadPark" if kind == "park" else "JavaMonitorEnter"):
            if not inside(event):
                continue
            v = event["values"]
            row = sync[(kind, group(v["eventThread"]), type_name(v[field]))]
            row[0] += 1
            row[1] += seconds(v["duration"])
    write(out("sync"), ("kind", "thread", "class", "events", "total_ms"),
          [(*k, n, round(1000 * d, 1)) for k, (n, d) in sorted(sync.items(), key=lambda i: -i[1][1])[:30]])

    print(f"window={window:.1f}s worker_samples={worker_total} gc_events={len(gc_rows)}")


if __name__ == "__main__":
    main()
