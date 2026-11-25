/*
 * Drawing a formula.
 *
 * The typesetting is not this project's. A formula is handed to
 * `ru.noties:jlatexmath-android`, which is a port of JLaTeXMath, and which is the reason this
 * file exists at all. `MathCache.kt` says why that renderer replaced the KaTeX-metrics one this
 * project used before, with the measurement that decided it; what is left here is the
 * renderer's side of the contract — which configuration a formula is drawn with, and how a
 * typeset formula becomes the `InlineTextContent` the paragraph declares.
 *
 * ## Why mathematics is not hand-written here
 *
 * The OpenType `MATH` table is where a formula's geometry lives: the axis a fraction bar sits
 * on, the thickness of that bar, how far a script is raised, the gap a radical needs, and —
 * the one no amount of care substitutes for — the `Size1`–`Size4` glyph variants a large
 * operator uses to grow. Android's public text API does not expose that table. `Paint` offers
 * `getFontMetrics` (ascent, descent, leading), `setFontFeatureSettings` and
 * `setFontVariationSettings`, and nothing that reads a math constant. JLaTeXMath carries the
 * table's contents as its own font metric files under `assets/org/scilab/forge/jlatexmath`, so
 * the formula is *typeset* rather than approximated, which is what the hand-written version
 * this project started with could not do: its fraction rule sat on the numerator's own height
 * instead of on the axis, and its integral could only be scaled by a fudge factor because the
 * glyph has no larger cut to select.
 *
 * ## The two shapes, and the two TeX styles
 *
 *  - **A display formula** — `$$…$$`, `\[…\]`, `\begin{aligned}` — is a block of its own, drawn
 *    as a `Text` whose whole content is one placeholder, so the transcript's selection treats it
 *    like any other run of prose and a formula wider than the screen can be scrolled. It is
 *    typeset at `TeXConstants.STYLE_DISPLAY`, which is what a formula on a line of its own means.
 *  - **Inside a paragraph**, the formula is an `InlineTextContent` of exactly the typeset size,
 *    which is what lets a stacked fraction or a radical appear mid-sentence. It is typeset at
 *    `TeXConstants.STYLE_TEXT`. That distinction is not decoration: the display style sets every
 *    fraction and large operator at full size — measured, `\frac{1}{2}` at 17sp / 44.6px is 106px
 *    tall in display style against 70px in text style — and a 106px formula cannot fit the 26sp
 *    line the reply's prose is set in. Every formula used to take the display style, inline ones
 *    included, which is what made a fraction mid-sentence overflow its line.
 *
 * ## The baseline and the line box, which are the two numbers this file has to get right
 *
 * A formula's baseline is not its bottom: `\frac{1}{2}` and `\int` hang below the line they are
 * written on. Compose places an inline placeholder by an edge, and **which edge decides which side
 * of the line box grows** — read out of `PlaceholderSpan.getSize`, where the only alignment that
 * moves neither side is the one that moves *both*:
 *
 * | `PlaceholderVerticalAlign` | what it does to the line |
 * | --- | --- |
 * | `AboveBaseline` | `ascent = min(ascent, -height)` — only the ascent grows |
 * | `TextTop` | `descent = max(descent, ascent + height)` — only the descent grows |
 * | `TextBottom` | only the ascent grows, from the text's descent |
 * | `TextCenter` | **both**, by centring the box on the text's own centre |
 *
 * So a formula placed with `AboveBaseline` — the obvious choice, and the first one tried — is
 * pushed up until its whole depth is above the baseline (the report "公式渲染位置和排版存在严重问题"),
 * and one placed by its top is pushed down. Neither can be right, because a formula needs room on
 * *both* sides of the baseline: its ascender above it and its descender below.
 *
 * `TextCenter` can: `getSize` centres a box of the declared height on `(ascent + descent) / 2` and
 * grows the two sides until it fits. This file therefore computes, per formula, the **smallest box
 * centred on the text's centre that contains the formula's ink** —
 * `half = max(|inkTop - centre|, |inkBottom - centre|)` — and then draws the drawable inside it at
 * the offset that puts the formula's own baseline on the line's.
 *
 * **The grown placeholder is not enough on its own.** Compose's `LineHeightSpan.chooseHeight`
 * overwrites the ascent and descent a placeholder reported with the `TextStyle`'s fixed
 * `lineHeight` whenever the style names one, and the reply's style does: 26sp. The placeholder still
 * draws at its true height, so a 106px fraction's box was laid out inside a 68px line and hung over
 * the line below, where the display formula's horizontal scroller clipped it. [withMathLineHeight]
 * therefore raises the style's `lineHeight` to the tallest placeholder in the block before the
 * `Text` is measured; a formula that fits leaves the style unchanged.
 *
 * The text's centre comes from a `TextMeasurer` measurement of the same style, because it is the
 * only place the font's ascent and descent are readable — the same measurement the inline code
 * fill makes in `CodeChip.kt`, for the same reason and in the same shape. It is a number the
 * renderer cannot know: the *sentence's* font decides it.
 */
