#!/usr/bin/env python3
"""Generate compact, form-aware Pokémon visual assets for PvPPokeGo.

The APK stores perceptual fingerprints (dHash + hue histogram) for recognition
and a small transparent sprite atlas for HUD reserve icons. Full-resolution
source artwork is never bundled. Both outputs are keyed by PvPoke speciesId
whenever a distinct Pokémon GO render can be resolved, with National Dex kept
as a fallback.

Source renders: PokeMiners pogo_assets 256x256 addressable Pokémon icons.
"""
from __future__ import annotations

import io
import json
import math
import re
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageOps

ASSETS = Path("app/src/main/assets")
GM = ASSETS / "gamemaster.json"
OUT = ASSETS / "pokemon_visual_fingerprints.json"
ICON_ATLAS_OUT = ASSETS / "pokemon_icon_atlas.png"
ICON_MAP_OUT = ASSETS / "pokemon_icon_atlas.json"
ICON_CELL = 48
ICON_COLUMNS = 32
BASE_URL = (
    "https://raw.githubusercontent.com/PokeMiners/pogo_assets/master/"
    "Images/Pokemon%20-%20256x256/Addressable%20Assets/{key}.icon.png"
)
WORKERS = 18

# PvPoke suffix -> common Pokémon GO asset-form spellings.
FORM_ALIASES = {
    "alolan": ("ALOLA", "ALOLAN"),
    "galarian": ("GALARIAN", "GALAR"),
    "hisuian": ("HISUIAN", "HISUI"),
    "paldean": ("PALDEAN", "PALDEA"),
    "10": ("TEN_PERCENT",),
    "50": ("FIFTY_PERCENT",),
    "complete": ("COMPLETE",),
    "origin": ("ORIGIN",),
    "therian": ("THERIAN",),
    "incarnate": ("INCARNATE",),
    "attack": ("ATTACK",),
    "defense": ("DEFENSE",),
    "speed": ("SPEED",),
    "sunshine": ("SUNNY",),
    "rainy": ("RAINY",),
    "snowy": ("SNOWY",),
    "pom_pom": ("POMPOM",),
    "pau": ("PAU",),
    "sensu": ("SENSU",),
    "baile": ("BAILE",),
    "plant": ("PLANT", "PLANT_CLOAK"),
    "sandy": ("SANDY", "SANDY_CLOAK"),
    "trash": ("TRASH", "TRASH_CLOAK"),
    "mega": ("MEGA",),
    "mega_x": ("MEGA_X",),
    "mega_y": ("MEGA_Y",),
    "primal": ("PRIMAL",),
    "galarian_standard": ("GALARIAN_STANDARD", "GALARIAN"),
    "galarian_zen": ("GALARIAN_ZEN",),
    "single_strike": ("SINGLE_STRIKE",),
    "rapid_strike": ("RAPID_STRIKE",),
    "full_belly": ("FULL_BELLY",),
    "hangry": ("HANGRY",),
    "dusk_mane": ("DUSK_MANE",),
    "dawn_wings": ("DAWN_WINGS",),
    "ultra": ("ULTRA",),
    "crowned_sword": ("CROWNED_SWORD",),
    "crowned_shield": ("CROWNED_SHIELD",),
    "resolute": ("RESOLUTE",),
    "sky": ("SKY",),
    "white": ("WHITE",),
    "black": ("BLACK",),
}

DEFAULT_FORMS = (
    "NORMAL", "ORDINARY", "INCARNATE", "ALTERED", "STANDARD", "AVERAGE",
    "BUSTED", "DISGUISED", "MALE", "FEMALE", "ARIA", "RED", "PLANT",
    "WEST", "SPRING", "OVERCAST", "LAND", "SHIELD", "MIDDAY", "SOLO",
    "AMPED", "ICE", "FULL_BELLY", "SINGLE_STRIKE", "ZERO", "HERO",
    "TWO_SEGMENT", "CURLY", "GREEN_PLUMAGE", "FAMILY_OF_FOUR", "CHEST",
    "COUNTERFEIT", "SUNNY", "BAILE", "TEN_PERCENT", "COMBAT",
)


