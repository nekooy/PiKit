package pi.kit.mob.data

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import pi.kit.mob.BuildConfig
import pi.kit.mob.env.AgentContext
import pi.kit.mob.env.SafeDelete
import pi.kit.mob.env.TermuxEnv

/**
 * The manifest entry's name, and the only archive format this build reads or writes.
 *
 * The name is a file *inside* the zip rather than a property of it, so it is a
 * stored value in the same sense [BackupCategory.id] is: renaming it would make
 * every archive written by this build unreadable by the next one. [BACKUP_FORMAT]
 * is the version that has to change when the layout does, and is the one field an
 * import refuses a newer archive on.
 */
internal const val BACKUP_MANIFEST_ENTRY = "pikit-backup.json"

internal const val BACKUP_FORMAT = 1

/**
 * One group of things a backup archive carries.
 *
 * [id] is what the manifest and the entry names inside the archive are written
 * with, so it is a **stored** value: renaming one orphans every archive that named
 * it, and the id has to be read back by a build that may be older than the one that
 * wrote it.
 */
enum class BackupCategory(val id: String) {
    /**
     * The app's own preferences: theme, language, working directory, the guard switch,
     * and which of the phone's folders the agent may reach.
     *
     * The storage grant is one of these rather than a group of its own: it is a
     * setting on a settings page, it lives in the same `SharedPreferences` store the
     * rest of the app's preferences live in, and a group with one line in it is a
     * tick box that costs a tap and answers nothing.
     */
    SETTINGS("settings"),

    /**
     * The model profiles — provider, model, key — and the files under
     * `$HOME/.pi/agent` that carry them to pi.
     */
    MODELS("models"),

    /**
     * Every saved conversation: one `.jsonl` per talk under `files/pi-sessions`, and
     * which of them are pinned.
     *
     * The pins travel with the conversations rather than beside them: a pin is a
     * property of a conversation, and a set of names with no conversations to point
     * at is a list of nothing. The conversation that was *open* when the app last
     * closed is deliberately not here — it is where the reader happened to be, not
     * something they chose, and a restore that reopened it would be reopening a file
     * from another install.
     */
    CONVERSATIONS("conversations"),

    /** `AGENTS.md` — the one prompt file the user edits. */
    AGENT_PROMPT("agent-prompt"),

    /** The agent's working directory. */
    WORKSPACE("workspace"),

    /** The HTML the reply's `/export` wrote into `$HOME/export`. */
    HTML_EXPORTS("html-exports"),
    ;

