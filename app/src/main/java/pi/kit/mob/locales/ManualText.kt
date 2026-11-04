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
 * Twelve numbered chapters, named after what the reader is trying to do rather
 * than after the feature that does it: the first launch, setting up a model, the
 * composer, more than one conversation at once, workspaces and the permission
 * tiers, the files page, the terminal, web search, the settings rows in one
 * table, the agent process, backup and restore, and what to do when something
 * fails. A chapter opens with the one sentence that answers it and then uses the
 * shape the answer wants: a table for a comparison (tiers, commands, settings
 * rows), numbered steps for a procedure, bullet lists led by a **bold** phrase,
 * and a `>` blockquote for a tip or a warning. A `###` inside a chapter is a
 * sub-question of that task, and it exists only where a reader would go looking
 * for one.
 *
 * Every label quoted below is the interface's own, read from `Strings` rather
 * than invented here: a row renamed in the catalog has to be renamed here in the
 * same change, and a menu path that names a row the app does not have is a lie
 * that reads like a typo.
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
launch: no Termux and no root.

The conversation is the whole app. Its top bar
holds a mark at the left that opens the
conversation list, the name **PiKit** beside it,
and the terminal, files and new-conversation
marks on the right; **Settings** sits at the
foot of the drawer.

> **Tip:** the drawer's foot is the only way into
> Settings, and a rightward swipe anywhere on the
> conversation pulls the drawer out.

## 1. First launch

The first launch unpacks about 285 MB from the
APK; nothing is downloaded over the network.

### What it asks

- **Notifications**: the shade entry that
  reports a running answer; Android 13 and later
  ask once.
- **File access**: an explanation first, then
  a page of Android's own; until it is granted
  the agent sees only its own directory.
- **Battery optimisation**: may ask once
  whether to exempt PiKit, so a long answer is
  not cut short with the screen off.
- **Every answer is reversible**: declining is
  fine, and each of the three can be granted
  later in Android's settings or on the row this
  manual names.

### Where the controls are

| Control | How to open it |
| --- | --- |
| Conversations | the mark at the top bar's left, or a rightward swipe |
| Terminal | the `>_` mark at the top bar's right |
| Files | the folder mark beside it |
| Settings | the gear pill at the drawer's foot |

- **The setup screen** draws the unpack's
  progress, and the conversation page appears
  when it is done.
- **The last conversation** reopens on the
  next launch; **Settings → Personalization →
  Open a new conversation on launch** starts
  empty instead.

## 2. Set up a model

A model profile is a provider, an endpoint and a
key. Without one the composer says `No model
configured`.

### Add a profile

1. **Settings → Model & provider → Add a
profile**.
2. Pick a **Provider**.
3. Paste the **API key** from the provider's
dashboard.
4. Press **Fetch models** and tap one, or type
an id under **Models**.
5. **Save**: the profile goes live, and the
agent restarts if the key, endpoint or model
moved.

The **model** chip in the composer switches the
answering model at once, and so does the
**thinking** chip beside it.

### Relays and endpoints

Choose **Custom endpoint** as the provider, then
give the base URL with its version segment, e.g.
`https://relay.example.com/v1`, and a model id
it serves. Calls use the OpenAI-compatible API
at `/chat/completions`; the **API** row picks
the protocol when the relay documents another
one. Anthropic-only and Google-only endpoints do
not work.

> **Warning:** a relay's key also reaches the
> command line, where anything that can read this
> app's process list can see it.

### Thinking level

The menu offers only the levels the chosen model
has, and a level the model lacks moves up to the
nearest one it does. The names are pi's own
(`off`, `minimal`, `low`, `medium`, `high`,
`xhigh`, `max`), so the chip and `pi
--list-models` in the terminal use the same word
for the same level.

### Model parameters

- **Takes image input**: required before a
  picture can be sent; pi calls this model
  text-only otherwise.
- **Context window** and **Max output**: the
  numbers pi uses; leave one empty to change
  nothing.
- **Fetch models**: the list is either
  reported by the provider, so this key can use
  it, or read from pi's catalog and unchecked
  against the key.

## 3. Ask the agent

Type in the field at the bottom and press the
round arrow; while the answer streams it is a
red stop button.

### The composer

- **Above the field**: the workspace, the
  thinking level, the model and the cache hit
  rate; each chip opens its own menu, and the
  four apply to the next message.
- **Below the field's left**: `⌘` for the
  command list, the permission button, and `+`
  for a camera, a picture or a file.
- **Below the field's right**: the context
  ring and the send button; the ring opens the
  same report the cache chip does.
- **The top bar**: always **PiKit**; a
  conversation is named in the drawer, where the
  one on screen is highlighted.

