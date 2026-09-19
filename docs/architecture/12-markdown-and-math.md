# What the model writes: Markdown

*[Architecture](../ARCHITECTURE.md) §12, part 1 of 3.*

What a reply is made of, and the walk that turns it into text. A formula is typeset by the
renderer in [part 2](12-mathematics.md), and the tables, display formulas and marks are
[part 3](12-display-and-marks.md).

The transcript's **Markdown** renderer is hand-written, and deliberately: a reply's vocabulary is
narrow — prose, fenced code, headings, lists, quotes, tables, inline emphasis — and those blocks
are cheap to draw directly, where a full CommonMark engine would be a dependency for constructs
the agent does not emit. `Markdown.kt` parses the blocks and the inline runs; `MathView.kt`
renders mathematics and owns the measurement of a formula, and the boundary of what each supports
is written out construct by construct in the source.

## What the parser handles, and what it does not

Blocks: fenced code (```` ``` ```` and `~~~`), ATX and setext headings, bullet, ordered and task
lists with nesting, block quotes holding further blocks, thematic breaks, pipe tables with
alignment, display formulas, and paragraphs with soft and hard breaks. Inline: escapes, code
spans, emphasis and strong emphasis, strikethrough, inline links and images, reference links,
autolinks, bare urls, and inline mathematics.

Deliberately absent, and each for its own reason rather than for lack of time:

- **Indented code blocks.** Four leading spaces are how a model indents a *nested* list, which is
  far more common in agent output; treating the indent as code turned a nested list into a grey box
  of its own source.
- **Raw HTML.** It is drawn as the text it is; silently swallowing a `<div>` would hide that
  nothing here renders HTML.
- **List items holding blocks.** An item is a string with a `depth`, so an item cannot hold a code
  block or a table. Recursing would make `depth` a property of the parser rather than of the item.
- **Footnotes.** The definition line is removed and no footnote list is drawn; the `[^1]` marker
  is left as text. A reply cites a paper rather than footnoting it, and every marker seen in
  practice pointed at something this app cannot fetch.
- **HTML entities.** `&amp;` is drawn as written. Decoding them is trivial and *not* decoding them
  is what makes a reply about HTML entities legible.

**Reference links are resolved at parse time, not at draw time.** `[label]: url` is lifted out of
the stream, and every `[label]`, `[label][]` and `[label][id]` below it is rewritten to
`[label](url)` before any block is drawn. Carrying the definition map into the token walk instead
would thread a map through `renderInline`, `rememberMathInline` and every caller of both for a
construct resolved once. A reference with no definition is left as written: `[1]` in prose about a
list is a `[1]`.

## Syntax highlighting, which was hand-written and has been removed

A code block was coloured by `SyntaxHighlight.kt`: one forward pass with no regular expressions and
no backtracking, in ten hand-written rule sets (shell, Python, Kotlin, Java, JavaScript, JSON,
YAML, SQL, Go, Rust), with a palette derived from the Material colour scheme. It worked, and it was
removed on request — "没有必要且难以覆盖所有编程语言".

**A code block was monospace and uncoloured** before it, and it is again. The libraries that do
this well are the wrong shape: Shiki and TextMate need a JavaScript engine and a grammar per
language, the Compose options are unpublished or a JS bridge, and `codehighlight-parser` (130 KB,
no network) needs Kotlin 2.3.20 where this project's compiler reads 2.1.0. The choice was never
"hand-written or a good library"; it was "hand-written, or not at all".

What settled the removal is the ten-rule-set ceiling: ten languages out of the hundreds a model
writes a fence for, with the feature absent exactly where the language is unfamiliar and present
exactly where the reader could already read the code — a keyword is an identifier matched whole,
and an unknown language is drawn plainly rather than guessed at. A file, a test, a palette and a
per-language list buys less than a wrong colour a reader cannot tell from a right one. What replaced
it is the fence's own language label, monospace at `bodySmall` — it says *what* the block is.

The old scanner's two properties still hold for anything that runs on every streamed token: it
cannot hang and cannot throw (a `"` with no closer and a `/*` with no `*/` are the *normal* state of
the input), and it is linear and yields offsets rather than styled text, so it has no opinion about
the theme and can be asserted on the JVM — the rules `tokenizeInline` in `Markdown.kt` follows.

## What the inline walk has to get right

The inline grammar and the mathematics are found by **one** tokeniser (`tokenizeInline`) and drawn
by **one** pass (`renderInline`). It used to be two walks: the paragraph was cut at its formulas
first, and the emphasis parser ran over the pieces — so `**b $x$ c**` reached the parser as `**b `
and ` c**`, neither half holds a pair of delimiters, CommonMark does not open an emphasis without
one, and both halves were drawn literally. The emphasis run is now open across the placeholder, and
for `**b $x$ c**` the assertion is that there is exactly one bold span, starting at `b` and ending
after `c`, with the formula as inline content inside it.

Three rules in the walk are load-bearing:

- **What is a formula.** `$…$` needs a closing `$` on the same line, no space just inside either
  delimiter, and a non-empty body — without those rules a price, "it costs $5 and $10 today",
  becomes the formula `5 and `. `$` is also escaped by a backslash, and the escape is counted
  rather than assumed: `$\pi$` must not be read as an escaped delimiter just because a backslash
  follows the opening `$`. `MarkdownMathTest` pins both. Deliberately *not* mathematics is a bare
  command — `\frac{1}{2}` on its own is text, because treating any backslash-command as mathematics
  turns `C:\Users` into a formula.
