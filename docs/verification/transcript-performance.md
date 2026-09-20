# The transcript's frame cost

*[Verification](../VERIFICATION.md): why opening a long reply is slow, every attempt to fix
it, and the number or the crash that settled each. What the renderer costs on its own is
[the formula renderer](formula-renderer.md).*

## The frame that opens a conversation

A temporary frame-metrics probe (`TOTAL_DURATION`, `LAYOUT_MEASURE_DURATION`, `DRAW_DURATION`,
`GPU_DURATION`, every frame over 32 ms) ran against a twelve-turn formula-heavy session:

```
total=835.4  layout=305.9  draw=25.6  gpu=67.3     the frame that opened the conversation
total=923.3  layout=218.4  draw=14.9  gpu=59.5     the same frame with MarkdownText as a lazy list
```

**305.9 ms in the measure/layout pass, for one conversation** — and a Compose problem rather than a
renderer one, since the GPU took 67 ms of the same frame, which on `swiftshader` is not the
bottleneck. The cause was structural: a whole reply is **one item** of the transcript's `LazyColumn`,
so the list had to measure *all* of the item's blocks — a plain `Column` — to learn its height,
including those six screens down. `MarkdownText` was made a `LazyColumn` of its own, one item per
block, and **`layout` fell from 305.9 ms to 218.4 ms, a 29% cut**.

**That `LazyColumn` has since been reverted, because it was a crash**: `MarkdownText` is also drawn
inside `ManualPage`'s `SettingsBody`, a `Column(verticalScroll)`, and inside the file preview, where
a scrollable inside a scrollable gets infinite height on the main axis (ARCHITECTURE §12.1). The
numbers are the size of the problem, not a description of the current code. What this does **not**
claim is the steady-state scroll: no scroll-phase frame reached the probe's threshold, and
`dumpsys gfxinfo` reports a 34 ms median that describes `swiftshader` rather than the change.

## The frame profile on a working backend

Opening the formula-heavy session (26 messages, fifteen display formulas in one reply) and scrolling
it up and down sixteen times, on `-gpu host`:

```
open:      Total frames 119  Janky 15 (12.6%)  50th 23ms  90th 34ms   99th 150ms
scrolling: Total frames 201  Janky 55 (27.4%)  50th 28ms  90th 46ms   99th 450ms
```

The 99th percentile is the one to read — 150 ms is the frame that opens the conversation and 450 ms
a formula arriving into view — and this is a `-gpu host` emulator on a laptop, so the absolute
frames are not a phone's. The numbers support only that opening a formula-heavy conversation is not
a multi-second stall and that scrolling one does not stutter repeatedly.

## Deferring off-screen formulas

| | Janky frames | 50th | 90th | 99th |
| --- | --- | --- | --- | --- |
| 300-block reply, before | 33 (11.5%) | 24 ms | 34 ms | 600 ms |
| …with off-screen formulas deferred behind a one-line `Spacer` | **37 (8.6%)** | 26 ms | 32 ms | 850 ms |

The deferral drew a `Spacer` until a formula was inside the window, `onGloballyPositioned` deciding
and `remember` keeping a measured formula measured; the jank percentage fell and the 99th percentile
did not, and the design was removed again (ARCHITECTURE §12.1). **The reading stands: the frame that
opens a 300-block reply is still around 800 ms.**

## The lazy list was tried again, and threw again

`IllegalStateException: Vertically scrollable component was measured with an infinity maximum height
constraints` — because the transcript does **not** give an answer item a bounded height: the item is
wrapped in a `SelectionContainer` and the bubble, so the earlier premise ("the outer list gives this
inner one a bounded height") is wrong for *this* call site too (`AGENTS.md`).

## The fix that would work

A reply is one item of the transcript's list and the list must know that item's height, so the
reply's blocks cannot be measured lazily from inside. The fix is to make the reply several items of
the transcript's list — chunking it where the list is built — a change to `ChatScreen.kt`'s
transcript rather than to `MarkdownText`. It is not made; it is listed among the
[known gaps](known-gaps.md).
