#!/usr/bin/env python3
"""Summarize OrionTrace output (finding #81): where worker time goes, and which lane saturates."""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

WORKERS = 7


def load(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def steady(rows, key="t_ms"):
    end = max(int(r[key]) for r in rows)
    return [r for r in rows if 0.1 * end <= int(r[key]) <= 0.9 * end]


def us(r, k):
    return int(r[k]) if r[k] else None


def summarize(base):
    samples = load(f"{base}.samples.csv")[1:]
    lanes = load(f"{base}.lanes.csv")
    steps = load(f"{base}.steps.csv")
    wall_ms = max(int(r["t_ms"]) for r in samples)
    out = {"wall_s": wall_ms / 1000}

    s = steady(samples)
    n = len(s)
    out["pool_cores"] = sum(int(r["pool_cpu_ns"]) for r in s) / (n * 1e8)
    out["poll_frac"] = sum(int(r["poll_cpu_ns"]) for r in s) / (n * 1e8)
    out["other_java_cores"] = sum(int(r["other_java_cpu_ns"]) for r in s) / (n * 1e8)
    out["process_cores"] = sum(int(r["process_cpu_ns"]) for r in s) / (n * 1e8)
    ticks = sum(int(r["host_ticks"]) for r in s)
    out["host_busy"] = 1 - sum(int(r["host_idle_ticks"]) for r in s) / ticks
    out["host_steal"] = sum(int(r["host_steal_ticks"]) for r in s) / ticks
    for k in ("fjp_queued_submissions", "fjp_queued_tasks", "fjp_running", "area_waiting", "exec_waiting", "steps_running"):
        out[k] = statistics.mean(int(r[k]) for r in s)
    out["starved_frac"] = sum(1 for r in s if int(r["fjp_queued_submissions"]) + int(r["fjp_queued_tasks"]) == 0) / n

    ls = steady(lanes)
    for k in lanes[0]:
        if k != "t_ms":
            out[f"lane_{k}"] = sum(int(r[k]) for r in ls) / (len(ls) * 1e8)

    # per-status latency components, microseconds
    comp = defaultdict(lambda: defaultdict(list))
    for r in steps:
        st, sub, rdy, sta, lck, be, end = r["status"], us(r, "submit_us"), us(r, "ready_us"), us(r, "start_us"), us(r, "locked_us"), us(r, "body_end_us"), us(r, "end_us")
        c = comp[st]
        if rdy is not None:
            c["area_wait"].append(rdy - sub)
            c["exec_wait"].append(sta - rdy)
            c["lock_wait"].append((lck - sta) if lck else 0)
            c["body"].append(be - (lck or sta))
        else:
            c["body"].append(be - sub)
        if end is not None:
            c["async_tail"].append(end - be)
    out["status"] = {st: {k: (sum(v) / 1e6, statistics.mean(v) / 1e3) for k, v in c.items()} for st, c in comp.items()}

    # per-chunk gap between one status finishing and the next being submitted (vanilla scheduling + neighbor deps)
    order = ["structure_starts", "structure_references", "biomes", "noise", "surface", "carvers", "features", "initialize_light", "light", "spawn", "full"]
    by_chunk = defaultdict(dict)
    for r in steps:
        by_chunk[(r["x"], r["z"])][r["status"]] = r
    gaps = defaultdict(list)
    for chunk in by_chunk.values():
        for a, b in zip(order, order[1:]):
            if a in chunk and b in chunk and chunk[a]["end_us"]:
                gaps[f"{a}->{b}"].append(int(chunk[b]["submit_us"]) - int(chunk[a]["end_us"]))
    out["gaps"] = {k: (statistics.median(v) / 1e3, statistics.mean(v) / 1e3, len(v)) for k, v in gaps.items()}
    return out


def main():
    for base in sys.argv[1:]:
        o = summarize(base)
        print(f"== {Path(base).name}  wall={o['wall_s']:.1f}s")
        print("  cores: pool=%.2f/%d poll=%.2f otherJava=%.2f process=%.2f host_busy=%.0f%% steal=%.1f%%" % (
            o["pool_cores"], WORKERS, o["poll_frac"], o["other_java_cores"], o["process_cores"], 100 * o["host_busy"], 100 * o["host_steal"]))
        print("  queues: fjpSub=%.1f fjpTasks=%.1f fjpRunning=%.2f areaWaiting=%.1f execWaiting=%.1f running=%.2f starved=%.0f%%" % (
            o["fjp_queued_submissions"], o["fjp_queued_tasks"], o["fjp_running"], o["area_waiting"], o["exec_waiting"], o["steps_running"], 100 * o["starved_frac"]))
        print("  lanes busy: " + " ".join(f"{k[5:]}={v:.2f}" for k, v in o.items() if k.startswith("lane_") and v > 0.005))
        print("  status       n    body_s  lock_s  area_s  exec_s  tail_s | body_ms lock_ms exec_ms")
        for st, c in sorted(o["status"].items(), key=lambda kv: -kv[1]["body"][0]):
            g = lambda k, i: c[k][i] if k in c else 0
            print("  %-20s %8.1f %7.1f %7.1f %7.1f %7.1f | %7.2f %7.2f %7.2f" % (
                st, g("body", 0), g("lock_wait", 0), g("area_wait", 0), g("exec_wait", 0), g("async_tail", 0), g("body", 1), g("lock_wait", 1), g("exec_wait", 1)))
        print("  gaps (median/mean ms): " + "  ".join(f"{k}={v[0]:.0f}/{v[1]:.0f}" for k, v in o["gaps"].items()))


if __name__ == "__main__":
    main()
