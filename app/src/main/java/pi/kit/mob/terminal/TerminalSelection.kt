package pi.kit.mob.terminal

/**
 * Which shell the terminal page shows after one is closed.
 *
 * A rule of its own rather than three lines inside [TerminalSessionManager.close],
 * for the reason `ConversationReducer` is a pure function: a screenshot of the page
 * shows one shell either way, so nothing in the running app tells a close that moved
 * the selection from one that did not until the reader sees the wrong shell on
 * screen. Ids in, one id out, no Android — a plain JUnit test pins it
 * (`TerminalSelectionTest`).
 *
 * The selection is the reader's choice, and closing a shell is not one. Only a close
 * that takes away the shell on screen is allowed to move the page.
 */
internal object TerminalSelection {

    /**
     * The id to select after the session at [closedIndex] is closed.
     *
     * A selection that survives the close is kept as it is: closing one shell of
     * several leaves the page on the shell the reader was already reading, whatever
     * row the finger was on.
     *
     * When the closed shell *was* the one on screen the successor is the session that
     * took its row ([closedIndex]) — the switcher is a list read top to bottom, and
     * the row the closed shell had is the one the reader's eye already sits on. A
     * closed last row has nothing below it, so it falls back to the row above, which
     * is the last one.
     *
     * @param roster the sessions before the close, in the order the switcher draws
     *   them, with the session being closed at [closedIndex]
     * @param selectedId the session the page was showing before the close. A null or
     *   stale id is treated as "no valid selection" and replaced, rather than left for
     *   the page's own fallback to paper over.
     * @param openReplacement opens the one shell that keeps the page usable when
     *   [roster] holds nothing but the closed session, and returns its id. Called only
     *   in that case: the terminal is a primary tab, and a manager with no session
     *   leaves it a blank rectangle with nothing to type into.
     */
    fun afterClose(
        roster: List<String>,
        closedIndex: Int,
        selectedId: String?,
        openReplacement: () -> String,
    ): String {
        val remaining = roster.filterIndexed { index, _ -> index != closedIndex }
        if (remaining.isEmpty()) return openReplacement()
        return selectedId?.takeIf { id -> remaining.any { it == id } }
            ?: remaining.getOrNull(closedIndex)
            ?: remaining.last()
    }
}
