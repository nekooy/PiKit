package pi.kit.mob.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import pi.kit.mob.env.TermuxEnv
import java.io.File

/**
 * One directory a conversation may be started in.
 *
 * [label] is the directory's own name (`workspace`, `myproject`), because that is the
 * word the reader sees in the composer's chip and the one that identifies the folder
 * among its siblings; the full path is what the menu shows underneath it. A workspace is
 * identified by its path — two folders with the same name are two workspaces.
 *
 * [builtIn] marks `$HOME/workspace`, which the app creates and the backup carries. It
 * cannot be removed: it is the directory every fresh install starts in, and a list with
 * no entry the app owns is a list that can be emptied.
 */
data class Workspace(
    val path: String,
    val label: String,
    val builtIn: Boolean = false,
) {
    /** `$HOME/foo` as `~/foo`, for a path the reader has to recognise in a menu. */
    fun displayPath(home: String): String {
        val cleanHome = home.trimEnd('/')
        return if (cleanHome.isNotEmpty() && path.startsWith("$cleanHome/")) {
            "~" + path.removePrefix(cleanHome)
        } else {
            path
        }
    }
}

/**
 * Why a path cannot become a workspace.
 *
 * An enum rather than a sentence: the sentence is user-visible text and belongs in the
 * catalogs (see `Strings.workspaces`), while this is the fact the page reports.
 */
enum class WorkspaceRefusal {
    BLANK,
    NOT_ABSOLUTE,
    MISSING,
    NOT_A_DIRECTORY,
    /** `$HOME` itself: pi's own configuration lives there, so it is not a project. */
    HOME,
    /** Inside `$HOME/.pi` — the agent's own files, which it must not treat as a project. */
    AGENT_DIR,
    /** Inside `$HOME/storage`, which is the user's phone, not a place to build in. */
    SHARED_STORAGE,
    DUPLICATE,
}

/**
 * The workspaces the user has added, on top of the one the app ships.
 *
 * ## Why a list rather than one setting
 *
 * The agent's working directory used to be a single text field on the settings page.
 * One directory is one project, and a phone is used for several over a week: the history
 * then reads as one undifferentiated stream of conversations from different folders, and
 * going back to an old one means retyping the path it belonged to. The list is what lets
 * the composer ask "which workspace" and the history answer "these came from that one".
 *
 * ## Where the default lives
 *
 * Not here: [SettingsStore]'s `defaultWorkspace` holds the path a *new* conversation
 * starts in, because it is a preference like the language. This store holds the *set* of
 * workspaces — the built-in directory plus what the user added — and both the composer's
 * menu and the settings page read it. A default that stops being in the set falls back to
 * the built-in one rather than launching the agent in a directory that is gone.
 *
 * ## What may not be added
 *
 * `$HOME` itself, anything under `$HOME/.pi`, and anything under `$HOME/storage`. The
 * first two are the agent's own state — a recursive delete of a workspace must never be
 * able to reach `auth.json` — and the third is the user's phone, which the storage page
 * grants folder by folder and which is not a place to build in. [WorkspaceRefusal] is
 * the answer for each.
 */
object WorkspaceStore {

    /** The preference file the added paths are persisted in. */
    const val PREFS = "pikit_workspaces"

    private const val KEY_ADDED = "added"

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped whenever the set changes.
     *
     * The composer draws the workspace list in three places — the chip, the menu and the
     * picker — and none of them owns it. A flow rather than a version number in the
     * preferences because the change happens under a sheet that is already composed, and
     * a menu that has to be reopened to see the folder the reader just added is the
     * second half of one decision.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** `$HOME/workspace`, the directory every fresh install starts in. */
    fun builtIn(env: TermuxEnv): Workspace =
        Workspace(path = env.workspacePath, label = TermuxEnv.WORKSPACE_DIR_NAME, builtIn = true)

    /** The built-in workspace first, then the added ones in the order they were added. */
    fun list(context: Context, env: TermuxEnv): List<Workspace> {
        val builtIn = builtIn(env)
        val seen = linkedSetOf(builtIn.path)
        return buildList {
            add(builtIn)
            for (path in addedPaths(context)) {
                if (!seen.add(path)) continue
                val file = File(path)
                // A folder the user deleted is not a workspace any more. Dropping it on
                // read rather than on write means the menu cannot offer a directory that
                // no longer exists, and nothing has to prune the preference.
                if (!file.isDirectory) continue
                add(Workspace(path = path, label = file.name.ifBlank { path }))
            }
        }
    }

