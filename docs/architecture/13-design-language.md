# The design language

Material 3 Expressive is the whole of this app's interface: the palette, the type
scale, the shape vocabulary, the motion and every component drawn from them. This
chapter is the rule for what a page may look like, and the reason each rule is
there. The code it describes is `app/src/main/java/pi/kit/mob/ui/design/`, eight
files, and the rule is that a screen composes *those* and does not invent a
colour, a radius, a spring or a control of its own.

## Why the dependency was raised for it

M3 Expressive arrives with Material 3 **1.5**, which is the release that made the
emphasized type scale, the shape library and the expressive widgets public API:
`displayLargeEmphasized` through `labelSmallEmphasized`, `MaterialShapes`'s
thirty-five polygons with built-in morphing, `ButtonGroup`, `LoadingIndicator`,
`FloatingToolbar` and `FloatingActionButtonMenu`. **1.4.0** — the version this
build used before — has the *structure* (`MaterialExpressiveTheme`, `MotionScheme`,
the flexible app bars, `ShortNavigationBar`) and **none of the widgets**.

The upgrade is not a version bump and was not treated as one. `material3`
1.5.0-beta01 declares `minCompileSdk=37` and `minAndroidGradlePluginVersion=9.1.0`
in its AAR metadata, and every Compose 1.12.x artifact declares the same pair, so
the version forces `compileSdk` 37, AGP 9 and Gradle 9 together. Three AGP 9
defaults had to be turned off to keep the change to a dependency upgrade — recorded
in `gradle.properties` with the reasoning — and `targetSdk` staying 28 was
verified in the merged and packaged manifests of every variant, because AGP 9
defaults a module's `targetSdk` to its `compileSdk` and 28 is a *ceiling* for this
app rather than a conservative choice (§1).

## The palette

Violet-tinted neutrals carrying very low chroma, one saturated violet-indigo, and
one warm coral held in reserve. `PiColor.kt` has both schemes written out in full;
dynamic colour is deliberately **not** used, because the terminal's own sixteen
colours and the transcript's syntax tinting are fixed and a wallpaper-derived
palette can land them on top of each other.

| Role | Hue | What it is for |
| --- | --- | --- |
| `primary` | violet-indigo | The app's own voice: selection, the active destination, primary actions, the launcher mark |
| `secondary` | slate-violet | Supporting containers, headings, chips — present without competing with a primary fill |
| `tertiary` | coral | **Live state, and nothing else.** A turn that is streaming, an agent that is working, a process that is still going |
| `error` | red | A thing that failed, and a confirmation that cannot be taken back |

The surfaces are a strict five-step ramp — `surfaceContainerLowest` through
`surfaceContainerHighest` — and a page picks a step rather than a colour. The steps
are close in value on purpose: a step that reads as a *different panel* rather than
as a step turns a list into a stack of unrelated slabs, and the same instruction
appears in the shape guidance as "do not over-round information-dense components".

A colour with one job is a colour a reader learns. That is the whole of the
expressive colour tactic — "use contrast to emphasize the main takeaway" — applied
to one meaning at a time, and it is why `PiTone` exists as an enum: a call site
says *live*, not *coral*.

## The type scale

Material's expressive scale is thirty styles: the fifteen baseline ones and an
emphasized variant of each, the same size and line height at a heavier weight. The
library ships both, so nothing here defines them. Two rules:

- **Emphasized styles are for hierarchy, not for emphasis on a word.** A page
  title, a section's count, a primary action's label, a hero heading. Applying one
  to a sentence is how a page ends up shouting in three places at once.
- **No font is bundled.** The app's text is overwhelmingly machine output — a
  transcript, a terminal, file paths — where the reader's familiarity with the
  platform's face is worth more than a distinctive one. The hierarchy is carried by
  the emphasized styles and by size contrast instead.

## Shape: two voices, one mark

The corner scale is Material's ten steps, unmodified, in `PiShapes`. What this app
decides is *which step a surface uses*, and it does so by picking a side:

| Voice | Radii | Where |
| --- | --- | --- |
| Dense | `row` 12dp, `card` 16dp | Information-dense surfaces: transcript tool rows, settings rows, file rows, terminal chrome |
| Action | `pill`, fully round | Buttons, chips, badges, the navigation bar's indicator, the composer's send control |

The tension between the two is load-bearing and is the expressive shape tactic
("unexpected moments by switching between square and fully rounded shapes") applied
at the level of the whole app: an action reads as an action because it is the only
round thing on the page. A surface in the *middle* — a 24dp radius on a list row —
is neither dense nor an action and reads as neither, so it is not available.

Nested corners follow the guidance's optical-roundness rule,
`outer radius - padding = inner radius`, which `opticalRadius` computes. Two nested
rectangles at the same radius look unbalanced; this is the one line that fixes it.

`MaterialShapes`'s thirty-five polygons are decorative and the guidance says to use
abstract shapes sparingly, so the app spends its one licence on a single silhouette:
a **seven-sided cookie** (`PiShapes.agent`). It is the agent's mark, its working
indicator and the empty-state medallion, and it appears nowhere else. Seven sides is
the count that still reads as a distinct outline at the 20dp it is drawn at
smallest; at twelve it is a circle and at four it is a square.

