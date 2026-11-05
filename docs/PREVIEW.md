# The README preview strip

The picture under the README's title, and the recipe for it. Five pages — a conversation, the
conversation list, the terminal, the files page and the settings — five full-bleed
screenshots butted together with no frames and no captions, shot in **浅色**, saved as
`docs/assets/preview-<version>.webp`.

## One scheme, and which

The app has two schemes and the strip has one row, so it has to choose. 浅色 is the
library's own palette — the one its components are drawn for — so the picture a reader
meets first is the interface rather than one of its settings. The conversation leads
because it is the page the app exists for: one turn, its tools and its answer. The list
follows it, because the drawer is how a reader leaves that turn for another.

The file is named for the version whose interface it shows. A strip of *another* version adds
a file rather than overwriting one — the strip before it keeps its own name as a record
(`preview-0.6.2.webp`, `preview-0.5.0.webp`, `preview-0.2.0.webp`). A strip of the *same*
version replaces its own file: the name is the version, so it cannot name two pictures.

Nothing generates the file, so this is the whole recipe.

## Before the shoot

- **A debuggable build of the version the strip is named for.** The seeds go in through
  `run-as`, which a release APK refuses; the release and debug APKs are often signed with
  different keys (a keystore configured for releases), so installing the debug build over
  the release one fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the shoot starts with
  `adb uninstall pi.kit.mob` — which takes the app's data with it, hence the seeding below —
  and the debug APK of that version from `app/build/outputs/apk/<flavor>/debug/`.
- **The interface language the strip is written for** — 简体中文 for the one the README ships.
- **`adb shell cmd uimode night no`**, which is how the strip gets a 浅色 shoot: the app
  follows the system unless a reader has pinned a scheme in 个性化, so the shoot sets the
  system rather than trusting what it finds.

## The shoot

1. **Capture five full-bleed screenshots** at 1080x2400, in 浅色. The drawer and the
   conversation are the page itself; the terminal and the files page are opened by the top
   bar's two marks, the settings by the drawer's foot. Tap by the bounds `adb shell
   uiautomator dump` reports rather than by eye off a screenshot: the PNG reaches a reader at
   whatever width the window has, while the tree is in device pixels. The five pages are
   five shots of **one state** — the transcript's scroll position, the fold's state, the
   terminal's scrollback and the drawer's row times are that state — so everything below is
   set up once and walked in a single pass.
2. **Give the chat panel a transcript.** The emulator has no provider, so a real turn cannot be
   produced and the empty state is what the page opens on. Seed a conversation into
   `files/pi-sessions/` with `run-as`: a `{"type":"session",…}` line, then one record per
   message — `{"type":"message",…,"message":{"role":"user"…}}`, assistant messages whose
   `content` carries `toolCall` blocks, a `toolResult` record per call, and a last assistant
   message carrying `usage`. Three traps, one hit per strip so far:
   - the records must form a **linear `parentId` chain**, because pi stores messages as a tree
     and `get_messages` returns only the branch that reaches the last record — five tool calls
     hanging off one prompt collapse to one step;
   - the file only reaches the screen if the app *opens* it, which needs
     `open_new_on_launch=false` at launch (or a tap on its row in the drawer);
   - **the header line has to be written compact**, `{"type":"session"` with no space after the
     colon, because that is the literal text `SessionMetadata` finds a session's workspace by
     (`SESSION_HEADER_MARK`). A pretty-printed record parses as a conversation with no
     directory: it lists under `未记录工作区`, at the bottom, whatever its timestamp.
   The seeds are written to `.tooling/` first — the ignored scratch directory, so they are
   never a commit — and a `touch -t` per file sets the time the drawer prints (see *Traps the
   shoot has cost*).
3. **Make the chips read something.** With no profile configured, `get_state` reports the model
   as `unknown`, so the composer's chips say `unknown` and `0%`. A `files/pikit-config.json`
   naming a provider and a model fixes the first (a dummy key is fine — nothing is sent), and a
   `usage` block on the last message fixes the other two.
