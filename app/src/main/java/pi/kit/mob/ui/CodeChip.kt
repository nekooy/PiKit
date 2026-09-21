package pi.kit.mob.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * The rounded fill behind an inline code span.
 *
 * ## Why the fill is drawn and the span is real text
 *
 * A code span used to be an `InlineTextContent` — a composable placed inside the
 * paragraph — because Compose has no rounded background for a text span:
 * `SpanStyle.background` is a square fill and ui-text names no `BackgroundStyle`.
 * That bought the radius and cost three things a reader noticed:
 *
 *  * **A selection could not highlight it.** `SelectionContainer` draws a
 *    selection from the `TextLayoutResult` of the text it crosses, and that pass
 *    happens *before* the inline content is drawn, so an opaque chip covered the
 *    highlight that was under it.
 *  * **It could not be selected in part.** A placeholder occupies one character of
 *    the annotated string — `U+FFFC`, the object replacement character — so a drag
 *    inside the code either took the whole span or none of it, and copying a
 *    message that contained code put `U+FFFC` on the clipboard instead of the
 *    code.
 *  * **It could not be padded.** The chip's box was the *placeholder*, and the
 *    fill was drawn inside it, so the pad the placeholder added appeared outside
 *    the fill: the report "文字开始前和结束后空余太小，戛然而止".
 *
 * So the code span is ordinary text in the monospace face, tagged with
 * [CODE_SPAN_TAG], and the fill is drawn *behind* the whole `Text` by
 * [CodeChipText]. Everything the reader does — selecting half of it, dragging the
 * handles, copying — is then the platform's own behaviour on real characters,
 * because there is nothing special about them any more, and the fill is the one
 * part that is ours.
 *
 * ## The two numbers the fill gets from the font, not from the line
 *
 * The fill is **the font's own box**, not the line box: `ascent` above the
 * baseline and `descent` below it, measured on `"Hg"` in the span's style with its
 * `lineHeight` dropped — the same measurement `MathView` makes for a formula's
 * baseline. The line box is 22sp for `bodyMedium` while the font's box is about
 * 16sp, and filling the line box is what made two consecutive lines of code look
 * joined together: the chip touched the line above and the line below it. With the
 * font's box plus [CODE_CHIP_PAD_Y] the chip has about 2dp of air on each side of
 * it inside the line.
 *
 * The horizontal box is the span's own extent plus [CODE_CHIP_PAD_X], which is the
 * one thing the pad *can* be here: a background drawn from the glyphs' advances
 * outwards, because the layout no longer holds a wider box for it. It is drawn
 * behind the text, so the few dp it claims from a neighbouring character are
 * covered by that character's own ink rather than by a gap.
 */
internal const val CODE_SPAN_TAG = "pi.kit.mob.inline-code"

/**
 * A `Text` that draws the fill of every inline code span behind itself.
 *
 * A wrapper rather than a modifier because the fill needs the `TextLayoutResult`,
 * which only `onTextLayout` hands out, and the two have to be wired together at
 * one place or every call site would have to remember to do it. The layout is
 * captured in a state read *inside* the draw lambda, so a new layout invalidates
 * the draw and not the composition — which matters on the transcript, where the
 * text is laid out again for every streamed token.
 *
 * [background] is `Color.Unspecified` for "no fill", which is what the table
 * measurement passes: a filled chip in every cell of a measured grid reads as a
 * grid of errors. Nothing is captured and nothing is drawn in that case.
 */
@Composable
internal fun CodeChipText(
    text: AnnotatedString,
    style: TextStyle,
    background: Color,
    modifier: Modifier = Modifier,
    inlineContent: Map<String, InlineTextContent> = emptyMap(),
    fontWeight: FontWeight? = null,
    textDecoration: TextDecoration? = null,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    softWrap: Boolean = true,
) {
    val pen = rememberCodeChipPen(style, background)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        modifier = modifier.drawWithContent {
            val result = layout
            if (result != null && pen != null) pen.draw(this, result, text)
            drawContent()
        },
        color = color,
        style = style,
        fontWeight = fontWeight,
        textDecoration = textDecoration,
        textAlign = textAlign,
        softWrap = softWrap,
        inlineContent = inlineContent,
        onTextLayout = { layout = it },
    )
}

/**
 * The chip's geometry, resolved once per style and colour rather than per frame.
 *
 * Null when there is no fill to draw, which is the measurement path and the
 * previews.
 */
