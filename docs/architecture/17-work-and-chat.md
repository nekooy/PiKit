# 17. Work and chat: two launch modes

*[Architecture](../ARCHITECTURE.md) §17.*

PiKit has two agents, and the choice is a launch mode rather than a skin over one
process. **Work mode** is the agent this app has always had: pi's coding-assistant
prompt, `AGENTS.md`, the bundled web-access extension, and the seven built-in tools.
**Chat mode** is a plain conversation: before its first message the only instruction text
is one editable system-prompt document, and pi starts with every source of ambient
resources switched off.

The mode is a preference (`PiSettings.agentMode`), not a property of a transcript. A
process reads it only when it is spawned, so changing it affects the next launch; the
UI handles that by restarting an empty conversation in place and opening a fresh one
when the current conversation already has messages. The old transcript is never
rewritten into another mode.

## The switch is in the title slot

The chat page's bar used to print the app's name. The name is now the two-segment
**Work / Chat** control, in the same glass capsule as the drawer disc and the trailing
action pill. Both answers are visible because the question is binary and a reader
should be able to see which agent they are talking to without opening a menu.

The control is a `selectableGroup` of two `Role.Tab` segments. Selecting a mode calls
`PiSettings.update`, then:

- an empty conversation is restarted in place — there is nothing to preserve;
- a conversation with messages keeps its process and transcript, and a new empty
  conversation is opened beside it in the new mode. A clean context is the point of
  chat mode, so inheriting a work conversation's tool history would defeat it;
- a full conversation pool falls back to restarting the current one. That is the one
  case where the click cannot make the ideal switch, and it is better than silently
  leaving the old process in force.

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
