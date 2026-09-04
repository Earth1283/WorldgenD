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
    ]
    progress = []
    for label, engine_names in stages:
        candidates = [(i, r) for i, r in enumerate(board_rows) if r["engine"] in engine_names]
        idx, latest = max(candidates, key=lambda ir: (finding_num(ir[1]), ir[0]))
        progress.append((label, emspc(latest), finding_num(latest)))

    # Real servers: each one's single best (lowest) eMSPC on record, any run.
    servers = []
    for name in ["Paper", "Leaf"]:
        candidates = [r for r in board_rows if r["engine"] == name]
        best = min(candidates, key=emspc)
        servers.append((name, emspc(best), finding_num(best), int(best["chunks"])))

    # Ordinal blue ramp (dataviz skill palette.md, steps 250-550): our own
    # progression is genuinely ordered (older -> newer integration).
    ramp = ["#86b6ef", "#6da7ec", "#5598e7", "#3987e5", "#2a78d6", "#1c5cab"]
    server_colors = [SERIES[1], SERIES[2]]  # categorical slots (orange, aqua) = Paper, Leaf

    labels = [s for s, _, _ in progress] + [f"{s}\n(best result)" for s, _, _, c in servers]
    values = [v for _, v, _ in progress] + [v for _, v, _, _ in servers]
    findings = [f for _, _, f in progress] + [f for _, _, f, _ in servers]
    colors = ramp + server_colors

    fig, ax = plt.subplots(figsize=(11, 6))
    x = list(range(len(labels)))
    bars = ax.bar(x, values, color=colors, width=0.6, zorder=3)
    ax.axvline(len(progress) - 0.5, color=BASELINE, linewidth=1, linestyle=":", zorder=2)

    for bar, val, fnum in zip(bars, values, findings):
        ax.text(bar.get_x() + bar.get_width() / 2, val, f"{val:.1f}\n#{fnum}",
                 ha="center", va="bottom", fontsize=8.5, color=INK_SECONDARY, linespacing=1.3)

    ax.set_ylabel("effective MSPC (total_ms / chunks, ms/chunk) — lower is better")
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=9)
    ax.set_ylim(0, max(values) * 1.22)

    fig.suptitle(
        "eMSPC has fallen ~44% since the mosaic — now ahead of Paper's own best run, still behind Leaf's",
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

    print(f"Wrote charts to {HERE}")


if __name__ == "__main__":
    main()
