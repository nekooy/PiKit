# Verification status

What has actually been exercised, and how. The reasoning behind each result — and
every design that was tried and rejected along the way — is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## On a build machine

- **Unit tests** — `./gradlew :app:testX64DebugUnitTest`, 362 tests, no device.
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
  stamp-and-length staleness rule, which was checked against deliberately broken
  variants of the cache). Lately: `modelsJsonWith` (the user's own `models.json`
  survives a merge, only `pikit-custom` is withdrawn, an id a catalogue check found absent
  from pi's catalogue gets a `models` entry — the only mechanism that reaches an id pi
  does not know — and that entry names pi's own facts for the model (`contextWindow` and
  `maxTokens` from the fallback, not PiKit's 128K/16K placeholders, with `provider`
  and `cost.tiers` whitelisted out) and then the user's three settings over them: the
  switch on writes `["text","image"]`, off writes `["text"]`, untouched repeats the
  fallback's own `input`, and an empty number box leaves the fallback's number in place
  rather than naming pi's default; the entry is withdrawn when the settings go away, when
  the id leaves the model list, or when the basis the check was recorded against no longer
  matches, so an id pi has since learned about can never stay overwritten; a near-miss
  entry in that array is left alone, an id the record does not name is never withdrawn, the
  factless shape older builds wrote is still recognised, `modelDefinitions` writes nothing
  without the fallback facts and nothing at all for a custom endpoint, and a custom
  endpoint's own entries follow its settings with pi's defaults where nothing is said),
  `settingsWithPiDefaults`
  (`defaultProvider`,
  `defaultModel`, `defaultThinkingLevel`, and the bundled extension's absolute path
  in `packages`, with every other key intact), `apiKeyEnvironment`,
  `ModelProfile.modelSettings` and `writtenModels` round-tripping (including the record a
  previous build wrote under `customImageModels`), `supportsImages` parsing,
  `SessionCommandTest` (the two `success: true` answers that are not successes: a
  `cancelled` session switch and the queue `clear_queue` returns), the thinking
  levels (`clampThinkingLevel` against the shipped catalog maps, `thinkingLevelsFor`'s
  remembered list and the model id it is keyed by, the level's name being Pi's own id
  in every language, and the footnote's single-level case), the agent's argv
  (`--offline` **absent**, and the reason recorded in the test: pi reads that flag as
  "the catalogue has no network" as well as "skip the update checks" — while the
  read-only launches assert the opposite, `--offline` and `--no-session` **present**, so a
  question about pi's catalogue neither updates it nor leaves a session behind), the
  catalogue
  refresh (its argv — `pi update --models`, with no `--mode`, no `--offline` and no key
  in it — its policy: due when the store is older than pi's own four-hour window, half an
  hour of quiet after a failure, and the four-hour figure itself asserted against pi's
  `REMOTE_CATALOG_REFRESH_INTERVAL_MS` — and the credential it is launched with: one
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
  relocator's own `OLD_ID`, which is what made every package from the Termux
  repository uninstallable.
- **The agent guard's rules** — `node tools/test-safety-guard.mjs`: **180 cases**,
  each run through the same `inspect()` the extension runs and none of them executed.
  They are grouped by the four axes the report named: a recursive delete outside the
  workspace is refused (`rm -rf ~`, `$HOME`, `"$HOME"`, `~/tmp/x`,
  `~/workspace` itself, `find ~ -delete`, `find "$HOME" -delete`, the same through
  `~/storage/shared/`, a three-line script, and the same command one level down in
  `sh -c`, `eval` and a heredoc fed to `bash`); pi's own files survive an agent that
  tidies them (`rm -f` on the guard, `auth.json`, `settings.json`, `models.json`,
  `web-search.json`, `AGENTS.md`, a session file and `pikit-config.json`, plus `mv`,
  `>`, `truncate`, `sed -i`, `chmod` and `cp` onto them); ordinary work outside the
  workspace still runs (`rm -f ~/notes.md`, `pkg`, `npm`, `git`, and the three
  false positives the report listed — `git commit -m 'fix; rm -rf /sdcard case'`,
  `grep -rn '&& rm -rf'`, a heredoc *writing* a script); and work inside the workspace
  runs at any depth (`rm -rf ~/workspace/myapp/node_modules`, `rm -rf ./dist`,
  `find . -name '*.log' -delete`). A relative target is checked against the directory
  the shell is really in: `rm -rf node_modules` is allowed from the workspace (where
  PiKit spawns the agent) and refused from `$HOME` (where a hand-run `pi` starts). The
  `write` and `edit` tools are covered by the
  same protected list, because they carry a path and no command text at all —
  `write ~/.pi/agent/auth.json` is refused while `~/workspace/myapp/.pi/settings.json`
  is not. The storage policy is checked in both spellings,
  including the `~/storage` links: with nothing granted,
  `cat ~/storage/shared/Download/notes.txt` is refused while
  `cat /sdcard/Download/notes.txt` is allowed, and both flip when the folder is on.
- **The client is pinned to real wire traffic** — `RealCaptureTest` replays a
  scrubbed capture of a real pi session from `app/src/test/resources/pi-rpc/`.
- **The runtime images** — `python tools/verify-runtime-image.py` rebuilds the tree
  the on-device installer would produce and asserts against it: all symlinks
  resolve (1291 on `arm64-v8a`, 1293 on `x86_64`), every shebang under `bin/` and
  `libexec/` points at something that exists, and `bin/bash` and `bin/node` are
  valid ELF for the target ABI with
  `DT_RUNPATH=/data/data/pi.kit.mob/files/usr/lib`.
- **Both APKs assemble**, debug and release, with `com/termux/terminal/JNI`
  surviving R8 in the release dex; the native PTY shim compiles for both ABIs.
- **The README's icon is the launcher's icon** — `python tools/render-icon.py
  --check` regenerates the SVG from `ic_launcher_foreground.xml` and
  `values/colors.xml` and fails on any difference; it is in `build-apks.py`'s
  `checks`, so the check runs on every full build. The SVG parses as XML (checked
  with `xml.etree`), and the geometry is a copy of the vector's `pathData`.
- **The documentation split lost nothing** — `docs/ARCHITECTURE.md` became a map
  plus fifteen chapter files under `docs/architecture/`, and every non-blank,
  non-heading line of the old 2,744-line document is present in exactly one of
  them (compared as multisets of stripped lines; the only deliberate differences
  are the new headers, the chapter titles, the one rewritten cross-reference, and
  the old introduction, which the map replaces). The old §-number references in
  code comments still resolve, because each chapter kept its number.
- **Both workflows have run, repeatedly.** `ci.yml` passes on every push to `main` and
  on pull requests to it (the first run taught it two things: `android-actions/setup-android@v3`
  fails in its own `sdkmanager tools` step, and every action was several majors behind).
  `release.yml` has published `v0.1.0` end to end, including the certificate check and
  the release itself. The commands this document records as working were also the ones
  the workflows ran, which is the only way a workflow is verified at all.
- **The release keystore path is exercised, and the APKs it signs are checked.**
  `./gradlew :app:signingReport` with the four `pikit.keystore.*` properties exported
  reports `arm64Release`/`x64Release` on `.release/release.jks` with alias `pikit`
  while both debug variants stay on the Android debug keystore, and without the
  properties the releases fall back to the debug key (which is what every build
  before this one did). `apksigner verify --print-certs` on the release APK built
  with the keystore reports the PiKit certificate rather than `CN=Android Debug`.
- **A release has been published from the workflow, and its APKs were checked before it
  was.** `v0.1.0` (2026-09-16) carries `app-arm64-release.apk` (107,279,942 bytes),
  `app-x64-release.apk` (106,715,604 bytes) and `SHA256SUMS`; the run that made it passed
  the certificate check against the keystore's fingerprint on the runner, and the assets'
  own `digest` fields were compared with the checksum file afterwards. Two failures on the
  way there, both recorded rather than smoothed over: `gh release create` is refused
  (`HTTP 403: Resource not accessible by integration`) inside the twenty-step build job and
  works from a job of its own, so publishing is a second job; and the first published
  `SHA256SUMS` was eight bytes because its glob had one directory level too many, which the
  step now refuses to produce.
- **`ORG_GRADLE_PROJECT_*` does not become a Gradle property on the GitHub runner, and
  that is measured.** The first release run exported all four keystore properties
  through `$GITHUB_ENV`, the build step's own environment listed them, and the APKs came
  out signed with a key Gradle generated on the spot — the runner has no
  `~/.android/debug.keystore`, so a debug signing config creates one — which the
  workflow's certificate check caught before anything was published. A two-step probe in
  `ci.yml` isolated it: a variable written as `ORG_GRADLE_PROJECT_probe.dotted` is in the
  next step's environment (`printenv` prints it) and `./gradlew -q properties` does not
  list `probe.dotted`, on Ubuntu with the wrapper's Gradle 8.11.1, while the same
  mechanism works on Windows. Two lessons, both now load-bearing:
  `tools/build-apks.py` passes whichever of the four it can see as `-P` arguments, which
  travel in the build request rather than the environment; and `$GITHUB_ENV` is not
  visible to the step that writes it, which is why the first version of that probe
  measured nothing.
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
  address in the same run — the reader's "开了vpn后就一直403". What has **not** run is that request
  from Android: the emulator cannot resolve `github.com` at all (`ping github.com` → `unknown
  host`, while `api.deepseek.com` resolves; a private-DNS resolver setting does not change it, and
  the `dns-pins.txt` workaround reaches pi's Node process only, never the app's own
  `HttpURLConnection`). Two states are therefore device-unverified: the row's "new version"
  wording and its browser hand-off — both a couple of lines of state machine over the `Release`
  the run above produced. One reading worth knowing: a repository with nothing published answers
  `404` (measured while this project had no release) or redirects to the releases list (measured
  on `octocat/Hello-World`), and a repository that does not exist answers `404` too — an anonymous
  caller cannot tell those apart, so all three are reported as "no release has been published
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
  records which version the cached tree holds — so a cache written before this change
  re-vendors once, which the next build's log says in those words.

## On the emulator (Medium Phone, Android 36.1, `x86_64`)

- **The first-launch permission sequence.** After
  `appops set --uid pi.kit.mob MANAGE_EXTERNAL_STORAGE deny` and `pm clear`, the
  platform's own log shows the notification request opened and closed **60-360 ms**
  later with no user input and no permission state change (`granted=false`, no
  `USER_SET`) — and with the request removed entirely, no prompt appeared at all,
  so the request stays. Two app-side defects were fixed and are visible in the same
  log: a *second* request used to abort the first (`Can request only one set of
  permissions at a time`, 66 ms after the first), and the request used to be raised
  from the app's first composition. It is now raised once per process, only after
  the runtime is ready, retried twice when the callback comes back in under a second
  (which cannot be a decision), and the storage dialog follows its answer. On this
  emulator every attempt is dismissed by the platform, so the sequence there reads
  as three `Displayed … GrantPermissionsActivity` lines ~1.7 s apart and then the
  storage dialog; ARCHITECTURE §5 has the log excerpt.
- **The repair button, on a fresh install.** `PrefixPatcher` used to rewrite the
  eight files the build protects; a full-prefix sha256 catalogue of all 19 325 files
  before and after one press showed exactly three changed — `bin/dpkg`, the apt hook
  and `libexec/pikit/relocate.js` — after which the relocator answered
  `OLD_ID === NEW_ID` and exited 4, and the storage self-test fell from `10 passed,
  0 failed` to `8 passed, 2 failed` (both failures were its relocator-boundary
  checks, which key on the refusal message the misconfigured script never reaches).
  With the exclusion list in place the same press reports
  **`无需重定位：所有文件都已匹配本应用的前缀。`**, shows progress while it runs
  (`正在检测…已扫描 1792 / 5120 / … / 23808 个文件` over ~20 s), leaves
  `relocate.js:99` reading `const OLD_ID = 'com.termux'`, passes
  `pikit-relocate --selfcheck`, and the self-test still reads `10 passed, 0 failed`
  afterwards.
- **The pi configuration the app owns.** With a DeepSeek profile in
  `pikit-config.json` and a hand-written `models.json` in place, one launch left
  `settings.json` as
  `{"defaultTools":[…],"packages":["…/pikit-extensions/node_modules/pi-web-access"],"defaultProvider":"deepseek","defaultModel":"deepseek-chat","defaultThinkingLevel":"medium"}`
  and the user's `models.json` **byte-identical** — the file the app used to delete
  whenever a built-in provider was active. A `pi` run by hand with the environment
  the Terminal tab spawns then reached the provider and answered `401
  Authentication Fails, Your api key: ****-key is invalid` for the placeholder key,
  which is the point: the model and the credential both arrived, where before it
  answered "No model selected".
- **The search settings page.** It renders under Settings, beside Model & provider
  and Language, with the extension's version in its first row; `web-search.json` is
  created at launch as `{"workflow": "none"}` and the page shows that workflow as
  **当前使用**.
- **A live `pkg install`.** The Termux repository is unreachable from the
  emulator, so `tools/build-hooktest-package.py` builds a synthetic package
  carrying the upstream prefix in a shebang, an ELF-ish blob and a symlink target,
  and installs it from a local apt repository. It reported `relocated 1
  archive(s), 3 occurrence(s)`, and the installed script's shebang came out as
  `#!/data/data/pi.kit.mob/files/usr/bin/sh` with no trace of the old prefix. This
  is the test that found the `file://` gap described in ARCHITECTURE §4.
- **Storage, end to end.** With "all files access" granted and two folders on, the
  app logged
  `reach=[documents:link=true,read=ok(1),write=ok downloads:link=true,read=ok,write=ok]`
  — a real read and a real write from inside the environment, not from a `run-as`
  shell, whose SELinux context makes its `Permission denied` meaningless here.
  After **Remove all access**, every seeded user file was still present and
  unchanged, and the shipped `pikit-storage-check` reports `9 passed, 0 failed`
  with no folders granted.
- **The delete hazard is controlled for, not assumed.** A traversal that follows a
  symlink — as `File.deleteRecursively` does — empties the target (1 file to 0),
  while the app's by-path removal leaves it intact. Without that control, `rm -rf`
  on the link farm would prove nothing: `rm -rf` does not follow symlinks anyway.
- **A turn against a live model.** Against a custom endpoint, a prompt produced a
  real tool call and a real answer, recorded as `stopReason=toolUse tools=[bash]` →
  `toolResult 'PIKIT_E2E_OK\n'` → `stopReason=stop`, with token counts. This is how
  the `--api-key` gap in ARCHITECTURE §6 was found.
- **The chat UI**, measured with `uiautomator` rather than eyeballed: a finished
  turn folds to its prompt, a content-sized chip and its reply; the tab strip stays
  at `y = 2251` whether the keyboard is up or down, i.e. it is covered rather than
  pushed; the composer's control row carries the agent state, the thinking level,
  the profile name and the context percentage, and the last of those reads
  `context 0%` before anything is sent.
- **Sheets stay in the page's window, and the keyboard stays up.** With the
  composer focused (`mInputShown=true`, the row's chips at `y=1153`), tapping the
  cache chip leaves `mInputShown=true` and the chips at `y=1153`, the panel drawn
  above the keyboard — where the `ModalBottomSheet` this replaced measured
  `mInputShown=false` and moved the same chips to `y=1763` against the keyboard's
  own 610 px. `dumpsys window` counts one `pi.kit.mob` window while a sheet is open,
  not two.
- **Dismissing a sheet gives input back on the next frame.** Scrim-tap then
  chip-tap at increasing gaps, with the sheet's presence read out of a
  `uiautomator` dump: lost at 0.00–0.10 s (the tap lands on the panel that is still
  leaving), landed from 0.15 s on. The dialog window this replaced lost the same tap
  at 0.15, 0.30 and 0.50 s and only landed at 0.80 s.
- **The page moves that were missing one now animate.** With
  `animator_duration_scale 10` and one screenshot mid-transition, the chat's
  history list was caught sliding in over the conversation with the composer
  travelling the other way, and a Files directory was caught the same way with its
  header fixed and only the listing moving.
- **One provider answers with several models.** A profile was given a second model
  on the model page (typed into the field and added, with the existing one still in
  the list), saved, and the chat's picker then showed both providers with both
  models grouped under `CUSTOM ENDPOINT` and `DEEPSEEK`. Switching between the two
  models of the custom endpoint happened **in place** — the chip changed and the
  log had no `launching:` line — where before the change the same switch relaunched
  the agent. The generated `files/home/.pi/agent/models.json` lists both models and
  parses as JSON.
- **The model chip and the picker agree, in both directions.** Picking a model in
  the composer's picker and reopening the picker shows the tick on the model just
  chosen, and the chip shows it too; making the *other* model active on the model
  page and switching back to the chat tab shows the new one on the chip at once.
  Both were wrong before: the chip kept the model it was composed with after a
  settings change, and the picker's tick came from the click handler's closure, so
  a single screenshot caught chip `mimo-v2-extra` and tick on `mimo-v2.5` at the
  same time.
- **The thinking level's control is a row and a sheet**, from the model page as well
  as from the composer's chip: the same levels with their descriptions, each labelled
  with Pi's own id (`off`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`) rather
  than a translation of it, and only the levels the model itself reports except before
  the agent has answered. The segmented control it replaced rendered as an empty
  rounded rectangle — measured with `uiautomator`, no text nodes in its bounds at all.
- **The catalogue refresh was measured against the bundled runtime** rather than only
  reasoned about: with a fresh agent directory and one dummy key, `--provider deepseek
  --model deepseek-flash` answered `["off","high","max"]` (three levels, the
  `deepseek-v4-pro` fallback, with pi's `Model … not found` warning on stderr);
  `pi update --models` then reported `Model catalogs refreshed` in 0.67 s and wrote one
  `models-store.json` entry, and the identical agent command answered
  `["off","low","high","max"]` (four). The same probe with no credentials at all
  finished in 0.25 s having written nothing, which is what "visited configured
  providers only" means. Recorded in ARCHITECTURE §7 — including two corrections that
  came out of reading pi's own source afterwards: the probe was run `--offline`, and
  *that flag is not what made the catalogue stale*; and RPC mode does start a
  `modelRuntime.refresh()` of its own at launch, but that one republishes the overlay
  inside the running process and never writes `models-store.json`, so the store is still
  the app's to refresh. It now does so on pi's own four-hour window — zero was the policy
  while the refresh covered only the profile's provider, and thirty-two catalogues per
  launch is a different order of cost.
- **The bundled bundle was read, not guessed, for the session-switch defect.** pi's
  `RuntimeHost.teardownCurrent` — the first thing `switch_session` and `new_session`
  do — starts with `await this.session.abort()`, so there is no way to leave a session
  without ending the turn that is running in it; the app refuses the move instead and
  says so (`TurnInFlightException`). The same read established what `PI_OFFLINE` gates
  (`modelNetworkEnabled`, `getLatestPiRelease`, `refreshModelCatalogs`,
  `checkForPackageUpdates`, `reportInstallTelemetry`), which is what ARCHITECTURE §2
  and §7 now record.
- **Switching conversations keeps the folding** — turns are re-derived from
  `get_messages`, with real durations read off the stored timestamps.
- **The first-launch storage prompt** appears once, opens Android's "All files
  access" page, and does not return after being answered.
- **A stale icon on the device is not a stale icon in the build.** Reported twice as an
  old amber/gold icon — in the launcher and then in the agent notification — after every
  drawable had been changed to black and white. Both were the device's own icon cache,
  which survives an APK update. The way to tell the two apart takes a minute and no
  device: sweep the built APK for the colour's bytes
  (`6bc4ffff`, and both endiannesses of it, across `res/`, `resources.arsc` and the
  compiled XML) — the current build reports `none`. A launcher or shade icon that is
  still wrong after an install is cleared by a reboot or a reinstall, not by a rebuild.

### The Markdown and formula round (2026-09-18)

The instrument was a **synthetic session file**, not a live turn: a session under
`files/pi-sessions/` in pi's own format (a `{"type":"session","version":3,…}` header,
then `message` entries linked by `id`/`parentId`), with eight turns of five formula
paragraphs, five display formulas, five three-column tables with formulas in the cells,
and a fenced code block each. Pushed with
`adb shell run-as pi.kit.mob sh -c 'cat > files/pi-sessions/…'` and opened from the
history list. It is not committed: a fixture that pi can open is a fixture that gets
stale, and the point of this round was what the *renderer* does with it.

What the `uiautomator` dump settled:

- **A formula inside a table cell renders.** The accessibility descriptions were the
  evidence, because a rendered formula is a `Canvas` with no text nodes of its own and
  a *failed* one is plain text: `fraction: n + 1 over 2`, `integral from 0 to infinity e
  to the power of - x squared dx = fraction: square root of pi over 2` and
  `P ( A divides B ) = fraction: P ( B divides A ) P ( A ) over P ( B )` all appear as
  nodes, at the cell bounds and outside them, where the source (`$\frac{n+1}{2}$`) no
  longer appears at all. That is the report "表格等内容里的公式为什么不会渲染" answered.
- **The same formulas in paragraphs and as display blocks.** Both spellings are in the
  dump — the inline one inside a sentence's accessible text and the display one as a
  node of its own.
- **The code block is monospace and uncoloured.** Its text arrives as one node
  (`def area(radius):&#10;    return math.pi * radius ** 2`), which is what a plain
  `Text` in `FontFamily.Monospace` produces and what a span-styled one does not.
- **A message carries a copy button and a time.** `content-desc="复制消息"` at
  `[52,1560][86,1594]` under the last answer, with `9月10日 10:34` beside it — a
  *previous* day in the fixture, so the date-and-time spelling; a message whose
  `createdAt` is today was checked in the same dump by the fixture's own timestamps.
- **The command list runs what it can.** The sheet shows `!` and the seven built-ins
  with their translated descriptions, and the `!` row opened a shell sheet
  (hint, placeholder `例如 ls -la`, 好 / 取消). Running `uname` from it appended
  `{"role":"bashExecution","command":"uname","output":"Linux\n","exitCode":0}` to the
  session file — a real command, run in the bundled runtime, from a tap.
- **The composer's row is the `+`'s own shape.** `常用指令 @ [738,2017][793,2072]`,
  `添加到消息 @ [846,2015][906,2075]`, `发送 @ [965,2018][1018,2071]`: three 40dp
  buttons, adjacent, with the command one immediately left of the `+`.

What it did **not** settle, and is written here rather than left implied:

- **The performance claim had no end-to-end number** in this round. It was measured in
  the next one — see *The session that opened slowly* below — and the answer was
  `layout=305.9 ms`, which is a Compose problem and not a `swiftshader` one.
- **Selecting across a display formula was verified by reading, not by dragging.**
  The change is `userScrollEnabled = false` on the formula's scroll box, which is what
  stops that box claiming the selection drag; a drag-based check needs a gesture over a
  `SelectionContainer` that `uiautomator` cannot make.
- **The camera entry has not been exercised.** `Take a photo` is a `PickerOption` whose
  tap launches `ActivityResultContracts.TakePicture` against a `FileProvider` URI in the
  app's cache, and the emulator's camera app is not a camera. What is unverified is the
  round trip — a written file arriving in the composer as a thumbnail — and the
  permission refusal path.
- **The `!` prefix route was not exercised, and that is a limit of the instrument
  rather than a finding.** `adb shell input text` will not deliver an exclamation mark
  to a Compose text field on this emulator — every spelling of it arrives as nothing or
  as a literal backslash — so the composer's own `!command` path could not be typed.
  What is known is that both routes call the same function: the sheet calls
  `PiAgentSession.runBash(line.trim())`, and `ChatScreen`'s `onSend` calls
  `runBash(prompt.removePrefix("!").trim())` for a prompt that starts with `!` and
  carries no image. A device pass with a keyboard is what would settle it.

### The session that opened slowly (2026-09-18, later still)

The first round left the performance claim as "no end-to-end number", so this one
instrumented the frame instead of arguing about it. A temporary probe — a
`Window.OnFrameMetricsAvailableListener` that logged `TOTAL_DURATION`,
`LAYOUT_MEASURE_DURATION`, `DRAW_DURATION` and `GPU_DURATION` for every frame over
32 ms — ran against the same synthetic session as above, grown to twelve turns.

The frame that opened the conversation:

```
total=835.4  layout=305.9  draw=25.6  gpu=67.3
```

**305.9 ms in the measure/layout pass, for one conversation.** That is what
"打开含有许多公式的历史对话会非常卡" was about, and it is a Compose problem rather
than a renderer one: the GPU took 67 ms of the same frame, which on `swiftshader` is
not the bottleneck.

The cause was structural. A whole reply is **one item** of the transcript's
`LazyColumn`, and every block of it sat in a plain `Column` inside that item — so the
list has to measure *all* of the blocks to learn the item's height, including the ones
six screens down. A formula's measurement is the expensive one (parse, then lay out
against KaTeX's metrics), so a reply with thirty of them paid for thirty before the
first frame was drawn.

`MarkdownText` was made a `LazyColumn` of its own, one item per block, so an off-screen
formula is not measured until it is scrolled to. The same frame afterwards:

```
total=923.3  layout=218.4  draw=14.9  gpu=59.5
```

`layout` fell from **305.9 ms to 218.4 ms**, a 29% cut, on a launch whose total is
dominated by the runtime unpack and pi's handshake.

**That `LazyColumn` has since been reverted, because it was a crash.** `MarkdownText` is
not only drawn inside the transcript, where a nested lazy list is legal: `ManualPage`
draws it inside `SettingsBody`, which is a `Column(verticalScroll)`, and so does the file
preview. A scrollable measured inside a scrollable gets infinite height on the main axis
and a `LazyColumn` throws on it. The crash and its replacement are in the round below;
the two numbers above still stand as the size of the problem, not as a description of the
current code.

What this does **not** claim is the steady-state scroll after the conversation is open: no
scroll-phase frame on this emulator ever reached the probe's threshold, and `dumpsys
gfxinfo` there reports a 34 ms median that describes `swiftshader` rather than the change.

And what this round did **not** verify, which is a gap rather than a result: **every
interactive change below is unverified on the emulator**, because its touch injection
stopped working part-way through. `adb shell input tap` produced no `onOpenSessions`
callback and no focus change; it then stopped responding to a tap on the launcher's own
tab strip, and stayed that way across a cold restart of the AVD — so the instrument was
gone, not the app.

Three facts from that last fix are worth keeping, and the first is a correction of
something this document claimed for one revision:

- **The prompt bubble's bug was its width, not its alignment — and the first two
  attempts here blamed the alignment.** Material3's `Surface` puts its content in a
  `Box(propagateMinConstraints = true)`, so the `0.86f` cap on the box *around* the
  bubble travelled through `wrapContentWidth` into the bubble's `Column`, and every
  prompt was drawn an 86%-wide bar with its text at the start of it. "气泡是固定宽度"
  and "用户消息靠左了" were the same bar. `wrapContentWidth` cannot win that, because
  `Surface` inflates its own child back to the minimum it was handed; the cap has to be
  imposed where `Surface` cannot see it, which is a `widthIn(max = …)` on a
  `BoxWithConstraints` with `fillMaxWidth()` — and *that* is what the bubble now does.
- **`Alignment.End` and `CenterEnd` are the same alignment.** An earlier revision of
  these notes claimed `Alignment.End` centres its content. That is false:
  `Alignment.End` is `BiasAlignment.Horizontal(1f)`, whose `align(size, space)` is
  `center * (1 + bias)` with `center = (space - size) / 2` — i.e. `space - size`. There
  was never an alignment bug, and the round spent on one was wasted.
- **`Alignment.CenterEnd` does not compile as `wrapContentWidth`'s argument.** The
  qualifier `Alignment` names the *interface's* companion object, whose `CenterEnd` is
  the two-axis `Alignment`; the compiler's answer is "actual type is Alignment, but
  Alignment.Horizontal was expected", and `Alignment.Horizontal.CenterEnd` does not
  resolve either, because a companion object is not a valid qualifier in Kotlin.

### The crashes, the dead taps and the blank glyph (2026-09-18, last round of the day)

Three reports, and the emulator answered all three — but only after its own fault was
found, which is worth writing down first because it invalidated a whole round of
"unverified" notes.

**The emulator was running on `swiftshader`, and its GPU context was timing out.** The
symptom was that the app's window was `inputConfig=NOT_VISIBLE, alpha=0` in
`dumpsys input` with a frame of `[162,360][918,2040]` on a 1080x2400 display, while
`dumpsys window` and `dumpsys activity` both reported it `isVisible=true`,
`mViewVisibility=0x0`, `mode=fullscreen` and `mBounds=Rect(0, 0 - 1080, 2400)`;
`uiautomator dump` returned a correct full-resolution tree, `screencap` returned a blank
white image, and every `input tap` was rejected with `InputDispatcher: No new touched
window`. The cause was in `dumpsys gfxinfo`: `GPU Context timeout: 10` and two frames in
the GPU histogram at **4950 ms**. Software rasterisation had stopped completing frames, so
the window never finished its first draw, so it was never marked touchable. `-gpu host`
fixed it outright — same AVD, same APK, window at `[0,0][1080,2400]` with `alpha=1` and
`inputConfig=0x0`. **Eleven rounds of "unverified because touch injection is broken" were
the graphics backend, not the app and not the instrument.**

Everything below was then verified by tapping, on the Medium_Phone AVD (Android 36.1,
`x86_64`) with `-gpu host`, against a synthetic session written into
`files/pi-sessions/` (and deleted afterwards).

| The report | What was done | What was seen |
| --- | --- | --- |
| 点击输入框无反应，无法输入文字 | tap the composer field | `uiautomator dump`: the `EditText` node reports `focused="true"`; the screenshot shows the caret and the Gboard window; `dumpsys window` shows an `InputMethod` insets source with `bottom=883` |
| 目前 `</>` 没有正常显示 | look at the composer | the two chevrons and the slash, drawn, at `[63,2017][118,2072]` — the field's left corner |
| 常用指令页删掉那些按钮 | open the command sheet | eight rows: `!`, `/new`, `/compact`, `/stop`, `/clone`, `/export`, `/model`, `/clear`. **No `/websearch`, `/curator`, `/google-account`, `/search` or `/llama`** |
| 打开设置页用户手册点击会闪退 | open 设置 → 使用手册 and scroll it end to end | renders, twelve full-page swipes, no `FATAL` and an empty crash buffer |
| 点击历史对话页面应用就会闪退 | open a saved conversation | renders; the crash buffer is empty |
| 表格里的公式 | the same session's table | `$\frac{a}{b}$` draws as a stacked fraction and `$x^{2}+y^{2}=z^{2}$` draws with real superscripts, inside the cells |
| 公式显示 | a reply with fifteen display formulas | all fifteen render — integrals, a `cases` block, a matrix product, `\underbrace` — and the off-screen ones are measured only as they arrive |
| 消息气泡 | `hi`, `ok`, and a long prompt | `ok`'s text node is `[968,531][1016,594]` — a small bubble at the right edge — and the long prompt wraps to three lines inside the 86% cap |

**The bubble was still wrong, and the accessibility tree is what caught it.** With the
`widthIn(max = cap)` fix in place, `ok` measured `[206,1196][1048,1259]`: 842px wide for
two characters, i.e. exactly the cap. The cause was the `Modifier.fillMaxWidth()` *on the
`Surface`* — `fillMaxWidth` sets the **minimum** width to the incoming maximum, so inside
the capped `Box` it asked for the cap and got it. Removing it is the whole fix; the `Box`'s
`widthIn(max = cap)` already leaves the minimum at zero, which is the pair of constraints a
shrink-wrapping child needs. After the removal the same bubble is `[968,531][1016,594]`.
This is the third revision of this one widget and the first that was measured rather than
reasoned about.

**The performance reading, on the working backend.** Opening the formula-heavy session
(26 messages, fifteen display formulas in one reply) and then scrolling up and down it
sixteen times:

```
open:      Total frames 119  Janky 15 (12.6%)  50th 23ms  90th 34ms   99th 150ms
scrolling: Total frames 201  Janky 55 (27.4%)  50th 28ms  90th 46ms   99th 450ms
```

The 99th percentile is the one to read: 150 ms is the single frame that opens the
conversation, and 450 ms is a formula arriving into view during a scroll. Both are the
deferred measurement doing its work on the UI thread — one frame each, once per formula,
remembered afterwards. **This is a `-gpu host` emulator on a laptop, so the absolute
frames are not a phone's, and no comparison against the reverted `LazyColumn` was made.**
What the numbers support is narrow and worth having: opening a formula-heavy conversation
is not a multi-second stall, and scrolling one does not produce a repeating stutter.

**What is still not verified, and cannot be by this instrument.** Text *selection* across
a display formula — and that is now a larger gap than it was, because selection is the
whole point of the change that made a display formula a `Text`. `adb shell input swipe`
does not raise Compose's selection toolbar here: a stationary three-second press and a
2.5-second drag both left the screen unchanged. What *can* be read from the accessibility
tree is that a display formula now publishes its own semantics the way an inline one does —
`content-desc="fraction: 1 over 2"` and `x squared` appear for the formulas in a table
cell — which is consistent with it being inside the text rather than beside it, and is not
the same thing as a selection working. **A manual check on a device is the only way to
settle it**, and it is the one thing in this round a reader should do by hand.

### The second round of the same three reports (2026-09-18, after the GPU fix)

With a working screen, five reports were measured rather than reasoned about. The numbers
are in ARCHITECTURE §12; what follows is what was seen.

| The report | What was done | What was seen |
| --- | --- | --- |
| `</>` 中间斜杠和两边括号连着了 | rasterise the three paths and compare their masks row by row | the first two revisions overlapped a bracket by 2.2 and 0.3 units at 100 px/unit; the shipped one clears by 1.36 left and 1.16 right, and renders correctly at 256, 96, 48 and 28 px through a headless browser |
| 行内代码背景无圆角 | open a reply with a code span | the span draws with a rounded fill; `SpanStyle` has no radius and ui-text 1.10 has no `BackgroundStyle`, so it is measured content now |
| 单独成段的公式仍无法选中复制；无法左右滑动；公式和行内代码没有选中高亮 | open a reply with two display formulas | both render, centred, and each is a `Text` node whose semantics carry the formula — see the selection gap above |
| markdown 和 latex 还是特别卡 | a probe in `MarkdownText`/`rememberMathInline` plus `dumpsys gfxinfo`, against a synthetic 300-block reply with 60 display formulas | compose+measure **807 ms** with one measurer per block against **741 ms** with one per reply; 210 measurements and 210 distinct measurers before, one per font size after. **Not fixed, improved.** |
| 思考等级 chip 每次打开都从 medium 跳到 high | read `shared_prefs/pikit_settings.xml` and `files/pikit-config.json` from the device | `thinking_levels_for=deepseek-flash`, `thinking_levels=off,high,max`, `modelId=deepseek-flash` — so the remembered levels *were* being persisted and the lookup by `state.model?.id` was the null key. The fallback to `saved.modelId` is the fix. |

**Two regressions this round produced and caught, both by looking at a screenshot rather
than by a test.** The first version of the formula-as-text change drew every display
formula as its literal LaTeX source, because `MdBlock.Formula` holds the body with its
delimiters stripped and the inline path decides what is mathematics *by looking for a
delimiter*; the delimiters are put back now. And a self-intersecting bracket path filled as
a solid wedge, which the row-by-row mask comparison in the icon script reported as a 3.0-unit
overlap. Neither was visible in the code and neither would have failed the JVM suite.

### The third round: the icon, and the scroll that is still slow (2026-09-18, latest)

**The `</>` was put back to `Icons.Filled.Code`, and an earlier diagnosis in this document
was wrong.** The claim was that Material3's `Icon` zeroes a stroked vector's
`strokeLineWidth`, which is why the standard glyph was replaced by a hand-drawn one. Read
out of `material3-android-1.4.0.aar`: `Icon` calls
`rememberVectorPainter(ImageVector, …)` — the overload that takes **no stroke arguments** —
so the glyph is drawn at the width its own `ImageVector` carries, and `Icons.Filled.Code`
in `material-icons-extended` 1.7.8 carries `strokeLineWidth = 4`. The reader's report on
the four hand-drawn replacements — "字体都变了，一点都不标准，保持原字体才行" — is the correct
verdict on all of them, and the collision three of them were fighting (the slash crossing
the chevron) is something the real glyph deliberately does. `ic_commands.xml` is deleted.

**The scroll: one real improvement, measured, and one approach ruled out by a crash.**

| | Janky frames | 50th | 90th | 99th |
| --- | --- | --- | --- | --- |
| 300-block reply, before this round | 33 (11.5%) | 24 ms | 34 ms | 600 ms |
| …with `DisplayFormulaLine` deferring off-screen formulas | **37 (8.6%)** | 26 ms | 32 ms | 850 ms |

`DisplayFormulaLine` draws a one-line `Spacer` until a formula is inside the window,
`onGloballyPositioned` decides, and `remember` means a measured formula stays measured. The
jank percentage falls; the 99th percentile does not, and **the honest reading is that the
frame that opens a 300-block reply is still around 800 ms.**

**Making `MarkdownText` a lazy list was tried again and threw again**, and this time the
stack trace was read instead of assumed:
`IllegalStateException: Vertically scrollable component was measured with an infinity
maximum height constraints`. The transcript does **not** give an answer item a bounded
height, because the item is wrapped in a `SelectionContainer` and the bubble. So the
premise in the previous round's notes — "the outer list gives this inner one a bounded
height" — is wrong for *this* call site too, and `AGENTS.md` now says so.

That leaves the remaining work on scroll named rather than done: a reply is one item of the
transcript's list and the list must know that item's height, so the reply's blocks cannot be
measured lazily from inside. **The fix that would work is to make the reply several items of
the transcript's list** — chunking it where the list is built — and that is a change to
`ChatScreen.kt`'s transcript rather than to `MarkdownText`, and it is not made here.

### The formula renderer: what it cost, and the migration to Operit's (2026-09-19)

**The report was "公式还是卡，看看迁移到 operit 那套方案的可行性"**, so the first number needed was
what the renderer this project used actually costs. A temporary probe page — `ProbePage.kt`, deleted
after the measurement, with the stress document the earlier round had used — drew the same
300-block, ~150-formula document with the formulas on and off, and two passes of counters (the
renderer's phases, then the worker's) were read out of `logcat`. All figures below are from the
emulator below.

| measurement | formulas on | formulas off |
| --- | --- | --- |
| document's first composition and layout, cold process | 806 / 1128 / 1205 ms | 351 ms |
| the same open again, renderer warm | 465 ms | — |
| 20 s in: the measurement worker's total | 142 formulas in **3078 ms** (21.7 ms each) | 0 |

The phase counters say what the cold cost is *not*: `rememberLatexMeasurer` ran 92 times in
**0 ms**, and the per-block token walk (`MathCache.keysFor`) plus `renderInline` together were
**0 ms**. So the renderer's own per-formula layout is the whole of the 3.1 s. **Once the cache is
full the mathematics costs nothing measurable while scrolling** — 17 ms median with the formulas on
and with them off, and 0 against 4 slow UI-thread frames in 585 and 590 frames respectively.

The frame tables were otherwise not usable as evidence: the same content produced 17 ms and 28 ms
medians in different runs (4.6 %–15.2 % janky) with no correlation to the switch, so the `-gpu
host` emulator is bimodal here and only the in-app counters are quoted.

**Operit's renderer, measured on the same device at the same 14 sp.** `ru.noties:jlatexmath-android:0.2.0`
lays a formula out in **422 µs mean, 307 µs p50, 1.5 ms worst** (including reading the intrinsic
size) and draws it into a bitmap in **183 µs p50**; 20/20 constructs render, including
`\begin{aligned}`, `pmatrix`, `cases`, `\text{}`, `\binom`, `\sqrt[3]`, `\mathbb{}` and
`\underset`. The AAR is 1.12 MB, it is on Maven Central, and `TeXIcon.getIconDepth()` supplies the
depth an inline placeholder needs to sit on the text's baseline — Operit itself resorts to
rewriting `FontMetricsInt` by hand for that. RenderX, the other LaTeX dependency Operit declares,
is not used by its chat path at all, so no JitPack repository and no GPL-3.0 dependency are
involved.

**It was adopted, and the same document says what it bought.** The three KaTeX-metrics libraries
were removed and `MathCache`'s worker now calls `JLatexMathDrawable.builder(...).build()`:

| on the same probe document | KaTeX metrics | JLaTeXMath |
| --- | --- | --- |
| the document's first layout, cold process | 806 / 1128 / 1205 ms | **575 ms** |
| the worker, one document's formulas | 142 in **3078 ms** | **146 in 51 ms** |
| scrolling, `50th` / 90th / janky | 17 / 18 ms / 4.6 % | 17 / 21 ms / 5.5 % |

The 351 ms that remains is what a 300-block reply costs with no formula in it at all: the
transcript still composes and measures every block, and still has to measure the whole item to know
its height. The fix for that is chunking a reply into several transcript items, as the note above
says, and it is still not made.

**The inline baseline was got wrong first, and the reading is worth keeping.** The first version
declared a placeholder of `height + depth` and drew the formula at the top of it, on the assumption
that a placeholder could be anchored below the baseline. It cannot: this file's chapter quotes
`AndroidParagraph`, where `PlaceholderSpan.ALIGN_ABOVE_BASELINE` is
`top = getLineBaseline(line) - span.heightPx` — the placeholder's **bottom** is the baseline, so a
formula's descender has nowhere to go and the whole formula is drawn a full depth too high. The
screenshot showed every fraction and integral floating above the sentence it belonged to. The
version that works declares the formula's whole height and draws the drawable **one depth lower**,
letting it overhang the placeholder; that is what `MathView.kt` does now, and a screenshot of the
same sentence — `f(x) = \frac{1}{2}x`, `x^{2}`, `\int_{0}^{1} f(t)\,dt` and `\sum_{k=1}^{n} a_k`
inline among Chinese text — shows all of them on one baseline.

**The display formula needed the opposite treatment, and only a screenshot caught it.** A formula
in a paragraph has leading to overhang into — a 22sp line box for 14sp text — so the inline
version above is right. A display formula has no line to sit on: its block *is* the line, and the
overhang is drawn outside the height the transcript measures and the scroll container clips at.
The screenshot of the table probe below shows the sum's lower half missing. A display formula
therefore declares `height + depth` and draws at the top of that box, which keeps every pixel
inside it; `rememberMathInline(..., display = true)` is the flag, and the same document after the
change is complete. A table cell's formula — the path whose prefetch changed shape in this round —
was checked in the same screenshot: `a_{1}`, `\frac{1}{2}`, `\sqrt{3}` and
`\int_{0}^{1} x^{2}\,dx` all typeset inside their cells, at the cell's own font size.

### The formula layout round, and the two reports after the swap (2026-09-19, later)

Two reports arrived together: "常用命令图标太大了" and "现在公式渲染位置和排版存在严重问题，请仔细检查
修改，我还发现行内公式换了行就不渲染，以及某些情况没渲染". The second one was right about three separate
things, and the first two attempts at it were both wrong.

**What the numbers say.** A diagnostic page printed what the eye cannot — Compose's own line boxes and
placeholder rectangles next to each formula's size — for one sentence at 14sp with `lineHeight = 22sp`,
density 2.625, so a line is 57.75 px and the font's own ascent/descent measure 38/13:

| formula | size (w×h) | depth | what it did |
| --- | --- | --- | --- |
| `x^{2}` | 51×48 | 10 | floated above the baseline |
| `\frac{1}{2}` | 44×91 | 36 | floated, and its denominator crossed the line above |
| `\int_{0}^{1} f(t)\,dt` | 183×106 | 43 | began a paragraph the line above no longer cleared |

The cause is in `PlaceholderSpan.getSize`: **a placeholder grows only one side of its line.**
`AboveBaseline` (the obvious choice for an inline image, and the first two versions here) grows the
ascent to the box height and leaves the descent alone, so a formula's descender has nowhere to go —
drawn to fill the box it floats a whole depth high, and drawn *and* pushed down by its depth it
overlaps the line below instead. `TextTop` is the mirror image. **`TextCenter` is the only alignment
that grows both**, because `getSize` centres the box on `(ascent + descent) / 2` and then grows the
two sides until it fits. `MathView.kt` now computes, per formula, the smallest box centred on the
text's centre that contains the ink and draws the drawable at the offset that puts the formula's
baseline on the line's; the text's centre comes from a `TextMeasurer` measurement of the same style.
A formula no taller than the text leaves the line alone (a 48 px formula in a 57.75 px line), and a
stacked fraction grows its own line — `\frac{1}{2}` needs 104 px, which is what a stacked fraction
needs. Screenshots before and after are the evidence; the after one shows `f(x) = \frac{1}{2}x`,
`x^{2}`, `\int_{0}^{1} f(t)\,dt` and `\sum_{k=1}^{n} a_k` inline among Chinese text on one baseline
with no overlap.

**The display formula needed the same fix and revealed a second fault.** Before the alignment change a
display formula's ink was drawn outside the height its own block reported, and the sum's lower half
was missing in a screenshot of a table probe — the scroll container clips at the reported height. The
same construction fixes it: the box contains the ink, so there is nothing left to clip.

**"行内公式换了行就不渲染" was not a wrapping bug at all — it was `\[…\]` inside a sentence.** A
formula on its own line starting with `\[` was already a display block; one written mid-sentence
("由 \[E = mc^{2}\] 可知") was not, so it was drawn as the brackets and backslashes it is written
with. The tokeniser now reads it inline, and `unescapeMath` — which stripped `\(…\)`, `$$` and `$` but
not `\[…\]` — strips it too; without that second half the renderer was handed the brackets, refused
them, and the reader saw the source. `\(…\)` was already handled, so the first attempt at this
change was dead code and was reverted.

**"某些情况没渲染" is eight constructs out of forty.** A sweep of the common commands, with the body
of every failure logged, produced exactly: `\ce`, `\color`, `\tag`, `\label`, `\cancel`, `\hcancel`,
`\oiint`, `\oiiint`. Everything else worked, including `\begin{align}`, `\operatorname`,
`\overbrace`, `\underbrace`, `\xrightarrow`, `\substack`, `\sideset`, `\boxed`, `\mathbb`,
`\mathcal`, `\displaystyle`, `\dfrac`, `\tfrac`, `\binom` and `\text{中文}`. `LatexCompat.kt` now
rewrites the first six — dropping numbering, unwrapping decorations, renaming `\ce` to `\text` and
`\color` to `\textcolor`, and aliasing `color` in JLaTeXMath's own (public) `MacroInfo.Commands`
table — and only the two glyphs the fonts do not have still fall back to their source. A formula that
still fails is logged by body, which is what made the list above possible in the first place.

**And the icon.** Lucide's `command` at the 21 dp the Material glyphs beside it use is a visibly
heavier mark, because the figure reaches 21 of its 24 units where Material's `Add` stays inside 14.
It is drawn at **17 dp** now, at the call site, with the imported vector untouched.

### The padded formula, the wide formula, and the copy mark (2026-09-19, last round of the day)

Four reports, four different causes, and one of them was two rounds old:

**"这种换行的仍然没渲染" was a delimiter rule, not a wrapping bug.** The formula in the screenshot —
`$P(A_i\mid B)=\dfrac{P(A_i)P(B\mid A_i)}{\sum_j P(A_j)P(B\mid A_j)} $` — rendered as its own source
and wrapped like prose. Reproduced on the emulator with six variants of the same formula, which
separated it in one run:

| variant | before |
| --- | --- |
| `$P(A_i\mid B)=1$` | formula |
| `$ P(A_i\mid B)=1 $` | **source** |
| `$P(A_i\mid B)=1 $` | **source** |
| `$ P(A_i\mid B)=1$` | **source** |
| a newline inside the delimiters | formula (the parser had already joined the lines) |
| `it costs $5 and $10 today` | prose, correctly |

So the rule that keeps a price a price — no space just inside either `$` — was rejecting every
formula a model pads, which is how TeX is written. It now rejects a padded body only when the body
does *not* look like mathematics: a `\command`, a script, a group or a relation. All four padded
variants render afterwards, `$ P(A_i\mid B) = 1 $` on its `=`, and "it costs $ 5 and $ 10" is still
a sentence. `MarkdownMathTest`'s "a formula padded with spaces is mathematics when it looks like
mathematics" is the case that fails without it, and the case beside it is the price that must not
become one.

**Two more formula reports in the same breath.** `\[…\]` written *inside* a sentence ("由 \[E =
mc^{2}\] 可知") was drawn with its brackets — the tokeniser knew `\(…\)` and only a line-leading
`\[`; and `unescapeMath` did not strip `\[`/`\]` even once the token existed, so the renderer was
handed the brackets and drew the source. Both fixed, and the second is why the first looked like it
had not worked.

**"无明显暗示能够滑动查看完整内容" is now drawn.** A display formula wider than the screen scrolls,
and gets a gradient at whichever edge has more to show plus a pill along the bottom reporting how
much of it is on screen and where the reader is in it. Verified by screenshot on a formula three
screens wide. An *inline* formula wider than its line cannot scroll — the paragraph scrolls
vertically, the transcript not at all — so it is now scaled down uniformly to fit, with a floor of
half size; a 1204px formula in a 1080px line was measured drawn at `scale=0.897` and complete.

**The copy mark: the question was the glyph, not the button.** Six *containers* were rendered at 1×
and 2.5× in both places the control appears (under a message, on a code block's header) and offered;
the answer was a picture of a glyph — two rounded sheets — with no container. The button is now
Lucide's `copy` at 18dp with nothing behind it, and Lucide's `check` for a moment after a tap. The
two remarks on the way (`现在的复制按钮不好看，换一种`, then the picture) are recorded in
ARCHITECTURE §12, because the wrong question was "what shape should the button be".

**And 拍照 moved above 图片 in the attach sheet**, which is a one-line reorder of the list — the row
the sheet exists for should not be the third one.

### The copy mark's path, and the release's asset names (2026-09-19, last of the day)

**The icon was wrong, and the reader found it by opening the source.** `ic_copy.xml` was supposed
to be an SVG they supplied, and the first version split its single path into two elements — which
meant turning the third subpath's **relative** move into an absolute one by hand, and getting it
wrong: `m170.666666 -256` is relative to where the *second* subpath ended (618.666667, 362.666667),
not to where the first began, so the back sheet was written at y=21.333 instead of y=106.667 — up
by **85.333 units**, exactly the inner contour's own offset. The two sheets were 256 apart instead
of 170.667, which is the "瑕疵" the reader saw.

The fix is to copy the path **verbatim**, relative moves and all, in one `<path>`; only the fill
colour and `fillType` are changed, and neither moves a point. `.tooling/copy-svg-check.py` compares
the two strings and now reports `identical: True` (771 characters each) — that is the check that
would have caught it, and it is the shape `AGENTS.md`'s warning about hand-adjusted glyphs asks
for: a path is data, and a conversion that cannot be avoided is arithmetic, not judgement.

**The published APKs were named after Gradle's output.** `gh release create` attached
`app-arm64-release.apk` and `app-x64-release.apk` — names that say neither the application nor the
version, on the page a reader downloads from and in the folder it lands in. The build job now
renames them in place, after the signing check and before the checksums and the artifact, so the
release carries **`PiKit-<version>-arm64.apk`** and **`PiKit-<version>-x64.apk`**, and `SHA256SUMS`
lines up with them. `docs/RELEASING.md` records the rule and the post-release check updated with
it.

**The two copy buttons were not the same colour, and one call site was to blame.** Under a message
the meta row asked for `onSurfaceVariant` explicitly; the code block's header took
`CopyButton`'s default, `LocalContentColor.current` — which is whatever the bubble's `Surface`
set, `onSurface`. In the light theme that is near-black against the grey the reader had just seen,
which is the report "代码块边上的复制按钮颜色太深了". The default is now
`MaterialTheme.colorScheme.onSurfaceVariant`, named in `CopyButton` rather than inherited, and the
message call site no longer passes a colour at all: one action, one colour, the same in both
places. Verified by rendering the real `MarkdownText` code block and the real meta row on the
bubble's own surface, in **both** themes — the two glyphs are identical in each.

**The turn's furniture is the accent colour now, and the code block's copy button is not.**
"把工作几秒、几个步骤、消息时间、消息复制按钮等都变成主题蓝色，代码块复制按钮不变" — three changes and one
deliberate non-change:

| where | was | now |
| --- | --- | --- |
| `工作 X 秒 · N 个步骤` (`TurnSummaryRow`) | `onSurfaceVariant`, with a `primary` chevron | `primary`, chevron unchanged |
| a message's timestamp (`MessageMeta`) | `onSurfaceVariant` | `primary` |
| the message's copy button | `onSurfaceVariant` | `primary` |
| the code block's copy button | `LocalContentColor` → `onSurface`, then `onSurfaceVariant` | `onSurfaceVariant` (unchanged in effect) |

`MessageMeta`'s `color` parameter is gone — both call sites passed the same value, so the row now
reads the accent itself — and the note that the chevron was "the one coloured mark among the page's
three disclosures" is corrected, since its label is the same colour now. Verified by drawing the
real `TurnSummaryRow`, a real `MarkdownText` code block and a real meta row on one bubble surface,
which is the comparison the report was about.

### The release APK typeset six constructs less than the debug one (2026-09-19, after the swap)

**The report was three formula blocks and a phone screenshot**: "我发现这三块公式只有第一块渲染正常了" —
`全概率`, `贝叶斯` and `合成一行`, of which the first drew and the other two were shown as
their LaTeX source, wrapped across lines. Reproducing it in the app with a stored session on
the emulator was the first surprise: **the same three bodies rendered**, and so did a control
line (`\dfrac{1}{2}`, `\frac{1}{2}`, `\sum_j P(A_j)`, `\sum_{j} P(A_j)`). The difference was
not the text — it was the APK. The emulator had the **debug** build; the reader installs the
**release** one, which is the only build R8 runs on.

The failure is logged, and the log names the cause rather than the symptom:

```
W PiKit: formula not typeset, drawn as its source: \dfrac{1}{2}
W PiKit: java.lang.NullPointerException: Attempt to invoke virtual method
         'java.lang.Object java.lang.reflect.Method.invoke(java.lang.Object,
         java.lang.Object[])' on a null object reference
```

JLaTeXMath resolves each macro entry point by name at run time (`Class.forName`,
`getDeclaredMethod`, `getDeclaredField` in `MacroInfo`/`TeXFormulaParser`), so R8 cannot see
the use, deletes the members, and the reflective lookup returns null. The same 42-construct
sweep (`\dfrac`, `\tfrac`, `\cfrac`, `\binom`, `\begin{align}`, `\left\{…\right.`,
`\operatorname`, `\substack`, `\ce`, `\color`, `\tag`, `\cancel`, …), the same sources, on the
same emulator, from `logcat`'s `formula not typeset` lines — reproducible with
`node tools/formula-sweep.mjs`, whose 42 constructs are listed in the file itself:

| APK | constructs that fell back to their source |
| --- | --- |
| `app-x64-debug.apk` | `\oiint`, `\oiiint` (both absent from the library's fonts — the documented limit) |
| `app-x64-release.apk`, before the fix | `\dfrac`, `\tfrac`, `\left\{\begin{matrix}…\right.`, `\begin{align}`, `\operatorname`, `\substack`, `\oiint`, `\oiiint` |
| `app-x64-release.apk`, after the fix | `\oiint`, `\oiiint` — identical to debug |

The fix is `-keep class org.scilab.forge.jlatexmath.** { *; }` in `app/proguard-rules.pro`:
the library is 330 KB of classes against a 107 MB APK, it reaches **218** `*_macro` entry
points this way, and a narrower rule would be a guess about which of its reflective paths
matter. The release APK grew by **32 KB** (107,231,914 → 107,264,682 bytes) for it.

**The checker was tested against the failure it is for, not only against success.**
`tools/check-release-math.py` compares every release APK's dex against the `*_macro` names the
library's own AAR defines (218 of them, read from the AAR's constant pools — the AAR rather
than a debug APK, because `release.yml` builds `--release-only`, where there is no debug APK,
and because "what the library defines" is the property that matters). Run while the arm64
release APK on disk was still the pre-fix build:

```
FAIL app\build\outputs\apk\arm64\release\app-arm64-release.apk: 218 of the library's 218 macro
     entry points are gone — the release build will draw those formulas as their LaTeX source
ok   app\build\outputs\apk\x64\release\app-x64-release.apk: all 218 macro entry points are in the dex
FAIL 1 of 2 release APK(s) cannot typeset everything the library defines
```

`tools/build-apks.py` runs it as its last step, after the APKs are built — the one checker that
cannot be in its `checks` list, since those run before anything is packaged.

**The copy button under an agent message was 5dp in from the message's own edge**: "对话页ai消息回复
气泡下面复制按钮最左侧没对齐气泡". It is not the row that was wrong but the *ink*: the button is
28dp with an 18dp glyph centred in it, so the 5dp the reader cannot see sits between the box the
row aligns and the mark they see. Measured with `uiautomator dump` at density 2.625, before and
after pulling the row out by `(28 − 18) / 2` dp:

| | message text | copy glyph | timestamp |
| --- | --- | --- | --- |
| before | x=32 | x=46 | x=111 |
| after | x=32 | x=33 | x=98 |

The glyph now lands on the message's own column (33 against 32, the remaining pixel being
rounding), and the timestamp kept its 18 px distance from the glyph. The inset is
`CopyButtonInkInset`, derived from the button's own two sizes rather than written down twice, and
the row is pulled on whichever side actually ends it: left for a left-aligned row, and right only
when there is no timestamp to end it instead.

### What a reader should check by hand

- **A drag that selects across a display formula.** `adb shell input swipe` does not raise
  Compose's selection toolbar on this emulator — a stationary three-second press and a
  2.5-second drag both left the screen unchanged — so this is unverified and is the
  headline promise of the formula-as-text change. The accessibility tree does show a
  display formula publishing semantics (`content-desc="fraction: 1 over 2"`), which is
  consistent with it being inside the text and is not the same thing.
- **The `</>` button**, which should now be the standard Material glyph at Material's own
  weight. Verified in a screenshot on the emulator, so this is a matter of taste rather
  than of evidence: it is Material's own vector, drawn by Material's own `Icon`.

## On a physical device

Xiaomi 15, Android 17 / API 37, `arm64-v8a`, installed alongside the official
`com.termux`:

First-run unpacking with the staging directory cleaned up; `node`, `bash`, `rg`,
`fd` and `pi 0.85.1` running from the relocated prefix; `pi --mode rpc` driven
through a real model-backed turn with tool calls and rendered Markdown; the
terminal's PTY, keyboard handling and extra-keys row; several concurrent terminal
sessions surviving a tab switch with their scrollback; renaming (both while a
conversation is open and while it is closed), pinning and multi-select delete;
model discovery against a live provider; saving a profile and the agent restarting
with the new `--model`; `pi update` reaching pi.dev from inside the runtime;
switching the interface language with the agent running; attaching images;
previewing a file from the Files tab; granting shared storage and writing through
it (`shared storage: symlinks=true writable=true`, a real write-and-read-back
probe); and coexistence with the real Termux installation, which is the point of
shipping under a separate application id.

## Known gaps

- **The model page's custom-parameters section was rewritten and has not been measured.**
  It is now one group of rows per model (a heading row naming the model, the image switch,
  and the two numbers as rows edited in a sheet) instead of a switch row plus two outlined
  boxes. What a `uiautomator` pass has to settle: the 38dp indent that aligns the number
  rows' labels under the switch row's title (it is arithmetic on another file's layout, so
  it is exactly the kind of thing that is off by a few pixels), whether a heading row, three
  controls and a divider still read as one group at font scale 1.8, and whether the sheet's
  field keeps focus with the keyboard up — it is a sheet rather than a dialog for that
  reason.
- **The first-launch screen is white on black now, and that is a colour claim.** The mark
  and the progress bar were amber; both are the launcher icon's white
  (`ic_pi_mark.xml`, `SETUP_FOREGROUND`). No test can see a colour, so this one is verified
  by reading the two drawables and the palette — the icon checker covers the launcher icon
  only.
- **Two rows have never been through a `uiautomator` pass**: the search page's "add an
  option" flow (an ~80-row picker with a filter field, and a value sheet holding a text
  field) and the workspace default on the agent page. Their behaviour below the UI is
  covered — `WebSearchStoreTest` for the store round trip, `tools/test-safety-guard.mjs`
  for the rules — but this project's bar for a *layout* claim is node bounds, and these
  two have none: the picker's list height against the keyboard, and that the value
  sheet's field keeps focus (it is a sheet rather than a dialog for exactly that reason).
- **"Open with" on a file under `~/storage` needs one device check.** The cause is
  established and fixed — `FileProvider.getUriForFile` resolves a file's *canonical*
  path, so a path that reads as `…/files/home/storage/shared/Download/a.txt` arrives
  as `/storage/emulated/0/Download/a.txt` and matched none of the configured roots,
  which is why the tap did nothing — and `res/xml/file_paths.xml` now carries an
  `external-path` root. What is not yet demonstrated on a device is the round trip
  into a receiving app, and the chooser's behaviour for a MIME type nothing claims.

- **Executable bits are decided by path.** A zip entry carries no Unix mode the JVM
  will apply, so the installer keeps a list. It originally missed
  `lib/node_modules/<package>/bin/`, which made `bin/npm` a symlink to a file that
  could not be executed — invisible until an update to a newer pi existed. Both the
  installer and the on-demand repair cover it now.
- **Three `dpkg` packaging-developer scripts** (`dpkg-buildapi`, `dpkg-buildtree`,
  `dpkg-fsys-usrunmess`) want `perl`, which is not bundled. `apt`, `dpkg` and `pkg`
  install and remove packages without it, and the official bootstrap has the same
  omission.
