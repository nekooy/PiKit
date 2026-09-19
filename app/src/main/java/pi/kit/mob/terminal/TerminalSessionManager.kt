package pi.kit.mob.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import pi.kit.mob.env.ShellEnvironment
import pi.kit.mob.env.TermuxEnv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The interactive shells shown in the terminal tab.
 *
 * Owned by the process rather than by the composable: the terminal is one of
 * four tabs, so its composable is disposed every time the user looks at another
 * one. A session created inside that composable died with it, which meant a
 * `pkg upgrade` or a long build was killed by a tab switch. Sessions live here,
 * survive every disposal, and the tab merely displays one of them.
 *
 * Every mutating method and every callback that mutates state runs on the main
 * thread — [TerminalSession] delivers its callbacks there itself — so the fields
 * below need no further synchronisation.
 */
class TerminalSessionManager(
    context: Context,
    env: TermuxEnv,
    /**
     * The active profile's credential, read at spawn time. A lambda rather than a
     * value because the profile can change while a shell is open; a session that
     * already exists keeps the environment it was born with.
     */
    private val credentials: () -> Map<String, String> = { emptyMap() },
) {

    private val appContext: Context = context.applicationContext

    /** One live shell, plus the state the session switcher renders. */
    class Entry internal constructor(
        val id: String,
        val label: String,
        val session: TerminalSession,
    ) {
        /**
         * The title the shell reported through escape sequences, or null when it
         * has not reported one. It is read defensively because `getTitle()`
         * returns null until the emulator exists and a shell is free to send an
         * empty one.
         */
        internal var terminalTitle by mutableStateOf<String?>(null)

        internal var running by mutableStateOf(true)

        /** Only meaningful once [running] is false. */
        internal var exitStatus by mutableStateOf(0)

        /** The shell's own title when it has one, otherwise the stable label. */
        val displayTitle: String get() = terminalTitle?.takeIf { it.isNotBlank() } ?: label

        fun write(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session.write(bytes, 0, bytes.size)
        }

        fun finishIfRunning() = session.finishIfRunning()
    }

    // A justification for StateFlow over mutableStateListOf: `collectAsState()`
    // observes a plain list, so a session added or removed anywhere recomposes
    // the switcher, and publishing a change is a single assignment that cannot
    // be forgotten. A SnapshotStateList would work too, but every mutation would
    // have to happen inside a snapshot for the read to be observed, and the
    // mutations here come from shell callbacks as well as from the UI.
    private val _sessions = MutableStateFlow<List<Entry>>(emptyList())

    /** Live sessions in creation order: index 0 is "Terminal 1". */
    val sessions: StateFlow<List<Entry>> = _sessions.asStateFlow()

    private val _selectedId = mutableStateOf<String?>(null)

    /** The session the [TerminalView] should be showing. */
    val selectedId: State<String?> = _selectedId

    /**
     * Bumped whenever a session's screen or title changes, so the UI repaints
     * the session it is showing even when the change arrived while the terminal
     * tab was not composed.
     */
    private val _screenRevision = MutableStateFlow(0)
    val screenRevision: StateFlow<Int> = _screenRevision.asStateFlow()

    /** The view currently displaying a session, if any, so output can be painted. */
    private var view: TerminalView? = null

    private val sessionFactory = SessionFactory(
        appContext = appContext,
        env = env,
        credentials = credentials,
        notifyScreenChanged = ::onSessionScreenChanged,
        notifyTitleChanged = ::onSessionTitleChanged,
        notifyFinished = ::onSessionFinished,
    )

    /** Opens the first session if there is none, so the page always has one. */
    fun begin() {
        if (_sessions.value.isEmpty()) create()
    }

    /**
     * Spawns a login shell in `$HOME`.
     *
     * The title is the session's index rather than anything the shell reports, so
     * a user with three shells can tell them apart before any of them has printed
     * a directory or a command name; a title the shell does report takes over it
     * (see [Entry.displayTitle]).
     */
    fun create(): Entry {
        val label = "Terminal ${_sessions.value.size + 1}"
        val entry = sessionFactory.spawn(label)
        _sessions.value = _sessions.value + entry
        _selectedId.value = entry.id
        return entry
    }

    fun select(id: String) {
        if (_sessions.value.none { it.id == id }) return
        _selectedId.value = id
    }

    /**
     * Kills a session and forgets it.
     *
     * Closing the last one immediately opens a replacement rather than being
     * refused: the terminal is a primary tab, and a manager with no session
     * leaves it showing a blank page with nothing to type into. There is no
     * state a user could want to preserve by having zero shells.
     */
    fun close(id: String) {
        val current = _sessions.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return

        val entry = current[index]
        _sessions.value = current.toMutableList().also { it.removeAt(index) }.toList()
        entry.finishIfRunning()

        // Decided here rather than left to the caller: between the removal and
        // the UI's next recomposition the entry it is holding is already gone,
        // and asking it to choose the successor is how a click lands on a
        // session that no longer exists.
        val successor = _sessions.value.getOrNull(index)
            ?: _sessions.value.lastOrNull()
            ?: create()
        _selectedId.value = successor.id
    }

    /**
     * Kills every session and opens one replacement.
     *
     * One replacement, not zero, for the reason [close] gives: the terminal is a
     * primary tab and a manager with no session leaves it showing a blank page with
     * nothing to type into. Calling [close] per id would be wrong here — every pass
     * opens the successor for the session it had just removed, so closing three
     * shells would spawn four times and leave the last replacement running.
     */
    fun closeAll() {
        val closing = _sessions.value
        if (closing.isEmpty()) return
        _sessions.value = emptyList()
        closing.forEach { it.finishIfRunning() }
        _selectedId.value = create().id
    }

    /**
     * Called from the composable whenever the [TerminalView] showing [id] is
     * created or replaced, so output arriving while another tab is visible still
     * has somewhere to be painted.
     */
    fun attachView(terminal: TerminalView, id: String) {
        view = terminal
    }

    fun detachView(terminal: TerminalView) {
        if (view === terminal) view = null
    }

    private fun onSessionScreenChanged(changed: TerminalSession) {
        _screenRevision.value++
        repaintIfShowing(changed)
    }

    private fun onSessionTitleChanged(changed: TerminalSession) {
        val entry = _sessions.value.firstOrNull { it.session === changed } ?: return
        entry.terminalTitle = readSessionTitle(changed)
        _screenRevision.value++
        repaintIfShowing(changed)
    }

    /**
     * A shell can end without being closed from the UI: the user types `exit`,
     * it crashes, or something kills it. Reporting that is the difference
     * between a switcher that lists a dead session as live and one that does
     * not.
     */
    private fun onSessionFinished(finished: TerminalSession) {
        val entry = _sessions.value.firstOrNull { it.session === finished } ?: return
        entry.running = false
        entry.exitStatus = finished.exitStatus
        _screenRevision.value++
    }

    private fun repaintIfShowing(session: TerminalSession) {
        if (view?.mTermSession === session) view?.onScreenUpdated()
    }

    /** `getTitle()` returns null until the emulator exists; blank means unset. */
    private fun readSessionTitle(session: TerminalSession): String? =
        session.title?.replace(OSC_TITLE_PREFIX, "")?.trim()?.ifBlank { null }

    /**
     * Creates sessions and translates their callbacks.
     *
     * A `TerminalSessionClient` per session, so `setTerminalShellPid` and the
     * title callbacks can be attributed to the right entry.
     *
     * ## Why these are named `notify…`
     *
     * They were `onScreenChanged`, `onTitleChanged` and `onFinished` — the same
     * names as the `TerminalSessionClient` methods that call them — and
     * `onTitleChanged` was therefore a **self-call**: inside
     * `override fun onTitleChanged(...)`, `onTitleChanged(changedSession)` resolves
     * to the override, not to the constructor's property. Running `pi` in the
     * terminal killed the process with a `StackOverflowError` after 8 MB of
     * `TerminalSessionManager$SessionFactory$client$1.onTitleChanged` frames, and
     * `pi` is what triggers it because a TUI sets the window title on every render
     * while `ls` never sets one at all.
     *
     * The compiler cannot see this: both are `(TerminalSession) -> Unit`, so the
     * self-call type-checks and reads as a delegation. A prefix that is impossible
     * for an interface method to carry is what makes the two distinguishable at the
     * call site.
     */
    private class SessionFactory(
        private val appContext: Context,
        private val env: TermuxEnv,
        private val credentials: () -> Map<String, String>,
        private val notifyScreenChanged: (TerminalSession) -> Unit,
        private val notifyTitleChanged: (TerminalSession) -> Unit,
        private val notifyFinished: (TerminalSession) -> Unit,
    ) {

        private val main = Handler(Looper.getMainLooper())

        private val clipboard: ClipboardManager?
            get() = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        fun spawn(label: String): Entry {
            val shell = when {
                env.login.isFile -> env.login.absolutePath
                else -> env.bash.absolutePath
            }
            val arguments = if (env.login.isFile) {
                // `$PREFIX/bin/login` is a script that exports SHELL and
                // LD_PRELOAD and then execs the shell, so it takes no arguments
                // of its own.
                emptyArray()
            } else {
                arrayOf("--login")
            }
            val environment = ShellEnvironment.forLoginShell(appContext, env, credentials())
                .map { (key, value) -> "$key=$value" }
                .toTypedArray()

            val created = TerminalSession(
                shell,
                env.homePath,
                arguments,
                environment,
                TRANSCRIPT_ROWS,
                client(),
            )
            return Entry(created.mHandle, label, created)
        }

        /**
         * An anonymous object rather than one built from a lambda: the interface
         * declares `Integer getTerminalCursorStyle()`, and an object expression
         * is what makes Kotlin emit the mangled name
         * `getTerminalCursorStyle$terminal_emulator` that the vendored Java
         * interface expects.
         */
        private fun client(): TerminalSessionClient = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                notifyScreenChanged(changedSession)
            }

            override fun onTitleChanged(changedSession: TerminalSession) {
                notifyTitleChanged(changedSession)
            }

            override fun onSessionFinished(finishedSession: TerminalSession) {
                notifyFinished(finishedSession)
            }

            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                post {
                    runCatching {
                        clipboard?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                    }.onFailure { Log.w(TAG, "Copy to the clipboard failed", it) }
                }
            }

            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                if (session == null) return
                post {
                    val text = runCatching {
                        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
                    }.getOrNull()
                    // An empty clipboard means there is nothing to send, and
                    // TerminalSession.write() would still cycle the I/O queue.
                    if (!text.isNullOrEmpty()) {
                        val bytes = text.toByteArray(Charsets.UTF_8)
                        session.write(bytes, 0, bytes.size)
                    }
                }
            }

            override fun onBell(session: TerminalSession) {
                // There is no audible bell and no per-session window to flash,
                // so the only useful thing left is a record of when it rang.
                Log.i(TAG, "bell from session ${session.mHandle}")
            }

            override fun onColorsChanged(session: TerminalSession) {
                notifyScreenChanged(session)
            }

            override fun onTerminalCursorStateChange(state: Boolean) = Unit

            override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

            override fun getTerminalCursorStyle(): Int? = null

            override fun logError(tag: String, message: String) {
                Log.e(tag, message)
            }

            override fun logWarn(tag: String, message: String) {
                Log.w(tag, message)
            }

            override fun logInfo(tag: String, message: String) {
                Log.i(tag, message)
            }

            override fun logDebug(tag: String, message: String) {
                Log.d(tag, message)
            }

            override fun logVerbose(tag: String, message: String) {
                Log.v(tag, message)
            }

            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
                Log.e(tag, message, e)
            }

            override fun logStackTrace(tag: String, e: Exception) {
                Log.e(tag, "error", e)
            }

            private fun post(block: () -> Unit) {
                if (Looper.myLooper() === Looper.getMainLooper()) block() else main.post(block)
            }
        }
    }

    companion object {
        private const val TAG = "PiKitTerminal"

        /**
         * The `OSC 0 ; title BEL` prefix a shell emits for its title. Termux
         * does not strip it before handing the title to the client.
         */
        private val OSC_TITLE_PREFIX = Regex("^\\u001b]0;")

        private const val CLIP_LABEL = "terminal"

        /** Scrollback kept per session; the pre-existing value, unchanged. */
        private const val TRANSCRIPT_ROWS = 2000
    }
}
