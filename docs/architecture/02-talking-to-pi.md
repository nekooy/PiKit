# 2. Talking to Pi: `--mode rpc`, not a pseudo-terminal

*[Architecture](../ARCHITECTURE.md) §2.*

Pi ships four modes; the RPC mode exists for exactly this kind of embedding. It
is a JSONL protocol on stdin/stdout and it is unusually strict. The rules below
are enforced in `JsonlFramer` and covered by unit tests using captured traffic:

- **LF (`\n`) is the only record delimiter.** `U+2028` and `U+2029` are legal
  inside JSON strings and Pi emits them *unescaped*, so `BufferedReader.readLine()`,
  `String.lines()`, `Scanner` and `lineSequence()` all corrupt records. The framer
  scans for `\n` itself.
- **Records straddle reads, and one read can contain several records.** Both cases
  are exercised by tests.
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
  whose `id` is not pending is re-emitted as an ordinary record — that is how Pi's
  id-less parse error reaches the UI.
- **`agent_settled`, not `agent_end`, means idle.** An `agent_end` can be followed
  by an auto-retry, a compaction retry or a queued continuation.
- **`prompt` returning `success: true` does not mean it worked.** It means
  accepted. Later failures arrive as an assistant message with
  `stopReason: "error"`.
- **`success: true` is also how Pi refuses a session switch.** `new_session` and
  `switch_session` answer `data.cancelled: true` when an extension's
  `session_before_switch` handler says no. Both callers used to clear the transcript
  and re-read state regardless, so a refused switch replaced the conversation on
  screen with the messages of a session Pi never opened; `PiRecord.Response.cancelled`
  is the field that had to be read.
- **An abort is not a stop.** "`abort` continues queued messages when they remain in
  the session" (`docs/rpc.md`, `clear_queue`), which is why the reference client's Esc
  is `clear_queue` **then** `abort`. PiKit sent only the `abort`, so a prompt typed
  while the agent was streaming — queued as `steer` by `sendPrompt` — was delivered
  the moment the interrupted run went idle and a new answer appeared: the reader's
  report that the Stop button does not stop. `session.abort()` does cover the
  interrupted run's retry, compaction and bash itself
  (`abortRetry`/`abortCompaction`/`abortBranchSummary`/`agent.abort`, then a wait for
  idle), so `abort_retry` and `abort_bash` have nothing to add. The text
  `clear_queue` returns goes back into the composer when it is empty, because a
  message the reader typed and never saw answered must not vanish.
- **A model can change without the app asking.** A `/model` command typed into the
  composer, a scoped-model cycle or an extension calling `pi.setModel` all emit
  `model_select`, and every model-shaped fact the UI holds came from a `get_state`
  reply that nothing re-asked for — the context meter kept reporting the previous
  model's window and the thinking picker kept offering the previous model's levels.
  The record loop refreshes state on that event; the app's own switch is the same
  event plus its own refresh, which is one small round trip on a rare action rather
  than a correctness question.
