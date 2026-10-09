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

Low-chroma neutrals in the accent's own hue, one saturated accent, and one role
that means *live*. `PiColor.kt` has both schemes written out in full; dynamic
colour is deliberately **not** used, because the terminal's own sixteen colours and
the transcript's syntax tinting are fixed and a wallpaper-derived palette can land
them on top of each other.

| Role | Hue | What it is for |
| --- | --- | --- |
| `primary` | the chosen accent, blue by default | The app's own voice: selection, the active destination, primary actions, section names, the launcher mark |
| `secondary` | the accent desaturated | Supporting containers and chips — present without competing with a primary fill |
| `tertiary` | the accent's neighbour, 14° round and quieter | **Live state, and nothing else.** A turn that is streaming, an agent that is working, a process that is still going |
| `error` | red, always | A thing that failed, and a confirmation that cannot be taken back |

### The hue is the choice; the lightness is not

A user has an opinion about the accent, so `data/ThemeColor.kt` offers eight — blue
is `DEFAULT`, and indigo is the violet-indigo the app shipped with before the choice
existed — and `PiColor.accentScheme` moves the *hue* of the whole scheme:

- the **accent roles** are re-derived from the seed at **fixed lightness steps**:
  0.45 for a light scheme's primary, 0.90 for its container, 0.16 for the ink on that
  container, and the dark scheme's mirror images. Fixing lightness rather than
  contrast is what makes the eight interchangeable — a yellow held at the blue's
  contrast ratio is a brown, and a palette whose roles move per hue is a palette
  where one choice is legible and another is not. The saturation is the seed's own,
  clamped to 0.35–0.9, and each container is a *fraction* of it;
- the **neutral ramp and the outlines are re-hued**, keeping their own lightness and
  their own very low chroma. This is the half that had to be found by getting it
  wrong first: deriving only the accent left a blue button on a lavender surface, and
  a low-chroma colour sitting next to a saturated one is not read as a neutral — it is
  read as *that* colour, drained, which is what "the greys look dirty" is describing.
  A surface is a blue-grey under a blue accent and a green-grey under a green one,
  which is what the palette did before the choice existed: 0.4.1's greys run `#F8F9FE`
  to `#EDEEF2`, all around hue 210, the same family as its `#35597F` primary.

What does **not** move is the lightness of anything — the tonal ramp is what the
transcript and the terminal are read on — and the error pair, because a failure is
the one colour that has to mean the same thing whatever was picked.

`tertiary` used to be a reserved coral, and that is the one part of the old scheme
the choice replaced. It is the accent's hue rotated 14° at a lower saturation: still
tellable from `primary` — which is the whole job of the role, `PiTone.Live` against
`PiTone.Accent` — and no longer a colour from outside the scheme, which in practice
was an orange patch in a blue interface that the user had not chosen and could not
change. 0.4.1's own tertiary was `#3F6373` against a `#35597F` primary: the same
relationship, a neighbouring hue, quieter.

The surfaces are a strict five-step ramp — `surfaceContainerLowest` through
`surfaceContainerHighest` — and a page picks a step rather than a colour. The steps
are close in value on purpose: a step that reads as a *different panel* rather than
as a step turns a list into a stack of unrelated slabs, and the same instruction
appears in the shape guidance as "do not over-round information-dense components".

A role with one job is a role a reader learns. That is the whole of the expressive
colour tactic — "use contrast to emphasize the main takeaway" — applied to one
meaning at a time, and it is why `PiTone` exists as an enum: a call site says
*live*, not a colour. Keeping the job and giving the hue to the user is what makes
the accent a preference rather than a re-skin.

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
- **A section heading is the accent, not a grey.** `PiSectionHeader` draws
  `labelLargeEmphasized`, upper-cased, letterspaced and inset 24dp, in `primary`.
  It was `secondary` on the theory that the primary colour belongs to actions and a
  heading in it would look tappable; against the light scheme's own values the
  theory does not hold — `secondary` is `#5C5D72`, which on the page surface is a
  *grey* label, so the section names that give a settings page its structure read as
  one more shade of body text. Form answers the concern the colour raised: nothing
  in the app that can be pressed looks like an upper-cased, letterspaced label.

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

**`PiSurfaces.kt`** — `PiScaffold` (one fixed header over a body, `PiHeaderHeight` tall with
the status-bar inset consumed inside the header's own `Surface`, so a page cannot get it
wrong), `PiCard` (one subject), `PiGroup` (peer rows in one frame), `PiSectionHeader`,
`PiRow`, `PiRowDivider`, `PiNote` (the one prose voice), `PiEmptyState`, `PiFullDivider`.
`PiPagePadding` and `PiPageBottom` are the page's own insets — the second is how far above
the navigation bar a scrolling body stops, so the last row or card is never read against the
bar's edge — and both exist so a page cannot pick its own number.

