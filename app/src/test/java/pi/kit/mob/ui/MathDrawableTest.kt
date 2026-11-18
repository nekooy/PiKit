package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision `MathDrawable` makes, pinned on the JVM.
 *
 * The defect is in JLaTeXMath's shared `Paint`: `AndroidGraphics2D` sets `Paint.Style.STROKE` for
 * every line, arc and rectangle outline, leaves it that way, and `drawChars` draws text with
 * whatever style is there — so a formula whose rule is drawn before its glyphs (`\boxed`, an
 * array's `\hline`, a `\cancel`'s strike) comes out hollow. `PaintStyleGuard` is the fix's whole
 * decision, and it is pure Kotlin on purpose: the drawing itself needs Android, so a unit test can
 * only pin the state machine. What it fixes on a device is a screenshot, not this file.
 *
 * [formulaScale] is the other half — the packaging replicated from `JLatexMathDrawable.draw`, so
 * that the geometry `MathCache` reasons about (a formula exactly at its typeset size, centred when
 * a caller gives it another box) is unchanged by drawing it ourselves.
 */
class MathDrawableTest {

    // ------------------------------------------------- the shared Paint's style

    @Test
    fun `glyphs need no fill when nothing has been stroked`() {
        // The Paint starts as FILL and ordinary mathematics is glyphs only, so the common case
        // must cost nothing at all.
        val guard = PaintStyleGuard()

        assertFalse("a formula with no rule in it pays no extra canvas call", guard.takeGlyphReset())
    }

    @Test
    fun `a stroke before glyphs asks for exactly one fill`() {
        // `\boxed{}` is this: `FramedBox` draws its frame line, then draws the content boxes.
        val guard = PaintStyleGuard()
        guard.onStrokeOp()

        assertTrue("the hollow-text bug: the first glyph after the frame needs FILL", guard.takeGlyphReset())
        assertFalse("and the next run of glyphs must not pay for it again", guard.takeGlyphReset())
    }

    @Test
    fun `a fill resets the need without help from the glyph path`() {
        // `\boxed{}` draws a background fill before the frame when it has one, and array cells
        // fill their backgrounds. A fill already leaves the Paint as FILL, so no zero-size fill is
        // owed on top of it.
        val guard = PaintStyleGuard()
        guard.onStrokeOp()
        guard.onFillOp()

        assertFalse(guard.takeGlyphReset())
    }

    @Test
    fun `every stroke owes its own fill`() {
        // An array's `\hline` above each row: rule, glyphs, rule, glyphs. The reset is consumed by
        // the text that needed it, so the second rule is not silently skipped.
        val guard = PaintStyleGuard()
        guard.onStrokeOp()
        assertTrue(guard.takeGlyphReset())
        guard.onStrokeOp()
        assertTrue(guard.takeGlyphReset())
        assertFalse(guard.takeGlyphReset())
    }

    @Test
    fun `a stroke after glyphs still owes a fill`() {
        // The order that matters is rule-then-text, not what came before the rule: a line drawn
        // after a run of text points the same shared Paint at STROKE for the next one.
        val guard = PaintStyleGuard()
        assertFalse(guard.takeGlyphReset())
        guard.onStrokeOp()
        assertTrue(guard.takeGlyphReset())
    }

    // ------------------------------------- the bounds arithmetic, replicated from the library

    @Test
    fun `a formula that fits its box is drawn at its own size and centred`() {
        // MathView sets the bounds to the formula's own width and height, so this is the normal
        // path: scale 1, no offset. The centring case is the same arithmetic with a taller box.
        assertEquals(FormulaScale(1f, 10, 10, 0), formulaScale(10, 10, 10, 10))
        assertEquals(FormulaScale(1f, 10, 10, 45), formulaScale(10, 10, 100, 100))
    }

    @Test
    fun `an overflowing axis scales uniformly and the other follows`() {
        // A drawable handed a narrower box than its icon — the library scales *down* only, and the
        // height follows the same factor so a fraction is not squashed. Width rounds with the
        // library's `+ 0.5f`, and the remainder is split above and below.
        assertEquals(FormulaScale(0.7f, 7, 7, 0), formulaScale(10, 10, 7, 7))
        assertEquals(FormulaScale(0.4f, 40, 20, 0), formulaScale(100, 50, 100, 20))
        assertEquals(FormulaScale(0.5f, 100, 5, 47), formulaScale(200, 10, 100, 100))
    }

    @Test
    fun `empty bounds draw nothing rather than a NaN scale`() {
        // A Drawable is allowed to be drawn before its bounds are set. The library's arithmetic
        // would divide by the empty axis here and paint a NaN-sized formula; nothing is the honest
        // answer, and the caller that forgot the bounds is visible as a formula that did not draw.
        assertEquals(FormulaScale(0f, 0, 0, 0), formulaScale(10, 10, 0, 0))
        assertEquals(FormulaScale(0f, 0, 0, 0), formulaScale(0, 10, 100, 100))
    }
}
