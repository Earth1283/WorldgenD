#!/usr/bin/env python3
"""Compare complete block-position digests from the corrected #66 checker."""
import argparse
import csv
import gzip
from pathlib import Path


def read(path):
    with gzip.open(path, "rt") as source:
        rows = {}
        for line in source:
            x, z, digest, counts = line.strip().split(" ", 3)
            assert len(digest) == 64, f"Old palette checker output: {path}"
            total = sum(int(entry.rsplit("=", 1)[1]) for entry in counts.split(","))
            assert total == 16 * 16 * 384, f"Incomplete overworld chunk: {path}: {x},{z}: {total} blocks"
            key = int(x), int(z)
            assert key not in rows
            rows[key] = digest, counts
        return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidates", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    baseline = read(args.reference)
    output = []
    for path in args.candidates:
        candidate = read(path)
        assert baseline.keys() == candidate.keys()
        changed = sum(baseline[key][0] != candidate[key][0] for key in baseline)
        counts_changed = sum(baseline[key][1] != candidate[key][1] for key in baseline)
        row = dict(reference=args.reference.name, candidate=path.name, chunks=len(baseline),
                   hash_mismatches=changed, count_mismatches=counts_changed)
        output.append(row)
        print(row)
    assert not args.output.exists(), args.output
    with args.output.open("w", newline="") as dest:
        writer = csv.DictWriter(dest, fieldnames=list(output[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(output)
    if any(row["hash_mismatches"] for row in output):
        raise SystemExit("Block digests differ; investigate before timing.")


if __name__ == "__main__":
    main()
