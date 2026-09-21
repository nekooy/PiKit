package pi.kit.mob.env

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.terminalBanner
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** The two ABIs an image is built for. */
internal const val ARCH_ARM64 = "arm64-v8a"
internal const val ARCH_X86_64 = "x86_64"

/**
 * Which ABI's runtime image to unpack.
 *
 * Pure, and outside the installer, so the rule can be pinned on the JVM.
 *
 * The *device* decides, and only the device: an APK carries one ABI's image, and a
 * mismatch between the two cannot be papered over here. An `arm64Debug` build on an
 * x86_64 emulator — Android Studio's default variant, with the emulator reporting
 * `x86_64,arm64-v8a` because it translates ARM — looks like it could run the arm64
 * image, and it cannot: the translation layer cannot preload the arm64
 * `libtermux-exec-ld-preload.so`, so every exec in the runtime dies in the linker.
 * Falling back to the bundled ABI would therefore turn one clear message into a
 * hundred confusing ones. What the build carries is used for the *message* instead
 * ([missingImageMessage]), and `app/build.gradle.kts` refuses the combination at
 * build time.
 *
 * The device's first feature ABI wins over its later ones because `SUPPORTED_ABIS`
 * is in preference order, and a device that lists neither gets its own first entry
 * — which is also the name the error message should talk about.
 */
internal fun chooseAbi(supported: List<String>): String =
    supported.firstOrNull { it == ARCH_ARM64 || it == ARCH_X86_64 }
        ?: supported.firstOrNull()
        ?: ARCH_ARM64

/**
 * Where the bundled images live under `assets/`, one directory per ABI.
 *
 * At file level rather than in [BootstrapInstaller]'s private companion because two
 * things need it now and they must not disagree: the installer, which unpacks the
 * directory, and `BundledImage`, which reads the metadata beside the archives.
 */
internal const val RUNTIME_ASSETS = "runtime"

/**
 * What to say when the APK has no image for the ABI that was chosen.
 *
 * The old message named only the missing side — "this build carries no runtime
 * image for x86_64" — which is true and leaves the reader with nothing to do. An
 * APK carries one ABI, the build variant in Android Studio is not necessarily the
 * one the device needs, and the two facts together are the whole of this failure:
 * so the message says what the build *does* carry and which variant to install
 * instead.
 */
internal fun missingImageMessage(abi: String, dir: String, bundled: List<String>): String =
    buildString {
        append("This build carries no runtime image for ").append(abi)
        append(" (assets/").append(dir).append(" is empty).")
        if (bundled.isEmpty()) {
            append(" It carries no runtime image at all: build one with ")
            append("tools/build-runtime-image.py before installing.")
        } else {
            append(" It carries ").append(bundled.joinToString(", ")).append('.')
            append(" Install the build variant for ").append(abi)
            append(" — in Android Studio, Build → Select Build Variant — or build its image with ")
            append("tools/build-runtime-image.py.")
        }
    }

/**
 * Unpacks the Termux runtime that is baked into the APK's assets.
 *
 * The image is one or more zip archives whose entries are relative to
 * `$PREFIX`, plus a `SYMLINKS.txt` entry listing the symlinks that the zip
 * format itself cannot represent (Java's zip API has no symlink support, which
 * is why Termux uses this same side-car convention).
 *
 * Extraction happens into a staging directory that is only renamed into place
 * once every archive has been applied, so an interrupted install can never
 * leave a half-populated `$PREFIX` behind. `$HOME` lives outside `$PREFIX`, so
 * reinstalling the runtime never discards the user's pi sessions.
 */
class BootstrapInstaller(private val context: Context) {
    private val env = TermuxEnv.of(context)
    private val assets = context.assets

    data class Plan(
        val abi: String,
        val archives: List<String>,
        val totalBytes: Long,
        val revision: String,
    )

    sealed interface Progress {
        data class Working(val fraction: Float, val message: String) : Progress
        data class Done(val revision: String) : Progress
    }

