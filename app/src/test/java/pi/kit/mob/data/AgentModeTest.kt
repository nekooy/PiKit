package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The pure rules `AgentMode.kt` carries: the chat prompt's template/instant split, the
 * stored-mode fallbacks, and the mode a conversation with no file starts in.
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
    fun `a new conversation follows the last mode unless the row pins it`() {
        assertEquals(AgentMode.WORK, NewConversationMode.FOLLOW_LAST.startingMode(AgentMode.WORK))
        assertEquals(AgentMode.CHAT, NewConversationMode.FOLLOW_LAST.startingMode(AgentMode.CHAT))
        // A pinned answer ignores the last conversation in both directions: that is the
        // whole of what the row is for, and the reason it is not a boolean.
        assertEquals(AgentMode.WORK, NewConversationMode.WORK.startingMode(AgentMode.CHAT))
        assertEquals(AgentMode.CHAT, NewConversationMode.CHAT.startingMode(AgentMode.WORK))
    }

    @Test
    fun `a fresh install's new conversation starts in work`() {
        // What the launch reads, on the settings an install that never opened the page has:
        // the default answer, resolved through the last-used field rather than bypassing it.
        assertEquals(AgentMode.WORK, PiSettings().startingMode)
        assertEquals(AgentMode.CHAT, PiSettings(agentMode = AgentMode.CHAT).startingMode)
        assertEquals(
            AgentMode.WORK,
            PiSettings(
                agentMode = AgentMode.CHAT,
                newConversationMode = NewConversationMode.WORK,
            ).startingMode,
        )
    }

    @Test
    fun `an unknown new-conversation choice reads as the default`() {
        // Absent is the default, and the default is the behaviour every install had, so a
        // preference file written before the row existed reads exactly as it always did.
        assertEquals(NewConversationMode.FOLLOW_LAST, NewConversationMode.fromCode(null))
        assertEquals(NewConversationMode.FOLLOW_LAST, NewConversationMode.fromCode("later-mode"))
        assertEquals(NewConversationMode.WORK, NewConversationMode.fromCode("work"))
        assertEquals(NewConversationMode.CHAT, NewConversationMode.fromCode("chat"))
    }
}
