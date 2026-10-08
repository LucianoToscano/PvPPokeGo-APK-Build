#!/usr/bin/env python3
"""
Build a compact, local PvPoke dataset for PvPPokeGo.

The app should use the generated files offline during battles. This script is
intended for development/release-time updates, not for real-time battle calls.
"""

from __future__ import annotations

import argparse
import csv
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

DEFAULT_REF = "master"
RAW_BASE = "https://raw.githubusercontent.com/pvpoke/pvpoke/{ref}/src/data"
LEAGUES = (1500, 2500, 10000)


def fetch_json(url: str) -> Any:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "PvPPokeGo-data-updater/1.0"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()


def normalize_species_id(value: Any) -> str:
    return clean_text(value).lower()


def normalize_move_id(value: Any) -> str:
    return clean_text(value).upper().replace(" ", "_")


def normalize_type(value: Any) -> str:
    if isinstance(value, dict):
        value = value.get("type") or value.get("name") or value.get("id") or ""
    return clean_text(value).lower()


def ensure_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def extract_move_id(value: Any) -> str:
    if isinstance(value, str):
        return normalize_move_id(value)
    if isinstance(value, dict):
        return normalize_move_id(
            value.get("moveId")
            or value.get("move")
            or value.get("id")
            or value.get("name")
        )
    return ""


def move_usage(value: Any) -> float:
    if isinstance(value, dict):
        raw = value.get("uses") or value.get("usage") or value.get("weight") or 0
        try:
            return float(raw)
        except (TypeError, ValueError):
            return 0.0
    return 0.0