package pi.kit.mob.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import kotlin.math.abs
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * What a formula is typeset with.
 *
 * ## Why the ink comes from the scheme and not from a composable call
 *
 * The ink is `onSurface`, read from `MiuixTheme.colorScheme` and memoised on it, because it
 * is part of the cache key: a light/dark switch has to re-typeset, and a recomposition that
 * changed nothing must not. It is an `Int` rather than a `Color` because it crosses to the
 * worker, and it is read here rather than inside the text walk so that the walk stays pure —
 * which is what lets the JVM suite assert on the tokens.
 *
 * ## Why the size is in pixels
 *
 * Because that is what the renderer takes and what the cache is keyed on, and because a font
 * scale change is a different size in pixels and must therefore be a different entry. The
 * conversion is done once, in composition, where `LocalDensity` — which carries the font scale —
 * is available.
 *
 * ## Why the style is part of the spec
 *
 * A formula is typeset either as display mathematics or as text mathematics, and the renderer picks
 * the size of fractions, sums and limits from that choice. At the same font size `\frac{1}{2}` is
 * 106px tall in display style and 70px in text style; only the text style's fraction belongs in a
 * 26sp prose line, and the display style's is one and a half times the line. The flag is part of
 * [MathSpec] — and therefore of the cache key — because the same body at the same size is two
 * different drawings.
 */
@Composable
internal fun mathSpec(fontSize: TextUnit, display: Boolean = false): MathSpec {
    val ink = MiuixTheme.colorScheme.onSurface
    val density = LocalDensity.current
    return remember(fontSize, ink, density, display) {
        MathSpec(
            sizePx = with(density) { fontSize.toPx() },
            ink = ink.toArgb(),
            display = display,
        )
    }
}

/**
 * The width a reply has to fit its formulas into, in pixels, published by `MarkdownText`.
 *
 * A formula wider than its line cannot be read inline and **cannot be scrolled either**: the
 * paragraph does not scroll horizontally, the transcript does not, and a horizontal drag inside a
 * paragraph belongs to the selection. So an inline formula that does not fit is drawn *scaled down*
 * to fit, which loses size and keeps the whole formula — the alternative is a tail nobody can
 * reach. A display formula is not clamped: its block scrolls horizontally and says so.
 *
 * Infinite when nothing published a width, which is what a preview, a screenshot test or a caller
 * that is only measuring wants.
 */
internal val LocalFormulaWidth = compositionLocalOf { Float.POSITIVE_INFINITY }

/**
 * A run of prose, split into the text it draws literally and the formulas that have to be
 * typeset before the text can be laid out.
 *
 * [text] declares each formula as a placeholder; [inlineContent] supplies the typeset one for
 * every placeholder [text] names. They are produced together because a key that only one of them
 * knows about is a crash, not a blank space: `Text` throws when a placeholder names content the
 * map does not hold.
 *
 * `@Immutable`, and it is load-bearing rather than decoration. This object is the argument of
 * every `Text` in a rendered reply, and Compose infers a class's stability from its *fields*:
 * `AnnotatedString` and `Map` are both read as unstable, so without the annotation every one of
 * those `Text`s was recomposed on every pass of the transcript — which while an answer streams is
 * every token, for every block of every message on screen. The values are genuinely immutable
 * (the pair is built once per block and never mutated afterwards), so the promise the annotation
 * makes is one this class already keeps.
 */
@Immutable
internal class MathInline(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
) {
    /**
     * The tallest placeholder box in this run, or `TextUnit.Unspecified` when it holds none.
     *
     * The placeholders are in sp, because that is the unit `InlineTextContent` takes, so this is
     * directly usable as a `TextStyle.lineHeight`. It exists because a placeholder's grown ascent
     * and descent are overwritten by Compose's `LineHeightSpan.chooseHeight` when the style fixes a
     * line height — see [withMathLineHeight].
     */
    val requiredLineHeight: TextUnit
        get() = inlineContent.values
            .map { it.placeholder.height }
            .maxByOrNull { it.value }
            ?: TextUnit.Unspecified
}

