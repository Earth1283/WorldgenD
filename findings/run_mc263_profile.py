#!/usr/bin/env python3
"""Interleaved A/B, OrionTrace, and JFR runner for Minecraft 26.3 vs 26.1.1 on Orion v5.9."""
import argparse
import csv
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = Path("/root/minecraft-server/runtime/java/bin")
MOJANG_DIR = ROOT.parent / "mojang"
OUT_ROOT = ROOT / "findings/profile_263_runs"

BASE_FLAGS = [
    "-Xms16g",
    "-Xmx16g",
    "-XX:+AlwaysPreTouch",
    "-XX:+UseParallelGC",
    "-Dmax.bg.threads=7",
    "-javaagent:build/libs/orion-agent.jar",
    "-Dorion.patchReentrancy=true",
    "-Dorion.patchStructureGenState=true",
    "-Dorion.patchParallelSteps=true",
    "-Dscheduler=orion5.9",
    "-Dorion.maxinflight=32",
    "-Dsaveworld=false",
]

GC_LINE = re.compile(r"\[([\d.]+)s\].*Pause (Young|Full) \((.*?)\) (\d+)M->(\d+)M\(\d+M\) ([\d.]+)ms")
GROUPS = [
    ("worker", re.compile(r"^Worker-Main")),
    ("poll", re.compile(r"^orion\d*-poll")),
    ("gc", re.compile(r"^(GC Thread|ParGC|G1 |VM Thread)")),
    ("jit", re.compile(r"^C[12] Compiler")),
    ("io", re.compile(r"^(IO-Worker|c1gc)")),
    ("light", re.compile(r"light", re.I)),
]


def group_of(name):
    for group, pattern in GROUPS:
        if pattern.search(name):
            return group
    return "other"


def find_java(pid):
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
        seen[tid] = (name, (int(fields[11]) + int(fields[12])) / 100.0)


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


def prepare_workdir(workdir, version):
    if workdir.exists():
        shutil.rmtree(workdir)
    workdir.mkdir(parents=True)
    servers = workdir / "servers"
    servers.mkdir()
    jar = MOJANG_DIR / f"mojang-{version.replace('.', '-')}-server.jar"
    if not jar.exists():
        raise FileNotFoundError(jar)
    (servers / jar.name).symlink_to(jar)
    return workdir


def verify_run(result_text, stdout_text, tile, version):
    chunks = (16 * tile) ** 2
    assert "PS MarkSweep" in stdout_text and "PS Scavenge" in stdout_text, "Wrong GC in stdout"
    summary = re.search(r"scheduler=(\S+) ok=(\d+) failed=(\d+) totalMs=(\d+)", result_text)
    assert summary, f"Missing summary in result: {result_text}"
    assert summary[1] == "orion5.9", f"Unexpected scheduler: {summary[1]}"
    assert int(summary[2]) == chunks and int(summary[3]) == 0, f"Bad chunk counts: {summary.group(0)}"
    assert "backgroundPool parallelism=7 " in result_text, f"Unexpected pool parallelism: {result_text}"
    assert "stalls count=0" in result_text, f"Non-zero stalls: {result_text}"
    assert "failed=[]" in result_text, f"StagePatch failures: {result_text}"
    assert "serverTasks mode=drain" in result_text, f"Server tasks not drained: {result_text}"
    assert "regionFileVersion id=4" in result_text, f"Missing regionFileVersion: {result_text}"
    if version == "26.3":
        assert "noiseCell not_applicable" in result_text, "Expected 26.3 noiseCell not_applicable"
    else:
        assert "noiseCell batch=true" in result_text, "Expected 26.1.1 noiseCell active"


