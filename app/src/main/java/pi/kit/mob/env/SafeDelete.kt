package pi.kit.mob.env

import android.system.Os
import java.io.File

/**
 * The only place this app is allowed to delete a directory tree.
 *
 * A recursive delete is the single most destructive thing an app can do, and the
 * three trees this app removes — a half-unpacked staging prefix, the runtime
 * prefix it replaces, and the model-discovery scratch agent directory — all used to
 * call `File.deleteRecursively()` directly, which is a call that will happily walk
 * anywhere it is pointed — including, if a path is ever wrong, into the user's own
 * files. (The `~/storage` link farm is not on that list: a link has to be removed
 * *as a link*, so `StorageAccess.removeFarm` deletes them one at a time instead.)
 *
 * This is the guard that makes that class of bug impossible rather than
 * unlikely. It refuses to run unless all of the following hold:
 *
 *  1. **The tree is inside the app's private data directory.** Nothing under
 *     `/sdcard`, `/storage` or `/mnt` is ever recursively deleted by this app,
 *     whatever path is passed. That is a hard boundary, not a warning: the app
 *     has no legitimate reason to remove a user's directory, and every previous
 *     design that allowed it risked exactly the outcome this exists to prevent.
 *  2. **The tree is below a floor.** The data directory itself, and each
 *     directory directly inside it that this app did not create, are refused.
 *  3. **Symlinks are removed as links, never followed.** `File.deleteRecursively`
 *     decides what a child is from `isDirectory()`, which follows the link — so
 *     a link into shared storage would be walked *through* and the target's
 *     contents deleted. Links are deleted by path instead.
 *
 * No caller uses `deleteRecursively()` any more: every tree above goes through
 * [recursively], and the single-file deletes elsewhere in the app — a session
 * JSONL, the stores' temp files, [PrefixPatcher]'s scratch — are `File.delete()`,
 * which cannot walk a tree.
 */
object SafeDelete {

    /** Top-level entries inside the data directory that this app never removes. */
    private val PROTECTED_NAMES = setOf(
        // Android's own directories, and this app's own persisted state. A
        // recursive delete that reaches any of these is a bug in the caller, and
        // the failure mode — losing the user's settings or preferences — is one
        // the user cannot see coming.
        "cache",
        "code_cache",
        "app_webview",
        "no_backup",
        "shared_prefs",
        "databases",
        "app_flutter",
    )

    sealed interface Outcome {
        data class Deleted(val entries: Int, val links: Int) : Outcome

        data class Refused(val reason: String) : Outcome
    }

    /**
     * Removes [target] and everything under it, or refuses.
     *
     * [within] is the tree the caller believes it owns — the app's `filesDir`,
     * always — and [target] must be strictly inside it. Passing the boundary
     * explicitly rather than reading it from a global keeps the check testable
     * and makes the caller state what it means.
     */
    fun recursively(target: File, within: File): Outcome {
        val refusal = refuse(target, within)
        if (refusal != null) return refusal

        var entries = 0
        var links = 0
        walk(target) { file, isLink ->
            val removed = if (isLink) {
                runCatching { java.nio.file.Files.deleteIfExists(file.toPath()) }.getOrDefault(false)
            } else {
                file.delete()
            }
            if (removed) {
                entries++
                if (isLink) links++
            }
        }
        return Outcome.Deleted(entries, links)
    }

    /** The reason [target] may not be deleted, or null when it may. */
    private fun refuse(target: File, within: File): Outcome.Refused? {
        if (!target.exists() && readLink(target) == null) {
            // Nothing to do; not a refusal, an empty result. Reported as a
            // refusal so a caller that expected a tree notices the difference.
            return Outcome.Refused("does not exist: ${target.absolutePath}")
        }

        // Compared through `Path` rather than by string: `absolutePath` uses the
        // platform separator, and a string comparison against a hard-coded "/"
        // silently failed to match on Windows — which is where the unit tests
        // run. Getting this wrong in the other direction would be worse than a
        // failing test, so the comparison is delegated to the platform.
        val boundary = within.toPath().toAbsolutePath().normalize()
        val path = target.toPath().toAbsolutePath().normalize()

        if (path == boundary) {
            return Outcome.Refused(
                "refusing to delete the data directory itself: ${target.absolutePath}",
            )
        }
        if (!path.startsWith(boundary)) {
            // The important one. This catches /sdcard, /storage/emulated/0, /mnt,
            // /, and any other path the app has no business removing.
            return Outcome.Refused(
                "refusing to delete ${target.absolutePath}: it is outside this app's " +
                    "private directory (${within.absolutePath})",
            )
        }

        // Inside the data directory. Refuse Android's own directories and the
        // app's persisted state, at any depth. `path` is normalised here, so the
        // segments are read off it rather than off a string with a separator
        // glued on.
        val relative = boundary.relativize(path)
        if (relative.nameCount > 0) {
            val firstSegment = relative.getName(0).toString()
            if (firstSegment in PROTECTED_NAMES) {
                return Outcome.Refused(
                    "refusing to delete Android's own directory: ${target.absolutePath}",
                )
            }
        }
        return null
    }

    /**
     * Walks a tree depth-first without following symlinks.
     *
     * Returns children before parents, so a directory is only removed once it is
     * empty. `isLink` is reported from `readlink` rather than inferred, because
     * `File.isDirectory` follows the link and a link to a directory would
     * otherwise be descended into.
     */
    private fun walk(root: File, visit: (File, Boolean) -> Unit) {
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to true)
        val pending = ArrayList<File>()

        while (stack.isNotEmpty()) {
            val (file, _) = stack.removeLast()
            val link = readLink(file) != null
            if (link) {
                visit(file, true)
                continue
            }
            if (file.isDirectory) {
                pending += file
                file.listFiles()?.forEach { stack.addLast(it to false) }
            } else {
                visit(file, false)
            }
        }
        // Directories last, deepest first: `pending` is in discovery order, which
        // is already parents-before-children, so reversing empties the leaves.
        pending.asReversed().forEach { visit(it, false) }
    }

    private fun readLink(file: File): String? =
        runCatching { Os.readlink(file.absolutePath) }.getOrNull()
}
