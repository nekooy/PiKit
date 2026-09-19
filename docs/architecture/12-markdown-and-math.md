# 12. What the model writes: Markdown, mathematics and tables

*[Architecture](../ARCHITECTURE.md) §12.*

The transcript's **Markdown** renderer is hand-written, and deliberately: what a reply
contains is a narrow vocabulary — prose, fenced code, headings, lists, quotes, tables,
inline emphasis — and those blocks are cheap to draw directly. A full CommonMark engine
would be a dependency for constructs the agent does not emit. `Markdown.kt` parses the
blocks and the inline runs; `MathView.kt` renders mathematics and owns the measurement
of a formula. Neither is a library, and the boundary of what each supports is written
out construct by construct in the source rather than left to be discovered.

## What the parser handles, and what it does not

Blocks: fenced code (```` ``` ```` and `~~~`), ATX headings, setext headings, bullet,
ordered and task lists with nesting, block quotes holding further blocks, thematic
breaks, pipe tables with alignment, display formulas, and paragraphs with soft and hard
breaks. Inline: escapes, code spans, emphasis and strong emphasis, strikethrough, inline
links and images, reference links, autolinks, bare urls, and inline mathematics.

Deliberately absent, and each for its own reason rather than for lack of time:

- **Indented code blocks.** Four leading spaces are how a model indents a *nested* list,
  which is a far more common thing in agent output than an indented code block; treating
  the indent as code turned a nested list into a grey box of its own source.
- **Raw HTML.** It is drawn as the text it is. Nothing here renders HTML, and silently
  swallowing a `<div>` would hide that.
- **List items holding blocks.** An item is a string with a `depth`, so an item cannot
  hold a code block or a table. Recursing would make `depth` a property of the parser
  rather than of the item.
- **Footnotes.** The definition line is removed and no footnote list is drawn; the
  `[^1]` marker is left as text. A reply cites a paper rather than footnoting it, and
  every marker seen in practice pointed at something this app cannot fetch.
- **HTML entities.** `&amp;` is drawn as written. Decoding them is trivial and *not*
  decoding them is what makes a reply about HTML entities legible.

**Reference links are resolved at parse time, not at draw time.** `[label]: url` is
lifted out of the stream, and every `[label]`, `[label][]` and `[label][id]` below it is
rewritten to `[label](url)` before any block is drawn. The alternative — carry the
definition map into the token walk — would thread a map through `renderInline`,
`rememberMathInline` and every caller of both, for a construct resolved once. A
reference with no definition is left exactly as written, which is the whole difference
between a reference and a bracket: `[1]` in prose about a list is a `[1]`.

## Mathematics is not hand-written, and the reason is a table in the font

A formula's
geometry lives in the OpenType `MATH` table: the axis a fraction bar sits on, that bar's
thickness, how far a script is raised, the gap a radical needs, and the `Size1`–`Size4`
glyph variants a large operator uses to grow. **Android's public text API does not expose
it.** `Paint` offers `getFontMetrics` (ascent, descent, leading), `setFontFeatureSettings`
and `setFontVariationSettings`, and nothing that reads a math constant. A hand-written
renderer therefore has to invent every one of those numbers, and the inventions are
exactly the failures a reader reported:

- a fraction whose rule sat on the numerator's measured height instead of the axis;
- an integral that could only be scaled by a fudge factor, because the glyph has no
  larger cut to select and no `Size` font to select it from;
- a vector arrow drawn as a combining character the font may or may not have.

So formulas are handed to **`ru.noties:jlatexmath-android`**, a port of JLaTeXMath, whose font
metric files are the `MATH` table's contents — the same reason the hand-written renderer could
not work, answered by a library that solves it the way TeX does. It replaced
`io.github.huarangmeng:latex-renderer` (KaTeX's own fonts and metrics, MIT), which typeset at
least as faithfully and cost **21.7 ms per formula**; the measurement that decided the swap is in
"What the formula measurement actually costs" below, and the report it comes from is the reader's
"还是无法解决卡顿问题，看看迁移到 operit 那套方案的可行性". The port is GPL-2.0 over upstream
JLaTeXMath's GPL-2.0 **with a linking exception**; both are named in the app's credits and in
[LICENSING](../LICENSING.md), and the exception is what lets this GPL-3 application link it.

## The two shapes a formula comes in

- **A display formula** — `$$…$$`, `\[…\]`, `\begin{aligned}` — is a block of its own drawn as a
  `Text` whose whole content is one placeholder, inside a horizontally scrolling parent: a formula
  is read left to right, and a wrapped one is two formulas. The environment is handed over
  **whole**, `&` and `\\` included, because the renderer knows what a `cases` or an `aligned` is.
  Drawing it as its own composable — which is what this was — leaves it out of the transcript's
  selection, which is the report "单独成段的公式仍无法选中复制".
- **Inline** mathematics is an `InlineTextContent` of exactly the typeset size, which is what lets a
  stacked fraction or a radical appear mid-sentence. The old hand-written renderer could not do
  this at all — it flattened mathematics to `SpanStyle`s, because a placeholder that must be given
  its size up front is useless without a measurer — which is why `$\frac{a}{b}$` in a sentence drew
  as `(a)/(b)`.

### The line box, which is the part Compose does not do for you

**A placeholder can grow only one side of its line, and a formula needs both.** Compose places an
inline placeholder by an edge, and which edge decides which side grows — read out of
`PlaceholderSpan.getSize`:

| `PlaceholderVerticalAlign` | what it does to the line |
| --- | --- |
| `AboveBaseline` | `ascent = min(ascent, -height)` — only the ascent |
| `TextTop` | `descent = max(descent, ascent + height)` — only the descent |
| `TextBottom` | only the ascent, measured from the text's descent |
| `TextCenter` | **both**, by centring the box on the text's own centre |

A formula's baseline is not its bottom — a fraction and an integral hang below the line they are
written on — so it needs an ascender's room *above* the baseline and a descender's room *below* it.
`MathView.kt` therefore declares the smallest box **centred on the text's centre** that contains the
formula's ink (`half = max(|inkTop - centre|, |inkBottom - centre|)`) and draws the drawable inside
it at the offset that puts the formula's own baseline on the line's. A formula no taller than the
text leaves the line height alone; a stacked fraction grows its own line, which is what a stacked
fraction has to do. The text's centre comes from a `TextMeasurer` measurement of the same style,
because the sentence's font decides it and the renderer cannot know it.

**Two versions are in `docs/VERIFICATION.md` and neither is worth repeating.** Filling a placeholder
aligned by its bottom puts every fraction and integral a whole depth above its line ("公式渲染位置和
排版存在严重问题"); doing that *and* pushing the drawable down by its depth fixes the baseline and
makes a tall formula overlap the line above, because the placeholder never grew the descent. That
second version is also why a display formula lost the bottom half of a sum to a clipping scroll
container: its ink was drawn outside the height its own block reported.

The **ink colour** comes from the scheme and is fixed at typeset time rather than tinted, and it
is part of the cache key: JLaTeXMath draws into the canvas its caller supplies and has no notion of
a theme, so a light/dark switch re-typesets — 0.35 ms a formula, so that is not a decision worth
complicating. **Accessibility is what the swap gave up.** The KaTeX renderer could publish a
MathSpeak-style description through the semantics tree, so TalkBack read "fraction: 1 over 2"; a
`Drawable` cannot, and a screen reader now reads the LaTeX source — which is the placeholder's
alternative text and was always there, but it is not the same thing. Nothing was found that
replaces it without writing a MathSpeak generator, which is not what this change was for.

### A formula the renderer refuses, and the four spellings of one

A model writes mathematics in more ways than one, and the two that are not Markdown's are the ones
that used to come out as text. `$…$` and `\(…\)` are both read inline — the tokeniser has known
`\(…\)` all along — and `\[…\]` is now read inline as well when it is *not* alone on its line, which
is what "由 \[E = mc^{2}\] 可知" is. That last one had a second half: the tokeniser produced the
formula and `unescapeMath` did not strip `\[`/`\]`, so the renderer was handed the brackets, refused
them, and the reader saw the source. Between the two, a formula written TeX-style inside a sentence
looks exactly like prose until it is read — which is why it was reported as "行内公式换了行就不渲染".

**A body padded with spaces is mathematics when it looks like mathematics**, and that qualifier is
the whole of the fix for the *next* report, "这种换行的仍然没渲染". `$ P(A_i\mid B) $` is how TeX is
written and how a model writes it when it is thinking about a paper rather than about Markdown; the
price rule rejected a padded body outright, so the reader's formula was drawn as its own source and
wrapped like prose. The rule now asks whether the body carries a `\command`, a script (`^`/`_`), a
group (`{}`) or a relation (`=`), which every formula has and no sentence about money does:
"it costs $ 5 and $ 10" stays a sentence, `$ x^{2} $` becomes a formula. The boundary it leaves is a
padded body with neither — `$ x $` — which stays prose, and that is the right way round, because a
formula shown as its source is legible and merely ugly while a price typeset as mathematics is a
sentence that has been mangled.

For the constructs the renderer genuinely does not have, `LatexCompat.kt` rewrites the body and
tries again before giving up, because drawing the source is a bad first resort and a fine last one:

| kind | example | rewrite |
| --- | --- | --- |
| numbering and labels | `\tag{1} x=1`, `\label{eq:1}` | dropped — they draw nothing |
| a decoration | `\cancel{x}`, `\hcancel{x}`, `\sout{x}` | `x` — the decoration is lost, the term is not |
| another spelling | `\color{red}{x}` | `\textcolor{red}{x}`, or an alias in JLaTeXMath's macro table |
| chemistry | `\ce{H2O}` | `\text{H2O}` |

Eight of forty common constructs failed before this existed, enumerated by logging the body every
time the renderer refused one — `\ce`, `\color`, `\tag`, `\label`, `\cancel`, `\hcancel`, `\oiint`,
`\oiiint` — and everything else in that sweep already worked, `\begin{align}`, `\operatorname`,
`\overbrace`, `\xrightarrow`, `\substack`, `\boxed` and `\text{中文}` among them. `\oiint` and
`\oiiint` are left failing on purpose: they are single glyphs the bundled fonts do not have, and any
rewrite would be an invented picture of a surface integral. A formula that still fails is logged by
body, so "某些情况没渲染" is a line in `logcat` rather than a guess.

### A formula too wide for its line

Two widths, two answers, because the two places a formula can be are not alike.

- **A display formula scrolls, and now says so.** Its block is one `Text` inside a
  `horizontalScroll`, which is what makes a wide derivation reachable at all — and nothing used to
  indicate that, which is the report "当一行的公式过长时，无明显暗示能够滑动查看完整内容". Two hints
  are drawn over it: a gradient that fades the last 18dp on whichever side has more to show, and a
  pill at the bottom reporting how much of the formula is on screen and where in it the reader is.
  The pill is drawn rather than a scrollbar because Android has no `HorizontalScrollbar`, and a real
  one would want the drag gestures the scroller already owns.
- **An inline formula is scaled down instead.** It cannot scroll: the paragraph scrolls vertically,
  the transcript does not scroll horizontally, and a horizontal drag inside a paragraph belongs to
  the selection. An inline formula wider than its line is therefore drawn uniformly scaled to fit,
  down to a floor of half size — below which a formula is unreadable rather than merely small, and
  it overflows the way it did before any of this. `MarkdownText` publishes the reply's own width
  through `LocalFormulaWidth` from a single `BoxWithConstraints`, because a placeholder's size has
  to be decided before the line it will sit on is known, and a subcomposition per paragraph would
  charge for the same number once per block.

## The cost that was measured before adopting it

**The renderer this section describes has since been replaced** — see "What the formula
measurement actually costs" below for the measurement that ended it. What is kept here is the
*other* half of that decision, because it did not go away with the dependency: the toolchain was
moved to satisfy it, and the moves are still in the build.

Every number here was read from the published artefacts, not estimated.

| | |
| --- | --- |
| Added to the APK | 1.09 MB `latex-renderer` (809 KB bytecode + 19 KaTeX fonts) + 490 KB `latex-parser` + 9 KB `latex-base` |
| Its `minSdk` | 23, below this project's 26 |
| Its `minCompileSdk` | **36** — this is what moved `pikit.compileSdk` from 35 |
| What it needs from Kotlin | metadata 2.1.0; the `-kt2.1.0` artefacts are the variant this project can read |
| What it needs from Compose | Jetpack Compose 1.9.4 / Material3 1.4.0, i.e. `compose-bom` **2025.11.00** |

The version pairing is not arbitrary and is worth stating, because it is the kind of thing
that rots silently: **Compose Multiplatform 1.8.0 and later require a dependency compiled
with at least Kotlin 2.1.0**, and a Kotlin compiler can only read metadata from its own
version or below. Kotlin 2.0.21 therefore could not read this library at all. Every
published release from 1.2.7 on — suffixed or not — asks for the same
`kotlin-stdlib 2.2.10` + Compose `1.9.3`, so there was no older library version that
avoided the toolchain move. AGP went to **8.13.2** because `compileSdk 36` needs an AGP
that knows it; **AGP 9 was deliberately not taken**, because it turns on built-in Kotlin
and defaults `defaultTargetSdkToCompileSdkIfUnset`, the one default that must never reach
`pikit.targetSdk=28` (see §1).

**None of the four moved back with the dependency, and that is deliberate.** `minCompileSdk 36`
was the one requirement that forced `compileSdk`, AGP and Kotlin together, so in principle the
whole set could come down again — but `compose-bom` 2025.11.00 is still what the UI is built
against, and re-deriving a compileSdk from the current dependency set is a change of its own with
its own measurement. What the swap did do is remove the *reason* the versions are what they are,
which is worth knowing before the next person tries to move them.

`jlatexmath-android`'s own requirements are trivial next to that: `minSdk 16`, `targetSdk 28`,
a single `androidx.annotation` dependency, 1.12 MB of fonts and bytecode, and an
`InitProvider` that installs the context its fonts are loaded from — verified in the merged
manifest, because every formula would fail on the first draw if it were missing.

**Why not KaTeX in a WebView.** It would typeset as faithfully and move no toolchain, but
each formula needs its own WebView surface to stay out of the transcript's scrolling, and
a reply can carry dozens. The dependency above is 1.6 MB of local code; that is a
different order of cost.

**Why the Markdown half was left alone.** The same author publishes a matching Markdown
renderer, and it was measured and twice rejected. Every version of it — 1.4.0 through
1.5.3, the latest — pulls in `ktor-client-android`, Coil 3, a diagram renderer and a
syntax highlighter, for remote images and Mermaid; this app fetches nothing to draw a
reply, and adding an HTTP client and an image loader to draw *text* is a different
program. It also needs Kotlin 2.4.0 metadata, Compose Multiplatform 1.11.1 and an
**alpha** Material3 (`1.10.0-alpha05`), and `ui 1.12.1` in that stack requires
`minCompileSdk 37` with AGP 9.1 — the AGP that defaults `targetSdk` from `compileSdk`.
The blocks an agent actually writes are ten constructs, all of them already drawn here.
The parser alone was considered too: `markdown-parser-android` is a genuine 130 KB with
no network dependency, but it also asks for Kotlin 2.4.0 metadata, which this compiler
cannot read either.

## Syntax highlighting, which was hand-written and has been removed

A code block was coloured by `SyntaxHighlight.kt`: one forward pass with no regular
expressions and no backtracking, in ten hand-written rule sets (shell, Python, Kotlin,
Java, JavaScript, JSON, YAML, SQL, Go, Rust), with a palette derived from the Material
colour scheme. It worked, and it was removed on request — "没有必要且难以覆盖所有编程语言".
The argument is worth writing down in full, because the argument for removing it is
stronger than the one that put it there.

**A code block was monospace and uncoloured** before the highlighter was added, and it
is again. The libraries that do this well are the wrong shape for the same reasons:
Shiki and TextMate need a JavaScript engine and a grammar per language, the Compose
options are unpublished or a JS bridge, and `codehighlight-parser` (130 KB, no network)
needs Kotlin 2.3.20 where this project's compiler reads 2.1.0 — the same wall the
Markdown half of this chapter runs into. So the choice was never "hand-written or a
good library"; it was "hand-written, or not at all".

What the removal rests on is the ceiling of the hand-written version. Ten rule sets is
not "programming languages"; it is ten languages out of the hundreds a model writes a
fence for, and the two rules that decide whether highlighting *helps* — a keyword is an
identifier matched whole, a language this file has never heard of is drawn plainly
rather than guessed at — mean the feature is absent exactly where it is unfamiliar and
present exactly where the reader could already read the code. That is the shape of a
feature whose cost (a file, a test, a palette, and a per-language list that has to be
maintained against the models' habits) buys less than the risk it carries: a wrong
colour a reader cannot tell from a right one is worse than no colour, and there is no
way to tell from a screenshot which of the ten sets is being applied.

What replaced it is the fence's own language label, monospace at `bodySmall`. The label
is the part that carries information the reader does not already have — it says *what*
the block is — and it costs one line of code.

**A note for the next feature that looks like this one.** The old file's two properties
are still the right ones for a scanner that runs on every streamed token: it cannot hang
and cannot throw (a `"` with no closer and a `/*` with no `*/` are the *normal* state of
the input), and it is linear and yields offsets rather than styled text, so the scanner
has no opinion about the theme and can be asserted on the JVM. `tokenizeInline` in
`Markdown.kt` was written to the same two rules.

