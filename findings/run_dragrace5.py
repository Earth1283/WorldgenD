#!/usr/bin/env python3
"""Drag race #78: Orion v5.7 vs Paper/Leaf/Leaf-on-crack, all at 7 workers, leg order rotated each round."""
import argparse
import csv
import os
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROL = ROOT.parent / "control"
JAVA_HOME = "/root/minecraft-server/runtime/java"
ENV = {**os.environ, "JAVA_HOME": JAVA_HOME, "PATH": f"{JAVA_HOME}/bin:{os.environ['PATH']}"}
ORION = [
    "-Xms16g", "-Xmx16g", "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dscheduler=orion5.7", "-Dorion.maxinflight=16", "-Dorion.c1gc.pressure=0.3", "-Dmosaic.tile=5",
]
SERVERS = {
    "Paper (7 workers)": "start-paper.sh",
    "Leaf (7 workers)": "start-leaf.sh",
    "Leaf-on-crack (7 workers)": "start-leaf-with-crack.sh",
}
ENGINES = ["Orion v5.7", *SERVERS]
FIELDS = ["round", "order", "engine", "total_ms", "chunks", "ms_per_chunk", "chunky_total", "workers"]


def run_orion(output, label):
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    started = time.monotonic()
    with (output / f"{label}.log").open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *ORION], cwd=ROOT, env=ENV, stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - started < 1200:
                if result_path.exists():
                    result = result_path.read_text()
                    if result.startswith("THREW:"):
                        raise RuntimeError(result)
                    if "regionFileVersion " in result:
                        break
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited {process.returncode}")
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
    summary = re.search(r"ok=(\d+) failed=(\d+) totalMs=(\d+)", result)
    assert summary and int(summary[1]) == 6400 and int(summary[2]) == 0, result
    return int(summary[3]), 6400, "", 7


def run_server(output, label, script):
    log_out = output / f"{label}.server.log"
    proc = subprocess.run(["bash", "run-drag-race.sh", script, label, str(log_out)],
                          cwd=CONTROL, env=ENV, capture_output=True, text=True)
    (output / f"{label}.driver.log").write_text(proc.stdout + proc.stderr)
    if proc.returncode != 0:
        raise RuntimeError(f"{label} failed: {proc.stdout[-500:]}{proc.stderr[-500:]}")
    elapsed = float(re.search(r"RCON-measured elapsed: ([\d.]+)s", proc.stdout)[1])
    log = log_out.read_text()
    chunky = re.search(r"Total time: (\S+)", log)
    workers = re.search(r"is using (\d+) worker threads", log)
    return round(elapsed * 1000), 6561, chunky[1] if chunky else "", int(workers[1]) if workers else ""


def set_line(path, pattern, replacement):
    path.write_text(re.sub(pattern, replacement, path.read_text(), flags=re.M))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--tag", default="dragrace5")
    args = parser.parse_args()
    output = ROOT / "findings" / f"{args.tag}_logs"
    output.mkdir(parents=True, exist_ok=False)
    results = ROOT / "findings" / f"{args.tag}_results.csv"
    paper_global = CONTROL / "config" / "paper-global.yml"
    properties = CONTROL / "server.properties"
    set_line(paper_global, r"^(\s*worker-threads:) -1$", r"\1 7")
    set_line(properties, r"^server-port=9090$", "server-port=25565")
    try:
        with results.open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            for round_number in range(1, args.rounds + 1):
                shift = (round_number - 1) % len(ENGINES)
                for order, engine in enumerate(ENGINES[shift:] + ENGINES[:shift], start=1):
                    label = f"r{round_number}_" + re.sub(r"\W+", "_", engine).strip("_")
                    if engine in SERVERS:
                        total, chunks, chunky, workers = run_server(output, label, SERVERS[engine])
                    else:
                        total, chunks, chunky, workers = run_orion(output, label)
                    row = dict(round=round_number, order=order, engine=engine, total_ms=total, chunks=chunks,
                               ms_per_chunk=round(total / chunks, 2), chunky_total=chunky, workers=workers)
                    writer.writerow(row)
                    stream.flush()
                    print(f"{label}: {row['ms_per_chunk']} ms/chunk workers={workers}", flush=True)
    finally:
        set_line(paper_global, r"^(\s*worker-threads:) 7$", r"\1 -1")
        set_line(properties, r"^server-port=25565$", "server-port=9090")
        subprocess.run(["bash", "wipe-world.sh"], cwd=CONTROL)


if __name__ == "__main__":
    main()
