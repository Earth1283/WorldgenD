#!/usr/bin/env python3
"""Generates findings/leaderboard.html from leaderboard_entries.csv.

Regenerate after adding a new run:

    python3 findings/generate_leaderboard.py
"""
import csv
import json
from pathlib import Path

HERE = Path(__file__).parent

ENGINE_COLORS = {
    "Orion v3 (patched)": "#8e44ad",
    "Orion v2.2": "#1baf7a",
    "Orion v2.1": "#2a78d6",
    "Orion v2": "#5a9fe8",
    "Orion v1": "#c3c2b7",
    "Mosaic": "#eda100",
    "Paper": "#eb6834",
    "Leaf": "#e87ba4",
    "Leaf-crack": "#b6547a",
}

DISCLAIMER = (
    "This is not a rigorous ranking and isn't trying to be one. Rows come from different "
    "sessions, different box states, and (for two of them) a genuinely different chunk count "
    "and scale entirely — scientific-findings.md #16/#17 already put this box's own "
    "run-to-run noise at ~9%, and #32 exists specifically because block-sequential comparisons "
    "like most of this table can't be trusted at face value. Treat this as a highlight reel, "
    "not a leaderboard you'd cite in a paper. The rigorous version, with all the caveats "
    "intact, is scientific-findings.md #1-#35 and its unhinged sibling "
    "cursed-scientific-advancements.md."
)


def load_entries():
    with (HERE / "leaderboard_entries.csv").open() as f:
        rows = list(csv.DictReader(f))
    entries = []
    for r in rows:
        total_ms = int(r["total_ms"])
        chunks = int(r["chunks"])
        entries.append({
            "engine": r["engine"],
            "run": r["run_label"],
            "msPerChunk": round(total_ms / chunks, 2),
            "totalMs": total_ms,
            "chunks": chunks,
            "finding": r["finding"],
        })
    entries.sort(key=lambda e: e["msPerChunk"])
    return entries


def render(entries):
    data_json = json.dumps(entries)
    colors_json = json.dumps(ENGINE_COLORS)
    engines = sorted({e["engine"] for e in entries})
    engine_options = "\n".join(f'<option value="{e}">{e}</option>' for e in engines)

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>WorldgenD Leaderboard</title>
<style>
  :root {{
    color-scheme: light dark;
    --surface: #fcfcfb; --ink: #16150f; --ink-secondary: #52514e; --ink-muted: #898781;
    --border: #e1e0d9; --row-alt: #f5f4f0; --accent: #2a78d6;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --surface: #16150f; --ink: #f2f1ea; --ink-secondary: #b8b6ac; --ink-muted: #7d7b73;
      --border: #33322b; --row-alt: #1e1d17; --accent: #6fa8ef;
    }}
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; background: var(--surface); color: var(--ink);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    padding: 32px 20px 64px;
  }}
  .wrap {{ max-width: 880px; margin: 0 auto; }}
  h1 {{ font-size: 1.5rem; margin: 0 0 4px; }}
  .subtitle {{ color: var(--ink-secondary); font-size: 0.95rem; margin: 0 0 20px; }}
  .disclaimer {{
    background: var(--row-alt); border: 1px solid var(--border); border-radius: 8px;
    padding: 14px 16px; font-size: 0.85rem; color: var(--ink-secondary); margin-bottom: 20px;
    line-height: 1.5;
  }}
  .controls {{ display: flex; gap: 10px; align-items: center; margin-bottom: 12px; flex-wrap: wrap; }}
  select, input {{
    background: var(--surface); color: var(--ink); border: 1px solid var(--border);
    border-radius: 6px; padding: 6px 10px; font-size: 0.85rem;
  }}
  table {{ width: 100%; border-collapse: collapse; font-size: 0.88rem; }}
  th, td {{ padding: 8px 10px; text-align: left; border-bottom: 1px solid var(--border); }}
  th {{
    cursor: pointer; user-select: none; color: var(--ink-secondary);
    font-weight: 600; white-space: nowrap; position: sticky; top: 0; background: var(--surface);
  }}
  th:hover {{ color: var(--ink); }}
  th .arrow {{ opacity: 0.5; font-size: 0.75em; margin-left: 3px; }}
  tbody tr:nth-child(even) {{ background: var(--row-alt); }}
  tbody tr:hover {{ background: color-mix(in srgb, var(--accent) 10%, transparent); }}
  .rank {{ color: var(--ink-muted); font-variant-numeric: tabular-nums; }}
  .mspc {{ font-variant-numeric: tabular-nums; font-weight: 600; }}
  .chip {{
    display: inline-block; padding: 2px 8px; border-radius: 999px; font-size: 0.78rem;
    font-weight: 600; color: #0b0b0b;
  }}
  .finding {{ color: var(--ink-muted); font-size: 0.82rem; }}
  .finding a {{ color: inherit; }}
  footer {{ margin-top: 24px; color: var(--ink-muted); font-size: 0.78rem; }}