## What the inline walk has to get right

The inline grammar and the mathematics are found by **one** tokeniser
(`tokenizeInline`) and drawn by **one** pass (`renderInline`), and that is a
correction rather than a tidy-up. It used to be two walks: the paragraph was cut at
its formulas first, and the emphasis parser ran over the pieces.

The cost of the old shape was a documented limit that turned out to be a visible
bug. A formula was removed from the fragment before the prose was parsed, so
`**b $x$ c**` reached the parser as `**b ` and ` c**` — and neither half holds a
pair of delimiters. CommonMark does not open an emphasis without one, so both halves
were drawn literally and the reader saw the asterisks. The old file recorded it as
"needs the emphasis state to survive the cut, which means the walk has to own the
builder and reopen a span the previous fragment left open". That is what the
tokeniser is: the emphasis run is open across the placeholder, and for
`**b $x$ c**` the assertion is now that there is exactly one bold span, starting at
`b` and ending after `c`, with the formula as inline content inside it.

Three rules in the walk are load-bearing:

- **What is a formula.** `$…$` needs a closing `$` on the same line, no space just inside
  either delimiter, and a non-empty body — without those rules a price, "it costs $5 and
  $10 today", becomes the formula `5 and `. `$` is also escaped by a backslash, and the
  escape is counted rather than assumed: `$\pi$` must not be read as an escaped delimiter
  just because a backslash follows the opening `$`. `MarkdownMathTest` pins both. What is
  deliberately *not* mathematics is a bare command: `\frac{1}{2}` on its own is text,
  because treating any backslash-command as mathematics turns `C:\Users` into a formula.