Two of `PiRow`'s slots are sized by the language rather than by the caller. Its **chevron is
20dp** — `SettingsRow`, which draws the settings tab, has used that box all along, and the two
were 16x27 px of ink against 19x31 on the same 420dpi screen until they were unified (§9.2) —
and its **`valueColor`** is a parameter, because a row that reports the outcome of something the
reader just started carries that outcome in a `PiTone` while every other row's value is the
neutral. `PiScaffold` takes a **`titleStyle`**, defaulting to the shell's `titleLarge`: the chat
page's title is prose (the reader's own first line) and asks for `titleMedium`, and no other page
does, so the exception is a parameter rather than a second header.

**`PiActions.kt`** — `PiButton` (`PiButtonKind` × `PiButtonSize` × `PiTone`:
Material's own five heights, 32, 40, 56, 96 and 136dp, with a destructive action
being the same button in the error role), `PiSelectChip`, `PiConnectedGroup` (the
expressive replacement for a segmented control; outer corners round, inner corners
tightened), `PiFab`, `PiGroupOption`.

**`PiInputs.kt`** — `PiSearchField`, `PiTextField`, `PiSwitchRow`, `PiChoiceRow`,
`PiValueRow`.

**`PiFeedback.kt`** — `PiLoading`, `PiStatePill` (a word and either the live indicator
or a settled mark), `PiAgentMark`, `PiAvatar`, `PiBadge`, `PiNotice`, `PiProgress`,
`PiDot`, and `PiTone` (Neutral / Accent / Live / Danger).

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

### No kind is outlined by the app; a dialog is two words

**No `PiButtonKind` carries a hairline of its own.** `Outlined` keeps the framework's
border — the kind that means "this is a control with an edge" — and `Text` is Material's
own text button and nothing more: no fill, no outline, its whole difference from the fill
kinds being the colour of its label. That is what a *dialog* is: a confirm and a dismiss
that are two words differing by colour, the destructive one in the error role, rather than
a filled pill beside a bordered word, which is two weights for one question. The app added
a 1dp `outlineVariant` hairline to `Text` for a while, to stop a bare button reading as a
word on a page whose cards and background are the same neutral; every `Text` button left in
the app lives inside a tinted notice, whose own container is that edge, so the hairline was
a second boundary around one thing and it is gone.

A first draft put the hairline on *all five* kinds, and it was wrong in both directions at
once: it made the app's primary actions look timid, and it turned the composer's chips and
its two icon buttons — already separated from the panel by their fill and their glyph —
into a row of boxes. The chips were the case that had a real problem in it, and the answer
to that one is not a boundary either: an unselected chip is the accent's **container**,
which is darker than the panel and visibly coloured, where the neutral step it used to use
was a shade of grey.

A toolbar **icon** is chrome rather than a button — a page header's back arrow, the
terminal's own actions, the composer's four controls — and carries no outline either way.

The **composer's field** is the one hairline that is not a `PiButtonKind.Text`, and it
is the same rule rather than an exception to it: the words above it are about *actions*,
and a field is not one — it is a place, and a place with no edge is a place the reader has
to be told about. `surface` one step above the band's own `surfaceContainer`, 1dp
`outlineVariant` and `largeIncreased`'s 20dp, which is where 0.4.1's 22dp
`shapes.medium` lands on this scale (§7.1).

**Action rows end-align, and a sheet's dismiss is not a button.** `PiSheetActions`,
the document editors' footers, a dialog's confirm and cancel: every row of buttons
collects at the trailing edge, the dismiss first and the action it commits last — and a
control that belongs to *one row* is that row's `trailing` instead of a strip under it (§9.2,
§9.3). The first draft spread them from the leading edge, which put a
sheet's one real action under the left thumb and its dismiss under the right.

The **standalone page action** is the exception, and it is the leading edge: the backup
page's export/import pair (`RunActions`) and the model page's "new configuration" both
start where the page's own content column starts, at one size (`Filled`, `Small`) and one
alignment. A page action that floats at the opposite edge from the next page's was the
inconsistency; a *row's* action still belongs at the row's trailing edge, which is a
different question from the page's.

