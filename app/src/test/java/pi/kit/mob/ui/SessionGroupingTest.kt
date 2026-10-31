package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import pi.kit.mob.data.Workspace
import pi.kit.mob.pi.PiAgentSession.SessionSummary

/**
 * How the history page groups conversations by the workspace they came from.
 *
 * The grouping key is the `cwd` pi wrote into the session file, which is the only place
 * a saved conversation says where it was started — so these cases are about what happens
 * when that answer is missing, stale, or names a folder the workspace list no longer has.
 * That last one is the case that would silently lose a conversation: a history page that
 * only drew conversations from *current* workspaces would drop every conversation from a
 * project the reader has since removed.
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

    /** The chip label the composer uses, so the two screens cannot disagree. */
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

    @Test
    fun `one group per workspace, named as the composer's chip names it`() {
        val grouped = groups(
            listOf(
                summary("$home/a.jsonl", added, modifiedAt = 10),
                summary("$home/b.jsonl", builtIn, modifiedAt = 20),
                summary("$home/c.jsonl", added, modifiedAt = 30),
            ),
        )

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
}
