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
 * Twelve chapters, named after what the reader is trying to do rather than after
 * the feature that does it: the first launch, setting up a model, the composer,
 * more than one conversation at once, workspaces and the permission tiers, the
 * files page, the terminal, web search, the settings rows in one table, the
 * agent process, backup and restore, and what to do when something fails. A
 * chapter opens with the one sentence that answers it and then lists; a `###`
 * inside one is a sub-question of that task, and it exists only where a reader
 * would go looking for one.
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

## First launch

The first launch unpacks about 300 MB from the
APK; the environment itself is never downloaded.

- The app asks once about notifications, once
  about file access and once about battery
  optimisation. **Declining any of them is
  fine**, and each answer can be changed later
  in Android's own settings.
- **File access** is the one worth granting:
  without it the agent sees only its own
  directory, and the phone's storage cannot be
  browsed when you pick a workspace.
- **PiKit** reopens the conversation you were
  in last; **Settings → Personalization → Open a
  new conversation on launch** starts empty
  instead.
- **Terminal** is a real shell, **Files**
  browses the agent's home; the top bar opens
  both, and **Settings** is at the drawer's
  foot.

## Set up a model

A model profile is a provider, an endpoint and a
key. Without one the composer says there is no
model.

1. **Settings → Model & provider → Add a
profile**.
2. Pick a **provider**.
3. Paste the **API key** from the provider's
dashboard.
4. Press **Fetch models** and tap one, or type
an id under **Models**.
5. **Save**. The profile goes live, and the
agent restarts if the key, endpoint or model
moved.

The **model chip** in the composer switches the
answering model at once, and so does the
**thinking chip** beside it.

### A relay or your own endpoint

Choose **Custom endpoint**, then give the base
URL with its version segment, e.g.
`https://relay.example.com/v1`, and a model id
it serves. Calls use the OpenAI-compatible API
at `/chat/completions`; the **API** row picks
the protocol when the relay documents another
one. Anthropic-only and Google-only endpoints do
not work.

A custom endpoint's key is also passed on the
command line, where anything that can read this
app's process list can see it.

### Thinking level

The menu offers only the levels the chosen model
has, and a level the model lacks moves up to the
nearest one it does. The names are pi's own
(`off`, `minimal`, `low`, `medium`, `high`,
`xhigh`, `max`), so the chip and `pi
--list-models` in the terminal use the same word
for the same level.

## Ask the agent

Type in the field at the bottom and press the
round arrow; while the answer streams it is a
red stop button. The bar always reads **PiKit**;
a conversation is named in the drawer, where the
one on screen is highlighted.

- **Above the field**: the workspace, the
  thinking level and the model, the three values
  the next message is sent with.
- **Under the field**: `⌘`, the permission
  button, `+`, the context ring and send.
- **`⌘`** opens the command list below.
- **The permission button** picks the tier the
  agent's tool calls are judged by.
- **`+`** attaches a photo, a picture or a
  file.
- **The ring** is how full the context window
  is; tap it for the model, tokens, cache hit
  rate, cost and the session file.

### Commands

| Command | What it does |
| --- | --- |
| `/new` | starts a conversation beside this one |
| `/compact` | summarises the earlier conversation |
| `/stop` | stops the answer, clears the queue |
| `/clone` | duplicates this conversation |
| `/export` | writes an HTML file into `~/export` |
| `/model` | opens the model picker |
| `/clear` | empties the input box |
| `/stats` | turns, tokens and cost so far |
| `/copy` | copies the last answer |

A message starting with `!` runs in the shell
instead of going to the model, and its output
joins the transcript. The `!` row in the command
list opens a field for the same thing.

### Attachments

- Camera, picture or file, at most four per
  message.
- An image over 4 MB is refused, and the model
  has to take image input: the switch is **Model
  parameters → Takes image input** in the
  profile.
- A file of any kind is copied into the app
  and handed to the agent as a path, so it opens
  it with its own tools.

### The transcript

- A finished turn folds to one line under your
  message (`Worked 47s · 5 steps`); tap it to
  open it. The final answer is never folded.
- Reasoning folds to one line too (`Thinking ·
  1.2k chars`).
- Each tool call is one line, marked ✓ or ✕;
  tap it to read the output.
- Long-press any text to copy it. Answers are
  Markdown, and a code block scrolls sideways.

