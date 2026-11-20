package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The chat prompt's one rule: a template is stored and an instant is rendered.
 *
 * The placeholder is the whole editor contract — the page shows `{{time}}`, not a
 * timestamp — so this pins both halves: every occurrence is replaced, and a template
 * without the token is left exactly as the reader wrote it.
 */
class AgentModeTest {

    private val now = Instant.parse("2026-11-17T13:00:38Z")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `renders the placeholder in the device's zone`() {
        assertEquals(
            "Current time: 2026-11-17 21:00:38 +08:00",
            renderChatSystemPrompt("Current time: {{time}}", now, shanghai),
        )
    }

    @Test
    fun `replaces every occurrence and leaves the rest of the document alone`() {
        assertEquals(
            "At 2026-11-17 21:00:38 +08:00, then again 2026-11-17 21:00:38 +08:00.",
            renderChatSystemPrompt(
                "At {{time}}, then again {{time}}.",
                now,
                shanghai,
            ),
        )
        assertEquals("No token here.", renderChatSystemPrompt("No token here.", now, shanghai))
    }

    @Test
    fun `the default is the current time token`() {
        assertEquals(CHAT_TIME_PLACEHOLDER, DEFAULT_CHAT_SYSTEM_PROMPT)
        assertEquals(
            "2026-11-17 21:00:38 +08:00",
            renderChatSystemPrompt(DEFAULT_CHAT_SYSTEM_PROMPT, now, shanghai),
        )
    }

    @Test
    fun `an unknown mode reads as work`() {
        assertEquals(AgentMode.WORK, AgentMode.fromCode(null))
        assertEquals(AgentMode.WORK, AgentMode.fromCode("later-mode"))
        assertEquals(AgentMode.CHAT, AgentMode.fromCode("chat"))
    }

    @Test
    fun `a new chat conversation's web default follows the chosen rule`() {
        assertEquals(false, ChatWebAccessDefault.OFF.initialValue(lastChatWebAccess = true))
        assertEquals(true, ChatWebAccessDefault.ON.initialValue(lastChatWebAccess = false))
        assertEquals(true, ChatWebAccessDefault.LAST.initialValue(lastChatWebAccess = true))
        assertEquals(false, ChatWebAccessDefault.LAST.initialValue(lastChatWebAccess = false))
        assertEquals(ChatWebAccessDefault.OFF, ChatWebAccessDefault.fromCode(null))
        assertEquals(ChatWebAccessDefault.LAST, ChatWebAccessDefault.fromCode("last"))
    }
}
