#!/usr/bin/env python3
"""Dependency-free structural and safety validation for repository profiles."""

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def validate(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    required = {"schemaVersion", "manufacturer", "model", "profileVersion",
                "verification", "uart", "framing", "commands", "txPolicy"}
    missing = required - data.keys()
    if missing:
        raise ValueError(f"{path}: missing {sorted(missing)}")
    if data["schemaVersion"] != 1:
        raise ValueError(f"{path}: unsupported schema version")
    policy = data["txPolicy"]
    if policy.get("defaultAllow") is not False:
        raise ValueError(f"{path}: transmit policy must default deny")
    commands = set(policy.get("allowList", []))
    if "gas.open" in commands or any(command.startswith("elevator.") for command in commands):
        raise ValueError(f"{path}: forbidden command in allow-list")
    if data["verification"] != "CONTROL_VERIFIED" and commands:
        raise ValueError(f"{path}: unverified profile has enabled commands")


def main() -> int:
    profiles = [path for path in (ROOT / "profiles").rglob("*.json")
                if path.name != "profile.schema.json"]
    for profile in profiles:
        validate(profile)
        print(f"OK {profile.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

