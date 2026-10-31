package pi.kit.mob.env

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.io.File

/**
 * The phone's shared storage, as the one convenience the environment keeps.
 *
 * ## What this is now
 *
 * It used to be a page: seven named roots, custom folders, a per-folder grant, a revoke
 * dialog and a switch for the tool-call policy. The three permission tiers (§14) made the
 * grant layer redundant — what the agent may *write* is decided by the tier and by the
 * workspace it was started in, and reads are free in every tier — so the page is gone and
 * with it the set of switches. What is left is the two things that are still true:
 *
 *  - **The `~/storage` farm is a convenience, not a permission.** `termux-setup-storage` is a
 *    shell command the runtime ships, and the farm is how a script or an agent reaches the
 *    phone without typing `/sdcard` — `~/storage/downloads`, `~/storage/dcim`. The links
 *    exist for every standard folder once Android's "all files access" is granted, and
 *    they are removed when it is not.
 *  - **A workspace may live on the phone.** "Any folder it can reach may be a workspace"
 *    is the model now, so the picker offers `/sdcard`, and a folder there needs the same
 *    grant. [isGranted] and [settingsIntent] are what the picker and the first-launch
 *    prompt ask.
 *
 * ## Why the writes are not decided here
 *
 * They used to be: the page's switches were enforced inside the tool-call guard, because
 * Android grants "all files access" to the *process* and the agent runs as this app — a
 * switch that only stopped at the app would be a dead letter. §14's tiers do that job now
 * with one answer instead of seven, and the folder a conversation was started in is the
 * writable root. A folder on `/sdcard` therefore becomes writable by being the workspace,
 * not by being granted.
 *
 * ## The grant itself, and its one trap
 *
 * `Environment.isExternalStorageManager()` is the app-op, not the manifest permission, and
 * that is what decides whether the mount is readable — the app-op also survives `pm clear`
 * and even a reinstall, so a "fresh" install can read as already granted.
 */
object StorageAccess {

    /** The directory under `$HOME` the links live in. */
    const val STORAGE_DIR_NAME = "storage"

    const val PREFS_NAME = "pikit_storage"

    private const val KEY_PROMPTED = "prompted"

    /**
     * The links the farm holds, in the names `termux-setup-storage` creates them.
     *
     * The names are the lower-case ones upstream uses, because scripts type them and
     * matching upstream matters more than matching the file manager; the directories are
     * the real ones under `/sdcard`.
     */
    val LINKS: List<Pair<String, String>> = listOf(
        "shared" to "",
        "downloads" to "Download",
        "documents" to "Documents",
        "pictures" to "Pictures",
        "dcim" to "DCIM",
        "music" to "Music",
        "movies" to "Movies",
    )

    /** Whether the app may read the phone's shared storage at all. */
    fun isGranted(): Boolean = runCatching {
        android.os.Environment.isExternalStorageManager()
    }.getOrDefault(false)

    /** The system page where that is granted. */
    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        android.net.Uri.parse("package:${context.packageName}"),
    )

    fun hasPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** `/sdcard`, or null on a device (or an emulator) that does not have one. */
    fun sharedRoot(): File? = runCatching {
        android.os.Environment.getExternalStorageDirectory()
    }.getOrNull()

    /**
     * Creates or removes the farm to match the grant.
     *
     * Called at install, before every agent launch and from `termux-setup-storage`'s
     * broadcast, so it has to be cheap and idempotent: an existing link to the right
     * target is left alone, a stale one is replaced, and without the grant the whole
     * directory is removed — creating nothing is not the same as taking the links away,
     * and a revoked grant must not leave the agent looking at a directory of dead paths.
     *
     * Links are removed **as links** (`File.delete`, which does not follow them), never as
     * trees: `SafeDelete` refuses anything outside the app's own directory, and a link
     * into shared storage is exactly the path that must not be walked through (§5).
     */
    fun syncFarm(env: TermuxEnv): Boolean {
        val directory = File(env.home, STORAGE_DIR_NAME)
        val root = sharedRoot()
        if (!isGranted() || root == null) {
            if (directory.exists()) {
                directory.listFiles().orEmpty().forEach { it.delete() }
                directory.delete()
            }
            return false
        }
        directory.mkdirs()
        for ((name, child) in LINKS) {
            val target = if (child.isEmpty()) root else File(root, child)
            val link = File(directory, name)
            // `exists()` is false for a dangling link, so the two tests together are
            // "something is at this path".
            val present = link.exists() || isLink(link)
            // A target that is not there would be a link to nowhere. Only the folders the
            // phone actually has are linked; a missing one is dropped rather than left as a
            // dead path.
            if (!target.isDirectory) {
                if (present && isLink(link)) link.delete()
                continue
            }
            // A plain directory of that name is the user's own; it is left alone rather
            // than deleted to put a link in its place.
            if (present && !isLink(link)) continue
            val current = runCatching { link.canonicalPath }.getOrNull()
            if (present && current == target.canonicalPath) continue
            if (present) link.delete()
            runCatching { android.system.Os.symlink(target.absolutePath, link.absolutePath) }
        }
        return true
    }

    private fun isLink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