    /** Inventory of what this APK actually carries for the running ABI. */
    fun plan(): Plan {
        // What the APK carries is read first: an APK holds exactly one ABI's image,
        // and on a device that can run more than one (an x86_64 emulator with ARM
        // translation, for instance) the device's own preference order is not the
        // same question as "which image is in this build".
        val bundled = bundledAbis()
        val abi = selectAbi(bundled)
        val dir = "$RUNTIME_ASSETS/$abi"
        val entries = runCatching { assets.list(dir)?.toList().orEmpty() }.getOrDefault(emptyList())
        val archives = entries.filter { it.endsWith(".zip", ignoreCase = true) }.sorted()
        check(archives.isNotEmpty()) { missingImageMessage(abi, dir, bundled) }

        val paths = archives.map { "$dir/$it" }
        val sizes = paths.map { assetSize(it) }
        val total = if (sizes.any { it < 0 }) UNKNOWN_SIZE else sizes.sum()

        val declaredRevision = entries
            .firstOrNull { it.equals("revision.txt", ignoreCase = true) }
            ?.let { name ->
                runCatching {
                    assets.open("$dir/$name").bufferedReader().use { it.readText().trim() }
                }.getOrNull()
            }
            ?.takeIf { it.isNotEmpty() }

        val revision = declaredRevision ?: buildString {
            append(abi)
            archives.forEachIndexed { index, name ->
                append('|').append(name).append(':').append(sizes[index])
            }
        }

        return Plan(abi = abi, archives = paths, totalBytes = total, revision = revision)
    }

    /**
     * Ensures the runtime is present and matches the bundled revision. Safe to
     * call on every launch: it is a no-op once the installed image is current.
     */
    suspend fun ensureInstalled(onProgress: (Progress) -> Unit = {}): Plan =
        withContext(Dispatchers.IO) {
            val plan = plan()

            if (env.isUpToDate(plan.revision)) {
                // An up-to-date image still has to be finalised before it is
                // used. A prefix installed by a PiKit that did not run the
                // second stage itself — every revision up to the one that added
                // [finaliseBootstrap] — has no lock and still carries the shell
                // hook, so leaving this branch alone would mean the noise simply
                // moved from the next launch to the one after it. Once the lock
                // exists the call is one `lstat` and a second existence check,
                // which is what a normal launch pays.
                finaliseBootstrap()
                onProgress(Progress.Done(plan.revision))
                return@withContext plan
            }

            verifyPrefixMatchesImage()
            onProgress(Progress.Working(0f, "Preparing"))

            // A previous run may have been killed mid-extract; never reuse a
            // partially populated staging tree.
            SafeDelete.recursively(env.stagingPrefix, env.filesDir)
            env.stagingPrefix.mkdirsOrThrow()
            chmodPrivate(env.stagingPrefix)

            val measurable = plan.totalBytes != UNKNOWN_SIZE
            var bytesDone = 0L
            var bytesReported = 0L
            var archiveIndex = 0

            for (archive in plan.archives) {
                val label = archive.substringAfterLast('/')
                onProgress(Progress.Working(fraction(bytesReported, plan.totalBytes), "Unpacking $label"))

                extractArchive(
                    assetPath = archive,
                    staging = env.stagingPrefix,
                    onBytes = { delta ->
                        bytesDone += delta
                        // Throttle UI updates to whole-percent steps.
                        if (measurable && bytesDone - bytesReported >= plan.totalBytes / 100) {
                            bytesReported = bytesDone
                            onProgress(
                                Progress.Working(
                                    fraction(bytesReported, plan.totalBytes),
                                    "Unpacking $label",
                                ),
                            )
                        }
                    },
                )

                bytesReported = maxOf(bytesReported, bytesDone)
                archiveIndex++
                onProgress(
                    Progress.Working(
                        (archiveIndex.toFloat() / plan.archives.size * ACTIVATION_FRACTION)
                            .coerceAtMost(ACTIVATION_FRACTION),
                        "Unpacked $label",
                    ),
                )
            }

            currentCoroutineContext().ensureActive()
            onProgress(Progress.Working(0.97f, "Activating environment"))

            activateStagingPrefix()
            // After activation, because the files it installs into `$HOME` come
            // out of the prefix.
            prepareHome()
            // And after `prepareHome()`, because the maintainer scripts this runs
            // inherit `$HOME` and `$TMPDIR` from us and both have to exist by
            // then. Not before it: the second stage ends by reconfiguring
            // packages *inside* the prefix, and nothing `prepareHome()` writes
            // depends on that.
            finaliseBootstrap()

            env.stampFile.writeText(plan.revision + "\n")

            onProgress(Progress.Done(plan.revision))
            plan
        }

