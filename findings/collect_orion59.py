#!/usr/bin/env python3
"""Collect finding #83's runs into orion59_runs.csv, orion59_profile.csv and the shared CSVs."""
import csv
import re
from pathlib import Path

HERE = Path(__file__).parent
RUNS = HERE / "orion59_runs"

V58 = ("Orion v5.8", "orion5.8", "v5.8")
V59 = ("Orion v5.9", "orion5.9", "v5.9")


def exp(base, what):
    return ("Experiment", base, what)


# (tag, arm) -> (leaderboard engine, scheduler, build). The scheduler flag named orion5.9 meant
# different things as pieces landed, so the build column is the source of truth. "v5.9" is the
# shipped default (all pieces, 32 in flight); the runner pins 16 unless an arm overrides it.
BUILDS = {
    ("lev", "v58"): V58,
    ("lev", "if64"): exp("orion5.8", "v5.8, window 64"),
    ("lev", "if256"): exp("orion5.8", "v5.8, window 256"),
    ("lev", "w8"): exp("orion5.8", "v5.8, 8 workers"),
    ("scale", "w2"): exp("orion5.8", "v5.8, 2 workers"),
    ("scale", "w4"): exp("orion5.8", "v5.8, 4 workers"),
    ("scale", "w6"): exp("orion5.8", "v5.8, 6 workers"),
    ("long", "v58"): V58,
    ("long", "v58p0"): exp("orion5.8", "v5.8, reclamation always on"),
    ("l16", "v58"): V58,
    ("l16", "w8"): exp("orion5.8", "v5.8, 8 workers"),
    ("l16", "v59"): exp("orion5.8", "v5.8 + area lock"),
    ("ab16", "v58"): V58,
    ("ab16", "greedy"): exp("orion5.8", "v5.8 + area lock"),
    ("ab16", "v59"): exp("orion5.9", "+ surface/biome"),
    ("pss16", "v59"): exp("orion5.9", "+ surface/biome"),
    ("pss16", "pss"): exp("orion5.9", "+ lock-free starts"),
    ("pss16", "pssw8"): exp("orion5.9", "+ lock-free starts, 8 workers"),
    ("scan16", "noscan"): exp("orion5.9", "+ lock-free starts"),
    ("scan16", "v59"): exp("orion5.9", "v5.9, window 16"),
    ("scan16", "v59w8"): exp("orion5.9", "v5.9, window 16, 8 workers"),
    ("win16", "v59"): exp("orion5.9", "v5.9, window 16"),
    ("win16", "if24"): exp("orion5.9", "v5.9, window 24"),
    ("win16", "if32"): V59,
    ("win16", "if32w8"): exp("orion5.9", "v5.9, 8 workers"),
    ("win16b", "if32"): V59,
    ("win16b", "if48"): exp("orion5.9", "v5.9, window 48"),
    ("win16b", "if64"): exp("orion5.9", "v5.9, window 64"),
    ("win16b", "if64w8"): exp("orion5.9", "v5.9, window 64, 8 workers"),
    ("win16b", "if128"): exp("orion5.9", "v5.9, window 128"),
    ("fin16", "v59w32"): V59,
    ("fin5", "v58"): V58,
    ("fin5", "v59"): V59,
    ("fin16", "v58"): V58,
    ("fin16", "v59"): exp("orion5.9", "v5.9, window 16"),
    ("big", "v58fix"): exp("orion5.8", "v5.8 + poll-thread fix"),
    ("big", "v59"): V59,
    ("fix16", "v58"): exp("orion5.8", "v5.8, fix check"),
    ("fix16", "v58fix"): exp("orion5.8", "v5.8 + poll-thread fix"),
}


def append_missing(path, key, rows):
    with path.open(newline="") as source:
        seen = {row[key] for row in csv.DictReader(source)}
        source.seek(0)
        fields = next(csv.reader(source))
    with path.open("a", newline="") as sink:
        writer = csv.DictWriter(sink, fieldnames=fields, lineterminator="\n")
        for row in rows:
            if row[key] not in seen:
                writer.writerow(row)


def profile(label, buckets=20):
    """Chunks per second and worker cores for each 5% of the run."""
    with (RUNS / f"{label}.cpu.csv").open(newline="") as source:
        rows = [r for r in csv.DictReader(source) if int(r["completed"]) > 0]
    total = int(rows[-1]["completed"])
    out, previous = [], rows[0]
    for bucket in range(1, buckets + 1):
        row = next(r for r in rows if int(r["completed"]) >= total * bucket / buckets)
        dt = float(row["t_s"]) - float(previous["t_s"])
        if dt > 0:
            out.append((bucket * 100 // buckets, (int(row["completed"]) - int(previous["completed"])) / dt,
                        (float(row["worker"]) - float(previous["worker"])) / dt))
        previous = row
    return out


def main():
    with (RUNS / "results.csv").open(newline="") as source:
        runs = []
        for r in csv.DictReader(source):
            tag, arm, round_tag = r["label"].split("_")
            if (tag, arm) in BUILDS:
                engine, scheduler, build = BUILDS[tag, arm]
                runs.append(dict(r, block=tag, round=round_tag[1:], engine=engine, scheduler=scheduler, build=build,
                                 emspc=round(int(r["total_ms"]) / int(r["chunks"]), 3)))
    lead = ["block", "round", "build", "engine", "emspc"]
    with (HERE / "orion59_runs.csv").open("w", newline="") as sink:
        writer = csv.DictWriter(sink, fieldnames=lead + [k for k in runs[0] if k not in lead], lineterminator="\n")
        writer.writeheader()
        writer.writerows(runs)

    with (HERE / "orion59_profile.csv").open("w", newline="") as sink:
        writer = csv.writer(sink, lineterminator="\n")
        writer.writerow(["label", "build", "percent_done", "cps", "worker_cores"])
        for r in runs:
            if r["chunks"] == "65536" and (RUNS / f"{r['label']}.cpu.csv").exists():
                for percent, cps, cores in profile(r["label"]):
                    writer.writerow([r["label"], r["build"], percent, round(cps, 1), round(cores, 2)])

    results, board = [], []
    for r in runs:
        text = (RUNS / f"{r['label']}.result").read_text()
        mspc = dict(re.findall(r"(min|p1|p25|p50|p75|p99|max)=([\d.]+)", text))
        workers = re.search(r"(\d) workers", r["build"])
        label = f"{r['build']} #83 {int(r['chunks']):,} chunks {r['block']} round {r['round']}, 16g heap"
        results.append({
            "config": f"o59_{r['label']}", "label": label, "scheduler": r["scheduler"], "mosaic_tile": r["tile"],
            "chunks": r["chunks"], "max_bg_threads_flag": workers[1] if workers else 7, "total_ms": r["total_ms"],
            **{f"mspc_{k}": v for k, v in mspc.items()},
        })
        board.append({"engine": r["engine"], "run_label": label, "total_ms": r["total_ms"], "chunks": r["chunks"], "finding": "#83"})
    append_missing(HERE / "orion_results.csv", "config", results)
    append_missing(HERE / "leaderboard_entries.csv", "run_label", board)
    print(f"{len(runs)} runs collected")


if __name__ == "__main__":
    main()
