# The maintenance page

*[Verification](../VERIFICATION.md): what the agent update could do to a working install,
the reading that settled it, and what the page does instead.*

## What the page used to do, and what it left behind

The maintenance page had one button that ran `pi update --all` and then
`pi update --models` inside the runtime. The first of those is pi's own self-update, and
what it runs on this install is `npm install -g --ignore-scripts --min-release-age=0
@earendil-works/pi-coding-agent@latest` with the `--prefix` inferred from where pi's own
files live (read out of the 0.86.1 bundle's `getSelfUpdateCommandForMethod`;
0.85.1 constructs the identical command). It rewrites
`$PREFIX/lib/node_modules/@earendil-works/pi-coding-agent` **in place**: no staging
directory, no verification, no previous tree kept. An install that stops halfway is
therefore a working prefix with a half-written agent in it, and the app cannot tell the
difference — `PiUpdater` reported success from the exit codes and from the version in the
new `package.json`.

The failure this produced, on a phone after an upgrade to pi 0.86.1:

```
pi exited with code 1
Error: Failed to load extension "/data/user/0/pi.kit.mob/files/home/.pi/agent/extensions/pi-safety-guard.ts":
Failed to load extension: Cannot find module 'jiti'
Require stack:…
```

`jiti` is the mechanism, not the cause. PiKit bundles the delete guard as TypeScript
(`tools/pi-safety-guard.ts` → `$HOME/.pi/agent/extensions/`), and **0.86.x loads a
TypeScript extension through `jiti`**, which is a dependency of that release and not of
0.85.1. A tree left without `jiti` therefore fails at extension load, which is fatal:
pi exits 1 before the RPC handshake, so the app has no agent at all. The report's own
sequence — the update button, then an abandoned install — is consistent with this and is
the reason the button is gone rather than repaired.

## What the 0.86.1 agent itself does, measured

The version is not the problem, and that was settled before the fix was chosen. The x64
debug APK of this repository (pi 0.85.1 built in) was installed on the Medium_Phone AVD,
its runtime unpacked, and then the app's own pi tree was replaced with an npm-installed
0.86.1 tree **inside the app's data directory**, after which the app was restarted:

```
launching: …/bin/node …/dist/bundle/cli.js --mode rpc --approve --session-dir …/pi-sessions
           --provider deepseek --model deepseek-flash --api-key … --thinking low
handshake get_state: success=true error=null
  model={"id":"deepseek-flash","name":"DeepSeek V4.1 Flash","api":"openai-completions",
         "baseUrl":"https://api.deepseek.com","provider":"deepseek",…}
```

0.86.1 starts, answers `get_state` and loads the bundled TypeScript guard — the same
extension whose load is the failure above, with `jiti` present. The same pass was then
repeated on the rebuilt x64 debug APK (pi 0.86.1 and web-access 0.30.0 inside it, app data
cleared first, so the runtime unpacked fresh): `Agent 已就绪`, `已安装版本 0.86.1` on the
maintenance page, and the page's own Chinese strings as measured with `uiautomator` —
`模型列表 / 需要时手动刷新模型列表 / 立即刷新`, the four-hour note under it, and the
relocation note that now opens with `仅在已安装的东西确实启动报错时才需要点`. Pressing
`立即刷新` showed the progress state (`正在联系各服务商…`) and then
`模型列表已是最新。`, which is also the true outcome on that emulator: it has no network,
so the refresh changes nothing.

Three more readings went with this one:

- **A global install does not damage the rest of the prefix.** `npm install -g
  --ignore-scripts --min-release-age=0` of the same release into a replica of
  `$PREFIX/lib/node_modules` (the vendored 0.85.1 tree plus the bundled
  `pikit-extensions`) added 118 packages, left `pikit-extensions` byte-count-identical,
  and kept the nested dependency layout the image ships. Node resolved indifferently
  across both layouts in the emulator run above.
- **The release moves two things the app parses.** 0.86.1's bundled catalogue gives
  `deepseek-flash` four thinking levels (`off, low, high, max`) where 0.85.1's fallback
  gave three (`off, high, max`) — a catalogue fact, not a code change.
- **The newest model id in the catalogue resolves to a different display name**
  (`DeepSeek V4.1 Flash` where 0.85.1 answered `deepseek-flash`), which is pi's own
  catalogue doing its job and is why the pin is bumped with a rebuilt image rather than
  left to `latest`.

