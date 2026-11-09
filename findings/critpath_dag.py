#!/usr/bin/env python3
"""Rebuild the executed chunk-step DAG from an OrionTrace steps.csv and attribute waiting (finding #81).

For each step: dep_ready = when its last dependency finished (own previous status, or the slowest
neighbor inside the vanilla 26.1.1 requirement radius). sched = submit - dep_ready is pure dispatch
latency; dep_wait = dep_ready - own previous end is time spent waiting on neighbors.
"""
import csv
import sys
from collections import defaultdict

import numpy as np

ORDER = ["structure_starts", "structure_references", "biomes", "noise", "surface", "carvers",
         "features", "initialize_light", "light", "spawn", "full"]
REQUIRES = {
    "structure_references": [("structure_starts", 8)],
    "biomes": [("structure_starts", 8)],
    "noise": [("structure_starts", 8), ("biomes", 1)],
    "surface": [("structure_starts", 8), ("biomes", 1)],
    "carvers": [("structure_starts", 8)],
    "features": [("structure_starts", 8), ("carvers", 1)],
    "light": [("initialize_light", 1)],
    "spawn": [("biomes", 1)],
}
COLS = ["submit_us", "ready_us", "start_us", "locked_us", "body_end_us", "end_us"]


def window_max(grid, r):
    pad = np.pad(grid, r, constant_values=-np.inf)
    h, w = grid.shape
    rows = np.max([pad[:, r + d: r + d + w] for d in range(-r, r + 1)], axis=0)
    return np.max([rows[r + d: r + d + h, :] for d in range(-r, r + 1)], axis=0)


def load(path):
    rows = list(csv.DictReader(open(path)))
    xs = [int(r["x"]) for r in rows]
    zs = [int(r["z"]) for r in rows]
    x0, z0 = min(xs), min(zs)
    shape = (max(xs) - x0 + 1, max(zs) - z0 + 1)
    grids = {s: {c: np.full(shape, np.nan) for c in COLS} for s in ORDER}
    for r, x, z in zip(rows, xs, zs):
        g = grids[r["status"]]
        for c in COLS:
            if r[c]:
                g[c][x - x0, z - z0] = int(r[c]) / 1000.0
    grids["_x0"], grids["_z0"] = x0, z0
    return grids


def analyze(path):
    g = grids = load(path)
    end = {s: np.where(np.isnan(g[s]["end_us"]), -np.inf, g[s]["end_us"]) for s in ORDER}
    print(f"== {path}")
    print("  status                 n   sched_ms(med/mean)  depwait_ms(med/mean)  blame: own/neighbor-status shares")
    totals = defaultdict(float)
    for i, s in enumerate(ORDER[1:], 1):
        present = ~np.isnan(g[s]["submit_us"])
        own = end[ORDER[i - 1]]
        cands = [("own", own)] + [(f"{d}@r{r}", window_max(end[d], r)) for d, r in REQUIRES.get(s, [])]
        stack = np.stack([c for _, c in cands])
        ready = stack.max(axis=0)
        blame = stack.argmax(axis=0)
        ok = present & np.isfinite(ready)
        sched = (g[s]["submit_us"] - ready)[ok]
        depwait = (ready - own)[ok & np.isfinite(own)]
        shares = " ".join(f"{name}={np.mean(blame[ok] == k):.0%}" for k, (name, _) in enumerate(cands))
        neg = np.mean(sched < -1)
        totals["sched"] += sched.sum()
        totals["depwait"] += depwait.sum()
        print(f"  {s:20s} {ok.sum():6d} {np.median(sched):8.1f}/{sched.mean():7.1f} {np.median(depwait):10.1f}/{depwait.mean():7.1f}   {shares}"
              + (f"  [sched<0 in {neg:.1%}]" if neg > 0.001 else ""))
    print(f"  total sched {totals['sched'] / 1e3:.1f} task-s, dep wait {totals['depwait'] / 1e3:.1f} task-s")
    critical_path(g, end)
    targets = path.replace(".steps.csv", ".targets.csv")
    try:
        target_paths(g, end, targets)
    except FileNotFoundError:
        pass