    // -------------------------------------------------------------- internals

    /**
     * Replaces the shell's welcome banner with PiKit's own short one.
     *
     * Written to `$HOME/.termux/motd.sh`, which is the *user* override the bundled
     * `login` script prefers over `$PREFIX/etc/motd`. Three reasons for that path
     * rather than the package's file:
     *
     *  - `$HOME` is outside `$PREFIX`, so a runtime update does not discard it.
     *  - `$PREFIX/etc/motd` belongs to `termux-tools`; editing a package's file
     *    invites `pkg upgrade` to put the old one back.
     *  - `login` calls it as a program, so it can be a heredoc `cat` and carries no
     *    terminal-width or colour assumptions.
     *
     * Rewritten only when the text differs, so it costs one read per launch.
     */
    fun installTerminalBanner(lang: Lang) {
        val directory = File(env.home, ".termux")
        val script = File(directory, "motd.sh")
        val wanted = buildString {
            append("#!").append(env.prefixPath).append("/bin/sh\n")
            append("cat <<'PIKIT_MOTD'\n")
            append(terminalBanner(lang))
            append("\nPIKIT_MOTD\n")
        }

        if (script.isFile && script.readText() == wanted) return

        directory.mkdirsOrThrow()
        script.writeText(wanted)
        // `login` chmods it if it is not executable, but doing it here means the
        // first shell of a fresh install does not depend on that fallback.
        runCatching { Os.chmod(script.absolutePath, OsConstants.S_IRWXU) }
    }

    /**
     * The bundled ELFs have the build-time prefix compiled into their
     * DT_RUNPATH, so the directory the image was built for has to be the
     * directory this installation actually uses.
     *
     * The comparison is deliberately *not* a string comparison. From Android 11
     * the app data directory is reachable through both `/data/data/<pkg>` and
     * `/data/user/0/<pkg>`, which are bind-mounted views of one directory: the
     * build names the first form and `Context.getFilesDir()` reports the
     * second. They differ as strings and are identical as directories, so the
     * check compares inode identity instead of spelling.
     *
     * The prefix itself does not exist yet at this point, so the containing
     * `files` directory — which does — stands in for it.
     */
    private fun verifyPrefixMatchesImage() {
        val expected = File(TermuxEnv.expectedPrefix)
        val actual = env.prefix

        if (expected.absolutePath == actual.absolutePath) return
        if (isSameDirectory(expected.parentFile, actual.parentFile)) return

        error(
            "PiKit's bundled runtime was built for the prefix\n  ${expected.absolutePath}\n" +
                "but this installation uses\n  ${actual.absolutePath}\n\n" +
                "Those are not the same directory, and Termux binaries resolve their " +
                "libraries through that absolute path. The runtime image has to be " +
                "rebuilt for this application id — see docs/ARCHITECTURE.md.",
        )
    }

    /**
     * True when two paths name the same directory, which on Android 11+ is how
     * `/data/data/<pkg>` and `/data/user/0/<pkg>` relate to each other. A plain
     * `canonicalPath` comparison cannot detect that, because a bind mount is
     * not a symlink.
     */
    private fun isSameDirectory(first: File?, second: File?): Boolean {
        if (first == null || second == null) return false
        return runCatching {
            val a = Os.stat(first.absolutePath)
            val b = Os.stat(second.absolutePath)
            a.st_dev == b.st_dev && a.st_ino == b.st_ino
        }.getOrDefault(false)
    }

