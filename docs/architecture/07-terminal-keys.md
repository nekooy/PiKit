# The terminal's key bar

*[Architecture](../ARCHITECTURE.md) §7, part 4 of 4.*

## The terminal's key bar: uniform keys, an arrow pad, a word that toggles

The bar was one horizontally scrolling row of chips, arrows first as a flat
`← ↑ ↓ →`. On a phone a flat arrow row is not a d-pad: the eye reads it left to
right and has to stop and count to find "down". It is now a two-row grid in which
**every key is the same rectangle** — measured on the emulator at font scale 1.0,
all of them 151x167px — with the arrows as a cross in the corner.

The uniform size is what makes the layout work, and it took four attempts to get
there. The first three all sized chips to their own labels, and each failed
differently:

1. an empty cell above `←` sized like `←` — `↑` landed over the gap, measured at
   x=56 against `↓` at x=66, because those two glyphs do not have the same width;
2. a hard-coded width for the middle column — right at the font scale it was written
   for, wrong at the next one;
3. every column measured from the bottom row, with the corners pinned to the
   *narrowest* of the three — aligned, but it squeezed the line-break key into 32dp,
   where `换行` drew as `换` and nothing else.

Uniform keys dissolve the problem rather than solving it: `↑` is above `↓` because
both are the middle cell of their row, and no label can be clipped by a neighbour's
width because no neighbouring width differs. The size is the widest label's natural
width plus the chip padding, taken from a `TextMeasurer` before the first layout —
measuring laid-out chips also works, but it costs a frame in which the whole bar is
drawn too narrow and then reflows.

The row scrolls at font scale 1.0 rather than fitting, since eleven keys of the
widest label plus the pad and the gaps exceed a 1080px bar. The width has a ceiling
for that reason: past it the bar would scroll more, not fit more.

The two top corners are the only space that is neither already a key nor underneath
the scrolling rows, so that is where the two non-arrow controls went: the scroll
toggle above `←`, and the line-break key above `→`.

The line-break key sent a bare `\n` for as long as it existed, on the theory that
its job was the shell's `>` continuation prompt. That theory was wrong twice: the
newline character *is* the line delimiter in a canonical-mode terminal, so the
shell read the line and ran it — the reader's report was "the newline key is a
carriage return" — and the missing glyph case it was written for was never what
the key was for. It now sends `lnext` (`0x16`, the terminal's `^V`) followed by
`\n`, so the newline is quoted and lands *inside* the line being typed: readline
handles the `^V` as `quoted-insert` in non-canonical mode, and a program reading
whole lines has it done by the line discipline. `\r` from the soft keyboard still
runs the line; the key writes it.

The label had a bug of its own in English. The bar sizes every key to its widest
label and caps that width at `MAX_KEY_WIDTH` (64dp), and `Line break` is 72dp of
`labelMedium` monospace — so the one label longer than the cap drew clipped, which
is the "terminal newline button text is broken in English" report. `Newline` is
the same fact inside 51dp, so the cap stays where it is.

The scroll toggle has been through three shapes. It began as a wide two-line button
pinned to the right of the whole bar, showing its state in an icon *and* a word — the
most prominent thing on the bar while being its least-used, and its label resized the
scrolling area whenever the state changed. It then became an icon-only square, which
was quieter but said nothing: a glyph for "jump to the bottom" does not tell a user
what tapping it does. It is now the word `滚动` in a key-sized cell, so it reads as
one of the bar's keys because it is one. **Only its tint changes when tapped** —
`primary` while following, `error` while paused — so it keeps its size, its word and
its shape and cannot be misread as a mode it is not.

The toggle drives the vendored `TerminalEmulator.isAutoScrollDisabled()`, whose
`onScreenUpdated` already implements what follow-off should mean — a row shift is
subtracted from `mTopRow` instead of the view snapping to the bottom. So with it
paused, a long command's output accumulates *below* the part being read and nothing
jumps. It is the only mutator the emulator exposes (`toggleAutoScrollDisabled`, no
setter), so the flag is read back and flipped only when it disagrees; calling the
toggle unconditionally inverts the state on every recomposition.

The bar's choice is UI state, not emulator state, and it is mirrored into the
emulator from the `AndroidView`'s `update` block rather than only from an effect:
the emulator lives in a `View` that Compose recreates on every tab switch, and the
effect has nothing to attach to at the moment the state is first set.