- **Nothing is asked for that is not used.** `get_available_models` was asked on every
  handshake purely to classify models for the image declaration, and the reply is every
  model of every configured provider with costs, windows and thinking maps — on a
  phone, at startup, for a test that no longer exists (see §6, "An image declaration is
  an override"). The catalogue *is* refreshed, once per provider and once every six
  hours, but by pi's own `update --models` rather than by a question the app then has
  to interpret (§7, "The model catalogue is pi's").

The last four points, plus the undocumented `session_info_changed`,
`entry_appended` and `thinking_level_changed` events, are places where Pi's prose
documentation and its implementation disagree. The client follows the
implementation.

## The command line

The agent is launched as:

```
node $PREFIX/lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js \
     --mode rpc --approve \
     --session-dir <app files>/pi-sessions ...
```

`--offline` used to be passed here, and is not any more. It sets `PI_OFFLINE=1` and
`PI_SKIP_VERSION_CHECK=1` for the whole process, and pi reads the first of those as
two unrelated things: "skip the update check, the package checks and the install
telemetry", and — in `ModelRuntime`'s constructor — `modelNetworkEnabled =
process.env.PI_OFFLINE === undefined`, i.e. **the model catalogue has no network**.
On a phone that reads as "the model list never moves": a model released that morning
could not be selected, and the report named it. The radio time the flag was there to
save is now spent only where it buys something, and the privacy promise it also
carried is kept by `PI_TELEMETRY=0`, which pi documents for exactly that one thing.
Two app-side changes go with it: the catalogue refresh runs on pi's own four-hour
**freshness window** rather than on every launch (§7), and the update button updates pi,
the installed extensions *and* the catalogue rather than pi alone. The API key is passed
through the environment (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, … depending on the
provider) for every provider Pi already knows, because argv is readable by other
processes.
For a **custom endpoint it has to go on the command line** instead — see §6, which
records the control test that established it.

### Why the built-in tool list is not `--tools`

`--tools` looks like the way to enable `grep` and `find` — the image ships `rg`
and `fd` for exactly those two, and pi enables only `read, bash, edit, write` by
default. It is not: pi applies `--tools` as a **strict allowlist over built-in,
extension and SDK tools alike** (its own `--help`: "Comma-separated allowlist of
tool names to enable … Applies to built-in, extension, and custom tools", and
`agent-session.js` filters `getAllRegisteredTools()` through it). Passing it
therefore removed every tool an installed extension registered: the extension
loaded, `pi.registerTool()` ran, and the tool was dropped before the model could
call it — with no error anywhere, because a tool that is not in the registry
simply does not exist as far as the model is concerned. That was the report
"扩展注册的工具无法在对话页使用", and the terminal tab never had it: a hand-run
`pi` passes no `--tools`, so the same extension worked there.

The list now reaches pi as `defaultTools` in `$HOME/.pi/agent/settings.json`,
which selects the **built-in** tools enabled at startup and leaves extension and
SDK tools enabled. `PiAgentSession.writeToolDefaults()` writes that file before
every launch, merging the single key rather than replacing the document —
unlike `models.json`, this file is pi's own and holds whatever else the user has
set. It has the same lifecycle as the `models.json` writer: written before each
launch, reused when unchanged, and a failure is logged and ignored because the
agent still starts without `grep`, `find` and `ls`.

Two consequences worth knowing. A *project* `$HOME/.pi/settings.json` can replace
the global `defaultTools` (pi documents that a project array replaces the global
one), and the app's default working directory is `$HOME/workspace` — the same trust
surface `--approve` already opens, minus the agent's own configuration, which a
recursive delete of the working directory used to take with it. And the check that
fails if this regresses is `PiProcessLauncherTest`'s "does not restrict tools on the
command line".

### What a `pi` started by hand gets

The agent is given its provider, model and key on its own command line and in its
environment, which is why a `pi` typed into the Terminal tab used to answer "no
model": a hand-run `pi` has neither. That is not a terminal bug, it is the app
keeping pi's own settings empty — so the fix is to keep them, in pi's files:

- `$HOME/.pi/agent/settings.json` gains `defaultProvider`, `defaultModel` and
  `defaultThinkingLevel`, merged (never replacing the document) and only when a
  profile is configured. They are the values the app itself launches pi with, so
  the two cannot disagree; `defaultTools` and the bundled extension's `packages`
  entry are written by the same merge, which is also where the exclusion-list
  reasoning for `models.json` lives (§6).
- The login shell that the Terminal tab spawns gets the active profile's key under
  the same environment variable the agent uses, so the credential is in scope for
  whatever the user runs. It is *not* written to pi's `auth.json`: that would put
  the secret on disk permanently for a file pi owns and rewrites, when it is
  already in the agent's argv by design (§6).
- Only new shells see a changed key. A session that is already open keeps the
  environment it was born with, which is the honest behaviour for a PTY.

## Why not drive the interactive TUI through a PTY?

Because parsing a TUI is strictly worse than a protocol: it is lossy, it breaks on
any layout change, and it cannot express structured tool calls. The PTY exists in
this app for the *user's* terminal tab, not for the agent.

---
