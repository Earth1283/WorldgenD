#!/usr/bin/env python3
"""Deterministic digest check for #83: every arm must match the first arm chunk for chunk.

  check_orion59.py --tile 3 --arms 'v58=-Dscheduler=orion5.8;v59=-Dscheduler=orion5.9'
Writes <output>/<arm>.hist (block positions) and <arm>.biomes (biome cells) per arm.
"""
import argparse
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = "/root/minecraft-server/runtime/java/bin"
BASE = [
    "-Xms10g", "-Xmx10g", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dorion.maxinflight=16", "-Dsaveworld=false",
    "-Dorion.deterministicFeatures=region", "-Dorion.patchWorldgenLight=true",
    "-Dorion.parallelSteps.verify=true", "-Dorion.targetShift=100",
]


def read(path):
    rows = {}
    for line in open(path):
        x, z, digest, _ = line.strip().split(" ", 3)
        rows[int(x), int(z)] = digest
    return rows


def run(name, flags, tile, output):
    args = BASE + flags + [
        f"-Dmosaic.tile={tile}", f"-Ddescribe.histogramfile={output / (name + '.hist')}",
        f"-Ddescribe.biomefile={output / (name + '.biomes')}",
    ]
    shutil.rmtree(ROOT / "servers/.run/headless/dimensions", ignore_errors=True)
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    env = dict(os.environ, PATH=f"{JAVA_BIN}:{os.environ['PATH']}")
    with (output / f"{name}.log").open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *args], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
        try:
            while True:
                result = result_path.read_text() if result_path.exists() else ""
                if result.startswith("THREW:"):
                    raise RuntimeError(result)
                if "regionFileVersion " in result:
                    break
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited {process.returncode}")
                time.sleep(0.25)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
    (output / f"{name}.result").write_text(result)
    notes = re.findall(r"(overlapViolations=\d+|orderViolations=\d+|surfaceMismatches=\d+|surfaceShortcuts=\d+|areaLock=\w+|failed=\d+)", result)
    print(f"{name}: {' '.join(notes)}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--arms", required=True)
    parser.add_argument("--tile", type=int, default=3)
    parser.add_argument("--output", default=str(ROOT / "findings/orion59_runs/det"))
    options = parser.parse_args()
    arms = [(name, flags.split()) for name, flags in (arm.split("=", 1) for arm in options.arms.split(";"))]
    output = Path(options.output)
    output.mkdir(parents=True, exist_ok=True)
    for name, flags in arms:
        run(name, flags, options.tile, output)
    reference = arms[0][0]
    for kind in ("hist", "biomes"):
        ref = read(output / f"{reference}.{kind}")
        for name, _ in arms[1:]:
            cand = read(output / f"{name}.{kind}")
            assert ref.keys() == cand.keys(), (len(ref), len(cand))
            bad = sorted(k for k in ref if ref[k] != cand[k])
            print(f"{kind}: {name} vs {reference}: {len(bad)}/{len(ref)} chunks differ {bad[:6]}")


if __name__ == "__main__":
    main()
