package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the block parser, which is where Markdown renderers usually fail.
 *
 * The samples are taken from what the agent actually emits — pipe tables,
 * checklists and shell pipelines inside table cells are all common, and each of
 * them has a plausible way of being mis-parsed.
 *
 * A run of list items is **one** `ListBlock` rather than one block per item: the
 * gap between two items of one list is not the gap between two blocks, and only a
 * block that holds the whole run can draw the difference. The tests below read the
 * items out of it rather than off the top-level list.
 */
class MarkdownParserTest {

    private fun blocks(text: String) = parseMarkdown(text)

    /** The items of the single list block a sample produced. */
    private fun items(text: String) =
        blocks(text).single().let { it as MdBlock.ListBlock }.items

    @Test
    fun `parses a pipe table with a header and rows`() {
        val result = blocks(
            """
            | Name | Size |
            | --- | ---: |
            | a.txt | 12 |
            | b.txt | 345 |
            """.trimIndent(),
        )

        assertEquals(1, result.size)
        val table = result.single() as MdBlock.Table
        assertEquals(listOf("Name", "Size"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a.txt", "12"), table.rows[0])
        assertEquals(listOf("b.txt", "345"), table.rows[1])
        // `---` starts, `---:` ends: right aligned.
        assertEquals(listOf(MdAlign.Start, MdAlign.End), table.aligns)
    }

    @Test
    fun `parses a table without outer pipes`() {
        val result = blocks(
            """
            Name | Size
            :--- | :---:
            a | 1
            """.trimIndent(),
        )

        val table = result.single() as MdBlock.Table
        assertEquals(listOf("Name", "Size"), table.header)
        assertEquals(listOf("a", "1"), table.rows.single())
        assertEquals(listOf(MdAlign.Start, MdAlign.Center), table.aligns)
    }

    @Test
    fun `keeps an escaped pipe inside its cell`() {
        val result = blocks(
            """
            | Command | Note |
            | --- | --- |
            | `ls \| wc -l` | count lines |
            """.trimIndent(),
        )

        val table = result.single() as MdBlock.Table
        assertEquals("`ls | wc -l`", table.rows.single()[0])
        assertEquals("count lines", table.rows.single()[1])
    }

    @Test
    fun `a sentence containing a pipe is not a table`() {
        val result = blocks("Use the a | b operator\nand then stop.")
        assertTrue(result.single() is MdBlock.Paragraph)
    }

    @Test
    fun `a rule is not mistaken for a table delimiter`() {
        val result = blocks("Intro text\n\n---\n\nMore text")
        assertEquals(3, result.size)
        assertTrue(result[1] is MdBlock.Rule)
    }

    @Test
    fun `table stops at the first line without a pipe`() {
        val result = blocks(
            """
            | a | b |
            | --- | --- |
            | 1 | 2 |

            done
            """.trimIndent(),
        )

        val table = result.first() as MdBlock.Table
        assertEquals(1, table.rows.size)
        assertTrue(result.last() is MdBlock.Paragraph)
    }

    @Test
    fun `pipes inside a fenced code block are not a table`() {
        val result = blocks("```sh\nls | wc -l\n--- | ---\n```")
        val code = result.single() as MdBlock.Code
        assertEquals("sh", code.language)
        assertEquals("ls | wc -l\n--- | ---", code.body)
    }

    @Test
    fun `parses task list items and their state`() {
        val result = items("- [x] done\n- [ ] pending\n- plain")
        assertEquals(3, result.size)
        assertEquals(true, result[0].checked)
        assertEquals("done", result[0].text)
        assertEquals(false, result[1].checked)
        assertEquals(null, result[2].checked)
    }

    @Test
    fun `records list nesting depth`() {
        assertEquals(listOf(0, 1, 2), items("- top\n  - nested\n    - deeper").map { it.depth })
    }

    @Test
    fun `records depth and number for ordered lists`() {
        val list = blocks("1. first\n  2. nested").single() as MdBlock.ListBlock
        assertTrue(list.ordered)
        assertEquals(1, list.start)
        assertEquals(listOf(0, 1), list.items.map { it.depth })
    }

    @Test
    fun `an ordered list that starts at three keeps its numbers`() {
        val list = blocks("3. three\n4. four").single() as MdBlock.ListBlock
        assertEquals(3, list.start)
        assertEquals(listOf("three", "four"), list.items.map { it.text })
    }

    @Test
    fun `joins wrapped prose into one paragraph`() {
        val result = blocks("one line\nsecond line")
        assertEquals("one line second line", (result.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `closes a fence only on a matching marker`() {
        val result = blocks("```\n~~~\n```")
        assertEquals("~~~", (result.single() as MdBlock.Code).body)
    }

    @Test
    fun `splits rows tolerant of ragged columns`() {
        val result = blocks(
            """
            | a | b | c |
            | --- | --- | --- |
            | 1 |
            """.trimIndent(),
        )
        val table = result.single() as MdBlock.Table
        assertEquals(listOf("1"), table.rows.single())
    }

    @Test
    fun `a wrapped list item is one block, not a paragraph at the margin`() {
        val result = blocks(
            "2. Provide the key — each provider has its own,\n" +
                "   and the key is passed through the environment.",
        )

        val item = (result.single() as MdBlock.ListBlock).items.single()
        assertEquals(
            "Provide the key — each provider has its own, " +
                "and the key is passed through the environment.",
            item.text,
        )
    }

    @Test
    fun `a wrapped bullet continues its bullet`() {
        val parsed = items("- first line\n  second line\n- another item")

        assertEquals(2, parsed.size)
        assertEquals("first line second line", parsed[0].text)
        assertEquals("another item", parsed[1].text)
    }

    @Test
    fun `a paragraph after a blank line is not swallowed by the list`() {
        val result = blocks("- item\n\nA separate paragraph.")

        assertEquals(2, result.size)
        assertEquals("item", (result[0] as MdBlock.ListBlock).items.single().text)
        assertEquals("A separate paragraph.", (result[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `a wrapped task item keeps its checkbox`() {
        val item = items("- [x] done thing\n  with a note").single()
        assertEquals(true, item.checked)
        assertEquals("done thing with a note", item.text)
    }

    // ------------------------------------------------------------ setext headings

    @Test
    fun `a line underlined with equals is a first-level heading`() {
        val result = blocks("Title\n=====")
        assertEquals(MdBlock.Heading(1, "Title"), result.single())
    }

    @Test
    fun `a line underlined with dashes is a second-level heading`() {
        val result = blocks("Title\n---")
        assertEquals(MdBlock.Heading(2, "Title"), result.single())
    }

    @Test
    fun `a rule after a blank line stays a rule`() {
        // The setext rule must not swallow a `---` that follows a *finished*
        // paragraph: only the paragraph being accumulated right now can become a
        // heading.
        val result = blocks("Intro\n\n---\n\nMore")
        assertEquals(3, result.size)
        assertTrue(result[1] is MdBlock.Rule)
    }

    // ------------------------------------------------------------------- quotes

    @Test
    fun `a quoted list is parsed as a list`() {
        val quote = blocks("> - a\n> - b").single() as MdBlock.Quote
        val list = quote.blocks.single() as MdBlock.ListBlock
        assertEquals(listOf("a", "b"), list.items.map { it.text })
    }

    @Test
    fun `two quoted paragraphs are two blocks in one quote`() {
        val quote = blocks("> first\n>\n> second").single() as MdBlock.Quote
        assertEquals(
            listOf("first", "second"),
            quote.blocks.map { (it as MdBlock.Paragraph).text },
        )
    }

    @Test
    fun `a quote inside a quote nests`() {
        val outer = blocks("> > deep").single() as MdBlock.Quote
        val inner = outer.blocks.single() as MdBlock.Quote
        assertEquals("deep", (inner.blocks.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `a blank line between two quotes keeps them apart`() {
        val result = blocks("> a\n\nplain\n\n> b")
        assertTrue(result[0] is MdBlock.Quote)
        assertTrue(result[1] is MdBlock.Paragraph)
        assertTrue(result[2] is MdBlock.Quote)
    }

    // -------------------------------------------------------------- hard breaks

    @Test
    fun `two trailing spaces are a hard break`() {
        val result = blocks("line one  \nline two")
        assertEquals("line one\nline two", (result.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `a trailing backslash is a hard break`() {
        val result = blocks("line one\\\nline two")
        assertEquals("line one\nline two", (result.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `a soft break is still a space`() {
        val result = blocks("line one\nline two")
        assertEquals("line one line two", (result.single() as MdBlock.Paragraph).text)
    }

    // --------------------------------------------------------- reference links

    @Test
    fun `a link definition is lifted out of the stream`() {
        // The definition's own line is gone — it is metadata, not a paragraph — and
        // the reference that named it still resolves.
        val result = blocks("[docs]: https://example.com \"The docs\"\n\nSee [docs].")
        assertEquals(1, result.size)
        assertEquals("See [docs](https://example.com).", (result.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `a reference link is rewritten to the inline spelling it means`() {
        // Lifted *and applied*: the definition's line goes away, and every spelling
        // of the reference below it becomes an inline link. Before this, the
        // definition was removed and the url was thrown away with it, so `[docs]`
        // stayed a pair of brackets.
        val paragraph = blocks(
            "[a]: https://one.example\n" +
                "[b]: https://two.example\n" +
                "\n" +
                "Bare [a], collapsed [b][], and labelled [the docs][a].",
        ).single() as MdBlock.Paragraph

        assertEquals(
            "Bare [a](https://one.example), collapsed [b](https://two.example), " +
                "and labelled [the docs](https://one.example).",
            paragraph.text,
        )
    }

    @Test
    fun `a bracket with no definition is left as a bracket`() {
        val paragraph = blocks("[1] is a citation, and [see][nowhere] is not a link."
        ).single() as MdBlock.Paragraph
        assertEquals("[1] is a citation, and [see][nowhere] is not a link.", paragraph.text)
    }

    @Test
    fun `a reference inside a table cell is resolved too`() {
        val table = blocks(
            "[a]: https://one.example\n" +
                "\n" +
                "| ref | note |\n" +
                "| --- | --- |\n" +
                "| [a] | see it |",
        ).single() as MdBlock.Table
        assertEquals("[a](https://one.example)", table.rows.single()[0])
    }

    @Test
    fun `a footnote definition is dropped`() {
        val result = blocks("Text[^1].\n\n[^1]: the note")
        assertEquals(1, result.size)
        assertEquals("Text[^1].", (result.single() as MdBlock.Paragraph).text)
    }

    // ---------------------------------------------------------------- regression

    @Test
    fun `a fence with a trailing language and attributes is still a fence`() {
        val code = blocks("```kotlin title=Main.kt\nval x = 1\n```").single() as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.body)
    }

    @Test
    fun `a list of ten items is one block with ten items`() {
        val list = blocks((1..10).joinToString("\n") { "$it. item $it" }).single() as MdBlock.ListBlock
        assertEquals(10, list.items.size)
    }
}
