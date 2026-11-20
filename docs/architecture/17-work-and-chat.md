# 17. Work and chat: two launch modes

*[Architecture](../ARCHITECTURE.md) §17.*

PiKit has two agents, and the choice is a launch mode rather than a skin over one
process. **Work mode** is the agent this app has always had: pi's coding-assistant
prompt, `AGENTS.md`, the bundled web extension, and the seven built-in tools.
**Chat mode** is a plain conversation: before its first message the only instruction text
is one editable system-prompt document — or nothing at all when that document is empty —
and pi starts with every source of ambient resources switched off.

The mode belongs to a **conversation**, not to the app. `SessionModeStore` keeps one
entry per session file; a conversation opened from the drawer reads its own record,
and a conversation with no file yet takes `PiSettings.agentMode` — the mode the last
visible conversation had — as its starting point. The fallback for a path with no
record is **Work**, which is what every conversation written before chat mode existed
is. A global switch could not answer "which mode is this conversation" when a work
conversation is opened from a chat screen, and that is exactly the bug that moved the
state into the store: the toggle showed chat while the work transcript was on screen.

## The drawer lists one mode's history

Work and chat are two ways of talking, and one history for both is a list where a month
of coding buries a week of chatting. **The drawer shows the conversations of the mode the
page behind it is in** — not a filter the reader sets, and not a second list:
`SessionsScreen` drops every row whose file's record is the other mode. The record is
`SessionModeStore`'s answer, so a file with no entry — every conversation written before
chat mode existed — is work, and stays in the one list its install has always had. The
search and the count are the lens's too: a search cannot return a conversation the list
refuses to draw, and the caption above the rows counts the rows it is showing.

