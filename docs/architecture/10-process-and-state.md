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

## Where state lives

| What | Where |
| --- | --- |
| Profiles, API keys, conversations, runtime | `<app files>/pikit-config.json`, `pi-sessions/*.jsonl` (written by pi), `usr` |
| Thinking level, working directory, language, pins | SharedPreferences `pikit_settings`, `pikit_session_pins` |
| Which folders the agent may reach | SharedPreferences `pikit_storage` (absent means **none**); whether the first-launch prompt was answered |
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
