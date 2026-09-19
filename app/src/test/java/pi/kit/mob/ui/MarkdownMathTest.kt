package pi.kit.mob.ui

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is a formula, and what is a dollar sign.
 *
 * The typesetting is the renderer's — `MathView.kt` hands a formula to JLaTeXMath
 * rather than drawing it here — so what is left to pin is the
 * decision the *transcript* makes: which span of a reply is mathematics, and how a
 * formula participates in the inline runs around it.
 *
 * That decision has one failure mode with real consequences and one without. The
 * real one is a price: "it costs $5 and $10 today" must not become the formula
 * `5 and `. The other is a formula the renderer cannot measure, which must fall
 * back to its own source text rather than a placeholder nothing can fill — a
 * placeholder naming content the map does not hold is a crash in `Text`, not a
 * blank space.
 */
class MarkdownMathTest {

    private val style = InlineStyle(linkColor = Color.Black)

    // ------------------------------------------------------- what the parser sees

    private fun blocks(markdown: String) = parseMarkdown(markdown)

    @Test
    fun `a formula on its own lines is one block`() {
        val block = blocks("$$\nx^2 + y^2\n$$").single() as MdBlock.Formula
        assertEquals("x^2 + y^2", block.body)
    }

    @Test
    fun `a formula fenced on one line is one block`() {
        assertEquals("x^2", (blocks("\$\$x^2\$\$").single() as MdBlock.Formula).body)
        assertEquals("x^2", (blocks("\\[x^2\\]").single() as MdBlock.Formula).body)
    }

    @Test
    fun `an environment is kept whole rather than split into rows`() {
        // The rows are *not* separated here: `cases`, `aligned`, `gather` and the
        // matrix environments are all supported by the renderer, which needs the
        // environment intact to know what to do with an `&` and a `\\`. Splitting
        // them in the parser would hand it N unrelated one-line formulas instead of
        // one two-row `cases`, which is a different picture.
        val block = blocks("\\begin{cases}\nx = 1 & y = 2 \\\\\nz = 3\n\\end{cases}")
            .single() as MdBlock.Formula
        assertEquals("x = 1 & y = 2 \\\\\nz = 3", block.body)
    }

    @Test
    fun `an unclosed formula takes the rest rather than being dropped`() {
        val result = blocks("\$\$x^2\n+ 1")
        val formula = result.single() as MdBlock.Formula
        assertEquals("x^2\n+ 1", formula.body)
    }

