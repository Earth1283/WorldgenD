#!/usr/bin/env python3
"""Compare real vanilla fillArray calls with scalar and vector replacements."""
import argparse
import csv
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--forks", type=int, default=3)
    args = parser.parse_args()
    output = ROOT / "findings/orion51_micro" / args.tag
    output.mkdir(parents=True, exist_ok=False)
    classes = ROOT / "build/density-batch-bench"
    classes.mkdir(parents=True, exist_ok=True)
    classpath = subprocess.check_output(["./gradlew", "-q", "agentJar", "printRuntimeClasspath"], cwd=ROOT, text=True).strip().splitlines()[-1]
    subprocess.run(["javac", "-cp", classpath, "-d", str(classes), "findings/DensityBatchBench.java"], cwd=ROOT, check=True)
    modes = ["vanilla", "vector", "scalar"]
    for fork in range(args.forks):
        for mode in modes[fork % 3:] + modes[:fork % 3]:
            flags = ["-Xms512m", "-Xmx512m", "-XX:+UseParallelGC", f"-Dbench.mode={mode}"]
            if mode != "vanilla":
                flags += ["-javaagent:build/libs/orion-agent.jar", "-Dorion.patchDensitySimd=true",
                          f"-Dorion.simd={'scalar' if mode == 'scalar' else 'auto'}"]
            if mode == "vector":
                flags += ["--add-modules=jdk.incubator.vector"]
            path = output / f"{mode}_fork{fork + 1}.csv"
            with (output / f"{mode}_fork{fork + 1}.log").open("w") as log:
                subprocess.run(["java", *flags, "-cp", f"{classes}:{classpath}", "DensityBatchBench", str(path)],
                               cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
            rows = list(csv.DictReader(path.open()))
            assert len(rows) == 20
            print(f"DONE {path.name}", flush=True)


if __name__ == "__main__":
    main()