def write_tsv(path: Path, header: list[str], rows: Iterable[list[Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(header)
        for row in rows:
            writer.writerow([clean_text(cell) for cell in row])


def build_moves(moves_json: list[dict[str, Any]]) -> tuple[list[list[Any]], dict[str, dict[str, Any]]]:
    rows: list[list[Any]] = []
    by_id: dict[str, dict[str, Any]] = {}

    for raw in moves_json:
        move_id = normalize_move_id(raw.get("moveId"))
        if not move_id:
            continue

        energy_gain = int(raw.get("energyGain") or 0)
        energy_cost = int(raw.get("energy") or 0)
        row = [
            move_id,
            raw.get("name") or move_id,
            normalize_type(raw.get("type")),
            int(raw.get("power") or 0),
            energy_gain,
            energy_cost,
            int(raw.get("cooldown") or 0),
            int(raw.get("turns") or 0),
        ]
        rows.append(row)
        by_id[move_id] = raw

    rows.sort(key=lambda row: row[0])
    return rows, by_id


def build_pokemon(pokemon_json: list[dict[str, Any]]) -> list[list[Any]]:
    rows: list[list[Any]] = []

    for raw in pokemon_json:
        species_id = normalize_species_id(raw.get("speciesId"))
        if not species_id:
            continue

        raw_types = ensure_list(raw.get("types"))
        types = [normalize_type(item) for item in raw_types]
        types = [item for item in types if item]

        fast_moves = [
            extract_move_id(item)
            for item in ensure_list(raw.get("fastMoves"))
        ]
        charged_moves = [
            extract_move_id(item)
            for item in ensure_list(raw.get("chargedMoves"))
        ]

        rows.append([
            species_id,
            raw.get("speciesName") or species_id,
            "|".join(filter(None, types)),
            "|".join(filter(None, fast_moves)),
            "|".join(filter(None, charged_moves)),
        ])

    rows.sort(key=lambda row: row[0])
    return rows


def choose_ranked_moves(
    ranking: dict[str, Any],
    moves_by_id: dict[str, dict[str, Any]],
) -> tuple[str, list[str]]:
    moveset = [extract_move_id(item) for item in ensure_list(ranking.get("moveset"))]
    moveset = [item for item in moveset if item]

    explicit_fast = ""
    explicit_charged: list[str] = []
    for move_id in moveset:
        move = moves_by_id.get(move_id, {})
        if int(move.get("energyGain") or 0) > 0 and not explicit_fast:
            explicit_fast = move_id
        elif int(move.get("energy") or 0) > 0:
            explicit_charged.append(move_id)

    if explicit_fast and explicit_charged:
        return explicit_fast, explicit_charged[:2]

    moves = ranking.get("moves") if isinstance(ranking.get("moves"), dict) else {}
    fast_candidates = ensure_list(moves.get("fastMoves"))
    charged_candidates = ensure_list(moves.get("chargedMoves"))

    fast_candidates = sorted(fast_candidates, key=move_usage, reverse=True)
    charged_candidates = sorted(charged_candidates, key=move_usage, reverse=True)

    fast = extract_move_id(fast_candidates[0]) if fast_candidates else explicit_fast
    charged = [extract_move_id(item) for item in charged_candidates]
    charged = [item for item in charged if item]

    if not charged:
        charged = explicit_charged

    return fast, charged[:2]


def build_rankings(
    ranking_json: list[dict[str, Any]],
    league_cp: int,
    moves_by_id: dict[str, dict[str, Any]],
) -> list[list[Any]]:
    rows: list[list[Any]] = []

    for index, raw in enumerate(ranking_json, start=1):
        species_id = normalize_species_id(raw.get("speciesId"))
        if not species_id:
            continue

        fast_move, charged_moves = choose_ranked_moves(raw, moves_by_id)
        score = raw.get("score")
        if score is None:
            score = raw.get("rating")

        rows.append([
            league_cp,
            index,
            species_id,
            fast_move,
            "|".join(charged_moves),
            score if score is not None else "",
        ])

    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        default="data/pvpoke",
        help="Output directory for generated local battle data.",
    )
    parser.add_argument(
        "--ref",
        default=DEFAULT_REF,
        help="PvPoke git ref to import (default: master).",
    )
    parser.add_argument(
        "--skip-rankings",
        action="store_true",
        help="Import only Pokemon and move data.",
    )
    args = parser.parse_args()

    output = Path(args.output)
    base = RAW_BASE.format(ref=args.ref)

    moves_url = f"{base}/gamemaster/moves.json"
    pokemon_url = f"{base}/gamemaster/pokemon.json"

    moves_json = fetch_json(moves_url)
    pokemon_json = fetch_json(pokemon_url)

    move_rows, moves_by_id = build_moves(moves_json)
    pokemon_rows = build_pokemon(pokemon_json)

    write_tsv(
        output / "moves.tsv",
        [
            "move_id",
            "name",
            "type",
            "power",
            "energy_gain",
            "energy_cost",
            "cooldown_ms",
            "turns",
        ],
        move_rows,
    )
    write_tsv(
        output / "pokemon.tsv",
        ["species_id", "species_name", "types", "fast_moves", "charged_moves"],
        pokemon_rows,
    )

    ranking_sources: list[str] = []
    ranking_rows: list[list[Any]] = []
    if not args.skip_rankings:
        for league_cp in LEAGUES:
            ranking_url = (
                f"{base}/rankings/all/overall/"
                f"rankings-{league_cp}.json"
            )
            ranking_sources.append(ranking_url)
            ranking_json = fetch_json(ranking_url)
            ranking_rows.extend(build_rankings(ranking_json, league_cp, moves_by_id))

        write_tsv(
            output / "rankings.tsv",
            ["league_cp", "rank", "species_id", "fast_move", "charged_moves", "score"],
            ranking_rows,
        )

    metadata = {
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "pvpokeRef": args.ref,
        "sources": {
            "moves": moves_url,
            "pokemon": pokemon_url,
            "rankings": ranking_sources,
        },
        "counts": {
            "moves": len(move_rows),
            "pokemon": len(pokemon_rows),
            "rankingRows": len(ranking_rows),
        },
        "runtimePolicy": (
            "PvPPokeGo battle logic must read local generated data. "
            "Do not query PvPoke over the network during an active battle."
        ),
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "metadata.json").write_text(
        json.dumps(metadata, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(
        f"Generated {len(move_rows)} moves, {len(pokemon_rows)} Pokemon, "
        f"{len(ranking_rows)} ranking rows in {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