def fetch_bytes(key: str) -> bytes | None:
    url = BASE_URL.format(key=key)
    for attempt in range(4):
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "PvPPokeGo-visual-bank/2.0"},
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None
            if 500 <= exc.code < 600 and attempt < 3:
                time.sleep(0.35 * (attempt + 1))
                continue
            raise
        except (urllib.error.URLError, ConnectionError, TimeoutError, OSError):
            if attempt < 3:
                time.sleep(0.35 * (attempt + 1))
                continue
            # A transient CDN failure for one render must not abort the whole bank.
            # Missing entries are counted and the build's minimum-coverage gate below
            # still protects against a catastrophically incomplete asset set.
            return None
    return None


def normalized_species_base(entry: dict) -> str:
    name = str(entry.get("speciesName") or "").split(" (")[0].lower()
    return re.sub(r"[^a-z0-9]+", "_", name).strip("_")


def source_candidates(entry: dict) -> list[str]:
    dex = int(entry.get("dex", 0) or 0)
    sid = str(entry.get("speciesId") or "").lower()
    if dex <= 0 or not sid:
        return []

    # Shadow aura is not a different static model and should not create a duplicate
    # fingerprint that would tie the normal form.
    sid = re.sub(r"_shadow$", "", sid)
    base = normalized_species_base(entry)
    suffix = sid[len(base):].strip("_") if base and sid.startswith(base) else ""

    forms: list[str] = []
    if suffix:
        forms.append(suffix.upper())
        forms.extend(FORM_ALIASES.get(suffix, ()))
        forms.append(suffix.upper().replace("_", ""))
        parts = suffix.split("_")
        if len(parts) > 1:
            forms.extend((parts[-1].upper(), parts[0].upper()))
            forms.extend(FORM_ALIASES.get(parts[0], ()))
    else:
        forms.extend(DEFAULT_FORMS)

    result: list[str] = []
    seen = set()
    for form in forms:
        key = f"pm{dex}.f{form}"
        if key not in seen:
            seen.add(key)
            result.append(key)

    # Plain dex sprite is the fallback for the base form.
    plain = f"pm{dex}"
    if plain not in seen:
        result.append(plain)
    return result


