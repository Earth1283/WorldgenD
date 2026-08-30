#!/usr/bin/env python3
"""Charts every MSPC/timing/thread-count stat from mspc_results.csv for the
WorldgenD scientific-findings doc. Regenerate after adding a new experiment row:

    python3 findings/plot_results.py
"""
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

HERE = Path(__file__).parent
CSV_PATH = HERE / "mspc_results.csv"

# Reference palette (dataviz skill), light mode, fixed categorical order.
SURFACE = "#fcfcfb"
INK_PRIMARY = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRIDLINE = "#e1e0d9"
BASELINE = "#c3c2b7"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]  # blue, orange, aqua, yellow, magenta

plt.rcParams.update({
    "font.family": ["DejaVu Sans"],
    "text.color": INK_PRIMARY,
    "axes.edgecolor": BASELINE,
    "axes.labelcolor": INK_SECONDARY,
    "xtick.color": INK_MUTED,
    "ytick.color": INK_MUTED,
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
})


def load_rows():
    with CSV_PATH.open() as f:
        return list(csv.DictReader(f))


def plot_percentiles(rows, out_path, title="MSPC: how long one chunk takes to generate, across every experiment",
                      subtitle="Lower is better. Log scale — the gap from p99 to max is real, not a rounding artifact."):
    percentile_cols = [
        ("mspc_min", "min"),
        ("mspc_p1", "p1"),
        ("mspc_p25", "p25"),
        ("mspc_p50", "p50"),
        ("mspc_p75", "p75"),
        ("mspc_p99", "p99"),
        ("mspc_max", "max"),
    ]

    fig, ax = plt.subplots(figsize=(11, 6.5))
    n_series = len(rows)
    n_groups = len(percentile_cols)
    group_width = 0.8
    bar_width = group_width / n_series
    x = range(n_groups)

    for i, row in enumerate(rows):
        values = [float(row[col]) for col, _ in percentile_cols]
        offsets = [xi - group_width / 2 + bar_width * i + bar_width / 2 for xi in x]
        ax.bar(
            offsets, values, width=bar_width * 0.92,
            color=SERIES[i % len(SERIES)], label=row["label"], zorder=3,
        )

    ax.set_yscale("log")
    ax.set_ylabel("milliseconds per chunk (log scale)")
    ax.set_xticks(list(x))
    ax.set_xticklabels([label for _, label in percentile_cols])
    fig.suptitle(title, color=INK_PRIMARY, fontsize=14, y=0.99)
    ax.set_title(subtitle, color=INK_SECONDARY, fontsize=9.5, pad=12, loc="left")
    ax.yaxis.set_major_formatter(mticker.FuncFormatter(lambda v, _: f"{v:g}"))
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    ax.legend(frameon=False, loc="upper left", fontsize=9, labelcolor=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_run_summary(rows, out_path):
    fig, (ax_time, ax_workers) = plt.subplots(1, 2, figsize=(11, 4.5))
    labels = [row["label"] for row in rows]
    colors = [SERIES[i % len(SERIES)] for i in range(len(rows))]

    total_s = [float(row["total_ms"]) / 1000.0 for row in rows]
    bars = ax_time.bar(labels, total_s, color=colors, zorder=3)
    ax_time.set_ylabel("total wall-clock time (s)")
    ax_time.set_title("Total time to fill the mosaic", fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars, total_s):
        ax_time.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.0f}s",
                     ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    workers = [int(row["workers_actual"]) for row in rows]
    verified = [row["jcmd_verified"] != "no" for row in rows]
    bars2 = ax_workers.bar(labels, workers, color=colors, zorder=3)
    ax_workers.set_ylabel("worker threads actually used")
    ax_workers.set_title("Pool size actually used (jcmd-confirmed where noted)", fontsize=11, color=INK_PRIMARY)
    for bar, val, ok in zip(bars2, workers, verified):
        mark = "" if ok else " (unverified)"
        ax_workers.text(bar.get_x() + bar.get_width() / 2, val, f"{val}{mark}",
                         ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    for ax in (ax_time, ax_workers):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)
        ax.tick_params(axis="x", labelrotation=20, labelsize=8)
        for tick in ax.get_xticklabels():
            tick.set_ha("right")

    fig.suptitle(
        "Cutting workers 7 -> 4 cost nothing; the flag never moved the count above 7 to begin with",
        fontsize=11, color=INK_SECONDARY, y=1.03,
    )
    fig.tight_layout()
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_gc_summary(rows, out_path, caption="Same 7 workers, same 16GB pretouched heap, same mosaic — only the collector changes"):
    fig, (ax_time, ax_p50) = plt.subplots(1, 2, figsize=(10, 4.5))
    labels = [row["label"] for row in rows]
    colors = [SERIES[i % len(SERIES)] for i in range(len(rows))]

    total_s = [float(row["total_ms"]) / 1000.0 for row in rows]
    bars = ax_time.bar(labels, total_s, color=colors, zorder=3)
    ax_time.set_ylabel("total wall-clock time (s)")
    ax_time.set_title("Total time to fill the mosaic", fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars, total_s):
        ax_time.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.0f}s",
                     ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    p50 = [float(row["mspc_p50"]) for row in rows]
    bars2 = ax_p50.bar(labels, p50, color=colors, zorder=3)
    ax_p50.set_ylabel("MSPC median, p50 (ms/chunk)")
    ax_p50.set_title("Typical per-chunk latency", fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars2, p50):
        ax_p50.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.1f}ms",
                    ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    for ax in (ax_time, ax_p50):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)
        ax.tick_params(axis="x", labelrotation=15, labelsize=8.5)
        for tick in ax.get_xticklabels():
            tick.set_ha("right")

    fig.suptitle(
        caption,
        fontsize=10.5, color=INK_SECONDARY, y=1.02,
    )
    fig.tight_layout()
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_drag_race(rows, out_path, time_title="Total time to generate its own selection",
                    caption=("WorldgenD did 6400 chunks; Paper/Leaf variants did 6561 (Chunky's radius-640 square is inclusive of\n"
                              "the center chunk) — throughput panel normalizes for that, time panel does not")):
    fig, (ax_time, ax_cps) = plt.subplots(1, 2, figsize=(11, 4.5))
    labels = [row["label"] for row in rows]
    colors = [SERIES[i % len(SERIES)] for i in range(len(rows))]

    total_s = [float(row["total_ms"]) / 1000.0 for row in rows]
    bars = ax_time.bar(labels, total_s, color=colors, zorder=3)
    ax_time.set_ylabel("total wall-clock time (s)")
    ax_time.set_title(time_title, fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars, total_s):
        ax_time.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.0f}s",
                     ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    cps = [float(row["chunks_per_sec"]) for row in rows]
    bars2 = ax_cps.bar(labels, cps, color=colors, zorder=3)
    ax_cps.set_ylabel("chunks/sec (normalized for chunk-count difference)")
    ax_cps.set_title("Throughput", fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars2, cps):
        ax_cps.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.1f}",
                    ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    for ax in (ax_time, ax_cps):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)
        ax.tick_params(axis="x", labelrotation=15, labelsize=8.5)
        for tick in ax.get_xticklabels():
            tick.set_ha("right")

    fig.suptitle(caption, fontsize=9.5, color=INK_SECONDARY, y=1.05)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_algorithm_progress(out_path):
    with (HERE / "algorithm_progress.csv").open() as f:
        progress_rows = list(csv.DictReader(f))

    # Sequential blue ramp (dataviz skill palette.md): light -> dark tracks
    # "worse -> better" here, since this is an ordered magnitude comparison
    # between implementations, not unrelated categories.
    ramp = ["#9ec5f4", "#2a78d6", "#104281"]

    fig, ax = plt.subplots(figsize=(8, 5.5))
    labels = [r["label"].replace("\\n", "\n") for r in progress_rows]
    values = [float(r["ms_per_chunk"]) for r in progress_rows]
    colors = ramp[: len(progress_rows)]

    bars = ax.bar(labels, values, color=colors, width=0.55, zorder=3)
    for bar, row in zip(bars, progress_rows):
        ax.text(
            bar.get_x() + bar.get_width() / 2, bar.get_height() + max(values) * 0.015,
            f"{row['ms_per_chunk']} ms\n({row['metric']})",
            ha="center", va="bottom", fontsize=9, color=INK_SECONDARY, linespacing=1.4,
        )

    ax.set_ylabel("typical time per chunk (ms) — lower is better")
    ax.set_ylim(0, max(values) * 1.28)
    fig.suptitle("MSPC has been going down as the fill algorithm improved", color=INK_PRIMARY, fontsize=13.5, y=0.985)
    ax.set_title(
        "Not an apples-to-apples metric across every bar — the first is a whole-run average\n"
        "(no per-chunk data existed yet); the mosaic bars are true MSPC medians. Still the\n"
        "right direction of travel: same box, same seed, same job, fewer ms per chunk.",
        color=INK_SECONDARY, fontsize=8.5, pad=10, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def main():
    rows = load_rows()
    plot_percentiles(rows, HERE / "mspc_percentiles.png")
    plot_run_summary(rows, HERE / "run_summary.png")
    plot_algorithm_progress(HERE / "mspc_progress.png")

    with (HERE / "gc_results.csv").open() as f:
        gc_rows = list(csv.DictReader(f))
    plot_percentiles(
        gc_rows, HERE / "gc_percentiles.png",
        title="MSPC across garbage collectors (fixed 16GB pretouched heap, 7 workers)",
        subtitle="Lower is better. Same mosaic, same heap settings — only the collector changes.",
    )
    plot_gc_summary(gc_rows, HERE / "gc_summary.png")

    with (HERE / "gc_4w_results.csv").open() as f:
        gc_4w_rows = list(csv.DictReader(f))
    plot_percentiles(
        gc_4w_rows, HERE / "gc_4w_percentiles.png",
        title="MSPC across garbage collectors, crossed with 4 workers (fixed 16GB pretouched heap)",
        subtitle="Lower is better. Same mosaic, same heap, same worker count — only the collector changes.",
    )
    plot_gc_summary(
        gc_4w_rows, HERE / "gc_4w_summary.png",
        caption="4 workers this time (not 7), same 16GB pretouched heap, same mosaic — only the collector changes",
    )

    with (HERE / "jfr_ab_results.csv").open() as f:
        jfr_rows = list(csv.DictReader(f))
    plot_percentiles(
        jfr_rows, HERE / "jfr_ab_percentiles.png",
        title="MSPC across the JFR-guided reflection A/B (ParallelGC, 4 workers)",
        subtitle="Lower is better. Same config throughout — only the reflection call strategy changes.",
    )
    plot_gc_summary(
        jfr_rows, HERE / "jfr_ab_summary.png",
        caption="MethodHandle conversion regressed; caching a plain Method did not",
    )

    with (HERE / "pgc_tuning_results.csv").open() as f:
        pgc_rows = list(csv.DictReader(f))
    plot_gc_summary(
        pgc_rows, HERE / "pgc_tuning_summary.png",
        caption="Bars are in run order — the tuned sample sits between two untouched baseline samples",
    )

    with (HERE / "drag_race_results.csv").open() as f:
        drag_race_rows = list(csv.DictReader(f))
    plot_drag_race(drag_race_rows, HERE / "drag_race_summary.png")

    with (HERE / "sustained_results.csv").open() as f:
        sustained_rows = list(csv.DictReader(f))
    plot_drag_race(
        sustained_rows, HERE / "sustained_summary.png",
        time_title="Total time to generate its own (much bigger) selection",
        caption=(
            "WorldgenD ran at two scales (6400 and 25600 chunks) to confirm its own throughput doesn't\n"
            "change with size; Paper/Leaf ran once each at 58081 chunks (Chunky radius 1920, tripled from #18)"
        ),
    )

    print(f"Wrote charts to {HERE}")


if __name__ == "__main__":
    main()
