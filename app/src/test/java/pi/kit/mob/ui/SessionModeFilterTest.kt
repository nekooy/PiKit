package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import pi.kit.mob.data.AgentMode
import pi.kit.mob.pi.PiAgentSession.SessionSummary

/**
 * Which conversations the drawer draws: one mode's, and the file's record is what decides.
 *
 * Two failures are worth a test rather than a careful reading. The first is a list that
 * shows the other side's conversations — a work conversation opened from a chat screen,
 * or the chat history in a work drawer. The second is the one that would be silent: a
 * conversation whose file carries no mode is **work** (`SessionModeStore`'s fallback), so
 * an install from before chat mode existed must keep its whole history in the work list
 * rather than watch it disappear from the only list it can reach.
 */
class SessionModeFilterTest {

    private fun summary(path: String): SessionSummary = SessionSummary(
        path = path,
        title = path,
        hasExplicitName = false,
        messageCount = 1,
        modifiedAt = 0,
        pinned = false,
    )

    /** The recorded modes; anything else is a file with no record. */
    private val modes = mapOf(
        "/home/chat-1.jsonl" to AgentMode.CHAT,
        "/home/chat-2.jsonl" to AgentMode.CHAT,
    )

    private fun listed(paths: List<String>, mode: AgentMode): List<String> =
        sessionsInMode(
            sessions = paths.map(::summary),
            mode = mode,
            // The store's own rule, spelled here because a path with no record is the
            // case this test is about.
            modeOf = { path -> modes[path] ?: AgentMode.WORK },
        ).map { it.path }

    @Test
    fun `the work list keeps the files with no record and drops the chat ones`() {
        assertEquals(
            listOf("/home/old-1.jsonl", "/home/old-2.jsonl"),
            listed(
                listOf("/home/old-1.jsonl", "/home/chat-1.jsonl", "/home/old-2.jsonl"),
                AgentMode.WORK,
            ),
        )
    }

    @Test
    fun `the chat list is the chat files, in the scan's own order`() {
        assertEquals(
            listOf("/home/chat-2.jsonl", "/home/chat-1.jsonl"),
            listed(
                listOf("/home/chat-2.jsonl", "/home/old-1.jsonl", "/home/chat-1.jsonl"),
                AgentMode.CHAT,
            ),
        )
    }

    @Test
    fun `a mode with nothing in it lists nothing`() {
        // The empty state, not an empty file: the drawer's list is empty while the other
        // side may hold a dozen, which is what `emptyModeHeading` says rather than "no
        // saved conversations yet".
        assertEquals(emptyList<String>(), listed(listOf("/home/chat-1.jsonl"), AgentMode.WORK))
    }
}
