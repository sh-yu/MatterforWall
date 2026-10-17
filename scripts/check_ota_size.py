#!/usr/bin/env python3
"""Fail when a firmware image does not fit both OTA slots."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


def parse_int(value: str) -> int:
    return int(value.strip(), 0)


def ota_slots(partition_file: Path) -> list[int]:
    sizes: list[int] = []
    with partition_file.open(encoding="utf-8") as stream:
        rows = csv.reader(line for line in stream if not line.lstrip().startswith("#"))
        for row in rows:
            if len(row) >= 5 and row[1].strip() == "app" and row[2].strip().startswith("ota_"):
                sizes.append(parse_int(row[4]))
    if len(sizes) < 2:
        raise ValueError("partition table must contain at least two OTA app slots")
    return sizes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("partitions", type=Path)
    parser.add_argument("--reserve", type=int, default=4096,
                        help="required unused bytes per slot")
    args = parser.parse_args()
    image_size = args.image.stat().st_size
    slot_size = min(ota_slots(args.partitions))
    available = slot_size - image_size
    print(f"image={image_size} slot={slot_size} free={available} reserve={args.reserve}")
    if available < args.reserve:
        print("ERROR: firmware exceeds OTA slot safety limit")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