def run_one(label, version, tile, mode, timeout, extra_flags=None):
    workdir = prepare_workdir(OUT_ROOT / label, version)
    gc_log = workdir / "gc.log"
    progress_log = workdir / "progress.log"
    result_path = workdir / "orion_result.txt"
    stdout_path = workdir / "stdout.log"

    args = [
        "python3",
        "run_direct.py",
        *BASE_FLAGS,
        f"-Dmosaic.tile={tile}",
        f"-Duser.dir={workdir}",
        f"-Dworldgen.progressfile={progress_log}",
        f"-Xlog:gc:file={gc_log}:time,uptime,level,tags",
    ]
    if mode == "trace":
        args.append(f"-Dorion.trace={workdir / 'trace'}")
    elif mode == "jfr":
        args.append("-XX:StartFlightRecording=name=prof,settings=profile")
    if extra_flags:
        args.extend(extra_flags)

    env = dict(os.environ, PATH=f"{JAVA_BIN}:{os.environ['PATH']}", JAVA_HOME=str(JAVA_BIN.parent))
    started = time.monotonic()
    last_progress, progressed_at = "", started
    seen, fill_start_cpu, last_sample = {}, None, 0.0
    result_text = ""

    with stdout_path.open("w") as log:
        proc = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
        try:
            while time.monotonic() - started < timeout:
                result_text = result_path.read_text() if result_path.exists() else ""
                if result_text.startswith("THREW:") or (workdir / "main_crash.txt").exists():
                    raise RuntimeError(f"{label} crashed: {result_text}")
                jvm = find_java(proc.pid)
                now = time.monotonic()
                done = "regionFileVersion " in result_text
                fill_started = "fill() starting" in result_text
                if jvm and (done or (fill_started and fill_start_cpu is None) or now - last_sample >= 1.0):
                    sample_threads(jvm, seen)
                    last_sample = now
                    if fill_start_cpu is None and fill_started:
                        fill_start_cpu = {tid: cpu for tid, (_, cpu) in seen.items()}
                if done:
                    if mode == "jfr":
                        jfr_path = workdir / "profile.jfr"
                        subprocess.run(
                            [str(JAVA_BIN / "jcmd"), str(jvm or proc.pid), "JFR.dump", "name=prof", f"filename={jfr_path}"],
                            check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                        )
                        assert jfr_path.exists() and jfr_path.stat().st_size > 0, f"Empty JFR dump for {label}"
                    break
                progress = progress_log.read_text() if progress_log.exists() else ""
                if progress != last_progress:
                    last_progress, progressed_at = progress, now
                elif now - progressed_at > 600:
                    raise TimeoutError(f"{label}: stalled without progress for 600s")
                if proc.poll() is not None:
                    raise RuntimeError(f"{label} exited early ({proc.returncode}); see {stdout_path}")
                time.sleep(0.15)
            else:
                raise TimeoutError(f"{label} timed out after {timeout}s")
        finally:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()

    stdout_text = stdout_path.read_text()
    verify_run(result_text, stdout_text, tile, version)

    # Clean up generated world dimensions on disk after verification to keep disk usage flat.
    shutil.rmtree(workdir / "servers/.run/headless/dimensions", ignore_errors=True)

    summary = re.search(r"scheduler=(\S+) ok=(\d+) failed=(\d+) totalMs=(\d+)", result_text)
    chunks = int(summary[2])
    total_ms = int(summary[4])
    mspc = dict(re.findall(r"(min|p1|p25|p50|p75|p99|max)=([\d.]+)", result_text))
    young, full, peak_live = gc_stats(gc_log)
    cpu = {}
    for tid, (name, total) in seen.items():
        grp = group_of(name)
        cpu[grp] = cpu.get(grp, 0.0) + total - (fill_start_cpu or {}).get(tid, 0.0)
    total_s = total_ms / 1000.0
    fill_cpu = sum(cpu.values())

    c1gc_match = re.search(r"c1gc .*persisted=(\d+).*armed=(\w+).*peakOldGenUsedMb=(\d+)", result_text)
    row = {
        "label": label,
        "mode": mode,
        "version": version,
        "tile": tile,
        "chunks": chunks,
        "total_ms": total_ms,
        "emspc": round(total_ms / chunks, 4),
        "cps": round(chunks / total_s, 2),
        "mspc_min": float(mspc["min"]),
        "mspc_p1": float(mspc["p1"]),
        "mspc_p25": float(mspc["p25"]),
        "mspc_p50": float(mspc["p50"]),
        "mspc_p75": float(mspc["p75"]),
        "mspc_p99": float(mspc["p99"]),
        "mspc_max": float(mspc["max"]),
        "young_gcs": len(young),
        "young_pause_ms": round(sum(young), 1),
        "full_gcs": len(full),
        "full_pause_ms": round(sum(full), 1),
        "peak_live_after_full_mb": peak_live,
        "c1gc_armed": c1gc_match[2] if c1gc_match else "",
        "c1gc_persisted": int(c1gc_match[1]) if c1gc_match else 0,
        "peak_old_mb": int(c1gc_match[3]) if c1gc_match else 0,
        "fill_cpu_s": round(fill_cpu, 2),
        "cores_busy": round(fill_cpu / total_s, 3),
        **{f"{grp}_cpu_s": round(cpu.get(grp, 0.0), 2) for grp in ("worker", "poll", "gc", "jit", "io", "light", "other")},
    }
    print(
        f"[{mode}] {label} ({version} tile={tile}): {total_ms}ms ({row['cps']} cps, {row['emspc']:.3f} emspc) "
        f"worker={row['worker_cpu_s']}s poll={row['poll_cpu_s']}s gc={row['gc_cpu_s']}s "
        f"cores={row['cores_busy']} yGC={len(young)}({sum(young):.0f}ms) fGC={len(full)}({sum(full):.0f}ms)",
        flush=True,
    )
    return row


def append_csv(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    exists = path.exists() and path.stat().st_size > 0
    with path.open("a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()), lineterminator="\n")
        if not exists:
            writer.writeheader()
        for r in rows:
            writer.writerow(r)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("ab", "trace", "jfr"), required=True)
    parser.add_argument("--tile", type=int, default=5)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--versions", default="26.3,26.1.1")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--tag", default="")
    args = parser.parse_args()

    versions = [v.strip() for v in args.versions.split(",") if v.strip()]
    tag = args.tag or f"{args.mode}_t{args.tile}"
    out_csv = OUT_ROOT / f"{args.mode}_runs.csv"

    rows = []
    if args.mode == "ab":
        for r in range(1, args.rounds + 1):
            order = versions if r % 2 == 1 else list(reversed(versions))
            for ver in order:
                label = f"{tag}_mc{ver.replace('.', '')}_r{r}"
                row = run_one(label, ver, args.tile, "ab", args.timeout)
                rows.append(row)
                append_csv(out_csv, [row])
    else:
        for ver in versions:
            label = f"{tag}_mc{ver.replace('.', '')}"
            row = run_one(label, ver, args.tile, args.mode, args.timeout)
            rows.append(row)
            append_csv(out_csv, [row])


if __name__ == "__main__":
    main()