- **A refused formula falls back to its own source.** `inlineContent` returns null for an
  empty or unmeasurable formula, and appending a placeholder the map does not hold throws
  inside `Text` rather than drawing nothing. The walk puts the source text back — and that
  path is also what a caller that is only *measuring* uses, which is why the two cases
  (the renderer refused, the caller did not ask) cannot drift apart.
- **The placeholder's alternative text is the formula's own source.** `TextMeasurer`
  measures a placeholder from its `Placeholder`, and the annotated string carries the
  size in an annotation whose tag is
  `androidx.compose.foundation.text.inlineContent`. A table column's width is measured
  through that pair (`MathInline.placeholders`), which is what makes a column holding a
  fraction as wide as the fraction rather than as wide as the source text beside it.

## Formulas in a table, which were not rendered

A table cell used to be rendered by a helper that flattened `$x$` to its literal
source. The effect is easy to miss in a screenshot of a table of *text* and impossible
to miss in a reply about a model's dimensions: the cell showed `$\frac{a}{b}$` where a
fraction belongs. A cell is now rendered by the same `renderInline` a paragraph uses,
with the formula measurer attached, and the *same* rendered cell is both what the
column width is measured from and what is drawn — so the measurement and the drawing
cannot disagree about what the cell is. The report was "表格等内容里的公式为什么不会渲染".

