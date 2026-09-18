#!/usr/bin/env python3
"""runs wgd by invoking java directly so that STDOUT doesn't get screwed as badly

Usage: python3 run_direct.py -Xms16g -Xmx16g -XX:+UseParallelGC -Dscheduler=orion
"""
import os
import subprocess
import sys

root = os.path.dirname(os.path.abspath(__file__))

build_tasks = ["printRuntimeClasspath"]
if any(arg.startswith("-javaagent:build/libs/orion-agent.jar") for arg in sys.argv[1:]):
    build_tasks.insert(0, "agentJar")

classpath = subprocess.run(
    ["./gradlew", "-q", "--console=plain", *build_tasks],
    cwd=root, capture_output=True, text=True, check=True,
).stdout.strip().splitlines()[-1]

args = sys.argv[1:]
if "-Dscheduler=orion5.1" in args or "-Dscheduler=orion5.2" in args or "-Dscheduler=orion5.5" in args or "-Dorion.patchDensitySimd=true" in args:
    if "--add-modules=jdk.incubator.vector" not in args:
        args = ["--add-modules=jdk.incubator.vector", *args]
os.execvp("java", ["java", *args, "-cp", classpath, "io.github.eath1283.worldgend.HeadlessWorldgenKt"])
