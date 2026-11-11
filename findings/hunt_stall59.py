#!/usr/bin/env python3
"""Repeated short tile-16 starts looking for #83's stuck target (window 64 stalled one chunk for 280 s).

Each attempt runs until fill time passes --cutoff seconds, or, if the watchdog fires, until it has
written --dumps dumps. Stall dumps are kept as <output>/<tag>_<n>.stall.
"""
import argparse
import os
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = "/root/minecraft-server/runtime/java/bin"
BASE = [
    "-Xms16g", "-Xmx16g", "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dsaveworld=false", "-Dmosaic.tile=16", "-Dorion.stallSeconds=30",
]


def attempt(label, flags, output, cutoff, dumps):
    shutil.rmtree(ROOT / "servers/.run/headless/dimensions", ignore_errors=True)
    stall = ROOT / "orion_stall.txt"
    stall.unlink(missing_ok=True)
    progress = output / f"{label}.progress"
    progress.unlink(missing_ok=True)
    env = dict(os.environ, PATH=f"{JAVA_BIN}:{os.environ['PATH']}")
    with (output / f"{label}.log").open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *BASE, *flags, f"-Dworldgen.progressfile={progress}"],
                                   cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
        found, fill_s = False, 0.0
        try:
            while process.poll() is None:
                time.sleep(1)
                if progress.exists():
                    lines = progress.read_text().splitlines()
                    if lines:
                        fill_s = int(lines[-1].rsplit("=", 1)[1]) / 1000
                count = stall.read_text().count("STALL target=") if stall.exists() else 0
                found = found or count > 0
                if (found and count >= dumps) or (not found and fill_s > cutoff):
                    break
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
    if found:
        shutil.copy(stall, output / f"{label}.stall")
    return found, fill_s


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--flags", required=True)
    parser.add_argument("--attempts", type=int, default=15)
    parser.add_argument("--cutoff", type=float, default=70)
    parser.add_argument("--dumps", type=int, default=3)
    parser.add_argument("--output", default=str(ROOT / "findings/orion59_runs/hunt"))
    options = parser.parse_args()
    output = Path(options.output)
    output.mkdir(parents=True, exist_ok=True)
    hits = 0
    for n in range(1, options.attempts + 1):
        found, fill_s = attempt(f"{options.tag}_{n}", options.flags.split(), output, options.cutoff, options.dumps)
        hits += found
        print(f"{options.tag}_{n}: {'STALL' if found else 'clean'} at fill {fill_s:.0f}s (hits {hits}/{n})", flush=True)


if __name__ == "__main__":
    main()
