package pi.kit.mob.env

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.system.Os
import java.io.File

/**
 * How much of the phone's shared storage the bundled environment may reach, and
 * the `~/storage` symlinks that expose it.
 *
 * ## Why this was redesigned
 *
 * The first version of this app had exactly two states: "no storage" and "all
 * files access", with the whole of `/sdcard` linked into `$HOME/storage` the
 * moment the user granted the second. That is the layout `termux-setup-storage`
 * builds and it is what makes a terminal on Android feel complete — but it is
 * also a single switch that hands an autonomous process read/write/delete over
 * every photo, document and backup on the device, with those paths presented as
 * ordinary directories inside a home folder. A recursive delete that was
 * supposed to clear a scratch directory could therefore reach the user's real
 * files, and there was nothing in between.
 *
 * Three changes follow from that, and together they are the redesign:
 *
 *  1. **The grant is scoped, not binary.** [Policy.roots] is an explicit set.
 *     When it is empty — which is the default for a fresh install, and for anyone
 *     upgrading from a build that had no policy at all — the environment cannot
 *     see shared storage at all and `~/storage` is not created. The user opts
 *     into individual trees, and the broad one (`shared`, all of `/sdcard`) has
 *     to be chosen deliberately.
 *  2. **The app itself can no longer delete a user's files.** Every recursive
 *     delete goes through [SafeDelete], which refuses any path outside the app's
 *     private data directory — `~/storage` links included, and a link is removed
 *     as a link rather than followed.
 *  3. **The farm is reconciled, not rebuilt blind.** The link set is compared
 *     against [Policy.roots] on every change, so revoking a tree removes its link
 *     and nothing else. The old code emptied the whole directory first, with
 *     `deleteRecursively()` on anything that was not a symlink, which is the call
 *     that could follow a link into shared storage.
 *
 * On top of that, the agent runs with a `tool_call` guard that refuses recursive
 * deletes outside `$HOME`, and that also refuses a command naming shared storage
 * outside the granted folders — see `tools/pi-safety-guard.ts` and
 * [spellingsOf]. Three independent layers, because each one alone has a hole: the
 * policy decides what is *offered* (`~/storage`), the guard decides what the model
 * is *allowed to name*, and [SafeDelete] decides what this *app* can do. None of
 * them is a wall Android enforces: "all files access" is granted to the process,
 * the agent runs as this app, and no unprivileged app can run it as anyone else.
 */
object StorageAccess {

    /** The directory the `~/storage` symlinks are created in. */
    const val STORAGE_DIR_NAME = "storage"

    /** Preference file the chosen policy is persisted in. */
    const val PREFS_NAME = "pikit_storage"

    /**
     * One selectable tree under `/sdcard`.
     *
     * [linkName] is the name the folder gets inside the environment
     * (`~/storage/downloads`), kept lower case because `termux-setup-storage` has
     * created them that way for years and scripts type them.
     */
    enum class Root(
        val linkName: String,
        val directoryName: String?,
    ) {
        /** Everything under `/sdcard`. */
        Shared("shared", null),

        Downloads("downloads", Environment.DIRECTORY_DOWNLOADS),
        Documents("documents", Environment.DIRECTORY_DOCUMENTS),
        Pictures("pictures", Environment.DIRECTORY_PICTURES),
        Dcim("dcim", Environment.DIRECTORY_DCIM),
        Music("music", Environment.DIRECTORY_MUSIC),
        Movies("movies", Environment.DIRECTORY_MOVIES),
    }

    /**
     * What the environment can see: the named folders, plus any the user added.
     *
     * The named ones are an enum because they are the same seven on every device
     * and the UI labels them; [custom] holds absolute paths instead, because "the
     * folders the user wants" is not knowable in advance. Both feed the same three
     * things — the symlink farm, the guard's allowed list, and the page's summary —
     * through [linkPlanFor], so a custom folder is not a second-class kind of grant.
     */
    data class Policy(val roots: Set<Root>, val custom: Set<String> = emptySet()) {

        val isEmpty: Boolean get() = roots.isEmpty() && custom.isEmpty()

        /** The whole of shared storage, i.e. the broadest possible grant. */
        val isUnrestricted: Boolean get() = Root.Shared in roots