/**
 * [this] with its `lineHeight` raised to fit [math]'s tallest formula, when the formula needs it.
 *
 * Compose's `LineHeightSpan.chooseHeight` overwrites the ascent and descent a placeholder reports
 * with the `TextStyle`'s fixed `lineHeight`; the placeholder itself still draws at its true height.
 * A 26sp line is 68px at this app's density, and a 106px fraction's box was squeezed into it while
 * the ink still drew at 106px, hanging over the line below and clipped by the display formula's
 * horizontal scroller. Raising the style's line height is the only way to keep the line box and the
 * placeholder's box the same number.
 *
 * A style with no line height (`TextUnit.Unspecified`) adopts the placeholder's; a formula that fits
 * leaves the style exactly as it was. `Placeholder.height` is in sp, so the result stays in sp.
 */
internal fun TextStyle.withMathLineHeight(math: MathInline): TextStyle {
    val required = math.requiredLineHeight
    if (required == TextUnit.Unspecified) return this
    val current = lineHeight
    return when {
        current == TextUnit.Unspecified || required > current -> copy(lineHeight = required)
        else -> this
    }
}

/**
 * A paragraph's inline text, with any mathematics in it typeset.
 *
 * Falls back to a plain render when the fragment has no formula, so the common case —
 * ordinary prose, and prose with a code span in it — never asks the cache for anything.
 *
 * [source] and [style] are what the result is keyed on, and both are stable across a
 * recomposition that changed nothing: `style` is an immutable data class and the text style is
 * the theme's own. That matters more than it looks — the transcript re-renders the last row on
 * every streamed token, and a key that missed would re-typeset every formula on screen each time.
 * [cached] is the same idea one level down: see `MathCache.versions` for why a finished formula
 * has to be a key rather than something read while the value is being computed.
 */
@Composable
internal fun rememberMathInline(
    source: String,
    inline: InlineStyle,
    textStyle: TextStyle,
    /**
     * True for a display formula: its block is the formula and scrolls horizontally, so it must
     * **not** be scaled down to the line the way an inline one is, and it is typeset at
     * `TeXConstants.STYLE_DISPLAY`. Every other formula is `TeXConstants.STYLE_TEXT`, whose smaller
     * fractions are what let one sit in a sentence.
     */
    display: Boolean = false,
): MathInline {
    if (!containsInlineMath(source)) {
        return remember(source, inline, textStyle) { renderInline(source, inline) }
    }

    // Remembered inside the branch so that a paragraph with no formula in it never
    // builds one.
    val spec = mathSpec(textStyle.fontSize, display = display)
    // The placeholder's size is in `sp`, so the conversion back out of pixels depends on the
    // density as well as on the formula: a font-scale change arrives as a new `Density`, and a
    // key that missed it would draw every formula at the old size. The font metrics are read here
    // for the same reason — the sentence's own font decides where a formula's baseline goes.
    val density = LocalDensity.current
    val metrics = rememberFontMetrics(textStyle)
    val lineWidth = if (display) Float.POSITIVE_INFINITY else LocalFormulaWidth.current

    // Read in composition, and it is what makes a finished measurement redraw *this* block.
    // Per block rather than global: see `MathCache.versions`.
    val cached = MathCache.versionOf(MathCache.keysFor(source, spec))

    return remember(source, inline, textStyle, spec, cached, density, metrics, lineWidth) {
        renderInline(
            text = source,
            style = inline,
            // The formula is taken from the cache when it is there and asked for when it is not,
            // and a miss returns null so the block draws the formula's own source. That is the
            // whole of the off-thread change: no typesetting on this thread, and a block that
            // redraws itself when its formulas arrive (see `MathCache`).
            method = { body, _ ->
                MathCache.get(body, spec)?.let { formula ->
                    formulaInlineContent(formula, density, metrics, lineWidth)
                } ?: run {
                    MathCache.request(body, spec)
                    null
                }
            },
        )
    }
}

/**
 * The ascent and descent of the prose a formula sits in, in pixels, with the ascent negative.
 *
 * Read from a one-line measurement of a two-glyph sample in the same style, because Compose gives
 * an `InlineTextContent` no way to ask the line it is being placed on what its metrics are, and the
 * placeholder's box has to be built around them — see this file's header. The sample is `"Hg"`
 * rather than `"x"`: a descender in the sample is what makes the measured descent the font's own
 * rather than zero.
 *
 * `lineHeight` is dropped for the measurement on purpose. It is a property of the paragraph's *line
 * box*, which is exactly what the placeholder is allowed to grow; measuring with it would make
 * every formula's box depend on a height the line may not end up having.
 */
@Composable
private fun rememberFontMetrics(textStyle: TextStyle): FontMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, textStyle, density) {
        val sample = measurer.measure(
            text = AnnotatedString(SAMPLE_GLYPHS),
            style = textStyle.copy(lineHeight = TextUnit.Unspecified),
            maxLines = 1,
        )
        FontMetrics(ascent = -sample.firstBaseline, descent = sample.size.height - sample.firstBaseline)
    }
}