## More than one conversation

Leaving a conversation does not stop it: an
answer that is being written keeps arriving.

- The circled **+** at the top bar's right end
  opens a new conversation beside this one, and
  nothing that is running is interrupted.
- At most **5** answers run at once. Opening
  another conversation closes the one that has
  been idle longest; only five answering with
  nothing idle is refused, and a message says
  so.
- The drawer marks a live conversation with a
  dot and **Answering now**. Tap one to switch
  to it, and the answer continues.
- Tap the mark at the top-left, or swipe right
  anywhere on the conversation, to pull the
  drawer out; swipe left, or tap the dimmed
  conversation, to put it back.

### The conversation drawer

The drawer covers about 86% of the width, and
the conversation stays visible, dimmed and a
little smaller, at its right edge. A caption
under the name says how many conversations are
saved, how many are selected, or whether a
search is still reading.

- The search field is always there, and reads
  the titles and the message text of every saved
  conversation.
- Conversations are grouped by workspace, each
  group headed with the workspace's name and its
  count, the pinned ones first, and the one on
  screen is highlighted.
- Hold a row for **Pin to top**, **Rename**
  and **Delete**.
- **Select conversations**, the mark at the
  drawer's top-right, turns the header into
  select all, delete and close.

## Give the agent a folder

A workspace is the folder a conversation runs
in, and the folder the agent may write to.

- The first chip in the composer picks it;
  **Add a workspace** takes any folder, the
  phone's storage included once file access is
  granted.
- **Settings → Agent process → Default
  workspace** is where a new conversation
  starts.
- Changing the workspace while an answer runs
  asks first, because the agent is relaunched in
  the new folder.

### Permission tiers

| Tier | What it allows |
| --- | --- |
| Read only | looks at files; changes none and runs no command that would |
| Workspace write | reads anywhere, writes inside the workspace |
| Full access | refuses nothing |

- The tier is checked on every tool call, so a
  change applies at once.
- A refused call comes back to the agent as a
  tool error naming the tier, so it says what it
  meant to change instead of retrying.

### The phone's storage

- The agent sees only its own directory until
  Android gives this app **all files access**;
  the folder picker has an **Allow file access**
  row for it.
- With the grant, the usual folders are linked
  into the agent's home: the phone's camera roll
  is `~/storage/dcim`. A link is a convenience,
  not a permission, and a write through one is
  judged like any other path.
- The limit binds the agent, not the terminal:
  `/sdcard` is the phone's real storage, and the
  terminal reaches it directly.
- **Settings → Maintenance & repair → Check
  storage** runs both guards for real, one line
  per check.

## Files

The files page browses the agent's home,
read-only: the folder mark in the top bar opens
it, the arrow at its top-left returns to the
conversation, and it shows what the agent
changed without a second app.

- The path bar under the title is tappable,
  and a segment goes back up.
- The filter field narrows the folder you are
  in; the button beside it sorts by name,
  modified time or size.
- A row's right-hand number is a file's size
  or a folder's entry count.
- Tap a file to preview it: pictures are
  drawn, text up to 64 KB, anything else is
  labelled binary.
- Hold a row for **Copy path**; a file also
  offers **Open with**.
- `/export` writes into `~/export`, a folder
  on this page.

## Terminal

The terminal is a real shell in the bundled
environment; the `>_` mark in the top bar opens
it, and the arrow at its top-left returns to the
conversation. Several sessions run at once, and
they keep running while you are back in the
conversation.

- The key bar holds what a phone keyboard
  cannot type: `ESC`, `TAB`, `C-C`, `C-D`,
  `HOME`, `END`, `PGUP`, `PGDN` and symbols.
  `CTRL` and `ALT` are toggles.
- **Scroll** stops the view so you can read
  what went past; the newline key breaks the
  line you are writing instead of running it.
- Pinch to change the text size.

| Command | For |
| --- | --- |
| `pkg install <name>` | install a package |
| `pkg search <query>` | find one |
| `pkg upgrade` | upgrade everything |
| `termux-change-repo` | switch mirrors |
| `termux-setup-storage` | link `~/storage` |
| `pi` | pi's interface |

- Packages installed with `pkg` or `apt` are
  relocated as they install; nothing needs
  doing.
- A package that still refuses to start:
  **Settings → Maintenance & repair → Installed
  packages → Package relocation → Repair**.
