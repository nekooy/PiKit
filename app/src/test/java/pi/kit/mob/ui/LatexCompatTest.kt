package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rewrite a formula gets when the renderer refuses it.
 *
 * These are the constructs a model actually writes and JLaTeXMath does not have — measured by
 * sweeping forty common ones on the device and logging the failures, which is where the report
 * "某些情况没渲染" came from. What is pinned here is the *shape* of each rewrite, because the
 * failure mode of getting one wrong is silent: a formula that comes out with a term missing looks
 * like a formula.
 */
class LatexCompatTest {

    @Test
    fun `numbering and labels are dropped`() {
        // The space the dropped command leaves is kept: it is whitespace to the typesetter,
        // and trimming it would mean rewriting text this function was not asked about.
        assertEquals("x=1", LatexCompat.rewrite("\\tag{1} x=1").trim())
        assertEquals("x=1", LatexCompat.rewrite("x=1 \\label{eq:one}").trim())
        assertEquals("x=1", LatexCompat.rewrite("x=1\\nonumber"))
        assertEquals("x=1", LatexCompat.rewrite("x=1\\notag"))
    }

    @Test
    fun `a decoration is dropped and its argument kept`() {
        assertEquals("{x}", LatexCompat.rewrite("\\cancel{x}"))
        assertEquals("{a+b}", LatexCompat.rewrite("\\hcancel{a+b}"))
        assertEquals("{x}", LatexCompat.rewrite("\\sout{x}"))
    }

    @Test
    fun `chemistry becomes plain text`() {
        assertEquals("\\text{H2O}", LatexCompat.rewrite("\\ce{H2O}"))
    }

    @Test
    fun `a command with another spelling is renamed`() {
        assertEquals("\\textcolor{red}{x}", LatexCompat.rewrite("\\color{red}{x}"))
    }

    @Test
    fun `a nested group is kept whole`() {
        assertEquals("{a+\\frac{1}{b}}", LatexCompat.rewrite("\\cancel{a+\\frac{1}{b}}"))
    }

    @Test
    fun `a formula with nothing to rewrite is handed back unchanged`() {
        val untouched = "\\frac{a}{b} + \\sqrt{2} - \\int_{0}^{1} f(t)\\,dt"
        assertEquals(untouched, LatexCompat.rewrite(untouched))
    }

    @Test
    fun `a backslash that is not a command is left alone`() {
        // `\\` is a row break in an environment, and a rewrite that ate it would silently
        // collapse a two-row derivation onto one row.
        assertEquals("\\begin{matrix} a \\\\ b \\end{matrix}", LatexCompat.rewrite("\\begin{matrix} a \\\\ b \\end{matrix}"))
    }

    @Test
    fun `an identifier that merely starts with a command is not renamed`() {
        // `\centering` must not be mistaken for `\ce`, which is why the scan reads the
        // whole command name before it looks it up.
        assertEquals("\\centering x", LatexCompat.rewrite("\\centering x"))
    }
}