## What the page does now

The pi and extension update is **removed**, not repaired, and the model-catalogue
refresh is kept as its own button:

- **pi moves only with a PiKit release.** `PI_VERSION` in `tools/build-runtime-image.py`
  is the pin, the image digest covers it and the builder's own source, so a bump rebuilds
  the runtime and a device unpacks it. A version that ships inside the APK cannot be
  half-installed, which is the property that was missing.
- **The model list has a button, and the launch path has a window.** Both go through one
  function, `PiAgentSession.requestCatalogueRefresh`: the launch path checks pi's own
  four-hour freshness window first (`CATALOGUE_REFRESH_WINDOW_MS`), the button skips the
  test, and both write the same deadline back and test "did the catalogue change" the
  same way, so the button and the window cannot come to mean two different things about
  `models-store.json`. `CatalogueUpdater` is the button's state, held by `SettingsScreen`
  so leaving the page mid-refresh does not discard the result.
- **The page says when to press it.** The row is "Model list / Updates the model list on
  demand", the button is "Refresh now", and the note under it states the four-hour
  automatic window, that a just-released model is the reason to force a refresh, that it
  needs a network connection, and that pi itself is not updated there.
- **The relocation note leads with "only if something already errors".** The button had
  been pressed on healthy installs, because the row ("Package relocation") reads as
  housekeeping; the note now says that anything installed with `pkg` or `apt` is
  rewritten as it installs, and that the button is for a package that arrived another way
  and refuses to start.

## The extension pin moved with it

`WEB_ACCESS_VERSION` went 0.29.0 → 0.30.0, and the row set the search page renders was
diffed against 0.30.0's own sources rather than assumed: it adds six keys
(`serplyApiKey`, `fetch.defaultMode`, `fetch.allowedModes`, `webSearch.allowedProviders`,
`openaiUseProviderBaseUrl`, `openaiUseAlphaSearch`) and removes **none** — the comparison
is an extract of every optional interface key in each release's TypeScript, 318 keys
against 325, and the seven in 0.30.0 that are not in 0.29.0 resolve to those six paths
plus `useProviderBaseUrl`, a nested spelling of one of them. `SEARCH_PROVIDERS` gained
`serply` (31 entries; the extension resolves 31 plus `auto` and `all`).

The vendoring cache had to be fixed to make that true: `vendor_web_access` reused
`node_modules` whenever it existed and only re-checked the named runtime dependencies, so
a version bump would have rebuilt the image **around the tree the cache vendored first**
while `build-metadata.json` named the new version. It now records `@version` in
`pikit-vendored.json` and re-vendors on a mismatch, exactly as `vendor_pi` does. Measured
on this machine after the fix: the first build logged
`re-vendoring the web-access extension: the cache holds an unrecorded version, this build
wants pi-web-access@0.30.0`, the second `reusing vendored web-access extension from cache
(pi-web-access@0.30.0)`; both logged `defuddle/node extraction ok (160 chars)`, and both
architectures passed `tools/verify-runtime-image.py`.

## What is not verified

- **The button was pressed, but never against a network.** The emulator this was
  investigated on has no working network (`ping 8.8.8.8` 100% loss, `getaddrinfo
  ENOTFOUND` for `registry.npmjs.org`, and `fetch` to a literal address fails), so
  `pi update --models` cannot complete there. The run reached its result state and the
  result was the true one — the catalogue had not changed — but it got there the same way
  a successful no-op would, because `CatalogueStatus.Done` answers "did the catalogue
  move" and a refresh that failed has not moved it. `refreshCatalogue`
  logs the exit code, so a *failure* is visible in `logcat` (`pi update --models: exit=1
  Error: Could not refresh model catalogs: …`) and not on the page; a device pass against
  a real network is what would exercise the success path, and a page that distinguished
  "asked and nothing changed" from "could not ask" would be a better shape than this one.
- **The stopped-install failure itself was not reproduced.** The emulator cannot run
  pi's self-update for the same reason. What was measured is the state it leaves —
  0.86.x's `jiti` requirement, the guard's load path, and a 0.86.1 tree that works when
  complete — and the fix removes the code path rather than the symptom.
- **The new extension pages were not driven.** The six added rows are pinned by
  `WebAccessParamsTest` and by the render path's own tests, and the 0.30.0 extraction
  path is exercised by the build; a `fetch_content` call through the bundled 0.30.0 is a
  device pass.