- **Rebuild the environment** unpacks the
  runtime again, and packages installed with
  `pkg` are recorded and put back.
- A `pi` you start here answers with the same
  model: PiKit writes your profile into pi's own
  settings.

## Web search

The agent can search the web out of the box, and
**Settings → Search settings** needs no change.
With no key it searches through Exa's keyless
endpoint, which may be rate-limited when it is
busy.

- The six controls are **Web access**,
  **Search workflow**, **Search provider**,
  **Inline content limit**, **Fetch timeout**
  and **Proxy**.
- Below them, **Configuration** lists the keys
  `web-search.json` carries; **Add an option or
  key** adds one, and the ✕ removes it.
- A provider's API key and a self-hosted
  SearXNG address are added there.
- **Restore defaults** puts the file back to
  what a fresh install has.
- If the file stops being JSON this app can
  read, a text editor replaces the controls, and
  that is the way out.

## Settings at a glance

The gear pill at the drawer's foot opens these
rows, and the arrow at the top-left returns to
the drawer.

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

- **Theme** offers system, light or dark, and
  nothing else colours the app.
- **Text size** has seven steps and applies
  when you let go.
- **Language** switches at once between
  English, 简体中文 and 日本語.

## The agent process

- **Default instructions** are read-only and
  PiKit's: they name the prefix, the three
  permission tiers and the workspace rules, and
  every new build rewrites them.
- **User instructions** are yours alone, empty
  at first, and PiKit never rewrites them.
  Saving restarts the agent, because pi reads
  the file only at startup.
- A project's own `AGENTS.md` in its folder is
  read as well, and the closest one wins.
- **Restart agent** stops and starts pi in one
  tap; when a start fails, pi's own error is
  under the row.
- **Keep alive** is Android's
  battery-optimisation exemption. The row's
  subtitle says which state it is in (**Battery
  optimisation is off for this app** or **Still
  subject to battery optimisation**), and **Ask
  for the exemption** appears only while it is
  missing.
- The shade entry appears only while an answer
  is being written and the app is in the
  background; it names how many conversations
  are answering, and it is gone between turns.

## Backup and restore

Uninstalling deletes the profiles and their
keys, every conversation and the workspace, and
the app keeps no cloud copy. **Settings → Backup
& restore** is the only way to keep them.

- The archive carries settings, profiles,
  conversations and workspaces: tick the groups
  you want, and **Export archive** puts the file
  wherever the system picker says.
- The first three groups are ticked to begin
  with; **Workspace files** is off because a
  workspace can be a whole project.
- **Include API keys** is on unless you turn
  it off, and with it off the archive carries no
  key at all.
- **Choose an archive…** shows the groups, the
  date, the app version and whether keys are in
  it before anything is applied.
- A restore stops the agent, overwrites files
  of the same name and deletes nothing; each
  conversation keeps its own date, and the agent
  starts again on the restored settings.
- The runtime is not in the archive: `usr` is
  rebuilt from the APK.

## When something goes wrong

- **Stream ended without finish_reason**: the
  endpoint or the network closed the stream
  before the last chunk, and the agent retries
  by itself up to 8 times — the answer usually
  carries on. If it keeps happening, try another
  network, or ask the provider whether its
  streaming sends `finish_reason`.
- **A tool call was refused**: the tier said
  no. The ✕ line names the tier and the path, so
  change the tier with the composer's button, or
  move the workspace.
- **`pi exited with code 1`**: the process
  failed to start, and the chat page shows pi's
  first error line with **Retry**. If that does
  not help, restart it on **Settings → Agent
  process**, then rebuild the runtime under
  **Maintenance & repair**.
- **A model answers "not found" or
  "unauthorised"**: the key cannot use that
  model. Fetch the list again, or type an id the
  provider reports; on a relay, check the
  endpoint's version segment too.
- **A package will not run**: `command not
  found` or a library error. Use **Package
  relocation → Repair**; if that fails, the
  package has no build for Android.
- **Where is my data**: under the app's
  private files, in `home`, `usr` and
  `pi-sessions`, and uninstalling deletes all of
  it.
- **A bug report needs** the version and
  package id from **About PiKit**, pi's version
  from the same page, and pi's own last error
  from **Agent process**.
""".trimIndent()
