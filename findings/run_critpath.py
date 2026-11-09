#!/usr/bin/env python3
"""Critical-path study on orion5.7: traced runs plus CPU-injection arms, interleaved (finding #81).

Arms are separated by ';'. 'base' injects nothing; otherwise the arm is an -Dorion.inject spec,
e.g. 'noise:2000' or 'noise:1000,surface:1000' (microseconds of thread CPU per step).
"""
import argparse
import csv
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEAP = "16g"
BASE = [
    "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dscheduler=orion5.7", "-Dorion.maxinflight=16", "-Dorion.c1gc.pressure=0.3",
]


def run(label, arm, tile, output, trace, extra):
    flags = [f"-Xms{HEAP}", f"-Xmx{HEAP}"] + BASE + extra + [f"-Dmosaic.tile={tile}", f"-Xlog:gc:file={output / (label + '.gc.log')}:time,uptime,level,tags"]
    if trace:
        flags.append(f"-Dorion.trace={output / label}")
    if arm != "base":
        flags.append(f"-Dorion.inject={arm}")
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    log_path = output / f"{label}.log"
    started = time.monotonic()
    with log_path.open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *flags], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - started < 1800:
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
    pauses = [float(m) for m in re.findall(r"Pause.*? ([\d.]+)ms", (output / f"{label}.gc.log").read_text())]
    traced = re.search(r"^trace .*$", result, re.M)
    fields = dict(
        label=label, arm=arm, tile=tile, chunks=chunks, total_ms=int(summary[4]),
        mspc_p50=float(mspc["p50"]), mspc_p99=float(mspc["p99"]),
        gcs=len(pauses), gc_pause_ms=round(sum(pauses), 1),
        trace=traced[0] if traced else "",
    )
    with (output / "results.csv").open("a", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(fields), lineterminator="\n")
        if stream.tell() == 0:
            writer.writeheader()
        writer.writerow(fields)
    print(f"{label}: {fields['total_ms']} ms gc={len(pauses)}x/{sum(pauses):.0f}ms {fields['trace']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--tile", type=int, default=5)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--arms", default="base")
    parser.add_argument("--heap", default="16g", help="the champion spec is 16g; smaller only when the host lacks RAM")
    parser.add_argument("--no-trace", action="store_true")
    parser.add_argument("--extra", default="", help="extra JVM flags, space separated")
    args = parser.parse_args()
    global HEAP
    HEAP = args.heap
    arms = args.arms.split(";")
    output = ROOT / "findings" / "critpath_runs" / args.tag
    output.mkdir(parents=True, exist_ok=False)
    for round_number in range(args.rounds):
        order = arms if round_number % 2 == 0 else arms[::-1]
        for index, arm in enumerate(order):
            name = re.sub(r"[^a-z0-9]+", "-", arm)
            run(f"{name}_r{round_number + 1}", arm, args.tile, output, not args.no_trace, args.extra.split())


if __name__ == "__main__":
    main()
