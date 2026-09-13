#!/usr/bin/env python3
"""Decode HotSpot's raw C2 bytes when hsdis is unavailable."""
import argparse
import gzip
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("probe", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    text = gzip.decompress(args.probe.read_bytes()).decode()
    compilations = [part for part in text.split("Compiled method (c2)")
                    if "DensityVector::apply (" in part.split("\n", 1)[0]]
    assert compilations, "No complete C2 compilation found"
    code = compilations[-1]
    bounds = re.search(r"main code\s+\[(0x[0-9a-f]+),(0x[0-9a-f]+)\]", code)
    start, end = (int(value, 16) for value in bounds.groups())
    data = bytearray(end - start)
    covered = bytearray(end - start)
    for address, groups in re.findall(r"^\s*(0x[0-9a-f]+): ([0-9a-f |]+)$", code, re.M):
        address = int(address, 16)
        payload = bytes.fromhex(groups.replace("|", ""))
        if start <= address < end:
            size = min(len(payload), end - address)
            data[address - start:address - start + size] = payload[:size]
            covered[address - start:address - start + size] = b"\1" * size
    assert all(covered), f"Missing {covered.count(0)} bytes"
    with tempfile.TemporaryDirectory() as temporary:
        binary = Path(temporary) / "density.bin"
        binary.write_bytes(data)
        assembly = subprocess.check_output(["objdump", "-D", "-b", "binary", "-m", "i386:x86-64", "-Mintel",
                                            f"--adjust-vma={start}", str(binary)], text=True)
    assembly = assembly.replace(str(binary), "DensityVector.apply C2 bytes")
    args.output.write_text(assembly)
    instructions = sorted(set(re.findall(r"\b(v(?:mul|div|add|sub|blendv|cmp)[a-z0-9]+)\b.*ymm", assembly)))
    assert "vmulpd" in instructions, instructions
    print("256-bit instructions:", ", ".join(instructions))


if __name__ == "__main__":
    main()
