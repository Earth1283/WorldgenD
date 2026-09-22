#!/usr/bin/env python3
import csv
import re
from pathlib import Path


HERE = Path(__file__).parent
RUNS = HERE / "orion56_runs"


def metric(pattern, text, default=""):
    match = re.search(pattern, text)
    return match[1] if match else default


def gc_metrics(path):
    if not path.exists():
        return ("", "", "", "")
    data = path.read_text()
    full = [float(x) for x in re.findall(r"Pause Full[^\n]*? ([0-9.]+)ms", data)]
    young = [float(x) for x in re.findall(r"Pause Young[^\n]*? ([0-9.]+)ms", data)]
    return (len(full), round(sum(full), 3), len(young), round(sum(young), 3))


def main():
    rows = []
    for directory in sorted(RUNS.iterdir()):
        if not directory.is_dir():
            continue
        source = {r["label"]: r for r in csv.DictReader((directory / "results.csv").open())}
        for path in sorted(directory.glob("*.result")):
            label = path.stem
            data = path.read_text()
            original = source[label]
            full_count, full_ms, young_count, young_ms = gc_metrics(directory / f"{label}.gc.log")
            rows.append(dict(
                experiment=directory.name,
                variant="rejected_identity_memo" if directory.name == "identity_a" and original["scheduler"] == "orion5.6" else original["scheduler"],
                label=label,
                scheduler=original["scheduler"],
                tile=original["tile"],
                chunks=original["chunks"],
                pressure=original["pressure"],
                max_inflight=original.get("max_inflight", "64"),
                total_ms=original["total_ms"],
                emspc=original["emspc"],
                mspc_p50=metric(r"\bp50=([\d.]+)", data),
                mspc_p99=metric(r"\bp99=([\d.]+)", data),
                armed=original["armed"],
                armed_at=original["armed_at"],
                persisted=original["persisted"],
                peak_old_gen_mb=original["peak_old_gen_mb"],
                full_gc_count=full_count,
                full_gc_ms=full_ms,
                young_gc_count=young_count,
                young_gc_ms=young_ms,
                flags=original["flags"],
            ))
    output = HERE / "orion56_results.csv"
    with output.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {output} ({len(rows)} runs)")


if __name__ == "__main__":
    main()
