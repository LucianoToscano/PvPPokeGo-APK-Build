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