The one cell-local decision is the code fill: a table measures its columns with
`InlineStyle(codeBackground = Color.Unspecified)`, because a filled span in every cell
of a measured grid reads as a grid of errors. The drawn cell keeps its fill.

## The known limit that is left

The old chapter asserted one limit and it has been fixed (above). What is left is
narrower and is not asserted by a test because it is not wrong, only absent: an
emphasis that wraps a formula *inside a table cell* is measured with the renderer
detached (see above), so a `/` in a cell is drawn as a literal. That is the same
trade the column width makes.

**Tables.** `border(…, shape)` draws a rounded outline; it does not clip what is
inside it, so the header row's own rectangular fill painted *past* the rounded
border — two square colour blocks sticking out either side of the top corners, which
is the "colour blocks outside the table" a reader sees first. `clip(shape)` before
the border is the whole of that fix.

The width is a separate decision. The old layout gave every column a weight from a
character count, so a table with a handful of columns was squeezed until every cell
wrapped and the rows grew to ten lines. The columns are measured now, with a
`TextMeasurer` on the cells' own annotated strings, because the width of `缓存命中`
and the width of `context` are not related to how many characters they have. A
column is capped at three fifths of the screen, and the table either fits — filling
the width with the cells wrapping — or keeps its natural width inside a horizontal
scroll. Three fifths is about the *scroll*: measured with a six-column table of
sentences, 0.9 gave 1.1 columns per screen, a table read one column at a time with
nothing on screen to say there was a second, where 0.6 gives about 1.7.

## Selecting a formula, and the two shapes it made awkward

**Superseded by "Display formulas are paragraphs" below.** This section is kept because
the reasoning in it is what the next round had to correct, and the correction is only
legible against it.

A display formula was inside a `horizontalScroll` box, and a `horizontalScroll` can claim a
drag that starts on its content. The transcript is one `SelectionContainer`, so the
consequence was exact and asymmetric: a reader could select and copy a formula written
inside a sentence, and could not drag a selection across one written on a line of its own.
The fix then was to disable the scroll (`userScrollEnabled = false`) and accept that a
formula wider than the screen was clipped rather than pannable.

**That diagnosis named the wrong owner of the drag.** A `horizontalScroll` claims only a
*horizontal* drag, and the transcript does not scroll horizontally — the drag a selection
uses is vertical and was never in dispute. So disabling the scroll bought nothing and cost
every over-wide formula, which is the later report "无法再左右滑动了，长的公式直接显示不全".
It was re-enabled, and the selection problem was solved properly instead, by making the
formula part of the text.

## The cost that is paid on every token

Two things about this file's work are worth knowing before changing it, because both are
invisible in a profile that only looks at one paragraph.

- **A paragraph's inline render is O(its own length), and used to be O(n²).** The old
  walk called itself on `text.substring(index)` for each piece between formulas, which
  copies the remainder of the message once per formula *and* once per formula-shaped
  `$`. A reply with a price in it — `$5` and `$10` — paid the scan twice per line. The
  walk is index-based now and the tokeniser is a single forward pass, so the cost is a
  function of the text rather than of the text and its dollar signs.
- **A block's `MathInline` is `@Immutable`, and that is load-bearing.** Compose infers
  a class's stability from its fields, and `AnnotatedString` and `Map` are both read as
  unstable; without the annotation every `Text` in a rendered reply was recomposed on
  every pass of the transcript, which while an answer streams is every token, for every
  block of every message on screen.
