#!/usr/bin/env python3
"""Reproduce finding #66, retaining each run and rejecting incomplete results."""
import argparse
import csv
import fcntl
import gzip
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
FINDINGS = ROOT / "findings"
BASE_FLAGS = ["-Xms16g", "-Xmx16g", "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC",
              "-Dmax.bg.threads=7", "-Dorion.maxinflight=64",
              "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
              "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true"]


def append(path, fields, row):
    exists = path.exists()
    with path.open("a", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
        if not exists:
            writer.writeheader()
        writer.writerow(row)


def run(mode, label, tile, verify, output_dir):
    scheduler = "orion5" if mode == "vanilla" else "orion5.1"
    flags = BASE_FLAGS + [f"-Dscheduler={scheduler}", f"-Dmosaic.tile={tile}"]
    if mode != "vanilla":
        flags += [f"-Dorion.simd={'scalar' if mode == 'scalar' else 'auto'}"]
    histogram = output_dir / f"{label}.hist"
    if verify:
        flags += ["-Dorion.parallelSteps.verify=true", "-Dorion.simd.verify=true",
                  "-Dorion.deterministicFeatures=region", "-Dorion.patchWorldgenLight=true",
                  "-Dorion.targetShift=100", f"-Ddescribe.histogramfile={histogram}"]
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    log_path = output_dir / f"{label}.log"
    print(f"START {label}: {shlex.join(flags)}", flush=True)
    result = ""
    threads = ""
    started = time.monotonic()
    with log_path.open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *flags], cwd=ROOT,
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - started < 480:
                if result_path.exists():
                    current = result_path.read_text()
                    if "THREW:" in current:
                        raise RuntimeError(current)
                    if "parallelSteps " in current and current.endswith("\n"):
                        if mode == "vanilla" or "densitySimd " in current:
                            result = current
                            break
                    if "starting, target=" in current and not threads:
                        sample = subprocess.run(["jcmd", str(process.pid), "Thread.print"], capture_output=True, text=True, timeout=20)
                        threads = sample.stdout
                        (output_dir / f"{label}.threads.gz").write_bytes(gzip.compress(threads.encode(), mtime=0))
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited {process.returncode} without completion: {log_path}")
                time.sleep(0.25)
            if not result:
                raise TimeoutError(label)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            if result_path.exists():
                (output_dir / f"{label}.result").write_text(result_path.read_text())
            agent_log = Path("/tmp/orion_agent_debug.log")
            if agent_log.exists():
                (output_dir / f"{label}.agent.gz").write_bytes(gzip.compress(agent_log.read_bytes(), mtime=0))
    log = log_path.read_text()
    (output_dir / f"{label}.log.gz").write_bytes(gzip.compress(log.encode(), mtime=0))
    log_path.unlink()
    gc = re.search(r"Active GC\(s\): (.+)", log)
    summary = re.search(r"scheduler=(\S+) ok=(\d+) failed=(\d+) totalMs=(\d+)", result)
    assert summary, result
    actual_scheduler, ok, failed, total = summary.groups()
    assert actual_scheduler == scheduler and int(ok) == (16 * tile) ** 2 and int(failed) == 0, result
    assert gc and "PS Scavenge" in gc[1] and "PS MarkSweep" in gc[1], log[:1000]
    workers = len(set(re.findall(r'^"(Worker-Main-\d+)"', threads, re.M)))
    assert workers >= 7, f"Expected at least 7 live workers, found {workers}"
    assert "backgroundPool parallelism=7 " in result, result
    if mode != "vanilla":
        expected_backend = "scalar" if mode == "scalar" else "vector"
        assert f"backend={expected_backend}" in result, result
    if verify:
        assert all(f"{key}=0" in result for key in ["overlapViolations", "orderViolations", "leakedCells", "stuckGates", "kickFailures"]), result
        if mode != "vanilla":
            assert re.search(r"calls=[1-9]\d*", result), result
        (output_dir / f"{label}.hist.gz").write_bytes(gzip.compress(histogram.read_bytes(), mtime=0))
        histogram.unlink()
    percentiles = dict(re.findall(r"(min|p1|p25|p50|p75|p99|max)=([\d.]+)", result))
    assert len(percentiles) == 7, result
    row = dict(config=label, label=label, scheduler=scheduler, mosaic_tile=tile, chunks=ok,
               max_bg_threads_flag=7, total_ms=total,
               **{f"mspc_{key}": value for key, value in percentiles.items()})
    fields = next(csv.reader((FINDINGS / "orion_results.csv").open()))
    append(FINDINGS / "orion_results.csv", fields, row)
    engine = "Orion v5" if mode == "vanilla" else "Orion v5.1"
    append(FINDINGS / "leaderboard_entries.csv", ["engine", "run_label", "total_ms", "chunks", "finding"],
           dict(engine=engine, run_label=label, total_ms=total, chunks=ok, finding="#66"))
    record = dict(label=label, mode=mode, tile=tile, verify=verify, total_ms=total, chunks=ok,
                  emspc=int(total) / int(ok), parallelism=7, live_workers=workers, gc=gc[1], flags=shlex.join(flags))
    append(FINDINGS / "orion51_results.csv", list(record), record)
    print(f"DONE {label}: {total} ms / {ok} chunks = {record['emspc']:.4f} eMSPC", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=["verify", "timing"], required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--tile", type=int, default=5)
    args = parser.parse_args()
    if args.rounds < 1 or args.tile < 1:
        parser.error("rounds and tile must be positive")
    output_dir = FINDINGS / "orion51_runs" / args.tag
    output_dir.mkdir(parents=True, exist_ok=False)
    (output_dir / "environment.info").write_text(subprocess.check_output(["lscpu"], text=True) +
        subprocess.run(["java", "-version"], capture_output=True, text=True).stderr)
    with (ROOT / "build/orion51-bench.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        modes = ["vanilla", "vector", "scalar"]
        for round_index in range(1 if args.phase == "verify" else args.rounds):
            order = modes[round_index % 3:] + modes[:round_index % 3]
            for mode in order:
                label = f"{args.tag}_{mode}_r{round_index + 1}"
                run(mode, label, args.tile, args.phase == "verify", output_dir)


if __name__ == "__main__":
    main()
