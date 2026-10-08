#!/usr/bin/env python3
"""Package a PvPoke snapshot into app/src/main/assets for offline battle use."""
from __future__ import annotations

import hashlib
import json
import re
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

OUT = Path("app/src/main/assets")
REF = "f627e89e53c0c7b903fff097df7a0ad0ac95decc"
RAW = f"https://raw.githubusercontent.com/pvpoke/pvpoke/{REF}/src/data"
RAW_JS = f"https://raw.githubusercontent.com/pvpoke/pvpoke/{REF}/src/js"
RAW_ROOT = f"https://raw.githubusercontent.com/pvpoke/pvpoke/{REF}"
PVP_TURN_MS = 500
LEAGUES = (1500, 2500, 10000)

def fetch_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "PvPPokeGo-offline-packager/1.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.load(r)

def fetch_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "PvPPokeGo-offline-packager/1.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read().decode("utf-8")


def compact_json_bytes(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def extract_cpms(pokemon_js: str) -> list[float]:
    match = re.search(r"var\s+cpms\s*=\s*\[([^\]]+)\]", pokemon_js, re.S)
    if not match:
        raise RuntimeError("Could not locate PvPoke cpms array in Pokemon.js")
    values = [
        float(token.strip())
        for token in match.group(1).split(",")
        if token.strip()
    ]
    if len(values) < 100:
        raise RuntimeError(f"PvPoke cpms array is unexpectedly short: {len(values)}")
    return values

def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)

    moves = fetch_json(f"{RAW}/gamemaster/moves.json")
    pokemon = fetch_json(f"{RAW}/gamemaster/pokemon.json")
    pokemon_js_url = f"{RAW_JS}/pokemon/Pokemon.js"
    base_url = f"{RAW}/gamemaster/base.json"
    license_url = f"{RAW_ROOT}/LICENSE"
    cpms = extract_cpms(fetch_text(pokemon_js_url))
    base = fetch_json(base_url)
    pvpoke_license = fetch_text(license_url)
    settings = base.get("settings", {})
    max_buff_stages = int(settings.get("maxBuffStages", 4))
    buff_divisor = float(settings.get("buffDivisor", 4))
    gamemaster_payload = {"pokemon": pokemon, "moves": moves}
    gamemaster_bytes = compact_json_bytes(gamemaster_payload)
    (OUT / "gamemaster.json").write_bytes(gamemaster_bytes)

    cpms_payload = {"format": "PvPPokeGo-pvpoke-cpms", "version": 1, "cpms": cpms}
    cpms_bytes = compact_json_bytes(cpms_payload)
    (OUT / "pvpoke_cpms.json").write_bytes(cpms_bytes)
    license_bytes = pvpoke_license.encode("utf-8")
    (OUT / "pvpoke_LICENSE.txt").write_bytes(license_bytes)

    ranking_sources = {}
    ranking_hashes = {}
    for cp in LEAGUES:
        url = f"https://pvpoke.com/data/rankings/all/overall/rankings-{cp}.json"
        ranking = fetch_json(url)
        ranking_bytes = compact_json_bytes(ranking)
        (OUT / f"rankings-{cp}.json").write_bytes(ranking_bytes)
        ranking_sources[str(cp)] = url
        ranking_hashes[str(cp)] = sha256_bytes(ranking_bytes)

    metadata = {
        "format": "PvPPokeGo-pvpoke-snapshot",
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "pvpokeRef": REF,
        "turnDurationMs": PVP_TURN_MS,
        "settings": {
            "maxBuffStages": max_buff_stages,
            "buffDivisor": buff_divisor,
        },
        "gamemasterSources": {
            "moves": f"{RAW}/gamemaster/moves.json",
            "pokemon": f"{RAW}/gamemaster/pokemon.json",
            "base": base_url,
            "cpms": pokemon_js_url,
            "license": license_url,
        },
        "rankingSources": ranking_sources,
        "sha256": {
            "gamemaster.json": sha256_bytes(gamemaster_bytes),
            "pvpoke_cpms.json": sha256_bytes(cpms_bytes),
            "pvpoke_LICENSE.txt": sha256_bytes(license_bytes),
            "rankings": ranking_hashes,
        },
    }
    (OUT / "pvpoke_snapshot.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print("Offline PvPoke assets packaged:", ", ".join(p.name for p in OUT.glob("*.json")))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
