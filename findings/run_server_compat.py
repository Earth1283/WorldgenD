#!/usr/bin/env python3
"""Probe an unchanged Orion 5.9 build against isolated server jars."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
JAVA_BIN = Path('/root/minecraft-server/runtime/java/bin')


def run(version, index, output, tile, timeout):
    label = f'mc{version}_r{index}'
    work = output / label
    work.mkdir()
    servers = work / 'servers'
    servers.mkdir()
    jar = ROOT.parent / 'mojang' / f'mojang-{version.replace(".", "-")}-server.jar'
    (servers / jar.name).symlink_to(jar)
    args = [
        'python3', str(ROOT / 'run_direct.py'),
        '-Xms16g', '-Xmx16g', '-XX:+AlwaysPreTouch', '-XX:+UseParallelGC',
        '-Dmax.bg.threads=7', '-Dscheduler=orion5.9', f'-Dmosaic.tile={tile}',
        f'-javaagent:{ROOT / "build/libs/orion-agent.jar"}',
        '-Dorion.patchReentrancy=true', '-Dorion.patchStructureGenState=true',
        '-Dorion.patchParallelSteps=true', '-Dorion.maxinflight=32',
        '-Dsaveworld=false', f'-Duser.dir={work}',
        f'-Dworldgen.progressfile={work / "progress.log"}',
    ]
    (work / 'command.json').write_text(json.dumps(args, indent=2) + '\n')
    env = dict(os.environ, PATH=f'{JAVA_BIN}:{os.environ["PATH"]}', JAVA_HOME=str(JAVA_BIN.parent))
    status, result = 'timeout', ''
    with (work / 'stdout.log').open('w') as log:
        process = subprocess.Popen(args, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
        start = time.monotonic()
        try:
            while time.monotonic() - start < timeout:
                path = work / 'orion_result.txt'
                result = path.read_text() if path.exists() else ''
                if 'regionFileVersion ' in result:
                    status = 'completed'
                    break
                if (work / 'main_crash.txt').exists() or result.startswith('THREW:'):
                    status = 'failed'
                    break
                if process.poll() is not None:
                    status = f'exit_{process.returncode}'
                    break
                time.sleep(0.25)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    match = re.search(r'scheduler=(\S+) ok=(\d+) failed=(\d+) totalMs=(\d+)', result)
    row = dict(label=label, version=version, status=status, scheduler='orion5.9',
               tile=tile, target_chunks=(16 * tile) ** 2, ok='', failed='', total_ms='',
               emspc='', cps='', jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    if status == 'completed' and not match:
        row['status'] = 'invalid'
    if match:
        ok, failed, total = map(int, match.group(2, 3, 4))
        row.update(ok=ok, failed=failed, total_ms=total)
        if status == 'completed' and ok == row['target_chunks'] and failed == 0:
            row.update(emspc=total / ok, cps=ok * 1000 / total)
        else:
            row['status'] = 'invalid'
    with (output / 'results.csv').open('a', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=row)
        if stream.tell() == 0:
            writer.writeheader()
        writer.writerow(row)
    print(json.dumps(row), flush=True)
    return row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--tile', type=int, default=5)
    parser.add_argument('--timeout', type=int, default=300)
    options = parser.parse_args()
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    current = run('26.3', 1, output, options.tile, options.timeout)
    baseline = run('26.1.1', 1, output, options.tile, options.timeout)
    if current['status'] == baseline['status'] == 'completed':
        run('26.1.1', 2, output, options.tile, options.timeout)
        run('26.3', 2, output, options.tile, options.timeout)


if __name__ == '__main__':
    main()
