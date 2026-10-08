# PvPPokeGo — public APK build mirror

This repository is a **build-only, public snapshot** of PvPPokeGo **0.5.26 Standard**, exported from the private canonical repository at commit `ec8610ba16ea8528e2f9c0268170d5a869d0387c`.

- The original project remains private and is **not automatically synchronized**.
- No private repository history, release keystore or signing passwords are included.
- The GitHub Actions workflow assembles **PvPPokeGo-0.5.26-QA-debug.apk** using the debug signing key for testing. It is not a permanent production release signature.
- Source code is publicly readable. The app's pre-existing development credits are preserved.
- Third-party data and imagery are fetched by the workflow, and relevant notices are in `THIRD_PARTY_NOTICES.md`.
- APK output is stored in the workflow's **Artifacts**, under *Actions*.

## Build

Go to **Actions → Build PvPPokeGo 0.5.26 Standard APK → Run workflow**, or use the automatically triggered build on the initial commit. Download the APK from the workflow artifacts after all tests pass.

This build mirror is isolated from the original repository. Fixes must be made in the private canonical project and explicitly copied here after review.

The first build is triggered by commits to `main`. If no run appears, use the **Run workflow** control in the Actions tab.
