# PvPPokeGo — public APK build mirror

This repository is a **build-only, public snapshot** of PvPPokeGo **0.5.29 Standard**, exported from the private canonical repository at commit `cb00b81607706df99fcd9837dd27fc6e4fc4c4bb`.

- The original project remains private and is **not automatically synchronized**.
- No private repository history, release keystore or signing passwords are included.
- The GitHub Actions workflow assembles **PvPPokeGo-0.5.29-QA-debug.apk** using the debug signing key for testing. It is not a permanent production release signature.
- Source code is publicly readable. The app's pre-existing development credits are preserved.
- Third-party data and imagery are fetched by the workflow, and relevant notices are in `THIRD_PARTY_NOTICES.md`.
- APK output is stored in the workflow's **Artifacts**, under *Actions*.

## Build

Go to **Actions → Build PvPPokeGo 0.5.29 Standard APK → Run workflow**, or use the automatically triggered build on the initial commit. Download the APK from the workflow artifacts after all tests pass.

This build mirror is isolated from the original repository. Fixes must be made in the private canonical project and explicitly copied here after review.

The first build is triggered by commits to `main`. If no run appears, use the **Run workflow** control in the Actions tab.

2026-10-07: compact reserve-card HUD and exact user-provided transparent launcher, source branch release/0.5.29.

0.5.29 visual patch: old 0.5.17 reserve/switch-matchup icons and normal move HUD restored; predictive diagnostics kept in debug mode. Official transparent launcher retained.

0.5.29: ally team recognition regains 0.5.17 text+CP fallback and wider screen scan with confidence checks. Original 0.5.17 HUD visual remains intact.

## Hybrid data sync preview (2026-10-15)
Isolated preview branch `preview/hybrid-data-sync-20261015` mirrors hybrid snapshot storage, WorkManager scheduling, loader and tests from private PR #37. The main branch remains the known 0.5.29 baseline. This preview has NOT passed CI and MUST NOT be described as a tested APK. The built-in update feed is pinned to this repository's hybrid-feed branch. Updates require a successful validated feed publication.

## Hybrid sync preview validation (2026-10-15)
- Full UI and OCR stay on the original 0.5.29 baseline. No production merge.
- Connected preview defaults to daily updates on unmetered Wi-Fi, using a fixed GitHub HTTPS feed URL and SHA-256 verified seven-file snapshots.
- The public CI now unit-tests the feed manifest generator, compiles and tests the APK, verifies its debug signature and publishes validated data to the separate hybrid-feed branch.
- Published hybrid-feed data comes from pinned PvPoke source assets plus currently fetched official rankings; the license and snapshot provenance are included.
- Public HTTPS plus SHA-256 is integrity verification, not a separately signed manifest. A dedicated signing key is still recommended for production.
- A green CI run is required before using a new APK. Actual device overlay/OCR regression QA remains essential.

## QA preview 0.5.30 — experimental features
- Three distinct observed opponent slots with stable CP/HP, reappearance after switches, and unknown-slot placeholders. Optional compact HUD toggle under settings.
- Daily hybrid sync preferences now configurable inside the Android app, with current/pending dataset status, one-off network check, and portable backup/restore of update settings.
- Diagnostic export includes the local hybrid data status.
- A complete, editable BUILD-MIRROR source ZIP is uploaded by the public CI alongside the debug APK. The private canonical GitHub repository holds additional internal documentation/history; the ZIP is the full buildable Android snapshot, not private repo Git history.
- Physical-device capture, OCR and PvP regressions remain to be validated. No independent detached signature for remote manifest, and no permanent production release signing key is bundled.
