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
section, the popup row, the avatar circle, the window-level dialog phrasing, the
snackbar host, and the content rendering (Markdown, formulas, the terminal).

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
transcript, the terminal banner, the dialogs — moves together. That multiplier is
the row's step times the ladder's own base (`TEXT_SCALE_BASELINE`, 0.95), so the
row's 100% — the default — draws 0.95 of the library's sizes: the size this app's
text is designed at, and the size the ladder used to call 95%. The library needs to
know nothing about it.

## A page is a scaffold, a bar and a one-line subtitle

`PiPage(title, subtitle, onBack, actions, body)` is the frame every
destination and every settings page uses. It is a Miuix `Scaffold` with a pinned
`SmallTopAppBar`, a `BackHandler` that exists only when `onBack` does (a page with no parent
has no back arrow and does not swallow the system gesture), and a body that receives the
scaffold's `PaddingValues` with the navigation strip taken out of it — see *The window's
last pixels* below. The `floating` slot this signature used to take was passed by no
caller and is gone, and the `bottomBarColor` that briefly followed it is gone with the strip
it painted.

The subtitle is drawn by the kit, not passed to the bar, and that is a measured rule:
`SmallTopAppBar` renders its `subtitle` with **no line limit**, so a sentence wider
than the space beside the action icons wraps and the bar — and every page's content
rectangle with it — grows by a line. The terminal page measured it: its English idle
sentence wraps on a phone where the Chinese one does not. The kit draws the subtitle
itself, one line with an ellipsis, and distinguishes `null` ("this page has no
subtitle") from `""` ("reserve the line while the value is being read") — the files
page's item count is null while a folder is listed, and a line that appeared and
disappeared once per folder moved the whole page by its height twice.

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

The conversation drawer is not a page and does not use `PiPage`; its panel reaches the
window's last pixel too, and the list and the Settings pill spend the strip as their own
bottom padding (`SessionsScreen` reads `WindowInsets.navigationBars` for exactly that). A
panel that stopped above the handle was the same band in the one place where the surface
behind it is a different colour.

## Sections, rows, and the one thing a row may not do

A settings page is a sequence of `PiSection`s: a `SmallTitle` and a `Card` of rows.
The rows are the library's preference components — `BasicComponent`,
`ArrowPreference`, `SwitchPreference`, `SliderPreference`, `RadioButtonPreference`,
`CheckboxPreference`, the dropdown and spinner preferences — and the app has no row
wrapper of its own. A value sits in the trailing slot; an arrow means "this row opens
something"; a switch means "this row is the switch".

Two row rules are behaviour rather than taste:

- **A row that raises a question is inert, and its switch is the control.** The
  storage grants ask every time they are turned on or off, so the row does not toggle
  and the finger has to reach the switch. Backup's switches stay row-toggling, because
  nothing there asks.
- **A `BasicComponent` with `startAction` or `endActions` must not go inside a
  `ListPopupColumn`.** Miuix 0.9.4 sizes a popup column from its children's intrinsic
  sizes, and the component's end-action budget overflows `Int` at the unbounded width
  it is asked about — the arithmetic is `(available - 8dp) * 6 / 10`, which is
  negative at `Constraints.Infinity`, and the crash is `maxWidth must be >= than
  minWidth`. It was reproduced twice: on the terminal's session roster, and on the
  composer's `+` menu, whose rows carry a *leading* mark and hit the same branch. A
  `BasicComponent` *without* either slot is safe. `ui/kit/PiMenuRow` is the row for a
  popup, marks and all, and it takes the library's own popup-mode metrics —
  `body1`/`body2`, 20dp at the sides — so a menu of these sits beside a menu of
  `DropdownImpl`s without a seam. It draws three kinds of row, and the difference is
  the semantics rather than the shape: a **choice** (the chosen member's title in the
  accent, which is the ink `DropdownImpl` gives its ticked row, so the terminal's
  roster and the composer's model list say "this one" the same way), an **action**,
  and a **fact** — a row with no tap at all, drawn in normal ink because the library's
  disabled ink makes true numbers read as unavailable choices. The context report is
  the one menu of facts: a page of readings drawn in the colour of a disabled control
  reads as readings that are not available.

## Dialogs, sheets and the one message

**Dialogs are window-level.** `PiConfirm` wraps Miuix's `WindowDialog` rather than the
overlay variant, and the reason is this app's shape rather than a
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

**A window is driven by `show`, never by whether it is composed.** Miuix's
`WindowDialog` and `WindowBottomSheet` animate in *and out* through that flag, so a
call site that raises one by writing `if (question != null) PiDialogHost(...)` removes
it from the hierarchy with the content already gone: the exit plays over an empty
panel, `onDismissFinished` never fires, and the window a reader answers keeps a
remembered field's last answer for the next question. `PiDialogHost` therefore takes a
required `show` and the caller holds the last value — `pendingDialog != null` opens it,
the held copy is what it draws while it leaves. The sessions page's three dialogs and
the personalization page's avatar sheet were the same bug and now follow the same rule.

**Transient messages were the platform's toast for one release**, raised through
`LocalPiToast` and drawn by the system. The app drew its own for two generations before
that — a pill, then Miuix's snackbar with a host remembered at the root — and the host was
a small framework of this app's own for one sentence: a `SnackbarHostState`, a scope, a
dismiss-the-previous rule, and a `snackbarHost` slot in the root scaffold. The platform
already queues messages, places them where its own navigation bar and display say, honours
the user's accessibility settings and text scale and per-app notification choice, and on
13+ draws the app's icon beside the text, so the whole of it was removed for
`Toast.makeText(...).show()` behind the same `LocalPiToast` interface. That swap was
reversed for the reason the later paragraph gives — the system's card in the system's
colours with the launcher icon reads as a notification landing on the page — and the app
draws the capsule itself again. The interface outlived both swaps: a page asks for a short
sentence and never learns which framebuffer it lands in.

What the platform toast settled, and what the app's own capsule keeps, is the split against
an in-page notice, by tense — a *moment* ("Saved", "Agent restarted") is a toast, a
*condition* ("the agent failed to start", "this folder cannot be read") stays on the page
in the error role, because a message that disappears after a couple of seconds is the wrong
shape for something that is still true. A failure the reader has to *read* is a dialog with
a bounded, scrolling body (the maintenance runs, the model catalogue's discovery problems);
a field's own validation error is a line under the box. One message at a time, held for a
couple of seconds and never interactive, is the queue's whole shape.

## Navigation

The app has one root page — the conversation — and everything else is reached from it.
Its top is the app's own arrangement rather than a library bar: two floating capsules and
a name between them. The leading capsule holds the mark that opens the conversation
drawer, the trailing one holds the terminal, the files and a new conversation in that
order, and the app's name stands on the page itself instead of in a frame. The
conversation is what the app is for; a bar of four equally weighted destinations said
otherwise.

**The drawer's foot wears the same capsule.** The one control at the bottom of the history
panel — the way into the settings — is a `PiGlass.fill()` pill on `glassLift`, the pair the
bar's marks are drawn with, because it is the same kind of object: a control floating over
a page rather than a row on it. It used to be an opaque `surfaceContainer` pill on
`solidLift` — the cheap lift, chosen for a control that floats over a scrolling list — and
that made it a different kind of object from the capsules one panel away. The blur is
redrawn as the list scrolls under it; the header's capsules accept the same cost on a page
that scrolls under them, and a way out of the list that looks like the bar it was reached
from is worth more than the frames.

**A bar with no bar in it.** The reference drawing the page follows has no strip across
the top: the content scrolls *under* the marks, fades out as it reaches them, and the
marks are the only opaque objects there. That is three decisions, and each was made
because the strip version was worse:

- **The marks sit in opaque capsules** (`PiGlass.fill()`: white in the light scheme and
  `surfaceContainerHighest`, #2D2D2D, in the dark one) with a 5dp lift under them. The first
  attempt used `PiTone.inset()` — a *wash of the ink*, right for quoted material and wrong for
  a control, because the capsule then read as shading on the page instead of as an object
  above it. The fill was white at an alpha for a while — 80%, then 90%, then 100% over the
  light page, and 14%, 20%, 24% of white over the black one — and the dark half of that pair
  never became opaque, so the same control was a solid object by day and a tinted pane by
  night: a row scrolled under the capsule showed through it, and the page stopped reading as
  one object per theme. **A floating control is filled, not tinted.** The light scheme paints
  the white an answer card would use, and the dark scheme paints the palette's top container
  role — one step above the card (`surfaceContainer`, #242424) and two above the page
  (black) — which is the same "raised by one step" the light scheme gets from white on
  #F7F7F7. The lift stays: an opaque fill still needs its shadow to read as an object, and
  that shadow is what separates a capsule from a card whose fill is close to it.
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
  The touch targets are independent of this — every mark keeps the platform's 48dp target —
  so the rule costs nothing to obey.
- **The lift is drawn, not `Modifier.shadow`, and there are two of them.** On this Compose
  version the modifier's shadow does not follow the shape it is given: a circle's shadow was
  drawn as a squircle, and while the capsules' fill was translucent that silhouette showed
  *inside* the capsule — a white block with grey corners, measured at the leading capsule and
  read as "the button has a white block in it". `solidLift` is a `graphicsLayer` with
  `shadowElevation` for a surface that paints an opaque fill: the fill hides the squircle, so
  the platform's silhouette cannot betray the shape, and the shadow is the layer it already
  rides on. `glassLift` draws a blurred fill of the shape's *own* outline with a `MaskFilter`,
  which is the exact silhouette and is re-rasterised on every frame it is drawn — the right
  trade for the 42dp header capsules, the composer's chips and the toast, and the wrong one
  for a surface as wide as the window: measured while the keyboard closed, the blur under the
  composer's field was a **48 ms frame**. The composer's field therefore takes `solidLift`,
  and the small floating marks keep `glassLift` for its exact outline.
- **The transcript runs under the top**, with `headerSpace` as its *content* padding
  rather than as a margin, so the list can still scroll a row into that space — a margin
  would have cut the row off at the bar's edge instead of letting it dissolve.
- **The fade starts at the status bar's bottom edge**, not at the button row's: its solid
  part is exactly the status bar's height and the ramp runs from there, so a row begins to
  dissolve the moment it leaves the clock's line. It is a four-stop ramp
  (`100% → 62% → 26% → 0`): a two-stop one hits its transparent end while the eye still
  expects a gradual departure, so the row crossing it seemed to snap into place. It is a fade and not a blur: Compose has no backdrop filter, and a real
  blur here would mean rendering the transcript twice (once through a `RenderEffect`) —
  a second copy of a `LazyColumn` for an effect that the gradient already carries at
  arm's length. The reference's blur is a backdrop filter's; this is the same idea drawn
  by hand, and it is why the seam is invisible while the pixels differ.

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

**A toast is part of the page, not a visit from the system.** The app's ephemeral messages
were the platform's `Toast` for a while — the system owns the queue, the placement and the
text scale, and that is a real bargain — but it draws its own card in the system's colours
with the launcher icon beside the text, which on this page reads as a notification that
happened to land on it. They are opaque capsules now, drawn above the composer in the same
fill as the bar's, and the interface (`LocalPiToast.show`) did not change. The cost is
the system's queue and its per-app notification choice; the gain is one kind of floating
object everywhere.

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
edge, a shadow, about 2% smaller — and the panel is what it uncovers. The first version
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
the separation is not one mechanism: in the light scheme the card's shadow owns it, while in
the dark scheme a black card's shadow is invisible, so the panel is painted
`background` (#242424) under a `surface` card (black) — one palette step that a shadow
would otherwise have had to draw. A panel painted `surface` in both schemes was the same
colour as the card in the dark one, so the two halves of the screen were divided by a gesture
with nothing to see at the division: the drawer read as one page split by a seam that was not
there.

Two consequences of that order are worth naming. The card's corner and shadow are set on
its `graphicsLayer` and only while `progress > 0`: at rest the page *is* the window, and a
radius there would cut the screen's own corners. And the panel no longer has a shadow of
its own: the depth belongs to the card's edge, where the reference puts it.

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

**A list row never paints its own press.** The fill a row carries says one thing — *this is
the conversation behind the drawer* — and colour is the whole of how the list says it. The
library's press indication is the other kind of mark: a light that follows the finger, which
is right on a button and wrong on a row whose gesture *opens a panel over itself*. A hold
lights the row, the row's menu arrives at 500 ms with a dim over the list, and the light is
then the only thing on screen that is about a finger that has stopped being the subject —
and it leaves when the press ends, which is before the menu does. Measured on the medium
phone at density 2.625, holding a row until its menu opened: the held row read rgb(22)
against rgb(14) for the rows beside it under the panel's dim, and fell back to 14 as soon
as the long press was accepted, so the panel was accompanied by a highlight for part of its
own opening and by none for the rest. The row now carries no indication at all and the hold
answers with the platform's long-press haptic — the same answer the transcript settled on
(§7.3) — because a state that belongs to the finger cannot be a state that says "this menu
is about this conversation".

The drawer's travel is kept in pixels, so a window that changes size has to re-anchor it:
after one rotation an open drawer kept the portrait width and drew about 170px off the
leading edge, cutting its own title in half. The size callback now re-derives the offset
from the *answer* (open or closed) rather than from the old number, and the panel's width
is capped at 420dp, so a tablet shows a drawer and not a second page.

Two marks are drawn for this app rather than borrowed, because Miuix's set has neither:
the terminal's (a chevron and an underscore) and the drawer's (a long rule over a short
one, left-aligned, which is the reference's own handle mark). Both are *navigation*
marks — two strokes at the library's line weight on a 24-unit grid, no fill — and neither
is the app's mark, which is the launcher glyph. The footer's settings pill is the
library's `Settings` at 20dp inside a 48dp capsule. It is **centred on the panel**, not
hung at the list's gutter: at the leading edge it shared a column with the row marks above
it, so it read as the last row's action rather than as the drawer's foot, and a control
that belongs to the panel is centred on the panel. The list's end padding is unchanged, so
a list scrolled to its last conversation still stops above the pill.

**Back at the root loses the app, on the second press.** The conversation is the root, so
a back press has nowhere to go, and the platform's left-edge gesture is exactly where a
thumb lands when it means to pull a drawer *out*. Measured on the emulator with gesture
navigation: a left-edge swipe finished the activity and the launcher appeared, which reads
as the app falling to a black screen. The chat page therefore handles back itself: the
first press arms and says so in a toast, a second within two seconds leaves. The drawer's
own handler is composed later and closes the drawer first, which is the order a back press
has to have.

**One bar, and a page that arrives.** `PiPage` used to have a second, left-aligned
variant for its roots: the title on the page's own gutter, with its back arrow inside a
button that started at 26dp — 38dp from the edge with the button's own padding, against
the 16dp the same arrow had on every settings page. Two pushes, two arrow positions. The
chat page has no bar at all now, so the left-aligned variant had no caller left; it is
gone, and every page's arrow starts where the library puts it.

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
glyphs rather than the library's 48dp: three full-size icon buttons beside the app's name
were wider than the name, and the panel's heading is not the place to spend that.

## Shapes, motion, icons

**One corner above the library's.** The library's own radius is 16dp on every card,
field and button, and the app draws one step larger: `PiCorner.radius` (20dp) is
passed at every `Card`, `Button`, `TextButton` and `TextField` call site, so the
whole interface is cut with the same corner and no surface is one radius while its
neighbour is another. It is a single number and not a scale on purpose: a second
step would need a rule for which surface is which, and there is no such rule to
state. Three deliberate exceptions keep the library's own figure — the terminal key
bar's keys (half of each key's height, so a wide key is a pill), a tool output's copy
button and the code block's `CornerRadius(min(radius, height/2))`, which are round by
their own geometry — and the two 40dp composer discs (send and stop) state
`cornerRadius = 20.dp` explicitly so the shape does not depend on the button staying
40dp. There is no theme-level shape local in Miuix 0.9.4 to hang this on, which is
why it is per-call-site.

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
  nothing in it draws the Pi mark and the page's hint instead of a blank area,
  because the blank is the first thing the app ever shows.
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
  attempt: a fixed #F0F0F0 read as a well on white and as a smudge on the #F7F7F7
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