    /**
     * The workspace at [path], or the default one when [path] is blank or unknown.
     *
     * Unknown rather than absent: a remembered conversation whose folder has been deleted
     * still has to open, and the honest answer is the built-in workspace with the
     * conversation's own path shown in the history row.
     */
    fun resolve(context: Context, env: TermuxEnv, path: String?): Workspace {
        val wanted = path?.trim().orEmpty()
        val all = list(context, env)
        if (wanted.isEmpty()) return defaultOf(context, env, all)
        return all.firstOrNull { it.path == wanted } ?: defaultOf(context, env, all)
    }

    /** The workspace [SettingsStore]'s `defaultWorkspace` names, or the built-in one. */
    fun default(context: Context, env: TermuxEnv): Workspace =
        defaultOf(context, env, list(context, env))

    private fun defaultOf(context: Context, env: TermuxEnv, all: List<Workspace>): Workspace {
        // The preference rather than the settings snapshot: this is called from the
        // composer's chip on every frame, and a snapshot read would build a
        // `ProfileStore` — which parses `pikit-config.json` — to answer a path.
        val wanted = SettingsStore.defaultWorkspace(context).trim()
        return all.firstOrNull { it.path == wanted } ?: all.first()
    }

    /** Adds [path]; returns the workspace, or the reason it cannot be one. */
    fun add(context: Context, env: TermuxEnv, path: String): Result<Workspace> {
        val normalised = normalise(env, path)
        val refusal = refusal(env, normalised)
        if (refusal != null) return Result.failure(RefusedWorkspace(refusal))
        val added = addedPaths(context)
        if (normalised in added || normalised == env.workspacePath) {
            // Already there is not an error the user has to read: the menu simply shows
            // it once. The caller reports nothing and closes.
            return Result.failure(RefusedWorkspace(WorkspaceRefusal.DUPLICATE))
        }
        write(context, added + normalised)
        _revision.value += 1
        return Result.success(Workspace(path = normalised, label = File(normalised).name))
    }

    /** Removes an added workspace. The built-in one is refused and returns false. */
    fun remove(context: Context, env: TermuxEnv, path: String): Boolean {
        if (path == env.workspacePath) return false
        val added = addedPaths(context)
        if (path !in added) return false
        write(context, added - path)
        // A removed workspace cannot stay the default: the next new conversation would
        // launch the agent in a directory the menu no longer offers.
        SettingsStore.clearDefaultWorkspace(context, path)
        _revision.value += 1
        return true
    }

    /** [path] with `~`/`$HOME` expanded and trailing slashes removed, or "" for blank. */
    fun normalise(env: TermuxEnv, path: String): String {
        var value = path.trim()
        if (value.isEmpty()) return ""
        val home = env.homePath.trimEnd('/')
        value = when {
            value == "~" -> home
            value.startsWith("~/") -> home + value.removePrefix("~")
            value == "\$HOME" -> home
            value.startsWith("\$HOME/") -> home + value.removePrefix("\$HOME")
            else -> value
        }
        while (value.length > 1 && value.endsWith('/')) value = value.dropLast(1)
        return value
    }

    /** The reason [path] cannot be a workspace, or null when it can. */
    fun refusal(env: TermuxEnv, path: String): WorkspaceRefusal? {
        if (path.isBlank()) return WorkspaceRefusal.BLANK
        if (!path.startsWith("/")) return WorkspaceRefusal.NOT_ABSOLUTE
        val home = env.homePath.trimEnd('/')
        if (path == home || path == env.filesDir.absolutePath.trimEnd('/')) {
            return WorkspaceRefusal.HOME
        }
        if (path == "$home/.pi" || path.startsWith("$home/.pi/")) return WorkspaceRefusal.AGENT_DIR
        if (path == env.storageDir.absolutePath || path.startsWith(env.storageDir.absolutePath + "/")) {
            return WorkspaceRefusal.SHARED_STORAGE
        }
        val file = File(path)
        if (!file.exists()) return WorkspaceRefusal.MISSING
        if (!file.isDirectory) return WorkspaceRefusal.NOT_A_DIRECTORY
        return null
    }

    private fun addedPaths(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ADDED, null) ?: return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as JsonArray
            array.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.startsWith("/") }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, paths: List<String>) {
        val json = buildJsonArray { paths.distinct().forEach { add(JsonPrimitive(it)) } }
        prefs(context).edit().putString(KEY_ADDED, json.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** A [WorkspaceStore.add] failure that is a reason rather than an exception. */
class RefusedWorkspace(val reason: WorkspaceRefusal) : Exception(reason.name)
