package pi.kit.mob.env

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pi.kit.mob.BuildConfig

/**
 * What the runtime image in this APK was built from, read from the image's own
 * metadata.
 *
 * `tools/build-runtime-image.py` writes `build-metadata.json` next to the archives it
 * produces, and the APK packages the whole directory — so the facts are in the app
 * and not only in a terminal. Two things read them: the About page, which shows the
 * environment's version next to pi's, and `TERMUX_VERSION` in the environment every
 * spawned process gets.
 *
 * Read from assets rather than from the unpacked prefix on purpose: the metadata
 * describes the image the *APK* carries, and it is therefore available before the
 * first launch installs anything — which is what lets the environment builder answer
 * without depending on an install having happened.
 */
object BundledImage {

    /** Written by the image builder, in the same directory as the archives. */
    const val METADATA_FILE = "build-metadata.json"

    /**
     * The parts of that file this app uses.
     *
     * [bootstrapTag] is the authority for [termuxVersion]; [piVersion] is what the
     * image builder was asked to vendor, which is `null` in an image built from
     * "latest" rather than from a pin (the About page reads pi's own `package.json`
     * for the version that is actually installed, which is the better answer anyway).
     */
    data class Metadata(val bootstrapTag: String, val piVersion: String?)

    @Volatile
    private var cached: Metadata? = null

    @Volatile
    private var read = false

    /** The metadata for the ABI this device will run, or null when it cannot be read. */
    fun metadata(context: Context): Metadata? {
        if (read) return cached
        synchronized(this) {
            if (!read) {
                cached = runCatching { read(context.applicationContext) }.getOrNull()
                read = true
            }
        }
        return cached
    }

    /**
     * The value `TERMUX_VERSION` carries: the bundled Termux environment's version.
     *
     * **Not the app's version**, which is what it used to be and what it still is
     * underneath this function as a fallback. `TERMUX_VERSION` is defined by Termux as
     * the *app's* version and read as one: `termux-info` prints it under "Termux
     * Variables", `termux-apps-info-app-version-name` falls back to it for a Termux
     * older than 0.119.0, and pi uses its presence alone to decide it is on Termux
     * (clipboard through `termux-clipboard-set`, no native clipboard module). PiKit's
     * own `0.1.0` in that variable answers a question nobody asked — "which Termux is
     * hosting this?" — with a version of a different program, and every later release
     * of this app would look like an upgrade of Termux. The bundled bootstrap's
     * version is the honest answer to that question.
     *
     * The fallback is the app version because a *version-shaped* value matters more
     * than provenance here: the metadata file is inside the APK, so the only way to
     * reach this branch is a build whose assets were tampered with, and an empty
     * `TERMUX_VERSION` would silently change pi's clipboard behaviour instead.
     */
    fun termuxVersion(context: Context): String {
        val tag = metadata(context)?.bootstrapTag ?: return BuildConfig.VERSION_NAME
        return versionOf(tag) ?: BuildConfig.VERSION_NAME
    }

    /**
     * `bootstrap-2026.09.06-r1+apt.android-7` to `2026.09.06-r1`.
     *
     * The tag is `<release date>-r<revision>+<package format>.<api level>`; the date
     * and revision are the environment's version, and the suffix says which Termux
     * package repository it came from, which is not a version. Anything that does not
     * look like that tag yields null rather than a guess.
     */
    internal fun versionOf(bootstrapTag: String): String? {
        val withoutPrefix = bootstrapTag.trim().removePrefix("bootstrap-")
        val version = withoutPrefix.substringBefore('+').trim()
        return version.takeIf { it.matches(VERSION_PATTERN) }
    }

    private val VERSION_PATTERN = Regex("""\d{4}\.\d{2}\.\d{2}(-r\d+)?""")

    private fun read(context: Context): Metadata? {
        val bundled = runCatching { context.assets.list(RUNTIME_ASSETS)?.toList() }
            .getOrNull()
            .orEmpty()
        if (bundled.isEmpty()) return null

        // The same rule the installer uses to pick the image to unpack, so the version
        // reported and the version installed always describe the same directory.
        val abi = chooseAbi(Build.SUPPORTED_ABIS.orEmpty().toList())
            .takeIf { it in bundled }
            ?: bundled.firstOrNull()
            ?: return null

        val json = runCatching {
            context.assets
                .open("$RUNTIME_ASSETS/$abi/$METADATA_FILE")
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull() ?: return null

        val payload = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val tag = payload["bootstrap_tag"]?.jsonPrimitive?.content.orEmpty()
        if (tag.isBlank()) return null
        return Metadata(
            bootstrapTag = tag,
            piVersion = payload["pi_version"]?.jsonPrimitive?.content?.takeIf { it != "null" },
        )
    }
}
