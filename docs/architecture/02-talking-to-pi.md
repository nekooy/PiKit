# 2. Talking to Pi: `--mode rpc`, not a pseudo-terminal

*[Architecture](../ARCHITECTURE.md) §2.*

Pi ships four modes, and the RPC mode exists for exactly this kind of embedding: a
JSONL protocol on stdin/stdout, enforced in `JsonlFramer` and covered by unit tests
using captured traffic.

- **LF (`\n`) is the only record delimiter.** `U+2028` and `U+2029` are legal
  inside JSON strings and Pi emits them *unescaped*, so `BufferedReader.readLine()`,
  `String.lines()`, `Scanner` and `lineSequence()` all corrupt records. The framer
  scans for `\n` itself.
- **Records straddle reads, and one read can contain several records.**
- **Multi-byte UTF-8 straddles reads too,** so decoding is incremental
  (`CharsetDecoder` with carry-over bytes), never `String(bytes)`.
- **stdout is protocol only.** Pi rebinds `process.stdout.write` to stderr at
  startup, so every diagnostic arrives on **stderr**, which is drained on its own
  thread into a bounded buffer.
- **There is no handshake and no shutdown command.** `get_state` is used as a
  readiness probe; closing stdin is the only graceful shutdown (Pi then flushes
  stdout and exits 0). `SIGTERM` skips that flush, so it is a fallback only.
- **A bare `null` line crashes Pi.** A command object is built with
  `kotlinx.serialization` rather than string concatenation, so a non-object record
  is unrepresentable.
- **Responses are correlated by `id`, never by position.** Pi answers `prompt`
  immediately and keeps working, so replies interleave with events. A response
  whose `id` is not pending is re-emitted as an ordinary record.
- **`agent_settled`, not `agent_end`, means idle.** An `agent_end` can be followed
  by an auto-retry, a compaction retry or a queued continuation.
- **`prompt` returning `success: true` does not mean it worked.** It means
  accepted. Later failures arrive as an assistant message with
  `stopReason: "error"`.
- **`success: true` is also how Pi refuses a session switch.** `new_session` and
  `switch_session` answer `data.cancelled: true` when an extension's
  `session_before_switch` handler says no, and a caller that ignores the field
  replaces the conversation with a session Pi never opened;
  `PiRecord.Response.cancelled` is what has to be read.
- **An abort is not a stop.** "`abort` continues queued messages when they remain in
  the session" (`docs/rpc.md`, `clear_queue`), which is why the reference client's Esc is
  `clear_queue` **then** `abort`. PiKit sent only the `abort`, so a prompt queued as
  `steer` by `sendPrompt` was delivered once the run went idle, which is a Stop button that
  does not stop. `session.abort()` already covers that run's retry, compaction and
  bash (`abortRetry`/`abortCompaction`/`abortBranchSummary`/`agent.abort`), so
  `abort_retry` and `abort_bash` add nothing; the text `clear_queue` returns goes back
  into the composer when it is empty.
- **A model can change without the app asking.** A `/model` command typed into the
  composer, a scoped-model cycle or an extension calling `pi.setModel` all emit
  `model_select`, and the model-shaped facts the UI held came from a `get_state` reply
  nothing re-asked for — so the context meter and the thinking picker kept reporting the
  previous model. The record loop refreshes state on that event; the app's own switch is
  the same event plus its own refresh.
- **Nothing is asked for that is not used.** `get_available_models` was asked on every
  handshake for a test that no longer exists — every model of every configured provider
  with costs, windows and thinking maps, on a phone, at startup. §6.2, "The override half,
  which is now the half for models pi knows", has the rest; the catalogue is refreshed
  instead by pi's own `update --models`, for every built-in provider, on a four-hour
  freshness window (§7.2, "The model catalogue is pi's, and stale is the default").

The last four points, plus the undocumented `session_info_changed`,
`entry_appended` and `thinking_level_changed` events, are places where Pi's prose
documentation and its implementation disagree. The client follows the implementation.

