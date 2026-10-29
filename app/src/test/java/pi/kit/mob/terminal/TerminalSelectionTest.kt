package pi.kit.mob.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides which shell the terminal page shows after one is closed.
 *
 * The bug being pinned: closing a shell from the switcher redrew the page on the
 * shell that had taken the closed row, even when a different shell was the one on
 * screen — the delete tap ended up selecting the terminal it was aimed at. Nothing
 * in a screenshot distinguishes that from the correct behaviour (one shell is on
 * screen either way), so the rule is a pure function over ids
 * ([TerminalSelection.afterClose]) and this is where it is checked.
 *
 * Two invariants sit beside it: a close that takes away the selected shell still
 * leaves a live selection, and a close that would empty the roster asks for a
 * replacement instead — the page is never left with zero sessions.
 */
class TerminalSelectionTest {

    private fun afterClose(
        roster: List<String>,
        closedIndex: Int,
        selected: String?,
        replacement: () -> String = { "replacement" },
    ) = TerminalSelection.afterClose(roster, closedIndex, selected, replacement)

    @Test
    fun `closing a shell that is not on screen leaves the selection alone`() {
        // A close below the selected row, and one above it: the row's index is not
        // the selection, and the list shifting up must not move the page either.
        assertEquals("a", afterClose(listOf("a", "b", "c"), 1, "a"))
        assertEquals("c", afterClose(listOf("a", "b", "c"), 0, "c"))
        assertEquals("b", afterClose(listOf("a", "b", "c"), 2, "b"))
    }

    @Test
    fun `closing the shell on screen shows the one that took its row`() {
        assertEquals("c", afterClose(listOf("a", "b", "c"), 1, "b"))
        assertEquals("b", afterClose(listOf("a", "b", "c"), 0, "a"))
    }

    @Test
    fun `closing the last row shows the row above it`() {
        // There is no row below the last one, and the nearest shell left is above it.
        assertEquals("a", afterClose(listOf("a", "b"), 1, "b"))
        assertEquals("b", afterClose(listOf("a", "b", "c"), 2, "c"))
    }

    @Test
    fun `closing the only shell opens a replacement and shows it`() {
        var opened = 0
        val selected = afterClose(listOf("a"), 0, "a") {
            opened++
            "new"
        }
        assertEquals("new", selected)
        assertEquals("a replacement is opened exactly once", 1, opened)
    }

    @Test
    fun `a replacement is not opened while a session remains`() {
        var opened = 0
        afterClose(listOf("a", "b"), 0, "b") {
            opened++
            "new"
        }
        assertEquals("no replacement while a shell is left", 0, opened)
    }

    @Test
    fun `a selection that no longer exists is replaced`() {
        // The page's own fallback (`?: sessions.firstOrNull()`) would show a shell the
        // state does not name; the rule names one instead.
        assertEquals("b", afterClose(listOf("a", "b"), 0, "gone"))
        assertEquals("b", afterClose(listOf("a", "b"), 0, null))
    }
}