        /**
         * Whether [root] is already reachable through a broader grant, so a link of
         * its own would name the same directory twice.
         *
         * One function rather than the same condition written out at each layer:
         * the storage page draws these rows as included with their switches
         * disabled, and the link farm must not create them either. When the two
         * disagreed, `/sdcard/Download` appeared in `~/storage` twice — once as
         * `downloads` from its own grant and once under `shared` — which is the
         * duplicate the user reported. `Root.Shared` itself is never subsumed; it
         * is the grant that does the subsuming.
         */
        fun isSubsumed(root: Root): Boolean = isUnrestricted && root != Root.Shared

        fun toggled(root: Root, enabled: Boolean): Policy =
            copy(roots = if (enabled) roots + root else roots - root)

        /** Adds a folder the user picked. Silently a no-op when it is already in. */
        fun plus(path: String): Policy = copy(custom = custom + path)

        fun minus(path: String): Policy = copy(custom = custom - path)

        companion object {
            /**
             * What a fresh installation starts with: nothing.
             *
             * This is the deliberate change in behaviour. The old app linked all
             * of `/sdcard` as soon as the permission existed, so the first
             * `pkg install` — whose `dpkg` unpacks with the installer's own
             * permissions — already had the user's files in reach. Opting in is
             * one tap; recovering deleted files is not possible at all.
             */
            val NONE = Policy(emptySet())
        }
    }

    // ------------------------------------------------------------ permission

