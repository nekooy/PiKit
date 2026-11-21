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

- `compose-miuix-ui/miuix` **0.9.5-beta02** — the second beta of the OS4 material:
  `GlassTextField`, the animated `GlassNavigationRail`/`GlassTabRow`, the refined
  progressive-blur pipeline and the Compose 1.13 API migration. It targets Compose
  Multiplatform 1.13.0-rc01; this project resolves the stable **Compose 1.13.0** line
  through BOM 2026.11.01, which is API-compatible with the candidate the library was
  compiled against.
- Compose **BOM 2026.11.01** (the 1.13 line), Kotlin **2.4.21**. So: `computedStateOf`
  replaces `derivedStateOf`; `CapturedAnimatedVisibility` is available, but the two
  cross-fades (`PiOverlays`' toast, `ChatPictures`' chrome) keep `AnimatedVisibility`
  because they animate a child that is already composed and the captured variant is
  for a conditional subtree. `TextFieldState` is available; the app's existing
  Miuix `TextField` call sites keep the value overload because the value is screen
  state (`ChatComposerState`'s draft, a settings form's field), and beta02's
  deprecation is a warning rather than a removal — a new field starts from
  `rememberTextFieldState` and the state-based overload. The strong-skipping compiler
  is on, which is why an unstable parameter is skipped by instance equality and the
  app carries no immutable-collections dependency.
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
| A destination or settings page | `PiPage(title, subtitle, onBack, actions, overlay, body)` | a bare `Scaffold`, a second bar variant |
| A titled group of rows | `PiSection` (+ `PiGroupAction` for the group's own action) | a hand-drawn `Card` + heading |
| A value with a few alternatives | `PiDropdownPreference` (or the summarised `options` form) | `OverlayDropdownPreference`, a radio group on the page, a dialog, a sheet |
| A long list of choices to search | `SettingsPickerSheet` | a dropdown (its panel has no filter) |
| A row in a popup menu | `PiMenuRow` (choice / action / fact) | `BasicComponent`, a preference row |
| A popup menu | `PiPopupMenu` + `Modifier.piMenuAnchor(anchor)` | `OverlayListPopup`, `OverlayDropdownPopup`, `GlassIconDropdownMenu`, a `GlassPopup` at the trigger |
| A question with answers | `PiConfirm` (app) / `PiDialogHost` (pi's questions) | a raw `WindowDialog`/`GlassDialog` |
| A question with a custom body | `PiCustomDialog` | composing `WindowDialog` or `GlassDialog` at the screen |
| A field on a glass sheet/dialog | `GlassTextField` + `piFieldCursor()` | a plain `TextField` with `piFieldColors()` |
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
- **The subtitle is drawn below the bar, not in its `bottomContent`.** Both places
  keep it out of the library's own `subtitle` and out of the recorded body, but only
  the first leaves the progressive-blur band alone: the band's height is
  `topInset + CollapsedHeight + bottomContentHeight` scaled by 1.2, and it is a
  rectangle clipped with `RectangleShape`, so its bottom edge is a hard cut in the
  middle of a smooth ramp. With the subtitle in `bottomContent` that edge landed
  across the title block — measured on the personalization page at 420dpi as a
  one-pixel step of 2 levels across 900 of 1080 columns, gone on the settings root,
  whose bar has no `bottomContent`. `PiPage` composes the bar and the subtitle in one
  `Column` inside the `topBar` slot, so the band keeps its no-subtitle height and the
  body's top inset still contains both.
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
- **A scrolling page reports `contentScrolled = scroll.canScrollBackward` to
  `PiPage`.** The bar's back arrow is a plain glyph at rest and becomes a glass pill
  only once the body has scrolled under the bar; Miuix's `isContentScrolled` overload
  falls back to `true` when it has neither a scroll behaviour nor a visual offset, so
  omitting the argument floats every arrow on the first frame. Passing the scroll
  position (not a nested-scroll accumulator) is what keeps a short page that only
  bounces from lighting the arrow.
- **A top-bar action is a `GlassIconButton`, not a basic `IconButton`.** It draws the
  glass icon set at 28dp and inherits the bar's material/scroll state; a plain
  `IconButton` stays flat while the back arrow rises, and a filled basic glyph beside
  a stroked one reads as two icon sets. The files and terminal bars are the two
  examples.
- **Two actions in a bar are spaced by `PiPage`, not by the call site.** The library
  wraps the `actions` slot in a bare `Row(horizontalArrangement = Arrangement.End)`,
  so buttons handed to it sit edge to edge: the files page measured its two glyph
  centres 116px apart against a 115.5px `GlassIconButton` at 420dpi, which is a zero
  gap — one two-icon control rather than two, with "go up" a thumb-width from "go
  home" and no gutter between the targets. `PiPage` wraps the slot in a
  `Row(spacedBy(ACTION_GAP))` at 4dp, the figure the chat header already puts between
  its marks; the same measurement now reads 127px on both the files and terminal bars.
  Never add a `Spacer` or a padding to one call site's buttons — a spacing in the
  callers is a spacing the next page forgets.
- **A page's windows live in `PiPage`'s `overlay` slot.** Compose its sheets,
  confirmations and custom dialogs there, not after the `PiPage(...) { }` call: the
  page's `LocalPiBackdrop` is provided around the slot but the slot is outside the
  recorded body, which is the only place where `PiSheet`/`PiConfirm`/`PiCustomDialog`
  can take the OS4 glass path without sampling their own layer. A window written after
  the call sees a null backdrop and silently becomes an opaque platform window.
- **Moving a window into the slot means moving it, not deleting it.** The one sweep
  that introduced `overlay` emptied six pages' slots instead of filling them —
  `AgentPage`, `MaintenancePage`, `PersonalizationPage`, `SearchPage` and both
  `ModelPages` — and nothing failed to compile: each page kept its `var x by remember`
  and its import, so the only symptom was a row that opened nothing. The workspace
  dialog went with them, which is why a workspace could be added and never removed.
  An empty `overlay = { }` beside a `var … by remember { mutableStateOf(false) }` is
  the signature of this bug, and a `false` written by a row with no window reading it
  is dead state the compiler does not report: grep the page for `overlay = {` and
  confirm the body is non-empty for every flag the page sets, rather than trusting the
  build to be green.

## Glass: the material, and the red lines

The chrome — the page bar, the chat header's capsules, the composer and its chips,
the jump-to-latest disc, a dialog or sheet raised from a page — is OS4 glass. A
settings card is still a Miuix `Card`: glass is a material for controls that *float
over* the page, not for every rectangle on it (§13).

The architecture is two levels, and the page owns the lower one:

- **The page's backdrop** is created by `rememberPiBackdrop()` at the page level and
  records only the page's **body**. `PiPage` puts `layerBackdrop` on the body `Box`
  and composes the bar in the Scaffold's `topBar` slot — *outside* the layer. The
  chat page puts it on the `BoxWithConstraints` that holds the transcript and the
  empty state; the header and composer are siblings. `LocalPiBackdrop` carries the
  value down to the controls.
- **A floating control** is cut from `PiGlass.panel(shape)`, which calls `glassPanel`
  with `PiGlass.material()` — `GlassMaterials.popupViewGlass` at 28dp — the small OS4
  edge stroke, the floating-control shadow, and an **opaque** fallback. The material
  is load-bearing: `glassPanel` with no material draws the blurred backdrop alone,
  which over an empty page is the page itself, and `puredThinGlass` measured rgb(230)
  on this app's rgb(240) empty page — a hole rather than an object — where the popup
  material measures rgb(249). The drawer's settings disc uses Miuix's
  `GlassShadows.Float`; the chat page's smaller capsules keep the chip shadow (§13).

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
   The chat page's transcript layer sits on the `BoxWithConstraints` that holds both
   the `LazyColumn` **and** the empty state: on the list alone, an empty conversation
   had no recording at all and its header capsules and composer chips drew as the page.
3. **The backdrop draws an opaque base first.** `rememberPiBackdrop` does
   `drawRect(base); drawContent()` — `surface` for a page, `background` for the
   drawer — so a subtree that paints no background of its own still gives the glass
   the right colour rather than transparent black.
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
7. **A popup menu is `PiPopupMenu`, never a basic popup panel.** `OverlayListPopup`,
   `OverlayDropdownPopup` and the `Window*` variants draw Miuix's pre-OS4 opaque
   `surfaceContainer` rectangle — 16dp corners, no blur, no stroke, no shadow — and a
   menu is the one floating control a page raises most often. Every menu in the app is
   the library's glass popup (§13, *Menus*); `tools/check-ui.py` refuses the old panels
   and a `GlassPopup` composed anywhere but the kit.
8. **A settings row with alternatives is `PiDropdownPreference`, never
   `OverlayDropdownPreference`.** The library's overlay preference draws the same pre-OS4
   panel as the menus above, from `DropdownDefaults.dropdownColors()`. The kit's row is
   `GlassDropdownPreference` with `LocalPiBackdrop` supplied and `PiCorner.popup`, and
   its `options` overload (per-option summaries, `PiDropdownOption`) is the form for a
   row whose alternatives need a sentence each. `tools/check-ui.py` refuses the
   library's preference rows and spinners too.

## Menus

A menu is a list of rows in a floating panel, opened from the control it belongs to.
All of them go through two kit pieces, and a call site is one of each:

```kotlin
val anchor = rememberPiMenuAnchor()
ControlChip(
    modifier = Modifier.piMenuAnchor(anchor),
    menu = { anchor -> ControlChipMenu(show, anchor, onDismiss) { /* PiMenuRow(…) */ } },
)
```

- **`Modifier.piMenuAnchor(anchor)`** goes on the trigger — the pill, the button, the
  row — and reports its window bounds. Not on a wrapper: the panel's end edge is the
  trigger's end edge.
- **`PiPopupMenu(show, onDismissRequest, anchor, placement) { PiMenuRow(…) }`** is
  written at the same call site and *registers* into `PiPopupHostProvider`, which the
  page provides outside its recorded layer. The host draws the panel full-window, so
  the panel is measured against the window rather than clipped to a chip or a drawer
  row, and it samples the page backdrop without containing itself (red line 1).
- **`placement`** is `Auto` (below if it fits, else above — the library's own test) or
  `Above`. Use `Above` for a band pinned to the bottom of the window: the composer's
  chip row and its toolbar. There the space "below" is the input field, and with the
  keyboard up it is under the keyboard, because the library measures against the window
  and the keyboard shrinks the content rather than the window.
- **A control in the page's bar** passes `openBelow = LocalPiBarHeight.current`. The bar
  is chrome and sits outside the recorded layer, so a panel drawn over it has nothing to
  blur and the bar's title shows through; opening below the bar puts it over the body.
- **The rows are `PiMenuRow`**, at the library's popup metrics — see the next section.
  Do not wrap them in `ListPopupColumn` and do not hand `GlassPopup` a panel of your own.
- **The panel is the library's**, so the corner is `PiCorner.popup` (24dp) and the
  height bound is `GlassPopupDefaults.MaxHeight` (416dp) with the content scrolling
  inside it. A page does not pass a height.

A menu belongs to the composition that raised it: a row that leaves its list takes its
menu with it. The transcript's card menu is the exception — it is registered by the
page, not the card, because a long conversation recycles cards constantly.

**A settings row with alternatives is the same material.** `PiDropdownPreference` is the
row shape for "a value with a few alternatives" — the library's `GlassDropdownPreference`
with the page's backdrop supplied and `PiCorner.popup`, so a settings dropdown's panel and
a menu's are cut the same way. Its `options` overload takes a `PiDropdownOption` per
alternative (a label and a sentence) and draws the panel as `PiMenuRow`s, which is the
form for a row whose alternatives each need explaining. `OverlayDropdownPreference` is
refused by `tools/check-ui.py` for the same reason `OverlayListPopup` is.

**The drawer foot is glass too, and it owns its layer.** The drawer is a panel rather
than a page, so `SessionsScreen` creates its own backdrop with `rememberPiBackdrop()`
and paints the panel's `background` as the base; the mode switch is Miuix's
`GlassSegmentedTabRow` and the settings disc is a `GlassIconButton` with
`GlassShadows.Float` — both sample that layer and both sit outside it as siblings.
`SessionsScreen` also *provides* that backdrop around its windows, so the rename,
delete and mode questions are glass over the drawer rather than opaque windows: a
glass dialog draws in the root Scaffold, so it is outside the layer it samples.
The toast capsule is the one remaining `PiGlass.fill()` control: it is raised on the
window's top layer, above every page, where there is nothing to sample. All of these
are floating objects, not rows.

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

- **A value with a few alternatives is a dropdown row.** Use the kit's
  `PiDropdownPreference` directly at the call site — the language, the theme, the
  launcher mark, the chat web default, the wire protocol. It states the value in effect
  at the row's end with the library's up/down arrow and opens its alternatives in a glass
  panel anchored at the row, with **no scrim** — which is why it is preferred over a radio
  group on the page (five rows for one question) and over a dialog (a scrim over the page
  the choice re-themes). A list a reader has to *search* is `SettingsPickerSheet` instead:
  the panel has no filter field, so thirty-odd provider ids are a sheet and three alternatives
  are a row. The library ticks when the panel opens and is silent on the pick, so the call
  site performs `PiHaptics.choose()` — and only when the pick changes something.
- **A row that raises a question is inert; its switch is the control.** The storage
  grants ask every time they flip, so the row does not toggle and the finger reaches
  the switch. A row that asks nothing (backup's switches) stays row-toggling.
- **A popup row is `PiMenuRow`.** It carries the library's popup metrics
  (`body1`/`body2`, 20dp sides, 44dp minimum, the 24dp mark cell, the press fill at
  `ItemPressRadius`) and three semantics a preference row does not name: a **choice**
  (the chosen title in the accent, the ink `DropdownImpl` gives its ticked row), an
  **action**, and a **fact** (no tap, normal ink — a disabled ink makes true numbers
  read as unavailable). The 0.9.4 intrinsic-width crash is fixed in beta01; the row
  stays for the metrics and the semantics (§13, AGENTS.md).
- **A changed choice buzzes once, and the row does it.** `PiMenuRow` performs
  `haptics.choose()` when a tap lands on a row that is not the current answer, so a
  menu's handler must not buzz as well — that is the double buzz `PiHaptics`' *Never
  twice* forbids, and the library's own radios already perform it. A tap on the ticked
  row is silent, because it changes nothing.
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
  raised. **A custom-content dialog uses `PiCustomDialog`** — it owns the same split
  for a body that is a list, a crop stage, a field or a failure paragraph, and uses
  `PiDialogAnswers(glass = …)` for its buttons. A screen never composes `WindowDialog`
  or `GlassDialog` itself: `check-ui.py` keeps the glass pair inside `PiOverlays`, and
  the wrapper is what prevents a page dialog from falling back to the opaque window.
  The drawer is a case of its own: it has a backdrop, and its windows are siblings of
  the recorded panel, so `SessionsScreen` provides `LocalPiBackdrop` around them and
  the rename, delete and mode questions are glass over the drawer.
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
  not a dropdown: a dropdown's popup is sized for a list, not for a search (§13). Its
  filter and any other field inside a glass sheet are `GlassTextField` with
  `GlassTextFieldDefaults.textFieldColors(borderColor = Color.Transparent)` and
  `piFieldCursor()`: the beta02 field has the translucent fill the material expects,
  while the opaque `secondaryContainer` fill of `piFieldColors()` is for a field on a
  page. A field on the composer's glass frame keeps its transparent-fill `TextField`.
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
  and the toast's bottom offset are the worked examples. One measured exception: the
  composer band's published height feeds the transcript's `contentPadding`, so a
  keyboard animation re-measures the list per inset step; §7.1 records why that publish
  point cannot move (moving it inside `bandBottom` under-counted by 95 px).
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