The **dismiss itself is gone** from every sheet. A sheet is closed four ways that need
no control at all — the scrim above it, the back gesture, a downward drag, and the
panel's own exit — and a *Cancel* or *Close* button was a fifth way to do what four
already did, on the one line that has room for the action the sheet exists for. What
remains in a footer is what *changes* something: the files preview keeps *Open with*,
the model numbers keep *Save*, the AGENTS.md editor keeps *Restore* and *Save*. A
dialog is not a sheet and keeps its two buttons: an `AlertDialog` is a decision with a
question above it, and its cancel is half of the answer.

### Where a sentence goes — and why most of them are gone

The app carries two kinds of prose, and a sentence that is neither does not get
written:

- about **one control** — what pressing it does, what a field's value is for — it
  goes *inside that control*: a row's `subtitle`, or a field's `supportingText`. The
  model-numbers sheet's "a blank box leaves the value alone" is its field's help. A
  button's `caption` — a second line inside its own fill — was a third home for one of
  these and it is gone: the one caller (the AGENTS.md editor's *Save*) dropped it because
  a two-line *Save* beside a one-line *Restore* read as two sizes for two peer actions,
  and the sentence about the restart is reported *after* the save instead.
- about a **state** — a save that failed, a file that could not be read, a shell that
  exited — it is `PiNotice`, in its container, beside the thing in that state.

What is deliberately **not** written is a third kind, and it was written in this app
for a long time: a paragraph about the *page* — what it is for, when a change takes
effect, why the default is what it is. Those paragraphs were on every settings page,
under a heading and in a voice of their own (`PiNote`, and `SettingsNote` on the
settings pages), and they were removed rather than restyled. The reasons are worth
keeping, because the design looks under-explained without them and that is the
point:

- **Nobody reads them.** They sit under the controls they describe, so they are met
  after the decision rather than before it, and a reader who has already tapped the
  switch has no reason to keep going. Nothing measured this; the app simply had a
  page's worth of them and the answer to "what does the maintenance page do" was
  still the page's rows.
- **They made the pages heavy.** Five settings pages carried fourteen paragraphs
  between them, in two different containers, at three different indents — the drift
  `PiNote`'s single inset had been invented to stop. Removing them is what let the
  pages be read as lists.
- **The manual is where they belong.** It is one document with chapters, it is
  reachable from the settings root, and it can say a thing once instead of at every
  place the thing is true. The keep-alive explanation and the accent colour's reach
  moved into it in the same change; the workspace's "only directory a recursive
  delete is allowed in" was already there.

A fact that a user *must* know before acting — a change that only takes effect on the
next agent start — is not a paragraph: it is a clause in the subtitle of the control
that makes the change.

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
  words at the end edge on the app's own colour, and an answer card holding that turn's
  reasoning, its tool calls and its reply. Tool calls are dense rows inside it, a live
  turn carries the live tone, and the composer is the one surface that *floats* — a panel
  drawn over the transcript with an elevation shadow, so the list keeps the whole height
  of the page. A conversation with nothing in it draws the app's hero moment instead of
  a blank area, because the blank is the first thing the app ever shows.
- **Terminal** — the terminal is a black rectangle with its own sixteen colours, so
  the chrome around it stays out of the way; the key bar is the action voice at its
  smallest, and its own background is the tab strip's `surfaceContainer` because it is
  the page's bottom edge — anything else drew a seam between the terminal and the bar.
- **Files** — a browser, not a file manager: a path that is a subtitle, a listing
  whose rows are dense, and a preview sheet. It does not have to match the settings
  pages, and it does not. The listing's frame **wraps its rows**: it was
  `fillMaxSize`, so a directory of three files drew one grey slab the height of the
  window with two rows at the top of it, and the fix is `wrapContentHeight` — which
  is the modifier that *relaxes the incoming minimum*, the page's body having
  arrived with a minimum of the whole viewport. A `LazyColumn` reports the height of
  its laid-out content when the content fits and fills only when there is something
  to scroll to, so the frame ends where the last row ends. It also gained the 8dp
  top gap every other list in the app has, because flush under the header the first
  row read as part of the bar. The preview's **Open with** is a button rather than a
  list row inside the body — the one thing the sheet can do with what was read — and
  it is the sheet's only control: there is no *Close* beside it, because the scrim,
  back and a downward drag all leave the preview.
- **Settings** — the only place `PiGroup` and `PiRow` are the whole page. Groups by
  subject, rows that act, and a page whose second level is a page rather than a
  dialog.
- **History** — a list of conversations is a list of *questions the reader asked*,
  so a row is two lines of the user's own words beside when it was and how much
  followed. It shares no row with Settings.
