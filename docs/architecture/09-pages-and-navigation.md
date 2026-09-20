# 9. Pages, and which moves are one

*[Architecture](../ARCHITECTURE.md) §9.*

There is no navigation library. A second-level page is local state — a `sealed
interface` of destinations in Settings, a `Boolean` for the chat's history list, a
`File` for the directory the Files tab is showing — and `PageSwap`
(`ui/components/Transitions.kt`) animates the change with `AnimatedContent`: the
incoming page enters a full width from the direction of travel and the outgoing one
is pushed a fifth of the way the other way, over 240 ms. The direction is passed in
rather than derived, because `AnimatedContent`'s `targetState` has already moved on
to the *next* destination by the time a second tap lands mid-slide.

The tab strip is the other case: four peers, `TabFade`, 160 ms and no movement at
all, because a crossfade has no direction to be wrong about.

Three moves were missing one, and the audit that found them is the list of screens whose
*whole content* is replaced by a tap:

- **The chat's history list.** It replaced the conversation between two frames — the one
  page in the app that cut while every Settings sub-page slid. It is the chat tab's second
  level in every sense, so it now slides, and back slides the other way.
- **A directory in the Files tab.** Entering a folder used to swap the listing instantly;
  it is this tab's second level, and the way out is the header's own action row. Only the
  *list* travels here, not the header: the title never changes (it is always "Files"), and
  what changes is the path under it, which updates in place.
- **The terminal's session list**, which was a `DropdownMenu` — see §7.1, sheets.

Not pages, and deliberately so: the four tabs (a crossfade, not a slide), every sheet
(movement from the bottom edge, not from the side), and every dialog (the platform's own
animation). The `ChatPage` split exists so that the history swap can hold two pages at once
without either owning the other's state: the draft, the attachments, the transcript's
scroll position and which turns are open all stay in `ChatScreen` and are passed down, so a
visit to the history list costs none of them. Rows inside a page are not pages either: an
expanded tool card, an opened reasoning block and a switched terminal session all change
content *within* the page they are on, and an animation on those would be motion without a
move.

## Back belongs to the thing on top, and the gesture walks up a browser

Two back-gesture bugs came from the same place: nothing had decided what back *means* on a
page that is a browser or on a page with a sheet over it.

- **The Files page had no handler at all**, so back from three directories down finished
  the activity. Back on a listing means the listing above it, so the gesture now mirrors
  the header's parent arrow exactly — same enabled condition, same destination.
- **A sheet lost the gesture to the page underneath it.** The modal layer registers its own
  `BackHandler` at the root, and pages are composed *inside* a transition: `Crossfade` and
  `AnimatedContent` subcompose their content during the layout pass, so a page's handler
  reaches the dispatcher **after** the layer's, and the dispatcher runs the most recently
  added enabled callback first. A page that was merely `enabled` therefore won, and a sheet
  stayed open while the page went back underneath it. Ordering the two
  handlers correctly would mean depending on the order two composition passes happen to run
  in, so the rule is explicit instead: `PageBackHandler` consumes back only while nothing is
  open above it (`SheetHost.isOpen`).

## The settings root flattened back out

The rows that state a fact — the environment, the agent process, About PiKit — were moved
behind an "Advanced" row to keep the tab root short, and that was one level too many:
opening the environment's paths or the agent's restart button cost a tap into a page whose
whole content was three rows, and the back arrow out of it landed on a page the user had
never seen. They are rows on the root again, with the two maintenance facts above them
stating their value in the value column. About PiKit is last and carries the app's own
version there, because it is the one row whose fact is about the app rather than about the
agent — and the fact a bug report is read against.

## The environment's page is rows of About PiKit

The environment had a page of its own, one row below the storage row, and it had nothing on
it to *do* — every row is a read-only fact — while repeating facts About PiKit already
carried: the bundled pi's version and entry point, the tool list and the agent's process
state were on both pages, and the process state was on the Agent page and the tab root's own
row as well. So the pages are one, and what was duplicated was **dropped rather than
merged**, because a fact drawn twice is a fact that can disagree with itself:

- **Kept from About PiKit:** the app's version, the package id, pi's version and its entry
  point inside `$PREFIX`, and the tool list — one row listing every tool, with the missing
  ones marked, rather than one row per tool next to a second row for `npm`.
