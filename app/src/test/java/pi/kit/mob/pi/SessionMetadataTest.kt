package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session list's labels come from parsing pi's own JSONL, so these samples
 * are shaped like real session files: a header line, messages whose content is a
 * block array, and a naming record appended at the end.
 */
class SessionMetadataTest {

    private val header =
        """{"type":"session","version":3,"id":"0198f2c1","timestamp":"2026-01-15T10:00:00.000Z","cwd":"/home"}"""

    private fun message(role: String, content: String, id: String = "a1b2c3d4") =
        """{"type":"message","id":"$id","parentId":null,"timestamp":"2026-01-15T10:00:01.000Z",""" +
            """"message":{"role":"$role","content":$content,"timestamp":1768471201000}}"""

    private fun textBlock(text: String) = """[{"type":"text","text":"$text"}]"""

    private fun read(vararg lines: String) = SessionMetadata.read(lines.asSequence())

    private fun search(vararg lines: String) = SessionMetadata.searchText(lines.asSequence())

    @Test
    fun `uses the first user message as the title when unnamed`() {
        val meta = read(
            header,
            message("user", textBlock("Refactor the auth module")),
            message("assistant", textBlock("Sure.")),
        )

        assertNull(meta.name)
        assertEquals("Refactor the auth module", meta.firstMessage)
        assertEquals(2, meta.messageCount)
    }

    @Test
    fun `prefers an explicit name over the first message`() {
        val meta = read(
            header,
            message("user", textBlock("hello there")),
            """{"type":"session_info","id":"k1l2m3n4","parentId":"a1b2c3d4","timestamp":"2026-01-15T10:00:05.000Z","name":"Auth work"}""",
        )

        assertEquals("Auth work", meta.name)
        assertEquals("hello there", meta.firstMessage)
    }

    @Test
    fun `the last naming record wins, which is how pi reads it back`() {
        val meta = read(
            header,
            """{"type":"session_info","id":"aaaaaaaa","name":"first name"}""",
            """{"type":"session_info","id":"bbbbbbbb","name":"second name"}""",
        )

        assertEquals("second name", meta.name)
    }

    @Test
    fun `a blank name clears the name rather than emptying the title`() {
        val meta = read(
            header,
            """{"type":"session_info","id":"aaaaaaaa","name":"named"}""",
            """{"type":"session_info","id":"bbbbbbbb","name":"   "}""",
            message("user", textBlock("fallback text")),
        )

        assertNull(meta.name)
        assertEquals("fallback text", meta.firstMessage)
    }

    @Test
    fun `reads content written as a plain string, as older files do`() {
        val meta = read(header, message("user", "\"plain string prompt\""))

        assertEquals("plain string prompt", meta.firstMessage)
    }

    @Test
    fun `ignores assistant turns when looking for the first message`() {
        val meta = read(
            header,
            message("assistant", textBlock("I spoke first")),
            message("user", textBlock("the actual question")),
        )

        assertEquals("the actual question", meta.firstMessage)
        assertEquals(2, meta.messageCount)
    }

    @Test
    fun `counts messages and tolerates non-message records`() {
        val meta = read(
            header,
            """{"type":"model_change","id":"cccccccc","model":"deepseek-v4-pro"}""",
            """{"type":"thinking_level_change","id":"dddddddd","level":"high"}""",
            message("user", textBlock("one")),
            message("assistant", textBlock("two")),
            message("user", textBlock("three")),
        )

        assertEquals(3, meta.messageCount)
        assertEquals("one", meta.firstMessage)
    }

    @Test
    fun `skips a corrupt line instead of failing the whole listing`() {
        val meta = read(
            header,
            "{not json at all",
            message("user", textBlock("survives")),
        )

        assertEquals("survives", meta.firstMessage)
    }

    @Test
    fun `blank first message is not used as a title`() {
        val meta = read(header, message("user", textBlock("   ")))

        assertNull(meta.firstMessage)
    }

