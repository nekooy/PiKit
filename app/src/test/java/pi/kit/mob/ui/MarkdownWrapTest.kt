package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.manualFor

/**
 * The two ways a wrapped body was rendered wrong, and the manual that showed both.
 *
 * Both bugs were in the wrapper's own bookkeeping rather than in any one feature,
 * and both were visible in this app's manual before they were found by dumping the
 * parse of it:
 *
 *  - a paragraph that followed a list had every line after its first appended to
 *    the *last list item*, because the item was still the last block;
 *  - every soft line break inside a CJK paragraph came back as a space, so the
 *    Chinese and Japanese bodies read as if a space had been typed inside a word.
 *
 * The fixtures are the smallest text that reproduces each, and the manual check at
 * the end is the one that would have caught both.
 */
class MarkdownWrapTest {

    private fun text(block: MdBlock): List<String> = when (block) {
        is MdBlock.Paragraph -> listOf(block.text)
        is MdBlock.Heading -> listOf(block.text)
        is MdBlock.ListBlock -> block.items.map { it.text }
        is MdBlock.Quote -> block.blocks.flatMap(::text)
        else -> error("not a text block: $block")
    }

    /** Every piece of prose in [block], tables flattened and code/formulas skipped. */
    private fun prose(block: MdBlock): List<String> = when (block) {
        is MdBlock.Table -> block.header + block.rows.flatten()
        is MdBlock.Code, is MdBlock.Formula, MdBlock.Rule -> emptyList()
        else -> text(block)
    }

    /** One block's single piece of prose, which is what most of these fixtures make. */
    private fun one(block: MdBlock): String = text(block).single()

    @Test
    fun `a paragraph after a list is not appended to the last item`() {
        val blocks = parseMarkdown(
            """
            1. Paste the key.
            2. Press Save.

            The first launch unpacks about 200 MB
            and asks once about file access.
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        // One list of two items, and the whole paragraph after it — which is what
        // the parser used to produce wrong: item 2 grew "and asks once about file
        // access." and the paragraph kept only its first line.
        assertEquals(listOf("Paste the key.", "Press Save."), text(blocks[0]))
        assertEquals(
            "The first launch unpacks about 200 MB and asks once about file access.",
            one(blocks[1]),
        )
    }

    @Test
    fun `a soft break inside CJK text adds no space`() {
        // The manual is wrapped to 48 columns, so this is the common case rather
        // than an edge: `重 新授权` was in the shipped Chinese body.
        val parsed = parseMarkdown("首次启动会解包约 200 MB，并询问\n一次文件权限。\n")
        assertEquals("首次启动会解包约 200 MB，并询问一次文件权限。", one(parsed.single()))
    }

    @Test
    fun `a soft break keeps a real word space`() {
        // The other side of the rule: `用这个 Key` is two words with a space, and a
        // break there must not run them together.
        val parsed = parseMarkdown("用这个\nKey 就不一样了。\n")
        assertEquals("用这个 Key 就不一样了。", one(parsed.single()))
    }

    @Test
    fun `a break beside an emphasis delimiter adds no space either side`() {
        // Both spellings came out of the wrap: one ends a bold run, the other opens
        // one, and a space in either place draws a gap inside the emphasis (the
        // renderer trims that one) or between the bold word and the full stop.
        val closing = parseMarkdown("想改时到 **设置 → 手机存储**\n重新授权。\n")
        assertEquals("想改时到 **设置 → 手机存储**重新授权。", one(closing.single()))

        val opening = parseMarkdown("询问一次文件权限。**\n拒绝也没关系**，想改时再说。\n")
        assertEquals("询问一次文件权限。**拒绝也没关系**，想改时再说。", one(opening.single()))
    }

    @Test
    fun `a break beside CJK punctuation adds no space`() {
        val parsed = parseMarkdown("`ESC`、`TAB`、\n`C-C` 和几个符号。\n")
        assertEquals("`ESC`、`TAB`、`C-C` 和几个符号。", one(parsed.single()))
    }

    /**
     * The check over the real bodies, in all three languages.
     *
     * A space with a CJK glyph on both sides is never written deliberately — those
     * scripts do not use word spaces — so the parsed manual must not contain one.
     * This is the assertion the two bugs above fail, and it covers every paragraph
     * the manual has rather than the six examples beside it.
     */
    @Test
    fun `no parsed manual block has a space inside a CJK run`() {
        for (lang in Lang.entries) {
            val offending = parseMarkdown(manualFor(lang))
                .flatMap(::prose)
                .filter { SPLIT_CJK.containsMatchIn(it) }
            assertTrue("$lang: $offending", offending.isEmpty())
        }
    }

    /** A CJK glyph, a space, another: the artefact this test exists for. */
    private val SPLIT_CJK = Regex(
        "[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef] [\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]",
    )

    @Test
    fun `the manual's start-here steps are a table`() {
        // A numbered list reads as a sequence the user must tick off; the manual's
        // five steps are one decision made in five fields, which is what a table
        // says and a list does not.
        val chinese = parseMarkdown(manualFor(Lang.CHINESE))
        val tables = chinese.filterIsInstance<MdBlock.Table>()
        assertTrue(
            "the start-here steps are the manual's first table",
            tables.first().header == listOf("步骤", "做什么") && tables.first().rows.size == 5,
        )
        assertFalse(
            "and they are no longer a numbered list",
            chinese.any { it is MdBlock.ListBlock && it.ordered },
        )
    }
}
