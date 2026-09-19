# The 2026-09-19 rounds: the renderer swap

*Part of [VERIFICATION.md](../VERIFICATION.md). The measurement that replaced the renderer, and the line box built around it after that.*

## The formula renderer: what it cost, and the migration to Operit's (2026-09-19)

**The report was "公式还是卡，看看迁移到 operit 那套方案的可行性"**, so the first number needed was what
the renderer this project used actually costs. A temporary `ProbePage.kt` — deleted after the
measurement — drew the same 300-block, ~150-formula document with the formulas on and off. Cold, its
first composition and layout took **806 / 1128 / 1205 ms against 351 ms with the mathematics switched
off**; the same open with the renderer warm took **465 ms**; and 20 s in, the measurement worker's
total was **142 formulas in 3078 ms (21.7 ms each)**. The phase counters say what that cost is *not*:
`rememberLatexMeasurer` ran 92 times in **0 ms**, and the per-block token walk (`MathCache.keysFor`)
plus `renderInline` together were **0 ms** — the renderer's own per-formula layout is the whole of the
3.1 s. **Once the cache is full the mathematics costs nothing measurable while scrolling**: 17 ms
median with the formulas on and with them off, and 0 against 4 slow UI-thread frames in 585 and 590
frames. The `-gpu host` emulator is bimodal here (17 ms and 28 ms medians for the same content, 4.6 %–
15.2 % janky, uncorrelated with the switch), so only the in-app counters are quoted.

**Operit's renderer, measured on the same device at the same 14 sp.**
`ru.noties:jlatexmath-android:0.2.0` lays a formula out in **422 µs mean, 307 µs p50, 1.5 ms worst**
(including reading the intrinsic size) and draws it into a bitmap in **183 µs p50**; 20/20 constructs
render, including `\begin{aligned}`, `pmatrix`, `cases`, `\text{}`, `\binom`, `\sqrt[3]`, `\mathbb{}`
and `\underset`. The AAR is 1.12 MB, it is on Maven Central, and `TeXIcon.getIconDepth()` supplies the
depth an inline placeholder needs to sit on the text's baseline — Operit itself rewrites
`FontMetricsInt` by hand for that. RenderX, the other LaTeX dependency Operit declares, is not used by
its chat path, so no JitPack repository and no GPL-3.0 dependency are involved.

**It was adopted.** The three KaTeX-metrics libraries were removed and `MathCache`'s worker now calls
`JLatexMathDrawable.builder(...).build()`. On the same probe document that took the first layout to
**575 ms** (from 806 / 1128 / 1205 ms), the worker to **146 formulas in 51 ms** (from 142 in 3078 ms),
and scrolling to `50th` **17 ms with 5.5 % janky** (from 17 ms and 4.6 %). The 351 ms that remains is
what a 300-block reply costs with no formula in it at all: the transcript still composes and measures
every block, and still has to measure the whole item to know its height. The fix is chunking a reply
into several transcript items, and it is still not made.

**The line box was got wrong twice.** The first declared a placeholder of `height + depth` and drew the
formula at its top; `ALIGN_ABOVE_BASELINE` is `top = getLineBaseline(line) - span.heightPx`, so its
**bottom** is the baseline and every fraction and integral floated a full depth high. Drawing one depth
lower fixed the baseline and made a tall formula overlap the line above, because the placeholder never
grew the descent. `MathView.kt` now declares the smallest box centred on the text's centre that
contains the ink and places the drawable so the formula's baseline lands on the line's, the
ascent/descent coming from a `TextMeasurer` of the same style (ARCHITECTURE §12). **A display formula
takes the opposite treatment**: its block *is* the line, so it declares `height + depth` and draws at
the top of that box, keeping the ink inside the height the transcript measures and the scroll container
clips at (`rememberMathInline(..., display = true)`). A table cell's formula typesets at the cell's own
font size: `a_{1}`, `\frac{1}{2}`, `\sqrt{3}`, `\int_{0}^{1} x^{2}\,dx`.

## The formula layout round, and the two reports after the swap (2026-09-19, later)

Two reports arrived together: "常用命令图标太大了" and "现在公式渲染位置和排版存在严重问题，请仔细检查
修改，我还发现行内公式换了行就不渲染，以及某些情况没渲染". The second was right about three separate
things, and the first two attempts at it were both wrong.

**What the numbers say.** A diagnostic page printed Compose's own line boxes and placeholder
rectangles next to each formula's size — one sentence at 14sp with `lineHeight = 22sp`, density 2.625,
so a line is 57.75 px and the font's ascent/descent measure 38/13:

| formula | size (w×h) | depth | what it did |
| --- | --- | --- | --- |
| `x^{2}` | 51×48 | 10 | floated above the baseline |
| `\frac{1}{2}` | 44×91 | 36 | floated, and its denominator crossed the line above |
| `\int_{0}^{1} f(t)\,dt` | 183×106 | 43 | began a paragraph the line above no longer cleared |

**A placeholder grows only one side of its line**: `AboveBaseline` grows the ascent and leaves the
descent alone, `TextTop` is the mirror image, and **`TextCenter` is the only alignment that grows
both** (ARCHITECTURE §12). A formula no taller than the text leaves the line alone (48 px in a 57.75 px
line) and a stacked fraction grows its own line — `\frac{1}{2}` needs 104 px. The same construction
fixed the display formula, whose ink was drawn outside the height its own block reported.

**"行内公式换了行就不渲染" was not a wrapping bug at all — it was `\[…\]` inside a sentence**, which the
tokeniser read only line-leading. It now reads it inline, and `unescapeMath` — which stripped `\(…\)`,
`$$` and `$` but not `\[…\]` — strips it too; without that second half the renderer was handed the
brackets, refused them, and the reader saw the source. (`\(…\)` was already handled, so the first
attempt was dead code and was reverted.)

**"某些情况没渲染" is eight constructs out of forty**: `\ce`, `\color`, `\tag`, `\label`, `\cancel`,
`\hcancel`, `\oiint`, `\oiiint`, with the body of every failure logged; everything else in the sweep
worked. `LatexCompat.kt` now rewrites the first six — dropping numbering, unwrapping decorations,
renaming `\ce` to `\text` and `\color` to `\textcolor`, and aliasing `color` in JLaTeXMath's own
(public) `MacroInfo.Commands` table — and only the two glyphs the fonts do not have still fall back to
their source.

**And the icon.** Lucide's `command` at the 21 dp the Material glyphs beside it use is a visibly
heavier mark, because the figure reaches 21 of its 24 units where Material's `Add` stays inside 14. It
is drawn at **17 dp** now, at the call site, with the imported vector untouched.
