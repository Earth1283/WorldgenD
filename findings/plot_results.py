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
import numpy as np
from matplotlib.colors import LinearSegmentedColormap, to_hex

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


def plot_worker_scaling(by_config, out_path):
    groups = [
        ("Mosaic", by_config["mosaic_champion_fresh"], by_config["mosaic_7w"]),
        ("Orion v2", by_config["orion2_champion"], by_config["orion2_7w"]),
        ("Orion v2.1", by_config["orion2_1_4w_fresh"], by_config["orion2_1_7w"]),
    ]

    fig, ax = plt.subplots(figsize=(8, 5.5))
    group_width = 0.6
    bar_width = group_width / 2
    x = range(len(groups))

    for i, (name, row4, row7) in enumerate(groups):
        t4 = float(row4["total_ms"]) / 1000.0
        t7 = float(row7["total_ms"]) / 1000.0
        xi = x[i]
        b4 = ax.bar(xi - bar_width / 2, t4, width=bar_width * 0.92, color=SERIES[0], zorder=3,
                    label="4 workers" if i == 0 else None)
        b7 = ax.bar(xi + bar_width / 2, t7, width=bar_width * 0.92, color=SERIES[1], zorder=3,
                    label="7 workers" if i == 0 else None)
        for bar, val in ((b4, t4), (b7, t7)):
            ax.text(bar[0].get_x() + bar[0].get_width() / 2, val, f"{val:.0f}s",
                    ha="center", va="bottom", fontsize=9, color=INK_SECONDARY)
        pct = (t4 - t7) / t4 * 100
        ax.text(xi, max(t4, t7) * 1.12, f"{pct:+.1f}%", ha="center", va="bottom",
                fontsize=10.5, color=INK_PRIMARY, fontweight="bold")

    ax.set_ylabel("total wall-clock time (s)")
    ax.set_xticks(list(x))
    ax.set_xticklabels([name for name, _, _ in groups], fontsize=11)
    all_ms = [float(r["total_ms"]) for _, row4, row7 in groups for r in (row4, row7)]
    ax.set_ylim(0, max(all_ms) / 1000.0 * 1.25)
    fig.suptitle("Does adding cores (4->7 workers) actually help?", color=INK_PRIMARY, fontsize=14, y=0.99)
    ax.set_title(
        "The mosaic's ~3% is inside this box's own ~9% noise band (#17), matching #13's 'cutting\n"
        "workers costs nothing' finding. v2's 6.3% was the first real gain in this investigation —\n"
        "v2.1's 10.6% (#33) is bigger still, once its own scheduler stopped eating a third of a core.",
        color=INK_SECONDARY, fontsize=9, pad=12, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    ax.legend(frameon=False, loc="upper right", fontsize=9.5, labelcolor=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0, 1, 0.87))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_cpu_breakdown(rows, out_path):
    fig, ax = plt.subplots(figsize=(8.5, 5.5))
    labels = [row["label"] for row in rows]
    issafe = [float(row["issafe_pct"]) for row in rows]
    polltask = [float(row["polltask_pct"]) for row in rows]
    other = [float(row["other_pct"]) for row in rows]

    y = range(len(rows))
    b1 = ax.barh(list(y), issafe, color=SERIES[1], zorder=3, label="isSafe() backlog rescan")
    b2 = ax.barh(list(y), polltask, left=issafe, color=SERIES[0], zorder=3, label="reflective pollTask()")
    left3 = [a + b for a, b in zip(issafe, polltask)]
    ax.barh(list(y), other, left=left3, color=BASELINE, zorder=3, label="everything else (real generation, GC, ...)")

    # Segments under 3% are visually near-zero already; an in-bar label there just overlaps.
    for i, (row, is_v, pt_v) in enumerate(zip(rows, issafe, polltask)):
        if is_v >= 3:
            ax.text(is_v / 2, i, f"{is_v:.1f}%", ha="center", va="center", fontsize=9, color="white", fontweight="bold")
        if pt_v >= 3:
            ax.text(is_v + pt_v / 2, i, f"{pt_v:.1f}%", ha="center", va="center", fontsize=9, color="white", fontweight="bold")
        if is_v < 3 and pt_v < 3:
            ax.text(is_v + pt_v + 1.5, i, f"{is_v + pt_v:.1f}%", ha="left", va="center", fontsize=9, color=INK_SECONDARY)
        ax.text(102, i, f"park={row['park_events']}", ha="left", va="center", fontsize=8.5, color=INK_MUTED)

    ax.set_xlim(0, 118)
    ax.set_yticks(list(y))
    ax.set_yticklabels(labels, fontsize=10)
    ax.set_xlabel("% of all CPU execution samples in the run")
    fig.suptitle("Poll-gating (#28/#29) went nowhere; the spatial index (#30) nearly erases both costs",
                 color=INK_PRIMARY, fontsize=12.5, y=1.0)
    ax.set_title(
        "#28/#29 only ever gated the smaller cost, so the loop stayed scan-bound either way. #30\n"
        "replaced the scan itself — main thread's CPU share drops from ~32% to ~1%.",
        color=INK_SECONDARY, fontsize=8.5, pad=10, loc="left",
    )
    ax.grid(axis="x", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.12), ncol=1, fontsize=8.5, labelcolor=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0, 1, 0.88))
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_scatter_order_comparison(by_config, out_path):
    configs = [
        ("4w, no scatter", "orion2_1_4w_fresh"),
        ("4w, scatter", "orion2_2_4w_scatter"),
        ("7w, no scatter", "orion2_1_7w"),
        ("7w, scatter", "orion2_2_7w_scatter"),
    ]
    labels = [c[0] for c in configs]
    rows = [by_config[c[1]] for c in configs]
    colors = [SERIES[1], SERIES[0], SERIES[1], SERIES[0]]

    fig, (ax_time, ax_p50) = plt.subplots(1, 2, figsize=(11, 4.8))

    total_s = [float(r["total_ms"]) / 1000.0 for r in rows]
    bars = ax_time.bar(labels, total_s, color=colors, zorder=3)
    ax_time.set_ylabel("total wall-clock time (s)")
    ax_time.set_title("Total time — mixed result", fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars, total_s):
        ax_time.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.0f}s",
                     ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    p50 = [float(r["mspc_p50"]) for r in rows]
    bars2 = ax_p50.bar(labels, p50, color=colors, zorder=3)
    ax_p50.set_ylabel("MSPC median, p50 (ms/chunk)")
    ax_p50.set_title("Typical per-chunk latency — clear win", fontsize=11, color=INK_PRIMARY)
    for bar, val in zip(bars2, p50):
        ax_p50.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.0f}ms",
                    ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    for ax in (ax_time, ax_p50):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)
        ax.tick_params(axis="x", labelrotation=12, labelsize=9)

    fig.suptitle(
        "#35: scatter-ordered target list — cuts median latency ~45-51%, total time barely moves either way",
        fontsize=10.5, color=INK_SECONDARY, y=1.03,
    )
    fig.tight_layout()
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_interleaved_comparison(rows, out_path):
    rounds = sorted(set(int(r["round"]) for r in rows))
    by_round_engine = {(int(r["round"]), r["engine"]): float(r["ms_per_chunk"]) for r in rows}
    engines = ["Orion v2.1", "Paper"]
    colors = {"Orion v2.1": SERIES[0], "Paper": SERIES[1]}

    fig, ax = plt.subplots(figsize=(8.5, 5.5))
    group_width = 0.6
    bar_width = group_width / len(engines)
    x = range(len(rounds))

    for i, engine in enumerate(engines):
        values = [by_round_engine[(rnd, engine)] for rnd in rounds]
        offsets = [xi - group_width / 2 + bar_width * i + bar_width / 2 for xi in x]
        bars = ax.bar(offsets, values, width=bar_width * 0.92, color=colors[engine], zorder=3, label=engine)
        for bar, val in zip(bars, values):
            ax.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.2f}",
                    ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)

    ax.set_ylabel("ms/chunk (lower is better)")
    ax.set_ylim(0, max(by_round_engine.values()) * 1.2)
    ax.set_xticks(list(x))
    ax.set_xticklabels([f"Round {r}" for r in rounds])
    fig.suptitle("#32: interleaved rerun — v2.1 vs Paper, alternating A,B,A,B,A,B",
                 color=INK_PRIMARY, fontsize=13, y=0.99)
    ax.set_title(
        "v2.1 wins round 1 by a real margin; rounds 2-3 are statistical ties (<0.4% apart).\n"
        "Averaged: v2.1 23.45ms vs Paper 24.02ms — a 2.4% gap, deep inside the ~9% noise band (#17).",
        color=INK_SECONDARY, fontsize=9, pad=12, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    ax.legend(frameon=False, loc="upper right", fontsize=9.5, labelcolor=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0, 1, 0.87))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion_concurrency_trace(out_path):
    with (HERE / "orion_concurrency_trace.csv").open() as f:
        rows = list(csv.DictReader(f))

    x = [int(r["chunk_index"]) for r in rows]
    y = [float(r["latency_ms"]) for r in rows]

    fig, ax = plt.subplots(figsize=(11, 5))
    ax.plot(x, y, color=SERIES[0], linewidth=1.1, zorder=3)
    ax.set_yscale("log")
    ax.set_xlabel("chunk index (submission order, row-major across a 16x16 region)")
    ax.set_ylabel("per-chunk latency (ms, log scale)")
    fig.suptitle(
        "Where the CPU curve actually came from: Orion's own scheduler never exceeded 1 in flight",
        color=INK_PRIMARY, fontsize=13, y=0.99,
    )
    ax.set_title(
        "Every telemetry sample this whole run reads inFlight=1 — the declining latency is vanilla's own\n"
        "per-chunk neighbor fan-out shrinking as later requests find their radius-8 halo already resident,\n"
        "not Orion's area-lock scheduler doing anything.",
        color=INK_SECONDARY, fontsize=8.5, pad=10, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    fig.tight_layout(rect=(0, 0, 1, 0.86))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_cpu_traces(out_path):
    runs = [
        ("orion2_1_cpu_trace.csv", "Orion v2.1", SERIES[0]),
        ("orion2_2_cpu_trace.csv", "Orion v2.2", SERIES[1]),
        ("orion3_cpu_trace.csv", "Orion v3 (patched)", SERIES[2]),
    ]

    fig, axes = plt.subplots(3, 1, figsize=(11, 9), sharex=True, sharey=True)
    for ax, (fname, label, color) in zip(axes, runs):
        with (HERE / fname).open() as f:
            rows = list(csv.DictReader(f))
        x = [float(r["t_seconds"]) for r in rows]
        y = [float(r["cpu_pct"]) for r in rows]
        ax.plot(x, y, color=color, linewidth=1.0, zorder=3)
        ax.axhline(700, color=BASELINE, linewidth=0.8, linestyle="--", zorder=2)
        ax.set_ylabel("java process CPU%")
        ax.set_title(label, color=INK_SECONDARY, fontsize=10, loc="left", pad=4)
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)

    axes[-1].set_xlabel("wall-clock seconds since launch")
    fig.suptitle(
        "CPU usage over time: Orion v2.1 vs v2.2 vs v3, champion config (7 workers, 8 cores, tile 5)",
        color=INK_PRIMARY, fontsize=13, y=0.995,
    )
    fig.text(
        0.01, 0.965,
        "Per-process %CPU from /proc/<pid>/stat, sampled at 2Hz (benching.md SOP, #51). "
        "Dashed line = 700% (all 7 workers pegged). Tail-off is JVM exit, not workload.",
        color=INK_SECONDARY, fontsize=8.5,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_emspc_integration_progress(out_path):
    with (HERE / "leaderboard_entries.csv").open() as f:
        board_rows = list(csv.DictReader(f))

    def emspc(row):
        return float(row["total_ms"]) / float(row["chunks"])

    def finding_num(row):
        return int(row["finding"].lstrip("#"))

    # Our own arc: one bar per scheduler generation, favoring each one's
    # latest result. Ties on finding number break to whichever row comes
    # later in the file (later within the same finding write-up).
    # "Orion v3" and "Orion v3 (patched)" are the same scheduler in two
    # spellings across findings — only the patched build ever completes.
    stages = [
        ("Mosaic", ["Mosaic"]),
        ("Orion v1", ["Orion v1"]),
        ("Orion v2", ["Orion v2"]),
        ("Orion v2.1", ["Orion v2.1"]),
        ("Orion v2.2", ["Orion v2.2"]),
        ("Orion v3 (patched)", ["Orion v3", "Orion v3 (patched)"]),
        ("Orion v4", ["Orion v4"]),
        ("Orion v5", ["Orion v5"]),
        ("Orion v5.1", ["Orion v5.1"]),
        ("Orion v5.2", ["Orion v5.2"]),
        ("Orion v5.3", ["Orion v5.3"]),
        ("Orion v5.4", ["Orion v5.4"]),
        ("Orion v5.5", ["Orion v5.5"]),
    ]
    progress = []
    for label, engine_names in stages:
        candidates = [(i, r) for i, r in enumerate(board_rows) if r["engine"] in engine_names and r["chunks"] == "6400"]
        idx, latest = max(candidates, key=lambda ir: (finding_num(ir[1]), ir[0]))
        progress.append((label, emspc(latest), finding_num(latest)))

    # Real servers: each one's single best (lowest) eMSPC on record, any run.
    servers = []
    for name in ["Paper", "Leaf"]:
        candidates = [r for r in board_rows if r["engine"] == name]
        best = min(candidates, key=emspc)
        servers.append((name, emspc(best), finding_num(best), int(best["chunks"])))

    # Ordinal blue ramp (dataviz skill palette.md): our own progression is
    # genuinely ordered (older -> newer integration).
    ramp = [
        to_hex(c) for c in
        LinearSegmentedColormap.from_list("ordinal", ["#d5e7fa", "#1455a4"])(np.linspace(0, 1, len(progress)))
    ]
    server_colors = [SERIES[1], SERIES[2]]  # categorical slots (orange, aqua) = Paper, Leaf

    labels = [s for s, _, _ in progress] + [f"{s}\n(best result)" for s, _, _, c in servers]
    values = [v for _, v, _ in progress] + [v for _, v, _, _ in servers]
    findings = [f for _, _, f in progress] + [f for _, _, f, _ in servers]
    colors = ramp + server_colors

    fig, ax = plt.subplots(figsize=(14, 6))
    x = list(range(len(labels)))
    bars = ax.bar(x, values, color=colors, width=0.6, zorder=3)
    ax.axvline(len(progress) - 0.5, color=BASELINE, linewidth=1, linestyle=":", zorder=2)

    for bar, val, fnum in zip(bars, values, findings):
        ax.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.1f}\n#{fnum}",
                 ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY, linespacing=1.3)

    ax.set_ylabel("effective MSPC (total_ms / chunks, ms/chunk) — lower is better")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=8.5, rotation=22, ha="right")
    ax.set_ylim(0, max(values) * 1.22)

    fig.suptitle(
        f"eMSPC has fallen {(1 - progress[-1][1] / progress[0][1]) * 100:.0f}% since the mosaic — {progress[-1][0]} is the newest measured stage",
        color=INK_PRIMARY, fontsize=13, y=0.96,
    )
    ax.set_title(
        "Each WorldgenD bar is that scheduler's latest champion-scale (6400-chunk) result (finding # labeled);\n"
        "Paper/Leaf bars are each server's single best result on record — both from #19's larger 58081-chunk\n"
        "sustained run, not the same scale as the WorldgenD bars. This box's own run-to-run noise is ~9% (#16/#17).",
        color=INK_SECONDARY, fontsize=8.5, pad=10, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)

    legend_handles = [
        plt.Rectangle((0, 0), 1, 1, color=ramp[-1], label="WorldgenD (own schedulers, oldest -> newest)"),
        plt.Rectangle((0, 0), 1, 1, color=server_colors[0], label="Paper (best result on record)"),
        plt.Rectangle((0, 0), 1, 1, color=server_colors[1], label="Leaf (best result on record)"),
    ]
    ax.legend(handles=legend_handles, frameon=False, loc="upper right", fontsize=9, labelcolor=INK_SECONDARY)

    fig.tight_layout()
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_orion_v4_arc(orion_rows_by_config, out_path):
    # Orion v4's own arc (#59-#61): baseline -> structure-gen fix -> DFC attempts,
    # each bar one specific champion-scale run, in the order they actually happened.
    stages = [
        ("orion3 baseline\n(no patches)", "orion4_59_baseline_no_patches", "#59"),
        ("orion4\n(structure fix only)", "orion4_59_structure_patch_only", "#59"),
        ("orion4 + DFC\n(hashmap cache)", "orion4_59_with_dfc", "#59"),
        ("orion4 + DFC\n(field cache,\nbespoke classes)", "orion4_60_dfc_field_cache", "#60"),
        ("orion4 + DFC\n(interpreter,\nrun 1)", "orion4_61_dfc_interpreted", "#61"),
        ("orion4 + DFC\n(interpreter,\nrun 2)", "orion4_61_dfc_interpreted_run2", "#61"),
    ]
    labels = [s[0] for s in stages]
    rows = [orion_rows_by_config[s[1]] for s in stages]
    findings = [s[2] for s in stages]
    values = [float(r["total_ms"]) / float(r["chunks"]) for r in rows]

    baseline_emspc = values[1]  # orion4, structure fix only, DFC off — what DFC is measured against

    # Regression bars in warm reds, the fixed/replicated bars back in the ordinal
    # blue ramp #61's own text calls "parity" — color itself tells the arc's shape.
    colors = [BASELINE, SERIES[2], "#c0392b", "#e0685a", SERIES[0], "#5598e7"]

    fig, ax = plt.subplots(figsize=(11, 6))
    x = list(range(len(labels)))
    bars = ax.bar(x, values, color=colors, width=0.62, zorder=3)
    ax.axhline(baseline_emspc, color=INK_MUTED, linewidth=1, linestyle="--", zorder=2)
    ax.text(len(labels) - 0.4, baseline_emspc, f"orion4-no-DFC: {baseline_emspc:.2f}",
            ha="right", va="bottom", fontsize=8.5, color=INK_MUTED)

    for bar, val, fnum in zip(bars, values, findings):
        pct = (val / baseline_emspc - 1) * 100
        pct_label = f"{pct:+.1f}%" if bar is not bars[1] else "baseline"
        ax.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.2f}\n{pct_label}\n{fnum}",
                ha="center", va="bottom", fontsize=8, color=INK_SECONDARY, linespacing=1.35)

    ax.set_ylabel("effective MSPC (total_ms / chunks, ms/chunk) — lower is better")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=8.5)
    ax.set_ylim(0, max(values) * 1.28)

    fig.suptitle(
        "Orion v4's DFC arc: two regressions, then a fix that lands at parity",
        color=INK_PRIMARY, fontsize=13, y=0.97,
    )
    ax.set_title(
        "All champion scale (9216 chunks, 16GB pretouched, ParallelGC, 7 workers), origin-centered. Percentages are\n"
        "vs orion4 with the structure-gen fix alone (DFC off) — the dashed line. Both interpreter runs land inside\n"
        "this box's own ~9% run-to-run noise band (#16/#17): parity, not a confirmed win.",
        color=INK_SECONDARY, fontsize=8.5, pad=10, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)

    fig.tight_layout()
    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_orion_v5(orion_rows_by_config, out_path):
    # #62: interleaved champion A/B (v4, v5, v4, v5) on the left, one CPU trace per engine on the right.
    runs = [
        ("orion4\nrun 1", "orion5_62_v4_control_r1", SERIES[1]),
        ("orion5\nrun 1", "orion5_62_v5_r1", SERIES[2]),
        ("orion4\nrun 2", "orion5_62_v4_control_r2", SERIES[1]),
        ("orion5\nrun 2", "orion5_62_v5_r2", SERIES[2]),
    ]
    values = [float(orion_rows_by_config[c]["total_ms"]) / float(orion_rows_by_config[c]["chunks"]) for _, c, _ in runs]
    v4_mean = (values[0] + values[2]) / 2

    fig, (left, right) = plt.subplots(1, 2, figsize=(13, 5.6), gridspec_kw={"width_ratios": [1, 1.6]})
    x = list(range(len(runs)))
    bars = left.bar(x, values, color=[r[2] for r in runs], width=0.62, zorder=3)
    for bar, val in zip(bars, values):
        left.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.2f}\n{(val / v4_mean - 1) * 100:+.1f}%",
                  ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)
    left.set_xticks(x)
    left.set_xticklabels([r[0] for r in runs], fontsize=9)
    left.set_ylabel("effective MSPC (total_ms / chunks) — lower is better")
    left.set_ylim(0, max(values) * 1.25)
    left.set_title("Interleaved champion runs, % vs orion4 mean", color=INK_SECONDARY, fontsize=9.5, loc="left")

    for fname, label, color in [("orion4_62_cpu_trace.csv", "orion4", SERIES[1]), ("orion5_62_cpu_trace.csv", "orion5", SERIES[2])]:
        with (HERE / fname).open() as f:
            trace = list(csv.DictReader(f))
        right.plot([float(r["t_seconds"]) for r in trace], [float(r["cpu_pct"]) for r in trace],
                   color=color, linewidth=1.0, label=label, zorder=3)
    right.axhline(800, color=BASELINE, linewidth=0.8, linestyle="--", zorder=2)
    right.set_xlabel("wall-clock seconds since launch (includes ~20s server boot)")
    right.set_ylabel("java process CPU%")
    right.set_title("CPU over time, run 2 of each (dashed = all 8 cores)", color=INK_SECONDARY, fontsize=9.5, loc="left")
    right.legend(frameon=False, fontsize=9)

    for ax in (left, right):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)

    fig.suptitle(
        "Orion v5: parallel chunk steps remove vanilla's serial worldgen lane",
        color=INK_PRIMARY, fontsize=13, y=0.99,
    )
    fig.text(
        0.01, 0.925,
        "9216 chunks (tile 6, origin), 16GB pretouched, ParallelGC, 7 workers. orion4 = reentrancy + structure-gen patches; "
        "orion5 adds -Dorion.patchParallelSteps=true and the v5 admission window.",
        color=INK_SECONDARY, fontsize=8.5,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.91))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_determinism65(orion_rows_by_config, out_path):
    # #65: left = chunks that differ between two runs per mode (log scale, 0 drawn at the floor); right = tile-6 cost.
    with (HERE / "determinism65_pairs.csv").open() as f:
        pairs = list(csv.DictReader(f))
    groups = [
        ("same target, repeat run", "shift100", ["v5", "closure", "region+light", "closure+light"]),
        ("overlapping targets (100 vs 116)", "shift100 vs 116", ["v5", "region+light", "closure+light"]),
    ]
    colors = {"v5": SERIES[1], "closure": SERIES[3], "region+light": SERIES[4], "closure+light": SERIES[2]}

    fig, (left, right) = plt.subplots(1, 2, figsize=(13, 5.6), gridspec_kw={"width_ratios": [1.5, 1]})
    x, labels, floor = 0, [], 0.5
    for group_label, target, modes in groups:
        start = x
        for mode in modes:
            rows = [r for r in pairs if r["target"] == target and r["mode"] == mode]
            pcts = [float(r["pct_elsewhere"]) for r in rows]
            mean = sum(pcts) / len(pcts)
            left.bar(x, max(mean, floor / 10), color=colors[mode], width=0.7, zorder=3)
            text = "0 chunks" if mean == 0 else f"{mean:.2f}%"
            left.text(x, max(mean, floor / 10) * 1.15, text, ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)
            labels.append((x, mode))
            x += 1
        left.text((start + x - 1) / 2, 160, group_label, ha="center", fontsize=9, color=INK_PRIMARY)
        x += 0.8
    left.set_yscale("log")
    left.set_ylim(floor / 10, 250)
    left.set_xticks([p for p, _ in labels])
    left.set_xticklabels([m for _, m in labels], fontsize=8.5)
    left.set_ylabel("% of chunks with any block different (positional hash)")
    left.set_title("Drift between runs, tile 3 (2304 chunks), spawn chunks excluded", color=INK_SECONDARY, fontsize=9.5, loc="left")

    modes = [("v5", "base", SERIES[1]), ("region+light", "region", SERIES[4]), ("closure+light", "closure", SERIES[2])]
    means = []
    for i, (label, key, color) in enumerate(modes):
        vals = [float(orion_rows_by_config[f"det65_t6_{key}_r{r}"]["total_ms"]) / float(orion_rows_by_config[f"det65_t6_{key}_r{r}"]["chunks"])
                for r in (1, 2, 3)]
        mean = sum(vals) / len(vals)
        means.append(mean)
        right.bar(i, mean, color=color, width=0.62, zorder=3)
        right.scatter([i] * len(vals), vals, color=INK_PRIMARY, s=12, zorder=4)
        right.text(i, max(vals) * 1.02, f"{mean:.2f}\n{(mean / means[0] - 1) * 100:+.1f}%", ha="center", va="bottom",
                   fontsize=8.5, color=INK_SECONDARY)
    right.set_xticks(range(len(modes)))
    right.set_xticklabels([m[0] for m in modes], fontsize=9)
    right.set_ylabel("effective MSPC (total_ms / chunks) — lower is better")
    right.set_ylim(0, max(means) * 1.35)
    right.set_title("Cost at tile 6 (9216 chunks), 3 rotating rounds", color=INK_SECONDARY, fontsize=9.5, loc="left")

    for ax in (left, right):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for spine in ("top", "right"):
            ax.spines[spine].set_visible(False)
        ax.spines["left"].set_color(BASELINE)
        ax.spines["bottom"].set_color(BASELINE)

    fig.suptitle("#65: deterministic Orion v5 — ordered FEATURES plus an unlit light view", color=INK_PRIMARY, fontsize=13, y=0.99)
    fig.text(
        0.01, 0.925,
        "region = order FEATURES within the fill; closure = also generate lower-key neighbors outside it; +light = WorldGenRegion "
        "answers light reads as an unlit column.",
        color=INK_SECONDARY, fontsize=8.5,
    )
    fig.tight_layout(rect=(0, 0, 1, 0.91))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_dragrace3(rows, out_path, title="#63: drag race vs Orion v5 — interleaved, rotating order, 3 rounds",
                   subtitle=None, reference="Paper"):
    # #63: interleaved, rotating-order drag race. Bars are per-engine means, dots are the individual rounds.
    colors = {
        "Orion v5": "#0f9d8a", "Orion v4": "#c0392b", "Paper": "#eb6834",
        "Paper (7 workers)": "#a8471d", "Leaf": "#e87ba4", "Leaf-on-crack": "#b6547a",
        "Leaf (7 workers)": "#c2577f", "Leaf-on-crack (7 workers)": "#8d3a5c",
    }
    by_engine = {}
    for r in rows:
        if r["total_ms"].isdigit():
            by_engine.setdefault(r["engine"], []).append(float(r["ms_per_chunk"]))
    engines = sorted(by_engine, key=lambda e: sum(by_engine[e]) / len(by_engine[e]))
    means = [sum(by_engine[e]) / len(by_engine[e]) for e in engines]
    paper_mean = sum(by_engine[reference]) / len(by_engine[reference]) if reference in by_engine else None

    fig, ax = plt.subplots(figsize=(10.5, 5.8))
    x = list(range(len(engines)))
    bars = ax.bar(x, means, color=[colors.get(e, BASELINE) for e in engines], width=0.62, zorder=3, alpha=0.9)
    for xi, engine in zip(x, engines):
        vals = by_engine[engine]
        ax.scatter([xi] * len(vals), vals, color=INK_PRIMARY, s=14, zorder=4)
    for bar, mean, engine in zip(bars, means, engines):
        vs = f"\n{(mean / paper_mean - 1) * 100:+.0f}% vs {reference}" if paper_mean and engine != reference else ""
        ax.text(bar.get_x() + bar.get_width() / 2, max(by_engine[engine]), f"{mean:.2f}{vs}",
                ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY)
    ax.set_xticks(x)
    ax.set_xticklabels(engines, fontsize=9)
    ax.set_ylabel("ms/chunk (total_ms / chunks) — lower is better")
    ax.set_ylim(0, max(max(v) for v in by_engine.values()) * 1.22)
    fig.suptitle(title, color=INK_PRIMARY, fontsize=13, y=0.99)
    ax.set_title(
        subtitle or "Bars = mean of 3 rounds, dots = each round. WorldgenD: 6400 chunks, 7 workers, ParallelGC, no disk writes;\n"
        "servers: Chunky radius 640 (6561 chunks), Aikar G1, Moonrise default 2 workers unless noted, real ticking + saving.",
        color=INK_SECONDARY, fontsize=8.5, pad=10, loc="left",
    )
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for spine in ("top", "right"):
        ax.spines[spine].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion51(out_path):
    import statistics

    results_path = HERE / "orion51_results.csv"
    micro_dir = HERE / "orion51_micro" / "simd66"
    if not results_path.exists() or not micro_dir.exists():
        return
    with results_path.open() as source:
        world = [row for row in csv.DictReader(source) if row["verify"] == "False" and row["tile"] == "5"]
    micro = []
    for path in sorted(micro_dir.glob("*fork*.csv")):
        with path.open() as source:
            micro.extend(dict(row, fork=path.stem.rsplit("fork", 1)[1]) for row in csv.DictReader(source))
    modes = [("vanilla", "Orion v5", SERIES[0]), ("vector", "v5.1 vector", SERIES[2]), ("scalar", "v5.1 scalar", SERIES[1])]
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    lengths = [49, 128, 256, 1024]
    for mode, label, color in modes:
        medians = []
        for index, length in enumerate(lengths):
            samples = [float(row["ns_per_element"]) for row in micro if row["mode"] == mode and int(row["length"]) == length]
            medians.append(statistics.median(samples))
            for fork in ("1", "2", "3"):
                values = [float(row["ns_per_element"]) for row in micro
                          if row["mode"] == mode and int(row["length"]) == length and row["fork"] == fork]
                axes[0].scatter(index, statistics.median(values), color=color, alpha=0.35, s=22)
        axes[0].plot(range(len(lengths)), medians, marker="o", color=color, label=label, linewidth=1.3)
    axes[0].set_xticks(range(len(lengths)), [str(length) for length in lengths])
    axes[0].set_xlabel("Density array length")
    axes[0].set_ylabel("Nanoseconds / element (lower is better)")
    axes[0].set_title("Isolated fillArray: median of 3 forks x 5 samples", fontsize=10)
    axes[0].legend(frameon=False, fontsize=9)
    for index, (mode, label, color) in enumerate(modes):
        samples = [row for row in world if row["mode"] == mode]
        for offset, row in enumerate(samples):
            value = float(row["total_ms"]) / int(row["chunks"])
            x = index + (offset - 1) * 0.13
            axes[1].scatter(x, value, color=color, s=40)
            axes[1].annotate(f"{value:.2f}", (x, value), xytext=(0, 8), textcoords="offset points", ha="center", fontsize=8)
    axes[1].set_xticks(range(len(modes)), [label for _, label, _ in modes])
    axes[1].set_ylabel("Effective milliseconds / chunk")
    axes[1].set_title("6,400 chunks: every run, rotated order", fontsize=10)
    axes[1].set_ylim(bottom=0)
    for ax in axes:
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    fig.suptitle("#66: Orion v5.1 SIMD density batches", fontsize=14, x=0.06, ha="left")
    fig.text(0.06, 0.01, "AMD EPYC Milan / Java 25.0.4. World runs: 16 GiB pretouched, ParallelGC, parallelism 7, in-flight 64. No verification overhead.", fontsize=8, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.04, 1, 0.95))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion52_throughput(out_path):
    import statistics

    with (HERE / "orion52_results.csv").open() as source:
        rows = list(csv.DictReader(source))
    modes = [("control", "Orion v5.1", SERIES[0]), ("optimized", "Orion v5.2", SERIES[2])]
    fig, ax = plt.subplots(figsize=(9, 5.2))
    rounds = [1, 2, 3]
    for mode, label, color in modes:
        values = [float(next(row["emspc"] for row in rows if row["mode"] == mode and int(row["round"]) == round_number)) for round_number in rounds]
        ax.plot(rounds, values, color=color, marker="o", linewidth=1.6, markersize=7, label=label, zorder=3)
        for round_number, value in zip(rounds, values):
            ax.annotate(f"{value:.2f}", (round_number, value), xytext=(0, 8), textcoords="offset points", ha="center", fontsize=8.5, color=INK_SECONDARY)
    control = [float(row["emspc"]) for row in rows if row["mode"] == "control"]
    optimized = [float(row["emspc"]) for row in rows if row["mode"] == "optimized"]
    control_median = statistics.median(control)
    optimized_median = statistics.median(optimized)
    ax.axhline(control_median, color=BASELINE, linewidth=1, linestyle="--", zorder=2)
    ax.text(3.05, control_median, f"v5.1 median {control_median:.2f}", va="center", fontsize=8.5, color=INK_MUTED)
    ax.set_xticks(rounds, ["Round 1\nv5.2 first", "Round 2\nv5.1 first", "Round 3\nv5.2 first"])
    ax.set_ylabel("Effective milliseconds / chunk — lower is better")
    ax.set_ylim(min(control + optimized) - 0.18, max(control + optimized) + 0.24)
    ax.legend(frameon=False, loc="upper left")
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    fig.suptitle("#67: Orion v5.2 Perlin port is throughput-neutral at full scale", fontsize=14, x=0.08, ha="left")
    fig.text(0.08, 0.01, f"6,400 chunks per leg, rotated order, 7 workers. Medians: v5.1 {control_median:.3f}, v5.2 {optimized_median:.3f} ms/chunk (-0.4%).\nMixed pairwise results remain inside the ~9% noise band.", fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.08, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion52_hotpath(out_path):
    with (HERE / "orion52_hot_methods.csv").open() as source:
        rows = list(csv.DictReader(source))
    modes = ["Orion v5.1", "Orion v5.2"]
    components = ["Permutation lookup", "Optimized interpolation", "Noise wrapper"]
    colors = [SERIES[1], SERIES[2], SERIES[0]]
    fig, ax = plt.subplots(figsize=(8.5, 5.2))
    bottoms = [0.0, 0.0]
    for component, color in zip(components, colors):
        values = [sum(float(row["percent"]) for row in rows if row["mode"] == mode and row["component"] == component) for mode in modes]
        bars = ax.bar(modes, values, bottom=bottoms, color=color, width=0.58, label=component, zorder=3)
        for bar, value, bottom in zip(bars, values, bottoms):
            if value:
                ax.text(bar.get_x() + bar.get_width() / 2, bottom + value / 2, f"{value:.2f}%", ha="center", va="center", fontsize=9, color="white")
        bottoms = [bottom + value for bottom, value in zip(bottoms, values)]
    for index, total in enumerate(bottoms):
        ax.text(index, total + 0.3, f"{total:.2f}%", ha="center", fontsize=9, color=INK_SECONDARY)
    ax.set_ylabel("JFR execution samples in ImprovedNoise")
    ax.set_ylim(0, max(bottoms) * 1.22)
    ax.legend(frameon=False, loc="upper right")
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    fig.suptitle("#67: direct permutation lookup removes ImprovedNoise.p() from the profile", fontsize=14, x=0.08, ha="left")
    fig.text(0.08, 0.01, "Matched tile-5 JFR runs, recording delayed 10s and stopped at generation completion.\nCombined hot-path share falls 15.66% → 13.71% (-12.5% relative).", fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.08, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_c1gc69_throughput(out_path):
    import statistics

    with (HERE / "c1gc69_results.csv").open() as source:
        rows = list(csv.DictReader(source))
    modes = [("orion53", "Orion v5.3", SERIES[0]), ("orion54", "Orion v5.4 (C1GC)", SERIES[2])]
    fig, ax = plt.subplots(figsize=(8, 5.2))
    rounds = [1, 2]
    for mode, label, color in modes:
        values = [float(next(row["emspc"] for row in rows if row["mode"] == mode and int(row["round"]) == round_number)) for round_number in rounds]
        ax.plot(rounds, values, color=color, marker="o", linewidth=1.6, markersize=7, label=label, zorder=3)
        for round_number, value in zip(rounds, values):
            ax.annotate(f"{value:.2f}", (round_number, value), xytext=(0, 8), textcoords="offset points", ha="center", fontsize=8.5, color=INK_SECONDARY)
    v53 = [float(row["emspc"]) for row in rows if row["mode"] == "orion53"]
    v54 = [float(row["emspc"]) for row in rows if row["mode"] == "orion54"]
    v53_mean, v54_mean = statistics.mean(v53), statistics.mean(v54)
    ax.set_xticks(rounds, ["Round 1\nv5.4 first", "Round 2\nv5.3 first"])
    ax.set_ylabel("Effective milliseconds / chunk — lower is better")
    ax.set_ylim(min(v53 + v54) - 0.3, max(v53 + v54) + 0.4)
    ax.legend(frameon=False, loc="upper left")
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    fig.suptitle("#69: C1GC has a real champion-scale cost, no benefit at this scale", fontsize=14, x=0.08, ha="left")
    fig.text(0.08, 0.01, f"6,400 chunks per leg, rotated order, 7 workers, 16GB heap. Means: v5.3 {v53_mean:.3f}, v5.4 {v54_mean:.3f} ms/chunk "
                         f"({(v54_mean - v53_mean) / v53_mean * 100:+.1f}%). Both rounds same direction — outside the ~9% noise band.",
             fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.08, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion55_70(out_path):
    import statistics

    with (HERE / "orion55_70_results.csv").open() as source:
        rows = [row for row in csv.DictReader(source) if row["c1gc_code"] == "post_fix"]
    modes = [
        ("v53", "Orion v5.3", SERIES[0]),
        ("v54", "Orion v5.4", SERIES[1]),
        ("v55p0", "v5.5, pressure=0", SERIES[3]),
        ("v55", "Orion v5.5", SERIES[2]),
    ]
    fig, (left, right) = plt.subplots(1, 2, figsize=(11, 5.2), gridspec_kw={"width_ratios": [3, 2]})
    for i, (mode, label, color) in enumerate(modes):
        values = [float(row["emspc"]) for row in rows if row["mode"] == mode and row["tile"] == "5"]
        if not values:
            continue
        left.scatter([i] * len(values), values, s=64, color=color, edgecolor="white", linewidth=2, zorder=3)
        mean = statistics.mean(values)
        left.hlines(mean, i - 0.25, i + 0.25, color=color, linewidth=2, zorder=2)
        left.annotate(f"{mean:.2f}", (i + 0.28, mean), va="center", fontsize=8.5, color=INK_SECONDARY)
    left.set_xticks(range(len(modes)), [label for _, label, _ in modes], fontsize=9)
    left.set_ylabel("Effective milliseconds / chunk — lower is better")
    left.set_title("6,400 chunks (dots = runs, bar = mean)", fontsize=10, loc="left", color=INK_SECONDARY)
    big = [(label, color, [float(row["total_ms"]) / 1000 for row in rows if row["mode"] == mode and row["tile"] == "16"])
           for mode, label, color in modes]
    big = [(label, color, values) for label, color, values in big if values]
    for i, (label, color, values) in enumerate(big):
        mean = statistics.mean(values)
        right.bar(i, mean, width=0.55, color=color, zorder=2)
        right.scatter([i] * len(values), values, s=24, color="white", edgecolor=INK_SECONDARY, linewidth=1, zorder=3)
        right.annotate(f"{mean:.0f}s", (i + 0.3, mean), va="center", fontsize=8.5, color=INK_SECONDARY)
    right.set_xticks(range(len(big)), [label for label, _, _ in big], fontsize=9)
    right.set_ylabel("Total seconds — lower is better")
    right.set_title("65,536 chunks (bar = mean, dots = runs)", fontsize=10, loc="left", color=INK_SECONDARY)
    for ax in (left, right):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    fig.suptitle("#70: v5.5 skips C1GC work until the heap needs it", fontsize=14, x=0.06, ha="left")
    fig.text(0.06, 0.01, "Post-race-fix code only. 7 workers, 16GB ParallelGC heap, rotated order. "
                         "v5.5 never arms at 6,400 chunks; pressure=0 forces v5.4-style reclaim. ~9% noise band.",
             fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def _v57_blocks():
    import re
    blocks = {}
    with (HERE / "orion_results.csv").open(newline="") as source:
        for row in csv.DictReader(source):
            if not row["config"].startswith("v57_") or row["mosaic_tile"] != "5":
                continue
            block = re.sub(r"_(?:[AB]\d+|(?:orion\d+|A56|B57|beard|cand|ctrl|no[a-z]+|5\.\d+)_r\d+|r\d+)$", "", row["config"])
            arm = row["scheduler"]
            ablation = re.search(r"_(no[a-z]+)_r\d+$", row["config"])
            if ablation:
                arm = "orion5.7 " + ablation.group(1)
            blocks.setdefault(block, {}).setdefault(arm, []).append(int(row["total_ms"]))
    return blocks


def plot_orion57_74(out_path):
    import statistics

    blocks = _v57_blocks()
    replications = [
        ("v57_rep1", "replication 1"), ("v57_rep2", "replication 2"),
        ("v57_rep3", "replication 3"), ("v57_ablation", "ablation block"),
        ("v57_inflight32", "in-flight block"), ("v57_beardcull", "Beardifier block"),
    ]
    fig, (left, right) = plt.subplots(1, 2, figsize=(11.5, 5.2), gridspec_kw={"width_ratios": [3, 2]})
    for i, (block, label) in enumerate(replications):
        base = statistics.median(blocks[block]["orion5.6"])
        deltas = [100 * (v / base - 1) for v in blocks[block]["orion5.7"]]
        left.scatter([i] * len(deltas), deltas, s=48, color=SERIES[0], edgecolor="white", linewidth=1.5, zorder=3)
        med = statistics.median(deltas)
        left.hlines(med, i - 0.25, i + 0.25, color=SERIES[0], linewidth=2, zorder=2)
        left.annotate(f"{med:+.1f}%", (i + 0.28, med), va="center", fontsize=8.5, color=INK_SECONDARY)
    left.axhline(0, color=BASELINE, linewidth=1)
    left.axhspan(-3.5, 0, color=GRIDLINE, alpha=0.5, zorder=0)
    left.set_xticks(range(len(replications)), [label for _, label in replications], fontsize=8, rotation=20, ha="right")
    left.set_ylabel("total_ms vs same-block v5.6 median (%) - lower is better")
    left.set_title("Six independent blocks (dots = v5.7 runs, bar = median)", fontsize=10, loc="left", color=INK_SECONDARY)
    abl = blocks["v57_ablation"]
    arms = [("orion5.7", "v5.7 (all on)"), ("orion5.7 noglue", "glue off"), ("orion5.7 nocache", "surface cache off"),
            ("orion5.7 nobatch", "batching off"), ("orion5.7 nolazy", "lazy off"), ("orion5.6", "v5.6")]
    for i, (arm, label) in enumerate(arms):
        values = [v / 1000 for v in abl[arm]]
        color = BASELINE if arm == "orion5.6" else SERIES[0] if arm == "orion5.7" else SERIES[1]
        med = statistics.median(values)
        right.bar(i, med, width=0.6, color=color, zorder=2)
        right.scatter([i] * len(values), values, s=18, color="white", edgecolor=INK_SECONDARY, linewidth=1, zorder=3)
        right.annotate(f"{med:.1f}", (i, med + 0.4), ha="center", fontsize=8, color=INK_SECONDARY)
    right.set_ylim(40, 55)
    right.set_xticks(range(len(arms)), [label for _, label in arms], fontsize=8, rotation=25, ha="right")
    right.set_ylabel("Total seconds - lower is better")
    right.set_title("Leave-one-out ablation (bar = median, dots = runs)", fontsize=10, loc="left", color=INK_SECONDARY)
    for ax in (left, right):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    fig.suptitle("#74: Orion v5.7's cell-batched density fill is ~10-12% faster, bit-exact", fontsize=14, x=0.06, ha="left")
    fig.text(0.06, 0.01, "6,400 chunks, 7 workers, 16GB ParallelGC, rotated order within each block. Shaded band: 0 to -3.5% "
                         "(below the win bar). v5.7 won 30/30 pairs across the six blocks.", fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion57_nulls75(out_path):
    import statistics

    blocks = _v57_blocks()
    experiments = [
        ("exp-mapall-memo", "mapAll memo", "orion5.6", ["v57_mapallmemo_ab1", "v57_mapallmemo_ab2"]),
        ("exp-structure-lane", "structure lane", "orion5.6", ["v57_structlane"]),
        ("exp-lockfree-structures", "lock-free structures", "orion5.6", ["v57_lockfree", "v57_lockfree_rep"]),
        ("exp-surface-biome", "surface + biome", "orion5.6", ["v57_surfbiome_ab1", "v57_surfbiome_ab2", "v57_surfbiome_rep"]),
        ("exp-inflight32", "v5.7 + in-flight 32", "orion5.7", ["v57_inflight32"]),
        ("exp-beard-cull", "v5.7 + Beardifier cull", "orion5.7", ["v57_beardcull"]),
    ]
    fig, ax = plt.subplots(figsize=(10, 5.2))
    for i, (arm, label, control, names) in enumerate(experiments):
        deltas = []
        for name in names:
            base = statistics.median(blocks[name][control])
            deltas += [100 * (v / base - 1) for v in blocks[name][arm]]
        color = SERIES[1] if control == "orion5.6" else SERIES[2]
        ax.scatter([i] * len(deltas), deltas, s=40, color=color, edgecolor="white", linewidth=1.5, zorder=3)
        med = statistics.median(deltas)
        ax.hlines(med, i - 0.25, i + 0.25, color=color, linewidth=2, zorder=2)
        ax.annotate(f"{med:+.1f}% (n={len(deltas)})", (i + 0.28, med), va="center", fontsize=8.5, color=INK_SECONDARY)
    ax.axhline(0, color=BASELINE, linewidth=1)
    ax.axhline(-3.5, color=INK_MUTED, linewidth=1)
    ax.annotate("win bar", (len(experiments) - 0.5, -3.5), va="bottom", ha="right", fontsize=8, color=INK_MUTED)
    ax.set_xticks(range(len(experiments)), [label for _, label, _, _ in experiments], fontsize=9, rotation=15, ha="right")
    ax.set_ylabel("total_ms vs same-block control median (%) - lower is better")
    ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    fig.suptitle("#75: six hypotheses that did not clear the bar", fontsize=14, x=0.06, ha="left")
    fig.text(0.06, 0.005, "Orange: control is v5.6. Green: control is v5.7. All bit-exact, pooled over every block that tested it.\n"
                         "The bar is consistency: surface + biome won only 10/16 pairs and failed its independent block.", fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.08, 1, 0.94))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def _orion57_runs():
    with (HERE / "orion57_runs.csv").open(newline="") as source:
        return list(csv.DictReader(source))


def plot_orion57_profile(out_path):
    with (HERE / "orion57_profile_summary.csv").open(newline="") as source:
        rows = [row for row in csv.DictReader(source) if row["kind"] != "total"]
    fig, axes = plt.subplots(1, 2, figsize=(12, 5.6))
    for ax, kind, title in ((axes[0], "stage", "By chunk step"), (axes[1], "frame", "By hot frame (inclusive)")):
        subset = sorted((r for r in rows if r["kind"] == kind), key=lambda r: float(r["v56_share_pct"]))
        for i, row in enumerate(subset):
            before, after = float(row["v56_share_pct"]), float(row["v57_share_pct"])
            ax.plot([before, after], [i, i], color=GRIDLINE, linewidth=2, zorder=1)
            ax.scatter(before, i, s=64, color=BASELINE, edgecolor=SURFACE, linewidth=2, zorder=2)
            ax.scatter(after, i, s=64, color=SERIES[0], edgecolor=SURFACE, linewidth=2, zorder=3)
            if abs(after - before) >= 2:
                ax.annotate(f"{after - before:+.1f} pt", (max(before, after) + 0.035 * ax.get_xlim()[1] + 0.4, i), va="center", fontsize=8, color=INK_SECONDARY)
        ax.set_yticks(range(len(subset)), [r["name"] for r in subset], fontsize=9)
        ax.set_xlabel("Share of worker CPU samples (%)")
        ax.set_title(title, fontsize=10, loc="left", color=INK_SECONDARY)
        ax.grid(axis="x", color=GRIDLINE, linewidth=0.8, zorder=0)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    axes[0].scatter([], [], s=64, color=BASELINE, label="v5.6")
    axes[0].scatter([], [], s=64, color=SERIES[0], label="v5.7")
    axes[0].legend(frameon=False, loc="lower right", fontsize=9)
    fig.suptitle("#74: where v5.7's worker time went - noise shrinks, the rest grows in share", fontsize=14, x=0.06, ha="left")
    fig.text(0.06, 0.01, "JFR execution samples on Worker-Main threads, one 6,400-chunk run each (20,769 vs 17,400 samples), different sessions. "
                         "v5.6 had 2,069 truncated stacks, so its step shares undercount.", fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 0.93))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion57_cpu_vs_wall(out_path):
    groups = [("v5.6", BASELINE, lambda a: a == "v5.6"), ("v5.7", SERIES[0], lambda a: a == "v5.7"),
              ("v5.7, one piece off", SERIES[1], lambda a: a.endswith(" off")),
              ("experiment", SERIES[2], lambda a: a.startswith("exp-"))]
    rows = [r for r in _orion57_runs() if r["worker_cpu_s"] and r["block"] != "deterministic overhead"]
    fig, ax = plt.subplots(figsize=(9, 5.6))
    for label, color, test in groups:
        pts = [(float(r["worker_cpu_s"]), int(r["total_ms"]) / 1000) for r in rows if test(r["arm"])]
        if pts:
            ax.scatter(*zip(*pts), s=56, color=color, edgecolor=SURFACE, linewidth=2, label=f"{label} (n={len(pts)})", zorder=3)
    ax.set_xlabel("Worker CPU-seconds per run")
    ax.set_ylabel("Total seconds - lower is better")
    ax.grid(color=GRIDLINE, linewidth=0.8, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.legend(frameon=False, fontsize=9, loc="upper left")
    fig.suptitle("#74: v5.7's wall-clock win is a CPU win", fontsize=14, x=0.08, ha="left")
    fig.text(0.08, 0.01, "Every 6,400-chunk standard-mode run with a recorded worker CPU total (replications 1-2, ablation, in-flight and Beardifier blocks).",
             fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 0.93))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion57_inflight(out_path):
    import statistics

    rows = [r for r in _orion57_runs() if r["block"] in ("in-flight sweep", "in-flight block")]
    def depth(r):
        if r["arm"] == "exp-inflight32":
            return 32
        if r["arm"] == "v5.7":
            return 16
        return int(r["arm"].rsplit(" ", 1)[1]) if r["arm"].startswith("v5.7 in-flight") else None
    by = {}
    for r in rows:
        d = depth(r)
        if d:
            by.setdefault(d, []).append(r)
    depths = sorted(by)
    fig, (left, right) = plt.subplots(1, 2, figsize=(11, 4.8))
    for d in depths:
        totals = [int(r["total_ms"]) / 1000 for r in by[d]]
        left.scatter([d] * len(totals), totals, s=40, color=SERIES[0], edgecolor=SURFACE, linewidth=1.5, zorder=3)
        left.hlines(statistics.median(totals), d - 1.5, d + 1.5, color=SERIES[0], linewidth=2, zorder=2)
    for key, color, name in (("p50_ms", SERIES[0], "p50"), ("p99_ms", SERIES[1], "p99")):
        meds = [statistics.median(float(r[key]) for r in by[d]) for d in depths]
        right.plot(depths, meds, color=color, linewidth=2, marker="o", markersize=7, zorder=3)
        right.annotate(name, (depths[-1] + 1, meds[-1]), va="center", fontsize=9, color=INK_SECONDARY)
    left.set_ylim(40, 48)
    left.set_ylabel("Total seconds - lower is better")
    left.set_title("Throughput is flat (dots = runs, bar = median)", fontsize=10, loc="left", color=INK_SECONDARY)
    right.set_ylabel("Per-chunk latency, median of runs (ms)")
    right.set_title("Latency grows with depth", fontsize=10, loc="left", color=INK_SECONDARY)
    for ax in (left, right):
        ax.set_xticks(depths)
        ax.set_xlabel("orion.maxinflight on v5.7")
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    fig.suptitle("#75: in-flight depth on v5.7 is a latency dial, not a throughput lever", fontsize=14, x=0.06, ha="left")
    fig.text(0.06, 0.01, "6,400 chunks. 16/24/48 from a rotated n=3 sweep, 16/32 from the n=5 3-arm block (pooled at 16). "
                         "The retracted n=4 probe is excluded.", fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 0.93))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion57_pairs(out_path):
    import re

    blocks = ["replication 1", "replication 2", "replication 3", "ablation block", "in-flight block", "Beardifier block"]
    runs = {}
    for r in _orion57_runs():
        if r["block"] in blocks and r["arm"] in ("v5.6", "v5.7"):
            rnd = int(re.search(r"r(\d+)$", r["run"]).group(1))
            runs.setdefault((r["block"], rnd), {})[r["arm"]] = int(r["total_ms"])
    grid = np.full((len(blocks), 5), np.nan)
    for (block, rnd), arms in runs.items():
        if len(arms) == 2 and rnd <= 5:
            grid[blocks.index(block), rnd - 1] = 100 * (arms["v5.7"] / arms["v5.6"] - 1)
    ramp = LinearSegmentedColormap.from_list("blue", ["#dbe9fa", SERIES[0], "#0b3a75"])
    fig, ax = plt.subplots(figsize=(8.5, 4.6))
    im = ax.imshow(-grid, cmap=ramp, vmin=0, vmax=16, aspect="auto")
    for i in range(len(blocks)):
        for j in range(5):
            if not np.isnan(grid[i, j]):
                ax.text(j, i, f"{grid[i, j]:+.1f}%", ha="center", va="center", fontsize=9,
                        color=SURFACE if -grid[i, j] > 8 else INK_PRIMARY)
    ax.set_xticks(range(5), [f"pair {k}" for k in range(1, 6)], fontsize=9)
    ax.set_yticks(range(len(blocks)), blocks, fontsize=9)
    ax.tick_params(length=0)
    for side in ax.spines.values():
        side.set_visible(False)
    ax.set_xticks(np.arange(-0.5, 5), minor=True)
    ax.set_yticks(np.arange(-0.5, len(blocks)), minor=True)
    ax.grid(which="minor", color=SURFACE, linewidth=2)
    bar = fig.colorbar(im, ax=ax, fraction=0.04, pad=0.02)
    bar.set_label("v5.7 faster than its paired v5.6 run (%)", fontsize=9)
    bar.outline.set_visible(False)
    fig.suptitle("#74: all 30 rotated pairs, v5.7 vs v5.6", fontsize=14, x=0.04, ha="left")
    fig.text(0.04, 0.01, f"Each cell: one v5.7 run vs the v5.6 run of the same round in the same block. "
             f"Range {np.nanmax(grid):+.1f}% to {np.nanmin(grid):+.1f}%; no pair crosses zero.",
             fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.05, 1, 0.93))
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def plot_orion57_65k(out_path):
    with (HERE / "orion57_65k.csv").open(newline="") as source:
        rows = list(csv.DictReader(source))
    colors = {"v5.6": BASELINE, "v5.7": SERIES[0]}
    labels = [f"run {r['run']}\n{r['version']}" for r in rows]
    fig, (left, right) = plt.subplots(1, 2, figsize=(11.5, 5), gridspec_kw={"width_ratios": [3, 2]})
    for i, r in enumerate(rows):
        total = int(r["total_ms"]) / 1000
        gc = float(r["full_gc_pause_s"]) + float(r["young_gc_pause_s"])
        left.bar(i, total - gc, width=0.6, color=colors[r["version"]], zorder=2)
        left.bar(i, gc, width=0.6, bottom=total - gc + 1.5, color=SERIES[1], zorder=2)
        left.annotate(f"{total:.0f}s\n{int(r['total_ms']) / 65536:.2f} eMSPC", (i, total + 4), ha="center", fontsize=8.5, color=INK_SECONDARY)
    from matplotlib.patches import Patch
    handles = [Patch(color=BASELINE, label="v5.6, outside GC pauses"), Patch(color=SERIES[0], label="v5.7, outside GC pauses"),
               Patch(color=SERIES[1], label="GC pauses (young + full)")]
    left.set_xticks(range(len(rows)), labels, fontsize=9)
    left.set_ylim(0, 600)
    left.set_ylabel("Total seconds - lower is better")
    left.set_title("Run order A-B-B-A; the GC slice is the same in both versions", fontsize=10, loc="left", color=INK_SECONDARY)
    left.legend(handles=handles, frameon=False, fontsize=8.5, loc="lower center", ncol=3, bbox_to_anchor=(0.5, -0.27))
    for key, color, name in (("p50_ms", SERIES[0], "p50"), ("p99_ms", SERIES[1], "p99")):
        for version, x in (("v5.6", 0), ("v5.7", 1)):
            vals = [float(r[key]) for r in rows if r["version"] == version]
            right.scatter([x] * len(vals), vals, s=48, color=color, edgecolor=SURFACE, linewidth=1.5, zorder=3)
        means = [sum(float(r[key]) for r in rows if r["version"] == v) / 2 for v in ("v5.6", "v5.7")]
        right.plot([0, 1], means, color=color, linewidth=2, zorder=2)
        right.annotate(f"{name} {100 * (means[1] / means[0] - 1):+.0f}%", (1.08, means[1]), va="center", fontsize=9, color=INK_SECONDARY)
    right.set_xticks([0, 1], ["v5.6", "v5.7"])
    right.set_xlim(-0.3, 1.5)
    right.set_ylim(0, 1050)
    right.set_ylabel("Per-chunk latency (ms)")
    right.set_title("Latency (dots = runs, line = mean)", fontsize=10, loc="left", color=INK_SECONDARY)
    for ax in (left, right):
        ax.grid(axis="y", color=GRIDLINE, linewidth=0.8, zorder=0)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    fig.suptitle("#74: v5.7 at 65,536 chunks is 7.6% faster; fixed GC cost dilutes the 10-12% compute win", fontsize=13, x=0.05, ha="left")
    fig.text(0.05, 0.01, "Tile 16, 7 workers, 16GB ParallelGC, C1GC armed in every run (62,464 chunks persisted). n=2 per arm: supporting evidence.",
             fontsize=8.5, color=INK_SECONDARY)
    fig.tight_layout(rect=(0, 0.04, 1, 0.93))
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

    with (HERE / "orion_results.csv").open() as f:
        wc_rows = [r for r in csv.DictReader(f) if r["config"].startswith("orion3_54_wc")]
    plot_percentiles(
        wc_rows, HERE / "orion3_waitceiling_percentiles.png",
        title="MSPC across claimOrWait's wait ceiling (#54)",
        subtitle="Lower is better. Same champion config, only orion.waitceilingms changes — a tail-latency dial, not a throughput lever.",
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

    with (HERE / "dragrace2_results.csv").open() as f:
        dragrace2_rows = list(csv.DictReader(f))
    plot_drag_race(
        dragrace2_rows, HERE / "dragrace2_summary.png",
        caption=(
            "#31: same-session rerun with Orion v2.1 in place of headless WorldgenD — WorldgenD did 6400\n"
            "chunks, Paper/Leaf/Leaf-crack did 6561 (Chunky radius-640 inclusive-center square), same as #18"
        ),
    )

    with (HERE / "interleaved_results.csv").open() as f:
        interleaved_rows = list(csv.DictReader(f))
    plot_interleaved_comparison(interleaved_rows, HERE / "interleaved_summary.png")

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

    with (HERE / "orion_results.csv").open() as f:
        all_orion_rows = list(csv.DictReader(f))
    orion_rows_by_config = {r["config"]: r for r in all_orion_rows}
    # Champion comparison charts stay fixed to the original 5 configs — the #27/#28
    # backoff rows are a different story (did a fix help?), charted separately below.
    champion_configs = ["mosaic_champion_fresh", "orion_champion", "orion2_champion", "mosaic_7w", "orion2_7w"]
    orion_rows = [orion_rows_by_config[c] for c in champion_configs]
    plot_percentiles(
        orion_rows, HERE / "orion_percentiles.png",
        title="MSPC: mosaic vs Orion v1 vs Orion v2, champion scale (6400 chunks)",
        subtitle="Lower is better per-chunk — but see orion_summary.png: v2's higher MSPC buys a lower total time.",
    )
    plot_gc_summary(
        orion_rows, HERE / "orion_summary.png",
        caption="v1 loses on both; v2 trades latency for ~24-27% less total time — and unlike the mosaic, v2 actually gets faster from 4->7 workers",
    )

    backoff_configs = ["orion2_champion_fresh", "orion2_backoff", "orion2_backoff_completions", "orion2_1_champion"]
    backoff_rows = [orion_rows_by_config[c] for c in backoff_configs]
    plot_gc_summary(
        backoff_rows, HERE / "orion2_backoff_summary.png",
        caption="#28/#29 (poll-gating) went nowhere; #30 (spatial index, targeting the actual dominant cost) is a real ~10.5% win",
    )

    with (HERE / "orion2_jfr_breakdown.csv").open() as f:
        breakdown_rows = list(csv.DictReader(f))
    plot_cpu_breakdown(breakdown_rows, HERE / "orion2_cpu_breakdown.png")

    with (HERE / "orion_call_timing.csv").open() as f:
        call_rows = list(csv.DictReader(f))
    plot_percentiles(
        call_rows, HERE / "orion_call_timing_percentiles.png",
        title="getChunkFuture.call() itself blocks — identical in mosaic and Orion",
        subtitle="Lower is better. Same reflective call, same JVM, same tile=1 config — this is what's actually serializing dispatch.",
    )

    plot_orion_concurrency_trace(HERE / "orion_concurrency_trace.png")
    plot_worker_scaling(orion_rows_by_config, HERE / "orion_worker_scaling.png")
    plot_scatter_order_comparison(orion_rows_by_config, HERE / "orion2_2_scatter_order.png")

    tile6_configs = ["orion2_1_7w_tile6", "orion2_2_7w_tile6"]
    tile6_rows = [orion_rows_by_config[c] for c in tile6_configs]
    plot_percentiles(
        tile6_rows, HERE / "orion_tile6_percentiles.png",
        title="MSPC: v2.1 vs v2.2 at tile 6 (9216 chunks, 7 workers, #39)",
        subtitle="Lower is better. Same-tile rerun of #35's comparison — scatter-order still trades a lower p25/p50/max for a higher p99.",
    )
    plot_gc_summary(
        tile6_rows, HERE / "orion_tile6_summary.png",
        caption="#39: same tile (6) for both — v2.1 and v2.2 are a ~2% wash on total time, v2.2 still wins median latency",
    )

    plot_cpu_traces(HERE / "orion_cpu_traces.png")

    plot_emspc_integration_progress(HERE / "emspc_integration_progress.png")

    plot_orion_v4_arc(orion_rows_by_config, HERE / "orion4_dfc_arc.png")

    plot_orion_v5(orion_rows_by_config, HERE / "orion5_parallel_steps.png")

    with (HERE / "dragrace3_results.csv").open() as f:
        plot_dragrace3(list(csv.DictReader(f)), HERE / "dragrace3_summary.png")

    with (HERE / "dragrace4_results.csv").open() as f:
        plot_dragrace3(
            list(csv.DictReader(f)), HERE / "dragrace4_summary.png",
            title="#64: every Moonrise server at 7 workers vs Orion v5 — interleaved, rotating order, 3 rounds",
            subtitle=("Bars = mean of 3 rounds, dots = each round. All servers: chunk-system.worker-threads=7 (log-confirmed), Chunky radius 640\n"
                      "(6561 chunks), Aikar G1, ticking + saving. Orion v5: 6400 chunks, 7 workers, ParallelGC, no disk writes."),
            reference="Paper (7 workers)",
        )

    plot_determinism65(orion_rows_by_config, HERE / "determinism65.png")

    plot_orion51(HERE / "orion51_simd.png")

    plot_orion52_throughput(HERE / "orion52_throughput.png")
    plot_orion52_hotpath(HERE / "orion52_hotpath.png")

    plot_c1gc69_throughput(HERE / "c1gc69_throughput.png")
    plot_orion55_70(HERE / "orion55_70_throughput.png")
    plot_orion57_74(HERE / "orion57_74_throughput.png")
    plot_orion57_nulls75(HERE / "orion57_nulls75.png")
    plot_orion57_profile(HERE / "orion57_profile.png")
    plot_orion57_cpu_vs_wall(HERE / "orion57_cpu_vs_wall.png")
    plot_orion57_inflight(HERE / "orion57_inflight.png")
    plot_orion57_pairs(HERE / "orion57_pairs.png")
    plot_orion57_65k(HERE / "orion57_65k.png")

    print(f"Wrote charts to {HERE}")


if __name__ == "__main__":
    main()
