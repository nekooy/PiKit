# The composer, and the bottom of the screen

*[Architecture](../ARCHITECTURE.md) §7, part 1 of 4.*

`enableEdgeToEdge()` sets `decorFitsSystemWindows = false`: the window is full-screen,
the IME is drawn *over* the app rather than resizing it, and the app is responsible for
keeping its own content out from under both. Measured on the emulator: the activity
window's frame stays `[0,0][1080,2400]` while the IME window is `[0,63][1080,2400]`
with `mImeHeight = 820`.

## What was tried, and why each one failed

1. **`AnimatedVisibility` driven by `WindowInsets.isImeVisible`.** That flag
   flips several times while one keyboard animation plays, as the IME's insets
   settle, so the bar expanded and collapsed repeatedly and every flip re-measured
   the whole page above it.
2. **`if (!imeVisible)` on top of the padding that already moved the bar.** Two
   mechanisms deciding the same thing: the bar appeared while the page resized in
   the opposite direction.
3. **The bar inside the keyboard's inset**, so it rode *up* and sat on top of the
   keyboard rather than getting out of the way. It shipped twice because it
   *looked* plausible: the bar really did move, just in the wrong direction.

## What is there now

The strip is pinned to the window's own bottom edge, outside the keyboard's inset,
and the **page**, not the strip, is padded by the keyboard: the keyboard rises over
the strip and hides it, and the page above ends exactly at the keyboard's top edge.

```
page bottom inset = max(navigationBars, ime, tabStripHeight)
tab strip         = aligned to the window bottom, inset = navigationBars only
```

There is no visibility state, no animation and no second clock: the strip's
disappearance *is* the keyboard's own motion, so the two cannot disagree. Swiping the
keyboard down reveals the strip progressively instead of snapping it back.

## The trap: `safeDrawing` already contains `ime`

`safeDrawing` is `systemBars + displayCutout + ime`. Using it as the strip's own
inset therefore added the keyboard's height to the strip's position and pushed it up
on top of the keyboard — design 3 again. Measured with `uiautomator`: the strip's
labels landed at `y = 1454` with the keyboard up, against `y = 2274` with it down — a
delta of 826 px, and the keyboard reported `mImeHeight = 820`. A missing inset and a
wrong inset look identical in a screenshot; they do not look identical in a number.
The fix is `WindowInsets.navigationBars`. The strip's height is measured with
`onSizeChanged` and fed back into the page's padding rather than hard-coded, because
it is Material's 80dp *plus* whatever the gesture strip is on the device.

## A text field that looked full width and was not

The composer is a rounded pill with the attachment button inside it and a filled
circular send button beside it. A `Box` with `weight(1f)` around a
`BasicTextField(modifier = Modifier.fillMaxWidth())` fails on the device: the field
measured **312 px wide inside a 780 px pill** — exactly the width of the placeholder
text, so text wrapped there — and everything to the right of that was inert, so
tapping the composer usually did nothing. `BasicTextField` measures its
`decorationBox` with `minWidth = 0` and reports *that* size, so a `fillMaxWidth`
chained to it is undone one node later. The fixed width a `Row` gives a **weighted
child** arrives as a *minimum* and survives, so `weight(1f)` belongs on the field
itself, with no `Box` in between.

## The composer's control row

The state, the model and the thinking level used to be three read-only rows of the
chat header, which put the three things a user changes most often while talking as
far from the message they apply to as the page allows. They are controls now, on a
row **above** the input box, each opening a modal sheet: thinking level, model, the
context window's fill, and the prompt cache's hit rate. Above, not below: a chip
between the field and the keyboard sits in the busiest strip on a phone screen and
reads as part of the message being written, where a chip above the field reads as
the setting the next message will be sent with. The row is a `LazyRow` so four chips
whose values can each be long stay on one line by scrolling rather than by being
squeezed. A plain `Row` with `horizontalScroll` under `fillMaxWidth` does not do
this: it measures its children against the screen's width and then draws the
overflow past the edge, so the last chip is sliced by the window — measured,
`缓存命中 0%` rendered at x=966..1048 and was cut rather than scrolled to.