### Commands

| Command | What it does |
| --- | --- |
| `!` | runs the rest of the line in the shell |
| `/new` | opens a conversation beside this one |
| `/compact` | summarises the earlier conversation |
| `/stop` | stops the answer and clears the queue |
| `/clone` | duplicates this conversation |
| `/export` | writes an HTML file into `~/export` |
| `/model` | opens the model picker |
| `/clear` | empties the input box |
| `/stats` | turns, tokens and cost so far |
| `/copy` | copies the last answer |

A message starting with `!` runs in the shell
instead of going to the model, and its output
joins the transcript. `/stats` and `/copy`
answer in a toast rather than in the
conversation, so they add nothing to read later.

### Attachments

- **Camera, picture or file**: the `+` menu,
  camera first.
- **At most four images per message**, and an
  image over 4 MB is refused.
- **A file of any kind** is copied into the
  app and handed to the agent as a path, so it
  opens it with its own tools.
- **Images need a model that takes them**:
  turn on **Takes image input** in the profile.

### The transcript

- **A finished turn folds** to one line under
  your message (`Worked 47s · 5 steps`); tap it
  to open it, and the final answer is never
  folded.
- **Reasoning folds** to one line too
  (`Thinking · 1.2k chars`).
- **Each tool call is one line**, marked ✓ or
  ✕; tap it to read the output.
- **Hold a message** for **Copy message**,
  **Select text** and **Branch from here**; the
  branch is a new conversation holding the
  records up to that message, and it appears
  only once the turn has finished.
- **Jump to the latest**: the arrow at the
  bottom right appears whenever the end of the
  transcript is off screen; scrolling up stops
  the follow, and the button turns it back on.

> **Tip:** answers are Markdown, and a code block
> scrolls sideways.

## 4. More than one conversation

Leaving a conversation does not stop it: an
answer that is being written keeps arriving. A
conversation you closed is a file on disk, and
opening it reads that file; its agent starts
when you send something.

### Limits

| What | Limit |
| --- | --- |
| Answers at once | 5; a sixth is refused with a message |
| Live conversations | 8; opening one more closes the least recently used idle one |
| An idle process | stopped after 90 seconds, except the two most recent; switching back starts it again |

- **The refusal is about work, not open
  conversations**: it needs five answers in
  flight and nothing idle to close, and its
  sentence names the cap.
- **A closed conversation is not lost**: its
  transcript is pi's, on disk, and the drawer
  opens it again in one tap.

### The drawer

The drawer covers about four fifths of the
width, and the conversation stays visible,
dimmed and a little smaller, at its right edge.

- **Search** is always there, reads the titles
  and the message text of every saved
  conversation, and shows the matching line
  under a row while it scans.
- **Groups**: the pinned ones first, then one
  group per workspace, the group holding the
  newest conversation at the top and the one on
  screen filled in.
- **A row carries two facts**: a pin mark, and
  a dot for **Answering now** or **Answered, not
  read**.
- **Hold a row** for **Rename**, **Pin to
  top** (or **Unpin**) and **Delete**.
- **Selection**: the mark at the drawer's
  top-right turns the header into select all,
  delete and cancel.

> **Tip:** a rightward swipe anywhere on the
> conversation pulls the drawer out, and a leftward
> one puts it back.

## 5. Give the agent a folder

A workspace is the folder a conversation runs
in, and the folder the agent may write to.

### Choosing one

- **The first chip in the composer** picks the
  workspace for this conversation; **Add a
  workspace** takes any folder, the phone's
  storage included once file access is granted.
- **The default**: **Settings → Agent process
  → Default workspace** is where a new
  conversation starts, and changing it does not
  move the one on screen.
- **Changing it mid-answer** asks first,
  because pi aborts the running turn when the
  session's directory changes.

### Permission tiers

| Tier | What it allows |
| --- | --- |
| Read only | looks at files; changes none, and runs no command that would |
| Workspace write | reads anywhere, writes inside the workspace |
| Full access | refuses nothing |

- **Checked on every tool call**, so a change
  applies at once without a restart.
- **A refusal is a sentence**: the tool error
  names the tier, so the agent says what it
  meant to change instead of retrying.

### The phone's storage

- **All files access**: the folder picker's
  **Allow file access** row opens the system
  page; until then the agent sees only its own
  directory.
- **Links are not permissions**: the usual
  folders are linked into the agent's home. The
  camera roll is `~/storage/dcim`, and a write
  through one is judged like any other path.
- **The tier binds the agent, not the
  terminal**: `/sdcard` is the phone's real
  storage, and the terminal reads and writes it
  whenever the app holds all files access,
  whatever the tier says.