4. **Give the drawer a history.** A strip whose first panel is an empty drawer says nothing
   about the app's navigation. The list is every `files/pi-sessions/*.jsonl`, so the seeds are
   a set rather than one file: a title in each one's first user message, a `cwd` in its header
   that names the workspace group it lists under (and that directory has to exist, or
   `adoptWorkspaces` skips it), and pins in `shared_prefs/pikit_session_pins.xml`, which names
   session *file* names.
5. **Show the fold, opened.** The chat panel is the one panel with a claim to make, and it is
   the feature the page exists for: an **opened** turn — the fold band with its step count,
   then a `✓` row per tool with its name and argument — above a Markdown answer with a heading
   and a table. The collapsed band is the same control in its other state and hides the point;
   a turn with no tool calls at all draws no count, which is how the first attempt shipped an
   answer that said nothing about tools. An opened fold makes the turn taller than the
   viewport, so swipe the transcript to its tail before shooting: not at the tail, the page
   draws its jump-to-tail arrow over the answer's last row. Keep the turn short enough that the
   fold, the answer and its table fit one panel — a paragraph line and a table row are both
   panel height.
6. **The terminal panel is typed, not seeded.** The banner prints itself, but a terminal that
   prints nothing else is a black rectangle with a prompt. Tap inside the view to raise the soft
   keyboard, type a few short commands (`node --version`, `npm --version`, `du -sh ~/workspace`),
   then **leave the page and come back** — the session outlives the page, and that is what hides
   the keyboard for the shot. Two things to know: keystrokes do not reach the terminal until it
   has been tapped (an `adb shell input text` before that goes to whichever view holds focus),
   and a tap on the terminal *after* it has printed selects text, which lands in the strip as a
   coloured band over a word. The page carries the terminal's own sixteen colours rather than
   the app's palette — `TerminalScreen`'s KDoc: a terminal that borrowed the app's palette stops
   being the thing whose output the reader is trusting — so **its panel is the one dark panel
   in a 浅色 strip**. That is the design, not a missed shot.
7. **Compose**: each screenshot scaled to 480px wide (Pillow, `Image.LANCZOS`), five butted
   left to right with no gap, saved as WebP at quality 88 — 2400x1067 for five panels, which
   is what every strip since 0.7.0 is.
8. **Reference it**: both READMEs (they are mirrors), and the asset rows in
   [README.md](README.md) — the new strip takes the row that was the current preview, and
   the file it replaces moves to a record row unless it was the same version's. The caption
   under the image is the version and nothing else: `PiKit <version> interface preview`
   （简中 `PiKit <version> 界面预览`）, matching the file name and the alt text. It read
   "what the reader is looking at" for one release (`PiKit's interface, drawn with Miuix`),
   which aged the moment the interface stopped changing — a caption about the drawing style
   says nothing about *which* build the pixels show, and a clause about the layout ages the
   same way; the version-stamped file name was already carrying that fact.

## Traps the shoot has cost

- **A session row's time is the file's mtime**, and opening a conversation rewrites it: a
  shoot that seeds at 01:56 and then opens the conversation at 02:28 draws `02:28` in the
  drawer, under a transcript whose own timestamps say 01:52. `run-as … touch -t <stamp>` the
  file, then **reopen the drawer** — the listing is scanned when the drawer opens, so a
  drawer that is already on screen keeps the old time. The files panel prints an mtime per
  row too, folded to `HH:mm` for today and `MM-dd` before that.
- **`adb shell input text` eats a space** unless it is written `%s`. A command typed as
  `input text 'node --version'` arrives as the single word `node`, which opens the Node REPL
  instead of printing a version and presses its own `Enter` — and a REPL that has been
  visited leaves `$HOME/.node_repl_history` behind and an eleventh row in the files panel.
  Write `node%s--version`, and delete that file before the files panel is shot.
- **The device's toybox `tar` refuses a GNU tar archive** — `Illegal seek` from stdin, `had
  errors` from a file — so the seeds go over one `run-as … sh -c 'cat > <path>'` per file
  rather than in one archive.
- **The files panel is a fixed list**: eight folders plus `data.csv` (127 B) and `README.md`
  (156 B) in `$HOME`, in the order the page sorts them, with the sizes it prints. A home
  directory with one row fewer, or a folder in it that the app created, is a panel that no
  longer matches the one beside it.