The boundary between the transcript and the composer is an 8dp elevation shadow,
not a hairline. A 1dp `outlineVariant` rule across the full width was the first
version: a rule is a *drawn line*, so it competes with the transcript's own dividers
and reads as the top of a box rather than as the bottom of the page. The shadow is
`Modifier.shadow` placed ahead of the `Surface` rather than the `Surface`'s own
`shadowElevation`, which would also tint the bar with `surfaceTint` and lift it a
step in the palette.

The agent's **state** is deliberately not one of the chips: it is a fact rather than
a control, and a row of tappable chips with one inert chip at its head makes the eye
check all four every time. It stays in the header's subtitle, with the coloured dot
it had as a table row. The dot is an `InlineTextContent` placeholder inside the text
rather than a `Box` beside it: a `Row` of a `Box` and a `Text` gives the box no
baseline, and at bodySmall the 5dp dot sat 3px low.

The cache chip is measured against the **last finalized message**, not the session
total, because prompt caching is a property of one request: a ratio accumulated
over a session reports the average of a conversation that has changed shape several
times. It reads `0%` rather than hiding when nothing has been cached, because the
control people reach for would then disappear exactly when they go looking for it.

**The chip's fill is one step per palette, and the steps are not the same size.**
The row went 26dp-with-a-hairline to 34dp-with-`surfaceContainerHigh` to
`surfaceVariant`, and the last of those was still reported as hard to see in the
light theme: on the composer's `#EDEEF2` bar, `#E4E9EF` is a 9/5/3-per-channel
difference, which at 34dp reads as a printed label rather than as a button.
`composerChipContainer()` takes one more step — `surfaceContainer`, `#DCE3EB`,
another 8/6/4 down — and *no* step in the dark theme, where the same move would put
the chip at `#1A2028` on a `#171C23` bar and erase it. The choice is made from the
fill's luminance, because the colour is the question being asked.

The header's three actions are ordered **compress, history, new**: left-to-right as
"do something to this conversation, look at the others, start another", and also the
order of how much each disturbs what is on screen — a compaction is applied in
place, the session list is a page away, and a new session replaces the transcript.

## Why the model switch is not a restart

Switching profile used to mean "make it active, then `scheduleRestart()`": a process
teardown, a `pi` start and a fresh handshake — seconds, and the whole transcript
re-read — to change one field that pi accepts over RPC through `set_model`. So the
switch is applied over RPC and the restart is only the fallback for when pi refuses,
which also means the running turn, the queue and the terminal's view of the session
survive it. Two details are load-bearing: the provider is sent as **pi's own provider
id** (`deepseek`, `pikit-custom`), because `set_model` matches `m.provider ===
command.provider` against the snapshot of models pi already knows — checked in pi
0.85.1's `rpc-mode.js`, which answers `Model not found: <provider>/<id>` for anything
else; the environment variable name is the same string for the built-in providers but
not the same concept, and sending `DEEPSEEK_API_KEY` is a lookup that always misses.
And the profile is committed to the store *first*, so the setting survives a later
restart even if the call is rejected — a rejection means this build does not offer
that model, and the fallback is a restart, which cannot be wrong.

## Why there is no segmented control in this app

A `SegmentedChoice` — one track, one marker sliding under the chosen segment —
lived in `Forms.kt` for a while, used for exactly one setting: the thinking level,
on the model page. It was removed rather than fixed for two reasons:

- **Seven Han labels do not fit a phone's width.** The control's own documentation
  said two to five options; the thinking levels are seven, so each segment got
  about 36dp and the touch targets were under the platform minimum.
- **It rendered as an empty box.** The control sized itself from its *first
  segment*: `BoxWithConstraints` wraps its content and the segments are sized from
  its marker, so seven options produced a ~150px rounded rectangle at the start of
  the row and every label ellipsised to nothing. Measured with `uiautomator`: not
  one text node inside the control's bounds, against seven rows in the sheet that
  replaced it.

