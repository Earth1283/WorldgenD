#!/usr/bin/env python3
"""Check isolated headless generation, saved chunk NBT, and optional vanilla reload."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def stop(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def wait_for_file(command, work, marker, timeout, crash=None):
    with (work / 'stdout.log').open('w') as log:
        process = subprocess.Popen(command, cwd=work, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        deadline = time.monotonic() + timeout
        try:
            while time.monotonic() < deadline:
                if crash is not None and crash.exists():
                    raise RuntimeError(crash.read_text())
                if marker.exists():
                    return
                if process.poll() is not None:
                    raise RuntimeError(f'Exited {process.returncode} before {marker.name}; see {work / "stdout.log"}')
                time.sleep(0.25)
            raise TimeoutError(f'No {marker.name} after {timeout}s; see {work / "stdout.log"}')
        finally:
            stop(process)


def reload_world(jar, world, work, options):
    work.mkdir()
    shutil.copytree(world, work / 'headless')
    (work / 'eula.txt').write_text('eula=true\n')
    (work / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=0\nonline-mode=false\n'
        'level-name=headless\nview-distance=2\nsimulation-distance=2\n')
    command = ['java', '-Xms1g', f'-Xmx{options.heap}', '-XX:+UseParallelGC',
               f'-Dmax.bg.threads={options.workers}', '-jar', str(jar), '--nogui']
    (work / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
    with (work / 'stdout.log').open('w') as log:
        process = subprocess.Popen(command, cwd=work, stdin=subprocess.PIPE, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True, text=True)
        deadline = time.monotonic() + options.timeout
        try:
            while time.monotonic() < deadline:
                text = (work / 'stdout.log').read_text(errors='replace')
                if 'Done (' in text and 'For help, type' in text:
                    process.stdin.write('stop\n')
                    process.stdin.flush()
                    process.wait(timeout=45)
                    if process.returncode != 0:
                        raise RuntimeError(f'Vanilla reload exited {process.returncode}')
                    # Startup alone can succeed after replacing unreadable world metadata.
                    text = (work / 'stdout.log').read_text(errors='replace')
                    errors = [line for line in text.splitlines() if any(term in line.lower() for term in (
                        'falling back', 'failed to read', 'failed to load', 'exception', '/error]'))]
                    if errors:
                        raise RuntimeError('Vanilla reload errors: ' + '\n'.join(errors))
                    return
                if process.poll() is not None:
                    raise RuntimeError(f'Vanilla exited {process.returncode} before ready')
                time.sleep(0.25)
            raise TimeoutError('Vanilla reload did not become ready')
        finally:
            stop(process)
            process.stdin.close()


def probe(options, output, row):
    jar = ROOT.parent / 'mojang' / f'mojang-{options.version.replace(".", "-")}-server.jar'
    if not jar.is_file():
        raise FileNotFoundError(jar)
    with ZipFile(jar) as bundle:
        nested = bundle.read('META-INF/versions.list').decode().strip().split('\t')[2]
        with ZipFile(io.BytesIO(bundle.read(f'META-INF/versions/{nested}'))) as server:
            version = json.loads(server.read('version.json'))
    if version['id'] != options.version:
        raise RuntimeError(f'Expected {options.version}, jar identifies as {version["id"]}')
    row['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    row['data_version'] = version['world_version']
    metadata = dict(version=version, java=subprocess.run(['java', '-version'], capture_output=True,
                    text=True, check=True).stderr,
                    revision=subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, capture_output=True,
                                            text=True, check=True).stdout.strip())
    (output / 'metadata.json').write_text(json.dumps(metadata, indent=2) + '\n')
    build = ['agentJar', 'printRuntimeClasspath'] if options.scheduler != 'mosaic' else ['printRuntimeClasspath']
    result = subprocess.run(['./gradlew', '-q', '--console=plain', *build], cwd=ROOT,
                            capture_output=True, text=True)
    (output / 'build.log').write_text(result.stdout + result.stderr)
    result.check_returncode()
    classpath = result.stdout.strip().splitlines()[-1]
    work = output / 'generation'
    (work / 'servers').mkdir(parents=True)
    (work / 'servers' / jar.name).symlink_to(jar)
    complete = work / 'complete'
    command = ['python3', str(ROOT / 'run_direct.py'), '-Xms1g', f'-Xmx{options.heap}',
               '-XX:+UseParallelGC', f'-Dmax.bg.threads={options.workers}',
               f'-Dscheduler={options.scheduler}', f'-Dmosaic.tile={options.tile}',
               '-Dsaveworld=true', f'-Duser.dir={work}', f'-Dworldgen.completionfile={complete}']
    if options.scheduler != 'mosaic':
        command += [f'-javaagent:{ROOT / "build/libs/orion-agent.jar"}',
                    '-Dorion.patchReentrancy=true', '-Dorion.patchStructureGenState=true',
                    '-Dorion.patchParallelSteps=true', '-Dorion.maxinflight=8',
                    '-Dorion.parallelSteps.verify=true', '-Dorion.serverTasks=drain',
                    '-Dorion.pollServerThread=true', '-Dorion.deterministicFeatures=region',
                    f'-Dorion.targetShift={row["target_shift"]}',
                    '-Dorion.patchWorldgenLight=true', f'-Ddescribe.histogramfile={work / "blocks.hist"}',
                    f'-Ddescribe.biomefile={work / "biomes.hist"}']
        if options.trace:
            command.append(f'-Dorion.trace={work / "trace"}')
    (work / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
    wait_for_file(command, work, complete, options.timeout, work / 'main_crash.txt')
    result_name = 'mosaic_result.txt' if options.scheduler == 'mosaic' else 'orion_result.txt'
    text = (work / result_name).read_text()
    match = re.search(r'ok=(\d+) failed=(\d+)', text)
    if not match or int(match[1]) != row['target_chunks'] or int(match[2]) != 0:
        raise RuntimeError('Incomplete generation: ' + text)
    if options.scheduler != 'mosaic':
        if any(check not in text for check in ['overlapViolations=0', 'orderViolations=0',
                                               'kickFailures=0', 'stuckGates=0', 'stalls count=0']):
            raise RuntimeError('Scheduler correctness checks failed: ' + text)
        for name in ['blocks.hist', 'biomes.hist']:
            lines = (work / name).read_text().splitlines()
            coords = {tuple(map(int, line.split()[:2])) for line in lines}
            side = 16 * options.tile
            lo = row['target_shift'] - side // 2
            expected = {(x, z) for x in range(lo, lo + side) for z in range(lo, lo + side)}
            if len(lines) != row['target_chunks'] or coords != expected:
                raise RuntimeError(f'Incomplete {name}')
    row['generated'] = int(match[1])
    world = work / 'servers/.run/headless'
    inspection = output / 'inspection'
    inspection.mkdir()
    report = inspection / 'saved_chunks.txt'
    side = 16 * options.tile
    command = ['java', '-Xms256m', '-Xmx1g', '-cp', classpath,
               'io.github.eath1283.worldgend.SavedWorldProbe', str(work / 'servers'), str(world),
               str(row['target_shift'] - side // 2), str(row['target_shift'] - side // 2),
               str(side), str(version['world_version']), str(report)]
    (inspection / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
    wait_for_file(command, inspection, report, options.timeout)
    if report.read_text() != f'ok={row["target_chunks"]} failed=0 dataVersion={version["world_version"]}\n':
        raise RuntimeError(report.read_text())
    row['saved_verified'] = row['target_chunks']
    if options.reload:
        reload_world(jar, world, output / 'reload', options)
        row['vanilla_reload'] = 'passed'
    row['status'] = 'passed'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', default='26.3')
    parser.add_argument('--scheduler', choices=['mosaic', 'orion5', 'orion5.9'], default='orion5')
    parser.add_argument('--tile', type=int, default=1)
    parser.add_argument('--workers', type=int, default=2)
    parser.add_argument('--heap', default='4g')
    parser.add_argument('--timeout', type=int, default=180)
    parser.add_argument('--trace', action='store_true', help='Record Orion step and lane traces')
    parser.add_argument('--reload', action='store_true', help='Boot a copy of the saved world in the official server')
    options = parser.parse_args()
    if options.tile < 1 or options.workers < 1:
        parser.error('tile and workers must be positive')
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    row = dict(version=options.version, scheduler=options.scheduler, tile=options.tile,
               workers=options.workers, heap=options.heap, target_chunks=(16 * options.tile) ** 2,
               target_shift=0 if options.scheduler == 'mosaic' else 100, trace=options.trace,
               generated=0, saved_verified=0, vanilla_reload='not_run', status='failed',
               data_version='', jar_sha256='', error='')
    try:
        probe(options, output, row)
    except Exception as error:
        row['error'] = str(error)
    with (output / 'results.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=row)
        writer.writeheader()
        writer.writerow(row)
    print(json.dumps(row), flush=True)
    return 0 if row['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