def normalize_image(raw: bytes) -> Image.Image:
    img = Image.open(io.BytesIO(raw)).convert("RGBA")
    alpha = img.getchannel("A")
    bbox = alpha.point(lambda a: 255 if a > 12 else 0).getbbox()
    if bbox:
        img = img.crop(bbox)

    side = max(img.width, img.height, 1)
    pad = max(2, int(side * 0.08))
    canvas = Image.new("RGBA", (side + pad * 2, side + pad * 2), (255, 255, 255, 255))
    fitted = ImageOps.contain(img, (side, side), Image.Resampling.LANCZOS)
    x = (canvas.width - fitted.width) // 2
    y = (canvas.height - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas.convert("RGB").resize((72, 72), Image.Resampling.LANCZOS)


def normalize_icon(raw: bytes) -> Image.Image:
    """Small transparent display sprite; recognition still uses white-normalized art."""
    img = Image.open(io.BytesIO(raw)).convert("RGBA")
    alpha = img.getchannel("A")
    bbox = alpha.point(lambda a: 255 if a > 12 else 0).getbbox()
    if bbox:
        img = img.crop(bbox)

    canvas = Image.new("RGBA", (ICON_CELL, ICON_CELL), (0, 0, 0, 0))
    fitted = ImageOps.contain(
        img,
        (ICON_CELL - 4, ICON_CELL - 4),
        Image.Resampling.LANCZOS,
    )
    x = (ICON_CELL - fitted.width) // 2
    y = (ICON_CELL - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def dhash64(img: Image.Image) -> str:
    gray = img.convert("L").resize((9, 8), Image.Resampling.LANCZOS)
    px = list(gray.getdata())
    value = 0
    bit = 0
    for y in range(8):
        row = y * 9
        for x in range(8):
            if px[row + x] > px[row + x + 1]:
                value |= 1 << bit
            bit += 1
    return f"{value:016x}"


def hue_histogram(img: Image.Image, bins: int = 12) -> list[int]:
    hsv = img.convert("HSV")
    counts = [0.0] * bins
    total = 0.0
    for h, sat8, val8 in hsv.getdata():
        sat = sat8 / 255.0
        val = val8 / 255.0
        if sat < 0.12 and val > 0.72:
            continue
        weight = max(0.08, sat) * (0.35 + 0.65 * (1.0 - abs(val - 0.55)))
        idx = min(bins - 1, int((h / 256.0) * bins))
        counts[idx] += weight
        total += weight
    if total <= 1e-9:
        return [0] * bins
    scaled = [int(round((c / total) * 255.0)) for c in counts]
    diff = 255 - sum(scaled)
    scaled[max(range(len(scaled)), key=lambda i: scaled[i])] += diff
    return scaled


def build_one(entry: dict) -> dict:
    sid = str(entry.get("speciesId") or "")
    dex = int(entry.get("dex", 0) or 0)
    if not sid or dex <= 0 or sid.endswith("_shadow"):
        return {"skip": sid}

    for key in source_candidates(entry):
        raw = fetch_bytes(key)
        if raw:
            img = normalize_image(raw)
            icon = normalize_icon(raw)
            icon_buf = io.BytesIO()
            icon.save(icon_buf, format="PNG", optimize=True)
            return {
                "speciesId": sid,
                "dex": dex,
                "sourceKey": key,
                "dhash": dhash64(img),
                "hue": hue_histogram(img),
                "_iconPng": icon_buf.getvalue(),
            }
    return {"speciesId": sid, "dex": dex, "error": "sprite-not-found"}


def main() -> int:
    if not GM.exists():
        raise SystemExit(f"Missing {GM}; run package_offline_pvpoke_assets.py first")

    gm = json.loads(GM.read_text(encoding="utf-8"))
    # One fingerprint per PvPoke identity. Exact duplicate speciesIds are irrelevant.
    by_id = {}
    for entry in gm.get("pokemon", []):
        sid = str(entry.get("speciesId") or "")
        if sid and not sid.endswith("_shadow") and int(entry.get("dex", 0) or 0) > 0:
            by_id.setdefault(sid, entry)
    entries_in = list(by_id.values())
    if len(entries_in) < 500:
        raise SystemExit(f"Gamemaster exposed only {len(entries_in)} visual identities")

    built: list[dict] = []
    failures: list[dict] = []
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        for item in pool.map(build_one, entries_in):
            if item.get("error"):
                failures.append(item)
            elif item.get("speciesId"):
                built.append(item)

    built.sort(key=lambda x: x["speciesId"])

    # Build one compact atlas so the HUD can draw the user's reserve Pokémon without
    # network access and without shipping the original 256x256 source files.
    rows = max(1, math.ceil(len(built) / ICON_COLUMNS))
    atlas = Image.new(
        "RGBA",
        (ICON_COLUMNS * ICON_CELL, rows * ICON_CELL),
        (0, 0, 0, 0),
    )
    icon_entries = []
    for index, item in enumerate(built):
        raw_icon = item.pop("_iconPng", None)
        if raw_icon:
            icon = Image.open(io.BytesIO(raw_icon)).convert("RGBA")
            col = index % ICON_COLUMNS
            row = index // ICON_COLUMNS
            x = col * ICON_CELL
            y = row * ICON_CELL
            atlas.alpha_composite(icon, (x, y))
            icon_entries.append({
                "speciesId": item["speciesId"],
                "dex": item["dex"],
                "x": x,
                "y": y,
                "w": ICON_CELL,
                "h": ICON_CELL,
            })

    atlas.save(ICON_ATLAS_OUT, format="PNG", optimize=True)
    ICON_MAP_OUT.write_text(
        json.dumps(
            {
                "format": "PvPPokeGo-pokemon-icon-atlas",
                "version": 1,
                "cellSize": ICON_CELL,
                "width": atlas.width,
                "height": atlas.height,
                "entries": icon_entries,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        encoding="utf-8",
    )

    # A missing form does not block the build, but a catastrophically incomplete bank does.
    if len(built) < 500:
        raise SystemExit(f"Only {len(built)} visual fingerprints generated ({len(failures)} missing)")

    payload = {
        "format": "PvPPokeGo-visual-fingerprints",
        "version": 2,
        "source": "PokeMiners pogo_assets Pokémon addressable renders",
        "sourceUrlTemplate": BASE_URL,
        "entries": built,
        "missingSpeciesIds": [f["speciesId"] for f in failures],
    }
    OUT.write_text(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    form_count = sum(1 for item in built if ".f" in item["sourceKey"])
    print(
        f"Visual fingerprint bank v2: {len(built)} species/form identities "
        f"({form_count} form renders), {len(failures)} missing -> {OUT}; "
        f"{len(icon_entries)} HUD icons -> {ICON_ATLAS_OUT}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
