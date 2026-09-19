# Contributing to PiKit

Thanks for looking. This file is short on purpose: the rules that matter are in
[AGENTS.md](AGENTS.md), which is written for a change to this repository whoever
makes it — a person or an agent — and [docs/README.md](docs/README.md), which says
how the documentation is organised.

## The short version

1. **Build and test before opening a pull request.**
   `python tools/build-apks.py` runs the tests, the checkers and all four APKs. If
   you are on a slow connection or already have the runtime images,
   `./gradlew :app:testX64DebugUnitTest` is the fast loop (353 tests, no device).

2. **One change per pull request.** A pull request that fixes a bug and renames a
   page is two changes, and the second one hides the first.
3. **Say what you measured.** This project's comments and documents carry numbers —
   node bounds from `uiautomator`, byte counts, window frames — because "looks
   better" cannot be argued with or re-checked. A claim about a layout needs a
   `uiautomator` pass, not a screenshot.
4. **Update the documents in the same change.** A new page, a new setting or a
   removed design lands with its chapter in `docs/`; `docs/ARCHITECTURE.md` and
   `docs/README.md` are the indexes, and a chapter that outlived its topic gets
   split rather than trimmed.
5. **User-visible text goes through the catalogs.** `locales/Strings.kt` declares
   the interface and the three catalogs implement it; nothing is written inline.
6. **The hard constraints in AGENTS.md are not suggestions.** The application id is
   exactly ten characters, `targetSdk` stays 28 (AOSP's policy stops granting
   `execute_no_trans` on `app_data_file` above it, so 28 is the highest value the
   runtime runs at), the Termux prefix cannot move, and every recursive delete goes
   through `env/SafeDelete.kt`. Each one has the failure it prevents written next to
   it.

## Before your first build

Two things that a fresh clone can be missing, both of which cost an afternoon if
they are not known:

- **`gradlew` may not be executable.** A checkout made on Windows has no executable
  bit on it, so `./gradlew` fails with "Permission denied" on Linux and macOS. Fix
  it once and commit the mode: `git update-index --chmod=+x gradlew`. CI also runs
  `chmod +x gradlew` before its first Gradle call, so a pull request is not blocked
  by it.
- **`local.properties` is untracked.** Point `sdk.dir` at your Android SDK, or set
  `ANDROID_HOME`; `docs/BUILDING.md` has the rest of the prerequisites, including
  the NDK version `terminal-emulator/build.gradle.kts` pins.

## Licence

PiKit is GPLv3 — see [LICENSE](LICENSE) and [docs/LICENSING.md](docs/LICENSING.md).
By contributing you agree that your contribution is distributed under the same
licence, and that the project may make the corresponding source available to
whoever receives a build.
