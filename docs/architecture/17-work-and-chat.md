# 17. Work and chat: two launch modes

*[Architecture](../ARCHITECTURE.md) §17.*

PiKit has two agents, and the choice is a launch mode rather than a skin over one
process. **Work mode** is the agent this app has always had: pi's coding-assistant
prompt, `AGENTS.md`, the bundled web-access extension, and the seven built-in tools.
**Chat mode** is a plain conversation: before its first message the only instruction text
is one editable system-prompt document, and pi starts with every source of ambient
resources switched off.

The mode belongs to a **conversation**, not to the app. `SessionModeStore` keeps one
entry per session file; a conversation opened from the drawer reads its own record,
and a conversation with no file yet takes `PiSettings.agentMode` — the mode the last
visible conversation had — as its starting point. The fallback for a path with no
record is **Work**, which is what every conversation written before chat mode existed
is. A global switch could not answer "which mode is this conversation" when a work
conversation is opened from a chat screen, and that is exactly the bug that moved the
state into the store: the toggle showed chat while the work transcript was on screen.

## The switch is at the drawer's foot

The chat page's bar used to hold the two-segment **Work / Chat** control. It now holds
only the drawer disc and the trailing action pill. The switch shares the drawer's
foot with the settings gear — mode on the leading side where a profile strip used to
stand, the gear on the trailing side as a disc with no word — because both are
choices about the whole conversation rather than about the page behind it. The switch
is a `selectableGroup` of two `Role.Tab` segments with a thumb that travels between
them: a tap animates the thumb, and a horizontal drag moves it under the finger and
settles on the nearer side.

Switching does not open a second conversation. It records the mode for the session
file and, when the process is live, restarts it in place; `restoreTarget` still names
the same file, so the fresh child rebinds to it and the transcript and model history
are unchanged. A conversation opened from disk but not yet started only records the
mode — its first prompt launches with it. Switching an empty conversation therefore
changes nothing about its history; switching one with messages keeps every message.
While an answer is streaming the switch asks first, because the relaunch ends the
turn, the same question the workspace chip asks.

A mode that flips is one of the app's five haptic moments, so the switch calls
`PiHaptics.toggle` rather than `tap` (ARCHITECTURE §16).

## What chat mode removes

The mode is enforced in `PiProcessLauncher.buildCommand`, not by hiding controls. The
agent is started with:

| Flag | What it closes |
| --- | --- |
| `--no-tools` | every built-in, extension, custom and MCP tool |
| `--no-extensions` | discovered, configured and built-in extensions (including the bundled web-access package) |
| `--no-skills` | discovered and configured skills |
| `--no-prompt-templates` | discovered and configured templates |
| `--no-context-files` | `AGENTS.md` and `CLAUDE.md` discovery |
| `--no-mcp` | the built-in MCP support, restated because it is itself an extension |
| `--system-prompt <file>` | pi's coding-assistant preamble |

The flags are a single launch's, not a second settings file: the next work launch is
ordinary again without undoing anything. `--system-prompt` is passed a file rather
than text because the prompt is a document the reader may make long, and argv is
readable through `/proc`; `PiAgentSession.writeChatPrompt` writes the rendered template
under `$HOME/.pikit/chat-system-prompt.txt` before the spawn.

Chat mode's composer follows the same rule at the surface: the workspace chip, the
permission control, the `!` shell row and the attach-a-file row are not drawn. They are
controls for an agent with tools; a chat process has none, so offering them would be a
control that cannot honour its label. Camera and picture attachments stay — they are
part of the message, not a tool the model calls.

## The prompt is a template, never a timestamp

The editor stores the text the reader writes, including `{{time}}`. That token is
replaced at launch with `yyyy-MM-dd HH:mm:ss XXX` in the device's zone. Storing a
rendered timestamp instead was rejected twice over: it would be stale by the next
process, and the reader could not say where in their document the time belongs.

The default is the token alone, so an untouched install injects only the current time.
Saving a blank field normalises back to that default instead of writing an empty
string: pi reads an empty custom prompt as "no custom prompt" and would restore its
coding-assistant preamble, which is exactly what chat mode is replacing. The editor
therefore offers **Restore default**, not **Clear**.

Saving while chat mode is in force calls `scheduleRestart`, not `restartAgent`: pi reads
its prompt once at process start, and an answer that is being written must not be cut
for a settings save. Work mode reads no prompt, so a save there changes nothing until
the reader switches.

## The settings page has two halves

The Agent page is split top and bottom. The work half keeps the page's existing three
groups — workspace, context and instructions, process — under `Work mode · …` titles.
The chat half is one card: a sentence naming what is not loaded, and the system-prompt
row that opens the editor. The split is structural rather than a tab: a reader who
opens the page to change a workspace should not first have to choose which mode they
are editing.

A UI-only version of chat mode — hiding tools in the composer while launching the same
agent — was rejected because the model would still receive the tool catalogue,
`AGENTS.md`, skills and extension context. The model, not the screen, is what the mode
is about.
