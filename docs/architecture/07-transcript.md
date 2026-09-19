# The transcript

*[Architecture](../ARCHITECTURE.md) §7, part 3 of 4.*

## The transcript stops following when the reader scrolls

The list scrolled to its last item on every streamed token, unconditionally. Both
the follow and the reader's finger write to the same scroll offset, so a reader who
scrolled up to read a tool's output was dragged back to the bottom a few
milliseconds later and could not read anything above the tail while an answer was
arriving. The position alone cannot tell the two apart — the position also moves
when the follow moves it — so the state machine reads `isScrollInProgress` and a
flag for the frames this app's own `scrollToItem` owns.

It is three rules, and two of them were wrong in an earlier draft, which is why
they are written down:

- **The position decides, not the start of the gesture.** Stopping on the frame a
  drag *began* at the bottom looks right — "the reader grabbed the tail" — but a
  phone scroll is a small drag repeated, and the first draft then resumed on the
  next frame of the same drag. Invisible to a unit test that asserts on one frame;
  a visible jitter on the device.
- **A gesture's judgement waits for its end.** Mid-fling the offset passes through
  the bottom on its way further up, and resuming on that frame is the same tug of
  war one fling long. The frame the gesture ends on is also the only one allowed to
  write the answer, because a streamed token changes the list's content — and
  therefore `canScrollForward` — on its own.
- **Our own scroll is not a gesture.** `scrollToItem` sets `isScrollInProgress`
  exactly as a drag does, so without the programmatic flag every follow would turn
  itself off on its own first frame.

`FollowTailState` is pure and Android-free, for the same reason
`ConversationReducer` is: the symptom of getting it wrong is "it feels janky", and
a screenshot of the bug and a screenshot of the fix are identical.
`FollowTailTest` walks whole gestures rather than asserting on single frames.

Two things about the *scroll itself* were wrong underneath that rule, and both were
reported as one bug — "a very short flick jumps to a fixed position, and the
jump-to-latest button does not reach the latest":

- **`scrollToItem(last)` is not the bottom.** It aims at the last item's *top*, so
  an answer taller than the viewport — every answer to a real question — was left
  showing its first line with the rest below the fold. The position the follow
  settled on was therefore the top of the last message, and a tiny gesture that
  re-enabled the follow snapped back to exactly that. The caller's own row count is
  still passed into `scrollToEnd`, because `layoutInfo` is empty before the first
  layout pass — the frame a restored conversation is scrolled on.
- **The programmatic flag was a dead local.** `rememberFollowTail` had its own
  `var programmatic = false` that nothing ever set, while `ChatScreen` wrote a
  `MutableState` of the same name that nothing ever read. The rule was reading the
  dead one. It works by accident only while every follow lands *at* the bottom —
  which is the first bug — so the flag is now a parameter, and the follow no longer
  turns itself off when its own scroll stops short of the tail.

## The end of the list is whatever the list says it is

Two further rounds of "the button does not work" came out of *computing* that end
instead of asking for it, and the second is worth recording because the arithmetic
looked right.

The distance left to scroll was measured as `last.offset + last.size +
afterContentPadding - viewportSize.height`, and it disagreed with the list's own
`canScrollForward` by exactly two content paddings. Compose's measure pass sets
`viewportEndOffset = mainAxisAvailableSize + afterContentPadding`, so the last item
has to end *one* padding above the viewport's end before the list is done. Measured
while an answer streamed on the Medium_Phone AVD, with the button reported as still
on screen after a tap:

```
visible=1@-2962+3667  viewport=810  afterPad=105  canFwd=true   (formula: 0)
```

The formula said "at the end"; the list said it could still scroll, and the button
follows the list. `scrollToEnd` now asks instead of calculating: `scrollBy` a
deliberately huge probe and read the delta it *consumed* — `scrollBy` clamps at the
list's own bounds, so what it returns **is** the distance that was left, and the same
measure pass produces both numbers, so they cannot disagree. The animated path uses
that distance by scrolling back by it and then animating over it, with no suspension
point between the two calls (nothing is drawn in between, which is the same reason
two snaps can be one visible move). Animating to the last item's *top* instead is
what had produced an up-then-down motion.

The second half of that report is *timing*: a streamed token changes the text before
it changes the layout, so a jump that arrives with one ends short by that token's
height, and once the answer stops there is no next token to correct it with. A
`settle()` step now waits a frame and re-probes, up to three frames, before the jump
is considered finished.