    @Test
    fun `a formula is not read as a table`() {
        // A `\left| … \right|` line above a rule is a formula, not a header row.
        val result = blocks("$$\\left| x \\right|$$\n\n| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertTrue(result.first() is MdBlock.Formula)
        assertTrue(result.last() is MdBlock.Table)
    }

    // ------------------------------------------------- what the transcript calls math

    @Test
    fun `a price in prose is not a formula`() {
        // The one with consequences: the dollar signs have to survive.
        assertFalse(containsInlineMath("It costs $5 and $10 today."))
        assertFalse(containsInlineMath("$5"))
        assertFalse(containsInlineMath("Priced at $9.99."))
        // And an escaped one is deliberately a literal.
        assertFalse(containsInlineMath("It costs \\$5."))
    }

    @Test
    fun `both spellings of an inline formula are found`() {
        assertTrue(containsInlineMath("The area is $\\pi r^2$ exactly."))
        assertTrue(containsInlineMath("The area is \\(\\pi r^2\\) exactly."))
        assertTrue(containsInlineMath("\$\$x^2\$\$ written inside a sentence"))
    }

    @Test
    fun `only the formula is handed to the renderer, and without its delimiters`() {
        val measured = mutableListOf<String>()
        renderInline(
            text = "The area is \$\\pi r^2\$ exactly.",
            style = style,
            method = { body, _ ->
                measured += body
                stubContent()
            },
        )

        assertEquals("only the formula is handed to the renderer", listOf("\\pi r^2"), measured)
    }

    @Test
    fun `a formula in the middle of a sentence is one placeholder`() {
        val rendered = renderInline(
            text = "The area is \$\\pi r^2\$ exactly.",
            style = style,
            method = { _, _ -> stubContent() },
        )

        assertEquals("one placeholder", 1, rendered.inlineContent.size)
        assertEquals(
            "with the prose kept either side of it, and the source where the formula is",
            "The area is \$\\pi r^2\$ exactly.",
            rendered.text.text,
        )
    }

    @Test
    fun `a formula the renderer refuses falls back to its own source`() {
        // The renderer returns null for an empty or unmeasurable formula. Appending
        // the placeholder anyway would throw inside `Text`, so the walk has to put
        // the source back — a reader seeing `$x$` is a rendering gap, not a crash.
        val rendered = renderInline(
            text = "before \$x\$ after",
            style = style,
            method = { _, _ -> null },
        )

        assertTrue("nothing was declared", rendered.inlineContent.isEmpty())
        assertEquals("and the source text is back", "before \$x\$ after", rendered.text.text)
    }

    @Test
    fun `prose with no formula never declares inline content`() {
        val rendered = renderInline(
            text = "**bold** and `code`, no mathematics.",
            style = style,
            method = { _, _ -> error("the renderer must not be asked for a formula that is not there") },
        )

        assertTrue(rendered.inlineContent.isEmpty())
    }

    /**
     * The limit that used to be here, now fixed.
     *
     * A formula used to be cut out of the fragment *before* the emphasis parser ran,
     * so `**b $x$ c**` reached the parser as `**b ` and ` c**`, neither of which
     * holds a pair of delimiters — and CommonMark does not open an emphasis without
     * one, so the asterisks were drawn literally. The tokeniser now holds both
     * grammars in one pass, so the emphasis run is open across the formula and the
     * formula is inline content inside a bold span.
     *
     * This is the test that replaces the one asserting the old limit.
     */
    @Test
    fun `an emphasis that wraps a formula keeps the formula inside the bold run`() {
        val rendered = renderInline(
            text = "a **b \$x\$ c** d",
            style = style,
            method = { _, _ -> stubContent() },
        )

        assertEquals("one formula", 1, rendered.inlineContent.size)
        assertEquals("the delimiters are gone", "a b \$x\$ c d", rendered.text.text)
        val bold = rendered.text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals("one bold span", 1, bold.size)
        assertEquals("starting at `b`", 2, bold.single().start)
        assertEquals("and ending after `c`", 9, bold.single().end)
    }

    @Test
    fun `an emphasis that does not wrap a formula is unaffected`() {
        val rendered = renderInline(
            text = "a **b** \$x\$ c",
            style = style,
            method = { _, _ -> stubContent() },
        )

        assertEquals("a b \$x\$ c", rendered.text.text)
        assertEquals(1, rendered.inlineContent.size)
        assertTrue(
            "the bold run is still a bold run",
            rendered.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold },
        )
    }

    @Test
    fun `an inline formula inside a table cell is measured like any other`() {
        // The report this covers: "表格等内容里的公式为什么不会渲染".
        val table = parseMarkdown(
            "| a | b |\n" +
                "| --- | --- |\n" +
                "| \$x^2\$ | \$\\frac{1}{2}\$ |",
        ).single() as MdBlock.Table
        val measured = mutableListOf<String>()
        renderInline(
            text = table.rows.single()[0],
            style = style,
            method = { body, _ ->
                measured += body
                stubContent()
            },
        )
        renderInline(
            text = table.rows.single()[1],
            style = style,
            method = { body, _ ->
                measured += body
                stubContent()
            },
        )

        assertEquals(listOf("x^2", "\\frac{1}{2}"), measured)
    }

