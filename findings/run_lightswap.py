#!/usr/bin/env python3
"""Interleaved A/B of -Dorion.lightSwap arms on orion5.7 at the champion spec (finding #76)."""
import argparse
import csv
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = [
    "-Xms16g", "-Xmx16g", "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dscheduler=orion5.7", "-Dorion.maxinflight=16", "-Dorion.c1gc.pressure=0.3",
]


def gc_stats(path):
    pauses = [float(m) for m in re.findall(r"Pause Young.*? ([\d.]+)ms", path.read_text())]
    return len(pauses), sum(pauses), max(pauses, default=0.0)


def run(label, arm, tile, output):
    flags = BASE + [f"-Dmosaic.tile={tile}", f"-Xlog:gc:file={output / (label + '.gc.log')}:time,uptime,level,tags"]
    if arm != "base":
        flags.append(f"-Dorion.lightSwap={arm}")
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    log_path = output / f"{label}.log"
    started = time.monotonic()
    with log_path.open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *flags], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - started < 1200:
                if result_path.exists():
                    result = result_path.read_text()
                    if result.startswith("THREW:"):
                        raise RuntimeError(result)
                    if "regionFileVersion " in result:
                        break
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited {process.returncode}; see {log_path}")
                time.sleep(0.25)
            else:
                raise TimeoutError(label)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    (output / f"{label}.result").write_text(result)
    summary = re.search(r"scheduler=(\S+) ok=(\d+) failed=(\d+) totalMs=(\d+)", result)
    chunks = (16 * tile) ** 2
    assert summary and int(summary[2]) == chunks and int(summary[3]) == 0, result
    mspc = dict(re.findall(r"(p50|p99)=([\d.]+)", result))
    young, pause_ms, max_pause = gc_stats(output / f"{label}.gc.log")
    light = re.search(r"lightSwap (.+)", result)
    fields = dict(
        label=label, arm=arm, tile=tile, chunks=chunks, total_ms=int(summary[4]),
        mspc_p50=float(mspc["p50"]), mspc_p99=float(mspc["p99"]),
        young_gcs=young, gc_pause_ms=round(pause_ms, 1), gc_max_pause_ms=max_pause,
        peak_old_gen_mb=int(re.search(r"peakOldGenUsedMb=(\d+)", result)[1]),
        light=light[1] if light else "",
    )
    with (output / "results.csv").open("a", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(fields))
        if stream.tell() == 0:
            writer.writeheader()
        writer.writerow(fields)
    print(f"{label}: {fields['total_ms']} ms p99={fields['mspc_p99']} gc={young}x/{pause_ms:.0f}ms {fields['light']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--tile", type=int, default=5)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--arms", default="base,skip")
    args = parser.parse_args()
    arms = args.arms.split(",")
    output = ROOT / "findings" / "lightswap_runs" / args.tag
    output.mkdir(parents=True, exist_ok=False)
    for round_number in range(args.rounds):
        order = arms if round_number % 2 == 0 else arms[::-1]
        for arm in order:
            run(f"{arm}_r{round_number + 1}", arm, args.tile, output)


if __name__ == "__main__":
    main()
