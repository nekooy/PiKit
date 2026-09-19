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
 * ## The two shapes
 *
 *  - **A display formula** — `$$…$$`, `\[…\]`, `\begin{aligned}` — is a block of its own, drawn
 *    as a `Text` whose whole content is one placeholder, so the transcript's selection treats it
 *    like any other run of prose and a formula wider than the screen can be scrolled.
 *  - **Inside a paragraph**, the formula is an `InlineTextContent` of exactly the typeset size,
 *    which is what lets a stacked fraction or a radical appear mid-sentence.
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
 * the offset that puts the formula's own baseline on the line's. A formula no taller than the text
 * leaves the line height alone; a stacked fraction grows its own line, which is what a stacked
 * fraction has to do.
 *
 * The text's centre comes from a `TextMeasurer` measurement of the same style, because it is the
 * only place the font's ascent and descent are readable — the same measurement the inline code chip
 * already makes. It is a number the renderer cannot know: the *sentence's* font decides it.
 */
package pi.kit.mob.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * What a formula is typeset with.
 *
 * ## Why the ink comes from the scheme and not from a composable call
 *
 * The ink is `onSurface`, read from `MaterialTheme.colorScheme` and memoised on it, because it
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
 */
@Composable
internal fun mathSpec(fontSize: TextUnit): MathSpec {
    val ink = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    return remember(fontSize, ink, density) {
        MathSpec(sizePx = with(density) { fontSize.toPx() }, ink = ink.toArgb())
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
)

/**
 * A paragraph's inline text, with any mathematics in it typeset.
 *
 * Falls back to a plain render when the fragment has no formula and no code span, so the common
 * case — ordinary prose — never asks the cache for anything and never builds a chip.
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
    /** False for a table cell, which is *measured* rather than drawn with this result. */
    codeChips: Boolean = true,
    /**
     * True for a display formula: its block is the formula and scrolls horizontally, so it must
     * **not** be scaled down to the line the way an inline one is.
     */
    display: Boolean = false,
): MathInline {
    val hasMath = containsInlineMath(source)
    val hasCode = codeChips && inline.codeBackground != Color.Unspecified && containsInlineCode(source)
    if (!hasMath && !hasCode) {
        return remember(source, inline, textStyle) { renderInline(source, inline) }
    }

    // Both are remembered *inside* their branch so that a paragraph with neither a formula nor a
    // code span in it never builds one.
    val spec = if (hasMath) mathSpec(textStyle.fontSize) else null
    val chip = if (hasCode) rememberCodeChip(textStyle, inline.codeBackground) else null
    // The placeholder's size is in `sp`, so the conversion back out of pixels depends on the
    // density as well as on the formula: a font-scale change arrives as a new `Density`, and a
    // key that missed it would draw every formula at the old size. The font metrics are read here
    // for the same reason — the sentence's own font decides where a formula's baseline goes.
    val density = LocalDensity.current
    val metrics = if (hasMath) rememberFontMetrics(textStyle) else null
    val lineWidth = if (display) Float.POSITIVE_INFINITY else LocalFormulaWidth.current

    // Read in composition, and it is what makes a finished measurement redraw *this* block.
    // Per block rather than global: see `MathCache.versions`.
    val cached = if (spec != null) MathCache.versionOf(MathCache.keysFor(source, spec)) else 0

    return remember(source, inline, textStyle, spec, chip, cached, density, metrics, lineWidth) {
        renderInline(
            text = source,
            style = inline,
            // The formula is taken from the cache when it is there and asked for when it is not,
            // and a miss returns null so the block draws the formula's own source. That is the
            // whole of the off-thread change: no typesetting on this thread, and a block that
            // redraws itself when its formulas arrive (see `MathCache`).
            method = if (spec != null && metrics != null) {
                { body, _ ->
                    MathCache.get(body, spec)?.let { formula ->
                        formulaInlineContent(formula, density, metrics, lineWidth)
                    } ?: run {
                        MathCache.request(body, spec)
                        null
                    }
                }
            } else {
                null
            },
            code = chip,
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
    val spec = mathSpec(MaterialTheme.typography.bodyMedium.fontSize)
    LaunchedEffect(spec) { MathCache.warm(spec) }
}

/**
 * The measurer for an inline code span, as the `InlineTextContent` factory
 * `renderInline` wants.
 *
 * ## Why inline code is measured content rather than a `SpanStyle`
 *
 * Because **Compose has no rounded background for a text span**. `SpanStyle` carries
 * `background: Color`, which the text renderer fills as a plain rectangle, and ui-text 1.10 has no
 * `BackgroundStyle` to give it a radius — checked in the artefact, not assumed. A `Text` whose
 * code spans have square fills behind rounded ones everywhere else on the page is the report
 * "行内代码背景无圆角很难看".
 *
 * So the fill becomes a composable: this returns a factory that measures the span's text and
 * wraps it in a `Box` with [background] and a corner radius, which leaves `Text` to place it as an
 * `InlineTextContent` — the same mechanism the formulas use.
 *
 * ## The three numbers
 *
 * - **Width** is the measured width plus a symmetric pad. The pad is what keeps the fill off the
 *   glyphs; without it a code span's box touches its own first and last character and reads as a
 *   highlight rather than as a chip.
 * - **Height is the line box**, not the text: an `InlineTextContent` is *clipped* to the
 *   placeholder it declares, so a shorter box would cut the fill's top and bottom off rather than
 *   shrink it, and the code would look like it had been struck through. Filling the whole line
 *   box instead reads as a chip that spans the line, which is what a code span is in a monospaced
 *   face.
 * - **The radius is bounded by the height**, because a pill needs half the height and a radius
 *   larger than that is silently clamped by the platform. `min(8dp, h / 2)` is a rounded
 *   rectangle on a normal line and a pill on a very short one.
 *
 * The text is drawn in the *same* monospace style the span would have had, resolved against
 * [textStyle] so a code span inside a heading is drawn at the heading's size rather than at the
 * paragraph's.
 */
@Composable
private fun rememberCodeChip(
    textStyle: TextStyle,
    background: Color,
): (String) -> InlineTextContent {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = textStyle.copy(fontFamily = FontFamily.Monospace)

    return remember(measurer, textStyle, background, density) {
        { source ->
            val size = measurer.measure(source, style, maxLines = 1).size
            val padX = with(density) { CODE_CHIP_PAD_X.roundToPx() }
            InlineTextContent(
                // The placeholder is in `sp`, as it is for every inline content: the size
                // `TextMeasurer` returns is a pixel count, and `toSp()` is the conversion back
                // that the density this was measured with inverts.
                Placeholder(
                    width = with(density) { (size.width + padX * 2).toSp() },
                    height = with(density) { size.height.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = CODE_CHIP_PAD_X)
                        .background(background, RoundedCornerShape(CODE_CHIP_RADIUS)),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(source, style = style, color = Color.Unspecified, maxLines = 1)
                }
            }
        }
    }
}

/** The gap between a code span's text and its fill, on each side. */
private val CODE_CHIP_PAD_X = 3.dp

/**
 * The code chip's corner radius.
 *
 * 6dp rather than the 8dp a block of code uses: a chip is one line tall, and at 8dp on a 22sp line
 * box the corners are nearly half its height and it stops reading as a rounded rectangle. The
 * chip's own height clamps it to a pill where the line is shorter than 12dp, which is what the
 * platform would do with a larger number anyway.
 */
private val CODE_CHIP_RADIUS = 6.dp
