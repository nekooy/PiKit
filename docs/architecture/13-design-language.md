# The interface, drawn by Miuix

Miuix is the whole of this app's interface: the palette, the type scale, the shapes,
the motion, the preference rows, the dialogs and the sheets. This chapter is the rule
for what a page may look like, and the reason each rule is there. The code it
describes is `app/src/main/java/pi/kit/mob/ui/kit/` — the theme, the page, the
section, the menu row and the window-level overlays — plus the screens that compose
it.

**A page composes the library and the kit. It does not invent a colour, a radius, a
type style or a spring of its own.** That is the whole rule; the rest of this chapter
is what it means where it is not obvious.

## Why the app stopped drawing its own design system

Until the Miuix rewrite the interface was Material 3 Expressive with a design system
of the app's own: `ui/design/` (nine files — palette, type, shapes, motion, surfaces,
inputs, actions, feedback, sheets) and `ui/components/` (six wrappers over Material
widgets) — **15 files and about 5,100 lines** whose job was to turn the library's
tokens into the app's vocabulary. Every screen depended on that vocabulary, and every
Material upgrade had to be re-read against it.

The rewrite replaced it with a library that already owns the vocabulary:
`compose-miuix-ui/miuix` 0.9.4, a Compose Multiplatform component library drawing the
MIUI/HyperOS idiom. Its theme engine ships the two palettes and derives a third
from a seed colour if asked; its preference components are the rows a settings page
is made of; its window dialogs,
sheets, snackbars, icons and navigation are the furniture. What is left for this app
is *no colour decision at all* — the palette is the library's, both schemes of it —
and a small set of compositions the library does not have: the page frame, the
section, the popup row, the window-level dialog phrasing, the snackbar host, and the
content rendering (Markdown, formulas, the terminal).

**A wrapper approach was rejected.** Keeping the `Pi*` API and re-implementing its
internals with Miuix would have compiled the same screens with less churn, but it
would have left two vocabularies in the tree — one wrapper per component, re-exposing
the library's parameters a few at a time — and the result would have been a re-skin
of the old design rather than a rewrite on the new one. The final tree has no `Pi*`
component that duplicates a Miuix one.

**Keeping Material 3 alongside Miuix was rejected for the final state.** Two palettes
and two type scales on one screen is exactly the drift this change removes. Both
libraries also draw press indications and squircle cards differently, so a screen
mixing them reads as two apps in one frame. Material 3's AAR metadata is no longer
what fixes `compileSdk` 37 either: that floor is the Compose artifacts' own, and it
stayed when the dependency left.

The cost is real and accepted: the app no longer decides what a Miuix component looks
like, and Miuix is an experimental library — a bump can move the interface. That is
the trade: one coherent idiom owned by a library, against a hand-kept design system
maintained alongside every screen.

## The palette: the library's own, both schemes

`PiKitMiuixTheme(settings)` is the one place the theme is assembled, and it makes
exactly one decision:

| Preference | What it becomes |
| --- | --- |
| `ThemeMode.SYSTEM` | `ColorSchemeMode.System` with `isDark = null` — the library reads the platform's setting |
| `ThemeMode.LIGHT` / `DARK` | the same mode with `isDark = false` / `true` — one controller, one override |

Nothing else is passed. The controller's default `lightColors` and `darkColors` are
Miuix's `lightColorScheme()` and `darkColorScheme()`, and those are the interface:
the blue `#3482FF` primary, the `#F7F7F7` page against a white card, the `#E0E0E0`
divider, the disabled pairs, the slider's track. A page assembled from the library's
components is drawn in the colours those components were drawn for, which is what
"the interface is Miuix" has to mean.

**An accent picker and a wallpaper switch were tried and removed.** The rewrite
first kept both: a `ThemeColor` seed went in as `keyColor` and the library's
material-colour engine derived all hundred and forty roles from it, with dynamic
colour expressed as "no key colour" so the platform's wallpaper palette answered.
It compiled, it looked plausible, and it was the wrong thing to own — a derived
scheme is a *different* palette wearing the library's component shapes, so the
container steps and the disabled pairs no longer sat where the library's own
screens put them. The measured consequence is small and real: a seeded tonal-spot
scheme paints `secondary` as a tinted grey, so `PiTone.live()` — a role this app
names — had to move to the tertiary pair, where the library's own scheme keeps a
quiet blue.