- **A refused formula falls back to its own source.** `inlineContent` returns null for an empty or
  unmeasurable formula, and appending a placeholder the map does not hold throws inside `Text`
  rather than drawing nothing; the walk puts the source text back. That path is also what a caller
  that is only *measuring* uses, which is why the two cases cannot drift apart.
- **The placeholder's alternative text is the formula's own source.** `TextMeasurer` measures a
  placeholder from its `Placeholder`, and the annotated string carries the size in an annotation
  whose tag is `androidx.compose.foundation.text.inlineContent`. A table column's width is measured
  through that pair (`MathInline.placeholders`), which is what makes a column holding a fraction as
  wide as the fraction rather than as wide as the source text beside it.

## The cost that is paid on every token

- **A paragraph's inline render is O(its own length), and used to be O(n²).** The old walk called
  itself on `text.substring(index)` for each piece between formulas, which copies the remainder of
  the message once per formula *and* once per formula-shaped `$` — a reply with a price in it, `$5`
  and `$10`, paid the scan twice per line. The walk is index-based now and the tokeniser is a single
  forward pass, so the cost is a function of the text rather than of the text and its dollar signs.
- **A block's `MathInline` is `@Immutable`, and that is load-bearing.** Compose infers a class's
  stability from its fields, and `AnnotatedString` and `Map` are both read as unstable; without the
  annotation every `Text` in a rendered reply was recomposed on every pass of the transcript —
  while an answer streams, every token, for every block on screen.
- **A reply is a plain `Column`, and the virtualisation moved into the formula.** A `LazyColumn`
  was tried for one round, on the reasoning above: the transcript's own list then measures *every*
  block of a reply to learn the height of the one item the reply occupies, and a formula's
  measurement is the expensive one. Measured with `FrameMetrics` on the emulator, that cut the
  layout pass from 305.9 ms to 218.4 ms.

  It is a `Column` again because the `LazyColumn` was a crash in two of the three places
  `MarkdownText` is drawn — the transcript, and `ManualPage` and the file preview inside
  `SettingsBody`'s `Column(verticalScroll)`. A scrollable measured inside a scrollable gets
  `Constraints.Infinity` on the main axis, and a `LazyColumn` refuses it outright —
  `IllegalStateException: Vertically scrollable component was measured with an infinity maximum
  height constraints, which is disallowed` — which was the reader's "打开设置页用户手册点击也会闪退". A
  tall reply drew faster and a page could not be opened at all.

  What replaced the saving for one round was a per-block deferral (`DeferredMeasurement`); **it was
  measured and removed** — 763 ms against 741 ms on the 300-block reply below, because the
  placeholder is measured too and the parent still has to know the item's height. What paid is one
  measurer per reply rather than one per block.

**Why the `LazyColumn` looked safe.** "A nested vertical `LazyColumn` is legal" is true under the
transcript and false under a `verticalScroll`, and the rule was checked against the one caller that
satisfies it. A shared composable's constraints are the union of its call sites.

**The prompt bubble's width, got wrong the same way.** It lives in `ChatScreen.kt`'s `UserBubble`,
and Material3's `Surface` wraps its content in a `Box(propagateMinConstraints = true)`, so a cap
applied to the box *around* the bubble travels into the bubble's `Column` and stretches it — the
reports "气泡是固定宽度" and "用户消息靠左了" are one bug, the text sitting at the start of an
86%-wide bar. The cap has to be imposed where `Surface` cannot see it, as a `widthIn(max = …)` on a
`BoxWithConstraints`; `Alignment.End` was never wrong, being `BiasAlignment.Horizontal(1f)`, i.e.
`space - size`.

## Inline code has a rounded fill, because a span cannot

`SpanStyle.background` is a plain colour fill with no corner radius, and ui-text 1.10 has no
`BackgroundStyle` — checked in the artefact. So a code span is *measured content* now, like a
formula: `rememberCodeChip` measures the span's text, wraps the result in a `Box` with a background
and a 6dp radius, and declares it as an `InlineTextContent`. That is the report
"行内代码背景无圆角很难看".

Three details are load-bearing. The placeholder is **clipped** to the size it declares, so the
height has to be the line box rather than the text's own height — a shorter one cuts the fill's top
and bottom off instead of shrinking it. The width carries a 3dp pad on each side so the fill does
not touch its own glyphs. And the table path passes `code = null`, so a measured grid still uses the
plain `SpanStyle`: a filled chip in every cell of a measured table reads as a grid of errors, and
only the widths are wanted there.

## The tests that could not catch it

`Regex("^\\\\begin\\{(\\w+\\*?)}")` is accepted by the JVM and rejected by Android's own ICU
engine, which fails the whole class initialiser: every Markdown block in the app threw
`ExceptionInInitializerError` the moment a reply started arriving, and the JVM suite for the same
code was green. It is `indexOf` now. For a *rendering* change the unit tests settle the parser and
the device settles the renderer; the two do not overlap, so a third-party formula renderer has to be
verified on a device.
