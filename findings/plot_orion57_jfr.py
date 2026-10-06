#!/usr/bin/env python3
"""Charts for the orion57 JFR breakdown CSVs (run orion57_jfr_breakdown.py first)."""
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
SURFACE, INK, INK2, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9"
BLUE, ORANGE = "#2a78d6", "#eb6834"

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "text.color": INK, "axes.labelcolor": INK2, "xtick.color": MUTED, "ytick.color": INK2,
    "axes.edgecolor": GRID, "font.size": 10, "axes.spines.top": False, "axes.spines.right": False,
})


def rows(name):
    with (HERE / f"orion57_jfr_{name}.csv").open() as stream:
        return list(csv.DictReader(stream))


def short(name):
    return ".".join(name.split(".")[-2:])


def hbar(path, title, labels, values, fmt, xlabel):
    fig, ax = plt.subplots(figsize=(9, 0.38 * len(labels) + 1.4))
    y = range(len(labels))
    ax.barh(y, values, color=BLUE, height=0.62)
    ax.set_yticks(list(y), labels)
    ax.invert_yaxis()
    ax.set_xlabel(xlabel)
    ax.xaxis.grid(True, color=GRID, linewidth=0.6)
    ax.set_axisbelow(True)
    ax.tick_params(length=0)
    for i, v in enumerate(values):
        ax.text(v + max(values) * 0.01, i, fmt(v), va="center", color=INK2, fontsize=9)
    ax.set_xlim(0, max(values) * 1.12)
    ax.set_title(title, loc="left", fontsize=12, color=INK)
    fig.tight_layout()
    fig.savefig(HERE / path, dpi=150)
    plt.close(fig)


def stages():
    data = rows("stages")
    hbar("orion57_jfr_stages.png", "Orion v5.7: worker CPU samples by chunk stage",
         [r["stage"] for r in data], [float(r["share_pct"]) for r in data],
         lambda v: f"{v:.1f}%", "share of worker execution samples (%)")


def hot_frames():
    data = rows("leaf_frames")[:15]
    hbar("orion57_jfr_hot_frames.png", "Orion v5.7: top 15 leaf frames on workers",
         [short(r["method"]) for r in data], [float(r["share_pct"]) for r in data],
         lambda v: f"{v:.1f}%", "share of worker execution samples (%)")


def alloc():
    data = rows("alloc_site")[:12]
    hbar("orion57_jfr_alloc_sites.png", "Orion v5.7: allocation by site (sampled bytes)",
         [short(r["site"]) for r in data], [float(r["share_pct"]) for r in data],
         lambda v: f"{v:.1f}%", "share of sampled allocation weight (%)")


def timeline():
    t = [r for r in rows("timeline") if r["jvm_user"]]
    gc = [r for r in rows("gc") if r["collector"] == "ParallelScavenge" and float(r["t_sec"]) >= 0]
    fig, (top, bottom) = plt.subplots(2, 1, figsize=(9, 5.4), sharex=True,
                                      gridspec_kw={"height_ratios": [3, 2]})
    top.plot([float(r["t_sec"]) for r in t], [100 * float(r["jvm_user"]) for r in t], color=BLUE, linewidth=2)
    top.set_ylabel("JVM user CPU, % of 8 cores")
    top.set_ylim(0, 100)
    top.set_title("Orion v5.7: CPU utilisation and young-GC pauses during the fill", loc="left", fontsize=12)
    bottom.bar([float(r["t_sec"]) for r in gc], [float(r["pause_ms"]) for r in gc],
               width=0.8, color=ORANGE)
    bottom.set_ylabel("scavenge pause (ms)")
    bottom.set_xlabel("seconds since first worker sample")
    for ax in (top, bottom):
        ax.yaxis.grid(True, color=GRID, linewidth=0.6)
        ax.set_axisbelow(True)
        ax.tick_params(length=0)
    fig.tight_layout()
    fig.savefig(HERE / "orion57_jfr_timeline.png", dpi=150)
    plt.close(fig)


if __name__ == "__main__":
    stages()
    hot_frames()
    alloc()
    timeline()