- **A reply is a plain `Column`, and the virtualisation moved into the formula.** It was
  a `LazyColumn` for one round, on the reasoning above: the transcript's own list
  therefore had to measure *every* block of a reply to learn the height of the one item
  the reply occupies, off-screen blocks included, and a formula's measurement is the
  expensive one. Measured with `FrameMetrics` on the emulator that cut the layout pass
  from 305.9 ms to 218.4 ms.

  It is a `Column` again because the `LazyColumn` was a crash, in two of the three places
  `MarkdownText` is drawn. It is not only inside the transcript: `ManualPage` draws it
  inside `SettingsBody`, which is a `Column(verticalScroll)`, and so does the file
  preview. A scrollable measured inside a scrollable gets `Constraints.Infinity` on the
  main axis, and a `LazyColumn` refuses it outright —

  ```
  java.lang.IllegalStateException: Vertically scrollable component was measured with an
  infinity maximum height constraints, which is disallowed.
  ```

  — which was the reader's "打开设置页用户手册点击也会闪退". The saving was measured in one
  place and paid for in another: a tall reply drew faster, and a page could not be opened
  at all.

  What replaced the saving, for one round, was a per-block deferral
  (`DeferredMeasurement`): a display formula was laid out as a one-line placeholder and
  composed only once the placeholder was reported inside the window. **That was measured
  and removed** — 763 ms against 741 ms on the 300-block reply below, because the
  placeholder is measured too and the parent still has to know the item's height. What
  actually paid is one measurer per reply rather than one per block; see "What the formula
  measurement actually costs" below. The file it lived in has been deleted, because a
  mechanism that does not pay for itself is worse than no mechanism.

**Why the `LazyColumn` looked safe.** The rule "a nested vertical `LazyColumn` is legal"
is true — the outer list gives the inner one a bounded height, and it becomes illegal only
under a `verticalScroll`. The mistake was checking that rule against the *transcript*,
which is the one caller that satisfies it, and not against the other two. A shared
composable's constraints are the union of its call sites, and a page body is a call site.

**One thing this chapter is not the place for, but which belongs next to the above
because it was got wrong the same way**: the prompt bubble's width lives in
`ChatScreen.kt`'s `UserBubble`, and two revisions of its comment blamed the *alignment*
for a bug that was the *width*. Material3's `Surface` wraps its content in a
`Box(propagateMinConstraints = true)`, so a cap applied to the box *around* the bubble
travels into the bubble's `Column` and stretches it — which is one bug wearing two
descriptions ("气泡是固定宽度", "用户消息靠左了") because the text then sits at the
start of an 86%-wide bar. The cap has to be imposed where `Surface` cannot see it, as a
`widthIn(max = …)` on a `BoxWithConstraints`. `Alignment.End` was never wrong:
it is `BiasAlignment.Horizontal(1f)`, i.e. `space - size`.

## Display formulas are paragraphs, and every consequence of that

A display formula used to be its own composable (`MathBlock`, a `Latex` in a
`horizontalScroll`). It is now a `Text` whose entire content is one inline content — the
same mechanism an inline formula uses — and four separate reports are all consequences of
that one change:

- **It can be selected and copied.** A `SelectionContainer` selects what its `Text`s lay
  out; a composable beside the text is outside that, so a drag across a display formula
  selected nothing. "单独成段的公式仍无法选中复制" is that, and it is fixed by construction
  rather than by a gesture fix.
- **It is highlighted when selected.** A selection is drawn from the `TextLayoutResult` of
  the text it crosses, so a formula *inside* the text is highlighted by the same pass that
  highlights the words around it. This is also why "行内公式和代码…没有被选中的高亮效果" was
  reported *separately* from the display formula being unselectable: the inline ones were
  already inside the text, and the missing highlight was the composable case.
- **Long formulas scroll again.** `softWrap = false` (a wrapped formula is not the formula)
  plus `Modifier.horizontalScroll` on the parent, which is the report "无法再左右滑动了，
  长的公式直接显示不全". The version before this had deliberately disabled that scroll on
  the argument that a drag across a formula should extend the selection. That argument was
  wrong about *which* composable owns the drag: a `horizontalScroll` claims only a
  horizontal drag, and the transcript does not scroll horizontally, so the vertical drag a
  selection uses was never in dispute. A long-press still selects; a plain horizontal drag
  pans.
