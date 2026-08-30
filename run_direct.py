#!/usr/bin/env python3
"""Runs WorldgenD by execing java directly, bypassing Gradle's JavaExec stdout
relay — which silently drops output in this environment for reasons never
isolated (see scientific-findings.md #23). All arguments are passed straight
through to java as JVM flags/system properties.

Usage: python3 run_direct.py -Xms16g -Xmx16g -XX:+UseParallelGC -Dscheduler=orion
"""
import os
import subprocess
import sys

root = os.path.dirname(os.path.abspath(__file__))

classpath = subprocess.run(
    ["./gradlew", "-q", "--console=plain", "printRuntimeClasspath"],
    cwd=root, capture_output=True, text=True, check=True,
).stdout.strip().splitlines()[-1]

os.execvp("java", ["java", *sys.argv[1:], "-cp", classpath, "io.github.eath1283.worldgend.HeadlessWorldgenKt"])
