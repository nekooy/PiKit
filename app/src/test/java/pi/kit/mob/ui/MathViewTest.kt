package pi.kit.mob.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line-height fix in `MathView.kt`, pinned on the JVM.
 *
 * A formula placeholder reports the ascent and descent its box needs, but Compose's
 * `LineHeightSpan.chooseHeight` replaces those with the `TextStyle`'s fixed `lineHeight` whenever
 * the style names one — and the reply's prose style does, at 26sp. The placeholder still draws at
 * its true height, so a 106px fraction's box was laid out in a 68px line and its ink hung over the
 * line below. [withMathLineHeight] raises the style to the tallest placeholder and leaves it alone
 * when every formula fits.
 */
class MathViewTest {

    @Test
    fun `a placeholder taller than the line raises the line height`() {
        val style = TextStyle(fontSize = 17.sp, lineHeight = 26.sp)
        // 40sp is the 106px display fraction at the emulator's 2.625 density.
        val adjusted = style.withMathLineHeight(mathInline(listOf(40.sp)))

        assertEquals("the line is as tall as the formula's box", 40.sp, adjusted.lineHeight)
        assertEquals("and the rest of the style is untouched", 17.sp, adjusted.fontSize)
    }

    @Test
    fun `a formula that fits leaves the line height alone`() {
        val style = TextStyle(lineHeight = 26.sp)
        val adjusted = style.withMathLineHeight(mathInline(listOf(20.sp)))

        assertEquals(26.sp, adjusted.lineHeight)
        assertSame("the style is not copied when nothing needs raising", style, adjusted)
    }

    @Test
    fun `the tallest placeholder in a block decides`() {
        val style = TextStyle(lineHeight = 26.sp)
        val adjusted = style.withMathLineHeight(mathInline(listOf(30.sp, 44.sp, 40.sp)))

        assertEquals(44.sp, adjusted.lineHeight)
    }

    @Test
    fun `an unspecified line height adopts the formula's`() {
        val style = TextStyle(lineHeight = TextUnit.Unspecified)
        val adjusted = style.withMathLineHeight(mathInline(listOf(48.sp)))

        assertEquals(48.sp, adjusted.lineHeight)
    }

    @Test
    fun `a block with no formula keeps its line height`() {
        val style = TextStyle(lineHeight = 26.sp)
        val prose = MathInline(AnnotatedString("plain prose"), emptyMap())

        assertTrue("no placeholder means no line to fit", prose.requiredLineHeight == TextUnit.Unspecified)
        assertEquals(26.sp, style.withMathLineHeight(prose).lineHeight)
    }

    /** One rendered block holding a placeholder of each [heights] value, in order. */
    private fun mathInline(heights: List<TextUnit>): MathInline {
        val content = heights.mapIndexed { index, height ->
            "math$index" to InlineTextContent(
                Placeholder(
                    width = 1.sp,
                    height = height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {}
        }.toMap()
        return MathInline(AnnotatedString("x"), content)
    }
}