The transcript also ends two lines of blank space above the composer now
(`TRANSCRIPT_TAIL_SPACE`): the composer's first row is a strip of chips on the same
colour as the page, so an answer that ended flush against it read as cut off rather
than as finished.

## Tool cards may not be sized by their own content

A `bash` card's width changed *while it ran*, because the summary line was
`weight(2f, fill = false)` — which lets it shrink to its text — and the chevron
beside it competed for the remainder differently depending on how long the command
was. The fix was a card exactly as wide as the transcript and exactly one text line
tall, with the collapsed output always two lines of monospace.

That was still a *card*: a filled panel with a header row and two lines of output
under it, so a turn that used six tools pushed the answer it was asking for a screen
and a half down with scaffolding. The closed state is now one line and no
background at all — the state mark, the tool's name, a middot and as much of its
argument as fits, with the chevron where the words end. The argument rather than the
output, because `ls -la` says what the call is and the first two lines of a
directory listing do not. The filled panel appears only once the row is opened,
which is how it says "this is the thing you opened" without decorating every closed
row in a turn.

**The spinner after the name was removed** — a second moving thing on a row whose
mark already reports the state, and it made the name's own width depend on whether
the call had finished. The three states are the app's tick, cross and a filled dot,
inside one fixed 14dp square so that a running row is not a shade taller than a
finished one.

The turn's fold chip lost its failure mark at the same time. `failedSteps` is still
computed and still pinned by `ChatFoldTest` — the question belongs with the fold
rule — but a red warning and "2 failed" after the step count made a row whose whole
job is "there is more behind this" report a second, unrelated fact.

## Three rows that open are one shape

The turn band, a message's reasoning and a tool call are all "a row that opens", and
they were three different objects: a filled chip as wide as its own text, a bare
two-part row with the count pushed to the far edge, and a bare row with a mark and a
chevron at the far edge. The report that produced this was a reference screenshot of
a transcript where all three are one shape, and matching it removed three separate
decisions rather than adding one:

- **One label shape**, `mark · words ⌄`, left-aligned at the transcript's margin.
  The chevron sits where the words end, not at the row's edge: at the edge it reads
  as a second, unrelated control a screen's width from the text it belongs to.
- **The turn band lost its fill.** A `surfaceVariant` chip across the page is what
  made a turn boundary look like a different *kind* of row rather than the first row
  of a turn; the band's position and its `primary`-tinted chevron are what separate
  it from the reasoning row directly beneath it.
- **The labels say facts, not verbs.** `工作 47 秒 · 5 个步骤` and `思考 · 1.2k 字`
  — the reference's `Worked 47s · 5 steps` shape, with the time in the interface's
  own units (`秒`, `s`: the units belong to the language and the arithmetic does
  not, so `Strings.Chat.duration` formats whole seconds). "Show reasoning" and the
  word "已" were removed with them: a control's verb belongs in its `onClickLabel`,
  where a screen reader looks for it, and "已工作" read as a second statement about
  a turn the reader had just watched finish.

The reasoning row's count is now live while the reasoning streams. It was hidden
before, on the theory that a number changing on every token is noise; it is the only
thing on the row that moves, and it is what says the block being waited for is
filling up.

**The band's type is one type.** It used to change with whether the row was a control:
`labelMedium` + `FontWeight.Medium` when the turn hid steps and `labelSmall` + Normal
when it did not. Both cases are the same sentence about the same turn — a turn whose
answer needed no tool call simply has nothing behind the band — so one conversation
drew `工作 47 秒` at two sizes, which is the report "有步骤时和没步骤时上面的工作X秒字体
大小样式不一样". The chevron is what says which of the two it is, and it is only drawn
when there is something to open.

**No disclosure row ripples.** `clickable`'s default indication is a *bounded*
ripple, so on a row that spans the transcript it fills the whole width — a bar of
colour flashing across the conversation on every tap, which the reader reported as
"点击反馈的条块". `disclosureClickable` is `clickable(indication = null)` with one
remembered interaction source, and what a disclosure row shows when it is tapped is
the thing it opened.

## The live cursor was removed

