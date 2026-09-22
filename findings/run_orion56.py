#!/usr/bin/env python3
import argparse
import csv
import re
import subprocess
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE = [
    "-Xms16g", "-Xmx16g", "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC",
    "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
]


def run(label, scheduler, tile, pressure, inflight, output):
    flags = BASE + [f"-Dscheduler={scheduler}", f"-Dmosaic.tile={tile}", f"-Dorion.maxinflight={inflight}",
                    f"-Xlog:gc:file={output / (label + '.gc.log')}:time,uptime,level,tags"]
    if pressure is not None:
        flags.append(f"-Dorion.c1gc.pressure={pressure}")
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
                    if result.startswith(f"scheduler={scheduler} ") and "regionFileVersion " in result:
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
    assert summary and summary[1] == scheduler and int(summary[2]) == (16 * tile) ** 2 and int(summary[3]) == 0, result
    assert "leakedCells=0" in result and "backend=vector" in result, result
    gc = re.search(r"Active GC\(s\): (.+)", log_path.read_text())
    assert gc and "PS MarkSweep" in gc[1], log_path
    c1gc = re.search(r"c1gc (.+)", result)
    mspc = dict(re.findall(r"(p50|p99)=([\d.]+)", result))
    fields = dict(
        label=label, scheduler=scheduler, tile=tile, chunks=int(summary[2]),
        pressure=pressure if pressure is not None else (0.3 if scheduler == "orion5.6" else 0.5),
        max_inflight=inflight, mspc_p50=float(mspc["p50"]), mspc_p99=float(mspc["p99"]),
        total_ms=int(summary[4]), emspc=int(summary[4]) / int(summary[2]),
        gc=gc[1], armed=re.search(r"armed=(\w+)", c1gc[1])[1],
        armed_at=int(re.search(r"armedAtCompletion=(-?\d+)", c1gc[1])[1]),
        persisted=int(re.search(r"persisted=(\d+)", c1gc[1])[1]),
        peak_old_gen_mb=int(re.search(r"peakOldGenUsedMb=(\d+)", c1gc[1])[1]),
        flags=" ".join(flags),
    )
    with (output / "results.csv").open("a", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(fields))
        if stream.tell() == 0:
            writer.writeheader()
        writer.writerow(fields)
    print(f"{label}: {fields['total_ms']} ms, {fields['emspc']:.3f} eMSPC, armed={fields['armed']}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--tile", type=int, default=5)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--pressure", type=float)
    parser.add_argument("--v56-inflight", type=int, default=16)
    parser.add_argument("--only", choices=("orion5.5", "orion5.6"))
    args = parser.parse_args()
    output = ROOT / "findings" / "orion56_runs" / args.tag
    output.mkdir(parents=True, exist_ok=False)
    (output / "environment.txt").write_text(subprocess.check_output(["lscpu"], text=True) + subprocess.run(["java", "-version"], capture_output=True, text=True).stderr)
    for round_number in range(args.rounds):
        order = ("orion5.5", "orion5.6") if round_number % 2 == 0 else ("orion5.6", "orion5.5")
        for scheduler in order:
            if args.only is not None and scheduler != args.only:
                continue
            label = f"{scheduler.replace('.', '')}_r{round_number + 1}"
            inflight = args.v56_inflight if scheduler == "orion5.6" else 64
            run(label, scheduler, args.tile, args.pressure, inflight, output)


if __name__ == "__main__":
    main()