## Motion

Springs, not durations. `PiMotion` reads all six specs — spatial and effects, each
in fast, default and slow — from `MaterialTheme.motionScheme`, which `PiTheme` sets
to `MotionScheme.expressive()` for the whole product. Nothing in the app names a
spring value: that is what makes swapping the scheme one line and no call sites.

- **Spatial** animates anything that moves, rotates or resizes, and overshoots.
- **Effects** animates colour and opacity and must not overshoot, because a colour
  that passes through a brighter value on its way reads as a flash.
- **Speed** follows size: fast for a switch or a button, default for most
  movement, slow for a whole screen.

Two hand-written specs exist and both are named in `PiMotion` rather than at a call
site, so there are exactly two places to look if the motion feels wrong. One is
`PiMotion.settle()`, a spring that *releases* rather than travels — a sheet sprung
back after a short drag — which wants different damping from a token and settles in
about three quarters of the time of the nearest token.

`PiPageSwap` is a spring-based directional slide and `PiTabFade` a crossfade on the
effects spring. The slide's two pages travel *different* distances — the incoming
one a full width, the outgoing one a fifth of it — because equal and opposite
offsets park both pages' text on the same pixels mid-animation and read as a smear
rather than as one page replacing another.

## The components

Everything a page needs is in `pi.kit.mob.ui.design`. No screen invents a control.

**`PiSurfaces.kt`** — `PiScaffold` (a flexible app bar over a body, with
`PiAppBarScroll.Collapsing` or `.Pinned`; the status-bar inset is the frame's own
business and a page cannot get it wrong), `PiCard` (one subject), `PiGroup` (peer
rows in one frame), `PiSectionHeader`, `PiRow`, `PiRowDivider`, `PiNote` (the one
prose voice), `PiEmptyState`, `PiFullDivider`.

**`PiActions.kt`** — `PiButton` (`PiButtonKind` × `PiButtonSize` × `PiTone`:
Material's own five heights, 32, 40, 56, 96 and 136dp, with a destructive action
being the same button in the error role), `PiSelectChip`, `PiConnectedGroup` (the
expressive replacement for a segmented control; outer corners round, inner corners
tightened), `PiFab`, `PiGroupOption`.

**`PiInputs.kt`** — `PiSearchField`, `PiTextField`, `PiSwitchRow`, `PiSliderRow`,
`PiChoiceRow`, `PiValueRow`.

**`PiFeedback.kt`** — `PiLoading`, `PiWorkingPill`, `PiAgentMark`, `PiBadge`,
`PiNotice`, `PiProgress`, `PiDot`, and `PiTone` (Neutral / Accent / Live / Danger).

**`PiSheets.kt`** — `PiSheetTitle`, `PiSheetList` (with a ceiling, because a sheet
that grows to cover the window has stopped being a sheet), `PiSheetRow` (whose tap is
gated on the sheet still being open), `PiSheetActions`, `PiSwatch`. These are
*bodies*: `ui/components/Sheets.kt` still owns the panel, the grabber, the scrim and
the drag, and it does so for measured reasons (a `ModalBottomSheet` is a second
window, which is why the keyboard used to close and why taps were swallowed for up
to 0.9 s after a dismissal). Nothing here re-opens that.

### The bump

Buttons are a **pill at rest and square up to `card` while held**. It is the
smallest possible version of the guidance's "unexpected moment by switching between
square and fully rounded shapes", it costs one animation and no layout, and it
answers a question a touch screen otherwise cannot: whether the press landed. The
quiet kinds — `Text` in a row, a chip in a filter bar — do not morph, because a
control that jumps under the finger is only delightful when the finger meant it, and
a list of twelve of them is a twitch.

### Containers: a card is not a group

A **card** is one subject with its own body. A **group** is a set of peer rows that
share a frame, with dividers between the rows. The distinction is what keeps a page
readable: eight cards stacked is eight visual objects where the reader needs one
list, and a group's rows must not each be a card.

## What the pages do with it

The app's four destinations and their second levels, and the one decision each makes
that the shared components do not:

- **Chat** — the transcript is the app's densest surface and the one place a *card*
  per turn is right, because a turn is a subject: a prompt card in the reader's own
  words, and an answer card holding that turn's reasoning, its tool calls and its
  reply. Tool calls are dense rows inside it, a live turn carries the coral, and the
  composer is the one surface that is a well rather than a list. A conversation with
  nothing in it draws the app's hero moment instead of a blank area, because the
  blank is the first thing the app ever shows.
- **Terminal** — the terminal is a black rectangle with its own sixteen colours, so
  the chrome around it stays out of the way; the key bar is the action voice at its
  smallest.
- **Files** — a browser, not a file manager: a path that is a subtitle, a listing
  whose rows are dense, and a preview sheet. It does not have to match the settings
  pages, and it does not.
- **Settings** — the only place `PiGroup` and `PiRow` are the whole page. Groups by
  subject, rows that act, and a page whose second level is a page rather than a
  dialog.
- **History** — a list of conversations is a list of *questions the reader asked*,
  so a row is two lines of the user's own words beside when it was and how much
  followed. It shares no row with Settings.