- **The delimiters have to be put back.** `MdBlock.Formula` holds the body with its
  `$$`/`\[`/`\begin{}` markers already stripped, and the inline path decides what is
  mathematics *by looking for a delimiter*. Handed the bare body it sees prose and draws
  the LaTeX source as literal text — which is what the first version of this did, caught in
  a screenshot rather than by a test (`$…$` is the one pair that works, because `\[…\]` and
  an environment are blocks and re-fencing one inside a paragraph would make its body the
  paragraph's content).

## What the formula measurement actually costs, and what was done about it

The reply that exposed this is a synthetic 12 KB reply of **300 blocks with ~120 formulas**
(60 display, 60 inline, plus table cells), opened from the history list. Everything below was
read from a probe around the measurement call and from `dumpsys gfxinfo`, not estimated.

| | |
| --- | --- |
| `parseMarkdown` | 23–41 ms for 300 blocks |
| compose + measure, one measurer per block | **807 ms**, 210 measurements, 210 distinct measurers, 1271 ms of measurement time |
| **a probe around the measurement call itself** | **260 calls, 3401 ms, `thread=main`, one call at 327 ms** |
| one block with one display formula, alone | 4 ms |
| the same reply with `accessibilityEnabled = false` | 826 ms — noise, so accessibility stays on |
| the measurer's identity, logged per provide | **changes on every one of 360 provides** |

**The cost is entirely formula layout: about 17 ms each, on the UI thread.** Nothing else in
the transcript is close. That is also where the comparison the reader drew comes in, and the first
version of this paragraph got it wrong: **Operit does not render off the UI thread.** Its
`LatexCache.getDrawable` builds the drawable on the calling thread — a Compose composition — and
keeps a 50-entry `LruCache` of them. What makes it smooth is that its renderer lays a formula out
in **0.42 ms**, so the main thread never notices. The lesson is the one this chapter acted on
second: moving the work is not the fix, and a fast renderer is.

**What was tried and did not work:**

- **`ProvideLatexMeasurer`** — one measurer per reply instead of one per block. It does not
  help the 17 ms (that is layout, not construction) and it cannot share across a font-family
  change, because the library replaces the font families and every `rememberLatexMeasurer` is
  keyed on them. It was kept at the time because it was right; it is gone now, because the swap
  removed the measurer along with the renderer.
- **`accessibilityEnabled = false`** — 85 ms across a reply, inside the noise.
- **Pinning the measurer** so the font-state change could not rebuild it: **180 calls, 3043 ms**,
  against 260 calls and 3401 ms. Better, and still 3 seconds, because a pinned measurer is
  still a measurer on the UI thread. Reverted, because it is not enough to be worth the
  divergence from the library's own API.
- **Making `MarkdownText` a `LazyColumn`.** Tried twice, and it threw
  `IllegalStateException: Vertically scrollable component was measured with an infinity
  maximum height constraints` **both times** — once from `ManualPage`, which draws it inside
  `SettingsBody`'s `Column(verticalScroll)`, and once from the transcript, which one would
  expect to supply a bounded height because an answer is a `LazyColumn` item. The second
  attempt was read out of `logcat` rather than assumed: the transcript does **not**, because
  the item is wrapped in a `SelectionContainer` and the bubble, and the constraint that
  arrives is infinite.
- **A hand-drawn `</>`** — four attempts, every one of them worse than the glyph Material
  ships. See below.

**What works is `MathCache`: the measurement leaves the UI thread.** A formula that has not
been measured is drawn as its own source, and the measurement is queued on a single-threaded
worker (single, because the library's measurer keeps its caches in plain `LinkedHashMap`s and
concurrent calls would corrupt them). The result lands in a `SnapshotStateMap`, which is what
redraws the block that asked. `MarkdownText` prefetches every formula in a reply when its text
changes, so the common case — opening a saved conversation — has the cache warm before the
first frame that draws it, and a `TextMeasurer` is thread-safe so the same worker measures the
inline code chips.

### What it bought, measured on the same 300-block reply

| | before `MathCache` | after |
| --- | --- | --- |
| the frame that opens the reply, `99th` | 850 ms | **93 ms** |
| scrolling 16 screens, `50th` / `90th` / `99th` | 24 / 34 / 600 ms | **17 / 18 / 26–53 ms** |
| scrolling, `Number Slow UI thread` | 8–38 | **0** |
| formula layout on the UI thread | 3401 ms | **none** |

`dumpsys gfxinfo` on a `-gpu host` emulator. The scroll figure is the one the reader asked
about ("就上下滑动特别是切换到某个消息时特别卡") and it is the one that moved: **0.5–2.4
milliseconds of jank per frame at the 99th percentile, against 600 ms before.**

### It was still not enough, and the renderer was why (2026-09-19)

"我觉得你现在公式渲染还是无法解决卡顿问题" was the report, and it was right: `MathCache` had made the
layout non-blocking and a formula-heavy reply still filled in over several seconds, showing LaTeX
source while it did. The probe that settled it drew the same 300-block document with the
mathematics switched off — the same blocks, the same text layout, no renderer at all.

| on the probe document | formulas on | formulas off |
| --- | --- | --- |
| the document's first composition and layout, cold process | 806 / 1128 / 1205 ms | **351 ms** |
| the same open again, renderer warm | 465 ms | — |
| the measurement worker's total after 20 s | **142 formulas in 3078 ms** (21.7 ms each) | 0 |
| `rememberLatexMeasurer`, once per block | 92 calls in **0 ms** | — |
| `keysFor` + `renderInline`, once per block | **0 ms** | — |

The measurer, the token walk and the annotated-string build were all free, so the renderer's own
layout was the whole of those three seconds: **`MathCache` had made a slow renderer
non-blocking, not fast.**

The replacement was measured before it was adopted, on the same emulator at the same 14 sp.
`ru.noties:jlatexmath-android` lays a formula out in **422 µs mean, 307 µs p50, 1.5 ms worst**
(including reading the intrinsic size it hands back) and draws one in **183 µs p50**, with 20 of
20 constructs rendering — `\begin{aligned}`, `pmatrix`, `cases`, `\text{}`, `\binom`,
`\sqrt[3]`, `\mathbb{}` and `\underset` among them. After the swap, on the same document:

| | KaTeX metrics | JLaTeXMath |
| --- | --- | --- |
| one reply's formulas, on the worker | 142 in **3078 ms** | **146 in 51 ms** (0.35 ms each) |
| the document's first layout, cold process | 806–1205 ms | **575 ms** |
| scrolling, `50th` / janky frames | 17 ms / 4.6 % | 17 ms / 5.5 % |

**What it did not fix, and what is left.** 351 ms of that cold open is composing 300 blocks of
`Text`, `Row` and divider with no formula in them at all, and the frame table while scrolling was
already the same with the mathematics on and off — the mathematics is now not the reason a long
reply is slow to draw. What remains is structural: a reply is one item of the transcript's list,
the list has to know that item's height before it can show it, and the change that fixes it is
chunking a reply into several items. That is named at the end of this chapter and still not made.

### Three things about that were all got wrong first, and are worth keeping

- **Reading the cache inside the `remember` that builds the annotated text does nothing.**
  That read happens while the value is being computed, so it registers no dependency, and the
  block keeps the annotated text it already built: **every formula stayed drawn as its own
  LaTeX source, forever.** The cache contents have to be a *key* of that `remember`, read in
  composition.
- **That key has to be per formula, not global.** A single global counter is simple and much
  worse: it becomes a key of every block's `remember`, so each of a reply's sixty measurements
  recomposed all three hundred of its blocks — **measured, `99th percentile 2050 ms`, against
  850 ms before any of this existed.** `MathCache.versionOf(keysFor(source))` is per block.
- **A cache that can only hold one font size fails silently, and the failure is a table.** A cell
  is laid out at `bodySmall` and everything else at `bodyMedium`, so a request for a cell's
  formula arrived at a size nothing had been prepared for, was refused, and the cell drew its
  LaTeX source forever — **measured, 120 refusals of `\frac{1}{2}` and `x^{2}` at `size=12.0.sp`
  against a measurer registered at 14sp.** That was fixed first with a measurer per size and is
  fixed now by the cache key itself: `MathCache` keys a formula on its body, its size **and its
  ink**, so the size a caller asks at is part of the identity of the entry rather than something
  a second lookup has to agree about. A table asks at its own sizes and gets its own entries.

## The command mark: six revisions, and the glyph the reader chose

The composer's command button is now **Lucide's `command`** (ISC) — ⌘, one closed path with
four corner loops, drawn at the library's own stroke weight. It is not a `</>` at all, and
that is the point: the button opens a list of *commands*. Getting there took six revisions
and three separate mistakes, all of which are worth more than the glyph.

**Mistake one: a wrong diagnosis that survived because it was never checked against the
artefact.** The first version used `Icons.Filled.Code`, which the reader reported drew
nothing ("目前 `</>` 没有正常显示"). The diagnosis was that `material-icons-extended` 1.7.x
draws that glyph as an open stroked polyline and that Material3's `Icon` builds its painter
through a `rememberVectorPainter` overload whose `strokeLineWidth` defaults to `0f`, zeroing
the stroke. **Read out of the artefact, that is false**: Material3 1.4.0's `Icon` calls
`rememberVectorPainter(ImageVector, …)` — the overload with no stroke arguments at all — and
the glyph is drawn at the width its own `ImageVector` carries, which for `Code` is 4. The
bytecode that looked like evidence (`StrokeCap`/`StrokeJoin` on the builder) is evidence that
the path *is* stroked, not that anything zeroes it. The build's own comment asserted the
false version for a round.

**Mistake two, and it is the real one: `ic_commands.xml` was not in the APK at all.** The
reader kept reporting a missing slash across four builds and was right every time — the
drawable failed to compile because an XML comment contained a double hyphen, which XML
forbids, and *my own build-output filter hid AAPT's error*. Every APK after that point was
served from a cached older build. `unzip -l app-debug.apk | grep ic_commands` is the check
that settles it, and it is now in `AGENTS.md` — including the trap that a *release* APK
renames every resource to a two-character path, so the same grep finds nothing for a
resource that is present.

**Mistake three: adjusting a glyph instead of choosing one.** With fills, strokes, weights
and clearances all hand-tuned, the result was five rejected variants — "字体都变了，一点都不
标准" and then "中间斜杠离两边括号太近了". The lesson is not "measure more": it is that **an
icon set is a set**, and a glyph whose parts have each been individually adjusted no longer
belongs to the icons around it.

**What worked was showing the reader the candidates at the size the app draws them.** Twenty
icons from Tabler, Lucide, Bootstrap and Phosphor were rendered at 21dp, 55dp and 64dp — the
21dp column being the point, because a glyph that reads at 64dp and merges at 21dp is exactly
what had been shipping. The reader first picked Tabler's `code`, asked for the slash
clearance to be widened, then changed their mind for **Lucide's `command`**: a single closed
figure, so there is no third stroke to crowd the other two at 21dp, and a mark whose meaning
is "command" rather than "code".

The general rule this round produced: **for a glyph, render the real candidates at the real
size and let the person who will look at it every day decide.** No amount of reasoning about
stroke weights substitutes for that.

**And the size of a borrowed glyph is a call-site decision.** ⌘ drawn at the 21dp the Material
glyphs beside it use is the report "常用命令图标太大了", and the reason is measurable in the
vectors: Lucide's figure reaches 21 of its 24 units, where Material's `Add` keeps its ink inside
14, so the same dp is a visibly heavier mark. It is **17dp** now — scaled at the call site in
`ChatScreen.kt`, with the imported vector untouched, which is the difference between using a
glyph from another set and editing one.

**The copy mark took four rounds, and three of them asked the wrong question.** Asked to improve
the copy button, this round first offered six *containers* — filled circle, outlined circle, rounded
square, two labelled pills, a bare glyph — rendered in both places it appears and at 2.5×. The
answer came back as a picture of a *glyph*. Then it offered six glyphs from Lucide, Tabler and
Phosphor; the answer was "the covering sheet should be at the top right". Then a third sheet with
five more sets; the answer was "I like Font Awesome's, but the two sheets are too far apart". The
fourth answer was the SVG itself, pasted into the conversation, and it is what ships:
`ic_copy.xml`, two rounded sheets with the back one peeking at the top right, geometry supplied by
the reader. Lucide's `check` (`ic_check.xml`) takes its place for a moment after a tap, because its
stroke weight is about the weight of the sheets' walls.

Two lessons, and the first is this file's own rule read the other way round. **When a report is
about how something looks, ask for a picture of the thing wanted** — every icon set this project
looked at draws that back sheet at the top *left*, or hangs a folded corner off the front one, so no
menu of real glyphs was ever going to contain the one in the reader's head. And the second: a sheet
of six candidates is six times the work of asking first.

## Inline code has a rounded fill, because a span cannot

`SpanStyle.background` is a plain colour fill with no corner radius, and ui-text 1.10 has
no `BackgroundStyle` — checked in the artefact. So a code span is *measured content* now,
like a formula: `rememberCodeChip` measures the span's text, wraps the result in a `Box`
with a background and a 6dp radius, and declares it as an `InlineTextContent`. That is the
report "行内代码背景无圆角很难看".

Three details are load-bearing. The placeholder is **clipped** to the size it declares, so
the height has to be the line box rather than the text's own height — a shorter one cuts
the fill's top and bottom off instead of shrinking it. The width carries a 3dp pad on each
side so the fill does not touch its own glyphs. And the table path passes `code = null`, so a
measured grid still uses the plain `SpanStyle`: a filled chip in every cell of a measured
table reads as a grid of errors, and only the widths are wanted there.

## The thinking chip's jump, which was a lookup against the wrong key

"新增模型配置后，选择模型后，未修改思考等级的情况下，每次打开app，思考等级chip会直接显示
模型没有的medium，然后再跳回模型有的high." The chip showed `medium` — a level the model does
not have — and then settled on `high`.

The app already had a mechanism for this: the levels pi reported for a model are remembered
(`rememberedThinkingLevels`), keyed by the model id, and the saved preference is clamped
against them before pi answers. On the frame the app opens, `get_state` has not arrived, so
`state.model` is null; the lookup was `saved.rememberedThinkingLevels(state.model?.id)`, and
`rememberedThinkingLevels` correctly refuses to answer for a null id. The list therefore fell
back to pi's full seven levels, the saved `medium` was already one of them, and the chip
showed the raw preference until `get_state` landed and moved it to `high`.

The fix is one fallback: `state.model?.id ?: saved.modelId`. `PiSettings.modelId` is the
active profile's model — the one the agent was *launched with* — so it is the right key on
the very first frame, which is exactly when it is needed.

## The composer's tap handler, which swallowed every tap

The same shape of mistake, one frame lower. Dismissing the paste/select toolbar that the
field's own long-press raises is the one thing `BasicTextField` cannot do for itself: it
consumes a tap on its own bounds, because that is how it moves the caret, so a handler
underneath never sees it. The first version cleared the focus on **every** pointer event
at `PointerEventPass.Final`, and that includes the down event of the tap that focuses the
field. The sequence for a first tap was: the field takes the focus, the handler clears it
in the same pass, and the field is left unfocused — no caret, no keyboard, and every
later tap repeating it. That is "现在打开app点击输入框无反应，无法输入文字".

Read from the emulator's accessibility dump, not from the code: after `input tap` on the
field, no node in the window had `focused="true"` and there was no `InputMethod` window.

It clears after a *completed* tap now, and only when the field already had the focus when
the finger went down (`awaitFirstDown(pass = PointerEventPass.Initial)` reads the flag
before the field's own gesture detector can change it). The first tap focuses and stops;
a second one dismisses.

## The commands list, which offered rows that could not act

The sheet was pi's own `get_commands` registry plus the app's own commands, on the theory
that the registry is an extension point and a hard-coded list goes stale. The theory was
right and the rows were wrong. Everything the bundled `pi-web-access` extension registers
— `/websearch`, `/curator`, `/google-account`, `/search`, `/llama` — needs an interactive
terminal to do anything; several of them open a review page in pi's own TUI and never
answer over RPC at all. A row for one of those is a control that cannot keep its promise,
which is the report "点击之后不仅不会直接执行，还带来了其他问题". The list is now exactly what
this page can run: the `!` shell prefix and the seven commands in `BUILT_IN_COMMANDS`.
`commandDescription` and `PiAgentSession.availableCommands()` went with the rows rather
than staying as dead code.

## The tests that could not catch it

`Regex("^\\\\begin\\{(\\w+\\*?)}")` is accepted by the JVM and rejected by Android's
own ICU engine, which fails the whole class initialiser: every Markdown block in the
app threw `ExceptionInInitializerError` the moment a reply started arriving, and the
JVM suite for the same code was green. It is `indexOf` now. The general point is
worth keeping: for a *rendering* change the unit tests settle the parser and the
device settles the renderer, and the two do not overlap. It is why the formula
renderer being third-party — and device-unverified in the change that adopted it — is
called out in the handover rather than assumed to work.

## The release build typeset less than the debug one

Every formula measurement in this chapter was made on a **debug** APK, because that is
the build `adb install` puts on the emulator and the only one `run-as` can reach. The
build a reader installs is the **release** one, and R8 runs only there. The reader
found the difference: three blocks of an answer, the first without `\dfrac` rendered,
the other two drawn as their LaTeX source — "我发现这三块公式只有第一块渲染正常了".

JLaTeXMath builds its command table **by name at run time**. `MacroInfo` and
`TeXFormulaParser` reach each macro entry point through `Class.forName`,
`getDeclaredMethod`/`getMethod` and `getDeclaredField`, and those names exist only as
strings — in `TeXFormulaSettings.xml`, and in `PredefMacros`, whose methods are
`<name>_macro`. R8 sees a class nobody calls, deletes the members, and the lookup
returns null:

```
java.lang.NullPointerException: Attempt to invoke virtual method
'java.lang.Object java.lang.reflect.Method.invoke(java.lang.Object, java.lang.Object[])'
on a null object reference
```

`MathCache` catches that and draws the formula as its source, which is the designed
last resort — a failure mode indistinguishable from "the renderer does not support
this", which is why it read as a parsing gap and not as a packaging one.

The same 42-construct sweep, same sources, x64, differing only in the build type:

| build | constructs drawn as their source |
| --- | --- |
| debug | `\oiint`, `\oiiint` — genuinely absent from the library's fonts |
| release | `\dfrac`, `\tfrac`, `\left\{…\right.`, `\begin{align}`, `\operatorname`, `\substack`, and those two |

`\dfrac` is the one every model writes, and it is why the reader's first block was the
only one that survived: it is the only one of the three without a display-style
fraction in it. **Six constructs of difference between the build that was verified and
the build that shipped**, and nothing on a debug device, no JVM test, and no screenshot
taken during those rounds could have shown it.

The fix is one keep rule — `-keep class org.scilab.forge.jlatexmath.** { *; }` — which
is deliberate rather than lazy: the library is ~330 KB of classes against a 107 MB APK,
it reaches **218** `*_macro` entry points this way, and guessing which of its reflective
paths matter is the mistake that caused this. `tools/check-release-math.py` states it as
a property of the artifact instead, comparing each release APK's dex against the names
the library's own AAR defines, and `tools/build-apks.py` runs it **after** the APKs are
built — the one checker that cannot live in that script's `checks` list, because those
run before anything is packaged.

The rule is worth generalising: a library that resolves its own members by name is
invisible to a shrinker, and in this app the formula renderer is such a library. Both
the rule and the checker carry this reasoning, because the symptom — a formula drawn as
its own source — points at the parser, several files away from the cause.

