## What this changes

<!-- One change. If it is two, it is two pull requests. -->

## Why

<!--
The diff says what changed; this says why. If a design was tried and rejected along
the way, say which and what made it fail — that paragraph is the part that cannot be
recovered from the code later, and it belongs in the commit body and in the relevant
chapter under docs/architecture/ too.
-->

## Checks

- [ ] `python tools/build-apks.py` passes, or the smaller loop that covers this
      change (`./gradlew :app:testX64DebugUnitTest`, plus the `tools/` checker it
      touches) — and the output is quoted if a check was skipped
- [ ] A layout claim was measured with `uiautomator dump`, not judged from a
      screenshot
- [ ] User-visible text was added to `locales/Strings.kt` and **all three** catalogs
- [ ] The documents that describe this behaviour were updated in this change, and a
      chapter that outgrew its file was split rather than trimmed
      (`AGENTS.md` → Documentation)

## Notes for the reviewer

<!-- Anything that is not obvious from the diff: a device check still outstanding, a
     deliberate omission, a claim that is weaker than it looks. -->