The setting is now the same row-and-sheet every other choice in the app uses
(`PickerRow` plus `PickerBody`), with the options built by one helper
(`thinkingLevelOptions`) so the model page and the composer's chip cannot disagree
about a level or its description.

## Sheets are drawn in the page's own window, not in a second one

Every modal in the app — the pickers, the conversation's numbers, the command list,
the file preview, the terminal's session switcher — used to be a Material3
`ModalBottomSheet`, which is not a view in the page but a `ComponentDialog`
(`ModalBottomSheetDialogWrapper`) laid over the activity. Neither of the two things
wrong with it could be fixed from the call sites, because a dialog window is defined
to take window focus.

**It closed the keyboard.** A focusable window appearing over a focused text field
takes the IME's connection with it. Measured: `mImeWindowVis=3 mInputShown=true`
before tapping a composer chip, `mImeWindowVis=0 mInputShown=false` 250 ms after.
Three animations then ran at once and disagreed — the keyboard leaving, the page
re-measuring as the IME's inset collapsed (the composer's chips moved 610 px, from
y=1153 to y=1763), and the sheet travelling in against an inset that was still
shrinking under it, so it arrived somewhere other than where it settled.

**It swallowed input after closing.** The window outlives the state that asked for
it. Counting `pi.kit.mob` windows with `dumpsys window` after a dismiss-then-tap
pair at increasing gaps: one window while open, and the tap after the dismissal was
lost at gaps of 150, 250 and 500 ms and only landed at 800 ms. Roughly 0.6–0.9 s of
dead screen, per sheet, everywhere a sheet was used.

A sheet is now a `Box` in the page's own window (`ui/components/Sheets.kt`), drawn
by the root above the tab strip. There is no second window, so there is nothing to
take focus, nothing to re-measure and nothing to outlive the state. Two
consequences are deliberate:

- **The keyboard stays up**, and the panel is lifted above it by
  `WindowInsets.ime`, so a picker opened mid-sentence appears over the keyboard
  rather than replacing it. The page underneath does not move at all.
- **A leaving sheet takes no input.** The panel's `draggable` and every row's
  `clickable` are installed only while the host says the sheet is open, so once it
  has been dismissed a tap reaches whatever is underneath — the same measurement
  now reads: lost at a 0.00–0.10 s gap, landed from 0.15 s on, which is the 250 ms
  of its own exit animation and nothing more. A pointer modifier that is merely
  *disabled* is not enough: it still claims the hit test, so a row on its way off
  screen would eat the tap instead of letting it through.

`SheetHost` holds one sheet — modal means never two — and keeps the last one for the
length of the exit, which is what the dialog's window used to do for free. The state
lives above the root's `key(language)` block: switching the language rebuilds every
screen under it, and the sheet it was switched *from* still has to finish sliding
out. A sheet's body is a composable rather than a row spec because the file preview
is not a list, and a body that must follow live data reads it inside the lambda,
since the lambda is built once, when the sheet opens. Only the *content* of a sheet
is drawn by the sheet: the panel, its shape, the grabber, the scrim, the back gesture
and the drag all belong to the layer, so the picker and the command list cannot
drift apart.

**The conversation list's row menu was the last `DropdownMenu` in the app.** `Popup`
emits a zero-size layout node into its caller's layout, and the row was a `Row` with
`Arrangement.spacedBy(4.dp)` — which does not skip zero-size children — so merely
opening the menu slid the ⋮ button 4 dp to the left and narrowed the title column by
the same amount. And a `DropdownMenu` is anchored to the composable that contains
it, so it opened under the *row's* left edge rather than under the button, over the
title it belonged to. It is now a `ReadOnlyBody` sheet, which is what `Forms.kt` says
a menu is, and what the terminal's session switcher became for the same reason.