/** Two glyphs with an ascender and a descender, so a measured line has both. */
private const val SAMPLE_GLYPHS = "Hg"

/**
 * A font's ascent and descent above and below its baseline, in pixels, the ascent negative.
 *
 * `@Immutable` so that it can be a `remember` key without invalidating the block on every pass.
 */
@Immutable
internal data class FontMetrics(val ascent: Float, val descent: Float)

/**
 * A typeset formula as the inline content a paragraph can hold.
 *
 * The placeholder is the smallest box **centred on the text's centre** that contains the formula's
 * ink, and the drawable is drawn inside it so that the formula's own baseline lands on the line's.
 * This file's header has the alignment table that says why the centre is the only alignment that
 * works: it is the one that grows both the ascent and the descent. The first version of this placed
 * the formula by its bottom and pushed it up until its depth was above the baseline — every
 * fraction floated, and every formula taller than the text overlapped the line above it.
 *
 * Drawn rather than rasterised: a bitmap per formula would be tens of megabytes for a long reply
 * that composes every one of its blocks at once, and the drawable costs 183 µs to draw. The bounds
 * are set on every draw because `Drawable` carries them, and the same drawable is shared by every
 * block that uses the same formula.
 */
private fun formulaInlineContent(
    formula: Formula,
    density: Density,
    metrics: FontMetrics,
    lineWidthPx: Float,
): InlineTextContent {
    // An inline formula wider than its line is scaled to fit — see `LocalFormulaWidth`. The scale
    // is uniform, because a squashed fraction is a different formula, and it is applied at the
    // *drawing* rather than in the cache: the same formula at the same size is the same entry
    // whether this block had room for it or not.
    val scale = if (lineWidthPx.isFinite() && formula.widthPx > lineWidthPx) {
        (lineWidthPx / formula.widthPx).coerceAtLeast(MIN_FORMULA_SCALE)
    } else {
        1f
    }
    val widthPx = formula.widthPx * scale
    val heightPx = formula.heightPx * scale
    val depthPx = formula.depthPx * scale
    // The text's centre relative to the baseline, and the formula's ink relative to the same.
    val centre = (metrics.ascent + metrics.descent) / 2f
    val inkTop = -(heightPx - depthPx)
    val inkBottom = depthPx
    // The box has to contain the ink and be centred on the text; the tighter of the two distances
    // decides how tall it is, and the offset then puts the formula's baseline on the line's.
    val half = maxOf(abs(inkTop - centre), abs(inkBottom - centre))
    val boxPx = half * 2f
    val offsetPx = inkTop - (centre - half)

    return InlineTextContent(
        Placeholder(
            width = with(density) { widthPx.toSp() },
            height = with(density) { boxPx.toSp() },
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            // `offset` rather than padding: the drawable is the formula's own size and only its
            // position inside the box changes, so nothing re-measures the formula.
            Canvas(
                Modifier
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .offset(y = with(density) { offsetPx.toDp() }),
            ) {
                formula.drawable.setBounds(0, 0, formula.widthPx, formula.heightPx)
                if (scale == 1f) {
                    drawIntoCanvas { formula.drawable.draw(it.nativeCanvas) }
                } else {
                    // The drawable is drawn at its own size into a canvas scaled about the
                    // formula's top-left, so the ink lands inside the smaller box without the
                    // drawable itself knowing.
                    scale(scale) { drawIntoCanvas { formula.drawable.draw(it.nativeCanvas) } }
                }
            }
        }
    }
}

/**
 * The smallest an over-wide inline formula is drawn at.
 *
 * Half size, because below that a formula is unreadable rather than merely small; a formula that
 * still does not fit at half size overflows the line, which is what it did before any of this.
 */
private const val MIN_FORMULA_SCALE = 0.5f

/**
 * Warms JLaTeXMath before any formula is drawn.
 *
 * The library parses its predefined formulas and loads its first font on the first formula it
 * typesets, which costs about 10 ms and which would otherwise be paid by the first formula the
 * worker is handed — on the frame that opens a conversation, where it is one formula's arrival
 * later than the reader expects. Called once, where no formula is on screen.
 *
 * The library's own `JLatexMathInitProvider` is what installs the application context it loads
 * its fonts from, so nothing here calls `JLatexMathAndroid.init`; the manifest merge is what
 * guarantees it, and a missing provider would fail on the first formula rather than silently.
 */
@Composable
internal fun WarmFormulaRenderer() {
    // The paragraph style's size in the text style, because that is the size a reply's prose is
    // set in and therefore the size most formulas will be asked for — inline mathematics
    // outnumbers display, and warming any other entry would fill the cache with one nothing draws.
    val spec = mathSpec(MiuixTheme.textStyles.paragraph.fontSize)
    LaunchedEffect(spec) { MathCache.warm(spec) }
}