The lens moves by moving the conversation, which is why it has no control of its own:
tapping the other half of the foot switch opens a conversation on that side (below), and
the list follows it. A mode is a fact about a conversation, so the list cannot be made to
disagree with the transcript — the one state that would put a work transcript under a
chat history. For the same reason nothing creates a conversation in an *unstated* mode:
the header's new-conversation mark passes the mode of the page it is on and the switch
passes the side that was tapped, so a new conversation always lands on the side the
reader is looking at (`PiKitRoot`'s `openNew`).

Two consequences are deliberate. **A pinned conversation is filtered too**: pinning lifts
a row to the top of its own mode's list, not onto a shelf above both. And the row menu's
**Switch to chat mode** now *moves the row out of the list*, because the conversation it
converts has changed sides; the toast names the new mode and the row is gone from the
list that was showing it.

**The chat history is not grouped by workspace.** Work mode's headings name the folder
each run of rows came from, because the workspace is the fact that groups a coding
conversation. Chat mode loads no workspace files, no `AGENTS.md` and no tools, and a
conversation converted from work still carries its old project's `cwd` in the session
file; drawing that folder over a chat list would put a workspace back into the mode
whose promise is that it has none. The chat list is therefore one section under the
pinned shelf, and its heading is the mode's own name — `聊天 · 3`, not `project · 3`.

## The switch starts a conversation; the row menu converts one

The chat page's bar used to hold the two-segment **Work / Chat** control. It now holds
the drawer disc, the current mode's name beside it, and the trailing action pill. The
name stands where the app's own name used to and follows the conversation: a work
conversation says Work, a chat one says Chat, so the page answers "which mode is this"
without opening the drawer. The switch itself shares the drawer's foot with the settings
gear — mode on the leading side where a profile strip used to stand, the gear on the
trailing side as a disc with no word — because both are choices about the whole
conversation rather than about the page behind it. The switch is a `selectableGroup` of
two `Role.Tab` segments with a thumb that travels between them: a tap animates the thumb,
and a horizontal drag moves it under the finger and settles on the nearer side.

**Tapping the half the conversation is not in creates a conversation, and that is the
whole of what the switch does.** It used to record the mode for the session file and
relaunch the process in place. That was correct about the mode and wrong about the
subject: the control sits over a live transcript, reads like a filter, and one stray tap
converted a history entry — its launch flags, its cache key, its tool loadout — while
the reader was doing something else. A conversation's mode is a fact about that
conversation, so it is changed where the conversation is named: hold the row in the
history list, and the row's menu offers **Switch to chat mode** or **Switch to work
mode**, whichever the row is not. The row's mark is Miuix's `Replace` — two arrows
swapping — and it does not change with the direction, because the action is one
*conversion* either way and the label already names the target; it used to draw
`Messages` in both directions, which is the mark of the mode being left and left the
reader looking at a chat bubble beside "switch to work mode". A tap there either
converts the conversation on screen (through `switchMode`, which restarts it in place
and asks first while an answer is streaming) or records the mode for a saved one — and
closes that conversation's idle live process so its next open starts in the new mode.
A saved conversation that is
mid-answer is refused with the sentence a delete is, because a launch flag cannot change
under a live turn.

The foot switch's new conversation is created with the mode already set: a fresh
`PiAgentSession` has no file for the per-session record to answer from, and `startAgent`
reads the mode once at the spawn, so `openNew` applies the mode before launching rather
than converting afterwards. The mode of the conversation that was on screen, and its
process, are untouched — which is what makes the control safe to tap. The history list
behind the switch follows the *new* conversation's mode, so the same tap also changes
which side's history the drawer is showing.

`restoreTarget` still names the same file after a conversion, so the fresh child rebinds
to it and the transcript and model history are unchanged. Switching an empty conversation
therefore changes nothing about its history; switching one with messages keeps every
message.

The relaunch a conversion causes also has to repair pi's recorded tool loadout. pi stores
the active tools in a session's system messages, and a conversation that began in chat
mode records none; restoring that file in work mode otherwise keeps the empty set while
extension tools reappear as newly registered ones. Measured on the emulator: after
chat → work the model's `bash` call returned `Tool bash not found` while `web_search` was
still declared. A work launch therefore passes `--tools +read,+bash,+edit,+write,+grep,
+find,+ls`: a list of only `+name` entries is applied to the restored loadout, whereas
plain names are an allowlist that would hide every extension tool. A fresh work
session gets the same seven from `defaultTools`, so this changes nothing there.

A mode that flips is one of the app's six haptic moments, so both the switch and the
row's conversion call `PiHaptics.toggle` rather than `tap` (ARCHITECTURE §16).

## What chat mode removes

The mode is enforced in `PiProcessLauncher.buildCommand`, not by hiding controls. The
agent is started with:

| Flag | What it closes |
| --- | --- |
| `--no-tools` | every built-in, extension, custom and MCP tool |
| `--no-extensions` | discovered, configured and built-in extensions (including the bundled web package) |
| `--no-skills` | discovered and configured skills |
| `--no-prompt-templates` | discovered and configured templates |
| `--no-context-files` | `AGENTS.md` and `CLAUDE.md` discovery |
| `--no-mcp` | the built-in MCP support, restated because it is itself an extension |
| `--system-prompt <file>` | pi's coding-assistant preamble |

The flags leave exactly one extension loadable: `--extension
share/pikit/pikit-chat-prompt.ts`. pi honours an explicit `--extension` even beside
`--no-extensions` (`loadCurrentExtensionSet` uses the CLI paths when discovery is
off), and that file is what makes the prompt exact; see the section below. It is the
one door chat mode keeps open because the alternative is a prompt pi has decorated.

The flags are a single launch's, not a second settings file: the next work launch is
ordinary again without undoing anything. `--system-prompt` is passed a file rather
than text because the prompt is a document the reader may make long, and argv is
readable through `/proc`; `PiAgentSession.writeChatPrompt` writes the rendered template
under `$HOME/.pikit/chat-system-prompt.txt` before the spawn.

Chat mode's composer follows the same rule at the surface: the workspace chip, the
permission control, the `!` shell row and the attach-a-file row are not drawn. They are
controls for an agent with tools; a chat process has none — or, since the network-tools
switch exists, only the two that reach the web — so offering them would be a control
that cannot honour its label. Camera and picture attachments stay — they are part of
the message, not a tool the model calls. Two controls take the empty slots: the row's
first chip is the conversation's **preset**, and the permission control's place in the
action row is the conversation's **network-tools switch**. Both are per-conversation
facts, and both stand in slots whose neighbours already answer "what is this message
sent with".

## The prompt is exact, including empty

`--system-prompt` is not enough on its own, and both failure modes were measured
against the bundled pi 1.5.0 through a local OpenAI-compatible capture:

- pi appends a `<cwd>` block to every custom prompt. A one-line document arrived at
  the provider as that line, a blank line, and
  `<cwd>/data/user/0/pi.kit.mob/files/home/workspace</cwd>` in the same `developer`
  message. It is true of a coding agent and it is context a chat reader did not write.
- An empty `--system-prompt` file is read as "no custom prompt", and pi restores its
  whole coding-assistant preamble — the exact thing chat mode exists to replace.

The mode therefore ships `share/pikit/pikit-chat-prompt.ts`, loaded by that explicit
`--extension`. Its `before_agent_start` returns
`{ systemPrompt: <the file's text> }`, which replaces the complete prompt for the
turn. Measured with the same capture: the one-line document then arrived alone, and an
empty document produced a request with no system or developer message at all. The
extension reads `PIKIT_CHAT_PROMPT_FILE`, set only by a chat launch, so work mode
never activates it. `writeChatPrompt` writes a single space for an empty document —
pi reads a truly empty `--system-prompt` as "no custom prompt", and the space keeps
the fallback on a blank prompt rather than on the coding preamble if the extension
ever fails to load; the extension trims that space back to an empty system prompt.

Patching pi's bundle to remove the `<cwd>` append was rejected: the bundle is a
pinned upstream artifact, the patch would have to be re-derived on every pi bump, and
an extension hook is pi's supported way to replace a prompt.

## The prompt is a template, never a timestamp

The editor stores the text the reader writes, including `{{time}}`. That token is
replaced at launch with `yyyy-MM-dd HH:mm:ss XXX` in the device's zone. Storing a
rendered timestamp instead was rejected twice over: it would be stale by the next
process, and the reader could not say where in their document the time belongs.

The default is the token alone, so an untouched install injects only the current
time. Empty is a real answer too: saving a blank field keeps it blank, and the
extension sends no system prompt at all. The editor therefore offers **Restore
default**, which restores the time token rather than an unremovable example.

Saving while chat mode is in force calls `scheduleRestart`, not `restartAgent`: pi reads
its prompt once at process start, and an answer that is being written must not be cut
for a settings save. Work mode reads no prompt, so a save there changes nothing until
the reader switches.

## Presets: the prompt with a name

One document was one conversation's voice, and a reader who talks to a translator in the
morning and a tutor in the evening was retyping the same paragraph on the settings page
between them. A **preset** is that document with a name, an identity and a place in the
composer: `ChatPreset(id, name, prompt, avatarColor, avatarImage)`, stored in one
SharedPreferences document (`pikit_chat_presets`) rather than as `PiSettings` fields — a
preset is a list with an object in it, and the profile store is the precedent for a list
that is its own document.

**The selection belongs to the conversation.** `ChatPresetStore` keeps one entry per
session file, beside the launch mode and the network-tools answer; a conversation with no
file yet takes the *default* preset and records its own answer when pi writes the file.
A global preset would answer "which voice is this transcript" with the last choice made
anywhere, which is the same bug the mode store was created to end. Switching the chip
records the selection and calls `scheduleRestart` — pi reads its prompt once, at
process start — so an answer being written is not cut for a persona change.

**Not set is a real state, and it inherits.** A preset's name, picture and colour may
each be blank, and the personalization page answers that part: the default preset is
created with a blank name and no picture or colour, so an install that never touches the
feature signs its answers exactly as it did before. The transcript draws the preset's
identity in chat mode (the name beside the answer, the disc in its colour), which is what
makes a translator preset visibly itself rather than the app's mark repeated. "Restore
the personalization avatar" is a switch on the editor because a swatch row has no empty
choice; the name and the picture say "unset" by being empty.

The first preset is migrated from `PiSettings.chatSystemPrompt`, the single document the
mode shipped before: an update must not silently replace the reader's prompt with the
bare time token. The field is kept as the migration source and is no longer editable.

The composer's **first chip** is the preset — its disc and its name — and its menu is the
list, the one in force ticked, at the chip the way every other composer menu is. It leads
the row because the persona is what the model and the thinking level are about: a
translation prompt and a reasoning prompt do not want the same model.

A *global* preset, a preset per workspace, and a preset as a package in `$HOME/.pi` were
all rejected. The first cannot answer two open conversations; the second contradicts
"the workspace is a directory the agent runs in", not a persona; the third is a file the
app rewrites on every launch, so a reader's edit would fight the writer exactly as
`AGENTS.md` did.

## The network tools, and exactly two of them

Chat mode still opens no door of its own. The one door the reader can open is the
conversation's **network-tools switch**, in the action row where the permission control
stands in work mode — the tier judges tool calls, and a chat conversation's tools are the
two that reach the web, so the same slot answers the neighbouring question. It is off by
default, per conversation, and its tint carries the state (the row's controls are one
40dp dress; a second shape for "off" would be a second glyph to keep in step). Off is the
clean context the mode promises, and the tools are declared in the request rather than
hidden on screen: a default of on would put `web_search` in every chat request whether or
not the reply had anything to look up, which is the always-on design this chapter
rejects. Turning it on is one tap and is recorded for that conversation alone; a
conversation with no record — including every one written before the default moved —
therefore starts off.

On, the launch passes a **strict allowlist** of two names —
`--tools web_search,web_fetch` — and loads the bundled `pikit-web` extension by an
explicit `--extension` beside `--no-extensions`. Off, it passes `--no-tools`, the mode's
original promise. The extension registers exactly those two tools; the allowlist is
restated because chat mode cannot inherit discovery, and it is what makes "only search and
fetch" true rather than aspirational.

The whole thing is measured, not inferred. `tools/test-chat-context.mjs` runs the
vendored pi against a local OpenAI-compatible endpoint that records the request, with a
preset marker in place of the prompt and an `AGENTS.md` in the working directory:

- switch off: **0 tools**, and the only system message is the preset's text byte for
  byte — no `<cwd>`, no `AGENTS.md`, no pi preamble;
- switch on: **exactly `web_search` and `web_fetch`**, and the same system text.

`build-apks.py` runs that audit beside the prompt-extension test, skipping cleanly when
the build cache has no pi bundle or web extension. A settings-file way to disable the other
tools was rejected: `web.json` configures the extension, and the switch is per
conversation. Always-on was rejected because the clean context is the mode's promise; a
UI-only switch was rejected for the reason the mode itself was: the tools are declared in
the request, and a switch that changed no request would be a picture of a decision.

## The settings page has two halves

The Agent page is split top and bottom. The work half keeps the page's existing three
groups — workspace, context and instructions, process — under `Work mode · …` titles.
The chat half is the preset list: a sentence naming what is not loaded, the default-preset
row (a picker of the list), one row per preset — its disc, its name and the prompt's first
line, with a tick on the default — and an add row. A row opens the editor: the avatar with
the inherit switch, the picture and the name, the prompt with a Save, and a delete that
asks first and is refused for the last preset. The list's Save is explicit and the
identity saves as it is edited, because a launch reads the prompt once and every keystroke
would otherwise schedule a relaunch. The split is structural rather than a tab: a reader
who opens the page to change a workspace should not first have to choose which mode they
are editing.

A UI-only version of chat mode — hiding tools in the composer while launching the same
agent — was rejected because the model would still receive the tool catalogue,
`AGENTS.md`, skills and extension context. The model, not the screen, is what the mode
is about.
