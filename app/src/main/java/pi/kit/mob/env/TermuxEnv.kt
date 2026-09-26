package pi.kit.mob.env

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An extension PiKit bundles inside the runtime image instead of installing.
 *
 * The image cannot run `pi install npm:…`: that needs npm and a network at a
 * moment the app may be offline, it installs into the user's own `$HOME`, and the
 * version would drift with whatever npm resolved that day. So the package is
 * vendored under `$PREFIX` and registered in pi's settings by absolute path —
 * pi's *local path* package source, which loads it through the package's own
 * `pi` manifest.
 *
 * [directory] is resolved from [TermuxEnv.prefix] because the metadata file
 * stores the path *relative* to the prefix: an absolute path would bake this
 * app's application id into a build artifact that is otherwise id-independent.
 */
data class BundledExtension(
    val name: String,
    val version: String,
    /** The package directory: the path pi's `packages` setting takes. */
    val directory: File,
) {
    /** The extension module the package's `pi` manifest declares. */
    val entryPoint: File get() = File(directory, ENTRY_POINT)

    companion object {
        /** Where the build writes this extension's metadata, relative to `$PREFIX`. */
        const val METADATA = "share/pikit/web-access.json"

        /** The `pi.extensions` entry of the bundled package. */
        const val ENTRY_POINT = "index.ts"
    }
}

/**
 * Filesystem layout of the Termux runtime that ships inside the APK.
 *
 * Every path is derived from [Context.getFilesDir] rather than hardcoded, so the
 * layout stays correct regardless of the installed package name. The *image*
 * inside the APK, however, is built for one fixed prefix (see [expectedPrefix])
 * because Termux binaries resolve their libraries through an absolute
 * DT_RUNPATH compiled into each ELF. [expectedPrefix] is therefore checked
 * during installation and a mismatch is reported instead of silently producing
 * a broken environment.
 */
