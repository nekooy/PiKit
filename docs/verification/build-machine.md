# On a build machine

*[Verification](../VERIFICATION.md): every check that needs no device.*

- **Unit tests** — `./gradlew :app:testX64DebugUnitTest`, 377 tests in 31 suites, no device; it
  is the same figure `tools/build-apks.py` sums out of the JUnit XML. Covered:
  JSONL framing (raw `U+2028`/`U+2029`, records and multi-byte UTF-8 split across
  reads), record parsing, the reducer's event ordering, the Markdown block parser,
  conversation titles, the turn fold, a profile's model list, the custom-endpoint
  document, the provider list against pi's own `getApiKeyEnvVars` table (every
  single-key provider present with pi's env var, the OAuth-only and second-input ones
  absent, one id per label, and the two providers that share a variable still told apart),
  `SafeDelete`'s boundary, the storage policy's subsumption rule (a
  folder inside the granted tree keeps its grant but gets no link of its own),
  `settingsWithDefaultTools`, and the conversation content search
  (`SessionMetadata.searchText`/`snippet` and `SessionSearchIndex`'s
  stamp-and-length staleness rule, checked against deliberately broken
  variants of the cache). Lately: `modelsJsonWith` (the user's own `models.json`
  survives a merge; only `pikit-custom` is withdrawn; an id a catalogue check found absent
  gets a `models` entry — the only mechanism that reaches an id pi does not know — naming
  pi's own `contextWindow`/`maxTokens` from the fallback rather than PiKit's 128K/16K
  placeholders, with `provider` and `cost.tiers` whitelisted out, and then the user's three
  settings over them: the switch on writes `["text","image"]`, off `["text"]`, untouched the
  fallback's own `input`, and an empty number box keeps the fallback's number. The entry is
  withdrawn when the settings go away, the id leaves the model list, or the recorded basis
  no longer matches; a near-miss entry is left alone, an id the record does not name is
  never withdrawn, the factless shape older builds wrote is still recognised,
  `modelDefinitions` writes nothing without the fallback facts and nothing for a custom
  endpoint, and a custom endpoint's entries follow its settings with pi's defaults.),
  `settingsWithPiDefaults` (`defaultProvider`, `defaultModel`, `defaultThinkingLevel`, and
  the bundled extension's absolute path in `packages`, with every other key intact),
  `apiKeyEnvironment`, `ModelProfile.modelSettings` and `writtenModels` round-tripping
  (including the record a previous build wrote under `customImageModels`), `supportsImages`
  parsing, `SessionCommandTest` (the two `success: true` answers that are not successes: a
  `cancelled` session switch and the queue `clear_queue` returns), the thinking
  levels (`clampThinkingLevel` against the shipped catalog maps, `thinkingLevelsFor`'s
  remembered list and the model id it is keyed by, the level's name being Pi's own id
  in every language, and the footnote's single-level case), the agent's argv
  (`--offline` **absent**, and the reason recorded in the test: pi reads that flag as
  "the catalogue has no network" as well as "skip the update checks" — while the
  read-only launches assert the opposite, `--offline` and `--no-session` **present**, so a
  question about pi's catalogue neither updates it nor leaves a session behind), the
  catalogue refresh (its argv — `pi update --models`, with no `--mode`, no `--offline` and
  no key in it — its policy: due when the store is older than pi's own four-hour window,
  half an hour of quiet after a failure, and the four-hour figure itself asserted against
  pi's `REMOTE_CATALOG_REFRESH_INTERVAL_MS` — and the credential it is launched with: one
  placeholder per built-in provider's variable, none for a custom endpoint, and none in
  the *agent's* environment, which is what keeps the available-model list honest), and
  `WebSearchStore`'s merge (unknown keys survive, a nested key keeps its siblings,
  a removed setting is dropped rather than emptied, and a file that does not parse
  is refused). The settings page's "add an option" is covered at the store level: the
  owned-key set is the renderer's own live keys, a documented object option is one
  entry and an unknown one is walked to its leaves, setting a path creates the branch
  it needs and keeps every sibling, a boolean is replaced rather than merged into, and
  removing the last member of a branch prunes it. The vendored VT parser's own
  20-file suite passes with them.
- **The hand-written prose** — `python tools/reflow-manual.py --check` (every
  manual line fits the 48-column screen, and each body is a fixed point of the
  wrapper, so an edit that was never reflowed is reported) and
  `python tools/check-locales.py` (every terminal banner line fits 48 columns, and
  no Chinese or Japanese word carries a space a wrap pass inserted).
- **Package relocation** — `python tools/test-relocate.py`, against real `.deb`
  files: no `com.termux` survives anywhere, including inside `control.tar`; the
  archive unpacks to the same entries; every rewritten ELF's `DT_RUNPATH` names
  the new prefix; no entry changed size; and the relocator **as packed into
  `overlay.zip`** passes its own `--selfcheck` and relocates a package. That last
  check is the one that fails when the build's prefix rewrite reaches the
  relocator's own `OLD_ID`, the failure that made every package from the Termux
  repository uninstallable.
- **The agent guard's rules** — `node tools/test-safety-guard.mjs`: **180 cases**,
  each run through the same `inspect()` the extension runs and none of them executed.
  They are grouped by four axes: a recursive delete outside the
  workspace is refused (`rm -rf ~`, `$HOME`, `~/tmp/x`, `~/workspace` itself,
  `find ~ -delete`, the same through `~/storage/shared/`, a three-line script, and the
  same command one level down in `sh -c`, `eval` and a heredoc fed to `bash`); pi's own
  files survive an agent that tidies them (`rm -f` on the guard, `auth.json`,
  `settings.json`, `models.json`, `web-search.json`, `AGENTS.md`, a session file and
  `pikit-config.json`, plus `mv`, `>`, `truncate`, `sed -i`, `chmod` and `cp` onto them);
  ordinary work outside the workspace still runs (`rm -f ~/notes.md`, `pkg`, `npm`, `git`,
  and the false positives a first version of the guard refused); and work inside the
  workspace runs at any depth (`rm -rf ~/workspace/myapp/node_modules`, `rm -rf ./dist`,
  `find . -name '*.log' -delete`). A relative target is checked against the directory the
  shell is really in: `rm -rf node_modules` is allowed from the workspace and refused from
  `$HOME`. The `write` and `edit` tools are covered by the same protected list, because they
  carry a path and no command text — `write ~/.pi/agent/auth.json` is refused while
  `~/workspace/myapp/.pi/settings.json` is not. The storage policy is checked in both
  spellings, including the `~/storage` links: with nothing granted,
  `cat ~/storage/shared/Download/notes.txt` is refused while
  `cat /sdcard/Download/notes.txt` is allowed, and both flip when the folder is on.
- **The client is pinned to real wire traffic** — `RealCaptureTest` replays a
  scrubbed capture of a real pi session from `app/src/test/resources/pi-rpc/`.
- **The runtime images** — `python tools/verify-runtime-image.py` rebuilds the tree
  the on-device installer would produce and asserts against it: all symlinks
  resolve (1291 on `arm64-v8a`, 1293 on `x86_64`), every shebang under `bin/` and
  `libexec/` points at something that exists, and `bin/bash` and `bin/node` are
  valid ELF for the target ABI with
  `DT_RUNPATH=/data/data/pi.kit.mob/files/usr/lib`. A zip entry carries no Unix mode
  the JVM will apply, so the installer keeps a list of the paths that need one. It
  originally missed `lib/node_modules/<package>/bin/`, which made `bin/npm` a symlink to
  a file that could not be executed — invisible until an update to a newer pi existed.
  Both the installer and the on-demand repair cover it now.
- **Both APKs assemble**, debug and release, with `com/termux/terminal/JNI`
  surviving R8 in the release dex; the native PTY shim compiles for both ABIs.
- **The README's icon is the launcher's icon** — `python tools/render-icon.py
  --check` regenerates the SVG from `ic_launcher_foreground.xml` and
  `values/colors.xml` and fails on any difference; it is in `build-apks.py`'s
  `checks`, and the geometry it writes is a copy of the vector's `pathData`
  (read out by regex — the script has no XML parser).
- **The documentation split lost nothing** — `docs/ARCHITECTURE.md` became a map
  plus one file per topic under `docs/architecture/`, and the old single document's every
  non-blank, non-heading line was checked to be present in exactly one of them when
  the split was made. The chapters that outgrew one file later gained numbered parts
  (`§6.1`–`§6.2`, `§7.1`–`§7.4`, `§12.1`–`§12.3`), and every `ARCHITECTURE §N`
  citation in the code and the documents was re-pointed to the part it means in the
  same change — 39 sites, checked by reading, since nothing parses Markdown.
- **Both workflows have run, repeatedly.** `ci.yml` passed on every push to `main` and on
  pull requests to it while it had those triggers, and it has since been made dispatch-only
  — the job is unchanged, so one dispatch confirms it fires and that the removed triggers
  are gone. `release.yml` has published `v0.1.0` end to end, including the certificate check
  and the release itself.
- **The release keystore path is exercised, and the APKs it signs are checked.**
  `./gradlew :app:signingReport` with the four `pikit.keystore.*` properties exported
  reports `arm64Release`/`x64Release` on `.release/release.jks` with alias `pikit`
  while both debug variants stay on the Android debug keystore, and without the
  properties the releases fall back to the debug key. `apksigner verify --print-certs`
  on the release APK built with the keystore reports the PiKit certificate rather than
  `CN=Android Debug`.
- **A release has been published from the workflow, and its APKs were checked before it
  was.** `v0.1.0` carries `PiKit-0.1.0-arm64.apk` (107,833,364 bytes),
  `PiKit-0.1.0-x64.apk` (107,269,014 bytes) and `SHA256SUMS`; the run that made it passed
  the certificate check against the keystore's fingerprint on the runner, and the assets'
  own `digest` fields were compared with the checksum file afterwards. Two failures on the
  way there, both recorded: `gh release create` is refused
  (`HTTP 403: Resource not accessible by integration`) inside the twenty-step build job and
  works from a job of its own, so publishing is a second job; and the first published
  `SHA256SUMS` was eight bytes because its glob had one directory level too many, which the
  step now refuses to produce. The assets carry the app's own names because the build job
  renames Gradle's outputs in place, after the signing check and before the checksums and
  the artifact; before that the release attached `app-arm64-release.apk` and
  `app-x64-release.apk`, which name neither the application nor the version.
- **`ORG_GRADLE_PROJECT_*` does not become a Gradle property on the GitHub runner, and
  that is measured.** A two-step probe in `ci.yml` isolated it: a variable written as
  `ORG_GRADLE_PROJECT_probe.dotted` is in the next step's environment (`printenv` prints
  it) and `./gradlew -q properties` does not list `probe.dotted`, on Ubuntu with the
  Gradle the wrapper carried then (8.11.1; it is 8.14.5 now), while the same mechanism
  works on Windows. It cost the first release run its signatures: the four keystore
  properties were exported through `$GITHUB_ENV`, the build step's environment listed them,
  and the APKs came out signed with a key Gradle generated on the spot (the runner has no
  `~/.android/debug.keystore`), which the workflow's certificate check caught. Two lessons,
  both load-bearing: `tools/build-apks.py` passes whichever of the four it can see as `-P`
  arguments, which travel in the build request rather than the environment; and
  `$GITHUB_ENV` is not visible to the step that writes it.
- **The update check's request runs on a machine that can resolve `github.com`, not on the
  emulator.** `UpdateCheckTest` pins what GitHub's redirect can land on (a tag, the releases
  list, an empty tag, another host), the endpoint `pikit.repository` produces, and the two ways
  the comparison can be wrong: `0.10.0` newer than `0.9.0`, and a missing component counting as
  zero. The request itself was exercised once on the JVM against live GitHub, from an address
  whose anonymous API budget was already spent — the case the check was rewritten for
  (ARCHITECTURE §9):

  ```
  PAGE termux/termux-app -> Found(release=Release(version=0.118.3, pageUrl=…/releases/tag/v0.118.3))
  PAGE nekooy/PiKit      -> NoReleases
  API  termux/termux-app -> 403 x-ratelimit-limit=60 x-ratelimit-remaining=0
  ```

  The page endpoint answered with a real release while the endpoint it replaced refused the same
  address in the same run, its anonymous budget already spent. What has **not** run is that request
  from Android: the emulator cannot resolve `github.com` at all (`ping github.com` → `unknown
  host`, while `api.deepseek.com` resolves; the `dns-pins.txt` workaround reaches pi's Node process
  only, never the app's own `HttpURLConnection`). Two states are therefore device-unverified: the
  row's "new version" wording and its browser hand-off. A repository with nothing published answers
  `404` or redirects to the releases list, and one that does not exist answers `404` too — an
  anonymous caller cannot tell those apart, so all three read as "no release has been published
  yet".
- **`TERMUX_VERSION` is the environment's version, not the app's.**
  `BundledImageTest` pins the tag parse (`bootstrap-2026.09.06-r1+apt.android-7` →
  `2026.09.06-r1`) and the refusal of anything that is not a bootstrap tag. The value
  reaching a live shell is not covered by a test: it would need a device, and the
  consumer that matters — pi's Termux detection, which reads only whether the
  variable is set — behaves the same for either value.
- **pi is pinned, and the pin is what the image carries.** `PI_VERSION` is hashed
  into the revision, `build-metadata.json` (readable from the APK's assets, and read
  by the About page) records it, and `.runtime-build/cache/pi/pikit-vendored.json`
  records which version the cached tree holds — so a cache written before that marker
  existed re-vendors once, which the next build's log says in those words.