    companion object {
        /**
         * What a page opens with ticked: the three things a reinstall has to get
         * back before the app is the app again.
         *
         * The rest are off by default, and not because they are unimportant: the
         * workspace alone can be a project tree of any size, and a backup is a file
         * the user has to carry somewhere.
         */
        val DEFAULT: Set<BackupCategory> = setOf(SETTINGS, MODELS, CONVERSATIONS)

        fun fromId(id: String): BackupCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The archive's own table of contents, first entry in every archive.
 *
 * [format] is what an import refuses on, and the only field it refuses on:
 * everything else is for the review the page draws before anything is applied.
 * [apiKeys] is there because it cannot be worked out from the entries — a profile
 * with the key blanked looks exactly like a profile whose key was never filled in —
 * and the reader of an archive handed to them by someone else has to be told which
 * of the two they have rather than inferring it from an empty field.
 */
@Serializable
data class BackupManifest(
    val format: Int = BACKUP_FORMAT,
    val app: String = "",
    val createdAt: String = "",
    val categories: List<String> = emptyList(),
    val apiKeys: Boolean = true,
) {
    val included: Set<BackupCategory>
        get() = categories.mapNotNull(BackupCategory::fromId).toSet()
}

/**
 * One preference value, in the shape `SharedPreferences` needs to read it back.
 *
 * The type is carried rather than inferred from the JSON value because it is not
 * recoverable: `1` is a valid `int` and a valid `long`, and `getInt` on a `putLong`
 * throws — into whoever reads it next, not into the restore. `Int` and `Long` are
 * separate arms for that reason and not for tidiness.
 */
internal sealed interface PrefValue {
    data class Text(val value: String) : PrefValue
    data class Flag(val value: Boolean) : PrefValue
    data class Number(val value: Int) : PrefValue
    data class Wide(val value: Long) : PrefValue
    data class Fraction(val value: Float) : PrefValue
    data class TextSet(val value: Set<String>) : PrefValue
}

/**
 * The preference files a backup reads and writes, behind two calls.
 *
 * An interface rather than a `Context` so that everything the archive *does* is
 * testable on the JVM with a map and a temporary directory: the parts worth pinning
 * down are which entries a category writes, where a restore puts them and what a
 * `..` in an entry name does, and none of those needs a device.
 */
internal interface PreferenceStore {
    /** Every value in one preference file, as `SharedPreferences.getAll` reports it. */
    fun read(name: String): Map<String, *>

    /** Applies one decoded document. */
    fun apply(name: String, values: Map<String, PrefValue>)
}

/**
 * Every path the archive reads or writes, as plain files.
 *
 * A value rather than the [TermuxEnv] it is built from, for the reason
 * [PreferenceStore] is an interface: a restore that put `AGENTS.md` somewhere pi
 * does not look, or a workspace entry that landed outside `$HOME/workspace`, is a
 * failure no compiler catches and no device is needed to see.
 */
internal data class BackupPaths(
    val filesDir: File,
    val piConfigDir: File,
    val sessionDir: File,
    val workspace: File,
    val exportDir: File,
) {
    /** `files/pikit-config.json` — the profiles and the keys in them. */
    val configFile: File get() = File(filesDir, CONFIG_FILE_NAME)

    /** `$HOME/.pi/agent/AGENTS.md`. */
    val agentsPromptFile: File get() = File(piConfigDir, AgentContext.FILE_NAME)

    companion object {
        fun of(env: TermuxEnv): BackupPaths = BackupPaths(
            filesDir = env.filesDir,
            piConfigDir = env.piConfigDir,
            sessionDir = env.sessionDir,
            workspace = env.workspace,
            exportDir = env.exportDir,
        )

        /** The profile file, spelled here rather than a second time at the call site. */
        private const val CONFIG_FILE_NAME = "pikit-config.json"
    }
}

/**
 * Writing and reading a `.zip` that holds a selection of the app's own data.
 *
 * ## Why a zip, and why not a directory
 *
 * The export leaves the app: it is written to a location the user picks through the
 * system's file picker, so it has to be one file. A zip is the format the platform
 * already reads without a library — `java.util.zip`, the same API
 * `BootstrapInstaller` unpacks the runtime with — and one a user can open to see
 * what is inside, which matters more than it sounds: half of this class exists to
 * keep secrets *out* of the archive, and the answer to "is my key in there" should
 * not require trusting the app that made it.
 *
 * ## What is not in it
 *
 * Nothing under `usr/`, `usr-staging/` or the extracted half of `$HOME`: that is the
 * runtime image, hundreds of megabytes of it, rebuilt from the APK on the next
 * launch. The same for `runtime-revision.txt`, the first-install log and pi's own
 * catalogue cache, which are each either re-derived or re-downloaded. A backup
 * carries what the user made and would have to make again.
 *
 * ## The two halves, and why they are not symmetric
 *
 * Files are copied byte for byte. Preferences are read and written through
 * `SharedPreferences` instead, and that is not a shortcut: the XML file is not the
 * source of truth while the process runs — every value the app reads comes from an
 * in-memory copy — so writing it behind the app's back would change nothing until
 * the next launch, and writing through the API is what makes a restore take effect
 * at once. The preference files therefore travel as one typed JSON document each,
 * and [encodePreferences] / [decodePreferences] are the pair that keeps the types.
 */
internal class BackupArchive(
    private val preferences: PreferenceStore,
    private val paths: BackupPaths,
) {

    /**
     * Everything that is one file inside the archive.
     *
     * [entry] is the path *inside* the zip and the path a restore reads it back
     * from, so the two are one spelling: a restore that mapped entries by hand would
     * be a second list to keep in step with this one, and the failure it would
     * produce — a file restored to the wrong place, silently — is the one thing a
     * backup must not do.
     */
    private val files: List<FileItem> = listOf(
        FileItem(BackupCategory.MODELS, "pi/pikit-config.json", paths.configFile, Redaction.PROFILE),
        FileItem(BackupCategory.MODELS, "pi/models.json", File(paths.piConfigDir, "models.json")),
        FileItem(BackupCategory.MODELS, "pi/settings.json", File(paths.piConfigDir, "settings.json")),
        FileItem(BackupCategory.MODELS, "pi/web-search.json", File(paths.piConfigDir, WEB_SEARCH_FILE), Redaction.WEB_SEARCH),
        FileItem(BackupCategory.AGENT_PROMPT, "agent/AGENTS.md", paths.agentsPromptFile),
    )

    /**
     * Everything that is a whole tree.
     *
     * A tree is walked without following symlinks: `$HOME/workspace` may hold one
     * into the user's `~/storage` farm, which is a link to the whole phone, and a
     * backup that followed it would be a backup of `/sdcard`.
     */
    private val trees: List<TreeItem> = listOf(
        TreeItem(BackupCategory.CONVERSATIONS, "conversations", paths.sessionDir),
        TreeItem(BackupCategory.WORKSPACE, "workspace", paths.workspace),
        TreeItem(BackupCategory.HTML_EXPORTS, "html-exports", paths.exportDir),
    )

    // ------------------------------------------------------------------ write

    /**
     * Writes the selected categories to [out].
     *
     * @param includeApiKeys false strips every secret the archive would have carried,
     *   so the file can be handed to someone else. What that means per file is
     *   [Redaction]'s business, and the manifest records the answer so the reader is
     *   not left to infer it from an empty field.
     * @param onProgress called with the number of entries written so far. A
     *   workspace backup is thousands of files, and a dead button is
     *   indistinguishable from a slow one.
     * @return how many entries were written, the manifest included.
     */
    fun write(
        out: OutputStream,
        categories: Set<BackupCategory>,
        includeApiKeys: Boolean,
        onProgress: (Int) -> Unit = {},
    ): Int {
        var written = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            val manifest = BackupManifest(
                app = BuildConfig.VERSION_NAME,
                createdAt = isoNow(),
                categories = BackupCategory.entries.filter { it in categories }.map { it.id },
                apiKeys = includeApiKeys,
            )
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
            zip.write(DOCUMENT_JSON.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            onProgress(++written)

            for (preference in PREFERENCE_FILES) {
                if (preference.category !in categories) continue
                val values = preferences.read(preference.name)
                val excluded = if (includeApiKeys) emptySet() else preference.secretKeys
                val document = encodePreferences(values, excluded)
                zip.putNextEntry(ZipEntry("$PREFERENCES_DIR/${preference.name}.json"))
                zip.write(document.toByteArray())
                zip.closeEntry()
                onProgress(++written)
            }

            for (item in files) {
                if (item.category !in categories) continue
                if (!item.source.isFile) continue
                zip.putNextEntry(entryFor(item.entry, item.source))
                zip.write(exported(item, includeApiKeys))
                zip.closeEntry()
                onProgress(++written)
            }

            for (tree in trees) {
                if (tree.category !in categories) continue
                if (!tree.source.isDirectory) continue
                walk(tree.source) { file, relative ->
                    zip.putNextEntry(entryFor("${tree.entry}/$relative", file))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    onProgress(++written)
                }
            }
        }
        return written
    }

    /**
     * An entry that carries [source]'s modification time.
     *
     * The time is not decoration: it is what the history list **sorts and dates** a
     * conversation by, because a transcript's own date is the file's. An archive that
     * did not carry it restored every conversation with the mtime of the extraction —
     * a list where the whole history is timestamped a minute ago, in the order the
     * walk happened to visit it. The rest of the file has to survive the trip the same
     * way, and a workspace file with no date is a file the agent reads as recently
     * touched.
     *
     * `ZipEntry.setTime` rather than `setLastModifiedTime`: the portable pair
     * (`setTime`/`getTime`) is what every reader of a zip understands, and the extended
     * timestamp extra field the Java-8 API writes is not something Android's own reader
     * is required to act on.
     */
    private fun entryFor(name: String, source: File): ZipEntry = ZipEntry(name).also { entry ->
        val modified = source.lastModified()
        // Zero means "unknown", and writing it would date the file 1980 rather than
        // leaving the reader to decide.
        if (modified > 0) entry.setTime(modified)
    }

    /**
     * The bytes one [FileItem] contributes.
     *
     * The file is copied **verbatim** unless a secret actually has to come out of it,
     * and that is deliberate: these files are pi's, the user is invited to edit them
     * by hand, and a round trip through this app's JSON encoder would return a
     * reformatted file with every comment in it gone. Re-encoding is the price of
     * redaction and is paid only when redaction was asked for.
     */
    private fun exported(item: FileItem, includeApiKeys: Boolean): ByteArray {
        val text = runCatching { item.source.readText() }.getOrNull() ?: return ByteArray(0)
        if (includeApiKeys || item.redaction == Redaction.NONE) return text.toByteArray()
        return redacted(text, item.redaction)?.toByteArray() ?: text.toByteArray()
    }

    private fun redacted(text: String, how: Redaction): String? = runCatching {
        val root = Json.parseToJsonElement(text)
        val stripped = when (how) {
            Redaction.PROFILE -> stripProfileKeys(root)
            Redaction.WEB_SEARCH -> stripSearchKeys(root)
            Redaction.NONE -> root
        }
        DOCUMENT_JSON.encodeToString(JsonElement.serializer(), stripped) + "\n"
    }.getOrNull()

    /** Every profile's own `apiKey`, and nothing else — the shape [ModelProfile] declares. */
    private fun stripProfileKeys(root: JsonElement): JsonElement {
        val document = root.jsonObject
        val profiles = document["profiles"]?.jsonArray ?: return root
        val stripped = buildJsonArray {
            profiles.forEach { profile ->
                val entry = profile.jsonObject.toMutableMap()
                if (entry.containsKey("apiKey")) entry["apiKey"] = JsonPrimitive("")
                add(JsonObject(entry))
            }
        }
        return JsonObject(document.toMutableMap().apply { this["profiles"] = stripped })
    }

    /**
     * Every key in the web-access extension's file whose *name* says it is one.
     *
     * Matched on the suffix rather than against a list of the field names: the file
     * is the extension's, the extension is replaced by a newer one with new providers
     * in it, and a list here would be a list that silently stopped covering the file.
     * `braveApiKey`, `tavilyApiKey` and the rest all end the same way, and the one
     * field that does not — xAI's `Token`-not-`ApiKey`, which the options table says
     * so about — is matched by `ApiToken`.
     */
    private fun stripSearchKeys(root: JsonElement): JsonElement = rewrite(root) { key, value ->
        if (key.endsWith("ApiKey") || key.endsWith("ApiToken")) JsonPrimitive("") else value
    }

    private fun rewrite(element: JsonElement, transform: (String, JsonElement) -> JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) -> transform(key, rewrite(value, transform)) },
            )

