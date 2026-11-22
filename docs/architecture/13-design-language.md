# The interface, drawn by Miuix

Miuix is the whole of this app's interface: the palette, the type scale, the shapes,
the motion, the preference rows, the dialogs and the sheets. This chapter is the rule
for what a page may look like, and the reason each rule is there. The code it
describes is `app/src/main/java/pi/kit/mob/ui/kit/` — the theme, the page, the
section, the menu row and the window-level overlays — plus the screens that compose
it. The practical checklist a change runs against is [docs/UI.md](../UI.md): the
vocabulary, the red lines and the do-this-not-that, each citing the section below
that carries its reasoning.

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
`compose-miuix-ui/miuix` 0.9.5-beta03, a Compose Multiplatform component library drawing the
MIUI/HyperOS idiom. Its theme engine ships the two palettes and derives a third
from a seed colour if asked; its preference components are the rows a settings page
is made of; its window dialogs,
sheets, snackbars, icons and navigation are the furniture. What is left for this app
is *no colour decision at all* — the palette is the library's, both schemes of it —
and a small set of compositions the library does not have: the page frame, the
section, the popup row, the avatar circle, the window-level dialog phrasing, the
toast host, and the content rendering (Markdown, formulas, the terminal).

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
what fixes `compileSdk` 37.2 either: that floor is the Compose artifacts' own, and it
stayed when the dependency left.

The cost is real and accepted: the app no longer decides what a Miuix component looks
like, and Miuix is an experimental library — a bump can move the interface. That is
the trade: one coherent idiom owned by a library, against a hand-kept design system
maintained alongside every screen.

## OS4 glass: the material the library draws, and the page it samples

Miuix 0.9.5-beta03 is the release this project builds against — the third beta of
`miuix-glass`, the OS4 material (beta01 was the first). The app's chrome — the page
bar and its action buttons, the chat header's capsules, the composer and its chips,
the jump-to-latest disc, and a dialog or sheet raised from a page — is drawn from it.
The bar's actions are `GlassIconButton`s with the 28dp glass glyph set, not basic
`IconButton`s: the bar carries a button into its material transition only when the
button is the glass control, and a filled basic glyph beside a stroked one is two
icon sets on one bar. A settings card is still a Miuix `Card`: OS4's glass is a
material for controls that float over the page, not for every rectangle on it.

**The bar's actions carry a 4dp gap, and it is `PiPage`'s number.** The library wraps
the `actions` slot in a `Row(horizontalArrangement = Arrangement.End)` and adds nothing
else, so two `GlassIconButton`s at the bar's 44dp size are laid edge to edge. Measured on
the files page at 420dpi, the two glyph centres were 116px apart against a 115.5px
button — a zero gap, which reads as one control carrying two icons rather than as two
controls, and puts "上一级目录" a thumb-width from "主目录" with no gutter between the
targets. The fix is a `Row(Arrangement.spacedBy(4.dp))` inside `PiPage`, in the one place
every page's bar is composed: a gap written at a call site is a gap the next page does
not have, and the two call sites (`FilesScreen`, `TerminalScreen`) both measured 127px
after the change. 4dp rather than the 12dp page gutter, because a bar is a row of
controls that belong together — a gap that wide reads as two groups and pushes the
trailing button past where `ActionIconPadding` puts it.

**The backdrop belongs to the page, not to the window.** `PiPage` creates the page's
`LayerBackdrop` with `rememberPiBackdrop()` and draws only its **body** into it; the
`GlassTopAppBar` is composed in the Scaffold's `topBar` slot — outside the body — and
receives that same value, so a card scrolling under the bar is what the material blurs.
The bar itself is deliberately *outside* the layer: a control inside it would sample its
own blurred output. **The failure is not only a visual one.** The first build put the
whole chat page — transcript, header and composer — in one `layerBackdrop`; the glass
controls then sampled their own recording, and `RenderNode::prepareTreeImpl` recursed
until the RenderThread stack overflowed (`SIGSEGV`, `SEGV_ACCERR`, Android reporting
`stack pointer is not in a rw map; likely due to stack overflow`) and the process died
before its first frame. The transcript is the layer; every control that samples it is a
sibling. A single window-wide layer would have put the bars inside their own sample.

**`PiGlass.panel(shape)` is the one surface a floating control is cut from, and its
material is the popup glass at a floating-control blur.** It calls `glassPanel` with
the page's `LocalPiBackdrop`, `PiGlass.material()` — `GlassMaterials.popupViewGlass`
at 28dp, the library's own stronger white mix — the OS4 small edge stroke, and an
opaque fallback; `PiGlass.fill()` remains that fallback and is what `piGlass()` uses
when no backdrop exists. The material is load-bearing on a flat page: `glassPanel`
with no material draws the blurred backdrop alone, which over an empty page is the page
itself. Measured on the empty light conversation, `puredThinGlass` drew the header
capsules, chips and composer frame at rgb(230) against a rgb(240) page — *darker* than
the page, so each control read as a hole rather than as an object; the popup material
at 28dp brings the same controls to rgb(249), a nine-step lift, and the populated
conversation's text shows through the same body. On the drawer's `#F7F7F7` panel the
settings disc lands at rgb(251), only a 4-step fill, which is why it carries Miuix's
`GlassShadows.Float` for its edge. Before any material was added the leading
`GlassIconButton` sampled rgb(239) while the trailing `PiGlass.panel` sampled rgb(230)
beside it; both ends of one row now share one material. The old `PiGlass` KDoc
said a real backdrop blur was impossible because Compose has no backdrop filter — that
was true of the library the app shipped against, and `miuix-blur` is what replaced it.

**The Android 8 floor is kept by a deliberate override.** `miuix-blur` declares
`minSdk 33` because `android.graphics.RuntimeShader` starts there. The manifest
overrides that library (`tools:overrideLibrary="top.yukonga.miuix.kmp.blur"`) and
every glass call site is gated by `isRuntimeShaderSupported()`; below API 33
`rememberPiBackdrop()` returns null and `PiGlass.panel` draws `fill()` instead. The app still
installs on Android 8.0, and a device below 33 gets the old flat material rather than
a crash. The gate is the contract: a new glass call site that does not pass through
`PiGlass` or `rememberPiBackdrop` is the way this breaks.

## The palette: the library's own, with a page step

`PiKitMiuixTheme(settings)` is the one place the theme is assembled. It decides the
light/dark mode and nothing else about colour:

| Preference | What it becomes |
| --- | --- |
| `ThemeMode.SYSTEM` | `ColorSchemeMode.System` with `isDark = null` — the library reads the platform's setting |
| `ThemeMode.LIGHT` / `DARK` | the same mode with `isDark = false` / `true` — one controller, one override |

The controller's dark scheme is Miuix's `darkColorScheme()` untouched. The light
scheme keeps the library's blue `#3482FF` primary, `#E0E0E0` divider, disabled pairs and
slider track; the page's two groups are set explicitly:

- `surface` — `#F0F0F0`, the page behind the cards;
- `background` and `surfaceContainer` — `#F7F7F7`, one colour for the history ground and
  the answer cards;
- `surfaceContainerHighest` — white, the floating controls;
- `secondaryContainer` — `#E8E8E8`, so fields stay a step below the card group;
- `secondaryVariant` — `#E6E6E6`, the fill of every secondary control.

