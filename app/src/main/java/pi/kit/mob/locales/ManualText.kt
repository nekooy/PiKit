package pi.kit.mob.locales

/**
 * The in-app manual, localised.
 *
 * Separate from [Strings] because it is prose rather than labels: keeping it out
 * of the interface catalog means the three catalogs stay readable, and the
 * manual is the one place where a translation is worth reading end to end.
 *
 * Kept in step with the app by hand. Anything it claims has to be true of the
 * build it ships in, every menu path and label in it has to exist in the UI, and
 * the three languages have to stay structurally parallel — the terminal is about
 * 48 columns wide, and the manual is read on the same screen. Parallel means the
 * same headings, in the same order, with the same table rows and the same number
 * of bullets; only the words differ.
 *
 * ## Structure
 *
 * Nine numbered sections, in the order a user needs them: what to set up, the
 * two ways to drive the agent (a prompt and a shell), the chat page, the
 * terminal, the files page, what the agent can reach, web search, a table of the
 * settings rows, and troubleshooting. Every section is a list, a table or a few
 * short paragraphs — a wall of prose is a screen the reader has to scroll past
 * without learning anything from its shape.
 *
 * Brevity is a requirement here, not a preference: the manual is read on the
 * phone it documents, so a paragraph that could be a sentence is a screen the
 * user has to scroll past. Anything that only justifies a design belongs in
 * `docs/ARCHITECTURE.md` instead.
 *
 * The bodies below are written as ordinary paragraphs; `tools/reflow-manual.py`
 * wraps them to 48 display columns without touching headings, lists or tables.
 * Run it once after the whole body is written, and do not hand-wrap: a line
 * longer than the screen wraps a second time and turns into a mess, and a second
 * run inserts a space at every line break it rejoins — which shows up as a gap
 * inside a Chinese or Japanese sentence.
 */
fun manualFor(lang: Lang): String = when (lang) {
    Lang.ENGLISH -> ENGLISH_MANUAL
    Lang.CHINESE -> CHINESE_MANUAL
    Lang.JAPANESE -> JAPANESE_MANUAL
}

