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

    @Test
    fun `rows with no environment of their own are wrapped in aligned`() {
        // The report "多行公式不显示": `&` and `\\` outside an environment are a parse
        // error to JLaTeXMath, and a writer who omits `\begin{aligned}` still means
        // two rows.
        assertEquals(
            "\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}",
            LatexCompat.wrapRows("a &= b \\\\\nc &= d"),
        )
    }

    @Test
    fun `a body that already opens an environment is left alone`() {
        val cases = "\\begin{cases}\nx = 1 & y = 2 \\\\\nz = 3\n\\end{cases}"
        assertEquals(cases, LatexCompat.wrapRows(cases))
    }

    @Test
    fun `a one-line formula is left alone`() {
        assertEquals("\\frac{a}{b}", LatexCompat.wrapRows("\\frac{a}{b}"))
    }

    @Test
    fun `row-break spacing is dropped even inside an environment`() {
        // The wrap is skipped when the body already opens one, but the dimension
        // goes either way: JLaTeXMath refuses `\\[6pt]` wherever it appears.
        assertEquals(
            "\\begin{aligned}\na &= b \\\\\nc &= d\n\\end{aligned}",
            LatexCompat.wrapRows("\\begin{aligned}\na &= b \\\\[6pt]\nc &= d\n\\end{aligned}"),
        )
    }

    @Test
    fun `a row break's spacing argument is dropped and the break kept`() {
        // `\\[6pt]` is a row break with extra vertical space. JLaTeXMath refuses the
        // optional dimension, and the report "换行间距指令如[6pt]不会正常渲染" is that
        // refusal. The break is the mathematics; the dimension is layout.
        assertEquals("a &= b \\\\ c &= d", LatexCompat.rewrite("a &= b \\\\[6pt] c &= d"))
        assertEquals("a \\\\ b", LatexCompat.rewrite("a \\\\[1em] b"))
        assertEquals("a \\\\ b", LatexCompat.rewrite("a \\\\* b"))
    }

    @Test
    fun `vertical space is dropped like horizontal space`() {
        assertEquals("xy", LatexCompat.rewrite("x\\vspace{6pt}y"))
        assertEquals("x  y", LatexCompat.rewrite("x \\vspace{6pt} y"))
    }

    @Test
    fun `a starred command drops its group like the unstarred one`() {
        assertEquals("xy", LatexCompat.rewrite("x\\hspace*{6pt}y"))
    }

    @Test
    fun `sqrt's optional root is kept`() {
        // `\sqrt[3]{x}` is a cube root, not a row break: the bracket rule only
        // applies after `\\`.
        assertEquals("\\sqrt[3]{x}", LatexCompat.rewrite("\\sqrt[3]{x}"))
    }
}