    private fun prepareHome() {
        env.home.mkdirsOrThrow()
        chmodPrivate(env.home)
        env.tmpDir.mkdirsOrThrow()
        env.piConfigDir.mkdirsOrThrow()
        // The agent's working directory, created here rather than at the first
        // launch: `$HOME` is the agent's home, not its project, and a directory
        // that exists before pi starts is one the guard can reason about. See
        // [TermuxEnv.workspace].
        env.workspace.mkdirsOrThrow()

        installSafetyGuard()

        // Describe the sandbox to the agent. Pi reads AGENTS.md automatically
        // from `$HOME/.pi/agent/AGENTS.md`; being explicit here avoids a class of
        // wrong assumptions about paths, storage and what is already installed —
        // and the storage rules below are the ones that keep the agent from
        // treating the user's files as scratch space.
        //
        // Written only when it is missing: it is editable from the settings page,
        // and a runtime update that replaced it would throw away what the user
        // wrote. See `AgentContext`.
        AgentContext.install(env)
    }

    /**
     * Copies the tool-call guard out of the runtime image into pi's global
     * extension directory.
     *
     * The image is replaced wholesale on every runtime update, so the file has
     * to be re-installed each time: this is why it is copied rather than loaded
     * from `$PREFIX` directly. `$HOME/.pi/agent/extensions` is a discovery path
     * pi reads at startup, and it survives a reinstall because `$HOME` lives
     * outside `$PREFIX`.
     *
     * A missing staged file is not fatal. It means an image built before this
     * guard existed, and the app should still start — the agent-side rules in
     * AGENTS.md are the second line of defence.
     */
    private fun installSafetyGuard() {
        val source = File(env.prefix, SAFETY_GUARD_SOURCE)
        if (!source.isFile) return
        val destination = SafetyGuard.file(env)
        runCatching {
            destination.parentFile?.mkdirsOrThrow()
            // Only rewritten when it actually differs, so the file's mtime is a
            // reliable "unchanged" signal for the user.
            val current = if (destination.isFile) destination.readText() else null
            val wanted = source.readText()
            if (current != wanted) destination.writeText(wanted)
        }
    }


    private fun activateStagingPrefix() {
        // Replace any previous runtime wholesale. $HOME is deliberately outside
        // $PREFIX, so user sessions and settings survive a reinstall.
        //
        // The delete goes through [SafeDelete], which refuses anything outside
        // this app's private data directory and removes symlinks by path rather
        // than following them. That last part is load bearing here: the prefix
        // contains symlinks, and a recursive delete that followed one could
        // reach outside the tree.
        if (env.prefix.exists()) {
            SafeDelete.recursively(env.prefix, env.filesDir).also { outcome ->
                if (outcome is SafeDelete.Outcome.Refused) {
                    error("Could not replace the runtime: ${outcome.reason}")
                }
            }
        }
        check(env.stagingPrefix.renameTo(env.prefix)) {
            "Could not move the prepared environment into place " +
                "(${env.stagingPrefix} -> ${env.prefix})"
        }
        chmodPrivate(env.prefix)
    }

    // ------------------------------------------------- bootstrap second stage

