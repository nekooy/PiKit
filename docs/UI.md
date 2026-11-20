# The UI guidelines

The practical rules for changing anything under `app/src/main/java/pi/kit/mob/ui/`.
Read this file before the change; read the chapter it points at before the line.
[AGENTS.md](../AGENTS.md) has the hard constraints and the commands;
[ARCHITECTURE §13](architecture/13-design-language.md) is the design language this
file is the checklist for — the palette, the type scale, the page frame, the rows,
the windows and the navigation, each with the measurement that settled it. The
reasoning lives there; the *do this, not that* lives here.

The shape of this file follows a sibling Miuix app's own guidelines
([Mishka's `ui-guidelines.md`](https://github.com/YuKongA/Mishka/blob/main/docs/ui-guidelines.md)
and [`miuix-glass-migration.md`](https://github.com/YuKongA/Mishka/blob/main/docs/miuix-glass-migration.md)):
a checklist of the traps a Compose + Miuix change walks into. The rules are this
app's, not a transplant — Mishka has four pager tabs, a wide-screen rail and a
Compose 1.13 line; this app has one root, a drawer, and the pins below. Where a
rule of theirs does not survive the difference, the difference is named.

## The pins the rules are written against

- `compose-miuix-ui/miuix` **0.9.5-beta01** — the first release with `miuix-glass`
  (the OS4 material) and `miuix-squircle`. beta02 is *not* taken: it moves the
  library to Compose Multiplatform 1.13.0-rc01, and this project builds the stable
  1.12 line (`gradle/libs.versions.toml` says why).
- Compose **BOM 2026.09.00** (the 1.12 line), Kotlin **2.4.21**. So: `derivedStateOf`
  (not 1.13's `computedStateOf`), `AnimatedVisibility` (not `CapturedAnimatedVisibility`),
  and `TextFieldState` *is* available and is what a field uses. The strong-skipping
  compiler is on, which is why an unstable parameter is skipped by instance equality
  and the app carries no immutable-collections dependency.
- `minSdk 26`, `targetSdk 28`. Glass needs `RuntimeShader` (API 33); the manifest
  overrides `miuix-blur`'s own floor and every glass call site is gated. **Do not
  raise `minSdk` to "fix" the glass floor** — the README promises Android 8.0
  (AGENTS.md, *Hard constraints*).

## The vocabulary — what a page is built from

A page composes the library and the kit. It does not invent a colour, a radius, a
type style or a spring of its own (§13). The kit is `ui/kit/`; reach for it before
writing a composition:

| Need | Use | Not |
| --- | --- | --- |
| A destination or settings page | `PiPage(title, subtitle, onBack, actions, body)` | a bare `Scaffold`, a second bar variant |
| A titled group of rows | `PiSection` (+ `PiGroupAction` for the group's own action) | a hand-drawn `Card` + heading |
| A row in a popup menu | `PiMenuRow` (choice / action / fact) | `BasicComponent`, a preference row |
| A question with answers | `PiConfirm` (app) / `PiDialogHost` (pi's questions) | a raw `WindowDialog`/`GlassDialog` |
| A window's content while it leaves | `rememberLastPresent(subject)` | reading the nullable flag straight into the body (empties the exit) |
| The answer row of any dialog | `PiDialogAnswers` | a hand-built `TextButton` pair |
| A bottom sheet | `PiSheet` | a raw `WindowBottomSheet`/`GlassBottomSheet` |
| A transient moment | `LocalPiToast.show(...)` | the platform `Toast`, a snackbar, an in-page line |
| A floating glass surface | `PiGlass.panel(shape)` / `Modifier.piGlass(shape)` | `glassPanel` called directly |
| The page's blur layer | `rememberPiBackdrop()` / `LocalPiBackdrop` | a window-wide layer |
| A text field's colours | `piFieldColors()` **and** `piFieldCursor()` together | the library defaults (a blue ring) |
| The corner | `PiCorner.radius` (20dp) at every `Card`/`Button`/`TextField` | a per-screen radius |
| A semantic colour | `PiTone.live/done/error/inset/prompt` | a role named at the call site, a literal |
| A buzz | `PiHaptics` (`haptics.hold/toggle/choose/key/tap/action`) | `performHapticFeedback` anywhere else |
| A page-level move | `PiMotion` | a hand-written spring |
| The elastic end of a scroll | `Modifier.piOverScroll()` | a second overscroll inside a region |
| A sheet's bottom clearance | `piSheetBottomInset()` | a guessed fixed padding |
| The navigation strip's height | `LocalPiNavigationInset` | reserving it as a viewport bottom padding |

**No component duplicates a Miuix one.** The wrapper approach was tried and rejected
(§13): a `Pi*` re-exposing a library widget a parameter at a time leaves two
vocabularies in the tree. The kit compositions above exist because the library has
no equivalent — the page frame, the popup row's three semantics, the window-level
dialog phrasing, the toast host. A new one needs the same argument.

## The page skeleton

`PiPage` is a Miuix `Scaffold` with a pinned `GlassTopAppBar`, a `BackHandler` that
exists only when `onBack` does, and a body that receives the scaffold's
`PaddingValues` with **bottom zero**. The strip's height comes down as
`LocalPiNavigationInset`; a scrolling body spends it as padding at the *end of its
content*, where the last row can still be scrolled clear of the gesture handle and
the rows above pass under it. Reserving it as a body padding was the opaque band
that cut the last card in two (§13, *The window's last pixels*).

- **The subtitle is `PiPage`'s, never the bar's.** Pass it to `PiPage`; do not write
  `GlassTopAppBar(subtitle = …)` or `SmallTopAppBar(subtitle = …)`. The library
  renders its own subtitle with no line limit, so a wide sentence wraps and grows
  the bar — and every page's content rectangle with it. `PiPage` draws one line with
  an ellipsis. `null` is "no subtitle"; `""` is "reserve the line while the value is
  read" (the files page's count). A page whose subtitle arrives later passes
  `orEmpty()`.
- **A settings page is a `Column(Modifier.verticalScroll(…))` of `PiSection`s.**
  These pages are short — a dozen rows — so whole-page composition is cheaper than a
  lazy list's bookkeeping, and the elastic end works on a page that fits. The long
  lists are lazy: the transcript, the files listing, the history drawer.
- **`piOverScroll()` comes *before* `verticalScroll` in the chain, never after.** A
  nested-scroll connection finds its scrollers by walking ancestors; placed after
  the scroller it is downstream and never dispatched to, so the page silently loses
  the bounce (`PiOverScroll.kt`, §9.1). On a `LazyColumn` it goes on the list's own
  modifier. A region *inside* a page (a list bounded in a card, a dialog's scroller,
  a `LazyRow`) keeps no elastic edge of its own.
- **No vertically scrollable component is measured with an unbounded height.** A
  `LazyColumn` inside a `Column(verticalScroll)` without a height bound throws
  `IllegalStateException`. `MarkdownText` is the shared one and stays a plain
  `Column`: it is drawn inside the transcript's `LazyColumn` *and* the manual's
  scrolling column, and a shared composable's constraints are the union of
  everywhere it is drawn (AGENTS.md, *Hard constraints*).
- **The chat page draws its own frame** — no `PiPage`, two floating capsules over a
  transcript that runs under them (§13, *Navigation*). That is the one documented
  exception, not a licence for a second page to invent a bar.

## Glass: the material, and the red lines

The chrome — the page bar, the chat header's capsules, the composer and its chips,
the jump-to-latest disc, a dialog or sheet raised from a page — is OS4 glass. A
settings card is still a Miuix `Card`: glass is a material for controls that *float
over* the page, not for every rectangle on it (§13).

The architecture is two levels, and the page owns the lower one:

- **The page's backdrop** is created by `rememberPiBackdrop()` at the page level and
  records only the page's **body**. `PiPage` puts `layerBackdrop` on the body `Box`
  and composes the bar in the Scaffold's `topBar` slot — *outside* the layer. The
  chat page puts it on the transcript `LazyColumn` only; the header and composer are
  siblings. `LocalPiBackdrop` carries the value down to the controls.
- **A floating control** is cut from `PiGlass.panel(shape)`, which calls `glassPanel`
  with `GlassMaterials.puredThinGlass` — the same material `GlassIconButton` uses —
  the small OS4 edge stroke, the floating-control shadow, and an **opaque** fallback.
  The material is load-bearing: `glassPanel` with no material draws the blurred
  backdrop alone, which over an empty page is the page itself (the two ends of one
  header row measured rgb(239) and rgb(230) before the material was shared; §13).

**The red lines** — each is a crash or a visible defect, not a taste:

1. **A `layerBackdrop` must not contain the glass controls that sample it.** The
   first build wrapped the whole chat page in one layer; the controls sampled their
   own recording and `RenderNode::prepareTreeImpl` recursed until the RenderThread
   stack overflowed (`SIGSEGV`, `SEGV_ACCERR`) before the first frame. The transcript
   is the layer; every control that samples it is a sibling. A window-wide layer puts
   the bars inside their own sample.
2. **`layerBackdrop` comes before inset/padding modifiers**, right after
   `fillMaxSize()`. A layer recorded after a padding is narrowed and offset, and the
   bar's blur clips to a rectangle on rotation or a sidebar change. (Mishka's first
   engineering red line; the same trap, this app's `PiPage` body and transcript.)
3. **The backdrop draws an opaque base first.** `rememberPiBackdrop` does
   `drawRect(surface); drawContent()` so a page that paints no background of its own
   still gives the glass the page colour rather than transparent black.
4. **Every glass call site is gated by the shader.** `rememberPiBackdrop()` returns
   `null` below API 33 (and on a GPU that cannot run the shader); `PiGlass.panel`
   then draws `fill()` — `surfaceContainerHighest`, opaque. A new call site that
   reaches `glassPanel`/`RuntimeShader` without passing through `PiGlass` or
   `rememberPiBackdrop` is the way the Android 8 floor breaks. Do not add one.
5. **A floating control is filled, not tinted.** The fallback is opaque; a
   translucent wash was tried (80/90/100% white in light, 14/20/24% in dark) and the
   dark half never became opaque, so a row scrolled under the capsule showed through
   it (§13). The glass body is the blurred page, not a wash of the surface colour.
6. **No second blur over a progressive one.** `GlassTopAppBar(progressiveBlur = true)`
   already draws the ramp; wrapping it in another gaussian blur is two materials.

**The drawer foot and the toast are the documented non-glass controls**: the drawer
is a panel with no page backdrop, so its mode switch and settings disc use
`PiGlass.fill()` directly; the toast capsule is the same fill on the window's top
layer. They are still floating objects, not rows.

## Shapes: one corner, and squircle for the hand-drawn ones

Miuix owns the radius. Its cards, fields and buttons are **16dp squircles** drawn by
the library's own `miuix-squircle` modifiers; the app draws one step larger,
`PiCorner.radius` (20dp), passed at every `Card`/`Button`/`TextButton`/`TextField`
call site so the whole interface is cut with the same corner (§13, *Shapes*). The
glass renderer's `GlassShape` carries its own corner smoothing, so a glass panel is
already continuous-cornered.

**A hand-drawn rectangular surface uses the squircle modifiers, not
`RoundedCornerShape`.** A `clip(RoundedCornerShape(20.dp))` beside a Miuix card is a
circular corner beside a continuous one — the same radius, two curves. The library
ships the modifiers (`top.yukonga.miuix.kmp.squircle`, pulled in by `miuix-ui`); pick
by what the surface does, because the cost differs:

| Surface | Modifier | Cost |
| --- | --- | --- |
| A non-clickable solid fill | `squircleBackground(color, radius)` | a shader brush, **no offscreen layer, no clip** |
| Something that must be clipped (a picture, a tile) | `squircleClip(radius)` | one offscreen layer |
| A clickable fill (ripple clipped to the corner) | `squircleSurface(color, radius)` | fill + clip |

Below API 33 each falls back to the matching `RoundedCornerShape`, so the Android 8
floor is kept by the same gate as glass. `LocalSquircleEnabled` (default true) is the
library's opt-out; the app does not turn it off.

**Where a `Shape` is passed rather than a modifier** — `graphicsLayer { shape = … }`
and a Miuix `Surface`'s `shape` parameter — the continuous shape is
`GlassShape(radius)` from `top.yukonga.miuix.kmp.material`, the same corner smoothing
the glass panels draw. `ChatNotice` and the drawer card use it; a `RoundedCornerShape`
there is a circular corner beside continuous ones. **Build it inside the composable,
never in a file-level `val`**: `miuix-material` and `miuix-squircle` are Java 21
bytecode while the unit tests run a JVM 17, and a top-level initializer makes the
screen's `…Kt` class fail with `UnsupportedClassVersionError` for every test that loads
it — `ChatNotice`'s local is the pattern.

**The exceptions, which are not violations:**

- **A capsule or circle keeps `RoundedCornerShape(percent = 50)`.** The squircle
  shader blends to a *pure circle* once the corner tile passes π/4 of half the
  smaller side (`BLEND_THRESHOLD_RATIO` in `SquircleBackground.kt`), which a 50%
  radius always does — so the squircle would draw the identical capsule through a
  shader and an offscreen layer. The plain shape is the cheaper way to the same
  pixels.
- **The terminal family keeps its own figures** — a 26dp pill bar with 10dp keys, a
  14dp keyboard panel with 7dp keys (§13). It is a family of its own in both schemes;
  its geometry is not the app's chrome.
- **Content rendering keeps its geometry** — Markdown's table corner and 2dp rule,
  the inline code chip's `min(radius, height/2)` clamp, the formula's line box
  (§12.1–§12.3). Those are drawn around text, not chrome.
- **A radius of 2dp or less** is a hairline, not a corner; the squircle's curve is
  sub-pixel there.

The surfaces that are half their own size — a 28dp copy button at 14dp, a 40dp disc
at 20dp — keep that rule regardless of the modifier: the shape does not depend on the
control staying that size (§13).

## Rows, cards, and the one thing a row may not do

A settings page is a sequence of `PiSection`s: a `SmallTitle` and a `Card` of the
library's preference rows (`ArrowPreference`, `SwitchPreference`, `SliderPreference`,
`RadioButtonPreference`, `CheckboxPreference`, the dropdown and spinner). The app has
no row wrapper of its own. `PiSection` carries the metrics — `padding(horizontal =
12.dp).padding(bottom = 12.dp)` — so a page does not invent a gutter or space its
cards with `Arrangement.spacedBy`. A `TextField` form is *not* wrapped in a card; it
takes the same padding (§9.2).

- **A row that raises a question is inert; its switch is the control.** The storage
  grants ask every time they flip, so the row does not toggle and the finger reaches
  the switch. A row that asks nothing (backup's switches) stays row-toggling.
- **A popup row is `PiMenuRow`.** It carries the library's popup metrics
  (`body1`/`body2`, 20dp sides) and three semantics a preference row does not name: a
  **choice** (the chosen title in the accent, the ink `DropdownImpl` gives its ticked
  row), an **action**, and a **fact** (no tap, normal ink — a disabled ink makes true
  numbers read as unavailable). The 0.9.4 intrinsic-width crash is fixed in beta01;
  the row stays for the metrics and the semantics (§13, AGENTS.md).
- **A list row never paints its own press; the menu it opens lights it instead.**
  The drawer's rows carry no press indication — the hold answers with the platform
  long-press haptic, and the row whose menu is open is lit with `PiTone.inset()`
  (§13). A press light that follows the finger is right on a button and wrong on a
  row whose gesture opens a panel over itself.

## Dialogs, sheets, and the one message

- **A window is driven by `show`, never by whether it is composed.** Miuix's windows
  and the glass overlay pair animate in *and out* through that flag. Writing
  `if (question != null) PiDialogHost(...)` removes the window with its content
  already gone: the exit plays over an empty panel, `onDismissFinished` never fires,
  and a remembered field carries one answer into the next question. `PiDialogHost`
  and `PiConfirm` take a required `show`; the caller keeps the last value so the
  window draws while it leaves (§13).
- **Glass when the page has a backdrop, a window when it does not.** `PiConfirm` and
  `PiSheet` read `LocalPiBackdrop`: inside a page they raise `GlassDialog` /
  `GlassBottomSheet`; above every page (the first-launch prompts, pi's questions)
  they keep `WindowDialog` / `WindowBottomSheet`, which need no `Scaffold` host. The
  two paths share `PiDialogAnswers`, so a question reads the same wherever it is
  raised. **A custom-content dialog follows the same split** — a rename field, a
  picker, the avatar crop compose `WindowDialog`/`GlassDialog` themselves because the
  confirm shape does not fit their body, but a dialog raised *inside a page* takes the
  glass variant so its material is the bar's; one raised from the drawer (a panel with
  no backdrop) or above every page keeps the window. The glass overlay pair itself is
  the kit's (`PiOverlays`); a screen does not call `GlassDialog` directly.
- **The answers are `PiDialogAnswers`**: dismiss neutral, confirm primary, a
  destructive confirm in the error role. The glass path draws `GlassDialogButton`s;
  the window path keeps the filled `TextButton` pair. A single-answer
  acknowledgement passes `confirm = null` and the dismiss takes the row.
- **A body the reader has to *read* is bounded and scrolling.** `PiDialogHost` bounds
  its body to a share of the window (`heightIn(max = …)` + `verticalScroll`), not a
  fixed dp that a landscape phone cannot fit; the answers stay below it whatever the
  extension sent. A failure that must be read is a dialog; a field's own validation
  error is a line under the box.
- **A sheet's content spends `piSheetBottomInset()`** at its bottom — the panel's own
  edge is the window's, so the last row sits under the gesture bar unless the content
  clears it. On a scrolling body the inset goes *inside* the scroll, so the text
  travels through it and the panel's fill still reaches the window's edge.
- **A picker longer than a few words is a sheet of radio rows with a filter field**,
  not a dropdown: a dropdown's popup is sized for a list, not for a search (§13).
- **A moment is a toast; a condition stays on the page.** "Saved", "Agent restarted"
  is `LocalPiToast.show`; "the agent failed to start", "this folder cannot be read"
  is an in-page line in the error role, because a message that disappears after two
  seconds is the wrong shape for something still true. One message at a time, held
  ~2.6s, never interactive; a second replaces the first rather than queueing.

## State, and what a frame may not cost

- **Collect a state flow with `collectAsStateWithLifecycle()`, not `collectAsState()`.**
  The agent runs in a foreground service and keeps streaming while the UI is stopped;
  `collectAsState` keeps collecting and recomposing the page for every token with the
  screen off, which is battery spent on a frame nobody sees. The lifecycle-aware
  collector pauses below `STARTED` and the `StateFlow` replays its current value on
  resume. Every flow the UI collects is state-derived (`settings`, `conversation`,
  `runtime`, the catalogues, `pendingDialog` — a `.map { … }.distinctUntilChanged()`
  over a `StateFlow`, so it replays too), so none loses an event by pausing.
  **The exception is the toast host**: its message is a *moment* with a wall-clock
  expiry, and gating it would let a stale toast appear on resume; it keeps
  `collectAsState` so the timer runs whether or not the page is visible.
- **A frame-rate value is read in layout or draw, never in composition.** Scroll
  offsets, the drawer's progress, the keyboard inset, an animation progress — a `by`
  unwrap feeding `Modifier.padding/size/alpha` subscribes the whole restart scope to
  every frame. Pass it as `State<T>` or `() -> T` and read it in `layout {}`,
  `offset {}`, `graphicsLayer {}` or `drawBehind {}`. Clamping to a Boolean or a
  discrete step with `derivedStateOf` *is* allowed in composition (the drawer's
  `progress > 0f`), because the answer changes rarely. The root's `imePaddingFloor`
  and the toast's bottom offset are the worked examples.
- **A `remember` key is not a lambda.** `remember(onSelect) { … }` re-runs every
  recomposition because a call-site lambda is a new instance; key on the data and
  catch the callback with `rememberUpdatedState`.
- **A content-sized bar does not `fillMaxWidth()` inside a width cap.**
  `fillMaxWidth` sets the *minimum* width, so a child under `Box(widthIn(max = …))`
  asks for exactly the cap — a two-character prompt drew an 86%-wide bar. Size from
  content means no `fillMaxWidth` on the child; the parent's cap already leaves the
  minimum at zero (AGENTS.md).
- **A continuous animation stops when it is not visible.** A frame loop or a colour
  advance gated by an alpha reads that alpha in *draw* (a delayed lambda is not
  readable in composition); a coroutine gates with `snapshotFlow { … }.first { it }`.
  An animation that runs while its surface is off-screen is pure spin.

## Colour, type, icons

**Four colour families, and no fifth.** A literal `Color(0xFF…)` is legal only in:
`PiKitTheme.kt` (the palette overrides and `PiTone.done`'s two greens), the terminal
family's named constants (`TerminalScreen.kt`, `TerminalKeyboard.kt` — black in both
schemes, its own sixteen colours), the setup screen's `SETUP_*` constants
(`PiKitRoot.kt` — the launcher icon's black and white, fixed because that screen is
dark while the app may be light), and `data/AvatarColor.kt` (the eight hand-picked
disc colours — an avatar is *content*, red in both schemes the way a photograph is,
so it does not follow the light/dark switch; §13). Everywhere else the colour is
`MiuixTheme.colorScheme.*` or a `PiTone` role. A screen that names a role directly
where a semantic exists (`error` instead of `PiTone.error()`) is saying *which
component* rather than *what it means*; a screen that invents a literal is the drift
the rewrite removed (§13). `Color.White`/`Black`/`Transparent` are structural — a
scrim, a knockout — not a fifth family.

**The type scale is the library's fourteen styles** — `title1`–`title4`,
`headline1`–`headline2`, `subtitle`, `main`, `paragraph`, `body1`–`body2`, `button`,
`footnote1`–`footnote2`. The app defines none. No `TextStyle(...)` is constructed and
no raw `.sp` is written, except the documented call-site decisions: the transcript's
`paragraph` line box raised to 26sp so an inline code span's fill does not touch the
line above (§12.1), and the terminal's own banner/key sizes. The text-size preference
is one multiplier on `LocalDensity.fontScale` in `MainActivity`, so every `sp` moves
together; no screen scales its own text.

**Icons are Miuix's**, at the library's 24-unit weight, tinted by the component
drawing them. The rules that survive every container change (§13, §12.3):

- **One glyph deletes** (`MiuixIcons.Delete`) — a conversation, a profile, a model, a
  saved key, a workspace: anything the app *stores*. **`Close` takes something off
  this screen** — a filter's clear, the selection bar's exit, a staged attachment
  never sent. A bin on a ✕'s act says something was deleted when nothing was.
- **Controls of one rank in one slot share one ink.** Miuix's two muted inks are a
  full opacity step apart (`onSurfaceVariantSummary` 60%, `onSurfaceVariantActions`
  40%); mixing them in one action slot reads as one live control and one disabled.
  The rank decides the ink; a genuinely unavailable control says so with `enabled`.
- **A borrowed glyph's size is a call-site decision** (⌘ at 17dp where the library's
  are 21dp); its geometry is never edited here. The four chosen glyphs (model,
  thinking, ⌘, copy/check) and the two hand-drawn *navigation* marks (the terminal's
  chevron-underscore, the drawer's handle) are the documented exceptions — do not
  redraw them, and do not hand-draw a new icon (AGENTS.md, *Hard constraints*).
- **An icon-only control names itself.** A `GlassIconButton`/`IconButton` with no
  text beside it passes a `contentDescription` from the catalog (the back arrow does);
  `null` is for a decorative icon inside a control that is already labelled. The
  transcript is a `liveRegion` so a streamed answer is announced once it stops
  growing.

## Motion

Motion is the library's, except the one page-level move every page spells out — the
root's cross-fade-with-travel, the settings stack's forward/back slide, the files
page's folder transition — and those come from `PiMotion`, built on the library's own
`folmeSpring` and `SinOutEasing`. **There is no app-level spring scale**: a page that
adds a spring of its own moves differently the next time the library does (§13). The
transcript carries **no `animateItem`** — it replays the appearance animation every
time a row scrolls back into view, which flickered the list and fought the follow
rule (§7.3); its absence is a fix, not an omission.

## The checkers that hold the line

Prose nobody runs is the weakest form of the rule (AGENTS.md). The UI rules a machine
can see are checked by `tools/build-apks.py`'s `checks` list:

- `check-haptics.py` — the platform's haptic API appears in `PiHaptics.kt` and nowhere
  else, and the kit declares exactly the six documented moments.
- `check-locales.py` — the terminal banners and the catalogs stay in step.
- `check-ui.py` — the colour families stay closed (no `Color(0x…)` or named hue
  outside the four files that own one), the bar is composed only in `PiPage`, and the
  glass overlay pair (`GlassDialog`/`GlassBottomSheet`) and `glassPanel` are called
  only by the kit (`PiOverlays`/`PiKitTheme`) — a screen reaches them through
  `PiConfirm`/`PiSheet`/`PiGlass`, never directly.

A new rule that a regex can hold lands in `check-ui.py` and in that list in the same
change; a rule that needs judgement stays here and in the chapter that owns it.
