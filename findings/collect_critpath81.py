#!/usr/bin/env python3
"""Collect finding #81's critpath runs into critpath81_runs.csv and critpath81_attribution.csv."""
import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
RUNS = HERE / "critpath_runs"
sys.path.insert(0, str(HERE))
import critpath_dag  # noqa: E402

STEP_COUNTS = {"noise": 7031, "structure_starts": 9875}
TAGS = ["dose1", "dose2"] + [f"ovh_{k}{i}" for i in range(1, 5) for k in "tn"]


def injected(arm):
    if arm == "base":
        return 0.0
    return sum(STEP_COUNTS[s] * int(us) / 1e6 for s, us in (p.split(":") for p in arm.split(",")))


def main():
    out = []
    for tag in TAGS:
        path = RUNS / tag / "results.csv"
        if not path.exists():
            continue
        for r in csv.DictReader(open(path)):
            label = r["label"]
            samples = RUNS / tag / f"{label}.samples.csv"
            pool = poll = ""
            if samples.exists():
                rows = list(csv.DictReader(open(samples)))
                pool = round(sum(int(x["pool_cpu_ns"]) for x in rows) / 1e9, 2)
                poll = round(sum(int(x["poll_cpu_ns"]) for x in rows) / 1e9, 2)
            g = lambda k: (re.search(k + r"=([\d.]+)", r["trace"]) or [None, ""])[1]
            out.append(dict(
                block=tag if tag.startswith("dose") else "trace overhead",
                arm=r["arm"] if r["trace"] else "untraced", round=label.rsplit("_r", 1)[1] if tag.startswith("dose") else tag[-1],
                total_ms=r["total_ms"], injected_cpu_s=round(injected(r["arm"]), 2), pool_cpu_s=pool, poll_cpu_s=poll,
                structure_wait_s=g("structureWaitS"), structure_lock_busy=g("structureLock"),
                light_lane_busy=g("lane.light"), worldgen_lane_busy=g("lane.worldgen"),
                mspc_p50=r["mspc_p50"], mspc_p99=r["mspc_p99"],
            ))
    with open(HERE / "critpath81_runs.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]), lineterminator="\n")
        w.writeheader()
        w.writerows(out)

    # per-target latency attribution, averaged over dose2's base runs
    seg_all = defaultdict(list)
    lat = []
    for steps in sorted((RUNS / "dose2").glob("base_r*.steps.csv")):
        g = critpath_dag.load(str(steps))
        end = {s: np.where(np.isnan(g[s]["end_us"]), -np.inf, g[s]["end_us"]) for s in critpath_dag.ORDER}
        targets = [t for t in csv.DictReader(open(str(steps).replace(".steps.csv", ".targets.csv")))
                   if t["complete_us"] and np.isfinite(end["full"][int(t["x"]) - g["_x0"], int(t["z"]) - g["_z0"]])]
        seg = defaultdict(float)
        for t in targets:
            x, z = int(t["x"]) - g["_x0"], int(t["z"]) - g["_z0"]
            t0, t1 = int(t["dispatch_us"]) / 1000, int(t["complete_us"]) / 1000
            lat.append(t1 - t0)
            for label, a, b in critpath_dag.chain(g, end, x, z, "full", t0):
                if b > a:
                    seg[label] += b - a
        for k, v in seg.items():
            seg_all[k].append(v / len(targets))
    groups = defaultdict(float)
    for k, v in seg_all.items():
        m = float(np.mean(v))
        if k.startswith("body:") or k.startswith("async:"):
            groups["step work: " + k.split(":", 1)[1]] += m
        elif k.startswith("sched"):
            groups["dispatch latency"] += m
        else:
            groups[k.replace("_", " ")] += m
    with open(HERE / "critpath81_attribution.csv", "w", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["segment", "mean_ms"])
        for k, v in sorted(groups.items(), key=lambda kv: -kv[1]):
            w.writerow([k, round(v, 2)])
        w.writerow(["target latency (mean)", round(float(np.mean(lat)), 2)])
    print(f"{len(out)} runs; attribution over {len(lat)} targets")


if __name__ == "__main__":
    main()
