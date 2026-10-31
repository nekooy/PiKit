package pi.kit.mob.env

import android.content.Context
import pi.kit.mob.data.PermissionMode
import pi.kit.mob.data.SettingsStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The permission tier in force, as the one file both halves of the app agree on.
 *
 * ## Why a file and not an environment variable
 *
 * The tier has to change without ending the conversation. An environment variable is read
 * once, when pi loads an extension, so every switch would restart the agent process — the
 * same restart the storage policy needs, and a visible one. A file is read on every
 * `tool_call`, so the composer's control takes effect on the very next thing the agent
 * does, which is what the DeepSeek Harness's own mode switch does and what a reader
 * expects from a control that sits beside the input box.
 *
 * The cost is a `readFileSync` per tool call on a few hundred bytes. That is the trade
 * this file exists to make, and [PermissionMode]'s header states it from the product side.
 *
 * ## Where it lives, and why there
 *
 * `$HOME/.pikit/permission.json` — outside the agent's workspace, so that under
 * [PermissionMode.WORKSPACE_WRITE] the agent cannot rewrite the file that judges it. The
 * extension's built-in fallback is the same path, so a `pi` typed in the terminal tab is
 * judged exactly like the agent even with no environment at all.
 *
 * The write is atomic (a temporary file renamed over the target): the extension may read
 * this file at any moment, and a half-written JSON is a mode the extension would have to
 * invent an answer for.
 */
object PermissionPolicy {

    /** Where the app tells the extension which file to read. */
    const val ENV_VAR = "PIKIT_PERMISSION_FILE"

    const val DIR_NAME = ".pikit"

    const val FILE_NAME = "permission.json"

    fun dir(env: TermuxEnv): File = File(env.home, DIR_NAME)

    fun file(env: TermuxEnv): File = File(dir(env), FILE_NAME)

    /** The tier to put in force, from the preference the composer and the settings page write. */
    fun current(context: Context): PermissionMode = SettingsStore.permissionMode(context)

    /**
     * Writes [mode] where the extension will read it.
     *
     * Failure is returned rather than thrown: a file that could not be written means the
     * agent keeps the tier it had, which is a worse answer than the user asked for but not
     * one worth crashing the page over.
     */
    fun write(env: TermuxEnv, mode: PermissionMode): Boolean = runCatching {
        val dir = dir(env)
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val target = File(dir, FILE_NAME)
        val body = buildJsonObject { put("mode", mode.code) }.toString()
        // Unchanged is not rewritten: the extension reads the file per tool call and a
        // needless write is a needless chance to catch it mid-rename.
        if (target.isFile && target.readText() == body) return@runCatching true
        val staged = File(dir, "$FILE_NAME.tmp")
        staged.writeText(body)
        if (!staged.renameTo(target)) {
            // `renameTo` can refuse across a filesystem, which cannot happen here — both
            // files are in one directory — but a silent failure would leave the old tier
            // in force with nothing saying so.
            staged.delete()
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    /** Publishes the current preference into the file. Called on install and on a change. */
    fun publish(context: Context, env: TermuxEnv): Boolean = write(env, current(context))
}