An assistant row used to draw a 2dp caret at the left margin while its answer was on
its way: an `infiniteRepeatable` alpha tween first, then a 2 Hz blink, both of them
justified in the code as the thing that says "the text will be here". The reader asked
for it out — "对话页输出时不要那个闪烁光标" — and the case for keeping it never
recovered from looking at what it actually covered. It was only ever drawn while the
row had no text *and* no reasoning, so what it marked was the wait for a first token,
which is a second or two; and the row it sat on already had a progress signal in the
reasoning control's live character count (`思考 · 1.2k 字`) for the whole time a
reasoning model is thinking. Nothing measures the caret's absence: the composer's
stop button is the "something is running" control, and `drawsNothing()` now drops an
empty streaming row instead of holding a blank line open for a cursor that is not
coming.

A *static* bar was the other candidate, and it is worse than nothing here: it is the
same caret with the only thing that made it read as one taken away, on a page whose
whole point is that it does not decorate bare rows. The blink it replaced is recorded
where the fade is: two alpha writes a second rather than a frame every 16 ms for the
length of a thinking phase, which was the one cost on this page that recomposition
discipline could not remove.

## The turn rail was removed

The reader asked for the reference's turn navigation — a run of ticks down the edge of
the transcript, one per turn, that jumps to it — and then asked for it out again: the
ticks were a second navigation over a transcript the reader was already scrolling, and
the 26dp they reserved on the right edge were taken from every line of every answer.

What it was is worth keeping, because it is the answer if a rail is ever wanted again.
`TurnRail` was one `drawBehind` pass over a box the height of the transcript with the
ticks spread evenly, not one composable per turn: a conversation with two hundred turns
would otherwise have added two hundred layout nodes to the heaviest list on the page,
and — the one that matters more — evenly spread ticks cannot overlap however many turns
there are, while a `Column` of fixed ticks runs off the bottom of the screen after
about twenty. The trade was that the ticks were not nodes, so a screen reader could not
reach an individual one; nothing usable was lost, because a 2dp tick is far below the
48dp minimum touch target, so the rail was one target spanning the full height and a
tap went to the nearest tick measured from the touch's own y. Where a tick pointed was
`turnTargets`, and it aimed at the turn's **prompt** rather than at the fold band under
it: landing with the question just off the top of the screen shows the answer to
something the reader cannot see.

Its width was reserved in the list's own end padding rather than overlapped, so no
answer was ever drawn under it. That reservation went with it: the transcript's two
gutters are now the same 12dp, and every answer is 26dp wider. The jump-to-latest button
keeps its 10dp inset from the same corner; it used to read `TURN_RAIL_WIDTH + 10dp`
because the rail's column was behind it.

The rail was also the only caller of `FollowTailController.stop`. A jump to an earlier
turn is the reader saying "not the tail", and a programmatic scroll is deliberately
invisible to the follow rule, so nothing else could carry that intent — with the rail
gone, the reader's own scrolling is the only thing that stops the follow, and `stop`
was deleted rather than left as an entry point with no caller.

## The transcript's rhythm: two boundaries and one module gap

Every row was spaced from the next by the same `Arrangement.spacedBy(8.dp)`, and that
one number is what made a conversation read as an undifferentiated stream: the gap
between a prompt and the answer it produced was the same as the gap between two calls
of the same step, so the only thing marking a turn boundary was the text. Measured
from the code, an answer streaming before its turn closed had no fold band yet and so
sat 8dp under the prompt it was answering.

The gap is now a function of the two rows — `rowGap` in `ChatScreen.kt`, which the list
applies as `padding(top = …)` with `spacedBy(0.dp)` — and it is computed in the page
rather than in `transcriptRows` so the fold rules stay about what is *shown*:

| adjacency | gap | why |
| --- | --- | --- |
| last row of a turn → next prompt | 20dp | the only gap that means "new subject" |
| prompt → what answers it | 12dp | one unit, but the bubble must not touch the answer |
| every module inside a turn | 8dp | a thought, a call, its answer, a notice, the fold band |

The first version of the rule had five numbers and a different one on each side of a
tool call — 4dp between two calls, 8dp after one, 12dp before one — which meant the
same two modules were laid out differently depending on which came first. A turn
reading "thinking → bash → thinking" showed 12dp above the call and 8dp below it, and
that is the reader's report that the spacing between the model's calls and its thinking
was not consistent. Nothing about those rows justifies three numbers: they are all one
thing after another within one turn, and neither the type of row nor its order is a
fact about the transcript's structure. What *is* structural is a prompt opening a turn
and the turn that answers it, so those are the two numbers left — and they stay
distinct from the module gap on purpose, because one number for every pair is the design
this section opened with.

