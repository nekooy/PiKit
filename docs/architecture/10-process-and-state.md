# 10. Process and state ownership

*[Architecture](../ARCHITECTURE.md) §10.*

```
PiAgentSession  (process-wide singleton — the single source of truth)
  ├─ TermuxEnv + BootstrapInstaller        first-run unpack
  ├─ SettingsStore                          provider / model / key / cwd
  ├─ PiProcessLauncher → Process            `node cli.js --mode rpc ...`
  ├─ PiRpcClient                            framing, correlation, stderr, shutdown
  ├─ ConversationReducer                    events → ConversationState (pure)
  └─ StateFlow<ConversationState>           observed by Compose
PiAgentService  (foreground service)        keeps the child alive when backgrounded
```

The reducer is a pure function over `(ConversationState, PiRecord)`. That is what
makes the conversation state machine testable on the JVM with nothing but
captured wire traffic — no device, no emulator, no live model. The service owns no
state. The UI can bind, unbind and re-attach freely, which is why a turn keeps
running when you leave the app.

## Where state lives

| What | Where |
| --- | --- |
| Profiles, API keys, conversations, runtime | `<app files>/pikit-config.json`, `pi-sessions/*.jsonl` (written by pi), `usr` |
| Thinking level, working directory, language, pins | SharedPreferences `pikit_settings`, `pikit_session_pins` |
| Which folders the agent may reach | SharedPreferences `pikit_storage` (absent means **none**); also whether the first-launch prompt was answered |
| Agent instructions and guard | `<app files>/home/.pi/agent/AGENTS.md`, `.../extensions/pi-safety-guard.ts` |
| The agent's workspace | `<app files>/home/workspace` (the default working directory) |
| Shared storage links | `<app files>/home/storage/*` → `/storage/emulated/0/...`, one per folder switched on |

Everything is under the app's private data directory, and uninstalling the app
deletes all of it. Nothing is written outside it except through the folders
switched on under **Settings → Shared storage** — see
[§5](05-storage-and-safety.md), which is also where the API key's exposure is
described honestly rather than as a claim.

---