            else -> element
        }

    // ------------------------------------------------------------------- read

    /**
     * The manifest of an archive, or null when [file] is not one.
     *
     * Read before anything is written, so a file that is not a PiKit backup — or one
     * written by a format this build does not know — is refused with the user still
     * looking at the picker they came from, rather than halfway through a restore.
     */
    fun manifest(file: File): BackupManifest? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(BACKUP_MANIFEST_ENTRY) ?: return@use null
            val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
            DOCUMENT_JSON.decodeFromString(BackupManifest.serializer(), text)
                .takeIf { it.format == BACKUP_FORMAT }
        }
    }.getOrNull()

    /**
     * Unpacks the selected categories of [file] into [staging].
     *
     * Staged rather than applied as it streams: the entries of a zip can be read in
     * any order and a truncated one fails in the middle, so applying as we go would
     * leave a half-restored app for a download that did not finish. The staging
     * directory is emptied into place only once every selected entry is out.
     *
     * @return the number of entries unpacked, and the preference documents — which
     *   cannot be staged, because a `SharedPreferences` is not a file this app may
     *   write.
     */
    fun extract(file: File, categories: Set<BackupCategory>, staging: File): Extraction {
        var entries = 0
        val documents = LinkedHashMap<String, JsonObject>()

        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            for (name in names) {
                val entry = zip.getEntry(name)
                if (entry == null || entry.isDirectory) continue
                if (name == BACKUP_MANIFEST_ENTRY) continue

                val preference = preferenceFile(name)
                if (preference != null) {
                    // Guarded like every other entry, and it was not at first: a
                    // preference document has no directory to be matched against, so
                    // the category check the file and tree entries go through had no
                    // equivalent here and an import of *one* category applied every
                    // preference in the archive — restoring the storage policy and
                    // the language on a request that named neither.
                    if (preference.category !in categories) continue
                    val text = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                    runCatching { Json.parseToJsonElement(text).jsonObject }
                        .onSuccess { documents[preference.name] = it }
                    entries++
                    continue
                }

                val category = categoryOf(name) ?: continue
                if (category !in categories) continue

                val target = stagedTarget(staging, name) ?: continue
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                // The date is the file's, not the extraction's — see [entryFor]. Set
                // here rather than only after the move, so the staging tree is already
                // right and the moved file inherits it.
                entry.time.takeIf { it > 0 }?.let { target.setLastModified(it) }
                entries++
            }
        }
        return Extraction(entries = entries, documents = documents)
    }

    /**
     * Where an entry lands under [staging], or null when it must not be written at
     * all.
     *
     * The zip-slip guard: an entry name is a string the archive chose, so `..` in one
     * is an archive that writes outside the staging directory — into
     * `shared_prefs/`, or into `/data/data/<pkg>/` itself. The check is on the
     * canonical path, so a name that resolves out through a symlink is caught too,
     * and it is the shape `BootstrapInstaller.safeTarget` uses for the runtime
     * image's own entries.
     */
    private fun stagedTarget(staging: File, name: String): File? {
        if (name.startsWith('/') || name.contains('\u0000')) return null
        val root = staging.canonicalFile
        val canonical = File(staging, name).canonicalFile
        return canonical.takeIf {
            it.path == root.path || it.path.startsWith(root.path + File.separator)
        }
    }

    /**
     * Moves every staged entry of the selected categories into place.
     *
     * The staging tree mirrors the archive exactly, so an entry's path relative to
     * the staging root *is* its name in the archive, and the two tables that put it
     * there are the ones that read it back. That is the whole reason a restore cannot
     * put a file somewhere the backup did not take it from.
     *
     * @return how many entries were moved.
     */
    fun applyAll(staging: File, categories: Set<BackupCategory>): Int {
        var moved = 0
        walk(staging) { file, relative ->
            val category = categoryOf(relative) ?: return@walk
            if (category !in categories) return@walk
            if (move(relative, file)) moved++
        }
        return moved
    }

    /**
     * Applies one preference document through the API the app itself reads.
     *
     * Returns nothing, and that is on purpose: the only caller counts what a restore
     * put back, and it counts *archive entries* — one per document, one per file —
     * so that the number it reports is the same number the export reported for the
     * same archive. Counting the keys inside a document instead made it say 15 for
     * an archive it had called 9.
     */
    fun applyPreferences(name: String, document: JsonObject) {
        preferences.apply(name, decodePreferences(document))
    }

    /**
     * Overwrite in place, and **nothing is ever deleted**: a restore that removed the
     * current workspace to make room for an older one would be a tap that destroys a
     * day's work with the file picker's own three taps as its whole warning. A
     * restored file replaces the file of that name; every other file stays.
     */
    private fun move(name: String, staged: File): Boolean {
        val target = destinationOf(name) ?: return false
        val modified = staged.lastModified()
        target.parentFile?.mkdirs()
        // A rename within one filesystem is free, and the staging directory is under
        // the same `filesDir` as every destination; a copy is the fallback for the
        // case where it is not.
        val moved = if (staged.renameTo(target)) {
            true
        } else {
            runCatching {
                staged.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                staged.delete()
                true
            }.getOrDefault(false)
        }
        // A copy carries no mtime, so the date is re-applied rather than assumed to
        // have survived the move — see [entryFor] for what depends on it. Redundant on
        // the rename path, where it is the same inode, and cheap enough not to be
        // worth a branch that would be wrong exactly when the copy path is taken.
        if (moved && modified > 0) target.setLastModified(modified)
        return moved
    }

    /** The archive path a category's entries live under, or null for an unrelated entry. */
    private fun categoryOf(name: String): BackupCategory? {
        files.firstOrNull { it.entry == name }?.let { return it.category }
        return trees.firstOrNull { name.startsWith("${it.entry}/") }?.category
    }

    private fun destinationOf(name: String): File? {
        files.firstOrNull { it.entry == name }?.let { return it.source }
        val tree = trees.firstOrNull { name.startsWith("${it.entry}/") } ?: return null
        val relative = name.removePrefix("${tree.entry}/")
        if (relative.isEmpty()) return null
        return File(tree.source, relative)
    }

    private fun preferenceFile(name: String): PreferenceFile? {
        if (!name.startsWith("$PREFERENCES_DIR/") || !name.endsWith(".json")) return null
        val stem = name.removePrefix("$PREFERENCES_DIR/").removeSuffix(".json")
        return PREFERENCE_FILES.firstOrNull { it.name == stem }
    }

    /**
     * An empty staging directory to unpack into.
     *
     * Emptied before it is used, and through `SafeDelete` rather than
     * `deleteRecursively`: a restore killed halfway leaves a tree here, and a second
     * restore that unpacked on top of it would apply files from a *different*
     * archive — the previous one — under the names the two happen to share.
     */
    fun freshStaging(): File {
        val staging = File(paths.filesDir, STAGING_DIR_NAME)
        SafeDelete.recursively(staging, paths.filesDir)
        staging.mkdirs()
        return staging
    }

    companion object {
        /** The production construction: the app's own preferences, the runtime's paths. */
        fun of(context: Context, env: TermuxEnv): BackupArchive =
            BackupArchive(ContextPreferences(context), BackupPaths.of(env))

        /** Suggested file name for the picker's "create a document". */
        fun suggestedName(now: Long = System.currentTimeMillis()): String {
            val format = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            return "pikit-backup-${format.format(java.util.Date(now))}.zip"
        }

        /**
         * One preference file's values, as a JSON document.
         *
         * Each value is written as `{"type": …, "value": …}` rather than as a bare
         * JSON value, because the type is not recoverable from the value — see
         * [PrefValue]. [excluded] drops keys entirely rather than blanking them: an
         * archive that claims to carry no keys should have none in it rather than
         * empty ones, and a restore that cannot write a secret cannot leak one.
         */
        fun encodePreferences(all: Map<String, *>, excluded: Set<String> = emptySet()): String {
            val document = buildJsonObject {
                all.forEach { (key, value) ->
                    if (key in excluded) return@forEach
                    val entry = when (value) {
                        is Boolean -> typed("boolean", JsonPrimitive(value))
                        is Int -> typed("int", JsonPrimitive(value))
                        is Long -> typed("long", JsonPrimitive(value))
                        is Float -> typed("float", JsonPrimitive(value))
                        is String -> typed("string", JsonPrimitive(value))
                        is Set<*> -> typed(
                            "stringSet",
                            buildJsonArray {
                                // Sorted, so an archive's bytes do not depend on the
                                // order a `getStringSet` happens to hand back.
                                value.filterIsInstance<String>().sorted()
                                    .forEach { add(JsonPrimitive(it)) }
                            },
                        )
                        // A type `SharedPreferences` does not hold. Skipped rather
                        // than stringified: a value the platform would refuse to read
                        // back is not a value worth carrying.
                        else -> return@forEach
                    }
                    put(key, entry)
                }
            }
            return DOCUMENT_JSON.encodeToString(JsonObject.serializer(), document)
        }

        /** The reverse of [encodePreferences]; an entry of an unknown type is dropped. */
        fun decodePreferences(document: JsonObject): Map<String, PrefValue> {
            val values = LinkedHashMap<String, PrefValue>()
            document.forEach { (key, element) ->
                val entry = element as? JsonObject ?: return@forEach
                val value = entry["value"] ?: return@forEach
                val primitive = value as? JsonPrimitive
                val decoded = when (entry["type"]?.jsonPrimitive?.contentOrNull) {
                    "boolean" -> primitive?.booleanOrNull?.let(PrefValue::Flag)
                    "int" -> primitive?.intOrNull?.let(PrefValue::Number)
                    "long" -> primitive?.longOrNull?.let(PrefValue::Wide)
                    "float" -> primitive?.floatOrNull?.let(PrefValue::Fraction)
                    "string" -> primitive?.contentOrNull?.let(PrefValue::Text)
                    "stringSet" -> (value as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?.toSet()
                        ?.let(PrefValue::TextSet)

                    else -> null
                }
                if (decoded != null) values[key] = decoded
            }
            return values
        }

        private fun typed(type: String, value: JsonElement): JsonObject = buildJsonObject {
            put("type", JsonPrimitive(type))
            put("value", value)
        }

        private fun isoNow(): String {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return format.format(java.util.Date())
        }

        /**
         * The encoder every document in an archive is written with.
         *
         * `encodeDefaults` because the manifest is a table of contents and a reader
         * has to be able to see every field in it. kotlinx omits a property equal to
         * its default by default, which would leave `apiKeys` out of exactly the
         * archive that *has* the keys — the field exists so that a reader can tell
         * two archives apart without opening them, and one that is present only when
         * the answer is "no" is worse than no field at all.
         */
        private val DOCUMENT_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        private const val PREFERENCES_DIR = "prefs"

        /** Where a selected archive is unpacked before any of it is applied. */
        private const val STAGING_DIR_NAME = "backup-staging"

        /** pi's own web-access configuration; the store that owns it spells it too. */
        private const val WEB_SEARCH_FILE = "web-search.json"

        /**
         * The preference files a category is made of, and the keys that are a secret
         * inside each.
         *
         * Two are deliberately absent. `pikit_model_catalogue` holds one deadline,
         * `due_at`, which this app re-decides within four hours of any launch, so a
         * restored one would either do nothing or make the app skip a refresh it
         * should have made. `pikit_agent_session` holds which conversation was open,
         * which is where the reader happened to be rather than a choice — and on a
         * restored install it names a file from the archive's own `pi-sessions` that
         * may not be there at all.
         */
        private val PREFERENCE_FILES = listOf(
            PreferenceFile("pikit_settings", BackupCategory.SETTINGS, secretKeys = setOf("api_key")),
            PreferenceFile("pikit_storage", BackupCategory.SETTINGS),
            PreferenceFile("pikit_session_pins", BackupCategory.CONVERSATIONS),
        )
    }
}