    @Test
    fun `title keeps only the first line of a multi-line prompt`() {
        assertEquals(
            "Fix the parser",
            SessionMetadata.asTitle("Fix the parser\n\nIt fails on pipes."),
        )
    }

    @Test
    fun `title is truncated with an ellipsis at the limit`() {
        val title = SessionMetadata.asTitle("x".repeat(200), maxChars = 80)

        assertEquals(80, title.length)
        assertEquals('\u2026', title.last())
    }

    // -------------------------------------------------------- content search

    /**
     * The bug this pins: the search field filtered on the title, which for an
     * unnamed session is the first user message — so a conversation whose *answer*
     * mentioned something could not be found by it at all.
     */
    @Test
    fun `content search finds a needle that appears only in an assistant reply`() {
        val text = search(
            header,
            message("user", textBlock("why did the upload fail?")),
            message("assistant", textBlock("The retry budget in BackoffPolicy was exhausted.")),
        )

        assertTrue("the reply's text should be searchable, got: $text", "BackoffPolicy" in text)
    }

    /**
     * A tool result is a file listing or a command's output, and indexing it would
     * answer "which conversation mentioned package.json" with every conversation
     * that ever ran `ls`.
     */
    @Test
    fun `content search skips tool results and records that are not messages`() {
        val text = search(
            header,
            """{"type":"model_change","id":"cccccccc","model":"deepseek-v4-pro"}""",
            message("user", textBlock("list the files")),
            message("toolResult", textBlock("a.txt b.txt package.json")),
            message("assistant", textBlock("There are two files.")),
        )

        assertTrue("the prompt should be searched, got: $text", "list the files" in text)
        assertTrue("the reply should be searched, got: $text", "two files" in text)
        assertFalse("a tool result is not conversation text", "package.json" in text)
        assertFalse("a model_change is not a message", "deepseek-v4-pro" in text)
    }

    @Test
    fun `content search reads content written as a plain string`() {
        val text = search(header, message("user", "\"plain string prompt\""))

        assertEquals("plain string prompt", text)
    }

    /**
     * pi echoes an attached image into the transcript as base64, and one screenshot
     * is megabytes. Only `type == "text"` blocks are read, which is what keeps that
     * out of the in-memory index.
     */
    @Test
    fun `content search skips an image block so base64 never reaches the index`() {
        val base64 = "iVBORw0KGgoAAAANSUhEUg".repeat(40)
        val content = """[{"type":"text","text":"look at this"},""" +
            """{"type":"image","data":"$base64","mimeType":"image/png"}]"""

        val text = search(header, message("user", content))

        assertEquals("look at this", text)
        assertFalse("image bytes must not reach the index", base64 in text)
    }

    @Test
    fun `snippet is null when the text does not contain the needle`() {
        assertNull(SessionMetadata.snippet("a conversation about parsers", "kubernetes"))
    }

    @Test
    fun `snippet returns the text around the match and ignores case`() {
        val text = "The quick brown fox jumps over the lazy dog."

        val snippet = SessionMetadata.snippet(text, "BROWN FOX")

        assertNotNull(snippet)
        // The user's own capitalisation: the match is case-insensitive but the
        // text shown is not rewritten.
        assertTrue("the match should be in the window, got: $snippet", "brown fox" in snippet!!)
        assertTrue("the words before the match should lead into it", snippet.contains("The quick"))
        assertFalse("a window that fits needs no ellipsis", snippet.contains('\u2026'))
    }

    @Test
    fun `snippet collapses a paragraph into one bounded line`() {
        val paragraph = "x".repeat(400) +
            "\n\n  second   line with the needle in it  \n" +
            "y".repeat(400)

        val snippet = SessionMetadata.snippet(paragraph, "needle", maxChars = 120)

        assertNotNull(snippet)
        assertFalse("a newline must not reach a one-line row", snippet!!.contains('\n'))
        // The window is `maxChars` wide and carries at most one ellipsis at each
        // end, so this is the ceiling the row can ever be asked to draw.
        assertTrue("expected a bounded line, got ${snippet.length}", snippet.length <= 120 + 2)
    }
}