The eight-colour enum outlived the picker as `AvatarColor`: an avatar's disc is
*content*, not a scheme role, and a red avatar is red in both schemes the way a
photograph is.

Two roles the app names itself, both taken from the library's scheme rather than
invented:

- **`PiTone.live()` / `liveContainer()`** — the colour that means *a turn is
  streaming*, *an agent is working*, *a process is still going*. Miuix has no
  "live" role; the kit maps it to the `tertiaryContainer` / `onTertiaryContainer`
  pair, which is the palette's quiet blue — tellable from an action without being a
  second accent.
- **`PiTone.error()` / `errorContainer()`** — the scheme's error pair, re-exported so
  a call site says *danger* rather than naming a role.

The system-bar icon appearance is set here too, from the resolved dark state. It is a
property of the theme rather than of a page, and one authority is what keeps a
dialog's separate window from disagreeing with the page behind it.

## The type scale is the library's fourteen styles

Miuix ships fourteen text styles — `title1`–`title4`, `headline1`–`headline2`,
`subtitle`, `main`, `paragraph`, `body1`–`body2`, `button`, `footnote1`–`footnote2` —
and the app defines none of them. What each is used for:

| Where | Style |
| --- | --- |
| Hero number, setup percentage | `title1` (32sp) |
| Page-opening heading | `title2`–`title4` |
| A row's title, a menu row's title | `headline1` at medium weight, the library's own row typography |
| Running prose (a reply, the manual) | `paragraph` (17sp) |
| Secondary line, code, table cells | `body2` (14sp) |
| Captions, dates, fences, key-bar keys | `footnote1`/`footnote2` |
| Action labels | `button` |

**No font is bundled**, the rule the previous design language settled and this one
keeps: the app's text is overwhelmingly machine output — a transcript, a terminal,
file paths — where the reader's familiarity with the platform's face is worth more
than a distinctive one, and the hierarchy is carried by the library's size ladder.

**The transcript's prose is `paragraph` with its line box raised at the call site.**
Miuix's `paragraph` carries a `1.2em` line height, barely above a Latin font's own
box; an inline code span's fill is the font box plus a point of padding, so on the
library's own line box the fill touches the line above. `Markdown.kt` raises the line
box to 26sp where the style's own 1.2em would be 20.4sp, and that 5.6sp is the fix —
a call-site decision, because the same style is drawn elsewhere without code in it.
ARCHITECTURE §12.1 carries the measurement.

The text-size preference is one multiplier on the composition's `LocalDensity`
`fontScale` (in `MainActivity`), so every `sp` in the app — every row, the
transcript, the terminal banner, the dialogs — moves together. The library needs to
know nothing about it.

## A page is a scaffold, a bar and a one-line subtitle

`PiPage(title, subtitle, onBack, actions, floating)` is the frame every destination
and every settings page uses. It is a Miuix `Scaffold` with a pinned
`SmallTopAppBar`, a `BackHandler` that exists only when `onBack` does (a tab root has
no back arrow and does not swallow the system gesture), and a body that receives the
scaffold's `PaddingValues`.

