# On the emulator

*[Verification](../VERIFICATION.md): the emulator pass — the instrument it needs, first launch and
permissions, storage, the runtime, and the interface measured with `uiautomator`.*

The device is the Medium Phone AVD, Android 36.1, `x86_64`, unless a line says otherwise.

## The graphics backend the emulator needs

**On software rasterisation the GPU context times out and the window never finishes its first
draw.** The window read `inputConfig=NOT_VISIBLE, alpha=0` in `dumpsys input` while `dumpsys window`
called it visible and fullscreen, `uiautomator dump` returned a correct tree, and every `input tap`
was rejected with `InputDispatcher: No new touched window`. `dumpsys gfxinfo` named the cause:
`GPU Context timeout: 10` and two frames in the GPU histogram at **4950 ms**. Starting the same AVD
with `-gpu host` fixed it outright, on the same APK. Attempts recorded as "unverified because touch
injection is broken" were this backend, not the app and not the instrument (`AGENTS.md`).

## First launch and permissions

- **The first-launch permission sequence.** After
  `appops set --uid pi.kit.mob MANAGE_EXTERNAL_STORAGE deny` and `pm clear`, the
  platform's own log shows the notification request opened and closed **60-360 ms**
  later with no user input and no permission state change (`granted=false`, no
  `USER_SET`), and with the request removed entirely no prompt appeared at all, so
  the request stays. Two app-side defects were fixed and are visible in the same
  log: a *second* request used to abort the first (`Can request only one set of
  permissions at a time`, 66 ms after the first), and the request used to be raised
  from the app's first composition. It is now raised once per process, only after
  the runtime is ready, retried twice when the callback comes back in under a second
  (which cannot be a decision), and the storage dialog follows its answer. On this
  emulator every attempt is dismissed by the platform, so the sequence there reads
  as three `Displayed … GrantPermissionsActivity` lines ~1.7 s apart and then the
  storage dialog; ARCHITECTURE §5 has the log excerpt.
- **The first-launch storage prompt** appears once, opens Android's "All files
  access" page, and does not return after being answered.

## The repair button, on a fresh install

`PrefixPatcher` used to rewrite the eight files the build protects; a full-prefix
sha256 catalogue of all 19 325 files before and after one press showed exactly three
changed — `bin/dpkg`, the apt hook and `libexec/pikit/relocate.js` — after which the
relocator answered `OLD_ID === NEW_ID` and exited 4, and the storage self-test fell
from `10 passed, 0 failed` to `8 passed, 2 failed` (both failures were its
relocator-boundary checks, which key on the refusal message the misconfigured script
never reaches). With the exclusion list in place the same press reports
**`无需重定位：所有文件都已匹配本应用的前缀。`**, shows progress while it runs
(`正在检测…已扫描 1792 / 5120 / … / 23808 个文件` over ~20 s), leaves
`relocate.js:99` reading `const OLD_ID = 'com.termux'`, passes
`pikit-relocate --selfcheck`, and the self-test still reads `10 passed, 0 failed`
afterwards.

## Storage, end to end

- With "all files access" granted and two folders on, the app logged
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

## The runtime, and a live `pkg install`

The Termux repository is unreachable from the emulator, so
`tools/build-hooktest-package.py` builds a synthetic package carrying the upstream
prefix in a shebang, an ELF-ish blob and a symlink target, and installs it from a
local apt repository. It reported `relocated 1 archive(s), 3 occurrence(s)`, and the
installed script's shebang came out as
`#!/data/data/pi.kit.mob/files/usr/bin/sh` with no trace of the old prefix. This is
the test that found the `file://` gap described in ARCHITECTURE §4.

## The pi configuration the app owns

With a DeepSeek profile in `pikit-config.json` and a hand-written `models.json` in
place, one launch left `settings.json` as
`{"defaultTools":[…],"packages":["…/pikit-extensions/node_modules/pi-web-access"],"defaultProvider":"deepseek","defaultModel":"deepseek-chat","defaultThinkingLevel":"medium"}`
and the user's `models.json` **byte-identical** — the file the app used to delete
whenever a built-in provider was active. A `pi` run by hand with the environment the
Terminal tab spawns then reached the provider and answered `401 Authentication
Fails, Your api key: ****-key is invalid` for the placeholder key, which is the
point: the model and the credential both arrived, where before it answered "No model
selected".

