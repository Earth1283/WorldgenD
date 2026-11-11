#!/usr/bin/env python3
"""Interleaved A/B runs with per-thread-group CPU accounting (Orion v5.9 / v6 study, #83).

Arms are 'name=flag flag ...' separated by ';', added to the champion spec, e.g.
  --arms 'v58=-Dscheduler=orion5.8;w8=-Dscheduler=orion5.8 -Dmax.bg.threads=8'
A flag given in an arm replaces the same -Dkey in the base spec.
"""
import argparse
import csv
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = "/root/minecraft-server/runtime/java/bin"
BASE = [
    "-XX:+AlwaysPreTouch", "-XX:+UseParallelGC", "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar", "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true", "-Dorion.patchParallelSteps=true",
    "-Dorion.maxinflight=16", "-Dsaveworld=false",
]
GC_LINE = re.compile(r"\[([\d.]+)s\].*Pause (Young|Full) \((.*?)\) (\d+)M->(\d+)M\(\d+M\) ([\d.]+)ms")
GROUPS = [
    ("worker", re.compile(r"^Worker-Main")), ("poll", re.compile(r"^orion\d*-poll")),
    ("gc", re.compile(r"^(GC Thread|ParGC|G1 |VM Thread)")), ("jit", re.compile(r"^C[12] Compiler")),
    ("io", re.compile(r"^(IO-Worker|c1gc)")), ("light", re.compile(r"light", re.I)),
]


def group_of(name):
    for group, pattern in GROUPS:
        if pattern.search(name):
            return group
    return "other"


def find_java(pid):
    # run_direct.py execs java in place, so the launched pid becomes the JVM
    try:
        return pid if "java" in os.readlink(f"/proc/{pid}/exe") else None
    except OSError:
        return None


def sample_threads(pid, seen):
    try:
        tids = os.listdir(f"/proc/{pid}/task")
    except OSError:
        return
    for tid in tids:
        try:
            stat = Path(f"/proc/{pid}/task/{tid}/stat").read_text()
        except OSError:
            continue
        name = stat[stat.index("(") + 1:stat.rindex(")")]
        fields = stat.rsplit(")", 1)[1].split()
        seen[tid] = (name, (int(fields[11]) + int(fields[12])) / 100)


def write_series(stream, elapsed, progress_path, seen):
    totals = {}
    for name, cpu in seen.values():
        totals[group_of(name)] = totals.get(group_of(name), 0.0) + cpu
    completed = 0
    if progress_path.exists():
        lines = progress_path.read_text().splitlines()
        if lines:
            completed = int(re.search(r"completed=(\d+)", lines[-1])[1])
    columns = [group for group, _ in GROUPS] + ["other"]
    stream.write(f"{elapsed:.1f},{completed}," + ",".join(f"{totals.get(c, 0.0):.2f}" for c in columns) + "\n")


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


def merge_flags(base, extra):
    keys = {flag.split("=", 1)[0] for flag in extra}
    return [flag for flag in base if flag.split("=", 1)[0] not in keys] + extra


STUCK_SECONDS = 600


def run(label, arm, flags, tile, heap, output, timeout):
    args = merge_flags([f"-Xms{heap}", f"-Xmx{heap}"] + BASE, flags) + [
        f"-Dmosaic.tile={tile}", f"-Xlog:gc:file={output / (label + '.gc.log')}:time,uptime,level,tags",
        f"-Dworldgen.progressfile={output / (label + '.progress')}",
    ]
    shutil.rmtree(ROOT / "servers/.run/headless/dimensions", ignore_errors=True)
    result_path = ROOT / "orion_result.txt"
    result_path.unlink(missing_ok=True)
    env = dict(os.environ, PATH=f"{JAVA_BIN}:{os.environ['PATH']}")
    started = time.monotonic()
    progress_path, last_progress, progressed_at = output / f"{label}.progress", "", started
    seen, fill_start_cpu, last_sample = {}, None, 0.0
    series = (output / f"{label}.cpu.csv").open("w")
    series.write("t_s,completed," + ",".join(group for group, _ in GROUPS) + ",other\n")
    with (output / f"{label}.log").open("w") as log:
        process = subprocess.Popen(["python3", "run_direct.py", *args], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
        try:
            while time.monotonic() - started < timeout:
                result = result_path.read_text() if result_path.exists() else ""
                if result.startswith("THREW:"):
                    raise RuntimeError(result)
                jvm = find_java(process.pid)
                now = time.monotonic()
                done = "regionFileVersion " in result
                if jvm and (done or now - last_sample >= 1.0):
                    sample_threads(jvm, seen)
                    last_sample = now
                    if fill_start_cpu is None and "fill() starting" in result:
                        fill_start_cpu = {tid: cpu for tid, (_, cpu) in seen.items()}
                    write_series(series, now - started, output / f"{label}.progress", seen)
                if done:
                    break
                progress = progress_path.read_text() if progress_path.exists() else ""
                if progress != last_progress:
                    last_progress, progressed_at = progress, now
                elif now - progressed_at > STUCK_SECONDS:
                    raise TimeoutError(f"{label}: no progress for {STUCK_SECONDS} s")
                if process.poll() is not None:
                    raise RuntimeError(f"{label} exited {process.returncode}; see {output / (label + '.log')}")
                time.sleep(0.1)
            else:
                raise TimeoutError(label)
        finally:
            series.close()
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
    cpu = {}
    for tid, (name, total) in seen.items():
        group = group_of(name)
        cpu[group] = cpu.get(group, 0.0) + total - (fill_start_cpu or {}).get(tid, 0.0)
    total_s = int(summary[4]) / 1000
    fields = dict(
        label=label, arm=arm, scheduler=summary[1], tile=tile, chunks=chunks, heap=heap,
        total_ms=int(summary[4]), cps=round(chunks / total_s, 2),
        mspc_p50=float(mspc["p50"]), mspc_p99=float(mspc["p99"]),
        young_gcs=len(young), young_pause_ms=round(sum(young), 1),
        full_gcs=len(full), full_pause_ms=round(sum(full), 1), peak_live_after_full_mb=peak_live,
        fill_cpu_s=round(sum(cpu.values()), 1),
        **{f"{group}_cpu_s": round(cpu.get(group, 0.0), 1) for group in ("worker", "poll", "gc", "jit", "io", "light", "other")},
        cores_busy=round(sum(cpu.values()) / total_s, 2),
    )
    with (output / "results.csv").open("a", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(fields), lineterminator="\n")
        if stream.tell() == 0:
            writer.writeheader()
        writer.writerow(fields)
    print(f"{label}: {fields['total_ms']} ms {fields['cps']} cps cores={fields['cores_busy']} worker={fields['worker_cpu_s']} "
          f"poll={fields['poll_cpu_s']} gc={fields['gc_cpu_s']} io={fields['io_cpu_s']} other={fields['other_cpu_s']} "
          f"young={len(young)}x/{sum(young):.0f}ms full={len(full)}x/{sum(full):.0f}ms", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--arms", required=True)
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--first-round", type=int, default=1)
    parser.add_argument("--tile", type=int, default=5)
    parser.add_argument("--heap", default="16g")
    parser.add_argument("--timeout", type=int, default=7200)
    parser.add_argument("--output", default=str(ROOT / "findings/orion59_runs"))
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
