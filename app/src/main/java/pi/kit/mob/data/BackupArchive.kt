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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import pi.kit.mob.BuildConfig
import pi.kit.mob.env.AgentContext
import pi.kit.mob.env.PermissionPolicy
import pi.kit.mob.env.SafeDelete
import pi.kit.mob.env.StorageAccess
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
     * How this install behaves: the app's own preferences, and the agent's instructions.
     *
     * The preferences are everything a settings page writes — theme, language, text size,
     * launcher icon, the avatars' pictures and names, the chat personas, the default
     * workspace and the list of added ones, the permission tier, the agent mode, the
     * thinking levels, and the storage grant (a setting of the same store rather than a
     * group of its own).
     *
     * The **two `AGENTS` halves** are here too, and they used to be a category of their own.
     * That category was one switch for two small files, and a reinstall that skipped it
     * restored an agent answering in a voice the reader never chose — the instructions are
     * part of "this install", exactly like the theme. An archive written before the merge
     * names `agent-prompt`, which [fromId] still resolves (`LEGACY_IDS`), so an old backup
     * restores into this switch.
     */
    SETTINGS("settings"),

    /**
     * The model profiles — provider, model, key — and pi's own directory,
     * `$HOME/.pi/agent`, from which the profiles are projected into `models.json` and
     * `settings.json` at every launch.
     *
     * The directory travels *whole*, minus the files the app or the runtime image
     * derives, because a whitelist of the four files this build knows is a backup that
     * silently drops the extension the user installed there or a config a newer pi reads
     * (see [BackupArchive]). That is also why the page calls it "models, keys and pi's
     * config" rather than "model profiles": what is in it is not all the app's.
     */
    MODELS("models"),

    /**
     * Every saved conversation: one `.jsonl` per talk under `files/pi-sessions`, the copies
     * of the files their prompts named (`files/attachments`), and which of them are pinned.
     *
     * The pins travel with the conversations rather than beside them: a pin is a
     * property of a conversation, and a set of names with no conversations to point
     * at is a list of nothing. The attachment copies travel here for the same reason —
     * a prompt names one by absolute path, so a transcript without its copies is a
     * history whose attachments all point at nothing. The conversation that was *open*
     * when the app last closed is deliberately not here — it is where the reader
     * happened to be, not something they chose, and a restore that reopened it would be
     * reopening a file from another install.
     */
    CONVERSATIONS("conversations"),

    /**
     * The agent's working directory — the built-in `$HOME/workspace` and every directory
     * the reader added, `$HOME` itself among them.
     *
     * [EXTRA_WORKSPACES_DIR] spells an added workspace as `workspaces/<its path relative
     * to $HOME>`, and `$HOME` is the one workspace that relative path cannot name: it
     * would be the empty string. It therefore travels under an entry of its own,
     * `home/…`, with the parts of `$HOME` other categories own skipped rather than
     * duplicated — see [HOME_TREE_DIR].
     */
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
         * the user has to carry somewhere. The agent's instructions ride in [SETTINGS]
         * and are therefore on by default, which is the point of folding them in.
         */
        val DEFAULT: Set<BackupCategory> = setOf(SETTINGS, MODELS, CONVERSATIONS)

        /**
         * Ids a released build wrote and this one no longer has, mapped to the category
         * that now carries their entries.
         *
         * Read on the way *in*: a manifest is stored json, so the ids in an archive are
         * whatever the build that wrote it knew. Dropping one would make an old backup
         * restore without the agent's instructions and report success — the failure this
         * map exists to prevent.
         */
        private val LEGACY_IDS: Map<String, BackupCategory> = mapOf("agent-prompt" to SETTINGS)

        fun fromId(id: String): BackupCategory? =
            entries.firstOrNull { it.id == id } ?: LEGACY_IDS[id]
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
    /**
     * `$HOME/.pi/agent` — pi's own directory.
     *
     * It is one root rather than four file paths because the MODELS category carries it
     * as a tree: the four files this build names keep their own entries (`pi/models.json`
     * and the rest), and everything else a user or a newer pi puts there travels under
     * `pi/agent/` without anyone having to list it first.
     */
    val piConfigDir: File,
    val sessionDir: File,
    val workspace: File,
    val exportDir: File,
    /**
     * `$HOME`, which is where a workspace lives unless it was added from elsewhere.
     *
     * A workspace's entry inside the archive names the path it came from relative to
     * this directory, so an archive taken on an install with `$HOME/projects/app` in
     * its list can be restored on one that has never heard of it.
     */
    val home: File = filesDir,
    /**
     * Workspaces beyond the built-in one, as their directories.
     *
     * The built-in `$HOME/workspace` is [workspace] and is always carried. These are the
     * folders the user added in the composer's workspace menu — without them a backup
     * would carry the built-in workspace and silently leave every other project on the
     * phone behind, which is the one failure a backup must not have.
     */
    val workspaces: List<File> = emptyList(),
) {
    /** `files/pikit-config.json` — the profiles and the keys in them. */
    val configFile: File get() = File(filesDir, CONFIG_FILE_NAME)

    /**
     * The two documents `AGENTS.md` is composed from.
     *
     * The composed file is *derived* — the app writes it on every launch — so an archive
     * that carried it would restore a document with no halves, and the migration that
     * splits a pre-split file would then treat the whole default text as the reader's own.
     * The halves are what a backup has to carry; the archive's restore recomposes.
     */
    val agentsAuthorFile: File get() = File(piConfigDir, AgentContext.AUTHOR_FILE_NAME)
    val agentsUserFile: File get() = File(piConfigDir, AgentContext.USER_FILE_NAME)

    /**
     * `files/avatars` — the two custom avatar pictures, when the reader has any.
     *
     * Driven from [AvatarStore] rather than spelled again, because the settings that
     * name the pictures hold *absolute paths* into this directory: a backup that put
     * them back anywhere else would restore two settings pointing at files that are
     * not there, which reads as "my avatar was lost" with no error to explain it.
     */
    val avatarDir: File get() = AvatarStore.directory(filesDir)

    /**
     * `files/attachments` — the copies of the files prompts were sent with.
     *
     * Driven from [AttachmentStore] rather than spelled again, for the reason the avatars
     * are: the absolute paths are written into the conversations themselves, so a backup
     * that put them back anywhere else would restore transcripts naming files that are not
     * there. They travel with [BackupCategory.CONVERSATIONS] because that is what they are
     * part of — the path is the message's own furniture.
     */
    val attachmentDir: File get() = AttachmentStore.directory(filesDir)

    companion object {
        fun of(env: TermuxEnv, workspaces: List<File> = emptyList()): BackupPaths = BackupPaths(
            filesDir = env.filesDir,
            piConfigDir = env.piConfigDir,
            sessionDir = env.sessionDir,
            workspace = env.workspace,
            exportDir = env.exportDir,
            home = env.home,
            // The built-in one is `workspace` below; naming it twice would put the same
            // files in the archive under two entries.
            workspaces = workspaces.filter { it.absolutePath != env.workspace.absolutePath },
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
 * Nothing under `usr/`, `usr-staging/`, `usr-content-staging/` or the extracted half of
 * `$HOME`: that is the runtime image, hundreds of megabytes of it, rebuilt from the APK on
 * the next launch. The same for `runtime-revision.txt`, `content-revision.txt`, the
 * first-install log and pi's own catalogue cache (`models-store.json`), which are each
 * either re-derived or re-downloaded. `$HOME/.pikit` is *derived*: `permission.json` is a
 * projection of the SETTINGS category's `permission_mode`, and `chat-system-prompt.txt` of
 * its chat template — the second is rewritten by the chat launch, and the first is
 * re-derived at the end of a restore because a restore that finds the agent stopped never
 * launches ([BackupManager]). `$HOME/.termux/motd.sh`, the `$HOME/storage` links, the
 * package-repair ledger under `files/pkg-restore/`, the catalogue probe's scratch
 * directory and `files/dns-pins.txt` are install- or device-specific: the last one pins
 * DNS for the network the phone is on, which a restore is not a fact about. Nor
 * `files/chat-pictures`: the transcript's copies of the pictures a prompt was sent with are
 * decoded from the bytes the session file itself carries, so an archive that shipped them
 * would carry the same picture twice and could still put back a copy older than the
 * conversation (§7.3). A backup carries what the user made and would have to make again.
 * `files/attachments` *is* carried, and that is not the same case: a session file names a
 * file attachment by path and holds none of its bytes, so the copy is the only thing a
 * restore could put back — it travels with CONVERSATIONS (§7.1).
 *
 * ## pi's directory is a tree, not four names
 *
 * `$HOME/.pi/agent` is pi's and its extensions', and this app writes only some of it:
 * `models.json` and `settings.json` (merged on every launch), `web-search.json` (the
 * search page's only writer) and the two `AGENTS` halves. A whitelist of those was a
 * backup that silently dropped everything else the user put there — their own pi
 * extension, a config a newer pi reads — so the directory travels as a tree and only the
 * *derived* names are withheld: the composed `AGENTS.md`, the catalogue cache, and the
 * extension copies the runtime image installs (restoring an older
 * `pikit-permissions.ts` would put a policy back that nothing can switch off until the
 * next content update, §14). The four known files keep their shorter entry names, so an
 * archive written before the tree existed is still read by a build that has it, and a
 * build without the tree skips the extra entries instead of refusing the archive — the
 * format number stays 1 because the change is additive.
 *
 * ## `$HOME` is owned by categories, and a workspace inside it inherits that
 *
 * Any directory may be a workspace, `$HOME` and `$HOME/.pi` included, and each part of
 * `$HOME` already belongs to a category: the built-in workspace and the added ones, the
 * HTML exports, pi's directory, the derived `.pikit` and the installer's `.termux`. A
 * workspace tree therefore never carries a second copy of a part another entry owns —
 * and, the half that can bite, never carries one under a *different* treatment. The
 * MODELS entry withholds pi's derived files and redacts its JSON; a workspace over
 * `$HOME/.pi` did neither, so an archive taken that way could restore the retired
 * `pikit-permissions.ts` and have it judge every tool call until the next content
 * update (§14). [withheldInHome] is that rule, stated once and applied to every entry
 * that can reach into pi's directory.
 *
 * A workspace that *is* `$HOME` has no relative name to travel under —
 * [EXTRA_WORKSPACES_DIR] would spell it as `workspaces/` — so it travels as `home/…`,
 * the one workspace the archive used to drop in silence while the menu showed it.
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
        FileItem(BackupCategory.MODELS, "pi/pikit-config.json", paths.configFile, Redaction.SECRETS),
        FileItem(
            BackupCategory.MODELS,
            "pi/models.json",
            File(paths.piConfigDir, "models.json"),
            Redaction.SECRETS,
        ),
        FileItem(
            BackupCategory.MODELS,
            "pi/settings.json",
            File(paths.piConfigDir, "settings.json"),
            Redaction.SECRETS,
        ),
        FileItem(
            BackupCategory.MODELS,
            "pi/web-search.json",
            File(paths.piConfigDir, WEB_SEARCH_FILE),
            Redaction.SECRETS,
        ),
        FileItem(BackupCategory.SETTINGS, "agent/AGENTS.author.md", paths.agentsAuthorFile),
        FileItem(BackupCategory.SETTINGS, "agent/AGENTS.user.md", paths.agentsUserFile),
    )

    /**
     * The added workspaces' `$HOME`-relative paths.
     *
     * Read by [skipHomeTreeOnExport], and for one reason: with `$HOME` *and* a folder
     * inside it both in the list — `~` and `~/projects/app` is a pair the menu allows —
     * the folder would be in the archive twice, once as `workspaces/projects/app/…` and
     * once under `home/…`. A workspace backup is the one that can be hundreds of
     * megabytes, so the second copy is skipped and the folder keeps the entry name it has
     * always had.
     */
    private val addedWorkspaceRoots: List<String> =
        paths.workspaces.mapNotNull { relativeToHome(it) }.filter { it.isNotEmpty() }

    /**
     * Everything that is a whole tree.
     *
     * A tree is walked without following symlinks: `$HOME/workspace` may hold one
     * into the user's `~/storage` farm, which is a link to the whole phone, and a
     * backup that followed it would be a backup of `/sdcard`.
     */
    private val trees: List<TreeItem> = buildList {
        add(TreeItem(BackupCategory.CONVERSATIONS, "conversations", paths.sessionDir))
        // The files those conversations name. A `文件` attachment is sent as the absolute
        // path of an app-made copy, and the transcript draws that path again for as long as
        // the conversation exists — so a restore without the copies is a history whose
        // attachments all point at nothing. They travel in this category rather than one of
        // their own: what a reader ticks here is "my conversations as they were".
        add(
            TreeItem(
                BackupCategory.CONVERSATIONS,
                AttachmentStore.DIRECTORY,
                paths.attachmentDir,
            ),
        )
        // The rest of pi's directory: everything the four entries above do not name.
        // See the class note — a whitelist was a backup that silently lost a user's own
        // extension, and every file pi or an extension adds later needs no entry here.
        add(
            TreeItem(
                BackupCategory.MODELS,
                PI_AGENT_DIR,
                paths.piConfigDir,
                skip = ::skipPiAgentFile,
                redaction = ::piAgentRedaction,
            ),
        )
        add(TreeItem(BackupCategory.WORKSPACE, "workspace", paths.workspace))
        // Every other workspace, under the one directory whose contents an archive may
        // name: `$HOME`. A workspace outside it has no relative path to record, and is
        // left out rather than written under a name a restore could not decode back.
        for (workspace in paths.workspaces) {
            when (val relative = relativeToHome(workspace)) {
                // `$HOME` itself: the one workspace whose relative name is empty, and the
                // one the archive used to drop in silence — see [HOME_TREE_DIR].
                "" -> add(
                    TreeItem(
                        BackupCategory.WORKSPACE,
                        HOME_TREE_DIR,
                        workspace,
                        skip = ::skipHomeTreeOnExport,
                        // The dedupe above is the export's business only — see the two
                        // functions. A restore refuses [skipHomeTree] and nothing else.
                        restoreSkip = ::skipHomeTree,
                    ),
                )
                // Outside `$HOME`: no name a restore could decode back.
                null -> continue

                else -> add(
                    TreeItem(
                        BackupCategory.WORKSPACE,
                        "$EXTRA_WORKSPACES_DIR/$relative",
                        workspace,
                        // A workspace that reaches pi's directory must withhold what the
                        // MODELS entry withholds; see [withheldInHome].
                        skip = workspaceSkip(relative),
                    ),
                )
            }
        }
        add(TreeItem(BackupCategory.HTML_EXPORTS, "html-exports", paths.exportDir))
        add(TreeItem(BackupCategory.SETTINGS, "avatars", paths.avatarDir))
    }

    /**
     * Whether a file under `$HOME/.pi/agent` is carried by an entry of its own, or is
     * derived and must not travel at all.
     *
     * The derived names are load-bearing in both directions, and that is why the same
     * function guards the write and the restore ([destinationOf]): an archive that names
     * a file this build withholds — one taken by an older build, or one crafted to
     * replace the permission policy — must not be able to put it back.
     */
    private fun skipPiAgentFile(relative: String): Boolean =
        relative in PI_AGENT_OWNED_ELSEWHERE ||
            relative in PI_AGENT_DERIVED ||
            // PiKit writes an atomic `.tmp` beside `web-search.json`; a backup taken in
            // that instant carries a half-written file that is not a document.
            relative.endsWith(".tmp")

    /**
     * The redaction for one file of pi's directory.
     *
     * Only JSON can hold a credential, and it is read as text only when a secret is
     * actually there ([redacted] returns null when nothing was taken out), so every
     * other file is copied byte for byte. The four known files have their own entries
     * and their own recipe; this covers what the tree adds — an extension's config, a
     * config a newer pi writes — so the "include the keys" switch cannot be defeated by
     * a file nobody listed.
     */
    private fun piAgentRedaction(relative: String): Redaction =
        if (relative.endsWith(".json", ignoreCase = true)) Redaction.SECRETS else Redaction.NONE

    /**
     * Whether a path **relative to `$HOME`** is one no entry of the archive carries.
     *
     * The rule exists because a workspace may be `$HOME`, `$HOME/.pi` or
     * `$HOME/.pi/agent` — the picker refuses only "not a directory" — and a workspace
     * tree is a plain directory copy. Without this, adding `.pi` to the workspace menu
     * turned the archive's withheld names into ordinary files: the composed `AGENTS.md`,
     * pi's catalogue cache, and the two extension copies the runtime image installs,
     * the last of which pi loads on every tool call. An archive that restored
     * `pikit-permissions.ts` from one would judge tool calls by an older policy until
     * the next content update, and the retired `pi-safety-guard.ts` would judge them by
     * two (§14). One rule for every entry that can reach into pi's directory, rather
     * than a second list beside the first.
     */
    private fun withheldInHome(relative: String): Boolean {
        val prefix = "$PI_AGENT_SUBDIR/"
        if (!relative.startsWith(prefix)) return false
        return skipPiAgentFile(relative.removePrefix(prefix))
    }

    /**
     * What a workspace tree withholds, given its own `$HOME`-relative path.
     *
     * A workspace elsewhere under `$HOME` withholds nothing: its `.pi` is the reader's
     * own project folder, not pi's. A workspace *at* or *inside* pi's directory
     * withholds the names [withheldInHome] names, and the rebasing is the whole
     * function — `agent/extensions/x.ts` inside `$HOME/.pi` is
     * `.pi/agent/extensions/x.ts` to the rule.
     */
    private fun workspaceSkip(base: String): (String) -> Boolean = { relative ->
        withheldInHome("$base/$relative")
    }

    /**
     * What the `$HOME` tree withholds, on **both** directions: the parts of `$HOME` that
     * another category owns.
     *
     * Each name here is carried by an entry of its own — the built-in workspace, the HTML
     * exports, pi's directory, the derived `.pikit`, the installer's `.termux`, the
     * `storage` links — so carrying it here as well would put the same file in the zip
     * twice, and under a *different* treatment: pi's JSON is redacted by MODELS and its
     * derived files are withheld, where a plain workspace copy would do neither. `.pi` is
     * skipped whole for that reason.
     *
     * This is the rule a restore applies, so it is stated with nothing about *this*
     * install's workspace list: an archive taken where `$HOME` was a workspace has
     * `home/projects/app/…` in it, and a phone that happens to have that folder as a
     * workspace of its own must still get the file.
     */
    private fun skipHomeTree(relative: String): Boolean {
        if (relative.substringBefore('/') in HOME_OWNED_ELSEWHERE) return true
        return withheldInHome(relative)
    }

    /**
     * What the `$HOME` tree leaves out of an **export** — [skipHomeTree] plus the folders
     * that travel under a name of their own.
     *
     * `~` and `~/projects/app` together are a pair the workspace menu allows, and the
     * project would otherwise be in the archive twice. That is export-side only: the
     * dedupe reads the *source* install's list, and applying it on the way back in would
     * drop an archive's `home/projects/app/…` on a phone whose list differs — silently,
     * which is the failure this whole class exists to avoid.
     */
    private fun skipHomeTreeOnExport(relative: String): Boolean =
        skipHomeTree(relative) ||
            addedWorkspaceRoots.any { root -> relative == root || relative.startsWith("$root/") }

    /**
     * `foo/bar` for a directory under `$HOME`, or null for one somewhere else.
     *
     * Separator-normalised, for the reason `walk` states: an entry name inside a zip is
     * always `/`-separated, and a path built by string arithmetic off `File.path` carries
     * the platform's own separator — which on this repository's JVM tests is `\`, and on
     * Android is `/`, so the bug would only ever show on one of the two.
     */
    private fun relativeToHome(file: File): String? {
        val separator = File.separator
        val home = paths.home.absoluteFile.path.trimEnd(File.separatorChar)
        val absolute = file.absoluteFile.path
        // `$HOME` itself answers the **empty** relative name, and that is a value rather
        // than an absence: it is the one workspace that travels under [HOME_TREE_DIR]
        // instead of [EXTRA_WORKSPACES_DIR]. Null therefore keeps meaning "outside
        // `$HOME`", which is the one case with no entry name at all.
        if (absolute == home) return ""
        if (!absolute.startsWith(home + separator)) return null
        return absolute.removePrefix(home + separator)
            .replace(File.separatorChar, '/')
            .trimEnd('/')
    }

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
                zip.write(exported(item.source, item.redaction, includeApiKeys))
                zip.closeEntry()
                onProgress(++written)
            }

            for (tree in trees) {
                if (tree.category !in categories) continue
                if (!tree.source.isDirectory) continue
                walk(tree.source) { file, relative ->
                    // The name is the one the restore decodes, so a skipped name is
                    // skipped by both directions — see [skipPiAgentFile].
                    if (tree.skip(relative)) return@walk
                    zip.putNextEntry(entryFor("${tree.entry}/$relative", file))
                    val how = tree.redaction(relative)
                    if (how == Redaction.NONE) {
                        file.inputStream().use { it.copyTo(zip) }
                    } else {
                        zip.write(exported(file, how, includeApiKeys))
                    }
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
     * The bytes one file contributes.
     *
     * The file is copied **verbatim** unless a secret actually has to come out of it, and
     * that is deliberate: these files are pi's, the user is invited to edit them by hand,
     * and a round trip through this app's JSON encoder would return a reformatted file
     * with every comment in it gone. Re-encoding is the price of redaction and is paid
     * only when redaction was asked for *and* found something to take out — [redacted]
     * answers null when the document is unchanged, which is what keeps that promise for a
     * file the reader formatted themselves.
     */
    private fun exported(source: File, redaction: Redaction, includeApiKeys: Boolean): ByteArray {
        val text = runCatching { source.readText() }.getOrNull() ?: return ByteArray(0)
        if (includeApiKeys || redaction == Redaction.NONE) return text.toByteArray()
        return redacted(text, redaction)?.toByteArray() ?: text.toByteArray()
    }

    private fun redacted(text: String, how: Redaction): String? = runCatching {
        val root = Json.parseToJsonElement(text)
        val stripped = when (how) {
            Redaction.SECRETS -> stripSecrets(root)
            Redaction.NONE -> root
        }
        // Nothing was taken out, so the file keeps its own bytes, its own indentation and
        // its own ordering. Without this test a `web-search.json` with no provider key
        // was re-encoded on every archive with the switch off, which is exactly the
        // reformatting the byte-for-byte half of the design promises not to do.
        if (stripped == root) return@runCatching null
        DOCUMENT_JSON.encodeToString(JsonElement.serializer(), stripped) + "\n"
    }.getOrNull()

    /**
     * Every credential-shaped field in a pi JSON document, blanked.
     *
     * One recipe for the four files rather than a named one each, because they are all
     * pi's and all hand-editable, and the exact field names are not the thing to pin: a
     * provider names its key `apiKey`, the web-access extension's table ends every one of
     * them in `ApiKey` and xAI's in `ApiToken`, and a newer provider adds a field nobody
     * listed. The suffix match is the same rule the extension's own options table implies
     * — and unlike a list here it cannot stop covering a file.
     *
     * **A `$` reference is not a secret.** `models.json` names the *environment variable*
     * that holds a profile's key (`apiKey: "$PIKIT_KEY_…"`, ARCHITECTURE §6.1), so
     * blanking it would produce a file the custom endpoint cannot resolve while removing
     * nothing worth protecting. A literal key the user pasted into any of these files is
     * blanked; the reference is kept.
     *
     * This walks arrays as well as objects: a profile is an element of `profiles`, so the
     * earlier object-only walk missed the one file the field names were written for.
     */
    private fun stripSecrets(root: JsonElement): JsonElement = rewrite(root) { key, value ->
        val primitive = value as? JsonPrimitive
        val literal = primitive != null &&
            primitive.isString &&
            primitive.content.isNotEmpty() &&
            !primitive.content.startsWith("$")
        if (isSecretName(key) && literal) JsonPrimitive("") else value
    }

    private fun isSecretName(key: String): Boolean =
        key == "apiKey" ||
            key == "apiToken" ||
            key.endsWith("ApiKey") ||
            key.endsWith("ApiToken")

    private fun rewrite(element: JsonElement, transform: (String, JsonElement) -> JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (key, value) -> transform(key, rewrite(value, transform)) },
            )

            is JsonArray -> JsonArray(element.map { rewrite(it, transform) })
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
        trees.firstOrNull { name.startsWith("${it.entry}/") }?.category?.let { return it }
        // An archive taken on an install that had a workspace this one has not: the entry
        // is still a workspace's file, and dropping it would restore a backup of a project
        // that came back empty.
        if (name.startsWith("$EXTRA_WORKSPACES_DIR/")) return BackupCategory.WORKSPACE
        // An archive taken on an install where `$HOME` itself was a workspace. It is the
        // same category and the same kind of entry as `workspaces/…`; only the name
        // differs, because an empty relative path cannot be spelled.
        if (name.startsWith("$HOME_TREE_DIR/")) return BackupCategory.WORKSPACE
        return null
    }

    private fun destinationOf(name: String): File? {
        files.firstOrNull { it.entry == name }?.let { return it.source }
        trees.firstOrNull { name.startsWith("${it.entry}/") }?.let { tree ->
            val relative = name.removePrefix("${tree.entry}/")
            if (relative.isEmpty()) return null
            // The same names the write withholds, withheld again on the way back: an
            // archive written by an older build — or crafted to replace the permission
            // policy — must not be able to restore a file this build derives, because
            // the derived copy is the one the runtime installs and the app recomposes.
            if (tree.restoreSkip(relative)) return null
            return File(tree.source, relative)
        }
        // The same fallback `categoryOf` has, and for the same reason: the entry names the
        // path it came from relative to `$HOME`, so it can be put back before the folder
        // is in this install's list — which is exactly the state a restore starts in.
        if (name.startsWith("$EXTRA_WORKSPACES_DIR/")) {
            val relative = name.removePrefix("$EXTRA_WORKSPACES_DIR/")
            if (relative.isEmpty()) return null
            // The withholding belongs to the *name*, not to this install's workspace
            // list: a phone that never made `.pi` a workspace must still not let an
            // archive put the runtime's extension back through this fallback.
            if (withheldInHome(relative)) return null
            return File(paths.home, relative.replace('/', File.separatorChar))
        }
        // A workspace that was `$HOME` on the install the archive came from. Same
        // reasoning as above, and the tree's own skip is applied here as well so the
        // fallback cannot carry what the tree would have withheld.
        if (name.startsWith("$HOME_TREE_DIR/")) {
            val relative = name.removePrefix("$HOME_TREE_DIR/")
            if (relative.isEmpty()) return null
            if (skipHomeTree(relative)) return null
            return File(paths.home, relative.replace('/', File.separatorChar))
        }
        return null
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
            BackupArchive(
                ContextPreferences(context),
                BackupPaths.of(
                    env,
                    workspaces = WorkspaceStore.list(context.applicationContext, env)
                        .map { File(it.path) },
                ),
            )

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
         * This list is the *whole* inventory of the app's preference files; a new file
         * belongs here in the same change that creates it, or a reinstall forgets it.
         *
         * Two are deliberately absent, and neither is a preference. `pikit_model_catalogue`
         * holds one deadline, `due_at`, which this app re-decides within four hours of any
         * launch, so a restored one would either do nothing or make the app skip a refresh
         * it should have made. `pikit_agent_session` holds which conversation was open,
         * which is where the reader happened to be rather than a choice — and on a
         * restored install it names a file from the archive's own `pi-sessions` that may
         * not be there at all.
         */
        /**
         * The entry prefix for workspaces beyond the built-in one.
         *
         * A workspace's entry is `workspaces/<its path relative to $HOME>/…`, which is
         * both what the export writes and what a restore decodes — one spelling for the
         * two directions, so a backup cannot put a project back somewhere it did not come
         * from.
         */
        internal const val EXTRA_WORKSPACES_DIR = "workspaces"

        /**
         * The entry prefix for a workspace that *is* `$HOME`.
         *
         * `$HOME` is a legal workspace — `WorkspaceStore` refuses only "not a directory"
         * — and it was the one the archive dropped in silence: its `$HOME`-relative path
         * is the empty string, which [EXTRA_WORKSPACES_DIR] cannot spell, so the tree
         * loop skipped it beside the folders *outside* `$HOME`. That is the worst shape
         * a backup failure takes: the menu shows the folder, the export reports its
         * entries, and everything the reader put there is simply not in the file.
         *
         * It gets an entry of its own rather than a magic relative path, and its tree is
         * written under the rules [skipHomeTree] states: the categories that own a part
         * of `$HOME` carry that part, and this entry carries the rest.
         */
        internal const val HOME_TREE_DIR = "home"

        /**
         * pi's own directory relative to `$HOME` — the prefix [withheldInHome] measures
         * an entry's path against.
         */
        private const val PI_AGENT_SUBDIR = ".pi/agent"

        /**
         * The top-level names under `$HOME` that another category owns.
         *
         * Read by [skipHomeTree], which is the rule for the one workspace that covers the
         * whole of `$HOME`. Each name has an entry elsewhere and would otherwise be in
         * the archive twice — and the second copy would be the worse one, because a plain
         * tree copies bytes while MODELS redacts pi's JSON and withholds its derived
         * files.
         *
         * `.pi` is here whole rather than as `.pi/agent`: pi keeps nothing of the reader's
         * directly under `.pi`, and a workspace copy of `.pi/agent/models.json` verbatim
         * is exactly the unredacted duplicate the "include the keys" switch must not
         * have.
         */
        private val HOME_OWNED_ELSEWHERE = setOf(
            // The built-in workspace and every added one — WORKSPACE's own trees.
            TermuxEnv.WORKSPACE_DIR_NAME,
            // The replies' `/export` output — HTML_EXPORTS.
            TermuxEnv.EXPORT_DIR_NAME,
            // Recreated from the platform's grant, never restored (§5).
            StorageAccess.STORAGE_DIR_NAME,
            // Derived: the permission file and the rendered chat prompt (§14).
            PermissionPolicy.DIR_NAME,
            // The installer's banner, rewritten on every install and update.
            ".termux",
            // pi's directory — MODELS, redacted and with its derived names withheld.
            ".pi",
        )

        /**
         * The entry prefix for the rest of pi's directory.
         *
         * The four files this build writes have short entries of their own
         * (`pi/models.json`, …), so renaming them would strand every archive already
         * written. Everything else under `$HOME/.pi/agent` — a user's own extension, a
         * config a newer pi reads — travels here, and needs no entry anywhere when it
         * appears, which is the point: a whitelist is a backup that silently loses the
         * next file.
         */
        private const val PI_AGENT_DIR = "pi/agent"

        /**
         * Files under `$HOME/.pi/agent` that are carried by an entry of their own.
         *
         * The halves travel under `AGENT_PROMPT` rather than here because they are the
         * user's document and a restore recomposes `AGENTS.md` from them; the other four
         * are spelled in [files] with the entry names earlier archives already use.
         */
        private val PI_AGENT_OWNED_ELSEWHERE = setOf(
            "models.json",
            "settings.json",
            WEB_SEARCH_FILE,
            AgentContext.FILE_NAME,
            AgentContext.AUTHOR_FILE_NAME,
            AgentContext.USER_FILE_NAME,
        )

        /**
         * Files under `$HOME/.pi/agent` that are derived and must not travel.
         *
         * `models-store.json` is pi's catalogue cache, re-downloaded by the launch path
         * (§6.2). The two extension files are the runtime image's: pi loads every module
         * it finds in `extensions/`, so restoring an older `pikit-permissions.ts` would
         * judge tool calls by a policy the app no longer ships — and restoring the
         * retired `pi-safety-guard.ts` would judge them by *two*, the stricter one
         * winning, with no switch anywhere that turns it off (§14). The installer copies
         * both from `$PREFIX` itself.
         *
         * Spelled here as strings because the installer's names are private to it; a
         * rename there is a rename here too. `BackupArchiveTest` pins that these names
         * are the ones withheld in both directions.
         */
        private val PI_AGENT_DERIVED = setOf(
            "models-store.json",
            "extensions/pikit-permissions.ts",
            "extensions/pi-safety-guard.ts",
        )

        private val PREFERENCE_FILES = listOf(
            PreferenceFile("pikit_settings", BackupCategory.SETTINGS, secretKeys = setOf("api_key")),
            PreferenceFile("pikit_storage", BackupCategory.SETTINGS),
            // Whether the first-launch battery question was answered. A preference in the
            // same sense the storage prompt's answer is: one record of an onboarding
            // answer, with the exemption itself being the platform's and out of reach of
            // any file. Carried because the SETTINGS category promises *every*
            // preference, and the cost of an answer that is already true on the target
            // install is nothing.
            PreferenceFile("pikit_power", BackupCategory.SETTINGS),
            // The workspaces the user added, which is what makes them appear in the
            // composer's menu again after a restore — the directories themselves travel
            // under [EXTRA_WORKSPACES_DIR].
            PreferenceFile(WorkspaceStore.PREFS, BackupCategory.SETTINGS),
            // The chat personas: the list, which one a new conversation starts with, and
            // which preset each saved conversation speaks in. The last is a fact about a
            // conversation, but it lives in the same `SharedPreferences` document, and a
            // preference *file* is one archive entry — splitting it by key would write
            // the same name into the zip twice. So it travels with the settings document;
            // the page's default tick set includes conversations as well, and a
            // conversation whose selection did not come back opens in the default voice,
            // which is the same answer a deleted preset gets.
            PreferenceFile(ChatPresetStore.PREFS, BackupCategory.SETTINGS),
            PreferenceFile("pikit_session_pins", BackupCategory.CONVERSATIONS),
            // The mode each conversation was started in travels with the transcripts:
            // without it every restored chat conversation would reopen as work.
            PreferenceFile(SessionModeStore.PREFS, BackupCategory.CONVERSATIONS),
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
 * not the same as "has no secret": a binary or a document this app does not parse is
 * copied verbatim by design. [Redaction.SECRETS] is tried on every JSON document the
 * archive carries, because any of them can hold a credential the user pasted in by
 * hand, and it costs a re-encode only when it actually finds one.
 */
private data class FileItem(
    val category: BackupCategory,
    val entry: String,
    val source: File,
    val redaction: Redaction = Redaction.NONE,
)

private enum class Redaction { NONE, SECRETS }

/**
 * One tree of the archive: a directory copied whole, and read back whole.
 *
 * [skip] and [redaction] are what make a directory an archive entry rather than a copy
 * of one: `$HOME/.pi/agent` holds files the app derives and must not restore, and JSON
 * that can hold a credential. Both are applied in *both* directions — see
 * [BackupArchive.skipPiAgentFile] — so an archive can neither omit nor inject them.
 */
private data class TreeItem(
    val category: BackupCategory,
    val entry: String,
    val source: File,
    /** Relative paths this tree does not carry, and will not put back. */
    val skip: (String) -> Boolean = { false },
    /**
     * Relative paths a restore refuses, when that is narrower than [skip].
     *
     * The two differ in exactly one place today: a folder that is *also* a workspace of
     * its own is left out of the `$HOME` tree's export because it travels under its own
     * entry, but the dedupe reads the exporting install's list and must not decide what a
     * different install may restore.
     */
    val restoreSkip: (String) -> Boolean = skip,
    /** The redaction recipe for one relative path; [Redaction.NONE] copies bytes. */
    val redaction: (String) -> Redaction = { Redaction.NONE },
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