class TermuxEnv private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /** `/data/data/<applicationId>/files` */
    val filesDir: File = appContext.filesDir

    /**
     * The installed application id, e.g. `pi.kit.mob`.
     *
     * The bundled runtime image is relocated to this name at build time, and
     * [PrefixPatcher] re-applies the same rewrite to anything installed later.
     */
    val packageId: String = appContext.packageName

    /** `$PREFIX` — `/data/data/<applicationId>/files/usr` */
    val prefix: File = File(filesDir, "usr")

    /** `$HOME` — `/data/data/<applicationId>/files/home` */
    val home: File = File(filesDir, "home")

    /**
     * `$HOME/export` — what a reply's own `/export` writes.
     *
     * Under `$HOME` and not hidden, because the Files tab is rooted at `$HOME` and
     * a folder nobody can see is a folder nobody opens. Named here rather than
     * spelled at its call site because the backup page names it too, and a second
     * spelling of one path is how a backup comes to miss a directory.
     */
    val exportDir: File = File(home, EXPORT_DIR_NAME)

    /**
     * `files/pi-sessions` — the conversation transcripts pi writes.
     *
     * Outside `$HOME` on purpose: a user who changes the working directory, or
     * deletes it, must not take their history with it. Named here for the same
     * reason as [exportDir] — the backup page and the session listing both need it.
     */
    val sessionDir: File = File(filesDir, SESSION_DIR_NAME)

    /**
     * `$HOME/workspace` — where the agent is meant to work.
     *
     * The working directory used to default to `$HOME` itself, which put every
     * dotfile, every pi config and every session transcript in the agent's own
     * project tree: a model told to "clean up the build output" was one path
     * mistake away from `$HOME/.pi/agent`, and a recursive delete of the working
     * directory meant the whole environment. The workspace is a subdirectory so
     * the two are different things by construction, and the guard (see
     * `tools/pi-safety-guard.ts`) allows a recursive delete inside it and nowhere
     * else under `$HOME`.
     */
    val workspace: File = File(home, WORKSPACE_DIR_NAME)

    /** Staging directory the runtime image is unpacked into before activation. */
    val stagingPrefix: File = File(filesDir, STAGING_DIR_NAME)

    val binDir: File = File(prefix, "bin")
    val libDir: File = File(prefix, "lib")
    val etcDir: File = File(prefix, "etc")
    val tmpDir: File = File(prefix, "tmp")
    val varDir: File = File(prefix, "var")

    val bash: File = File(binDir, "bash")
    val login: File = File(binDir, "login")
    val node: File = File(binDir, "node")
    val pi: File = File(binDir, "pi")

    /**
     * `termux-exec` rewrites the interpreter path of scripts whose shebang is
     * baked to the build-time prefix. `$PREFIX/bin/login` exports
     * `LD_PRELOAD` for it on its own; we also set it so that processes we spawn
     * directly, without going through `login`, inherit the hook.
     */
    val termuxExecLib: File = File(libDir, "libtermux-exec-ld-preload.so")

    /** Records which runtime image revision is currently installed. */
    val stampFile: File = File(filesDir, RUNTIME_STAMP_FILE)

    /** Pi's config directory (`$HOME/.pi/agent`). */
    val piConfigDir: File = File(home, ".pi/agent")

    /**
     * The web-access extension that ships inside the runtime image, or null when
     * this runtime does not carry one.
     *
     * Read from metadata the image build writes, not hard-coded here: the path
     * inside `$PREFIX` and the version are the build's business, and a runtime
     * image without the extension has to be a *missing file* the settings page can
     * report rather than a path this app assumes exists. A metadata file whose
     * directory has gone is treated as absent for the same reason.
     */
    val webAccessExtension: BundledExtension? by lazy {
        runCatching {
            val metadata = File(prefix, BundledExtension.METADATA)
            if (!metadata.isFile) return@runCatching null
            val document = Json.parseToJsonElement(metadata.readText()).jsonObject
            val name = document["name"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val version = document["version"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val path = document["path"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            BundledExtension(name = name, version = version, directory = File(prefix, path))
        }.getOrNull()?.takeIf { it.directory.isDirectory }
    }

    /**
     * `~/storage` — symlinks into the phone's shared storage, created once the
     * user grants "all files access". See [StorageAccess].
     */
    val storageDir: File = File(home, StorageAccess.STORAGE_DIR_NAME)

    val prefixPath: String get() = prefix.absolutePath
    val homePath: String get() = home.absolutePath
    val workspacePath: String get() = workspace.absolutePath

    /** True once a runtime image has been unpacked and activated. */
    val isInstalled: Boolean
        get() = bash.isFile && stampFile.isFile

    /** Installed runtime image revision, or null when nothing is installed. */
    val installedRevision: String?
        get() = runCatching { stampFile.readText().trim() }.getOrNull()?.ifBlank { null }

    fun isUpToDate(expectedRevision: String): Boolean =
        isInstalled && installedRevision == expectedRevision

    companion object {
        const val STAGING_DIR_NAME = "usr-staging"
        const val RUNTIME_STAMP_FILE = "runtime-revision.txt"

        /** The directory under `$HOME` the agent works in; see [workspace]. */
        const val WORKSPACE_DIR_NAME = "workspace"

        /** Where a reply's `/export` writes; see [exportDir]. */
        const val EXPORT_DIR_NAME = "export"

        /** Where pi writes a conversation; see [sessionDir]. */
        const val SESSION_DIR_NAME = "pi-sessions"

        /**
         * The prefix the bundled image was compiled for. Termux packages bake
         * this into every ELF's DT_RUNPATH, so it cannot be relocated at
         * runtime — see docs/ARCHITECTURE.md.
         */
        val expectedPrefix: String = pi.kit.mob.BuildConfig.TERMUX_PREFIX

        @Volatile
        private var instance: TermuxEnv? = null

        fun of(context: Context): TermuxEnv =
            instance ?: synchronized(this) {
                instance ?: TermuxEnv(context).also { instance = it }
            }
    }
}
