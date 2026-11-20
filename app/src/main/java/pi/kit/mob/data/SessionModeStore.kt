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

    /**
     * Whether a chat conversation may use the bundled extension's network tools.
     *
     * A second record per session, in the same file and under a prefixed key, because the
     * answer is the same kind of fact as the mode: a launch flag that belongs to the
     * conversation. **False when there is no record**, which is the clean context chat
     * mode promises and the answer for every conversation written before the switch
     * existed. A conversation with no file yet never asks this function: it starts from
     * `PiSettings.chatWebAccessDefault` (off unless the reader chose otherwise) and
     * records that answer when pi writes its session file. The tools are declared in the
     * request rather than hidden on screen, so an unconditional default of on would put
     * `web_search` in every chat request whether or not the reply needed it.
     *
     * The prefix keeps the two maps apart in one preference file: a mode is stored under
     * the bare path, and a boolean under `web:<path>`. One file rather than two because a
     * backup entry per conversation kind is a list that grows, and both records already
     * travel with the transcripts.
     */
    fun webAccessFor(path: String): Boolean {
        if (path.isBlank()) return false
        return prefs.getBoolean(KEY_WEB_PREFIX + path, false)
    }

    /** Records [enabled] for [path]; a blank path is a conversation with no file yet. */
    fun setWebAccess(path: String, enabled: Boolean) {
        if (path.isBlank()) return
        prefs.edit().putBoolean(KEY_WEB_PREFIX + path, enabled).apply()
    }

    /** Drops a deleted conversation's entries so the preference file does not grow forever. */
    fun forget(path: String) {
        if (path.isBlank()) return
        prefs.edit().remove(path).remove(KEY_WEB_PREFIX + path).apply()
    }

    companion object {
        /** Also listed in `BackupArchive.PREFERENCE_FILES` under conversations. */
        const val PREFS = "pikit_session_modes"

        /** The key prefix the per-conversation web-access answer is stored under. */
        private const val KEY_WEB_PREFIX = "web:"
    }
}