- **Kept from the environment page:** the revision stamped into the unpacked image, and the
  three paths (`$PREFIX`, `$HOME`, the app's own files directory) with the note that a
  package installed under the prefix is relocated to it. Those paths are what a bug report is
  read against and had no other home.
- **Dropped:** the runtime's live state and the agent's process state. The root already gates
  the whole interface on the runtime being ready, and the Agent page is where the process is
  started, stopped and restarted.
- **Lost on purpose:** the `安装情况` section's second revision row (`运行环境状态`) was the
  same revision the installed-image row shows, read from the running status rather than from
  the stamp; and `npm`'s presence, which the updater reports by name when it fails.

A saved page state of `Runtime` restores to the tab root, the way the retired `Advanced`
page does, so an upgrade cannot land the user on a page this build does not have.

## A divider is between rows, not beside a text field

The search page's key section drew a `SettingsDivider()` over each of its seven credential
fields — six between the boxes and one under the note — and thin lines appeared between the
input boxes. A divider spans its card's full width; an `OutlinedTextField` is inset 12dp
inside that card and rounds its own corners, so the line crossed the 4dp of padding above
and below the box and stuck out past both of its corners, with 4dp of air on each side of
it. Seven boxes with a full-width hairline threaded between every pair read as a layout
fault rather than as a list. The same thing happened in miniature beside the two number
fields and the two SearXNG fields, and those went with them; the fields themselves are gone
from the page now — every credential is typed into the document editor — but the rule stands
for the fields that remain.

The rule now lives on `SettingsDivider`: it separates *rows* — a switch, a picker, a
statement of fact — and a text field is not one, because it brings its own outline. A field
therefore follows the row above it directly, and a run of fields is separated by their
outlines and by the 8dp that two `vertical = 4.dp` paddings add up to. Nothing was lost: the
section's label and card are what say where a group starts and ends, and the model and agent
pages had been built field-after-row from the start — the search page was the outlier.

The rejected design was insetting the divider to the field's own 12dp so the two would line
up, which is what Material's list insets look like. It does not answer the complaint — the
line is still a second boundary drawn 4dp away from the field's own — and it would have been
the only inset divider in the app.

## A card's last row is the row that acts

`SettingsSection` draws its rows on a `Surface` with `shapes.medium`, and `Surface` clips
its content to that shape — so a row's press ripple is a rounded rectangle at the card's own
corners and a plain rectangle everywhere else, and which of the two a row gets depends on
whether anything is drawn **inside the card** below it. The search page's
*添加配置项* row showed it: its outcome note (`ConfigNote`, `已保存…`) was inside the card
under the row, so as soon as an option had been added the row's ripple changed shape — square
along the bottom edge — while the storage page's *添加文件夹* row, which never had anything
under it, kept its rounded one.

The rule that came out of it: **the notes around an action live outside its card**, above the
section (a `SettingsNote`) or below it (the outcome), so the card is the list and the one row
that acts on it. `WebSearchConfigOptions` and `WebSearchResetSection` are both built that
way, and the two `SettingsNote`s that explain the configuration section moved out with them.
The alternative — clipping the row to a rounded rectangle of its own so its ripple never
changes — was rejected because it makes a row in the middle of a card look detached from it:
the storage page's row would still not match it in the empty state.

## A form that holds edits asks before it leaves

The model form is the one page in the app that keeps its edits until Save, which is also what
makes it the only page that can lose them. Every way out — the back arrow, the Cancel button,
the system back gesture — goes through one `requestLeave`, which leaves directly when the
form matches what is stored and otherwise asks (save / discard / cancel). The gesture needs a
handler composed *inside* the page, because the settings tab registers one for the whole tab
and the deeper handler is the one that runs first; it is `PageBackHandler` rather than
`BackHandler`, so a picker sheet still takes the gesture before the page does. The comparison
is field-by-field against the stored profile rather than a `dirty` flag set in every writer,
and it counts the model-id field's own draft: "added a model, swiped back, and the model was
not in the list" was an id typed into the field and never added, and a flag maintained by the
eight setters would have had to be right about that too.

**What the question offers depends on whether the form can be stored at all.** It used to
offer Save and Discard unconditionally, and with a profile that was not yet complete — no
provider, no model ticked — that was a loop with no exit: Save was refused, the reason was
written at the bottom of the page the dialog was still covering, and the user was back at the
same two buttons. The refusal is now `refusal()`, a pure function that both the dialog and
`save` ask, and when it answers the dialog's first button is *keep editing* and its body is
the reason; Save appears only when Save would succeed. `refusal` is pure for a reason of its
own: `save` adds the model in the id field as it stores, so a check that mutated the form
would make the *question* change the thing it is asking about.

## The web-access page owns six keys, and documents the rest itself

`pi-web-access` 0.29.0 — the version the runtime image vendors — reads roughly eighty
configuration keys. The page used to present a *selection* of them: a master switch, two
pickers, three toggles, four numbers and seven credential fields, laid out as if that were
the configuration. Two things were wrong with that: the provider picker listed eleven
services where the extension resolves **thirty**, so a Kagi or Serper user concluded their
provider was unsupported (`WebSearchSettings.SEARCH_PROVIDERS` is now the extension's own
list, and `WebSearchStoreTest` pins the count); and a *drawn* control is also a writer —
`githubClone.enabled` defaults to `true`, so a switch the page showed as on, because it had
never seen the key, would silently re-enable cloning over a file that had turned it off. The
line is therefore not "the important options" but **"the keys PiKit is willing to own"** —
six: the master switch, the workflow, the provider, two content limits and the fetch timeout.
The rest of the configuration is a **list plus one button**, and the file's own syntax is not
something the user has to know.

**What used to be here, and why it went.** The section was a text editor holding the whole
rendered document — PiKit's six keys uncommented with the values in effect, every other key
as a comment carrying its path and a usable example — and it asked the user to write JSON to
set one API key, on a phone keyboard, in a box about 46 columns wide, where the one thing
that mattered (which of three hundred lines are live) had to be read off a marker in front of
a `//`. The renderer, the schema list and the comment reader all remain, because
they are what keeps the *file* correct and what the fallback editor still shows.

**The list is the file.** `WebSearchStore.extra` walks the document and reports every live
key the page's controls do not own: a documented option whole — `curatorRemote` is one row,
not one per member — and an unknown key by its leaf path, which is the only name it has. The
order is the file's own. Removing a row removes that path and prunes any object the removal
emptied, because the extension reads a present-but-empty branch as configured, and the page
would otherwise list `fetch` for ever after its last member was removed. A key the controls
own is never listed and can never be set from here — the renderer writes those from
`WebSearchSettings` on every render, so a value added there would be overwritten by the next
tap anywhere on the page. `WEB_SEARCH_OWNED_PATHS` is the one list both sides read, and
`WebSearchStoreTest` pins it against the renderer's own live keys.

**One button adds one option.** It opens `WEB_ACCESS_PARAMS` in the app's picker — with a
filter field, because eighty rows is past the point where scrolling is finding. The sheet
that follows takes the value, and its shape follows the option's type: a `string` option is
stored as a JSON string, which is what makes `$XAI_API_KEY` work and what keeps `"sk-…"`
with its quotes from being the tax this section exists to remove. Everything else is parsed
as JSON, so a `boolean | object` option accepts either shape and a `"50"` where a number
belongs is refused rather than quietly stored — the extension ignores a value of the wrong
type, and a field that says why is worth more than a setting that does nothing.

**The file on disk is not the document, and the difference is a bug that shipped.** The
extension parses `web-search.json` with a bare `JSON.parse` — `loadConfiguredProxy` in its
`utils.ts` and a `loadConfig` in every provider that takes a key — so a single `//` in the
file fails *every* web tool with `Unexpected token '/' … is not valid JSON`, and
`web_search`, `fetch_content` and `source_check` were all dead on a default install. The two
are separated now: the **document** is what the renderer produces and what the fallback
editor shows, comments and all, and the **file** is `strictDocument(document)` — the same
text parsed with the comment-tolerant reader and re-encoded as plain JSON, so only the keys
that are live are in it, and no comment ever is. `ensureDocument` repairs an install that
predates the fix: a file that is a JSON object once comments are stripped but not as it
stands is rewritten in the strict form at the next launch — one that is already strict keeps
its bytes, including a hand-written one whose formatting differs from PiKit's, and one that
does not parse at all is left for the editor, which is the only way out of it.

Four details make the renderer work, and each is pinned by a test:

- **Comments are read on the way in, never written.** `stripJsonComments` — pi's own two
  regular expressions from `utils/json.js`, no block comments — reads them, so the document
  may contain `//` comments and trailing commas. It is a *reader* only: a file with comments
  in it is a file no tool can use, so the writer's guard is `strictDocument`.
- **A write may not lose anything.** Rendering walks the schema tree, so a key the document
  already has is written uncommented with its value, and a key the schema has never heard of
  is written uncommented where it was found, with a comment saying it is not PiKit's. The
  user's own formatting is the one thing not preserved, which is the point.
- **Some objects may not be empty.** `searchRouting.providers` and
  `fetchRouting.providers` are required once their parent exists, so an `{}` there is a file
  the agent refuses to start on; those two branches are written as a commented block until
  the user gives them a value. Every other branch may be an empty object, which the extension
  reads exactly as an absent key.
- **The document has no trailing commas, and that was measured.** On the real rendered
  document, handed to the extension's own `stripJsonComments` and then to `JSON.parse`,
  **two survived and the parse threw** — the string-literal alternative in its second regular
  expression can swallow a comma followed by comment-stripped blank lines, depending on where
  the scan happens to be. So the document is valid JSON with nothing in it that needs
  stripping, and the editor's Save says so instead of writing a document nothing can parse. A
  test asserts the property directly, because it is invisible in review and fatal on the
  device.

The document draws **a blank line between every two siblings**, at every depth, which costs
nothing in the file: a blank line is dropped by `strictDocument` along with the comments, and
an empty line inside a commented block stays empty rather than becoming a bare `// ` (which is
what `commentedOut` had to be taught).

The rows document the extension as it is, not as its README describes it: the project's `main`
branch documents `serplyApiKey`, `fetch.defaultMode` and `webSearch.allowedProviders`, none of
which exist in any published release. The examples use `$NAME` references rather than
placeholder keys, because the extension treats an unset variable as *not configured* — so a
value filled from the example cannot turn a working search into a broken one, which `"sk-..."`
would.

"Restore defaults" is the one destructive control on the page and it asks first: it puts the
fresh-install configuration back, which takes every key the user typed with it.

## The update check is a row, and it is the app's only request of its own

About PiKit gained a **Check for updates** row, and the design question was not how to check
but whether to. A release check on launch would be one request per cold start, from every
install, carrying an install timestamp to GitHub — telemetry by another name, in a project
whose whole claim is that it has none. So the row is inert until it is tapped, the answer is
shown in the row it was asked from, and there is no update *notification*, because a
notification is a check nobody asked for. Four details are each a decision:

- **It asks GitHub Releases, not a version file of the app's own.** The release workflow
  already creates the release and the tag, so the answer is the artefact a user would
  install. This is the distribution channel: Play is not one — the app targets `targetSdk
  28` (see §1) and holds `MANAGE_EXTERNAL_STORAGE`, which Play's policies do not accept for
  this kind of app. `pikit.repository` is the one line that says which repository to ask.
- **It compares `versionName`, not `versionCode`.** Android's integer ordering is a fact
  about installability, not about which release is newer to a person, and the tag is derived
  from `versionName` anyway: `0.10.0` is newer than `0.9.0`, which a string comparison gets
  backwards.
- **It asks the release *page*, not the API.** `GET api.github.com/repos/…/releases/latest`
  is unauthenticated here — there is no token in this app, by design — and GitHub allows
  **60 such requests per hour per address**, so an address behind a VPN can already have spent
  a whole node's budget: measured from exactly such an address, `HTTP/1.1 403 rate limit exceeded`
  with `X-RateLimit-Limit: 60`, `X-RateLimit-Remaining: 0` and `X-RateLimit-Used: 60`. The page
  `https://github.com/<owner>/<repo>/releases/latest` carries the same fact as a `302` to
  `…/releases/tag/v0.2.0`, against no API budget, and the URL it lands on is both the tag to
  compare and the page to open; a repository with nothing published answers `404` (measured
  while this project had no release) or redirects to the releases list (measured on
  `octocat/Hello-World`).
- **It installs nothing.** Downloading and relaunching an APK from inside the app needs
  `REQUEST_INSTALL_PACKAGES`, a `FileProvider` root and a `PackageInstaller` session, and it
  only works when the new build is signed with the same key as the installed one. The row
  opens the release page in a browser instead; the user installs it, which is one tap more
  and three failure modes fewer.

Its failure text is the exception's message — or a named refusal — rather than a guess:
"Unable to resolve host" is a different problem from "GitHub refused the request (403)", and
the person reading it is the one who can act on the difference. `data/UpdateCheck.kt` is the
whole of it: no library, one `HttpURLConnection` that follows the redirect, and two pure
functions (`releaseFrom`, `compareVersions`) that the JVM suite pins.

---
