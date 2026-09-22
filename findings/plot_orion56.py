#!/usr/bin/env python3
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


HERE = Path(__file__).parent
ROWS = list(csv.DictReader((HERE / "orion56_results.csv").open()))
COLORS = {"orion5.5": "#5479ad", "orion5.6": "#df8452"}


def paired_metric(axis, field, scale, title, ylabel):
    runs = [row for row in ROWS if row["experiment"] == "inflight16_a"]
    for scheduler in ("orion5.5", "orion5.6"):
        series = sorted(
            (row for row in runs if row["scheduler"] == scheduler),
            key=lambda row: row["label"],
        )
        axis.plot(
            [int(row["label"][-1]) for row in series],
            [float(row[field]) / scale for row in series],
            marker="o", linewidth=2, markersize=7,
            color=COLORS[scheduler], label=scheduler,
        )
    axis.set(title=title, xlabel="Rotated pair", ylabel=ylabel)
    axis.set_xticks((1, 2, 3))
    axis.grid(axis="y", alpha=0.25)


def large_gc(axis):
    variants = [
        ("large_a", "orion5.5", "v5.5\n64 / 0.5", COLORS["orion5.5"]),
        ("large_a", "orion5.6", "v5.6\n16 / 0.3", COLORS["orion5.6"]),
        ("pressure05_a", "orion5.6", "v5.6\n16 / 0.5", "#bd6757"),
    ]
    values = [
        float(next(row for row in ROWS if row["experiment"] == experiment and row["scheduler"] == scheduler)["full_gc_ms"]) / 1000
        for experiment, scheduler, _, _ in variants
    ]
    bars = axis.bar(
        [label for _, _, label, _ in variants], values,
        color=[color for _, _, _, color in variants], width=0.65,
    )
    axis.bar_label(bars, fmt="%.1fs", padding=3)
    axis.set(title="65,536 chunks: full-GC pause time (n=1 each)", ylabel="Seconds")
    axis.set_ylim(0, max(values) * 1.2)
    axis.grid(axis="y", alpha=0.25)
    axis.set_axisbelow(True)


def main():
    fig, axes = plt.subplots(2, 2, figsize=(12, 8))
    paired_metric(axes[0, 0], "total_ms", 1000, "6,400 chunks: total time", "Seconds")
    paired_metric(axes[0, 1], "mspc_p50", 1, "6,400 chunks: p50 latency", "ms/chunk")
    paired_metric(axes[1, 0], "mspc_p99", 1, "6,400 chunks: p99 latency", "ms/chunk")
    large_gc(axes[1, 1])
    handles, labels = axes[0, 0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=2, bbox_to_anchor=(0.5, 0.955))
    fig.suptitle("Orion v5.6: latency falls; throughput remains tied", fontsize=16, y=0.995)
    fig.text(
        0.5, 0.01,
        "7 workers, 16GB pretouched ParallelGC. Large-run labels show max in-flight / C1GC pressure. Source: orion56_results.csv.",
        ha="center", fontsize=9,
    )
    fig.tight_layout(rect=(0, 0.035, 1, 0.92))
    output = HERE / "orion56_performance.png"
    fig.savefig(output, dpi=180)
    print(output)


if __name__ == "__main__":
    main()
