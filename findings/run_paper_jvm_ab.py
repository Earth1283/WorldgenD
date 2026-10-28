#!/usr/bin/env python3
"""Paper-7w on Corretto vs Ubuntu OpenJDK 25, interleaved: does the JVM explain #78's faster servers?"""
import argparse
import csv
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROL = ROOT.parent / "control"
JVMS = {
    "corretto": "/root/minecraft-server/runtime/java",
    "ubuntu": "/usr/lib/jvm/java-25-openjdk-amd64",
}
FIELDS = ["round", "order", "jvm", "java_version", "total_ms", "chunks", "ms_per_chunk", "chunky_total", "workers"]


def set_line(path, pattern, replacement):
    path.write_text(re.sub(pattern, replacement, path.read_text(), flags=re.M))


def run(output, label, java_home):
    env = {**os.environ, "JAVA_HOME": java_home, "PATH": f"{java_home}/bin:{os.environ['PATH']}"}
    log_out = output / f"{label}.server.log"
    proc = subprocess.run(["bash", "run-drag-race.sh", "start-paper.sh", label, str(log_out)],
                          cwd=CONTROL, env=env, capture_output=True, text=True)
    (output / f"{label}.driver.log").write_text(proc.stdout + proc.stderr)
    if proc.returncode != 0:
        raise RuntimeError(f"{label} failed: {proc.stdout[-500:]}{proc.stderr[-500:]}")
    log = log_out.read_text()
    elapsed = float(re.search(r"RCON-measured elapsed: ([\d.]+)s", proc.stdout)[1])
    chunky = re.search(r"Total time: (\S+)", log)
    return dict(
        java_version=re.search(r"Running Java \d+ \(([^;)]+)", log)[1],
        total_ms=round(elapsed * 1000), chunks=6561, ms_per_chunk=round(elapsed * 1000 / 6561, 2),
        chunky_total=chunky[1] if chunky else "",
        workers=int(re.search(r"is using (\d+) worker threads", log)[1]),
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--tag", default="paper_jvm_ab")
    args = parser.parse_args()
    output = ROOT / "findings" / f"{args.tag}_logs"
    output.mkdir(parents=True, exist_ok=False)
    paper_global = CONTROL / "config" / "paper-global.yml"
    properties = CONTROL / "server.properties"
    set_line(paper_global, r"^(\s*worker-threads:) -1$", r"\1 7")
    set_line(properties, r"^server-port=9090$", "server-port=25565")
    try:
        with (ROOT / "findings" / f"{args.tag}_results.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            arms = list(JVMS)
            for round_number in range(1, args.rounds + 1):
                order = arms if round_number % 2 else arms[::-1]
                for position, jvm in enumerate(order, start=1):
                    label = f"r{round_number}_{jvm}"
                    row = dict(round=round_number, order=position, jvm=jvm, **run(output, label, JVMS[jvm]))
                    writer.writerow(row)
                    stream.flush()
                    print(f"{label}: {row['ms_per_chunk']} ms/chunk ({row['java_version']}) workers={row['workers']}", flush=True)
    finally:
        set_line(paper_global, r"^(\s*worker-threads:) 7$", r"\1 -1")
        set_line(properties, r"^server-port=25565$", "server-port=9090")
        subprocess.run(["bash", "wipe-world.sh"], cwd=CONTROL)


if __name__ == "__main__":
    main()