    /**
     * Runs the bootstrap's second stage — the `postinst` maintainer scripts that
     * `dpkg` would have run had the bootstrap been installed by a package
     * manager instead of unpacked out of a zip — and then removes the shell hook
     * that would otherwise run it later.
     *
     * The Termux app does this itself, immediately after extracting the
     * bootstrap, which is the only reason the bootstrap ships
     * `etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh`: a hook that
     * notices the second stage never ran and runs it from the first login shell.
     * PiKit extracts the bootstrap in Kotlin and used to run nothing, so the hook
     * fired instead — printing one line per package plus every
     * `update-alternatives` message into a terminal about 48 columns wide, in
     * front of the user, on the first visit to the terminal tab. Running it here
     * makes it a bounded, one-time cost of the install whose transcript lands in
     * a file, and takes the decision away from whichever shell happens to be the
     * first one.
     *
     * Idempotence is the script's own lock symlink, not a marker of ours: it
     * creates the lock before it runs anything, a second run therefore refuses
     * and reports success, and wiping the prefix takes the lock with it — which
     * is exactly the state after a reinstall, where the second stage must run
     * again. That is what lets [ensureInstalled]'s already-up-to-date branch call
     * this unconditionally.
     *
     * On failure the hook is deliberately left in place. It is the shell's own
     * retry, and deleting it after a failed second stage would be the one
     * outcome that quietly hides an unconfigured environment forever.
     */
    private fun finaliseBootstrap() {
        val script = File(env.prefix, SECOND_STAGE_SCRIPT)
        val lock = File(env.prefix, SECOND_STAGE_LOCK)
        val fallback = File(env.prefix, FALLBACK_SCRIPT)

        if (lockExists(lock)) {
            // The second stage has already run — here, or by the hook on a
            // launch that predates this code. Only the hook itself is left to
            // clear, and it is usually gone already because the hook deletes
            // itself after a successful run.
            removeFallbackScript(fallback)
            verifyPreloadLibrary()
            return
        }

        // Nothing to run in an image that predates the second stage script. The
        // hook is left alone rather than deleted: without the script it cannot
        // do anything anyway, and deleting it would throw away the only
        // remaining trace of why the environment is unconfigured.
        if (!script.isFile) {
            verifyPreloadLibrary()
            return
        }

        val exitCode = runSecondStage(script)
        if (exitCode == EXIT_OK) {
            removeFallbackScript(fallback)
            Log.i(TAG, "Bootstrap second stage finished: exit 0, log ${secondStageLog.absolutePath}")
        } else {
            Log.w(
                TAG,
                "Bootstrap second stage failed: exit $exitCode, " +
                    "left $FALLBACK_SCRIPT in place, log ${secondStageLog.absolutePath}",
            )
        }

        // After the stage, which is the thing that writes this file, and on every
        // path above so that an install that came out broken is repaired by the
        // next launch rather than by a reinstall.
        verifyPreloadLibrary()
    }