**The same report came back a second time**, and the reason it did is that the module
gap is not the whole of the space between two modules. `AssistantBubble` is *one* row
that can hold three things — the reasoning control, the answer, the error — and the gap
between its own parts used to be unconditional: a 5dp spacer separated the control from
the answer, and it was emitted whether or not there was an answer under it. A step that
thinks and then calls a tool *has* no answer, which is most steps of most turns, so
every one of those rows ended with 5dp of nothing. The distance from a tool call up to
the thinking above it was `MODULE_GAP` + 5dp while the distance down to the next call
was `MODULE_GAP`, and no amount of tuning `rowGap` could have fixed it — the asymmetry
was inside a row, one file away, and invisible to the test that pins the rhythm.
Measured against the two constants: 13dp against 8dp, in the "thinking → bash →
thinking" shape the reader named. The spacer now belongs to the pair it separates
(`REASONING_ANSWER_GAP`) and is only emitted when something follows it.

The step rail is gone as well. A run of two or more tool calls used to be inset 12dp
from the turn's margin and joined by a 2dp bar down its left edge; the reader's report
was that the bar said nothing the rows' own spacing could not, and the inset cost every
row 12dp of width — and, because it applied to *all* tool rows, rail or not, it moved
every tool's mark 12dp right of every other row's text. The rows now start at the
transcript's own margin, like the thinking and the answer they belong to.

Two further numbers came from the same review. The prose style is `bodyMedium` with a
22sp line box rather than the theme's 20sp — a 1.43 ratio is under the 1.5 that WCAG's
visual-presentation criterion asks for within a paragraph, and 22sp is 1.57 — and the
transcript column is capped at 560dp and centred once the window reaches 600dp, which
is where 80 Latin characters and 40 Han characters per line coincide. On a 411dp phone
the gutters already put a line at about 55 Latin characters, so nothing is capped
there.

## There is no row entrance animation, and that is the fix

`Modifier.animateItem` was added to every row and taken straight back out. It replays
its appearance animation **every time an item enters composition**, and in a lazy list
that is every time a row scrolls back into view — so the transcript flickered while
scrolling — while its default placement and disappearance springs fought the follow
rule, which is scrolling the same list on every streamed token. The symptom was
reported as "a serious animation bug and flickering while outputting or scrolling", and
both halves are one cause.

A message list is the one list where an entrance animation cannot be afforded: the
reader is watching it change continuously, and the only rows that are genuinely new are
the two ends of a turn. Anything that animates position there is animating the thing
the reader is reading.

## Reasoning is folded until the reader opens it

The block used to open on its own while the reasoning streamed and fold itself a
second after the answer began, with a `rememberSaveable` `touched` flag so a tap won.
It was removed, and the report is why — it made every turn taller by a screenful of
grey reasoning the reader had not asked for, it moved everything below it twice per
turn (once when it opened, once when it closed), and it fought anyone who was
scrolling while it happened. The row's own label carries the progress
(`思考 · 1.2k 字`), so the block is one tap away and nothing moves under the reader's
eyes.

What the label says is a *count*, not a duration. A duration is what the other
implementations show (`Thought for 1.2s`) and the reducer still does not record when
thinking stopped, so inventing one would bill the tool calls that ran before it; the
turn band above the answer already reports how long the whole turn took. The count is
live while the reasoning streams — hiding it was the earlier design and it left the
row with nothing that moved on it.

The block itself is a filled `surfaceContainerLow` panel at 12dp, capped at 240dp with
its own scroll: reasoning arrives unbounded, and an uncapped block pushed the answer —
the thing that was asked for — off the bottom of the screen with nothing to say it was
there. It used to be a bare block behind a 2dp left rule, which said "secondary" twice
and cost an `IntrinsicSize.Min` row — an extra intrinsic measure of a block of text that
grows on every streamed token, on every frame.

**The italic went with the left rule.** The convention it came from is Anthropic's own
"the reasoning as gray italic text", and it is the half of that convention this app
cannot use: the reasoning is mostly Han in Chinese and Japanese, a CJK face has no
italic, and the platform synthesises one by slanting the glyphs — which makes a 14sp Han
line *harder* to read, not easier, and slants the Latin paths and command names inside
the reasoning in the same pass. Muted colour on its own is the whole signal, and the
panel's fill already separates the block from the answer under it.

The page's three disclosure controls — the turn band, a tool row and the reasoning
row — share one chevron (`DisclosureChevron`): one glyph that turns 180° rather than
two that swap, one size, and the accessibility label on the control rather than on the
arrow inside it.