def critical_path(g, end):
    """Walk back from the last FULL along last-arriving dependencies; attribute each segment."""
    full_end = end["full"]
    x, z = np.unravel_index(np.argmax(full_end), full_end.shape)
    s = "full"
    seg = defaultdict(float)
    t_end = full_end[x, z]
    hops = 0
    while True:
        gg = {c: g[s][c][x, z] for c in COLS}
        sub, rdy, sta, lck, be, en = (gg[c] for c in COLS)
        if not np.isnan(rdy):
            seg["area_wait"] += rdy - sub
            seg["exec_queue"] += sta - rdy
            seg["lock_wait"] += (lck - sta) if not np.isnan(lck) else 0
            seg[f"body:{s}"] += be - (lck if not np.isnan(lck) else sta)
        else:
            seg[f"body:{s}"] += be - sub
        seg[f"async:{s}"] += en - be
        i = ORDER.index(s)
        if i == 0:
            seg["before_first_step"] = sub
            break
        best, bx, bz, bs = end[ORDER[i - 1]][x, z], x, z, ORDER[i - 1]
        for d, r in REQUIRES.get(s, []):
            win = end[d][max(0, x - r): x + r + 1, max(0, z - r): z + r + 1]
            k = np.unravel_index(np.argmax(win), win.shape)
            if win[k] > best:
                best, bx, bz, bs = win[k], max(0, x - r) + k[0], max(0, z - r) + k[1], d
        seg["sched"] += sub - best
        x, z, s = bx, bz, bs
        hops += 1
    total = sum(seg.values())
    print(f"  critical path to last FULL: {t_end / 1e3:.2f}s, {hops} hops, segments sum {total / 1e3:.2f}s")
    for k, v in sorted(seg.items(), key=lambda kv: -kv[1])[:12]:
        print(f"    {k:28s} {v / 1e3:7.2f}s  {v / total:5.1%}")


def chain(g, end, x, z, s, t_stop):
    """Intervals (label, a, b) on the last-arriving-dependency path ending at (x, z, s), back to t_stop."""
    out = []
    while True:
        sub, rdy, sta, lck, be, en = (g[s][c][x, z] for c in COLS)
        pieces = [(f"async:{s}", be, en)]
        if not np.isnan(rdy):
            body_start = lck if not np.isnan(lck) else sta
            pieces += [(f"body:{s}", body_start, be), ("lock_wait", sta, body_start), ("exec_queue", rdy, sta), ("area_wait", sub, rdy)]
        else:
            pieces += [(f"body:{s}", sub, be)]
        i = ORDER.index(s)
        best = -np.inf
        if i > 0:
            best, bx, bz, bs = end[ORDER[i - 1]][x, z], x, z, ORDER[i - 1]
            for d, r in REQUIRES.get(s, []):
                win = end[d][max(0, x - r): x + r + 1, max(0, z - r): z + r + 1]
                k = np.unravel_index(np.argmax(win), win.shape)
                if win[k] > best:
                    best, bx, bz, bs = win[k], max(0, x - r) + k[0], max(0, z - r) + k[1], d
            pieces.append(("sched" if bs == ORDER[i - 1] and (bx, bz) == (x, z) else f"sched_after:{bs}", best, sub))
        for label, a, b in pieces:
            out.append((label, max(a, t_stop), b))
        if i == 0 or best <= t_stop:
            if i > 0:
                out.append(("before_request", t_stop, t_stop))
            return out
        x, z, s = bx, bz, bs


def target_paths(g, end, path):
    rows = [r for r in csv.DictReader(open(path))
            if r["complete_us"] and np.isfinite(end["full"][int(r["x"]) - g["_x0"], int(r["z"]) - g["_z0"]])]
    # steps grid origin is recomputed from the steps file; targets sit inside it
    seg = defaultdict(float)
    lat = []
    for r in rows:
        x, z = int(r["x"]) - g["_x0"], int(r["z"]) - g["_z0"]
        t0, t1 = int(r["dispatch_us"]) / 1000, int(r["complete_us"]) / 1000
        lat.append(t1 - t0)
        for label, a, b in chain(g, end, x, z, "full", t0):
            if b > a or label == "before_request":
                seg[label] += b - a
            if label == "before_request":
                seg["chains_reaching_request"] += 0
        seg["full->complete"] += max(0.0, t1 - end["full"][x, z])
    n = len(rows)
    total = sum(seg.values())
    print(f"  per-target latency: n={n} mean={np.mean(lat):.1f}ms median={np.median(lat):.1f}ms p99={np.percentile(lat, 99):.0f}ms; path segments mean {total / n:.1f}ms")
    for k, v in sorted(seg.items(), key=lambda kv: -kv[1])[:14]:
        print(f"    {k:32s} {v / n:7.2f}ms  {v / total:5.1%}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        analyze(p)