internal val ENGLISH_MANUAL = """
# PiKit

PiKit runs the Pi coding agent on your phone.
Its Linux environment, Node.js, `ripgrep`, `fd`
and `pi` are unpacked from the APK on first
launch. No Termux and no root.

Four tabs: **Chat**, **Terminal**, **Files**,
**Settings**.

## 1. Getting started

Set a provider and a model first: **Settings →
Model & provider → Add a profile**.

| Step | What to do |
| --- | --- |
| Name | any label |
| Provider | pick from the list |
| API key | paste it from the provider's dashboard |
| Models | press **Fetch models** and tap one, or type an id; tap a row to make it the answering model |
| Save | the profile goes live and the agent restarts |

The first launch unpacks about 200 MB and asks
once about file access. **Declining is fine**;
**Settings → Shared storage** brings the offer
back.

### A relay or a self-hosted endpoint

Choose **Custom endpoint**, then give the base
URL with its version segment (e.g.
`https://relay.example.com/v1`) and a model id
it serves. Calls use the OpenAI-compatible API
at `/chat/completions`; Anthropic- or
Google-only endpoints are not supported yet.

The key also goes on the command line: pi does
not expand `${'$'}VARIABLE` for a provider it
does not know, and anything reading this app's
process list can see it.

### Thinking level

At the top of **Settings → Model & provider**.
The picker offers the levels the chosen model
has, because pi moves a level the model lacks up
to the next one it does. It applies at once,
with no restart.

The names are pi's own — `off`, `minimal`,
`low`, `medium`, `high`, `xhigh`, `max` — so the
chip here and `pi --list-models` in the terminal
use the same word for the same level.

## 2. Commands

A chat message starting with `!` runs in the
shell instead of going to the model, and its
output joins the transcript. Everything else is
a prompt. In the **Terminal** tab:

| Command | For |
| --- | --- |
| `pkg install <name>` | install a package |
| `pkg search <query>` | find one |
| `pkg upgrade` | upgrade everything |
| `termux-change-repo` | switch mirrors |
| `termux-setup-storage` | link `~/storage` |
| `pikit-storage-check` | storage check |
| `pi` | pi's interface |

## 3. Chat

Type into the box at the bottom and press the
round arrow. While the model answers it becomes
a red stop button. The header holds the
conversation's name, the agent's state and three
icons: history, new conversation, compress.

The chips above the input are everything you
change while talking:

| Chip | For |
| --- | --- |
| Thinking level | How hard the model reasons. Immediate. |
| Model | Which model answers. Immediate. |
| Context | Window use. Tap for the numbers. |
| Cache | Share served by the prompt cache. |

The `/` button lists the agent's commands,
PiKit's own `/new`, `/compact` and `/stop`
included; a command from pi's list is typed into
the box, so you can add arguments first. The `+`
button attaches an image or a file: images over
4 MB are refused, and the model has to accept
images. Whether it does is under **Settings →
Model & provider → Model parameters** — the
catalogue's own answer, which you can override.

Tapping **Context** opens the session's details:
model and provider, window use, token counts,
cache hit rate, cost, turns and the file it is
written to. Near 100% the agent has to drop
earlier parts of the conversation — that is what
compress is for.

The transcript:

- **Folded turns** — a finished turn collapses
  to one line under your message (`Worked 47s ·
  5 steps`); tap it to open the turn. The final
  answer is never hidden.
- **Reasoning** — one line too (`Thinking ·
  1.2k chars`), and it stays folded until you
  tap it.
- **Tool calls** — one line each, marked ✓ or
  ✕ as it finishes: tap to read the output, and
  copy it from there.

Long-press text to copy it. Answers are
Markdown, and code blocks scroll sideways. The
ticks down the right edge are the turns: tap one
to jump to that turn.

## 4. Terminal

The Terminal tab is a real shell in the bundled
environment. Several sessions run at once and
keep running when you switch tabs.

The key bar holds the keys a phone keyboard
cannot produce: `ESC`, `TAB`, `C-C`, `C-D`,
`HOME`, `END`, `PGUP`, `PGDN` and symbols.
`CTRL` and `ALT` are toggles. `Scroll` stops the
view so you can read what scrolled past; the
line-break key breaks the line being written
instead of running it.

Packages from `pkg` or `apt` are relocated as
they are installed, so nothing needs doing. If
something installed another way still refuses to
start, press the button once: **Settings →
Maintenance & repair → Installed packages →
Check and repair**.

A `pi` you start here answers with the same
model: PiKit writes your provider, model and key
into pi's own settings, so the terminal and the
chat page agree.

## 5. Files

The Files tab browses the agent's home
directory, read-only: it exists so you can see
what the agent changed without a second app. Tap
a file to preview it — text up to 64 KB, binary
as binary. **Open with** hands it to another
app.

## 6. What the agent can see

By itself the agent sees only its own directory;
your photos and documents do not exist as far as
it is concerned. Reaching them takes the Android
**All files access** permission and a folder
switched on in **Settings → Shared storage**.

That permission is all or nothing on Android, so
the folder switches are the real limit on a
task: a command naming a folder that is off is
refused before it runs. Only the folders you
switch on are linked into the agent's home under
the lower-case names `termux-setup-storage` has
always used, so `/sdcard/DCIM` is
`~/storage/dcim` inside the environment.

The limit binds the agent, not the terminal:
`/sdcard` is the phone's real storage, and a
shell in the Terminal tab reaches it directly
whenever the app holds all files access, whether
or not a folder is granted.

Each folder asks for confirmation first. **All
of shared storage** covers the rest, **Add a
folder** grants another one at a time (✕ takes
it back), and **Remove all access** unlinks
every folder and deletes nothing.

Both guards are worth trusting, and both are
checkable: the agent is refused recursive
deletes outside its workspace (`~/workspace`),
and every recursive delete inside PiKit refuses
paths outside the app's private data. **Settings
→ Maintenance & repair → Check storage** runs
both checks for real, in a separate process, one
line per check.

## 7. Web search

The agent can search the web out of the box:
**Settings → Search settings** needs no changes
at all. With no key configured it searches
through Exa's free endpoint, which needs no
account and may be rate-limited when it is busy.

The switches on the page cover six options: the
web-access master switch, what a search returns,
which provider is asked first, how much page
text reaches the model, the fetch timeout, and a
proxy. Everything else the extension reads is
one row below them: **Add an option** opens the
extension's own list, every entry with the
sentence that says what it does, and what you
type is written to the file in the shape that
option expects. A provider's API key and a
self-hosted SearXNG address are added there.
What you have added is listed with its value,
and the ✕ beside it removes it.

The list is the file. Nothing is written as a
comment, because the extension parses the
configuration as plain JSON and would refuse it;
an option you have not added is simply not in
the file, so the extension's own default
applies. **Restore defaults** puts the file back
to what a fresh install has, and the text editor
appears instead of the list if the file is ever
one this app cannot read — that is the way out
of it.

## 8. Settings

| Row | What it holds |
| --- | --- |
| Model & provider | Provider, key, models, thinking level |
| Search settings | Web search: how, where, limits, keys |
| Language | English, 简体中文 or 日本語; immediate |
| User manual | This page |
| Shared storage | Which folders the agent may reach |
| Agent process | Start, stop, restart, working directory |
| Maintenance & repair | Update pi, relocation, storage check |
| About PiKit | Version, package id, runtime paths, licence, app update check |

## 9. Troubleshooting

**The agent will not start.** Check the
profile's key and model; **Settings → Agent
process** shows pi's own error.

**A model returns "not found" or
"unauthorised".** The key cannot use that model.
Fetch the list again and pick one the provider
reports for this key; with a custom endpoint,
check the URL too.

**A package does not run** — `command not
found`, or a library error. See **Check and
repair** in section 4; if it still fails, the
package is broken for Android.

**Where is my data?** Under the app's private
files directory, in `home`, `usr` and
`pi-sessions`; uninstalling deletes all of it.

**Did the agent use my disk?** Yes, possibly a
lot: it downloads packages and files into the
environment as it works, which is normal. `du
-sh ~` shows what is there, and `pkg clean`
removes downloaded archives.
""".trimIndent()
