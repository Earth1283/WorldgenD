#!/usr/bin/env python3
import argparse
import collections
import csv
import json
import subprocess
from pathlib import Path


def method(frame):
    value = frame["method"]
    return value["type"]["name"].replace("/", ".") + "." + value["name"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("recording", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    data = json.loads(subprocess.check_output([
        "jfr", "print", "--json", "--events", "jdk.ExecutionSample", str(args.recording)
    ]))
    rows = data["recording"]["events"]
    leaf = collections.Counter()
    inclusive = collections.Counter()
    threads = collections.Counter()
    for event in rows:
        values = event["values"]
        thread = values["sampledThread"]["javaName"]
        group = "worker" if thread.startswith("Worker-Main") else thread
        threads[group] += 1
        frames = values.get("stackTrace", {}).get("frames", [])
        if not frames:
            continue
        names = [method(frame) for frame in frames]
        leaf[group, names[0]] += 1
        for name in set(names):
            inclusive[group, name] += 1
    args.output.mkdir(parents=True, exist_ok=True)
    for label, counts in (("leaf", leaf), ("inclusive", inclusive)):
        with (args.output / f"{label}.csv").open("w", newline="") as stream:
            writer = csv.writer(stream)
            writer.writerow(("thread", "method", "samples", "thread_samples", "percent"))
            for (group, name), count in counts.most_common():
                writer.writerow((group, name, count, threads[group], round(100 * count / threads[group], 3)))
    with (args.output / "threads.csv").open("w", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(("thread", "samples"))
        writer.writerows(threads.most_common())


if __name__ == "__main__":
    main()
