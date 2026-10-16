#!/usr/bin/env python3
"""Offline synthetic tests for the versioned hybrid manifest generator."""
import json
import tempfile
import unittest
from pathlib import Path

from generate_hybrid_feed import build


class HybridFeedGeneratorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.src = Path(self.tmp.name) / "assets"
        self.dst = Path(self.tmp.name) / "feed"
        self.src.mkdir()
        payloads = {
            "gamemaster.json": {"pokemon": [{} for _ in range(120)], "moves": [{} for _ in range(120)]},
            "rankings-1500.json": [{} for _ in range(120)],
            "rankings-2500.json": [{} for _ in range(120)],
            "rankings-10000.json": [{} for _ in range(120)],
            "pvpoke_cpms.json": {"cpms": [0.5] * 120},
            "pvpoke_snapshot.json": {"pvpokeRef": "test-ref"},
        }
        for name, data in payloads.items():
            (self.src / name).write_text(json.dumps(data), encoding="utf-8")
        (self.src / "pvpoke_LICENSE.txt").write_text("Licensed test data " * 8)

    def test_manifest_checksum_and_deterministic_version(self):
        first = build(self.src, self.dst)
        second = build(self.src, self.dst)
        self.assertEqual(first["version"], second["version"])
        self.assertEqual(first, json.loads((self.dst / "manifest.json").read_text()))
        self.assertEqual(len(first["files"]), 7)
        for entry in first["files"]:
            self.assertEqual((self.dst / entry["name"]).stat().st_size, entry["size"])
        (self.src / "pvpoke_snapshot.json").write_text(
            json.dumps({"pvpokeRef": "test-ref", "generatedAtUtc": "new timestamp"}))
        self.assertEqual(first["version"], build(self.src, self.dst)["version"])
        (self.src / "pvpoke_LICENSE.txt").write_text("Edited licensed test data " * 8)
        self.assertNotEqual(first["version"], build(self.src, self.dst)["version"])

    def test_refuses_incomplete_rankings(self):
        (self.src / "rankings-1500.json").write_text("[{}]")
        with self.assertRaises(ValueError):
            build(self.src, self.dst)


if __name__ == "__main__":
    unittest.main()