@Composable
private fun rememberCodeChipPen(style: TextStyle, background: Color): CodeChipPen? {
    if (background == Color.Unspecified) return null
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, style, background, density) {
        // The sample has an ascender and a descender, so a measured line carries
        // both: `"x"` measures a descent of zero and the fill would then end on
        // the baseline. `lineHeight` is dropped because it is a property of the
        // *paragraph's* line box — exactly what the fill must not be.
        val sample = measurer.measure(
            text = AnnotatedString(SAMPLE_GLYPHS),
            style = style.copy(lineHeight = TextUnit.Unspecified),
            maxLines = 1,
        )
        CodeChipPen(
            color = background,
            ascent = -sample.firstBaseline,
            descent = sample.size.height - sample.firstBaseline,
            padX = with(density) { CODE_CHIP_PAD_X.toPx() },
            padY = with(density) { CODE_CHIP_PAD_Y.toPx() },
            radius = with(density) { CODE_CHIP_RADIUS.toPx() },
        )
    }
}

/** Two glyphs with an ascender and a descender, so a measured line has both. */
private const val SAMPLE_GLYPHS = "Hg"

/** The fill's geometry in pixels. */
@Immutable
private class CodeChipPen(
    private val color: Color,
    private val ascent: Float,
    private val descent: Float,
    private val padX: Float,
    private val padY: Float,
    private val radius: Float,
) {
    /**
     * Draws one rounded rectangle per line the span crosses.
     *
     * Per line, because a code span long enough to wrap is two chips as far as a
     * reader is concerned — the same shape a selection of it takes — and because a
     * single rectangle spanning the wrap would cover the end of one line and the
     * start of the next.
     */
    fun draw(scope: DrawScope, layout: TextLayoutResult, text: AnnotatedString) {
        val ranges = text.getStringAnnotations(CODE_SPAN_TAG, 0, text.length)
        if (ranges.isEmpty()) return
        for (range in ranges) {
            val start = range.start
            val end = range.end
            if (end <= start) continue
            val firstLine = layout.getLineForOffset(start)
            val lastLine = layout.getLineForOffset(end - 1)
            for (line in firstLine..lastLine) {
                val from = maxOf(start, layout.getLineStart(line))
                val to = minOf(end, layout.getLineEnd(line, visibleEnd = false))
                if (to <= from) continue
                val left = layout.getHorizontalPosition(from, usePrimaryDirection = false)
                val right = layout.getHorizontalPosition(to, usePrimaryDirection = false)
                if (right <= left) continue
                val baseline = layout.getLineBaseline(line)
                // Clamped to the text's own box: a chip at the start of a line
                // would otherwise draw its pad outside the paragraph, and a pad
                // on an over-wide first line would paint past the layout's width.
                val chipLeft = (left - padX).coerceAtLeast(0f)
                val chipRight = (right + padX).coerceAtMost(scope.size.width)
                val chipTop = (baseline + ascent - padY).coerceAtLeast(0f)
                val chipBottom = (baseline + descent + padY).coerceAtMost(scope.size.height)
                if (chipRight <= chipLeft || chipBottom <= chipTop) continue
                val height = chipBottom - chipTop
                scope.drawRoundRect(
                    color = color,
                    topLeft = Offset(chipLeft, chipTop),
                    size = Size(chipRight - chipLeft, height),
                    // Bounded by the height, because a pill needs half of it and a
                    // larger radius is silently clamped by the platform anyway.
                    cornerRadius = CornerRadius(minOf(radius, height / 2f)),
                )
            }
        }
    }
}

/** The gap between a code span's text and its fill, on each side. */
private val CODE_CHIP_PAD_X = 3.dp

/**
 * The fill's breathing room above and below the glyphs.
 *
 * 1dp, and the number is the *line*: at `bodyMedium` the font's box is about 16sp
 * inside a 22sp line, so the chip already has 3dp of the line's own leading on each
 * side of it and this is only what squares the corners off. More than that and the
 * chip grows past the line box again, which is the state this file was written to
 * leave behind.
 */
private val CODE_CHIP_PAD_Y = 1.dp

/**
 * The code chip's corner radius.
 *
 * 6dp rather than the 8dp a block of code uses: a chip is one line tall, and at 8dp
 * on a 22sp line box the corners are nearly half its height and it stops reading as
 * a rounded rectangle. The height clamps it to a pill where the line is shorter
 * than 12dp, which is what the platform would do with a larger number anyway.
 */
private val CODE_CHIP_RADIUS = 6.dp