    @Test
    fun `a display formula written inside a sentence is still a formula`() {
        // "由 \[ E = mc^{2} \] 可知" — a `\[…\]` that is not alone on its line. It used
        // to be drawn with its brackets and backslashes, which is half of the report
        // "某些情况没渲染": nothing about it looks wrong, it just never became mathematics.
        val bodies = mutableListOf<String>()
        val rendered = renderInline(
            text = "由 \\[ E = mc^{2} \\] 可知",
            style = style,
            method = { body, _ ->
                bodies += body
                stubContent()
            },
        )

        assertEquals("the renderer is handed the body, not the brackets", listOf("E = mc^{2}"), bodies)
        assertEquals("one formula", 1, rendered.inlineContent.size)
    }

    @Test
    fun `an unmatched display bracket is left as the escaped character it is`() {
        val rendered = renderInline(
            text = "见 \\[ 未闭合",
            style = style,
            method = { _, _ -> stubContent() },
        )

        assertTrue("no formula", rendered.inlineContent.isEmpty())
        assertEquals("CommonMark's escape rule, not a formula", "见 [ 未闭合", rendered.text.text)
    }

    @Test
    fun `a backslash bracket that is escaped is not a formula`() {
        val rendered = renderInline(
            text = "a \\\\[b\\\\] c",
            style = style,
            method = { _, _ -> stubContent() },
        )

        assertTrue("no formula", rendered.inlineContent.isEmpty())
    }

    @Test
    fun `a formula padded with spaces is mathematics when it looks like mathematics`() {
        // `$ x^{2} $` is how TeX is written and how a model writes it when it is thinking
        // about a paper rather than about Markdown. It used to be rejected outright by the
        // price rule — which is the report "这种换行的仍然没渲染", a formula drawn as its own
        // source, wrapped like prose, with nothing about it saying "this should have been a
        // formula".
        listOf("\$ x^{2} \$", "\$ \\frac{1}{2} \$", "\$ a_i \$", "\$ P(A) = 1 \$").forEach { source ->
            assertEquals(
                source,
                1,
                renderInline(text = "a $source b", style = style, method = { _, _ -> stubContent() })
                    .inlineContent.size,
            )
        }
    }

    @Test
    fun `a padded price is still a price`() {
        // The other side of that rule, and the reason it is "looks like mathematics" rather
        // than "is padded": a body with no command, script or group in it is a price, whatever
        // the spaces around it.
        listOf(
            "it costs \$ 5 and \$ 10 today",
            "a \$ 5 \$ b",
            "from \$ 10 to \$ 20",
        ).forEach { source ->
            assertTrue(
                source,
                renderInline(text = source, style = style, method = { _, _ -> stubContent() })
                    .inlineContent.isEmpty(),
            )
        }
    }

    @Test
    fun `a formula written without delimiters is left as the text it is`() {
        // The other side of the line the previous test draws: `\frac{1}{2}` on its
        // own is *not* mathematics here. A model that writes one is writing it as
        // literal text, and treating any backslash-command as mathematics would turn
        // a Windows path (`C:\Users`) into a formula.
        assertEquals(
            listOf(InlineToken.Text("\\frac{1}{2}")),
            tokenizeInline("\\frac{1}{2}"),
        )
    }

    // ------------------------------------------------------------ the inline runs

    @Test
    fun `an emphasis that wraps a formula can also be a strikethrough`() {
        val rendered = renderInline(
            text = "~~a \$x\$ b~~",
            style = style,
            method = { _, _ -> stubContent() },
        )
        assertTrue(
            rendered.text.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough },
        )
    }

    @Test
    fun `an inline formula inside a link label is still measured`() {
        val rendered = renderInline(
            text = "[\$x\$](https://example.com)",
            style = style,
            method = { _, _ -> stubContent() },
        )
        assertEquals(1, rendered.inlineContent.size)
        assertTrue(
            "and the link is a link",
            rendered.text.getLinkAnnotations(0, rendered.text.length).isNotEmpty(),
        )
    }

    private fun stubContent(): InlineTextContent = InlineTextContent(
        Placeholder(
            width = 1.sp,
            height = 1.sp,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {}
}
