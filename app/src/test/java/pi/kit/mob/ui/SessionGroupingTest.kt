package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.AgentMode
import pi.kit.mob.data.Workspace
import pi.kit.mob.pi.PiAgentSession.SessionSummary

/**
 * How the history page groups conversations.
 *
 * Work mode groups by the workspace each conversation came from; the grouping key is the
 * `cwd` pi wrote into the session file, which is the only place a saved conversation says
 * where it was started — so these cases are about what happens when that answer is
 * missing, stale, or names a folder the workspace list no longer has. That last one is the
 * case that would silently lose a conversation: a history page that only drew
 * conversations from *current* workspaces would drop every conversation from a project the
 * reader has since removed. Chat mode is one section under the mode's own name, because it
 * has no workspace to name — a converted chat conversation's stale `cwd` must not bring one
 * back.
 */
class SessionGroupingTest {

    private val home = "/data/data/pi.kit.mob/files/home"
    private val builtIn = "$home/workspace"
    private val added = "$home/projects/app"

    private fun summary(
        path: String,
        workspace: String?,
        modifiedAt: Long = 0,
    ): SessionSummary = SessionSummary(
        path = path,
        title = path,
        hasExplicitName = false,
        messageCount = 1,
        modifiedAt = modifiedAt,
        pinned = false,
        workspace = workspace,
    )

    /** The label the menu and the headings share, so the two cannot disagree. */
    private fun chip(name: String, isDefault: Boolean): String =
        if (isDefault) "$name（默认）" else name

    private val workspaces = listOf(
        Workspace(path = builtIn, label = "workspace", builtIn = true),
        Workspace(path = added, label = "app"),
    )

    private fun groups(sessions: List<SessionSummary>) = sessionGroups(
        sessions = sessions,
        workspaces = workspaces,
        defaultPath = builtIn,
        unknownLabel = "No workspace recorded",
        chip = ::chip,
    )

    private val now = 1_700_000_000_000L

    private fun history(
        mode: AgentMode,
        sessions: List<SessionSummary>,
        clock: Long = now,
    ) = historyGroups(
        mode = mode,
        sessions = sessions,
        workspaces = workspaces,
        defaultPath = builtIn,
        unknownLabel = "No workspace recorded",
        chatLabels = ChatGroupLabels(
            today = "Today",
            yesterday = "Yesterday",
            week = "Previous 7 days",
            month = "Previous 30 days",
        ),
        chip = ::chip,
        now = clock,
    )

    @Test
    fun `one group per workspace, named as the menu names it`() {
        val grouped = groups(
            listOf(
                summary("$home/a.jsonl", added, modifiedAt = 10),
                summary("$home/b.jsonl", builtIn, modifiedAt = 20),
                summary("$home/c.jsonl", added, modifiedAt = 30),
            ),
        )

        // The list's label, `（默认）` included: the headings are a list. The composer's
        // chip is the one place that says the folder's own name alone.
        assertEquals(listOf("app", "workspace（默认）"), grouped.map { it.label })
        assertEquals(listOf(2, 1), grouped.map { it.sessions.size })
    }

    @Test
    fun `the group holding the newest conversation comes first`() {
        val grouped = groups(
            listOf(
                summary("$home/a.jsonl", added, modifiedAt = 10),
                summary("$home/b.jsonl", builtIn, modifiedAt = 20),
            ),
        )

        assertEquals(listOf(builtIn, added), grouped.map { it.key })
    }

    @Test
    fun `a workspace that left the list still gets its own group`() {
        val gone = "$home/old-project"
        val grouped = groups(listOf(summary("$home/a.jsonl", gone, modifiedAt = 99)))

        // Named by the folder's last segment: the reader recognises the project even
        // though the list this install knows has forgotten it.
        assertEquals(listOf("old-project"), grouped.map { it.label })
        assertEquals(listOf(gone), grouped.map { it.key })
    }

    @Test
    fun `a conversation with no recorded directory is the last group`() {
        val grouped = groups(
            listOf(
                summary("$home/a.jsonl", null, modifiedAt = 99),
                summary("$home/b.jsonl", builtIn, modifiedAt = 1),
            ),
        )

        assertEquals(listOf(builtIn, "unknown"), grouped.map { it.key })
        assertEquals("No workspace recorded", grouped.last().label)
    }

    @Test
    fun `an empty list has no groups`() {
        assertEquals(emptyList<Any>(), groups(emptyList()))
    }

    @Test
    fun `work history keeps one section per workspace`() {
        val sections = history(
            AgentMode.WORK,
            listOf(
                summary("$home/a.jsonl", added, modifiedAt = 10),
                summary("$home/b.jsonl", builtIn, modifiedAt = 20),
            ),
        )

        assertEquals(listOf("workspace（默认）", "app"), sections.map { it.label })
        assertEquals(listOf(1, 1), sections.map { it.sessions.size })
    }

    @Test
    fun `chat history is grouped by time and names no workspace`() {
        // A chat conversation converted from work still records the project's `cwd`;
        // the list must not resurrect that folder as a heading in a mode that loads no
        // workspace at all. Time is the one fact left that describes the conversation.
        val day = 86_400_000L
        val sections = history(
            AgentMode.CHAT,
            listOf(
                summary("$home/a.jsonl", added, modifiedAt = now - 60_000L),
                summary("$home/b.jsonl", builtIn, modifiedAt = now - day),
                summary("$home/c.jsonl", null, modifiedAt = now - 3 * day),
                summary("$home/d.jsonl", added, modifiedAt = now - 10 * day),
                summary("$home/e.jsonl", builtIn, modifiedAt = now - 60 * day),
            ),
        )

        assertEquals(
            listOf("Today", "Yesterday", "Previous 7 days", "Previous 30 days"),
            sections.take(4).map { it.label },
        )
        assertEquals(5, sections.size)
        assertEquals(1, sections[0].sessions.size)
        assertEquals("chat-today", sections[0].key)
        // No heading is a workspace path, whatever the rows recorded.
        assertTrue(sections.none { it.key.startsWith("/") })
    }

    @Test
    fun `an empty chat history has no sections`() {
        assertEquals(emptyList<Any>(), history(AgentMode.CHAT, emptyList()))
    }
}
