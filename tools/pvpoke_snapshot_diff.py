#!/usr/bin/env python3
"""Human-readable diff between two bundled PvPoke asset snapshots.

Inspired by the useful snapshot-comparison pattern in pogo-gbl-analyzer, but
implemented specifically for PvPPokeGo's compact offline assets.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def by_id(items, key):
    return {str(x.get(key, "")): x for x in items if str(x.get(key, ""))}


def normalized_move(move):
    return {
        "type": move.get("type"),
        "power": move.get("power"),
        "energy": move.get("energy"),
        "energyGain": move.get("energyGain"),
        "cooldown": move.get("cooldown"),
        "buffs": move.get("buffs"),
        "buffsSelf": move.get("buffsSelf"),
        "buffsOpponent": move.get("buffsOpponent"),
        "buffTarget": move.get("buffTarget"),
        "buffApplyChance": move.get("buffApplyChance"),
        "damageMethod": move.get("damageMethod"),
    }


def normalized_pool(mon):
    return {
        "fastMoves": sorted(mon.get("fastMoves") or []),
        "chargedMoves": sorted(mon.get("chargedMoves") or []),
    }


def ranking_rows(path: Path):
    rows = load_json(path, [])
    out = {}
    for rank, row in enumerate(rows, start=1):
        sid = str(row.get("speciesId") or row.get("speciesName") or "")
        if sid:
            out[sid] = {
                "rank": rank,
                "moveset": list(row.get("moveset") or []),
                "score": row.get("score"),
            }
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("old_dir", type=Path)
    ap.add_argument("new_dir", type=Path)
    ap.add_argument("--output", type=Path)
    args = ap.parse_args()

    old_gm = load_json(args.old_dir / "gamemaster.json", {"moves": [], "pokemon": []})
    new_gm = load_json(args.new_dir / "gamemaster.json", {"moves": [], "pokemon": []})

    old_moves = by_id(old_gm.get("moves", []), "moveId")
    new_moves = by_id(new_gm.get("moves", []), "moveId")
    old_pokemon = by_id(old_gm.get("pokemon", []), "speciesId")
    new_pokemon = by_id(new_gm.get("pokemon", []), "speciesId")

    lines = ["PvPPokeGo PvPoke dataset diff", ""]

    added_moves = sorted(set(new_moves) - set(old_moves))
    removed_moves = sorted(set(old_moves) - set(new_moves))
    changed_moves = sorted(
        mid for mid in set(old_moves) & set(new_moves)
        if normalized_move(old_moves[mid]) != normalized_move(new_moves[mid])
    )
    lines += [
        f"Moves: +{len(added_moves)} -{len(removed_moves)} changed={len(changed_moves)}",
    ]
    for mid in changed_moves[:80]:
        lines.append(f"  MOVE {mid}: {normalized_move(old_moves[mid])} -> {normalized_move(new_moves[mid])}")
    for mid in added_moves[:40]:
        lines.append(f"  +MOVE {mid}")
    for mid in removed_moves[:40]:
        lines.append(f"  -MOVE {mid}")

    added_pokemon = sorted(set(new_pokemon) - set(old_pokemon))
    removed_pokemon = sorted(set(old_pokemon) - set(new_pokemon))
    changed_pools = sorted(
        sid for sid in set(old_pokemon) & set(new_pokemon)
        if normalized_pool(old_pokemon[sid]) != normalized_pool(new_pokemon[sid])
    )
    lines += [
        "",
        f"Pokemon: +{len(added_pokemon)} -{len(removed_pokemon)} movepool_changed={len(changed_pools)}",
    ]
    for sid in changed_pools[:120]:
        lines.append(f"  POOL {sid}: {normalized_pool(old_pokemon[sid])} -> {normalized_pool(new_pokemon[sid])}")
    for sid in added_pokemon[:60]:
        lines.append(f"  +POKEMON {sid}")
    for sid in removed_pokemon[:60]:
        lines.append(f"  -POKEMON {sid}")

    for cp in (1500, 2500, 10000):
        old_rank = ranking_rows(args.old_dir / f"rankings-{cp}.json")
        new_rank = ranking_rows(args.new_dir / f"rankings-{cp}.json")
        common = set(old_rank) & set(new_rank)
        move_changes = sorted(
            sid for sid in common
            if sorted(old_rank[sid]["moveset"]) != sorted(new_rank[sid]["moveset"])
        )
        shifts = sorted(
            ((sid, old_rank[sid]["rank"], new_rank[sid]["rank"])
             for sid in common
             if old_rank[sid]["rank"] != new_rank[sid]["rank"]),
            key=lambda x: abs(x[1] - x[2]),
            reverse=True,
        )
        lines += [
            "",
            f"Rankings CP{cp}: moveset_changed={len(move_changes)} rank_shifted={len(shifts)}",
        ]
        for sid in move_changes[:60]:
            lines.append(
                f"  MOVESET {sid}: {old_rank[sid]['moveset']} -> {new_rank[sid]['moveset']}"
            )
        for sid, old_pos, new_pos in shifts[:40]:
            lines.append(f"  RANK {sid}: {old_pos} -> {new_pos} ({old_pos-new_pos:+d})")

    text = "\n".join(lines) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