The search settings page renders under Settings, beside Model & provider and
Language, with the extension's version in its first row; `web-search.json` is
created at launch as `{"workflow": "none"}` and the page shows that workflow as
**当前使用**.

## A turn against a live model

Against a custom endpoint, a prompt produced a real tool call and a real answer,
recorded as `stopReason=toolUse tools=[bash]` → `toolResult 'PIKIT_E2E_OK\n'` →
`stopReason=stop`, with token counts. This is how the `--api-key` gap in
ARCHITECTURE §6.1 was found.

## The interface, measured with `uiautomator`

- **The chat UI**, measured with `uiautomator` rather than eyeballed: a finished
  turn folds to its prompt, a content-sized chip and its reply; the tab strip stays
  at `y = 2251` whether the keyboard is up or down, i.e. it is covered rather than
  pushed; the composer's control row carries the agent state, the thinking level,
  the profile name and the context percentage, and the last of those reads
  `context 0%` before anything is sent.
- **The prompt bubble's defect was its width, not its alignment.** The `0.86f` cap
  travelled through Material3's `Surface` — which puts its content in a
  `Box(propagateMinConstraints = true)` — into the bubble's `Column`, so every
  prompt drew an 86%-wide bar: `ok` measured 842 px for two characters, exactly the
  cap. `Alignment.End` was never wrong; it is `BiasAlignment.Horizontal(1f)`, i.e.
  `space - size`, and `Alignment.CenterEnd` does not compile as `wrapContentWidth`'s
  argument at all (ARCHITECTURE §12.1, `AGENTS.md`).
- **The command sheet shows exactly eight rows** — `!`, `/new`, `/compact`, `/stop`,
  `/clone`, `/export`, `/model`, `/clear`, with no `/websearch`, `/curator`,
  `/google-account`, `/search` or `/llama` — and the `!` row works: `uname` from it
  appended a real `{"role":"bashExecution","command":"uname","output":"Linux\n","exitCode":0}`
  record. The composer's row is three 40dp buttons with `常用指令` immediately left
  of the `+`.
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

## Models and thinking levels

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
- **The chip used to jump from medium to high on every open.** `thinking_levels_for=deepseek-flash`
  and `thinking_levels=off,high,max` *were* persisted in
  `shared_prefs/pikit_settings.xml`, and the lookup by `state.model?.id` was the null
  key, so the fallback to `saved.modelId` is the fix.
- **The catalogue refresh was measured against the bundled runtime** rather than only
  reasoned about: with a fresh agent directory and one dummy key, `--provider deepseek
  --model deepseek-flash` answered `["off","high","max"]` (three levels, the
  `deepseek-v4-pro` fallback, with pi's `Model … not found` warning on stderr);
  `pi update --models` then reported `Model catalogs refreshed` in 0.67 s and wrote one
  `models-store.json` entry, and the identical agent command answered
  `["off","low","high","max"]` (four). The same probe with no credentials at all
  finished in 0.25 s having written nothing, which is what "visited configured
  providers only" means. Recorded in ARCHITECTURE §7.2 — including two corrections that
  came out of reading pi's own source afterwards: the probe was run `--offline`, and
  *that flag is not what made the catalogue stale*; and RPC mode does start a
  `modelRuntime.refresh()` of its own at launch, but that one republishes the overlay
  inside the running process and never writes `models-store.json`, so the store is still
  the app's to refresh. It now does so on pi's own four-hour window — zero was the policy
  while the refresh covered only the profile's provider, and thirty-two catalogues per
  launch is a different order of cost.

## Sessions

- **The bundled bundle was read, not guessed, for the session-switch defect.** pi's
  `RuntimeHost.teardownCurrent` — the first thing `switch_session` and `new_session`
  do — starts with `await this.session.abort()`, so there is no way to leave a session
  without ending the turn that is running in it; the app refuses the move instead and
  says so (`TurnInFlightException`). The same read established what `PI_OFFLINE` gates
  (`modelNetworkEnabled`, `getLatestPiRelease`, `refreshModelCatalogs`,
  `checkForPackageUpdates`, `reportInstallTelemetry`), which is what ARCHITECTURE §2
  and §7.2 now record.
- **Switching conversations keeps the folding** — turns are re-derived from
  `get_messages`, with real durations read off the stored timestamps.
