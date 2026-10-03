# 10. Process and state ownership

*[Architecture](../ARCHITECTURE.md) §10.*

```
PiAgentSession  (process-wide singleton — the single source of truth)
  ├─ TermuxEnv + BootstrapInstaller   first-run unpack
  ├─ SettingsStore                    provider / model / key / cwd
  ├─ PiProcessLauncher → Process      `node cli.js --mode rpc ...`
  ├─ PiRpcClient                      framing, correlation, stderr, shutdown
  ├─ ConversationReducer              events → ConversationState (pure)
  └─ StateFlow<ConversationState>     observed by Compose
PiAgentService  (foreground service)  keeps the child alive when backgrounded
```

The reducer is a pure function over `(ConversationState, PiRecord)`, so the state machine is
testable on the JVM against captured wire traffic — no device, no live model. The service owns no
state, so the UI can bind and unbind freely and a turn keeps running when you leave the app.

## The keep-alive is the notification, and the notification is the system's to hide

A foreground service exists *because* of its notification: that is the deal Android offers. What
the app does control is *when* that entry is up. [PiAgentService] drops the shade entry the moment
a window of the app is in front — the Activity is already keeping the process alive, and a
persistent "agent is running" over a turn the reader is watching is noise ("去除 agent is running
的通知"). `MainActivity.onStart`/`onStop` tell the service which side of that line the UI is on;
the status collector keeps the claim honest on the other axis (running vs failed vs stopped).

When the entry *is* up it is silent and empty of prose: `PRIORITY_MIN`, an `IMPORTANCE_MIN` channel
with no sound or badge, and no title. It is the price of the foreground claim, not an
announcement. A user who wants even that gone turns the *Agent* channel off in system settings:
`startForeground` still succeeds and the service stays a foreground service.

Keep-alive has three layers, and the report "息屏后会直接 terminated" is what made the third one
necessary:

1. **The foreground service**, with `android:stopWithTask="false"` so a swipe-away does not take
   the child with it. The shade entry only appears while the UI is gone.
2. **Two wake locks.** The turn lock (`pikit:agent-turn`) is acquired on `agent_start` and
   released on `agent_settled` — it keeps the CPU awake *and* the child's stdout drained for the
   length of one turn. The background lock (`pikit:agent-bg`) is held while the UI is gone and
   the agent is up, which covers the screen-off with nothing streaming that the turn lock never
   saw.
3. **The battery-optimisation exemption.** Neither of the above survives a power manager that has
   already decided to reclaim the process. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is the system's
   own dialog and cannot be granted by the app; the Agent page's keep-alive row reports whether
   it is held and offers the ask.

Two lifecycle facts are load-bearing, and both were measured on a device rather than reasoned:

- **`startForeground` is owed before the agent is up, not when it is.** A cold launch is in
  `Stopped` while the runtime image unpacks, which is far longer than Android 12+'s five-second
  deadline. Waiting for `Running` before promoting is a
  `ForegroundServiceDidNotStartInTimeException` on first run. The service promotes as soon as it is
  started and the status collector takes the entry away again if the agent is not actually up — a
  brief "running" during `Starting` is the cheaper lie.
- **The service tracks `AgentStatus` rather than deciding once in `onStartCommand`.** Deciding once
  meant a start that later succeeded from the UI — the Agent page's Restart, `scheduleRestart`
  after a settings edit, the one automatic recovery after a crash — never brought the service back,
  so the turn that needed keep-alive most was the one running without it; and a deliberate stop from
  the Agent page left the shade claiming "running". The collector keeps the notification honest,
  tears the service down when a stop the user asked for lands (debounced, because the two restart
  helpers stop and start in one breath), and `startAgent` itself asks for the service on success,
  which is what covers every caller that is not `MainActivity`.

## Where state lives

| What | Where |
| --- | --- |
| Profiles, API keys, conversations, runtime | `<app files>/pikit-config.json`, `pi-sessions/*.jsonl` (written by pi), `usr` |
| Thinking level, working directory, language, pins | SharedPreferences `pikit_settings`, `pikit_session_pins` |
| Which folders the agent may reach | SharedPreferences `pikit_storage` (absent means **none**); whether the first-launch prompt was answered |
| Whether the battery-optimisation exemption was asked for | SharedPreferences `pikit_power`; the exemption itself is the platform's (`isIgnoringBatteryOptimizations`) |
| Agent instructions and guard | `<app files>/home/.pi/agent/AGENTS.md`, `.../extensions/pi-safety-guard.ts` |
| The agent's workspace | `<app files>/home/workspace` (the default working directory) |
| Shared storage links | `<app files>/home/storage/*` → `/storage/emulated/0/...`, one per folder switched on |

Everything is under the app's private data directory, and uninstalling the app deletes all of it.
Nothing is written outside it except through the folders switched on under **Settings → Shared
storage**; the API key's exposure is in [§5](05-storage-and-safety.md).

## What a backup carries, and why a restore has to re-read

**Settings → Backup & restore** writes a selection of the table above to one `.zip` and puts it back.
The page is `ui/settings/BackupPage.kt`; the reasoning is here rather than in [§9.2](09.2-settings-pages.md)
because the subject is state ownership — which of these files is the app's, which is pi's, and which
of them anything actually reads — and not the layout of a page.

**Two halves travel differently, and the difference is otherwise a silent one.** Files are copied byte
for byte and extracted back to where they came from. Preferences are *not*: a `SharedPreferences` file
is not the source of truth while the process runs — every value the app reads comes from an in-memory
copy loaded at the start of the process — so writing `shared_prefs/pikit_settings.xml` behind the
app's back would change nothing until the next launch. The preference files therefore travel as one
typed JSON document each (`BackupArchive.encodePreferences`) and are applied through the API on the
way back in. Each value is written as `{"type": …, "value": …}` rather than as a bare JSON value,
because the type is not recoverable from the value: `1` is a valid `int` and a valid `long`, and
`getInt` on a `putLong` throws — into whoever reads it next, not into the restore.

**Nothing under `usr/` is in the archive.** The runtime is hundreds of megabytes of image unpacked out
of the APK, so a reinstall puts it back — the same for `usr-staging/`, `runtime-revision.txt` and pi's
own `models-store.json` catalogue cache, which the launch path refreshes on a four-hour window
([§3](03-what-is-baked-in.md), [§6.2](06.2-models-and-catalogue.md)). The catalogue's *deadline* is in
`pikit_model_catalogue` and is deliberately not exported either: it is one timestamp this app
re-decides by itself, and a restored one would either do nothing or make the app skip a refresh it
should have made.

**A restore is a merge, and nothing is ever deleted.** An entry of the archive replaces the file of
that name; a preference is applied key by key, so a key the archive does not carry keeps the value it
has and every other file stays. "Restore" sounds like *wipe, then write*, and a design that did that
would delete the user's current workspace to make room for an older one — a tap that destroys a day's
work, with the file picker's own three taps as its whole warning.

**The agent is stopped for it.** pi holds the conversation it has open and rewrites `models.json` and
`settings.json` at the start of every launch, so a restore made under a running agent is undone by the
next thing the agent does — or lands in the middle of a turn it is still writing. The manager stops
it, applies, and starts it again on the restored configuration. The restart is in a `finally`, because
the one outcome worse than a restore that did not happen is an app whose agent has silently stopped.

**Writing the files is only half a restore, and the other half is a list.**
`PiAgentSession.reloadAfterRestore` is that list. Every store here read its file once, at construction,
and then holds a copy: `SharedPreferences` hands a single cached instance to everyone who asks,
`SettingsStore` publishes a `StateFlow` built from an `AtomicReference`, `WebSearchStore` holds the
document it parsed, and the conversation listing caches its parse keyed by `(path, modified, length)`.
A restore that wrote the files and left all of those alone would show a restored theme, language,
model and conversation exactly as they were before it — indistinguishable from a restore that did
nothing. It is one function rather than each store watching its own file because `SettingsStore` →
`ProfileStore` → the agent launcher is a single chain with one reader at its end, and a second watcher
would be a second answer to "who owns this file". It runs **before** the agent is restarted, because
the launch path re-derives `models.json` and `settings.json` *from* the restored profile.

**The archive is refused before anything is written.** The manifest is the first entry of the zip, its
`format` is the one field an import refuses on, and what is selected is unpacked **in full** into a
staging directory before any of it is moved into place — so a truncated download, or a zip the
platform refuses halfway through, fails with the app untouched. A staged entry's target is measured
against the staging root's canonical path, the same zip-slip guard `BootstrapInstaller.safeTarget`
uses for the runtime image: an entry name is a string the archive chose, and `..` in one is an archive
that writes outside the app.

**The keys question is asked, and the answer travels.** `pikit-config.json` and `web-search.json` hold
credentials in cleartext, and `android:allowBackup="false"` is in the manifest precisely to keep them
out of cloud backups. The page therefore carries one switch — include the keys or not — and the
manifest records which was chosen, because it cannot be worked out from the entries: a profile with its
key blanked looks exactly like a profile whose key was never filled in, and the reader of an archive
somebody else handed them has to be told which of the two they have. What comes out is a named recipe
per file rather than a generic one — a profile's `apiKey`, the web-access extension's
`*ApiKey`/`*ApiToken` fields — because `models.json`'s `apiKey` names the *environment variable* that
holds the key (`PIKIT_API_KEY`), so blanking it would produce a file the custom endpoint cannot
resolve while protecting nothing ([§6.1](06.1-custom-endpoints.md)).

**The run is owned by the session, not by the page.** A workspace backup is thousands of files, and a
restore is a stop, an unpack and a restart; the page is one tab that leaves the composition the moment
the user looks at another one. `BackupManager` therefore hangs off `PiAgentSession`, beside
`repairInstalledPackages`, which walks `$PREFIX` for the same reason — and not off the composition the
way `CatalogueUpdater` and `StorageSelfTest` are, because those run for seconds and their result is a
line of text, where losing this halfway is a half-restored device.

**The destination is the system's file picker, not a path.** The archive has to be able to leave the
app, and the app has no business choosing where it goes; `CreateDocument`/`OpenDocument` is also what
keeps the whole feature working with **no** storage permission, which matters on a page whose sibling
rows are about granting exactly that.


---