- **Check storage**: **Settings → Maintenance
  & repair → Check storage** prints one ok or
  FAIL line per check, and a tally.

> **Warning:** any folder can be a workspace,
> including the agent's own home; the write boundary
> is the tier applied to the folder you chose.

## 6. Files

The files page browses the agent's home,
read-only: the folder mark in the top bar opens
it, and the arrow at its top-left returns to the
conversation.

### Getting around

- **The title is the folder you are in**, and
  the line under it is how many entries it
  holds.
- **The path bar** is tappable, a segment goes
  back up, and **Home** returns to the agent's
  home.
- **Back and the up arrow** walk to the
  filesystem's root, not just to the home
  directory.
- **The filter** narrows the folder you are in
  and is cleared when you move; the button
  beside it sorts by name, modified time or
  size.

### Reading a file

- **Tap a file** to preview it: pictures are
  drawn, text up to 64 KB, anything else is
  labelled binary.
- **Hold a row** for **Copy path**; a file
  also offers **Open with**.
- **A file's number is its size**, and a
  folder's is how many entries it holds.
- **This page never writes**: changes go
  through the agent or the terminal.
- **`/export`** writes into `~/export`, a
  folder on this page.

> **Tip:** the page opens at the agent's home, not at
> the phone's storage, because its question is what
> the agent changed.

## 7. Terminal

The terminal is a real shell in the bundled
environment; the `>_` mark in the top bar opens
it, and the arrow at its top-left returns to the
conversation.

### The key bar

- **Scroll** pauses the follow so you can read
  what went past; the key is in the accent
  colour while following and red while paused.
- **The four arrows and `ESC`** are the keys a
  phone keyboard does badly, so they keep their
  own places.
- **Send text** opens a field whose contents
  go in whole, for a long or a multi-line
  command.
- **Two keyboards**: the laptop button raises
  the app's own key panel instead of the IME,
  and the keyboard button raises or lowers the
  phone's keyboard.
- **No clear-screen button**: it is gone; the
  bar's two marks are the new terminal and the
  roster.

### Sessions

- **Several at once**: the `+` opens one, and
  they keep running while you are back in the
  conversation.
- **The roster**: the stack mark lists the
  shells; tap one to switch, the bin closes one,
  and **Close all terminals** is the last row.
- **A shell that ends**: a row over the
  terminal says `exited (code)` and offers a new
  one.

### Commands

| Command | For |
| --- | --- |
| `pkg install <name>` | install a package |
| `pkg search <query>` | find one |
| `pkg upgrade` | upgrade everything |
| `termux-change-repo` | switch mirrors |
| `termux-setup-storage` | link `~/storage` |
| `ls ~/storage` | the phone's storage |
| `pi` | pi's interface |

- **Packages relocate themselves**: anything
  installed with `pkg` or `apt` is rewritten as
  it installs, and nothing needs doing.
- **When one still fails**: **Settings →
  Maintenance & repair → Package relocation →
  Repair**.
- **A `pi` you start here** answers with the
  same model, because PiKit writes your profiles
  into pi's own settings.

> **Tip:** **Rebuild the environment** unpacks the
> runtime again, and packages installed with `pkg`
> are recorded and put back afterwards.

## 8. Web search

The agent can search the web out of the box.
**Settings → Search settings** needs no change;
with no key it searches through Exa's keyless
endpoint, which may be rate-limited when it is
busy.

### The six controls

| Row | What it sets |
| --- | --- |
| Web access | the master switch over searching and page reading |
| Search workflow | raw results, a summary, or a curator page |
| Search provider | which service is asked first; **Automatic** tries Exa, then a keyed one |
| Inline content limit (characters) | how many characters of a page reach the model |
| Fetch timeout (seconds) | how long a page fetch may take |
| Proxy | the proxy the extension's requests carry |

### The configuration file

- **Configuration** lists the keys of
  `web-search.json` that the controls above do
  not write.
- **Add an option or key** picks one and
  stores a value in the shape it expects; the ✕
  removes that key only.
- **A provider's API key and a self-hosted
  SearXNG address** are added there.
- **Restore defaults** puts the file back to
  what a fresh install has.
- **A file that is not a JSON object** turns
  the controls into a text editor, and saving
  valid JSON makes the page editable again.

> **Warning:** **Restore defaults** removes every
> key and option you added, and the page asks first.

## 9. Settings at a glance

The gear pill at the drawer's foot opens the
tab: five rows in **Essentials** and four in
**Advanced**.

