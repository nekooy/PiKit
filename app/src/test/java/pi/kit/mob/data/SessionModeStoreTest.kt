package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * One conversation's mode is its own record.
 *
 * The default is the half that matters: every conversation written before chat mode
 * existed has no entry, and opening one of those while the app's last choice was Chat
 * must answer Work — that is the report the store exists for.
 */
class SessionModeStoreTest {

    private fun newStore(
        preferences: FakeSharedPreferences = FakeSharedPreferences(),
    ): SessionModeStore = SessionModeStore(FakeContext(preferences, tempDir()))

    @Test
    fun `a conversation with no record is work`() {
        val store = newStore()

        assertEquals(AgentMode.WORK, store.modeFor("/sessions/old.jsonl"))
    }

    @Test
    fun `each session keeps its own mode across a store rebuild`() {
        val preferences = FakeSharedPreferences()
        val first = newStore(preferences)
        first.setMode("/sessions/work.jsonl", AgentMode.WORK)
        first.setMode("/sessions/chat.jsonl", AgentMode.CHAT)

        val rebuilt = newStore(preferences)

        assertEquals(AgentMode.WORK, rebuilt.modeFor("/sessions/work.jsonl"))
        assertEquals(AgentMode.CHAT, rebuilt.modeFor("/sessions/chat.jsonl"))
        assertEquals(AgentMode.WORK, rebuilt.modeFor("/sessions/other.jsonl"))
    }

    @Test
    fun `a deleted conversation is forgotten`() {
        val preferences = FakeSharedPreferences()
        val store = newStore(preferences)
        store.setMode("/sessions/chat.jsonl", AgentMode.CHAT)

        store.forget("/sessions/chat.jsonl")

        assertEquals(AgentMode.WORK, store.modeFor("/sessions/chat.jsonl"))
        assertNull(preferences.getString("/sessions/chat.jsonl", null))
    }

    private fun tempDir() = Files.createTempDirectory("session-mode-test").toFile()
}
