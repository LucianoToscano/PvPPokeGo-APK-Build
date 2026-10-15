#!/usr/bin/env python3
"""Download and optimize Pokémon HOME PNG renders to 256px transparent WebP.

Never commits Pokémon artwork into a public repository. Output is an optional
local cache/artifact; source artwork is © The Pokémon Company.
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tempfile
import time
from urllib.request import Request, urlopen
from PIL import Image, UnidentifiedImageError

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/java/com/lucianotoscano/pvppokego/data/PokemonImageCatalog.kt"
URL_ROOT = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home"


def catalog():
    source = CATALOG.read_text(encoding="utf8")
    match = re.search(r'val entries = "([0-9a-z:,]+)"', source)
    if not match:
        raise RuntimeError("Mega catalog changed; update this parser before downloading")
    yield from ((dex, "base", dex) for dex in range(1, 1026))
    for item in match.group(1).split(","):
        token, source_id = item.split(":")
        form = "mega_x" if token.endswith("x") else "mega_y" if token.endswith("y") else "mega"
        dex = int(token.rstrip("xy"))
        yield dex, form, int(source_id)


def validate(path):
    try:
        if not path.is_file() or path.stat().st_size < 40:
            return False
        with Image.open(path) as img:
            img.verify()
        with Image.open(path) as img:
            return img.format == "WEBP" and min(img.size) >= 16 and max(img.size) <= 256
    except (OSError, ValueError, UnidentifiedImageError):
        return False


def fetch(entry, output, retries, timeout):
    dex, form, source_id = entry
    key = f"{dex:04d}_{form}"
    dest = output / (key + ".webp")
    if validate(dest):
        return {"key": key, "status": "cached", "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()}
    error = ""
    for retry in range(retries + 1):
        try:
            req = Request(f"{URL_ROOT}/{source_id}.png", headers={"User-Agent": "PvPPokeGo-image-db/3.0"})
            with urlopen(req, timeout=timeout) as response:
                raw = response.read(6 * 1024 * 1024 + 1)
            if len(raw) > 6 * 1024 * 1024 or not raw.startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError("Invalid PNG or asset too large")
            with Image.open(io.BytesIO(raw)) as img:
                img.load()
                if not (32 <= img.width <= 2048 and 32 <= img.height <= 2048):
                    raise ValueError("Unexpected image dimensions")
                rendered = img.convert("RGBA")
                rendered.thumbnail((256, 256), Image.Resampling.LANCZOS)
                with tempfile.NamedTemporaryFile(dir=output, suffix=".tmp", delete=False) as tmp:
                    temp = Path(tmp.name)
                try:
                    rendered.save(temp, format="WEBP", quality=88, method=5)
                    if not validate(temp):
                        raise ValueError("WebP verification failed")
                    os.replace(temp, dest)
                finally:
                    temp.unlink(missing_ok=True)
            return {"key": key, "status": "downloaded", "sha256": hashlib.sha256(dest.read_bytes()).hexdigest()}
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
            if retry < retries:
                time.sleep(retry + 1)
    return {"key": key, "status": "failed", "sourceId": source_id, "error": error}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="pokemon_images")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--workers", type=int, default=6)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=15)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    all_entries = sorted(catalog(), key=lambda x: (x[1] == "base", x[0], x[1]))
    assert len(all_entries) == 1071 and len({(x[0], x[1]) for x in all_entries}) == 1071
    if args.limit > 0:
        all_entries = all_entries[:args.limit]
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, min(args.workers, 12))) as pool:
        results = list(pool.map(lambda e: fetch(e, output, args.retries, args.timeout), all_entries))
    report = {"requested": len(results), "downloaded": sum(x["status"] == "downloaded" for x in results),
              "cached": sum(x["status"] == "cached" for x in results),
              "failed": sum(x["status"] == "failed" for x in results), "entries": results,
              "copyright": "Images © The Pokémon Company. Redistribution rights are not conferred by PokeAPI."}
    (output / "image-sync-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"Images checked: {report['requested']} downloaded: {report['downloaded']} "
          f"cached: {report['cached']} failed: {report['failed']}")
    return 1 if report["failed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