## The command line

The agent is launched as:

```
node $PREFIX/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js \
     --mode rpc --approve \
     --session-dir <app files>/pi-sessions ...
```

`--offline` used to be passed here, and is not any more. It sets `PI_OFFLINE=1` and
`PI_SKIP_VERSION_CHECK=1` for the whole process, and pi reads the first as **the model
catalogue has no network** (`ModelRuntime`'s constructor: `modelNetworkEnabled =
process.env.PI_OFFLINE === undefined`) as well as "skip the update check, the package
checks and the install telemetry" — on a phone, the model list never moves. The privacy
promise the flag also carried is kept by `PI_TELEMETRY=0`. Two app-side changes go with
it: the catalogue refresh runs on pi's own four-hour **freshness window** (§7.2), with a
button on the maintenance page to force it, and **pi itself is never updated on the
device** — it is an input of the runtime image, so a new pi arrives with a new PiKit.
The button that used to run pi's own `npm install -g` in the live prefix was removed
after an interrupted run left a tree with no `jiti` in it, which 0.86.x loads the bundled
TypeScript guard extension through: every agent start then failed with
`Failed to load extension "…/pi-safety-guard.ts": Cannot find module 'jiti'`. The API key is
passed through the environment (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, …) for every
provider Pi already knows, because argv is readable by other processes; for a **custom
endpoint it has to go on the command line** instead — see §6.1, which records the control
test that established it.

### Why the built-in tool list is not `--tools`

`--tools` looks like the way to enable `grep` and `find` — the image ships `rg` and
`fd` for exactly those two, and pi enables only `read, bash, edit, write` by default —
but pi applies it as a **strict allowlist over built-in, extension and SDK tools
alike** (`agent-session.js` filters `getAllRegisteredTools()` through it). Passing it
dropped every tool an installed extension registered, with no error anywhere, so an
extension's own tools could not be used in a conversation.

The list now reaches pi as `defaultTools` in `$HOME/.pi/agent/settings.json`, which
selects the **built-in** tools enabled at startup and leaves extension and SDK tools
enabled. `PiAgentSession.writePiDefaults()` writes that file before every launch,
merging the single key rather than replacing the document — unlike `models.json`,
this file is pi's own and holds whatever else the user has set. It has the same
lifecycle as the `models.json` writer: reused when unchanged, and a failure is logged
and ignored because the agent still starts without `grep`, `find` and `ls`.

A *project* `$HOME/.pi/settings.json` can replace the global `defaultTools`, and the app's
default working directory is `$HOME/workspace` — the same trust surface `--approve`
already opens. The check that fails if this regresses is `PiProcessLauncherTest`'s "never
passes --tools".

### What a `pi` started by hand gets

A `pi` typed into the Terminal tab used to answer "no model": the app gives its agent
provider, model and key on its own command line and in its environment and leaves pi's
own settings empty. That is not a terminal bug — the fix is to keep them, in pi's files:

- `$HOME/.pi/agent/settings.json` gains `defaultProvider`, `defaultModel` and
  `defaultThinkingLevel`, merged (never replacing the document) and only when a
  profile is configured. They are the values the app itself launches pi with, so the
  two cannot disagree; `defaultTools` and the bundled extension's `packages` entry are
  written by the same merge, which is also where the exclusion-list reasoning for
  `models.json` lives (§6.1).
- The login shell that the Terminal tab spawns gets the active profile's key under the
  same environment variable the agent uses, so the credential is in scope for whatever
  the user runs. It is *not* written to pi's `auth.json`: that would put the secret on
  disk permanently, when it is already in the agent's argv by design (§6.1).
- Only new shells see a changed key. A session that is already open keeps the
  environment it was born with, which is the honest behaviour for a PTY.

## Why not drive the interactive TUI through a PTY?

Because parsing a TUI is strictly worse than a protocol: it is lossy, it breaks on
any layout change, and it cannot express structured tool calls. The PTY exists in
this app for the *user's* terminal tab, not for the agent.

---
