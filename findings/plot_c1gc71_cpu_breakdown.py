#!/usr/bin/env python3
"""Charts finding #71's JFR CPU breakdown: a pie of where wall-clock time goes
(worldgen compute vs C1GC's three sub-phases vs GC), and a bar of the top leaf
frames inside the worldgen-compute slice. Regenerate after re-profiling:

    python3 findings/plot_c1gc71_cpu_breakdown.py
"""
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).parent

# Reference palette (dataviz skill), light mode, fixed categorical order.
SURFACE = "#fcfcfb"
INK_PRIMARY = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRIDLINE = "#e1e0d9"
BASELINE = "#c3c2b7"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4"]

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


def load_breakdown():
    rows = list(csv.DictReader(open(HERE / "c1gc71_cpu_breakdown.csv")))
    return [(r["category"], float(r["pct_of_wall_clock"])) for r in rows]


def load_leaf_frames(n=10):
    rows = list(csv.DictReader(open(HERE / "c1gc71_worker_leaf_frames.csv")))
    rows = [r for r in rows if r["leaf_frame"] != "Other (long tail)"]
    return [(r["leaf_frame"], float(r["pct_of_worker_total"])) for r in rows[:n]]


def short_name(m):
    # Class.method(...) -> Class.method, dropping package and args.
    m = m.split("(")[0]
    parts = m.split(".")
    return ".".join(parts[-2:])


def plot_pie():
    import math

    data = load_breakdown()
    sizes = [pct for _, pct in data]
    colors = SERIES[: len(data)]

    fig, ax = plt.subplots(figsize=(10, 6))
    wedges, _ = ax.pie(
        sizes, colors=colors, startangle=90, counterclock=False,
        wedgeprops={"linewidth": 2, "edgecolor": SURFACE},
        explode=[0 if s > 5 else 0.05 for s in sizes],
        radius=1.0,
    )

    # the dominant slice gets a direct label; the rest (all <2%, packed into
    # a narrow angular wedge) go in a legend instead of leader lines, which
    # would collide at this size difference.
    for w, (name, pct) in zip(wedges, data):
        if pct > 5:
            ang = math.radians((w.theta2 + w.theta1) / 2)
            x, y = 0.6 * math.cos(ang), 0.6 * math.sin(ang)
            ax.text(x, y, f"{name}\n{pct:.1f}%", ha="center", va="center",
                     fontsize=11, color="white", fontweight="bold")

    handles = [plt.Rectangle((0, 0), 1, 1, fc=c) for c in colors]
    legend_labels = [f"{name} — {pct:.2f}%" for name, pct in data]
    ax.legend(handles, legend_labels, loc="center left", bbox_to_anchor=(1.05, 0.5),
               frameon=False, fontsize=9.5, labelcolor=INK_SECONDARY,
               title="Category", title_fontsize=10)

    fig.suptitle("Where the 647s went: JFR wall-clock breakdown", fontsize=13,
                  color=INK_PRIMARY, x=0.36)
    fig.text(0.36, 0.02, "Orion v5.5, 65,536 chunks (#71). C1GC's three phases +\n"
                          "GC pause sum to ~4.4% — worldgen compute dominates.",
              ha="center", fontsize=9, color=INK_MUTED)
    fig.savefig(HERE / "c1gc71_cpu_breakdown.png", dpi=150, bbox_inches="tight")
    plt.close(fig)


def plot_leaf_bar():
    data = load_leaf_frames(10)
    data.sort(key=lambda r: r[1])
    names = [short_name(m) for m, _ in data]
    pcts = [p for _, p in data]

    fig, ax = plt.subplots(figsize=(9, 5.5))
    bars = ax.barh(names, pcts, color=SERIES[0], zorder=3)
    for bar, p in zip(bars, pcts):
        ax.text(bar.get_width() + 0.08, bar.get_y() + bar.get_height() / 2,
                 f"{p:.2f}%", va="center", fontsize=8.5, color=INK_SECONDARY)

    ax.set_xlabel("% of worldgen-compute execution samples")
    ax.set_title("Top offenders inside the worldgen-compute slice (#71)",
                  fontsize=12.5, color=INK_PRIMARY, pad=12)
    ax.grid(axis="x", color=GRIDLINE, linewidth=0.8, zorder=0)
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.spines["left"].set_color(BASELINE)
    ax.spines["bottom"].set_color(BASELINE)
    fig.tight_layout()
    fig.savefig(HERE / "c1gc71_worker_hotpath.png", dpi=150)
    plt.close(fig)


def main():
    plot_pie()
    plot_leaf_bar()
    print("wrote findings/c1gc71_cpu_breakdown.png and findings/c1gc71_worker_hotpath.png")


if __name__ == "__main__":
    main()