**A filled secondary control had to move with the page, and its absence was the bug.**
Miuix fills a secondary `Button` and `TextButton` with `secondaryVariant`, which its own
light scheme sets to `#F0F0F0` — one step below *its* `#F7F7F7` page. This app's page is
`#F0F0F0`, so the library's control fill and the page became the same colour: the model
settings' **new configuration** row drew its label on the page itself, and a measured
screenshot showed a word with no button around it. It was not one call site. The model
form's **Cancel** and **Fetch**, a chat preset's **Restore**, and the dialogs' answer pair
whose panel is the card group all took the same default — the last of those a faint wash
on `#F7F7F7` rather than a control. `#E6E6E6` is the library's own `secondary` grey, so the
palette gains no colour it did not have; it sits 10 steps below the page and 17 below the
card, and the scheme's `onSecondaryVariant` ink (`#303030`) is 10:1 on it. The disabled
fill moves with it and to the same value, because the library's `#F2F2F2` is *lighter* than
the page — a disabled button would have vanished in the one state that most needs a shape;
a disabled secondary control keeps its rectangle and fades only its ink. The dark scheme
needs none of this: its `secondaryVariant` (`#434343`) is plainly above its black page.

**Why the light palette is overridden: it has no two-group step.** `background`,
`surfaceVariant` and `surfaceContainer` are all `#FFFFFF` in `lightColorScheme()`, and
its `surfaceContainerHighest` is `#E8E8E8` — a grey, not a brighter white. The dark
scheme has the two groups naturally: `#242424` for the history ground and the answer
cards, `#2D2D2D` for the floating controls. The light scheme mirrors that with
`#F7F7F7` for the card/history group and `#FFFFFF` for the controls, over a `#F0F0F0`
page; the floating buttons are the brighter white group rather than grey.

A page assembled from the library's components is still drawn in the colours those
components were drawn for; the page's two groups are the price of removing the shadows
without losing the layers.

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
transcript, the terminal banner, the dialogs — moves together. That multiplier is
the row's step times the ladder's own base (`TEXT_SCALE_BASELINE`, 0.95), so the
row's 100% — the default — draws 0.95 of the library's sizes: the size this app's
text is designed at, and the size the ladder used to call 95%. The library needs to
know nothing about it.

## A page is a scaffold, a bar and a one-line subtitle

`PiPage(title, subtitle, onBack, actions, overlay, body)` is the frame every
destination and every settings page uses. It is a Miuix `Scaffold` with a pinned
`GlassTopAppBar`, a `BackHandler` that exists only when `onBack` does (a page with no parent
has no back arrow and does not swallow the system gesture), and a body that receives the
scaffold's `PaddingValues` with the navigation strip taken out of it — see *The window's
last pixels* below. The `overlay` slot is the page's window home: it is inside
`LocalPiBackdrop` but outside the recorded body, so a sheet or dialog composed there can
sample the page without entering the layer that records it. A window written after the
`PiPage` call is outside the provider and silently takes the opaque platform path. The
`floating` slot this signature used to take was passed by no caller and is gone, and the
`bottomBarColor` that briefly followed it is gone with the strip it painted.