    /**
     * Makes sure the Termux `$LD_PRELOAD` library is a file with content in it.
     *
     * ## The failure this exists for
     *
     * `lib/libtermux-exec-ld-preload.so` is preloaded into *every* process the
     * environment runs, and on Android an `LD_PRELOAD` that points at an empty
     * file does not degrade — the linker refuses the program:
     *
     * ```
     * CANNOT LINK EXECUTABLE "/data/data/pi.kit.mob/files/usr/bin/sh":
     * file offset for the library "…/lib/libtermux-exec-ld-preload.so" >= file size: 0 >= 0
     * ```
     *
     * which is what a reader sees in the terminal tab, with the agent failing the
     * same way one screen away (`node` cannot start either).
     *
     * ## What was measured
     *
     * The file is written by `termux-exec`'s own `postinst`, which copies one of
     * two variants onto that name (`cp -a` from `libtermux-exec-direct-ld-preload.so`
     * on API 28+, from `libtermux-exec-linker-ld-preload.so` below it, chosen by
     * `termux-exec-system-linker-exec is-enabled`). On the Medium_Phone AVD one
     * install that *replaced an existing prefix* — the arm64-to-x64 variant switch,
     * and the same shape as any runtime update — came out with this file at 0 bytes
     * while its source variant was intact, the second stage reported success, and
     * every other file in the prefix was correct. Its mtime was *identical to the
     * nanosecond* to the file the `postinst` copies from, so it is that `cp -a`'s
     * own output and not a leftover; a fresh install and a repeat of the same
     * reinstall both came out correct, so it is not deterministic and the maintainer
     * scripts have not been made to reproduce it on demand.
     *
     * ## Why this repairs rather than re-installs
     *
     * Re-running the package's own tool is exactly what the `postinst` does, and it
     * is the only thing that knows which variant this device needs. `LD_PRELOAD` is
     * removed from the child's environment because everything exec'd while it points
     * at the empty file dies in the linker — including `sh`, which the tool needs to
     * start. The output is appended to the second stage's log, so the day this fires
     * the evidence of what the environment looked like is one `adb pull` away.
     */
    private fun verifyPreloadLibrary() {
        val library = env.termuxExecLib
        if (isUsablePreload(library)) return

        Log.w(
            TAG,
            "The Termux \$LD_PRELOAD library is missing or empty (${library.length()} bytes): " +
                library.absolutePath,
        )

        val tool = File(env.prefix, PRELOAD_SETUP_TOOL)
        val exitCode = try {
            val process = ProcessBuilder(tool.absolutePath, "setup", "-v")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(secondStageLog))
                .apply {
                    environment().clear()
                    environment().putAll(ShellEnvironment.build(context, env))
                    // Set by [ShellEnvironment] and removed here: exec'ing anything
                    // while it points at the broken file fails in the linker.
                    environment().remove("LD_PRELOAD")
                }
                .start()
            if (process.waitFor(PRELOAD_REPAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                COULD_NOT_START
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not run $PRELOAD_SETUP_TOOL; log ${secondStageLog.absolutePath}", t)
            COULD_NOT_START
        }

        if (isUsablePreload(library)) {
            Log.i(TAG, "Repaired the Termux \$LD_PRELOAD library; setup exited $exitCode")
        } else {
            Log.w(TAG, "The Termux \$LD_PRELOAD library is still unusable; setup exited $exitCode")
        }
    }

    /**
     * True when [library] is a file with content, or a symlink that resolves to one.
     *
     * A *dangling* symlink is the other shape this takes, and it fails the same way,
     * so both are checked through the same `length()`.
     */
    private fun isUsablePreload(library: File): Boolean =
        runCatching { library.isFile && library.length() > 0L }.getOrDefault(false)

    /**
     * Runs the second stage script and waits for it, bounded.
     *
     * 120 s is generous for the ~30 `postinst` scripts in the bootstrap, which
     * mostly edit dpkg's own database and call `update-alternatives`; the bound
     * exists so one wedged maintainer script cannot make first launch look like
     * a hang. `destroyForcibly` signals the direct child only, so a `postinst`
     * that `bash` already spawned outlives the timeout — the transcript in the
     * log says which package it stopped at.
     *
     * The script is handed to `$PREFIX/bin/bash` rather than executed directly.
     * The bootstrap zip carries no Unix modes and [needsExecutableBit] only
     * covers `bin/`, `libexec`, apt's helpers and `node_modules`, so this file
     * is not executable after extraction — which is why Termux's own hook
     * `chmod +x`es it first. Naming the interpreter skips that step entirely.
     *
     * @return the script's exit code, or a negative sentinel when it could not be
     *   started or had to be killed.
     */
    private fun runSecondStage(script: File): Int {
        val log = secondStageLog

        val process = try {
            ProcessBuilder(listOf(env.bash.absolutePath, script.absolutePath))
                // A directory that exists and is writable — the same
                // `home.also { mkdirs() }` the other child processes use. The
                // script changes to `/` itself before running a maintainer
                // script, so this only has to be somewhere sane.
                .directory(env.home.also { it.mkdirs() })
                // Both streams into the log file, so neither can land in the
                // app's own stdout and neither can block on a pipe nobody is
                // draining.
                .redirectErrorStream(true)
                .redirectOutput(log)
                // Anything in a maintainer script that reads stdin gets EOF
                // instead of waiting out the timeout below.
                .redirectInput(ProcessBuilder.Redirect.from(File(NULL_DEVICE)))
                .apply {
                    // Cleared rather than amended. The script enforces
                    // `TERMUX__UID` only when it is set, and this app has no
                    // Termux app to take that value from; leaving the app's
                    // environment in place would also leave `PATH` pointing at
                    // `/system/bin`, where `ln`, `sed`, `head` and `dpkg` are
                    // missing or are not the Termux ones.
                    environment().clear()
                    environment().putAll(ShellEnvironment.build(context, env))
                    // Belt and braces against a `TERMUX__UID` arriving through
                    // [ShellEnvironment] one day: with it set, the script fails
                    // unless `id -u` matches it.
                    environment().remove("TERMUX__UID")
                }
                .start()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start the bootstrap second stage; log ${log.absolutePath}", t)
            return COULD_NOT_START
        }

        val finished = try {
            process.waitFor(SECOND_STAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "Waiting for the bootstrap second stage failed; log ${log.absolutePath}", t)
            process.destroyForcibly()
            return COULD_NOT_START
        }

        if (!finished) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)
            return TIMED_OUT
        }