</style>
</head>
<body>
<div class="wrap">
  <h1>The WorldgenD Leaderboard</h1>
  <p class="subtitle">Every scheduler this project has shipped, plus real Paper/Leaf/Leaf-crack, ranked by effective MSPC (total_ms / chunks). One row per individual run — nothing here is averaged.</p>
  <div class="disclaimer">{DISCLAIMER}</div>
  <div class="controls">
    <label for="engineFilter">Engine:</label>
    <select id="engineFilter">
      <option value="">All</option>
      {engine_options}
    </select>
    <input id="search" type="search" placeholder="Filter run label…">
  </div>
  <table id="board">
    <thead>
      <tr>
        <th>#</th>
        <th data-key="msPerChunk">ms/chunk<span class="arrow"></span></th>
        <th data-key="engine">Engine<span class="arrow"></span></th>
        <th data-key="run">Run<span class="arrow"></span></th>
        <th data-key="finding">Finding<span class="arrow"></span></th>
      </tr>
    </thead>
    <tbody></tbody>
  </table>
  <footer>Generated by findings/generate_leaderboard.py from leaderboard_entries.csv. Regenerate after adding a run.</footer>
</div>
<script>
const DATA = {data_json};
const COLORS = {colors_json};
let sortKey = "msPerChunk", sortDir = 1;

function render() {{
  const engine = document.getElementById("engineFilter").value;
  const q = document.getElementById("search").value.toLowerCase();
  let rows = DATA.filter(r => (!engine || r.engine === engine) && r.run.toLowerCase().includes(q));
  rows.sort((a, b) => {{
    const av = a[sortKey], bv = b[sortKey];
    if (typeof av === "number") return (av - bv) * sortDir;
    return String(av).localeCompare(String(bv)) * sortDir;
  }});
  const tbody = document.querySelector("#board tbody");
  tbody.innerHTML = rows.map((r, i) => `
    <tr>
      <td class="rank">${{i + 1}}</td>
      <td class="mspc">${{r.msPerChunk.toFixed(2)}}</td>
      <td><span class="chip" style="background:${{COLORS[r.engine] || "#ccc"}}">${{r.engine}}</span></td>
      <td>${{r.run}} <span style="color:var(--ink-muted)">(${{r.chunks}} chunks)</span></td>
      <td class="finding">${{r.finding}}</td>
    </tr>
  `).join("");
  document.querySelectorAll("th[data-key] .arrow").forEach(a => a.textContent = "");
  const active = document.querySelector(`th[data-key="${{sortKey}}"] .arrow`);
  if (active) active.textContent = sortDir === 1 ? "\\u25b2" : "\\u25bc";
}}

document.querySelectorAll("th[data-key]").forEach(th => {{
  th.addEventListener("click", () => {{
    const key = th.dataset.key;
    if (key === sortKey) sortDir *= -1; else {{ sortKey = key; sortDir = 1; }}
    render();
  }});
}});
document.getElementById("engineFilter").addEventListener("change", render);
document.getElementById("search").addEventListener("input", render);
render();
</script>
</body>
</html>
"""


def main():
    entries = load_entries()
    out = HERE / "leaderboard.html"
    out.write_text(render(entries))
    print(f"Wrote {out} ({len(entries)} entries)")


if __name__ == "__main__":
    main()