The subtitle is drawn by the kit, not passed to the bar, and that is a measured rule:
`SmallTopAppBar` renders its `subtitle` with **no line limit**, so a sentence wider
than the space beside the action icons wraps and the bar — and every page's content
rectangle with it — grows by a line. The terminal page measured it: its English idle
sentence wraps on a phone where the Chinese one does not. The kit draws the subtitle
itself, one line with an ellipsis, and distinguishes `null` ("this page has no
subtitle") from `""` ("reserve the line while the value is being read") — the files
page's item count is null while a folder is listed, and a line that appeared and
disappeared once per folder moved the whole page by its height twice.

## Sections, rows, and the one thing a row may not do

A settings page is a sequence of `PiSection`s: a `SmallTitle` and a `Card` of rows.
The rows are the library's preference components — `BasicComponent`,
`ArrowPreference`, `SwitchPreference`, `SliderPreference`, `RadioButtonPreference`,
`CheckboxPreference`, the dropdown and spinner preferences — and the app has no row
wrapper of its own. A value sits in the trailing slot; an arrow means "this row opens
something"; a switch means "this row is the switch".

Two row rules are behaviour rather than taste:

- **A row that raises a question is inert, and its switch is the control.** The
  storage grants and the safety guard ask every time they are turned on or off, so
  the row does not toggle and the finger has to reach the switch. Backup's switches
  stay row-toggling, because nothing there asks.
- **A `BasicComponent` with `endActions` must not go inside a `ListPopupColumn`.**
  Miuix 0.9.4 sizes a popup column from its children's intrinsic sizes, and the
  component's end-action budget overflows `Int` at the unbounded width it is asked
  about — the arithmetic is `(available - 8dp) * 6 / 10`, which is negative at
  `Constraints.Infinity`, and the crash is `maxWidth must be >= than minWidth`. It was
  reproduced on the terminal's session roster. `ui/kit/PiMenuRow` is the row for a
  popup that needs a trailing mark; a `BasicComponent` *without* end actions is safe.

## Dialogs, sheets and the one message

**Dialogs are window-level.** `PiConfirm` and `PiAlert` wrap Miuix's `WindowDialog`
rather than the overlay variant, and the reason is this app's shape rather than a
preference: the first-launch permission prompts are raised before there is a page to
attach an overlay to, a pi question can arrive over any destination, and a sheet
belongs to a page that may be disposed while it closes. A window dialog needs no
`Scaffold` host, so it works in all three cases with one call. The cost is the
platform's dialog window around Miuix content — the content still inherits the
theme, density and language, and Miuix draws its own background, corner radius, dim
and animation inside it.

**Actions in a dialog are flat text buttons**, with the dismiss answer neutral and the
confirm answer in the error role only when it cannot be taken back
(`destructive = true`). Filled buttons were tried in the language picker and rejected:
a dialog's confirm is not the page's primary action, and a filled pill in a dialog
competes with the page behind it for the same emphasis.

**Sheets are `PiSheet`** (Miuix's `WindowBottomSheet`): scrim, back gesture and
downward drag all dismiss, and the page only owns *which* sheet is open. A picker is
a sheet of radio rows with a filter field, not a dropdown, when its content is longer
than a few words — a dropdown's popup is sized for a list, not for a search.

**Transient messages are one snackbar**, hosted once at the root and raised through
`LocalPiToast`. The old pill became the library's snackbar, and two of its rules
survive with it: there is **one slot** (a second message replaces the first and
restarts the clock — a queue is a log nobody reads), and the split against an
in-page notice is by tense — a *moment* ("Saved", "Agent restarted") is a snackbar, a
*condition* ("the agent failed to start", "this folder cannot be read") stays on the
page in the error role, because a message that disappears after four seconds is the
wrong shape for something that is still true. A failure the reader has to *read* is a
dialog with a bounded, scrolling body (the maintenance runs, the model catalogue's
discovery problems); a field's own validation error is a line under the box.

## Navigation

The four destinations are a Miuix `NavigationBar` at the root, one item per
destination — icon and label, the library's own selected state, no custom indicator.
The tab switch is a `Crossfade` with the bar outside it, so the bar does not travel
with the page and a draft in the composer survives a look at the terminal (the
composer is hoisted above the destination for the same reason). The terminal's
glyph — a chevron and an underscore — is the one mark drawn for this app, because
Miuix's set has no terminal icon.

The settings stack keeps its own model: a sealed `SettingsPage` with a `parent`, a
`rememberSaveable` encode/decode, and a per-page `PiPage(onBack)` rather than one
handler above the stack. It survives process death, which a single "current page"
variable does not.

## Shapes, motion, icons

**Shapes are the library's.** Cards, fields and menus are squircles; buttons are
capsules. The two voices the previous design language described — dense surfaces
against round actions — survive as the same distinction in Miuix, but the app no
longer owns a radius, and nested-corner arithmetic is the library's business.

**Motion is the library's**, except for the one page-level move a page has to spell
out (the settings stack's forward/back slide, the files page's folder transition, the
chat history swap). There is no app-level spring scale: the library's dialogs, sheets,
switches, sliders and navigation bar carry their own animation, and a page that adds a
spring of its own is a page that will move differently the next time the library does.

**Icons are Miuix's**, at the library's 24-unit weight and tinted by the component
drawing them. The exceptions are four glyphs the reader chose at the size the app
draws them — the model mark, the thinking mark, ⌘ and the copy/check pair — which
predate the rewrite and are kept: a glyph chosen that way is not replaced because the
container around it changed. They are drawn through Miuix's `Icon`, from the same
drawables. The Pi mark is the launcher icon's geometry, drawn from `ic_pi_mark.xml`.

Two glyph rules survive from the previous interface because they are still true: a
glyph from another set has another weight, so **its size is a call-site decision** (⌘
is drawn at 17dp where the library's glyphs are 21dp); and a glyph that was not drawn
here is not edited here — scaling it at the call site is a layout decision, changing
its geometry is not.

## What the pages do with it

The four destinations and their second levels, and the one decision each makes that
the shared vocabulary does not:

- **Chat** — the transcript is the app's densest surface and the one place a `Card`
  per turn is right, because a turn is a subject: a prompt card on the reader's own
  colour at the end edge, an answer card holding that turn's reasoning, tool calls and
  reply. Tool calls are dense rows inside it, a live turn carries `PiTone.live()`, and
  the composer is the page's bottom band — a `surfaceContainer` strip holding the four
  chips, the `TextField` and the action row — so the transcript keeps the rest of the
  height. A conversation with nothing in it draws the Pi mark and the page's hint
  instead of a blank area, because the blank is the first thing the app ever shows.
- **Terminal** — a black rectangle with its own sixteen colours, so the chrome stays
  out of the way: a `PiPage` bar with the shell's name and the session roster, and a
  key bar of Miuix buttons on a `surfaceContainer` track. The keys are the same glyph
  weight as everything else, a latched modifier takes `primary`, and the bar scrolls
  horizontally because English labels need more room than Chinese ones.
- **Files** — a directory is a list of objects, and it is drawn as one: a
  `BreadcrumbBar` location trail, a filter field with a sort menu, and a `LazyColumn`
  of Miuix `Card` rows with press feedback and a hold menu (copy path, open with). A
  row's glyph is **by what the file is** — folder, link, code, image, archive, video,
  audio, sheet, document — because a listing where every file wears the same sheet of
  paper asks the reader to read names to tell anything apart. The preview is a sheet
  that draws a picture instead of calling it binary, and carries **Copy path** beside
  **Open with**. This page chose cards where the previous interface deliberately had
  none: the Miuix card's press feedback is the row's touch affordance, and a table of
  unframed rows had none.
- **History** — a list of conversations is a list of *questions the reader asked*, so
  a row is the reader's own words, ellipsised to one line, beside when it was and how
  much followed, with selection as a checkbox and actions on a hold. The pinned group
  is tinted with the secondary container under its own `SmallTitle`, not one lift per
  row, so a run of pinned conversations reads as a group rather than a stack of
  separate selections.
- **Settings** — `PiSection` groups by subject, rows that act, and a page whose second
  level is a page rather than a dialog. The rows carry the library's own typography
  and spacing; what the page decides is *which* group a fact belongs to, not how a
  row looks.

## What the rewrite kept, and what it removed

Kept, because they are behaviour rather than skin: the strings catalogs and the three
languages; the transcript's parser, turn cards, folding, tool rows and status pills;
JLaTeXMath, `MathCache`'s worker and the formula line box (§12.2); the terminal's
vendored `TerminalView`, its fixed sixteen colours and every key it sends; the copy
glyph and its 1400 ms confirmation; and every settings rule — validation, the restart
schedule, "ask before discarding a form", the font-size commit on release,
per-grant storage questions.

Removed in the same change: the Material 3 palette, shapes, motion and typography;
`ui/design/**` and the six shared component wrappers (15 files, about 5,100 lines);
the `androidx.compose.material3` and `material-icons-extended` dependencies, with
nothing left importing them; and two `Strings` members whose call sites died — the
sessions page's back label, which `PiPage` now names from `common.back`, and the
thinking-level descriptions, which the composer's menu stopped showing when it became
a list of ids.

`components/CopyButton.kt` is the one survivor of the old `ui/components/` package,
and only because its content was rewritten to Miuix while keeping the two call sites:
the transcript's meta row and a tool card's output still use the same control.

## What is still the app's, not the library's

- **The transcript's content colours.** Syntax highlighting, diff tints and the
  live/error tones are content, not chrome; they are derived from the scheme where
  they can be and fixed where the terminal requires it.
- **The terminal's sixteen colours.** A terminal with a theme-derived palette is a
  terminal whose output changes meaning with the wallpaper.
- **The formula line box** — the box, the ascent/descent measurement and the
  typesetter are the app's; only their ink and text style come from the theme.
- **The window-level dialog phrasing** (`PiConfirm`/`PiAlert`) and the snackbar host,
  because Miuix ships the window and not this app's two-answer question.
