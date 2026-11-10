#!/usr/bin/env python3
"""Interleaved A/B runs for the server-task drain (Orion v5.8, finding #82).

Arms are 'name=flag flag ...' separated by ';'. Each arm is added to the champion spec, e.g.
  --arms 'v57=-Dscheduler=orion5.7;v58=-Dscheduler=orion5.8'
"""
import argparse
import csv
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = [
    "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dorion.maxinflight=16", "-Dsaveworld=false",
]
GC_LINE = re.compile(r"\[([\d.]+)s\].*Pause (Young|Full) \((.*?)\) (\d+)M->(\d+)M\(\d+M\) ([\d.]+)ms")


def gc_stats(path):
    young, full, peak_live = [], [], 0
    for line in path.read_text().splitlines():
        match = GC_LINE.search(line)
        if not match:
            continue
        (young if match[2] == "Young" else full).append(float(match[6]))
        if match[2] == "Full":
            peak_live = max(peak_live, int(match[5]))
    return young, full, peak_live


def run(label, arm, flags, tile, heap, output, timeout):
    args = [f"-Xms{heap}", f"-Xmx{heap}"] + BASE + flags + [
        f"-Dmosaic.tile={tile}", f"-Xlog:gc:file={output / (label + '.gc.log')}:time,uptime,level,tags",
        f"-Dworldgen.progressfile={output / (label + '.progress')}",
    ]
    shutil.rmtree(ROOT / "servers/.run/headless/dimensions", ignore_errors=True)
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    started = time.monotonic()
    with (output / f"{label}.log").open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *args], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - started < timeout:
                if result_path.exists():
                    result = result_path.read_text()
                    if result.startswith("THREW:"):
                        raise RuntimeError(result)
                    if "regionFileVersion " in result:
                        break
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited {process.returncode}; see {output / (label + '.log')}")
                time.sleep(0.25)
            else:
                raise TimeoutError(label)
            cpu = Path(f"/proc/{process.pid}/stat").read_text().rsplit(")", 1)[1].split()
            cpu_s = (int(cpu[11]) + int(cpu[12])) / 100
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
    young, full, peak_live = gc_stats(output / f"{label}.gc.log")
    pending = re.search(r"serverTasks mode=(\S+) pending=(\d+)", result)
    armed = re.search(r"armedAtCompletion=(-?\d+)", result)
    fields = dict(
        label=label, arm=arm, scheduler=summary[1], tile=tile, chunks=chunks, heap=heap,
        total_ms=int(summary[4]), emspc=round(int(summary[4]) / chunks, 3),
        mspc_p50=float(mspc["p50"]), mspc_p99=float(mspc["p99"]),
        young_gcs=len(young), young_pause_ms=round(sum(young), 1),
        full_gcs=len(full), full_pause_ms=round(sum(full), 1), peak_live_after_full_mb=peak_live,
        jvm_cpu_s=cpu_s, server_tasks_pending=pending[2] if pending else "",
        c1gc_armed_at=armed[1] if armed else "",
    )
    with (output / "results.csv").open("a", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(fields), lineterminator="\n")
        if stream.tell() == 0:
            writer.writeheader()
        writer.writerow(fields)
    print(f"{label}: {fields['total_ms']} ms young={len(young)}x/{sum(young):.0f}ms full={len(full)}x/{sum(full):.0f}ms "
          f"cpu={cpu_s:.0f}s pending={fields['server_tasks_pending']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--arms", required=True)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--first-round", type=int, default=1)
    parser.add_argument("--tile", type=int, default=5)
    parser.add_argument("--heap", default="16g")
    parser.add_argument("--timeout", type=int, default=3600)
    parser.add_argument("--output", default=str(ROOT / "findings/orion58_runs"))
    options = parser.parse_args()
    arms = [(name, flags.split()) for name, flags in (arm.split("=", 1) for arm in options.arms.split(";"))]
    output = Path(options.output)
    output.mkdir(parents=True, exist_ok=True)
    for round_number in range(options.first_round, options.first_round + options.rounds):
        order = arms if round_number % 2 else arms[::-1]
        for name, flags in order:
            run(f"{options.tag}_{name}_r{round_number}", name, flags, options.tile, options.heap, output, options.timeout)


if __name__ == "__main__":
    main()