/** What an archive carries, once it has been unpacked into staging. */
internal data class Extraction(
    val entries: Int,

    /** Preference name to document; applied through the API, not staged. */
    val documents: Map<String, JsonObject>,
)

/**
 * The preference files, as `SharedPreferences` has them.
 *
 * The one place in this class that touches Android, and it is deliberately the
 * thinnest piece: what is worth testing is the document and what a restore does
 * with it, not the two calls that get it there.
 */
private class ContextPreferences(context: Context) : PreferenceStore {

    private val appContext = context.applicationContext

    override fun read(name: String): Map<String, *> =
        appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all

    override fun apply(name: String, values: Map<String, PrefValue>) {
        val editor = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        values.forEach { (key, value) -> write(editor, key, value) }
        editor.apply()
    }

    private fun write(editor: SharedPreferences.Editor, key: String, value: PrefValue) {
        when (value) {
            is PrefValue.Text -> editor.putString(key, value.value)
            is PrefValue.Flag -> editor.putBoolean(key, value.value)
            is PrefValue.Number -> editor.putInt(key, value.value)
            is PrefValue.Wide -> editor.putLong(key, value.value)
            is PrefValue.Fraction -> editor.putFloat(key, value.value)
            is PrefValue.TextSet -> editor.putStringSet(key, value.value)
        }
    }
}

