# Verification status

What has actually been exercised, and how. The reasoning behind each result — and
every design that was tried and rejected along the way — is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## On a build machine

Moved to [verification/build-machine.md](verification/build-machine.md): the unit suites and
every checker that needs no device, with what each one covers.

## The dated rounds

Each round is kept whole in a file of its own, because the numbers in it are the evidence a
later claim rests on. The reasoning behind every design is in [ARCHITECTURE.md](ARCHITECTURE.md);
what is *verified now* is the rest of this file.

| Rounds | File |
| --- | --- |
| 2026-09-18: Markdown and formulas, a slow session, the crashes, and the third round | [2026-09-18-rendering-rounds.md](verification/2026-09-18-rendering-rounds.md) |
| 2026-09-19: the renderer swap, and the line box after it | [2026-09-19-renderer-swap.md](verification/2026-09-19-renderer-swap.md) |
| 2026-09-19: the padded formula, the wide formula, and the copy mark | [2026-09-19-formula-and-copy.md](verification/2026-09-19-formula-and-copy.md) |
| 2026-09-19: the release APK's lost macro entry points | [2026-09-19-release-dex.md](verification/2026-09-19-release-dex.md) |

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

### What a reader should check by hand

- **A drag that selects across a display formula.** `adb shell input swipe` does not raise
  Compose's selection toolbar on this emulator — a stationary three-second press and a
  2.5-second drag both left the screen unchanged — so this is unverified and is the
  headline promise of the formula-as-text change. The accessibility tree does show a
  display formula publishing semantics (`content-desc="fraction: 1 over 2"`), which is
  consistent with it being inside the text and is not the same thing.
- **The command button's mark**, which is Lucide's `command` (⌘) at 17dp rather than the
  21dp the Material glyphs beside it use. Verified in a screenshot on the emulator, so it
  is a matter of taste rather than of evidence: it is one borrowed glyph at a call-site
  size (`AGENTS.md`), not a redrawn vector.

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

- **Executable bits are decided by path, and that is now covered rather than a gap.** A
  zip entry carries no Unix mode the JVM will apply, so the installer keeps a list. It
  originally missed `lib/node_modules/<package>/bin/`, which made `bin/npm` a symlink to a
  file that could not be executed — invisible until an update to a newer pi existed. Both
  the installer and the on-demand repair cover it now.
- **Three `dpkg` packaging-developer scripts** (`dpkg-buildapi`, `dpkg-buildtree`,
  `dpkg-fsys-usrunmess`) want `perl`, which is not bundled. `apt`, `dpkg` and `pkg`
  install and remove packages without it, and the official bootstrap has the same
  omission.
