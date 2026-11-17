package pi.kit.mob.data

import android.content.Context

/**
 * Which launch mode each saved conversation belongs to.
 *
 * pi's session file records the directory and the messages, but not which command line
 * produced it: chat mode is a set of launch flags, and a transcript from one is
 * indistinguishable from a work transcript that happened not to call a tool. The mode
 * therefore lives beside the session rather than inside pi's file — one preference entry
 * per session path, in a file of this app's own.
 *
 * The mapping is deliberately not part of [PiSettings]: a global preference cannot answer
 * "which mode is this conversation" when two conversations with different modes are open
 * at once, and opening a work conversation while chat mode was the last choice must not
 * silently answer chat. [AgentMode.WORK] is the fallback for a path with no entry, which
 * is also every conversation written before chat mode existed.
 */
internal class SessionModeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The mode recorded for [path], or [AgentMode.WORK] when there is no record. */
    fun modeFor(path: String): AgentMode {
        if (path.isBlank()) return AgentMode.WORK
        return AgentMode.fromCode(prefs.getString(path, null))
    }

    /** Records [mode] for [path]; a blank path is a conversation with no file yet. */
    fun setMode(path: String, mode: AgentMode) {
        if (path.isBlank()) return
        prefs.edit().putString(path, mode.code).apply()
    }

    /** Drops a deleted conversation's entry so the preference file does not grow forever. */
    fun forget(path: String) {
        if (path.isBlank()) return
        prefs.edit().remove(path).apply()
    }

    companion object {
        /** Also listed in `BackupArchive.PREFERENCE_FILES` under conversations. */
        const val PREFS = "pikit_session_modes"
    }
}