    /**
     * Whether the *Android* permission that makes shared storage writable is
     * held. Below API 30 the legacy permissions are enough and this is always
     * true; from API 30 it is the "All files access" page.
     *
     * Asked of [Environment] rather than of `checkSelfPermission`, which reports
     * "granted" for a legacy-target app even when the kernel group is still
     * missing — exactly the state that produces "can read but cannot write".
     */
    fun isGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    }

    /**
     * The system screen the user has to confirm on.
     *
     * "All files access" has no runtime dialog — this settings page is the only
     * way to obtain it, so the UI sends the user there and re-checks when the
     * app comes back to the foreground.
     */
    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether the one-time explanation has been shown.
     *
     * "All files access" has no runtime dialog, so the app cannot simply ask for
     * it the way it asks for notifications. What it can do is explain once, on the
     * first launch, and offer the system page — which is what a user who expects a
     * permission prompt is actually looking for.
     *
     * Recorded on the first *answer*, not on the first showing, and never reset:
     * an install that reopened this on every launch would be nagging, and the
     * Settings row is always there for anyone who dismissed it by mistake.
     */
    fun hasPrompted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- policy

    /** The persisted policy. Missing means [Policy.NONE] — never "everything". */
    fun readPolicy(context: Context): Policy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getStringSet(KEY_ROOTS, null) ?: return Policy.NONE
        // An unknown name is dropped rather than crashing, so a downgrade after a
        // future version added a root loses that root instead of the settings.
        val roots = stored.mapNotNull { name -> Root.entries.firstOrNull { it.name == name } }
        val custom = prefs.getStringSet(KEY_CUSTOM, null).orEmpty()
            .filter { it.startsWith("/") }
            .toSet()
        return Policy(roots.toSet(), custom)
    }

    fun writePolicy(context: Context, policy: Policy) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ROOTS, policy.roots.map { it.name }.toSet())
            .putStringSet(KEY_CUSTOM, policy.custom)
            .apply()
    }

    /** Where [root] points, or null when shared storage is not reachable. */
    fun targetOf(root: Root): File? {
        val external = sharedRoot() ?: return null
        return root.directoryName?.let { File(external, it) } ?: external
    }

    /** The root of shared storage itself, or null when it cannot be read. */
    fun sharedRoot(): File? = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()

    /**
     * Where [root] points, spelled the way the user's file manager spells it.
     *
     * `/sdcard/DCIM`, not `~/storage/dcim`, and the difference is not cosmetic: the
     * *link* name is lower case because that is what `termux-setup-storage` creates
     * (`~/storage/dcim`, `~/storage/downloads` — eight characters, typed by hand
     * for years, and dropping them would break every script the user already has),
     * while the real directory on Android is `DCIM` and `Download`. A settings page
     * that shows only the link is a page where the folder the user recognises never
     * appears.
     *
     * Falls back to the link's own path when the external directory cannot be read,
     * which is a device-state question and not one worth hiding the whole page for.
     */
    fun displayTargetOf(root: Root): String {
        val shown = targetOf(root)?.absolutePath
            ?: sharedRoot()?.let { File(it, root.linkName).absolutePath }
            ?: "~/storage/${root.linkName}"
        return displayPathOf(shown)
    }

    /**
     * Every spelling a command might use for [path], given the external root.
     *
     * `/storage/emulated/0/DCIM`, `/sdcard/DCIM` and `/storage/self/primary/DCIM`
     * are one directory, and a shell command may name any of them. The agent's
     * guard matches text rather than resolving symlinks — deliberately, so it
     * cannot be talked into following a link — so the policy it is given has to
     * carry every spelling, or "granted Download" would refuse `/sdcard/Download`
     * on a device whose external root is `/storage/emulated/0`.
     */
    private fun spellings(path: String, external: String?): List<String> {
        val out = linkedSetOf(path)
        if (external != null && external.isNotEmpty()) {
            when {
                path == external -> {
                    out += PRIMARY_ALIAS
                    out += SELF_ALIAS
                }

                path.startsWith("$external/") -> {
                    val rest = path.removePrefix("$external/")
                    out += "$PRIMARY_ALIAS/$rest"
                    out += "$SELF_ALIAS/$rest"
                }
            }
        }
        return out.toList()
    }

    /** The external root in every spelling, for the guard's `PIKIT_STORAGE_ROOTS`. */
    fun rootSpellings(): List<String> {
        val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        return spellings(external?.absolutePath ?: PRIMARY_ALIAS, external?.absolutePath)
    }

    /** One granted folder in every spelling, for `PIKIT_STORAGE_ALLOWED`. */
    fun spellingsOf(root: Root): List<String> {
        val path = targetOf(root)?.absolutePath ?: return emptyList()
        return spellingsOf(path)
    }

    /** An arbitrary granted folder — a custom one — in every spelling. */
    fun spellingsOf(path: String): List<String> {
        val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        return spellings(path, external?.absolutePath)
    }

    /**
     * Every granted path, in every spelling: what the agent's guard is allowed to
     * name. Root folders and custom ones are the same thing here, which is the
     * point of routing both through this function.
     */
    fun allowedSpellings(policy: Policy): List<String> =
        (policy.roots.flatMap { spellingsOf(it) } + policy.custom.flatMap { spellingsOf(it) })
            .distinct()

    /** Where [path] points, spelled the way the user's file manager spells it. */
    fun displayPathOf(path: String): String {
        val external = runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.absolutePath
            ?: return path
        return when {
            path == external -> PRIMARY_ALIAS
            path.startsWith("$external/") -> "$PRIMARY_ALIAS/${path.removePrefix("$external/")}"
            else -> path
        }
    }

    /**
     * The link name a custom folder gets inside the environment.
     *
     * Lower case, spaces and punctuation folded to `-`, because the name is typed
     * in a shell and the alternative is quoting it every time. The named roots have
     * their own historical names (`downloads`, `dcim`) and are not produced here.
     */
    internal fun linkNameFor(path: String): String {
        val cleaned = path.trimEnd('/').substringAfterLast('/')
            .lowercase()
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-' }
            .joinToString("")
            // Runs collapsed, because one separator per rejected *character* turns
            // `Camera (2026)` into `camera---2026`, and a link name is something the
            // user has to type.
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return cleaned.ifEmpty { "folder" }
    }

    /**
     * The links the farm should hold for [policy]: name to target, collision-free.
     *
     * **One directory, one name.** A grant is only worth a link if it adds reach:
     * while the whole tree is on, `shared` is the only link the plan keeps, because
     * every other grant names a directory inside it — `/sdcard/Download` is
     * `~/storage/downloads` by its own grant and `~/storage/shared/Download`
     * through the broad one, so the farm showed one folder twice. The page already
     * states this rule ([Policy.isSubsumed] is the same condition its switches use,
     * and `StoragePage` only lets a custom folder be picked inside the shared
     * root); this is the rule one layer down, where the names are actually made.
     * It has to live in the plan and not in `applyPolicy`'s creation loop, because
     * the plan is also what prunes: a stale `downloads` left by an earlier grant
     * disappears on the next apply only because it is absent from `wanted`.
     *
     * Dropping the other links loses no grant: [Policy.roots] and [Policy.custom]
     * are untouched, so switching `shared` off brings each folder's own link back.
     *
     * Two custom folders can share a basename (`/sdcard/A/photos` and
     * `/sdcard/B/photos`), and one of them has to lose: the second gets `photos-2`
     * rather than either overwriting the other or being dropped silently.
     */
    internal fun linkPlanFor(policy: Policy): Map<String, String> {
        val plan = linkedMapOf<String, String>()
        policy.roots.forEach { root ->
            if (policy.isSubsumed(root)) return@forEach
            val target = targetOf(root)?.absolutePath ?: return@forEach
            plan[root.linkName] = target
        }
        // Every custom folder is inside the shared root — the picker only walks
        // down from `sharedRoot()` — so while that root is on there is no custom
        // folder `shared` does not already reach.
        if (policy.isUnrestricted) return plan
        policy.custom.sorted().forEach { path ->
            val base = linkNameFor(path)
            var name = base
            var suffix = 2
            while (name in plan) name = "$base-${suffix++}"
            plan[name] = path
        }
        return plan
    }

    /** `/sdcard`, the short name every Android app and shell script uses. */
    const val PRIMARY_ALIAS = "/sdcard"

    /** The other stable alias, which a few tools prefer. */
    const val SELF_ALIAS = "/storage/self/primary"

    /**
     * The link farm as the guard reads it: one `name=target` pair per line.
     *
     * Newline-separated rather than the `:` the other two lists use, because a
     * *custom* folder's name can legally contain a colon and a target is an absolute
     * path; `name=` splits on the first `=` and the name is already restricted to
     * `[a-z0-9._-]` by [linkNameFor].
     *
     * It exists because the link spelling is the one PiKit itself offers the agent
     * and it contains none of the grant's paths: `~/storage/shared/Download/a.txt` is
     * `/sdcard/Download/a.txt`. Without this the folder switches were defeated by
     * their own convenience links — `cat ~/storage/shared/Download/a.txt` was allowed
     * with nothing granted while `cat /sdcard/Download/a.txt` was refused. The
     * parser on the other side is `linkList` in `tools/pi-safety-guard.ts`, and
     * `StoragePolicyTest` pins this format against it.
     */
    internal fun storageLinksValue(plan: Map<String, String>): String =
        plan.entries.joinToString("\n") { (name, target) -> "$name=$target" }

    // --------------------------------------------------------------- symlinks
    /**
     * Brings `$HOME/storage` in line with [policy].
     *
     * The links are created directly rather than by running
     * `termux-setup-storage`: that script broadcasts a `reload_style` request for
     * the app to handle and then blocks on a `read` prompt, neither of which
     * works from here. [StorageSetupReceiver] handles the broadcast so the
     * command still works for someone who types it.
     *
     * Removal is per-link by name, and only for entries this function created.
     */
    fun applyPolicy(env: TermuxEnv, policy: Policy): Result<Unit> = runCatching {
        val storage = env.storageDir

        if (policy.isEmpty || !isGranted()) {
            removeFarm(storage)
            return@runCatching
        }

        storage.mkdirs()
        val wanted = linkPlanFor(policy)

        // Drop links that are no longer wanted. Anything that is not a symlink is
        // left alone: this app did not put it there.
        storage.listFiles()?.forEach { child ->
            if (child.name !in wanted) removeLink(child)
        }

        val failures = mutableListOf<String>()
        wanted.forEach { (name, target) ->
            val link = File(storage, name)
            if (isLink(link)) return@forEach
            if (link.exists()) {
                // A real file or directory has taken the name; leave it alone
                // rather than deleting something the user may have put there.
                failures += name
                return@forEach
            }
            val failure = runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), File(target).toPath())
            }.exceptionOrNull()
            if (failure != null) failures += name
        }

        writeTermuxProperties(env)

        check(failures.isEmpty()) {
            "storage links could not be created: ${failures.joinToString()}"
        }
    }

    /**
     * Removes the links in the farm.
     *
     * Nothing here can reach a target's contents: a link is deleted by path, so
     * the directory it pointed at is untouched. Anything that is not a symlink is
     * left where it is.
     */
    fun removeFarm(storage: File) {
        storage.listFiles()?.forEach { removeLink(it) }
    }

    private fun removeLink(entry: File) {
        if (isLink(entry)) {
            runCatching { java.nio.file.Files.deleteIfExists(entry.toPath()) }
        }
    }

    /**
     * `File.isDirectory` follows a link, so a link into shared storage would look
     * like a directory and be walked through. This asks the filesystem what the
     * entry *is*, which is the only answer that matters here.
     */
    private fun isLink(file: File): Boolean =
        runCatching { Os.lstat(file.absolutePath) }.isSuccess &&
            runCatching { Os.readlink(file.absolutePath) }.isSuccess

    /**
     * Records in `$HOME/.termux/termux.properties` that this app may start other
     * apps.
     *
     * Termux's tools read this to decide whether they may, and pi reads it before
     * handing a file to a viewer. Written without clobbering anything the user
     * put there.
     */
    private fun writeTermuxProperties(env: TermuxEnv) {
        runCatching {
            val properties = File(env.home, ".termux/termux.properties")
            properties.parentFile?.mkdirs()
            val existing = if (properties.isFile) properties.readText() else ""
            if (!existing.contains(KEY_ALLOW_EXTERNAL_APPS)) {
                properties.writeText(
                    existing.trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" } +
                        "# Written by PiKit: lets `pi` open a file in another app.\n" +
                        "$KEY_ALLOW_EXTERNAL_APPS=true\n",
                )
            }
        }
    }

    /**
     * Proves the grant actually works by writing through it and reading back.
     *
     * [isGranted] only reports what Android recorded; it does not prove the
     * kernel will let this process through the storage mount. Those two have been
     * seen to disagree — which is precisely the "it can read but not save"
     * report — so the result is what the Settings row displays rather than an
     * assumption.
     */
    fun canWriteSharedStorage(): Boolean = runCatching {
        val root = Environment.getExternalStorageDirectory() ?: return@runCatching false
        val probe = File(root, PROBE_FILE)
        probe.writeText(PROBE_CONTENT)
        val readBack = probe.readText()
        probe.delete()
        readBack == PROBE_CONTENT
    }.getOrDefault(false)

    /**
     * What this app can actually do to shared storage, as one line for the log.
     *
     * This exists because the capability is genuinely hard to establish from the
     * outside, and getting it wrong is invisible until a user's agent fails to
     * save something. Two things make it hard:
     *
     *  - `/storage/emulated` is a **FUSE** mount on modern Android. Access is
     *    decided by the media provider against this app's uid and its
     *    `MANAGE_EXTERNAL_STORAGE` app-op, *not* by Unix group membership. This
     *    app's process is not in `sdcard_rw` or `media_rw`, which looks alarming
     *    and means nothing — so "is the group present" is the wrong question.
     *  - A shell run with `run-as` is a **different** process: it has the uid but
     *    a `runas_app` SELinux context and a different group set, so it cannot
     *    stand in for the app here. Measured: the same `echo > /sdcard/x` that
     *    this app performs successfully is refused (`Permission denied`) from
     *    `run-as`, which is a property of the shell and not of the app.
     *
     * So the check is done from inside the app, over the *granted folders*, with
     * the paths the environment actually uses — which is the thing a user cares
     * about.
     */
    fun describeReach(env: TermuxEnv, policy: Policy): String {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        val grantState = if (isGranted()) "granted" else "not-granted"

        val reach = linkPlanFor(policy).entries.sortedBy { it.key }.map { (name, target) ->
            val via = File(env.storageDir, name)
            val read = probeRead(File(target))
            val write = probeWrite(via)
            "$name:link=${isLink(via)}${if (read != "none") ",read=$read" else ""},write=$write"
        }

        return "storage: permission=$grantState root=${root?.absolutePath ?: "unknown"} " +
            "reach=[${reach.joinToString(" ")}]"
    }

    private fun probeRead(folder: File): String = runCatching {
        val entries = folder.list()
        if (entries == null) "denied" else "ok(${entries.size})"
    }.getOrDefault("error")

    private fun probeWrite(folder: File): String = runCatching {
        folder.mkdirs()
        val probe = File(folder, PROBE_FILE)
        probe.writeText(PROBE_CONTENT)
        val back = probe.readText()
        probe.delete()
        if (back == PROBE_CONTENT) "ok" else "mismatch"
    }.getOrDefault("denied")

    private const val KEY_ROOTS = "roots"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_PROMPTED = "prompted"
    private const val PROBE_FILE = ".pikit-write-probe"
    private const val PROBE_CONTENT = "pikit"

    private const val KEY_ALLOW_EXTERNAL_APPS = "allow-external-apps"
}