/**
 * One file of the archive.
 *
 * [redaction] is the recipe for taking the secret out of it, and [Redaction.NONE] is
 * not the same as "has no secret": `models.json` names the *environment variable*
 * that holds the key (`PIKIT_API_KEY`) rather than the key itself, so blanking its
 * `apiKey` would produce a file the custom endpoint cannot resolve while removing
 * nothing worth protecting.
 */
private data class FileItem(
    val category: BackupCategory,
    val entry: String,
    val source: File,
    val redaction: Redaction = Redaction.NONE,
)

private enum class Redaction { NONE, PROFILE, WEB_SEARCH }

/** One tree of the archive: a directory copied whole, and read back whole. */
private data class TreeItem(
    val category: BackupCategory,
    val entry: String,
    val source: File,
)

private data class PreferenceFile(
    val name: String,
    val category: BackupCategory,
    val secretKeys: Set<String> = emptySet(),
)

/**
 * Walks [root] depth-first, calling [onFile] with each regular file and its path
 * relative to [root].
 *
 * Symlinks are skipped, never followed and never stored: a link out of the tree is a
 * link to something that is not part of this backup — `$HOME/workspace` may hold one
 * into the user's `~/storage` — and a zip has no way to say "this is a link" that the
 * platform's own reader would honour.
 *
 * The path it reports is **relative and separator-normalised**, and both halves of
 * that are load-bearing. An entry name inside a zip is always `/`-separated, so a
 * relative path built by stripping a prefix off `File.path` carries the platform's
 * own separator and produces a name like `conversations/C:\…\source\one.jsonl` — which
 * `destinationOf` then reads back as a nested path under the tree, silently. That is
 * exactly what a JVM test caught here, and it is a bug Android alone would never have
 * shown, because there the two separators are the same character.
 */
private fun walk(root: File, onFile: (File, String) -> Unit) {
    val stack = ArrayDeque(listOf(root))
    while (stack.isNotEmpty()) {
        val directory = stack.removeLast()
        for (child in directory.listFiles().orEmpty()) {
            // `Files.isSymbolicLink` rather than comparing `canonicalPath` with
            // `absolutePath`: that comparison is also true of any path with a
            // short-name component, which would make this walk skip every file on a
            // machine whose home directory is spelled the 8.3 way — and the unit
            // tests run on such a machine.
            if (runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }.getOrDefault(false)) {
                continue
            }
            when {
                child.isDirectory -> stack.addLast(child)
                child.isFile -> onFile(child, child.relativeTo(root).invariantSeparatorsPath)
            }
        }
    }
}
