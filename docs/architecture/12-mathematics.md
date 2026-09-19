# Mathematics: the renderer, and what it costs

*[Architecture](../ARCHITECTURE.md) §12, part 2 of 3.*

Why the renderer is a library, how its line box is built, and every measurement that chose it.
The Markdown half is [part 1](12-markdown-and-math.md); the display formulas and marks are
[part 3](12-display-and-marks.md).

## Mathematics is not hand-written, and the reason is a table in the font

A formula's geometry lives in the OpenType `MATH` table: the axis a fraction bar sits on, its
thickness, how far a script is raised, the gap a radical needs, and the `Size1`–`Size4` glyph
variants a large operator grows through. **Android's public text API does not expose it** — `Paint`
offers `getFontMetrics` (ascent, descent, leading), `setFontFeatureSettings` and
`setFontVariationSettings`, and nothing that reads a math constant — so a hand-written renderer must
invent them, and the inventions are the failures a reader reported: a fraction rule on the
numerator's measured height instead of the axis; an integral scaled by a fudge factor, with no
larger glyph cut and no `Size` font to select one from; a vector arrow drawn as a combining
character the font may or may not have.

So formulas are handed to **`ru.noties:jlatexmath-android`**, a port of JLaTeXMath whose font
metric files are the `MATH` table's contents. It replaced `io.github.huarangmeng:latex-renderer`
(KaTeX's own fonts and metrics, MIT), which typeset at least as faithfully and cost **21.7 ms per
formula** — the measurement that decided the swap is in "What the formula measurement actually
costs" below. The port is GPL-2.0 over upstream JLaTeXMath's GPL-2.0 **with a linking exception**;
both are named in the app's credits and in [LICENSING](../LICENSING.md), and the exception is what
lets this GPL-3 application link it.

## The two shapes a formula comes in

- **A display formula** — `$$…$$`, `\[…\]`, `\begin{aligned}` — is a block of its own drawn as a
  `Text` whose whole content is one placeholder, inside a horizontally scrolling parent: a formula
  is read left to right, and a wrapped one is two formulas. The environment is handed over
  **whole**, `&` and `\\` included, because the renderer knows what a `cases` or an `aligned` is.
  Its own composable would leave it out of the transcript's selection, as
  [part 3](12-display-and-marks.md) records.
- **Inline** mathematics is an `InlineTextContent` of exactly the typeset size, which is what lets
  a stacked fraction or a radical appear mid-sentence. The old hand-written renderer flattened
  mathematics to `SpanStyle`s, which is why `$\frac{a}{b}$` in a sentence drew as `(a)/(b)`.

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

A formula's baseline is not its bottom — a fraction and an integral hang below their line — so it
needs room above *and* below the baseline. `MathView.kt` therefore declares the smallest box
**centred on the text's centre** that contains the formula's ink
(`half = max(|inkTop - centre|, |inkBottom - centre|)`) and draws the drawable inside it at the
offset that puts the formula's own baseline on the line's; a formula no taller than the text leaves
the line height alone, and a stacked fraction grows its own line. The text's centre comes from a
`TextMeasurer` measurement of the same style, because the sentence's font decides it and the
renderer cannot know it.

**Two line-box versions are in `docs/verification/2026-09-19-renderer-swap.md` and neither is worth
repeating.** Filling a placeholder aligned by its bottom floats every fraction a whole depth above
its line; pushing that down by its depth instead overlaps the line above, because the placeholder
never grew the descent.

The **ink colour** comes from the scheme, is fixed at typeset time rather than tinted, and is part
of the cache key: JLaTeXMath draws into the canvas its caller supplies and has no notion of a
theme, so a light/dark switch re-typesets — 0.35 ms a formula, not a decision worth complicating.
**Accessibility is what the swap gave up.** The KaTeX renderer could publish a MathSpeak-style
description through the semantics tree, so TalkBack read "fraction: 1 over 2"; a `Drawable` cannot,
and a screen reader now reads the LaTeX source — the placeholder's alternative text, which was
always there, but not the same thing, and replacing it means writing a MathSpeak generator.

### A formula the renderer refuses, and the four spellings of one

A model writes mathematics in more ways than one, and the two that are not Markdown's used to come
out as text. `$…$` and `\(…\)` are both read inline — the tokeniser has known `\(…\)` all along —
and `\[…\]` is now read inline as well when it is *not* alone on its line
("由 \[E = mc^{2}\] 可知"). `unescapeMath` did not strip `\[`/`\]` either, so the renderer was handed
the brackets and refused them — which is why a TeX-style formula inside a sentence looks like prose
until it is read, and was reported as "行内公式换了行就不渲染".

**A body padded with spaces is mathematics when it looks like mathematics** — the fix for
"这种换行的仍然没渲染", where `$ P(A_i\mid B) $` was rejected by the price rule and drawn as its own
source. The rule now asks whether the body carries a `\command`, a script (`^`/`_`), a group (`{}`)
or a relation (`=`): "it costs $ 5 and $ 10" stays a sentence, `$ x^{2} $` becomes a formula, and a
padded body with neither — `$ x $` — stays prose. That boundary is the right way round: a formula
shown as its source is legible and merely ugly, while a price typeset as mathematics is a sentence
mangled.

For the constructs the renderer genuinely does not have, `LatexCompat.kt` rewrites the body and
tries again before giving up:

| kind | example | rewrite |
| --- | --- | --- |
| numbering and labels | `\tag{1} x=1`, `\label{eq:1}` | dropped — they draw nothing |
| a decoration | `\cancel{x}`, `\hcancel{x}`, `\sout{x}` | `x` — the decoration is lost, the term is not |
| another spelling | `\color{red}{x}` | `\textcolor{red}{x}`, or an alias in JLaTeXMath's macro table |
| chemistry | `\ce{H2O}` | `\text{H2O}` |

Eight of forty common constructs failed before this existed, enumerated by logging the body every
time the renderer refused one — `\ce`, `\color`, `\tag`, `\label`, `\cancel`, `\hcancel`, `\oiint`,
`\oiiint` — and everything else in that sweep already worked. `\oiint` and `\oiiint` are left
failing on purpose: they are single glyphs the bundled fonts do not have, and any rewrite would be
an invented picture of a surface integral. A formula that still fails is logged by body, so
"某些情况没渲染" is a line in `logcat` rather than a guess.

### A formula too wide for its line

Two widths, two answers, because the two places a formula can be are not alike.

- **A display formula scrolls, and now says so.** Its block is one `Text` inside a
  `horizontalScroll`, which is what makes a wide derivation reachable at all; nothing used to say
  so, which is "无明显暗示能够滑动查看完整内容". Two hints are drawn over it: a gradient fading the
  last 18dp on whichever side has more to show, and a pill at the bottom reporting how much of the
  formula is on screen and where in it the reader is. The pill is drawn because Android has no
  `HorizontalScrollbar`, and a real one would want the drag gestures the scroller already owns.
- **An inline formula is scaled down instead.** It cannot scroll: the paragraph scrolls
  vertically, the transcript does not scroll horizontally, and a horizontal drag inside a paragraph
  belongs to the selection. An inline formula wider than its line is drawn uniformly scaled to fit,
  down to a floor of half size, below which it overflows as it did before any of this.
  `MarkdownText` publishes the reply's own width through `LocalFormulaWidth` from a single
  `BoxWithConstraints`, because a placeholder's size has to be decided before the line it will sit
  on is known, and a subcomposition per paragraph would charge for the same number once per block.

## The cost that was measured before adopting it

**The renderer here has since been replaced** (see below), but the toolchain moves it forced are
still in the build, and every number was read from the published artefacts, not estimated.

| | |
| --- | --- |
| Added to the APK | 1.09 MB `latex-renderer` (809 KB bytecode + 19 KaTeX fonts) + 490 KB `latex-parser` + 9 KB `latex-base` |
| Its `minSdk` / `minCompileSdk` | 23, below this project's 26 / **36**, which is what moved `pikit.compileSdk` from 35 |
| What it needs from Kotlin | metadata 2.1.0; the `-kt2.1.0` artefacts are the variant this project can read |
| What it needs from Compose | Jetpack Compose 1.9.4 / Material3 1.4.0, i.e. `compose-bom` **2025.11.00** |

**Compose Multiplatform 1.8.0 and later require a dependency compiled with at least Kotlin 2.1.0**,
and a compiler reads metadata only from its own version or below, so Kotlin 2.0.21 could not read
this library at all; every release from 1.2.7 on asks for the same `kotlin-stdlib 2.2.10` + Compose
`1.9.3`, so no older version avoided the move. AGP went to **8.13.2** because `compileSdk 36` needs
one that knows it, and **AGP 9 was deliberately not taken** because it turns on built-in Kotlin and
defaults `defaultTargetSdkToCompileSdkIfUnset`, the one default that must never reach
`pikit.targetSdk=28` (§1). **None of the four moved back with the dependency, deliberately**:
`compose-bom` 2025.11.00 is still what the UI is built against, and re-deriving a compileSdk from
the current dependency set is a change of its own.

`jlatexmath-android`'s requirements are trivial next to that: `minSdk 16`, `targetSdk 28`, one
`androidx.annotation` dependency, 1.12 MB of fonts and bytecode, and an `InitProvider` that
installs the fonts' context — verified in the merged manifest, because every formula would fail on
the first draw without it.

**Rejected alternatives.** KaTeX in a WebView would typeset as faithfully and move no toolchain,
but each formula needs its own WebView surface to stay out of the transcript's scrolling, against
1.6 MB of local code here. The same author's Markdown renderer (1.4.0–1.5.3) was measured and
twice rejected: it needs Kotlin 2.4.0 metadata, Compose Multiplatform 1.11.1 and
an **alpha** Material3 (`1.10.0-alpha05`), with `ui 1.12.1` requiring `minCompileSdk 37` under AGP
9.1, and drags in `ktor-client-android`, Coil 3 and a syntax highlighter for remote images and
Mermaid where this app fetches nothing to draw a reply; its parser alone
(`markdown-parser-android`, 130 KB, no network) also wants Kotlin 2.4.0.

## What the formula measurement actually costs, and what was done about it

The reply that exposed this is a synthetic 12 KB reply of **300 blocks with ~120 formulas** (60
display, 60 inline, plus table cells), opened from the history list. Everything below was read from
a probe around the measurement call and from `dumpsys gfxinfo`.

| | |
| --- | --- |
| `parseMarkdown` | 23–41 ms for 300 blocks |
| compose + measure, one measurer per block | **807 ms**, 210 measurements, 210 distinct measurers, 1271 ms of measurement time |
| **a probe around the measurement call itself** | **260 calls, 3401 ms, `thread=main`, one call at 327 ms** |
| one block with one display formula, alone | 4 ms |
| the same reply with `accessibilityEnabled = false` | 826 ms — noise, so accessibility stays on |
| the measurer's identity, logged per provide | **changes on every one of 360 provides** |

**The cost is entirely formula layout: about 17 ms each, on the UI thread.** Nothing else in the
transcript is close. **Operit does not render off the UI thread**: its `LatexCache.getDrawable`
builds the drawable on the calling thread — a Compose composition — and keeps a 50-entry
`LruCache`; what makes it smooth is that its renderer lays a formula out in **0.42 ms**, so the
main thread never notices. Moving the work is not the fix, and a fast renderer is.

**What was tried and did not work:**

- **`ProvideLatexMeasurer`** — one measurer per reply instead of one per block. It does not help
  the 17 ms (that is layout, not construction) and cannot share across a font-family change, since
  the library replaces the font families that key every `rememberLatexMeasurer`. Gone with the
  renderer.
- **`accessibilityEnabled = false`** — 85 ms across a reply, inside the noise.
- **Pinning the measurer** so the font-state change could not rebuild it: **180 calls, 3043 ms**
  against 260 calls and 3401 ms — better, still 3 seconds, because a pinned measurer is still on
  the UI thread. Reverted: not worth diverging from the library's API.
- **Making `MarkdownText` a `LazyColumn`.** Tried twice, and it threw `IllegalStateException:
  Vertically scrollable component was measured with an infinity maximum height constraints` **both
  times** — from `ManualPage`, inside `SettingsBody`'s `Column(verticalScroll)`, and from the
  transcript, whose item is wrapped in a `SelectionContainer` so the constraint is infinite.
- **A hand-drawn `</>`** — four attempts, each worse than the glyph Material ships (part 3).

**What works is `MathCache`: the measurement leaves the UI thread.** An unmeasured formula is drawn
as its own source and the measurement is queued on a single-threaded worker (single, because the
library's measurer keeps its caches in plain `LinkedHashMap`s and concurrent calls would corrupt
them); the result lands in a `SnapshotStateMap`, which redraws the block that asked. `MarkdownText`
prefetches every formula in a reply when its text changes, so opening a saved conversation has the
cache warm before the first frame that draws it, and a `TextMeasurer` is thread-safe so the same
worker measures the inline code chips.

### What it bought, measured on the same 300-block reply

| | before `MathCache` | after |
| --- | --- | --- |
| the frame that opens the reply, `99th` | 850 ms | **93 ms** |
| scrolling 16 screens, `50th` / `90th` / `99th` | 24 / 34 / 600 ms | **17 / 18 / 26–53 ms** |
| scrolling, `Number Slow UI thread` | 8–38 | **0** |
| formula layout on the UI thread | 3401 ms | **none** |

`dumpsys gfxinfo` on a `-gpu host` emulator. The scroll figure is the one the reader asked about
("就上下滑动特别是切换到某个消息时特别卡"), and it is the one that moved: **0.5–2.4 milliseconds of
jank per frame at the 99th percentile, against 600 ms before.**

### It was still not enough, and the renderer was why (2026-09-19)

"我觉得你现在公式渲染还是无法解决卡顿问题" was the report: `MathCache` had made the layout non-blocking
and a formula-heavy reply still filled in over several seconds, showing LaTeX source while it did.
The probe that settled it drew the same document with mathematics switched off: the measurer, the
token walk and the annotated-string build were all free, so **`MathCache` had made a slow renderer
non-blocking, not fast.** The figures — the 806 / 1128 / 1205 ms cold open against 351 ms with no
formulas, the 142 formulas in 3078 ms, JLaTeXMath's 422 µs mean and 183 µs p50, the 146 formulas in
51 ms after the swap — are in [the same round's file](../verification/2026-09-19-renderer-swap.md).

**What the swap did not fix.** 351 ms of that cold open is composing 300 blocks of `Text`, `Row`
and divider with no formula in them at all, and the scrolling frame table was already the same with
the mathematics on and off, so the mathematics is no longer the reason a long reply is slow to
draw. What remains is structural: a reply is one item of the transcript's list, the list must know
that item's height before it can show it, and the fix is chunking a reply into several items —
still not made.

### Three things about that were all got wrong first, and are worth keeping

- **Reading the cache inside the `remember` that builds the annotated text does nothing.** That
  read happens while the value is being computed, so it registers no dependency and the block keeps
  the text it already built: **every formula stayed drawn as its own LaTeX source, forever.** The
  cache contents have to be a *key* of that `remember`, read in composition.
- **That key has to be per formula, not global.** A single global counter becomes a key of every
  block's `remember`, so each of a reply's sixty measurements recomposed all three hundred of its
  blocks — **measured, `99th percentile 2050 ms`, against 850 ms before any of this existed.**
  `MathCache.versionOf(keysFor(source))` is per block.
- **A cache that can only hold one font size fails silently, and the failure is a table.** A cell is
  laid out at `bodySmall` and everything else at `bodyMedium`, so a request for a cell's formula
  arrived at a size nothing was prepared for, was refused, and the cell drew its LaTeX source
  forever — **measured, 120 refusals of `\frac{1}{2}` and `x^{2}` at `size=12.0.sp` against a
  measurer registered at 14sp.** `MathCache` now keys a formula on its body, its size **and its
  ink**, so the size a caller asks at is part of the entry's identity; a table asks at its own sizes
  and gets its own entries.

## The release build typeset less than the debug one
Every formula measurement here was made on a **debug** APK, the build `adb install` puts on the
emulator and the only one `run-as` can reach; a reader installs the **release** one, and R8 runs
only there. The reader's "我发现这三块公式只有第一块渲染正常了" is three blocks of an answer, the first
without `\dfrac` rendered and the other two drawn as their LaTeX source.

JLaTeXMath builds its command table **by name at run time**. `MacroInfo` and `TeXFormulaParser`
reach each macro entry point through `Class.forName`, `getDeclaredMethod`/`getMethod` and
`getDeclaredField`, and those names exist only as strings — in `TeXFormulaSettings.xml`, and in
`PredefMacros`, whose methods are `<name>_macro`. R8 sees a class nobody calls, deletes the members,
and the lookup returns null — `java.lang.NullPointerException: … Method.invoke … on a null object
reference`.

`MathCache` catches that and draws the formula as its source, the designed last resort; the failure
is indistinguishable from "the renderer does not support this", so it read as a parsing gap rather
than a packaging one.

The same 42-construct sweep, same sources, x64, differing only in the build type:

| build | constructs drawn as their source |
| --- | --- |
| debug | `\oiint`, `\oiiint` — genuinely absent from the library's fonts |
| release | `\dfrac`, `\tfrac`, `\left\{…\right.`, `\begin{align}`, `\operatorname`, `\substack`, and those two |

`\dfrac` is the one every model writes, which is why the reader's first block was the only one that
survived: it is the only one of the three without a display-style fraction. **Six constructs of
difference between the build that was verified and the build that shipped**, and nothing on a debug
device, no JVM test, and no screenshot could have shown it.

The fix is one keep rule — `-keep class org.scilab.forge.jlatexmath.** { *; }` — deliberate rather
than lazy: the library is ~330 KB of classes against a 107 MB APK, it reaches **218** `*_macro`
entry points this way, and guessing which of its reflective paths matter is the mistake that caused
this. `tools/check-release-math.py` states it as a property of the artifact instead, comparing each
release APK's dex against the names the library's own AAR defines, and `tools/build-apks.py` runs it
**after** the APKs are built — the one checker that cannot live in `checks`, which runs before
anything is packaged.

The rule generalises: a library that resolves its own members by name is invisible to a shrinker,
and the symptom — a formula drawn as its own source — points at the parser, several files away from
the cause.