        return process.exitValue()
    }

    /**
     * Removes the profile hook that runs the second stage from the shell.
     *
     * Deleting it is what the hook does to itself at the end of a successful run
     * (`rm -f` on its own path), so this is not a behaviour change — it just
     * makes the removal happen at install time instead of depending on a login
     * shell actually being started and sourcing `etc/profile.d`.
     *
     * A failure to delete is logged and ignored: the hook re-checks the lock
     * before doing anything, so the worst case is the status quo.
     */
    private fun removeFallbackScript(fallback: File) {
        if (!fallback.isFile) return
        runCatching { fallback.delete() }
            .onFailure { Log.w(TAG, "Could not remove ${fallback.absolutePath}", it) }
    }

    /**
     * True when the lock exists, symlink or not.
     *
     * `File.exists()` follows the link and answers false for a dangling one,
     * while the script's own `ln -s` and the hook's `[ -L ]` test are asking
     * about the directory entry. `lstat` is that question — the same reason
     * [extractArchive] probes symlinks with it.
     */
    private fun lockExists(lock: File): Boolean =
        runCatching { Os.lstat(lock.absolutePath) }.isSuccess

    /** Where the second stage's transcript goes. Rewritten on every attempt. */
    private val secondStageLog: File
        get() = File(env.filesDir, SECOND_STAGE_LOG)

    /**
     * Streams one archive out of assets. Symlinks are collected first and
     * created after the archive has been fully extracted, matching Termux's
     * own installer.
     */
    private suspend fun extractArchive(
        assetPath: String,
        staging: File,
        onBytes: (Long) -> Unit,
    ) {
        val symlinks = ArrayList<Pair<String, String>>(64)
        val buffer = ByteArray(64 * 1024)

        assets.open(assetPath).use { raw ->
            ZipInputStream(BufferedInputStream(raw, buffer.size)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val name = entry.name

                    if (name == SYMLINKS_ENTRY) {
                        // Read the side-car without letting anything close it.
                        //
                        // Kotlin's `Reader.forEachLine` is implemented on top of
                        // `useLines`, which calls `use { }` — so iterating lines
                        // that way closes the underlying stream, and the
                        // `closeEntry()` below then throws "Stream closed". The
                        // reader is therefore driven by hand and deliberately
                        // never closed: it stops at the end of this entry
                        // because ZipInputStream.read returns -1 there.
                        val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val parts = line.split(SYMLINK_SEPARATOR)
                            check(parts.size == 2) { "Malformed symlink line: $line" }
                            val linkPath = File(staging, parts[1]).absolutePath
                            File(linkPath).parentFile?.mkdirsOrThrow()
                            symlinks += parts[0] to linkPath
                        }
                        zip.closeEntry()
                        continue
                    }

                    val target = safeTarget(staging, name)
                        ?: error("Refusing archive entry outside the prefix: $name")

                    if (entry.isDirectory) {
                        target.mkdirsOrThrow()
                    } else {
                        target.parentFile?.mkdirsOrThrow()
                        FileOutputStream(target).use { out ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                onBytes(read.toLong())
                            }
                        }
                        if (needsExecutableBit(name)) chmodPrivate(target)
                    }
                    zip.closeEntry()
                }
            }
        }

        for ((linkTarget, linkPath) in symlinks) {
            // A dangling symlink makes File.exists() false, so probe with lstat.
            if (runCatching { Os.lstat(linkPath) }.isSuccess) {
                File(linkPath).delete()
            }
            File(linkPath).parentFile?.mkdirsOrThrow()
            Os.symlink(linkTarget, linkPath)
        }
    }

    /** Resolves an archive entry, rejecting path traversal (zip-slip). */
    private fun safeTarget(staging: File, entryName: String): File? {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        var file = staging
        for (part in parts) file = File(file, part)
        return file
    }

    private fun chmodPrivate(file: File) {
        runCatching { Os.chmod(file.absolutePath, OsConstants.S_IRWXU) }
    }

    private fun File.mkdirsOrThrow() {
        if (isDirectory) return
        check(mkdirs() || isDirectory) { "Could not create directory: $absolutePath" }
    }

    private fun fraction(done: Long, total: Long): Float =
        if (total == UNKNOWN_SIZE || total <= 0) 0f
        else (done.toFloat() / total * ACTIVATION_FRACTION).coerceIn(0f, ACTIVATION_FRACTION)

    private fun assetSize(path: String): Long =
        runCatching { assets.openFd(path).use { it.length } }.getOrDefault(UNKNOWN_SIZE)

    /** The ABI directories this APK actually carries under `assets/runtime`. */
    private fun bundledAbis(): List<String> =
        runCatching { assets.list(RUNTIME_ASSETS)?.toList().orEmpty() }.getOrDefault(emptyList())

    private fun selectAbi(bundled: List<String>): String = chooseAbi(Build.SUPPORTED_ABIS.orEmpty().toList())

    private companion object {
        const val UNKNOWN_SIZE = -1L

        /** Unpacking is reported over 0..0.95; activation owns the rest. */
        const val ACTIVATION_FRACTION = 0.95f

        const val SYMLINKS_ENTRY = "SYMLINKS.txt"

        /** Where the image keeps the tool-call guard, relative to `$PREFIX`. */
        const val SAFETY_GUARD_SOURCE = "share/pikit/pi-safety-guard.ts"

        /** The package's own tool for choosing the `$LD_PRELOAD` library. */
        const val PRELOAD_SETUP_TOOL = "bin/termux-exec-ld-preload-lib"

        /**
         * A backstop for the repair above, which copies one small file: long enough
         * for a loaded phone, short enough that a wedged install still finishes.
         */
        const val PRELOAD_REPAIR_TIMEOUT_SECONDS = 30L

        /** Termux's own bootstrap finalisation, relative to `$PREFIX`. */
        const val SECOND_STAGE_SCRIPT =
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"

        /** The lock that same script creates for itself, to run only once. */
        const val SECOND_STAGE_LOCK = "$SECOND_STAGE_SCRIPT.lock"

        /** The login-shell hook that would otherwise run the second stage. */
        const val FALLBACK_SCRIPT = "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh"

        /** The second stage transcript, inside the app's private files directory. */
        const val SECOND_STAGE_LOG = "bootstrap-second-stage.log"

        /**
         * A backstop rather than an expectation: the bootstrap's own `postinst`
         * scripts are small, but this runs during first launch, and a maintainer
         * script that waits on a network or a lock must not make the install look
         * hung forever.
         */
        const val SECOND_STAGE_TIMEOUT_SECONDS = 120L

        const val KILL_GRACE_SECONDS = 5L

        const val NULL_DEVICE = "/dev/null"

        const val EXIT_OK = 0

        /** The script had to be killed; not an exit code the shell produced. */
        const val TIMED_OUT = -1

        /** `ProcessBuilder.start()` or `waitFor` threw. */
        const val COULD_NOT_START = -2

        const val TAG = "PiKit"

        /** Termux writes `target←link` lines; U+2190 is the separator. */
        const val SYMLINK_SEPARATOR = "\u2190"
    }
}