| Row | What it holds |
| --- | --- |
| Model & provider | provider, endpoint, key, models |
| Search settings | the web-access switches and its file |
| Personalization | launch, text size, theme, icon, avatars |
| Language | English, 简体中文, 日本語 |
| User manual | this page |
| Agent process | restart, workspace, instructions, keep alive |
| Backup & restore | one archive out, and back in |
| Maintenance & repair | models, packages, rebuild, storage check |
| About PiKit | versions, package id, paths, update check |

- **Theme** offers follow system, light or
  dark, and nothing else colours the app.
- **Text size** has seven steps from 80% to
  130%, and applies when you let go.
- **Language** switches at once, and a fresh
  install starts in 简体中文.
- **App icon and avatars**: two launcher
  icons, and an avatar over each speaker with a
  name, a colour or a picture.

> **Tip:** the version and package id a bug report
> needs are both on **About PiKit**.

## 10. The agent process

This page owns the folder a new conversation
starts in, what the model is told, and the
process that carries both.

### What the model is given

| Part | What it holds |
| --- | --- |
| System prompt | pi's role, its tools and calling rules, and its documentation index |
| Instruction files | the default instructions PiKit writes, then yours, then a project's own |
| Runtime | model, thinking level, session file, workspace and permission tier |
| Tools | read, bash, edit, write, grep, find, ls, and the extension's search tools |
| Conversation | every message and tool result so far |

### Instructions

- **Default instructions** are read-only and
  PiKit's: they name the prefix, the three tiers
  and the workspace rules, and every new build
  rewrites them.
- **User instructions** are yours alone, empty
  at first, and PiKit never rewrites them;
  saving restarts the agent, because pi reads
  the file only at startup.
- **A project's own `AGENTS.md`** in its
  folder is read as well, and the closest one
  wins.

### Process and keep-alive

- **Restart agent** stops and starts pi in one
  tap; when a start fails, pi's own error is
  under the row.
- **Keep alive** is Android's
  battery-optimisation exemption; the row says
  which state it is in and asks only while the
  exemption is missing.
- **The shade entry** appears only while an
  answer is being written and the app is in the
  background, and it names how many
  conversations are answering.

## 11. Backup and restore

Uninstalling deletes the profiles and their
keys, every conversation and the workspace, and
the app keeps no cloud copy; **Settings → Backup
& restore** is the only way to keep them.

### What the archive carries

| Group | What it carries |
| --- | --- |
| Basic settings | theme, language, default workspace, permission tier, every preference |
| Models & providers | every saved profile with its key, and the files pi reads them from |
| All conversations | one file per conversation, and which of them are pinned |
| Agent prompt | the default and the user instruction documents |
| Workspace files | everything under the agent's home workspace, plus the folders you added |
| HTML exports | the files `/export` wrote into `~/export` |

### Exporting and restoring

- **Ticked to begin with**: the first three;
  **Workspace files** is off because a workspace
  can be a whole project.
- **Include API keys** is on unless you turn
  it off, and with it off the archive carries no
  key at all.
- **Choose an archive…** shows the groups, the
  date, the app version and whether keys are in
  it before anything is applied.
- **A restore** stops the agent, overwrites
  files of the same name and deletes nothing;
  the agent starts again on the restored
  settings.
- **The runtime is not in the archive**: `usr`
  is rebuilt from the APK.

> **Tip:** the archive is the only copy of the keys,
> so keep one off the phone.

## 12. When something goes wrong

Most failures name their cause; these are the
ones with a way out.

### Answers

- **`Stream ended without finish_reason`**:
  the endpoint or the network closed the stream
  before the last chunk, and the agent retries
  by itself up to 8 times. If it keeps
  happening, try another network, or ask the
  provider whether it sends `finish_reason`.
- **A model answers "not found" or
  "unauthorised"**: the key cannot use that
  model. Fetch the list again, or type an id the
  provider reports; on a relay, check the
  endpoint's version segment too.
- **A tool call was refused**: the tier said
  no, and the ✕ line names the tier and the
  path; change the tier with the composer's
  button, or move the workspace.

### The process

- **`pi exited with code 1`**: the process
  failed to start, and the chat page shows pi's
  error with **Retry**. If that does not help,
  restart it on **Settings → Agent process**,
  then rebuild the runtime under **Maintenance &
  repair**.
- **A package will not run**: `command not
  found` or a library error. Use **Package
  relocation → Repair**; if that fails, the
  package has no build for Android.

### Your data

- **Where it is**: under the app's private
  files, in `home`, `usr` and `pi-sessions`, and
  uninstalling deletes all of it.
- **A bug report needs** the version and
  package id from **About PiKit**, pi's version
  from the same page, and pi's own last error
  from **Agent process**.

> **Tip:** **Check storage** proves the grant without a
> terminal, and **Rebuild the environment** is the way
> back from a damaged runtime.
""".trimIndent()
