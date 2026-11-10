#!/usr/bin/env python3
"""Collect finding #82's v5.7/v5.8 runs into orion58_runs.csv, orion58_heap.csv and the shared CSVs."""
import csv
import re
from pathlib import Path

HERE = Path(__file__).parent
RUNS = HERE / "orion58_runs"
GC_LINE = re.compile(r"\[([\d.]+)s\].*Pause (Young|Full) \((.*?)\) (\d+)M->(\d+)M\(\d+M\) ([\d.]+)ms")
NAMES = {"v57": "orion5.7", "v58": "orion5.8"}
SIZES = {"short": "6,400 chunks", "large": "65,536 chunks"}


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


def main():
    with (RUNS / "results.csv").open(newline="") as source:
        runs = [r for r in csv.DictReader(source) if r["label"].split("_")[0] in SIZES]
    with (HERE / "orion58_runs.csv").open("w", newline="") as sink:
        writer = csv.DictWriter(sink, fieldnames=["block", "round"] + list(runs[0]), lineterminator="\n")
        writer.writeheader()
        for r in runs:
            block, _, round_tag = r["label"].split("_")
            writer.writerow(dict(r, block=block, round=round_tag[1:]))

    # one row per GC of the large runs, so the heap chart does not need the raw GC logs
    with (HERE / "orion58_heap.csv").open("w", newline="") as sink:
        writer = csv.writer(sink, lineterminator="\n")
        writer.writerow(["label", "arm", "uptime_s", "kind", "before_mb", "after_mb", "pause_ms"])
        for r in runs:
            if not r["label"].startswith("large"):
                continue
            for line in (RUNS / f"{r['label']}.gc.log").read_text().splitlines():
                m = GC_LINE.search(line)
                if m:
                    writer.writerow([r["label"], r["arm"], m[1], m[2].lower(), m[4], m[5], m[6]])

    results, board = [], []
    for r in runs:
        block, arm, round_tag = r["label"].split("_")
        text = (RUNS / f"{r['label']}.result").read_text()
        mspc = dict(re.findall(r"(min|p1|p25|p50|p75|p99|max)=([\d.]+)", text))
        label = f"{NAMES[arm]} #82 {SIZES[block]} pair {round_tag[1:]}, 16g heap"
        results.append({
            "config": f"o58_{r['label']}", "label": label, "scheduler": NAMES[arm], "mosaic_tile": r["tile"],
            "chunks": r["chunks"], "max_bg_threads_flag": 7, "total_ms": r["total_ms"],
            **{f"mspc_{k}": v for k, v in mspc.items()},
        })
        board.append({
            "engine": "Orion v" + NAMES[arm].removeprefix("orion"), "run_label": label,
            "total_ms": r["total_ms"], "chunks": r["chunks"], "finding": "#82",
        })
    append_missing(HERE / "orion_results.csv", "config", results)
    append_missing(HERE / "leaderboard_entries.csv", "run_label", board)
    print(f"{len(runs)} runs collected")


if __name__ == "__main__":
    main()