The subtitle is drawn by the kit, not passed to the bar, and that is a measured rule:
the library's top bars render their `subtitle` with **no line limit**, so a sentence wider
than the space beside the action icons wraps and the bar — and every page's content
rectangle with it — grows by a line. The terminal page measured it: its English idle
sentence wraps on a phone where the Chinese one does not. The kit draws the subtitle
itself, one line with an ellipsis, and distinguishes `null` ("this page has no
subtitle") from `""` ("reserve the line while the value is being read") — the files
page's item count is null while a folder is listed, and a line that appeared and
disappeared once per folder moved the whole page by its height twice.

`PiPage` draws that one line in the bar's **`bottomContent`**, which Miuix includes in
the fade's coverage. In 0.9.5-beta03 the progressive band is still measured as
`(topInset + CollapsedHeight + bottomContentHeight + 20dp) × 1.2` (re-read from the
library's `GlassTopAppBar` measure pass after the bump). Leaving the subtitle
as a sibling in the `topBar` slot gave pages the same absolute band height, not the
same fade past the header: `Scaffold` included the line in the body's top inset, but
Miuix did not include it in the band. On the 420dpi personalization page the subtitle
stayed at y=211–260, but the first card's top jumped from gray level 2 to 35 in one
pixel (y=259 to 260). Including the line in `bottomContent` spreads that same change
over the fade (16 to 19 on those rows, reaching the card's level of 36 by y=270).
The resting layout and subtitle bounds were unchanged; the subtitle is foreground,
outside the recorded body. The settings root has no bottom content and retains the
compact band's geometry. A previous capture had a two-level step between y=190 and
191; that is inside the band, not its measured outer edge, and did not recur in the
comparison at the same density.

The overhang is intentional. The [Miuix documentation](https://github.com/compose-miuix-ui/miuix/blob/v0.9.5-beta03/docs/zh_CN/components/glass/glasstopappbar.md)
and the [Mishka migration guide](https://github.com/YuKongA/Mishka/blob/main/docs/miuix-glass-migration.md)
both say not to clip the bar's parent: the fading tail and the buttons' shadows
extend beyond its measured height. Both examples give the header inset to the
scroller as **content padding**, not viewport padding. PiKit used to put its header
inset outside `verticalScroll`; the glass sampled a blank strip under the title,
while its tail blurred the abruptly clipped section text below. At 420dpi the first
heading on the settings root could be cut to y=211–224 while its visible fragment
was still blurred. The root has no subtitle, so the discrepancy stood out there.
`settingsPageScroll` keeps the original resting gap but allows the rows to travel
behind the title. At the same density, the heading still spans y=243–292 at rest
and reaches y=170–219 on a short scroll. On the personalization page, the subtitle
stays at y=211–260 while the first section heading can move above it. A parent clip
only hides the tail and leaves the wrong viewport in place.

The manual and files have an inner scroller below fixed controls, not a full-page
scroll. The manual's contents label stayed at y=292–306 when jumping to a chapter, but
the top bar's band blurred it anyway after the jump. These pages pass
`contentScrollsUnderBar = false`: the bar's buttons still respond to the scroller, but
the background band (including the gradient fallback) stays off because no moving
content can pass behind the header. The files page leaves the listing's top edge alone:
its toolbar and location bar are opaque furniture, so there is no edge for a fade to
hide and a blur band would only soften the first rows of a list that starts below them.
The manual's contents bar is likewise opaque, and the band only blurred the first lines
of the chapter a jump had just landed on.

### The window's last pixels are a page's, not the system's

The app asks for edge-to-edge with **no scrim of the platform's own**:
`enableEdgeToEdge` is given transparent scrims for the navigation bar and
`window.isNavigationBarContrastEnforced` is cleared, because the alternative is a band the
app did not paint — a white one under a light page is the "not immersive" strip, and the
same enforcement under the terminal's black page is a light band under a black screen. The
navigation strip is then whatever the page's own bottom edge is, and the bar icons' ink is
the theme's (`PiKitMiuixTheme`): light icons on a dark palette, dark icons on a light one.

A page's content does not reserve that strip, and the strip is not painted by the scaffold.
`PiPage` hands its body the scaffold's start, top and end and **zero** bottom, and provides
the strip's height as `LocalPiNavigationInset`; a scrolling page spends it as padding at the
end of its content, where the last row can still be scrolled clear of the gesture handle and
the rows above it pass *under* the handle. Reserving it as a body padding was the opaque
band this section used to describe: the viewport ended at the strip's top edge, the
scaffold's `surface` filled the strip, and in the dark scheme that was a black band cutting
the last card of the page in two. The inset belongs to the
content because the content is what moves; the viewport is the window.

The terminal is the one page whose bottom edge is not the app's `surface`: its shell, key
bar and banner are black in both schemes. It paints the strip as padding *inside* the key
bar's own black — background first, then `BAR_PADDING + LocalPiNavigationInset` — so the
black reaches the window's last pixel and the keys stay above the handle. It also raises
`DarkNavigationBar()`, which flips the `isAppearanceLightNavigationBars` flag for as long as
the page is composed. The flag is a composition (`LocalDarkNavigationBars` holds it, the
theme's `SideEffect` reads it) rather than an imperative write into the window controller,
because a theme change while the page is open must not leave the icons and the strip
disagreeing. The status bar is deliberately not included: the top bar keeps its own surface,
which is what puts the terminal's header in the app's light chrome and its body in the
shell's black.

The setup screen is the one surface that needs both halves. It is returned before the root
Scaffold and painted `SETUP_BACKGROUND` (`#0B0E13`) in both schemes, so the light theme's
own answer — dark status-bar ink — disappeared on it, and the gesture handle was drawn in
the app's dark ink on near-black. `LightStatusBar()` is the status-bar twin of
`DarkNavigationBar()`: it raises `LocalLightStatusBars`, the theme's `SideEffect` combines
it into `isAppearanceLightStatusBars`, and the setup screens call it beside
`DarkNavigationBar()`. The two flags stay separate because the terminal needs only the
navigation half.

The conversation drawer is not a page and does not use `PiPage`; its panel reaches the
window's last pixel too, and the list and the Settings pill spend the strip as their own
bottom padding (`SessionsScreen` reads `WindowInsets.systemBars` unioned with the display
cutout for exactly that). A panel that stopped above the handle was the same band in the
one place where the surface behind it is a different colour.

## Sections, rows, and the one thing a row may not do

A settings page is a sequence of `PiSection`s: a `SmallTitle` and a `Card` of rows.
The rows are the library's preference components — `BasicComponent`,
`ArrowPreference`, `SwitchPreference`, `SliderPreference`, `RadioButtonPreference`,
`CheckboxPreference`, the dropdown and spinner preferences — and the app has no row
wrapper of its own. A value sits in the trailing slot; an arrow means "this row opens
something"; a switch means "this row is the switch".

Two row rules are behaviour rather than taste:

- **A short list of alternatives is a dropdown row, and the length of the list is the rule.**
  `PiDropdownPreference` states the value in effect at the row's end beside the library's
  up/down arrow and opens its alternatives in a panel anchored at the row. It is used directly
  at the call site, like `ArrowPreference` — the row shape is the kit's, not a per-page
  composition. This is the shape the language, the theme, the launcher mark, the chat web
  default and the wire protocol take, and it replaced three shapes that disagreed with each
  other: a dialog of radio rows (the language), radio rows inline on the page (the theme, the
  launcher mark, the web default, which cost a screenful for three to five alternatives), and
  a sheet (the provider, the API type). The inline radio group had a real argument — a set is
  better read at once than one at a time behind a **scrim** — and the dropdown answers it
  rather than ignoring it: the panel opens over the row with no scrim, so the row being
  changed stays in sight and the page below it is not darkened. A list a reader has to
  *search* is still `SettingsPickerSheet` with a filter field, because a dropdown's panel has
  no search: thirty-odd provider ids are a sheet, three alternatives are a row. The library
  ticks when the panel opens and says nothing when a row is picked, so the call site performs
  `PiHaptics.choose()` on the pick — and only when it changes something, because picking the
  value already in effect is a read rather than a choice.

  **The row's panel is glass, which is why the row is the kit's.** Miuix's
  `OverlayDropdownPreference` draws the pre-OS4 `surfaceContainer` rectangle — 16dp corners,
  no blur, no stroke, no shadow — the same panel the menus had. On a page whose bar, cards and
  dialogs are OS4 glass, that is the one opaque rectangle left, and it is raised by the rows a
  reader touches most on a settings page. `PiDropdownPreference` wraps the library's
  `GlassDropdownPreference`, supplies `LocalPiBackdrop` (the row cannot read the local itself,
  and a call site that passed `null` would silently get the opaque fill) and states
  `PiCorner.popup`, so a settings dropdown and a menu are cut the same way. Its `options`
  overload takes a `PiDropdownOption` per alternative and draws the panel as `PiMenuRow`s in
  the library's own `GlassDropdownPopup` — the form for a row whose alternatives each need a
  sentence, which the library's glass *preference* cannot express because it takes bare
  strings. Both forms therefore raise one popup and open one way; the summarised one puts the
  app's rows inside that popup rather than composing a second panel around them.
  `tools/check-ui.py` refuses `OverlayDropdownPreference`, `WindowDropdownPreference` and the
  spinner rows for the same reason it refuses `OverlayListPopup`.
- **A row that raises a question is inert, and its switch is the control.** The
  storage grants ask every time they are turned on or off, so the row does not toggle
  and the finger has to reach the switch. Backup's switches stay row-toggling, because
  nothing there asks.
- **A popup row is `PiMenuRow`, not a `BasicComponent`.** Miuix 0.9.4 sized a popup
  column from its children's intrinsic sizes and the component's end-action budget
  overflowed `Int` at the unbounded width it was asked about — the arithmetic is
  `(available - 8dp) * 6 / 10`, which is negative at `Constraints.Infinity`, and the
  crash is `maxWidth must be >= than minWidth`. It was reproduced twice: on the
  terminal's session roster, and on the composer's `+` menu, whose rows carry a
  *leading* mark and hit the same branch. **0.9.5-beta01 fixed the measure** by
  branching on `constraints.hasBoundedWidth`, so the crash is no longer the reason;
  `ui/kit/PiMenuRow` remains the popup row because it takes the library's own
  popup-mode metrics — `body1`/`body2`, 20dp at the sides, 44dp minimum, the 24dp mark
  cell, the press fill at `GlassPopupDefaults.ItemPressRadius` — so a menu of these sits
  beside a menu of `DropdownImpl`s without a seam, and because a popup row has three
  semantics the preference component does not name. It draws three kinds of row, and
  the difference is the semantics rather than the shape: a **choice** (the chosen
  member's title in the accent, which is the ink `DropdownImpl` gives its ticked row,
  so the terminal's roster and the composer's model list say "this one" the same way),
  an **action**, and a **fact** — a row with no tap at all, drawn in normal ink because
  the library's disabled ink makes true numbers read as unavailable choices. The
  context report is the one menu of facts: a page of readings drawn in the colour of a
  disabled control reads as readings that are not available. The choice haptic is the
  row's own — a tap that lands on a row which is not the current answer performs
  `haptics.choose()`, and a tap on the ticked row is silent — so a menu's handler never
  buzzes, which is the *Never twice* rule read from the menu's side.
- **A menu uses the library's glass material, with motion appropriate to its trigger.**
  Miuix's `OverlayListPopup` and the `OverlayDropdownPopup` built on it draw the
  **pre-OS4** panel: `surfaceContainer`, 16dp corners, no blur, no stroke, no shadow.
  Beside a bar, capsules and dialogs that are all OS4 glass, a menu was the one opaque
  rectangle on the page — the most frequently raised control and the only one not in
  the material. Menus use the library's glass surface at `PiCorner.popup` (24dp; the
  popup family is cut one step larger than the 20dp cards, because the library's own
  popup surface is) and `GlassPopupDefaults.MaxHeight` (416dp, content scrolling
  inside), reached through `ui/kit/PiPopupMenu`.

  The panel is drawn by a host the page provides, not at the trigger, and that is the
  same division `OverlayListPopup` made with the root `Scaffold`'s popup host. Two
  reasons: a panel has to be measured against the **window**, and it samples the page's
  backdrop, so it cannot be inside the recording that holds the trigger. History put
  its host beside the list rather than around it; the in-place fallback received the
  row's unbounded height and crashed in `GlassPopupSurface` with
  `Size(853 x 2147483647) is out of range`. There is no in-place fallback now.
  `PiPopupHostProvider` records the scene in an outer layer and renders the menu as its
  sibling. Bar, composer and drawer controls still sample their inner body or transcript
  layers; none sample the scene that contains them. The drawer and the chat card share
  an outer host, so placement and outside taps cover both. `PiPopupMenu` registers into
  `PiPopupHostProvider` through a `DisposableEffect`, which also keeps the menu owned by
  the composition that raised it — a row that leaves its list takes its menu with it.
  The transcript's card menu is the exception and is registered by the page, because a
  long conversation recycles cards and a menu that vanished mid-read would be unusable.

  Point and row context menus keep Miuix's logical-end alignment and safe-area fit
  test. Its edge reveal started at 15% of the final width, which read as a new object
  growing from a corner instead of actions for the pressed item. The panel now fades
  at full size with only 6dp of travel toward the trigger on exit.

  **A dropdown row's panel is not a menu and does not take that fade.** Its alternatives
  are the row's own value unfolded, so it keeps the library's arc: a flat capsule at 69%
  of the settled width grows out of the value cell, arcs into place on `GlassMotion`'s
  four dropdown springs, and fades its own value out underneath itself. The summarised
  form used to share the menu's anchored panel and fade at full size, which read as no
  animation at all beside the language row's arc one card away — a fade over a panel that
  is already at its final size is not motion. `PiDropdownPopup` is that panel: the
  library's `GlassDropdownPopup` with the app's `PiMenuRow`s inside it, registered into
  `PiPopupHostProvider` like every other popup so it samples the page from outside the
  recording.

  A glass icon button uses the same anchored panel in its `FromAnchor` mode: the panel
  morphs out of the measured button and settles at its end edge, below it when it fits.
  The morph is the kit's own rather than Miuix's `GlassTransformPopup`, because the
  library's popup keeps a full-page dismiss layer composed while its exit spring runs:
  a trigger tapped to close and tapped again before the panel had finished leaving had
  its second tap consumed by that invisible layer, which read as a menu that would not
  reopen for the length of the exit spring. `PiAnchoredMenu` drops the layer the moment
  `show` turns false while the panel keeps drawing, the same rule the context menus
  follow. For the terminal roster, `openBelow = LocalPiBarBottom.current` translates
  the settled rectangle to the measured bar bottom, not the collapsed button geometry;
  files sort is already below that bar. Both register from the page overlay and keep
  their `PiMenuRow` content in the host. The host reads the visibility state directly:
  passing a Boolean through the scaffold slot left the terminal roster closed after
  its button received the click.

  The composer needs an explicit upward destination the library's transform popup did
  not expose. The previous inverted rectangle forced `GlassPopup` upward but
  still seeded the animation at the final panel's far corner. The kit now combines the
  library's `GlassMotion` and `glassSurface` with a viewport above the measured trigger,
  at logical start with an 8dp gap (§7.1). The viewport stays fixed while bounds move.

## Dialogs, sheets and the one message

**A dialog is glass when the page has a backdrop, and a window when it does not.**
`PiConfirm` reads `LocalPiBackdrop`: inside a `PiPage` or the chat page it raises
Miuix's `GlassDialog`, whose material samples the page behind it; above every page —
the first-launch permission prompts — it keeps `WindowDialog`, because there is no
page to sample and no `Scaffold` host yet. The two paths share `PiDialogAnswers`, so a
question reads the same wherever it is raised. A custom body — a language list, an
avatar crop, a workspace picker, a field-bearing failure — goes through the same split
in `PiCustomDialog`; a screen does not compose either window itself. The overlay slot
of `PiPage` (and the chat page's matching provider) is what makes the page path
reachable: a window composed after the page call has no `LocalPiBackdrop` and falls to
the opaque window even though the page is visibly behind it. The window path's cost is
the platform's dialog window around Miuix content; the glass path's cost is that it
needs a page with a backdrop, which is exactly the condition the split names.

**Actions in a glass dialog are Miuix's glass buttons**: the dismiss answer is the
neutral tonal role, the confirm answer is the primary role, and a destructive confirm
uses the error role. The window fallback keeps the older filled `TextButton` pair for
the same reason: it is a window with no glass behind it, and a translucent answer over
an opaque platform panel is neither one material nor the other.

**Sheets are `PiSheet`** (Miuix's `GlassBottomSheet` when a page backdrop exists, its
`WindowBottomSheet` otherwise): scrim, back gesture and downward drag all dismiss, and
the page only owns *which* sheet is open. A picker is a sheet of radio rows with a
filter field, not a dropdown, when its content is longer than a few words — a
dropdown's popup is sized for a list, not for a search. Every sheet body spends
`piSheetBottomInset()` at its bottom — inside the scroll on a scrolling body — because
the panel's bottom edge is the window's: without it the last row sits under the gesture
strip, and the panel's own fill still has to reach the window's edge. A field inside a
glass sheet is `GlassTextField`: beta02's glass field carries the translucent fill for
the material and the state-based API new fields start from; a `TextField` with
`piFieldColors()` is the field for a page or the composer's own frame, not for a sheet.
The composer itself keeps its hand-built frame — its field is transparent inside the
glass panel and `GlassTextField`'s tonal wash would be a second material (see §7.1).

**A window is driven by `show`, never by whether it is composed.** Miuix's window
components and the glass overlay pair animate in *and out* through that flag, so a
call site that raises one by writing `if (question != null) PiDialogHost(...)` removes
it from the hierarchy with the content already gone: the exit plays over an empty
panel, `onDismissFinished` never fires, and the window a reader answers keeps a
remembered field's last answer for the next question. `PiDialogHost` therefore takes a
required `show` and the caller holds the last value — `pendingDialog != null` opens it,
the held copy is what it draws while it leaves (`rememberLastPresent` is the kit's
helper for exactly that hold). The sessions page's three dialogs and
the personalization page's avatar sheet were the same bug and now follow the same rule.

**Transient messages are the app's floating capsule**, raised through `LocalPiToast`. It is
the same fill as a bar's controls — a `PiGlass.fill()` pill on the window's top layer —
placed above the composer rather than over it. Three other implementations were tried:
the platform's `Toast` twice, whose card in the system's colours with the launcher icon
beside the text reads as a notification that landed on the page, and Miuix's snackbar
once, which is a full-width bar pinned to the window's bottom edge where the composer
already lives. Both read as something that arrived over the page rather than as one of
its floating objects. The host is remembered once above every page, so the message
survives the page that raised it; its bottom anchor is the chat composer's measured
band, which is why a keyboard that is up lifts the capsule with it instead of covering
it. A second message replaces the first rather than queueing behind it — an ephemeral
message that arrives five seconds late is about a state the reader has already left.

What a toast settles is the split against an in-page notice, by tense — a
*moment* ("Saved", "Agent restarted") is a toast, a *condition* ("the agent failed to
start", "this folder cannot be read") stays on the page in the error role, because a
message that disappears after a couple of seconds is the wrong shape for something that
is still true. A failure the reader has to *read* is a dialog with a bounded, scrolling
body (the maintenance runs, the model catalogue's discovery problems); a field's own
validation error is a line under the box. One message at a time, held for a couple of
seconds and never interactive, is the queue's whole shape.

## Navigation

The app has one root page — the conversation — and everything else is reached from it.
Its top is the app's own arrangement rather than a library bar: two floating capsules. The
leading capsule holds the mark that opens the conversation drawer, and the current mode's
name stands beside it where the app's name used to; the trailing one holds the terminal,
the files and a new conversation in that order. The conversation is what the app is for;
a bar of four equally weighted destinations said otherwise. The **Work / Chat** switch
left the header for the drawer's foot, where it shares the panel's footer with the
settings disc.

**The drawer's foot wears the same kind of object, and the drawer now owns the layer it
samples.** The mode switch and the settings disc at the bottom of the history panel used
to be `PiGlass.fill()` because the drawer is a panel rather than a page and had no
backdrop; `SessionsScreen` now creates one with `rememberPiBackdrop()` over the panel's
`background`, records its heading, search field and list into it, and keeps both controls
outside the layer as siblings. The mode switch is Miuix's `GlassSegmentedTabRow` (the
library owns the glass track, the sliding indicator and the drag) and the settings disc
is a `GlassIconButton` with the library's glass `SettingsOutline` and `GlassShadows.Float`.
The settings control used to be an opaque `surfaceContainer` pill with a word; it is a
disc now, since the mode switch beside it already names the only other thing the footer
does.

**A bar with no bar in it.** The reference drawing the page follows has no strip across
the top: the content scrolls *under* the marks, fades out as it reaches them, and the
marks are the only objects there. That is three decisions, and each was made
because the strip version was worse:

- **The marks are OS4 glass panels** (`PiGlass.panel` with the small bloom stroke; the
  opaque `PiGlass.fill()` is the fallback when no backdrop is available). The first
  attempt used `PiTone.inset()` — a *wash of the ink*, right for quoted material and wrong
  for a control, because the capsule then read as shading on the page instead of as an
  object above it. The fill was white at an alpha for a while — 80%, then 90%, then 100%
  over the light page, and 14%, 20%, 24% of white over the black one — and the dark half of
  that pair never became opaque, so the same control was a solid object by day and a tinted
  pane by night: a row scrolled under the capsule showed through it, and the page stopped
  reading as one object per theme. **A floating control is filled, not tinted** was the
  rule that replaced it, and the glass material is the other way to obey it: the body is
  the blurred page, not a wash of the surface colour, and its fallback is opaque.
- **The row sits a little lower than a bar's.** The capsules' top edge is 22dp under the
  status bar — a bar's row would start at 0 — because the reference keeps the handle clear
  of the clock's own line. That is also why the leading capsule is a circle and the
  trailing one a pill: two objects at the ends, with the name standing between them on the
  page.
- **Both capsules are the same height, and the height is the mark's box.** 42dp
  (`HEADER_MARK_SIZE`): the circle draws one mark, the pill boxes three, so the pill's
  padding is *horizontal only* — 4dp at each end to keep the marks off its rounded ends. It
  had 2dp above and below as well, which made the pill 46dp against the circle's 42dp, and
  the two ends of one row differing by 4dp does not read as two objects of a kind: measured
  on the 1080 px emulator the circle was 110 px tall and the pill 120 px, now 110 and 110.
  The touch target is the mark's own box — 42dp, two above the library's `IconButton`
  floor of 40dp — and not the platform's 48dp. The drawer's selection controls go one
  step smaller still (36dp with 20dp glyphs), for the width reason further down.
- **A glass panel carries a small floating-control shadow; the opaque fallback does not.**
  `PiGlass.panel` uses a 10-unit shadow rather than the library's `Regular` panel shadow:
  on a 32dp chip the panel radius reads as a halo, and even `Float`'s 24 units is visibly
  wider than the object. The old rule was that a filled surface had no shadow at all,
  because `Modifier.shadow` drew a squircle for every shape (a circle's shadow came out
  with flat corners) and the hand-drawn blur was re-rasterised every frame (measured
  under the composer's field while the keyboard closed, a **48 ms frame**). The OS4
  material's shadow is part of `glassPanel` and is applied to the glass shape itself; on
  the fallback path the fill still carries the separation alone.
- **Over a picture, the capsule is still the capsule.** The picture viewer's chrome is the
  header's object — 42dp, `PiGlass.fill()`, `onSurface` ink, a 22dp glyph — because a floating
  control is one object in this app whatever page it floats over. A dark scrim (38% of black,
  white ink) was tried first, on the argument that a photograph is not a page; it is legible,
  and it is a second family of controls for one screen. Over a white screenshot the capsule's
  edge merges with the picture and the glyph carries the control, which is the same trade the
  header makes over a light page. The full reasoning is §7.3.
- **The transcript runs under the top**, with `headerSpace` as its *content* padding
  rather than as a margin, so the list can still scroll a row into that space — a margin
  would have cut the row off at the bar's edge instead of letting it dissolve.
- **The fade starts at the status bar's bottom edge**, not at the button row's: its solid
  part is exactly the status bar's height and the ramp runs from there, so a row begins to
  dissolve the moment it leaves the clock's line. It is a four-stop ramp
  (`100% → 62% → 26% → 0`): a two-stop one hits its transparent end while the eye still
  expects a gradual departure, so the row crossing it seemed to snap into place. The
  header's glass samples the transcript's own layer; this gradient is still drawn by hand
  rather than by a second rendering of the list, because it also has to cover the area
  beside the capsules where no control samples anything. The settings bar's pinned
  `GlassTopAppBar` band was tried as a replacement and lost: it brings a compact bar's
  strip to a page that has no bar, so the top stops being two capsules over a full-width
  dissolve.

**The bottom of the page is a fade too, and it starts at the field.** The composer is a
band that *floats over* the transcript rather than a row under it: the list's bottom
content padding is the band's measured height, so the end of the conversation still
scrolls clear of it, while a row in the middle of the list passes *behind* the band and
dissolves from the field's top edge down. The chips above the field sit on the transparent
part of the band, with the page's own content visible behind them — a conversation at full
contrast behind a floating chip — which is what the reference's own composer does.

Two consequences follow. The ramp is drawn by the band's own `drawBehind`, anchored at the
field's top edge measured *in the band's coordinates* (`onGloballyPositioned`, not a sum of
heights), and it runs `BAND_FADE` upward from that edge; solid `surface` covers everything
from the edge down, through the band's foot and the inset, so a row never shows through the
strip below the field. And the transcript has no fade of its own any more: the one it had
ended where the band began, which is now above the chips rather than above the field, and
two ramps with a gap between them would have been visible as a seam.

**The agent's state is not on the page at all.** It was a 10sp caption on the window's
bottom edge for a while — the size the reference gives its own "内容由AI生成" hint — and it
is gone: the composer's own placeholder says "Agent 启动失败" when the agent has failed,
the composer's chip row says which model is loading, and a line under everything that
mostly read "Agent 已就绪" was the interface telling the reader that nothing was happening.
The state that matters is the one that is *not* the happy path, and that one already has a
place.

**A toast is part of the page, not a visit from the system.** The app draws the capsule
itself: `PiGlass.fill()`, the fill the bar's marks use, so it reads as one of the page's
floating objects. The platform's `Toast` held the implementation
twice, and Miuix's snackbar once; the first brought its own card in the system's colours
with the launcher icon beside the text, and the second was a full-width bar on the
window's bottom edge. Both read as a notification that had landed on the page. The host
is one piece of the root rather than a framework at every call site, and the interface
(`LocalPiToast.show`) did not change through any of the swaps; a page still asks for a
short sentence.

**The four-tab bar came first, and its costs are what retired it.** A Miuix
`NavigationBar` at the root with one item per destination kept every page one tap away,
and it also spent 48dp of every screen on the same four words for the whole life of the
app, while the chat page drew a second bar of its own above the transcript. The composer
still has to be hoisted above the destination even without it: the root's `AnimatedContent`
removes the page that left, and a draft, the attached images and the transcript's scroll
position would go with it on a look at the terminal — `ChatComposerState` is the scope that
survives, and `rememberSaveable` is not a substitute for it. The terminal and the files are
still one tap from the conversation, from the trailing capsule rather than from a strip.

**The drawer is behind the page, and the page is the thing that moves.** The conversation
list is the second thing a chat app is used for, and the reference screens are unambiguous
about the layer order: the *conversation* is a card that slides right — rounded leading
edge, about 5% smaller — and the panel is what it uncovers. The first version
had the other order (a panel sliding over a dimmed page, with a scrim between them), and
one frame is enough to tell them apart: in one the conversation looks pushed aside, in the
other it looks covered.

So the panel is composed first, the chat card over it, and **both move**: at progress `p`
the panel occupies `[-W + pW, pW]` and the card `[pW, pW + W]`, so their edges meet on every
frame and both halves of the gesture are visible. A panel that only sat still behind the
card was a reveal rather than a slide, which is not what the reference shows. It is reached by the
leading mark or by a rightward drag from anywhere on the page, and both drive one number
(0..1), so a drag and a tap cannot animate differently and a released drag springs back
from where the thumb left it. While the card is out, a transparent target over it takes
the touches: a tap closes the drawer, a leftward drag is the same gesture that closes it,
and the panel beside it keeps its own rows, taps and drag. There is no dim any more, and
the separation is a palette step: the panel is painted `background` — #242424 in the dark
scheme and #F7F7F7 in the light one — under a `surface` card, black in dark and #F0F0F0
in light. A panel painted `surface` in both schemes was the same colour as the card in the
dark one, so the two halves of the screen were divided by a gesture with nothing to see at
the division: the drawer read as one page split by a seam that was not there.

Two consequences of that order are worth naming. The card's corner is set on its
`graphicsLayer` and only while `progress > 0`: at rest the page *is* the window, and a
radius there would cut the screen's own corners. And neither half carries a shadow: the
palette step at the seam is the whole of the depth, in both schemes.

Opening a conversation from the list closes the drawer *before* the page is rebuilt: the
open/closed answer is published when the settle starts, not when it ends, because the
rebuild that follows the tap reads it. That ordering was measured — with the answer
published at the end, the chosen conversation was on screen with the drawer it came from
still out over it, which reads as a tap that did nothing.

**The motion is not the page's, and neither is the panel's composition.** The tap that
opens a conversation is two events in one gesture: the drawer starts to close and
`key(session)` replaces the page behind it. When the offset, the measured width and the
panel's `reachable` flag were `remember`ed inside the page, the rebuild disposed them, so
the close animation was cancelled at full offset while the new page composed at zero — the
drawer vanished between two frames and the chosen row's fill blinked with it. Both now live
in `ConversationDrawerState`, above `key(session)` (`RootContent`), and the settle runs on
the root's scope: the panel slides out over the conversation the tap opened, which is the
motion a list of conversations should have on the way back to one, and the row that was tapped is
drawn filled on the new page's first frame. Publishing the *answer* early and keeping the
*motion* alive are two different owners, and both are needed.

**A list row never paints its own press; the menu it opens lights it instead.** The fill a
row carries says *which row is in question*, and this list has two causes for that — the
conversation the page behind the drawer is showing, and the row whose action menu is open.
One wash answers both (`PiTone.inset()`), because a list has one answer to "which
conversation" and two shades of it would be two highlights. The library's press indication
is the other kind of mark: a light that follows the finger, which is right on a button and
wrong on a row whose gesture *opens a panel over itself*. Measured on the medium phone at
density 2.625, holding a row until its menu opened, in the dark scheme: the held row read
rgb(22) against rgb(14) for the rows beside it under the panel's dim, and fell back to 14 as
soon as the long press was accepted — the panel was accompanied by a highlight for part of
its own opening and by none for the rest, which is both wrong and unstable. The row carries
no indication at all now, and the hold answers with the platform's long-press haptic, the
same answer the transcript settled on (§7.3). What is lit instead is the row itself, for as
long as its menu is open: measured in the light scheme under the same dim, that row reads
rgb(160) where its neighbours read rgb(178), which is `inset`'s 10% of the ink with the
scrim over it.

The drawer's travel is kept in pixels, so a window that changes size has to re-anchor it:
after one rotation an open drawer kept the portrait width and drew about 170px off the
leading edge, cutting its own title in half. The size callback now re-derives the offset
from the *answer* (open or closed) rather than from the old number, and the panel's width
is capped at 420dp, so a tablet shows a drawer and not a second page.

Two marks are drawn for this app rather than borrowed, because Miuix's set has neither:
the terminal's (a chevron and an underscore) and the drawer's (a long rule over a short
one, left-aligned, which is the reference's own handle mark). Both are *navigation*
marks — two strokes at the library's line weight on a 24-unit grid, no fill — and neither
is the app's mark, which is the launcher glyph. The footer's settings control is the
library's `Settings` at 20dp inside a 42dp disc at the panel's trailing gutter, opposite
the mode switch: the foot has two ends, and each holds the choice that belongs to it. The
list's end padding is unchanged, so a list scrolled to its last conversation still stops
above both controls.

**Back at the root loses the app, on the second press.** The conversation is the root, so
a back press has nowhere to go, and the platform's left-edge gesture is exactly where a
thumb lands when it means to pull a drawer *out*. Measured on the emulator with gesture
navigation: a left-edge swipe finished the activity and the launcher appeared, which reads
as the app falling to a black screen. The chat page therefore handles back itself: the
first press arms and says so in a toast, a second within two seconds leaves. The drawer's
own handler answers only while the panel is out, and the page's exit handler stands down
while it is: the two are disjoint by `enabled`, so which one the dispatcher runs first
cannot decide what back means. Composing one after the other was the earlier shape, and
it made back depend on whether the panel had been opened before the page was rebuilt —
a press could be swallowed behind a closed drawer, or arm the exit behind an open one.

**One bar, and a page that arrives.** `PiPage` used to have a second, left-aligned
variant for its roots: the title on the page's own gutter, with its back arrow inside a
button that started at 26dp — 38dp from the edge with the button's own padding, against
the 16dp the same arrow had on every settings page. Two pushes, two arrow positions. The
chat page has no bar at all now, so the left-aligned variant had no caller left; it is
gone, and every page's arrow starts where the library puts it.

**The back arrow is a plain glyph until content reaches the bar.** Miuix's
`GlassTopAppBar` drives its buttons' material from `isContentScrolled`; at rest the
button is a glyph with no pill, and once the page has scrolled under the bar the band
and the pill rise together. `PiPage` uses both logical scroll (`canScrollBackward`) and
actual upward elastic translation from `settingsPageScroll`. At 100% font size on the
1080 px emulator, Personalization fit the window. A held upward drag moved the first
card up by 383 px and put its unblurred summary behind the title, even though the scroll
position stayed at zero. The band must respond to the rendered position, including the
spring-back frames, not just to consumed scroll. Downward pulls at the top do not put
content behind the bar. An earlier nested-scroll accumulator was rejected because it
could leave the arrow floated after the spring returned. Letting the library default to
`true` floated every arrow on the first frame. The terminal keeps its plain arrow: its
native scrollback never travels behind the page bar.

A page that arrives is also *seen* arriving: the destinations cross-fade with a tenth of
the width of travel, from the trailing edge on the way in and from the leading edge on the
way back. The fade alone read as a redraw rather than as a move, which is what "tapping
terminal does nothing" looks like when the page does change.

The settings stack keeps its own model: a sealed `SettingsPage` with a `parent`, a
`rememberSaveable` encode/decode, and a per-page `PiPage(onBack)` rather than one
handler above the stack. It survives process death, which a single "current page"
variable does not.

**A row's dot is a state, and the two states are different facts.** The drawer's dot used
to mean "this conversation has a live process", which is a fact about memory rather than
about the conversation, and it appeared on every conversation that had ever been opened.
It now says one of two things: **answering** (the accent, while a turn streams) or
**answered while you were elsewhere** (a green, cleared the moment the conversation becomes
the one on screen). A turn that ends on the conversation being read marks nothing: the
reader watched it arrive.

**The drawer's heading is the name, the count, and three controls.** It used to break a
mixed list with a "Recent" heading under the pinned shelf; the workspace headings already
say where a run of rows came from, and "Recent" was the only label in the panel that
described nothing — the list is all recent. The selection controls are 36dp with 20dp
glyphs rather than the library's 40dp: three full-size icon buttons beside the app's name
were wider than the name, and the panel's heading is not the place to spend that.

## Shapes, motion, icons

**One corner above the library's.** The library's own radius is 16dp on every card,
field and button, and the app draws one step larger: `PiCorner.radius` (20dp) is
passed at every `Card`, `Button`, `TextButton` and `TextField` call site, so the
whole interface is cut with the same corner and no surface is one radius while its
neighbour is another. It is a single number and not a scale on purpose: a second
step would need a rule for which surface is which, and there is no such rule to
state. The surfaces that are not cut with it are the ones drawn by their own geometry:
a copy button and the composer's two discs are half their own size — 14dp on a 28dp
button, `cornerRadius = 20.dp` on a 40dp disc — so the shape does not depend on the
control staying that size, and an inline code chip clamps to `min(radius, height / 2)`
where a tall box would otherwise cut into its text. The terminal is a family of its
own and keeps the library's smaller figure throughout: its key bar is a 26dp pill with
10dp keys on a 32dp cap, and the on-screen keyboard a 14dp panel with 7dp keys on 34dp.
There is no theme-level shape local in Miuix 0.9.5-beta03 to hang this on, which is
why it is per-call-site.

**A hand-drawn rectangular surface is cut with the squircle modifiers, not
`RoundedCornerShape`.** Miuix's cards, fields and buttons are squircles drawn by the
library's own `miuix-squircle`; a hand-drawn fill or clip at a dp radius with
`RoundedCornerShape` is a circular corner beside a continuous one — same radius, two
curves. The library ships `squircleBackground` (fill only, no offscreen layer),
`squircleClip` (one offscreen layer) and `squircleSurface` (fill + clip for a clickable
surface); the drawer row, the message-menu row and the avatar crop frame use them.
Where a `Shape` is passed instead — `graphicsLayer { shape = … }` or a Miuix `Surface`'s
`shape` — the continuous shape is `GlassShape(radius)`, which is what the drawer card
and the page notice strip use. A capsule keeps `RoundedCornerShape(percent = 50)`: the
squircle shader blends to a pure circle at that ratio (`BLEND_THRESHOLD_RATIO` in
`SquircleBackground.kt`), so the plain shape is the same pixels without a shader and an
offscreen layer. The terminal's own figures (above) and content rendering (tables, code
chips, the formula line box) are the other exceptions.

**Motion is the library's**, except for the one page-level move every page has to
spell out (the root's destinations, the settings stack's forward/back slide, the files
page's folder transition). Those moves are defined in `PiMotion`, from the library's
own `folmeSpring` and `SinOutEasing`: the root's incoming page travels a short 12% on a
240 ms sine-out tween and the outgoing page fades, while inside a stack both pages move
on a critically damped spring, a quarter in and an eighth out. There is no app-level
spring *scale*: the library's dialogs, sheets, switches, sliders and navigation bar
carry their own animation, and a page that adds a spring of its own is a page that will
move differently the next time the library does.

**Icons are Miuix's**, at the library's 24-unit weight and tinted by the component
drawing them. The exceptions are four glyphs the reader chose at the size the app
draws them — the model mark, the thinking mark, ⌘ and the copy/check pair — which
predate the rewrite and are kept: a glyph chosen that way is not replaced because the
container around it changed. They are drawn through Miuix's `Icon`, from the same
drawables. The app's in-app mark is the wordmark's P, drawn from `ic_pi_mark.xml`; the launcher
icon itself is the full PiKit wordmark.

**One glyph deletes, and only its size and ink are the call site's.** Every control that
removes something the app stores — a conversation, a profile, a model listed in a profile,
a saved search key, a workspace the reader added — draws `MiuixIcons.Delete`. The size is
the slot's: 16dp on a dialog row's trailing action, 18dp on the terminal's session roster
(§10.1), the library's own glyph in a popup row, and the library's row size in a settings
row's `endActions`; the tint is the summary ink wherever the control is a row's trailing
action rather than the row's subject. Before this rule the model list and the search page
drew `Close` for exactly that act, beside a profile row and a session bar that drew the
bin — one kind of act in two vocabularies, and the ✕ read as "close this row" while it
removed an entry from a stored configuration.

**Controls that sit at one level are inked at one level.** A slot holding several
controls of the same rank — the edit and delete pair at a profile row's end is the pair
this was learned on — takes one ink for all of them, because a reader who is told two
buttons are peers reads their weight as availability: the fainter one looks disabled.
Miuix's two muted inks are a full opacity step apart (`onSurfaceVariantSummary` is 60% of
the surface's ink, `onSurfaceVariantActions` 40%), so mixing them inside one slot is not a
subtlety — the pair read as one live control and one that could not be pressed, and the
bin was the faint one. The rank decides the ink and the slot does not: a control that is
the row's subject takes the normal ink, every control beside it in that row's action slot
takes the same muted one, and a genuinely unavailable control says so with `enabled`,
which dims its ink *and* refuses the press, rather than by borrowing a fainter token.

`Close` keeps the other act: **taking something off this screen.** A filter field's clear
(`ClearFieldButton`), the selection bar's exit, a failed tool call's state mark and the
composer's staged attachment — never sent, nothing stored — are ✕s, and a bin on any of
them would say something was deleted when nothing was (§7.1, §7.3).

Two glyph rules survive from the previous interface because they are still true: a
glyph from another set has another weight, so **its size is a call-site decision** (⌘
is drawn at 17dp where the library's glyphs are 21dp); and a glyph that was not drawn
here is not edited here — scaling it at the call site is a layout decision, changing
its geometry is not.

## What the pages do with it

The conversation's three pushed pages, the settings behind the drawer's foot, and the
one decision each makes that the shared vocabulary does not:

- **Chat** — the transcript is the app's densest surface and the one place a `Card`
  per turn is right, because a turn is a subject: a prompt card on the reader's own
  colour at the end edge, an answer card holding that turn's reasoning, tool calls and
  reply. Tool calls are dense rows inside it, a live turn carries `PiTone.live()`, and
  the composer is the page's bottom band on `surface` — the same grey the page is
  painted in, so the foot of the page meets the window's edge at no seam. Inside the
  band the field is a raised white frame (`surfaceContainer` at the kit's corner)
  holding the text **and the four controls**, in the order a thumb meets them: the
  command list and the attach control at the field's left corner, the context
  reading and the send button at its right — a filled disc in the accent while it
  can send. Grouping the two controls that *add something to the message* at the
  field's left is the correction: they are one kind of thing, and the corner a thumb
  rests on is where they belong. The three setting chips above the frame (thinking,
  model, cache) are values drawn in normal ink, and each opens its dropdown menu at
  the control rather than as a bottom sheet, for the reason §7.1 gives: a menu that
  answers one question belongs at the control that asks it. A conversation with
  nothing in it draws the page's title and hint instead of a blank area, because
  the blank is the first thing the app ever shows.
- **Terminal** — a black rectangle with its own sixteen colours, so the chrome stays
  out of the way: a `PiPage` bar with the shell's name and the session roster, and a
  key bar of Miuix buttons on a `surfaceContainer` track. The keys are the same glyph
  weight as everything else, a latched modifier takes `primary`, and the bar scrolls
  horizontally because English labels need more room than Chinese ones. The page opens
  in the conversation's workspace and keeps one shell per directory (see §10.1), so the
  directory the reader sees in the files page is the one the prompt in the terminal
  already stands in.
- **Files** — a directory is a list of objects, and it is drawn as one: a
  `BreadcrumbBar` location trail, a filter field with a sort menu, and a `LazyColumn`
  of Miuix `Card` rows with press feedback and a hold menu (copy path, open with). It
  opens in the conversation's workspace, and `$HOME` is one step up via the `~` crumb. A
  row's glyph is **by what the file is** — folder, link, code, image, archive, video,
  audio, sheet, document — because a listing where every file wears the same sheet of
  paper asks the reader to read names to tell anything apart. The preview is a sheet
  that draws a picture instead of calling it binary, and carries **Copy path** beside
  **Open with**. The toolbar (filter and sort) sits **above** the location trail — the
  control the page is used through leads, and the trail stays directly above the rows
  it describes. The walk is not gated at `$HOME`: the up button and the back gesture go
  to the filesystem's root, because the listing above the home directory is the same
  kind of content and a gate that showed `~/.pi` while refusing its parent was hiding
  its own subject — and above the root the trail is drawn as the absolute path from
  `/`. This page chose cards where the previous interface deliberately had
  none: the Miuix card's press feedback is the row's touch affordance, and a table of
  unframed rows had none.
- **History** — a list of conversations is a list of *questions the reader asked*, so
  a row is the reader's own words, ellipsised to one line, beside when it was and how
  much followed, with selection as a drawn mark and actions on a hold. The pinned group
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
a code block's header and a tool card's output still use the same control.

## What is still the app's, not the library's

- **The transcript's content colours.** Syntax highlighting, diff tints and the
  live/error tones are content, not chrome; they are derived from the scheme where
  they can be and fixed where the terminal requires it.
- **The terminal's sixteen colours.** A terminal with a theme-derived palette is a
  terminal whose output changes meaning with the scheme, and a `ls` colour that means
  "directory" in one theme and something else in the next is a terminal that has to
  be re-read.
- **The formula line box** — the box, the ascent/descent measurement and the
  typesetter are the app's; only their ink and text style come from the theme.
- **The quotation wash.** A code span, a fenced block and an inline chip are filled
  with `PiTone.inset()` — 10% of `onSurface` over whatever surface they are drawn on —
  rather than with a palette role, because the same renderer draws on three different
  backgrounds (the answer card, the manual page, the reader's own prompt card) and no
  single role is a step away from all of them. `secondaryContainer` was the last
  attempt: a fixed #F0F0F0 read as a well on white and disappeared on the #F0F0F0
  manual, and in the dark scheme `surfaceContainerHigh` *is* the card's own #242424.
  A wash of the surface's own ink is the same relative step everywhere, which is what
  "the same depth" has to mean here.
- **Everything drawn inside a card obeys the same rule, and the dark ramp is why.** The
  tool output panel, the reasoning panel, a status pill, the idle send disc, a file-type
  tile and the reader's own bubble all used to name a step of the surface ramp
  (`surfaceContainerHigh`, `surfaceContainerHighest`). Miuix's dark scheme gives
  `surface` black, `surfaceContainer` and `surfaceContainerHigh` **both** #242424 and
  `surfaceContainerHighest` #2D2D2D: each of those panels was a step in the light scheme
  and no step — or a two-point one — in the dark one, so a tool's output ran into the card
  behind it, a status pill's fill vanished and the send disc disappeared into the
  composer's own frame. What replaced them is one sentence: *a fill inside a container is
  a step from its content colour, not a role from the ramp* — `PiTone.inset()`, the
  surface's own ink at 10%, for a well, and `PiTone.prompt()`, the accent mixed into the
  card colour (16% on white, 34% on the dark card), for the reader's own message. The
  first is contrast rather than colour, so it is the same relative step on every surface
  the app draws, in both schemes; the second is a *mix* rather than a translucent wash
  for a measured reason — 14% of the accent over the dark page, which is black, is
  #061122, and a bubble that dark is not blue until it is beside something blacker. Both
  keep `onSurface` legible, which is the property the prompt bubble needed most.
- **The window-level dialog phrasing** (`PiConfirm` and `PiDialogAnswers` in the kit,
  `SettingsAlert` on the settings pages), because Miuix ships the window and not this
  app's two-answer question. `PiDialogAnswers` is the one
  dialog-button row in the app — two filled `TextButton`s of equal weight, the
  destructive one told apart by its label's colour — and every dialog in the app draws
  its answers through it; the transparent-container variant some dialogs used to pass
  was the reason the same control had two weights depending on which page raised it.
- **The focus ring is off.** Miuix's `TextField` animates a 2dp border in
  `colors.borderColor` — `primary` by default — when it takes the focus, and on a page
  whose fields are the only blue rectangles that ring is the loudest thing on screen.
  `piFieldColors()` states the border as the field's own fill so the ring animates from
  the fill to the fill, and `piFieldCursor()` states the caret, because the library
  derives the caret's brush from the border's colour: without the pair, hiding the ring
  hides the caret with it. Every field in the app passes both.
