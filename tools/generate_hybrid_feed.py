#!/usr/bin/env python3
"""Build a versioned, immutable-data-compatible PvPPokeGo hybrid feed.

Run after tools/package_offline_pvpoke_assets.py. Never modify app assets.
Outputs manifest.json plus checksum-matched source files.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

REQUIRED = (
    "gamemaster.json", "rankings-1500.json", "rankings-2500.json",
    "rankings-10000.json", "pvpoke_cpms.json",
)
EXTRAS = ("pvpoke_snapshot.json", "pvpoke_LICENSE.txt")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build(source: Path, dest: Path) -> dict:
    if source.resolve() == dest.resolve():
        raise ValueError("Source and destination must differ")
    data = {name: (source / name).read_bytes() for name in (*REQUIRED, *EXTRAS)}
    master = json.loads(data["gamemaster.json"])
    if len(master.get("pokemon", [])) < 100 or len(master.get("moves", [])) < 100:
        raise ValueError("Game Master unexpectedly small")
    for cp in (1500, 2500, 10000):
        ranks = json.loads(data[f"rankings-{cp}.json"])
        if not isinstance(ranks, list) or len(ranks) < 100:
            raise ValueError(f"Rankings {cp} unexpectedly small")
    if len(json.loads(data["pvpoke_cpms.json"]).get("cpms", [])) < 100:
        raise ValueError("CPM table unexpectedly small")
    if len(data["pvpoke_LICENSE.txt"]) < 50:
        raise ValueError("PvPoke license missing")
    entries = [
        {"name": name, "sha256": sha256(data[name]), "size": len(data[name])}
        for name in (*REQUIRED, *EXTRAS)
    ]
    version_digest = sha256(
        json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    )[:24]
    manifest = {
        "schema": "pvppokego-hybrid-v1",
        "version": f"pvpoke-{version_digest}",
        "files": entries,
    }
    dest.mkdir(parents=True, exist_ok=True)
    for name, content in data.items():
        (dest / name).write_bytes(content)
    (dest / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Hybrid feed prepared: {manifest['version']}; {sum(map(len, data.values()))} bytes")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--output", type=Path, default=Path("hybrid-feed"))
    args = parser.parse_args()
    build(args.source, args.output)


if __name__ == "__main__":
    main()
