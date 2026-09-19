package pi.kit.mob.pi

import pi.kit.mob.env.TermuxEnv
import java.io.File

/**
 * Locates the pi CLI inside the bundled environment.
 *
 * Pi is invoked as `node <cli.js>` rather than through the `pi` shim in
 * `$PREFIX/bin`. The shim is a shell script, so it would depend on `termux-exec`
 * rewriting its shebang; calling the entry point directly removes that
 * dependency and keeps argv construction predictable.
 */
object PiInstallation {

    /** Path of pi's published bin entry, relative to `$PREFIX`. */
    const val CLI_ENTRY_RELATIVE = "lib/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js"

    /** Directory the npm package is installed into, relative to `$PREFIX`. */
    private const val PACKAGE_ROOT_RELATIVE = "lib/node_modules/@earendil-works/pi-coding-agent"

    fun cliEntry(env: TermuxEnv): File? =
        File(env.prefix, CLI_ENTRY_RELATIVE).takeIf { it.isFile }

    fun packageRoot(env: TermuxEnv): File = File(env.prefix, PACKAGE_ROOT_RELATIVE)

    /**
     * The version pi's own `package.json` declares, or null when it cannot be read.
     *
     * The regex is hoisted out of the function: this is called from composable
     * bodies on three settings pages, and compiling it per call was a regex compile
     * per recomposition for a string that cannot change while the app runs.
     */
    fun installedVersion(env: TermuxEnv): String? = runCatching {
        File(packageRoot(env), "package.json").takeIf { it.isFile }?.readText()
    }.getOrNull()?.let { json ->
        VERSION_REGEX.find(json)?.groupValues?.get(1)
    }

    private val VERSION_REGEX = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * Tools pi shells out to. `grep` runs `rg` and `find` runs `fd`, and pi
     * refuses to download them on Android because the upstream Linux builds are
     * glibc-linked. They must therefore be present in the image.
     */
    fun requiredTools(env: TermuxEnv): Map<String, Boolean> = mapOf(
        "node" to env.node.isFile,
        "bash" to env.bash.isFile,
        "rg" to File(env.binDir, "rg").isFile,
        "fd" to File(env.binDir, "fd").isFile,
    )
}
