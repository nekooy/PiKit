package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title request is one `pi --print` process, so the parts pinned here are the
 * prompt it is given, the one line its output becomes, and the rule that only a fresh
 * conversation's opening prompt can ask for a title.
 */
class TitleGeneratorTest {

    @Test
    fun `a title is one line with the markdown a model adds removed`() {
        assertEquals("Fix login redirects", normalizeTitle("**Fix login redirects**\nextra commentary"))
        assertEquals("Fix login redirects", normalizeTitle("Title: \"Fix login redirects\""))
        assertEquals("Fix login redirects", normalizeTitle("  Fix   login  redirects  "))
        assertEquals("修复登录跳转", normalizeTitle("「修复登录跳转」"))
        assertEquals("", normalizeTitle("\n\n"))
    }

    @Test
    fun `a title is capped to the drawer row`() {
        val long = "一".repeat(120)
        val title = normalizeTitle(long)
        assertTrue(title.length <= 40)
        assertTrue(title.toByteArray(Charsets.UTF_8).size <= 80)
        assertEquals("🧭".repeat(19) + "…", normalizeTitle("🧭".repeat(30)))
    }

    @Test
    fun `the system prompt asks for a bare one-line title in the messages' language`() {
        val prompt = titleSystemPrompt()
        assertTrue(prompt.contains("Return only the title on one line"))
        assertTrue(prompt.contains("Use the language of the messages"))
    }

    @Test
    fun `only the opening prompt of a fresh unnamed conversation can supply a title`() {
        val opening = ChatItem.User(key = "user-0", text = "检查模型路由", imageCount = 0)
        val firstTurn = ChatTurn(promptKey = opening.key, finalReplyKey = null, startedAt = 1)
        val first = ConversationState(sessionFile = "/fresh.jsonl", items = listOf(opening), turns = listOf(firstTurn))
        assertEquals("检查模型路由", firstPromptText(first, "/fresh.jsonl", enabled = true))
        // The prompt item is the fact; the turn record may not have reached the
        // reducer yet when the first user `message_end` is folded.
        assertEquals("检查模型路由", firstPromptText(first.copy(turns = emptyList()), "/fresh.jsonl", enabled = true))
        assertNull(firstPromptText(first, "/fresh.jsonl", enabled = false))
        assertNull(firstPromptText(first.copy(sessionName = "Manual"), "/fresh.jsonl", enabled = true))
        assertNull(firstPromptText(first.copy(sessionFile = "/history.jsonl"), "/fresh.jsonl", enabled = true))
        val followup = opening.copy(key = "user-1", text = "跟进一下")
        val later = first.copy(items = listOf(opening, followup), turns = listOf(firstTurn, firstTurn.copy(promptKey = followup.key)))
        assertNull(firstPromptText(later, "/fresh.jsonl", enabled = true))
    }

    @Test
    fun `attachment machinery is not part of the prompt used for a title`() {
        val attachments = "These files are ready; read them by path:\n/data/data/pi.kit.mob/files/attachments/a.txt\n\n"
        val note = "[Image: original 1200x2670, displayed at 900x2000]"
        val opening = ChatItem.User(key = "user-0", text = attachments + note, imageCount = 1)
        assertNull(firstPromptText(ConversationState(sessionFile = "/fresh.jsonl", items = listOf(opening)), "/fresh.jsonl", true))
        val withWords = opening.copy(text = attachments + note + "\nReview this example")
        assertEquals(
            "Review this example",
            firstPromptText(ConversationState(sessionFile = "/fresh.jsonl", items = listOf(withWords)), "/fresh.jsonl", true),
        )
    }
}
