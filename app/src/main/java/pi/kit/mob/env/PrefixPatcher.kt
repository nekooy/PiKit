package pi.kit.mob.env

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Re-applies the prefix relocation after the user installs packages.
 *
 * The runtime image ships already relocated to this app's prefix, so nothing
 * needs doing on first launch. But `pkg install` (and `apt`, and `npm -g`)
 * download artefacts compiled for `/data/data/com.termux/files/usr`, and those
 * arrive with the upstream prefix baked into their shebangs and `DT_RUNPATH`.
 * A freshly installed binary whose `DT_RUNPATH` points at a directory that does
 * not exist cannot resolve its libraries, so it fails at exec time — which
 * looks like the package itself is broken.
 *
 * This walks the prefix and rewrites the package id. The replacement is the
 * same length as the original, so every byte offset, ELF section offset and
 * internal string length is preserved: the file changes meaning without
 * changing shape, and no recompilation is needed.
 *
 * Deliberately conservative:
 *
 *  - **Compressed files are skipped.** Their contents are invisible to a byte
 *    scan and editing them would destroy the stream.
 *  - **`com/termux` is never touched.** Every occurrence of the slash form is a
 *    `github.com/termux/...` URL in documentation or package metadata.
 *  - **Symlinks are handled separately**, because a link target is filesystem
 *    metadata rather than file content and no byte scan over regular files can
 *    reach it.
 *  - **Writes are atomic** (temp file + rename), because this runs against a
 *    live environment that another process may be reading.
 *  - **It is idempotent**: the replacement does not contain the original, so
 *    re-running is a no-op.
 */
class PrefixPatcher(private val env: TermuxEnv) {

    data class Result(
        val filesScanned: Int = 0,
        val filesRewritten: Int = 0,
        val occurrences: Int = 0,
        val symlinksRewritten: Int = 0,
        val skippedCompressed: Int = 0,
        val modesFixed: Int = 0,
        val errors: List<String> = emptyList(),
        /**
         * Set when the runtime's relocator is itself damaged, so a repair cannot
         * help and the caller must say what will. See [relocatorIsIntact].
         *
         * A flag rather than an entry in [errors] because this one is a message
         * for the user, translated in the catalogs, while [errors] are developer
         * diagnostics about individual files.
         */
        val relocatorDamaged: Boolean = false,
    ) {
        val changed: Boolean
            get() = filesRewritten > 0 || symlinksRewritten > 0 || modesFixed > 0
    }

    /** The package id baked into the shipped image. */
    private val upstream = UPSTREAM_PACKAGE_ID.toByteArray(Charsets.US_ASCII)

    /** This installation's package id; must be the same length. */
    private val replacement = env.packageId.toByteArray(Charsets.US_ASCII)

    /**
     * Paths, relative to the walk's root, that must be left exactly as they are.
     *
     * The same list the build applies (`prefix_patch.NEVER_REWRITE`), read from
     * `$PREFIX/share/pikit/never-rewrite.txt` rather than repeated here so the two
     * cannot drift. It is the list of files the build writes itself, and the one
     * that matters is `libexec/pikit/relocate.js`: it has to *name* the upstream id,
     * because that is what it rewrites away from.
     *
     * This class did not know about the list, and the omission was not academic.
     * Pressing "check and repair" on a freshly installed image rewrote three files
     * — `bin/dpkg`, the apt hook and the relocator — turning the relocator's
     * `OLD_ID` into `NEW_ID`. From then on it refused to run at all
     * (`relocator misconfigured: OLD_ID === NEW_ID`, exit 4), every package the
     * user installed afterwards went unrelocated, and the storage self-test's two
     * relocator checks failed. A repair that breaks the thing that does the
     * repairs is exactly what this list exists to prevent.
     */
    /**
     * Paths, relative to the walk's root, that must be left exactly as they are.
     *
     * Read **once per pass**, not once per file. It was `isProtected(relative)`
     * calling this, which opened and parsed the list for every one of the twenty-odd
     * thousand files a repair walks — the same file, unchanged, twenty thousand
     * times. Nothing writes it while the walk runs; the only writer is the image
     * build.
     */
    private fun readProtectedPaths(): Set<String> {
        val listed = runCatching {
            File(env.prefix, PROTECTED_LIST_NAME)
                .takeIf { it.isFile }
                ?.readLines()
                ?.map(String::trim)
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?.toSet()
        }.getOrNull()
        // The union, not the file *or* the constant: the constant is the set that is
        // known to be dangerous to rewrite, and the file is what the build applied.
        // A file that is stale, partial or empty therefore cannot drop protection —
        // and an image built before the file existed is covered by the constant
        // alone (its copies are already rewritten by the build, so the walk would
        // find nothing in them anyway).
        return FALLBACK_PROTECTED + listed.orEmpty()
    }

    /**
     * True when the relocator in this runtime still recognises the upstream id.
     *
     * A runtime damaged by the version of this class that had no exclusion list is
     * not repairable by this pass: `libexec/pikit/relocate.js` no longer contains
     * `com.termux` at all, so `pikit-relocate` answers every run with
     * `relocator misconfigured: OLD_ID === NEW_ID` and exits 4. The walk would
     * happily report "nothing to relocate" while every package the user installs
     * still lands unrelocated, which is the worst of both answers. A runtime
     * without the script at all (an image from before it existed) is not this
     * check's business and reports as intact.
     */
    private fun relocatorIsIntact(): Boolean {
        val script = File(env.prefix, RELOCATOR_NAME)
        if (!script.isFile) return true
        return runCatching { script.readBytes().indexOfSubArray(upstream) >= 0 }.getOrDefault(true)
    }

    /**
     * Rewrites [root] (defaults to `$PREFIX`). Returns what was changed, so the
     * caller can decide whether to tell the user anything.
     *
     * [onProgress] is called every [PROGRESS_EVERY] files with the number scanned
     * so far. The walk is over twenty thousand files, so a caller that shows
     * nothing while it runs looks broken; it is a callback rather than a flow here
     * because this class has no dispatcher of its own.
     */
    fun repair(root: File = env.prefix, onProgress: ((Int) -> Unit)? = null): Result {
        if (replacement.size != upstream.size) {
            return Result(
                errors = listOf(
                    "Cannot relocate: package id '${env.packageId}' is " +
                        "${replacement.size} characters but the image was built for a " +
                        "${upstream.size}-character id. The rewrite must be the same " +
                        "length or it would corrupt every ELF file.",
                ),
            )
        }
        if (root == env.home || root.absolutePath.startsWith(env.homePath)) {
            // $HOME is the user's data; the prefix is the only thing compiled
            // against the old package id.
            return Result(errors = listOf("refusing to rewrite the home directory"))
        }
        // The same boundary [SafeDelete] enforces, for the same reason: this walks
        // a tree and writes to every file it finds, so a root outside the app's own
        // data directory is a way to damage files that have nothing to do with the
        // runtime. `~/storage` points into shared storage, and a caller that
        // resolved a link has to be refused rather than obeyed.
        if (!isInsideDataDirectory(root)) {
            return Result(
                errors = listOf(
                    "refusing to rewrite ${root.absolutePath}: it is outside this app's " +
                        "private directory, and only the runtime prefix ever names the old " +
                        "package id",
                ),
            )
        }
        // An install damaged by the version of this class that had no exclusion
        // list: it rewrote `libexec/pikit/relocate.js`'s `OLD_ID` into `NEW_ID`, so
        // the relocator now refuses to run and every package installed since is
        // unrelocated. A pass here cannot help — the file that does the relocating
        // is the damaged one — so it says what will.
        if (!relocatorIsIntact()) return Result(relocatorDamaged = true)

        var scanned = 0
        var rewritten = 0
        var occurrences = 0
        var symlinks = 0
        var skipped = 0
        var modes = 0
        val errors = ArrayList<String>()
        val rootPath = root.absolutePath.trimEnd('/')
        val protectedPaths = readProtectedPaths()

        walk(root) { file ->
            try {
                // Before the mode fix and before the byte scan: these are the files
                // the build wrote, and rewriting one of them is what broke the
                // relocator (see [readProtectedPaths]).
                val relative = file.absolutePath.removePrefix(rootPath).trimStart('/')
                if (relative in protectedPaths) return@walk

                val target = readLinkOrNull(file)
                if (target != null) {
                    if (target.startsWith("/") && target.contains(UPSTREAM_PACKAGE_ID)) {
                        val patched = target.replace(UPSTREAM_PACKAGE_ID, env.packageId)
                        file.delete()
                        Os.symlink(patched, file.absolutePath)
                        symlinks++
                    }
                    return@walk
                }

                // Nothing in the image's install path re-applies modes, so an
                // install made before this rule existed keeps a non-executable
                // `bin/npm` forever unless it is fixed here.
                if (needsExecutableBit(relative) && !file.canExecute()) {
                    if (runCatching {
                            Os.chmod(file.absolutePath, OsConstants.S_IRWXU)
                        }.isSuccess
                    ) {
                        modes++
                    }
                }

                val blob = file.readBytes()
                scanned++
                if (onProgress != null && scanned % PROGRESS_EVERY == 0) onProgress(scanned)
                if (isCompressed(blob)) {
                    if (blob.indexOfSubArray(upstream) >= 0) skipped++
                    return@walk
                }
                val hits = blob.countOccurrences(upstream)
                if (hits == 0) return@walk

                val patched = blob.replace(upstream, replacement)
                check(patched.size == blob.size) {
                    "rewrite of ${file.name} changed its length"
                }
                writeAtomically(file, patched)
                rewritten++
                occurrences += hits
            } catch (t: Throwable) {
                if (errors.size < MAX_ERRORS) errors += "${file.name}: ${t.message}"
            }
        }

        return Result(scanned, rewritten, occurrences, symlinks, skipped, modes, errors)
    }

    private fun walk(root: File, visit: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val children = directory.listFiles() ?: continue
            for (child in children) {
                // isDirectory follows links, which we must not do: a symlink to
                // a directory would be descended into and rewritten twice.
                if (readLinkOrNull(child) == null && child.isDirectory) {
                    stack.addLast(child)
                } else {
                    visit(child)
                }
            }
        }
    }

    private fun readLinkOrNull(file: File): String? =
        runCatching { Os.readlink(file.absolutePath) }.getOrNull()

    /**
     * True when [root] is inside `$FILES_DIR`.
     *
     * Compared through `Path` rather than by string prefix: `absolutePath` uses
     * the platform separator, and on Android `/data/data/<pkg>/files` is also
     * reachable as `/data/user/0/<pkg>/files`, so normalising first is what makes
     * both spellings land inside the same boundary.
     */
    private fun isInsideDataDirectory(root: File): Boolean = runCatching {
        val boundary = env.filesDir.toPath().toAbsolutePath().normalize()
        val path = root.toPath().toAbsolutePath().normalize()
        path == boundary || path.startsWith(boundary)
    }.getOrDefault(false)

    private fun writeAtomically(file: File, blob: ByteArray) {
        // The process id keeps two concurrent passes from renaming each other's
        // half-written temporary into place. The pass is meant to be single-flight,
        // but that guard is a check-then-act on a state flow in the caller.
        val temp = File(file.parentFile, "${file.name}.${Os.getpid()}.prefix-tmp")
        temp.writeBytes(blob)
        runCatching { Os.chmod(temp.absolutePath, filePermissions(file)) }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun filePermissions(file: File): Int =
        runCatching { Os.stat(file.absolutePath).st_mode }.getOrDefault(0b110100000)

    companion object {
        /** The application id the bundled Termux packages are compiled for. */
        const val UPSTREAM_PACKAGE_ID = "com.termux"

        /**
         * Where the image records the paths the rewrite must not touch, relative
         * to `$PREFIX`. Written by `tools/build-runtime-image.py` from
         * `prefix_patch.NEVER_REWRITE`, which is the list the *build* applies.
         */
        const val PROTECTED_LIST_NAME = "share/pikit/never-rewrite.txt"

        /** The relocator, whose constant decides whether it can run at all. */
        const val RELOCATOR_NAME = "libexec/pikit/relocate.js"

        /** How often [repair] reports progress, in files. */
        const val PROGRESS_EVERY = 256

        /**
         * The same list as it stood when this list was first written into the
         * image, for a runtime built before that. Kept identical to
         * `prefix_patch.NEVER_REWRITE`; the file is authoritative when present.
         */
        private val FALLBACK_PROTECTED = setOf(
            "bin/dpkg",
            "bin/pi",
            "bin/pikit-relocate",
            "bin/pikit-storage-check",
            "etc/apt/apt.conf.d/99pikit-relocate",
            "libexec/pikit/relocate.js",
            "share/pikit/pi-safety-guard.ts",
            "share/pikit/storage-self-test.sh",
        )

        private const val MAX_ERRORS = 20

        private val COMPRESSED_MAGIC = listOf(
            byteArrayOf(0x1F, 0x8B.toByte()), // gzip
            byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00), // xz
            byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()), // zstd
            byteArrayOf(0x42, 0x5A, 0x68), // bzip2
        )

        private fun isCompressed(blob: ByteArray): Boolean =
            COMPRESSED_MAGIC.any { magic ->
                blob.size >= magic.size && magic.indices.all { blob[it] == magic[it] }
            }

        private fun ByteArray.countOccurrences(needle: ByteArray): Int {
            if (needle.isEmpty() || size < needle.size) return 0
            var count = 0
            var index = indexOfSubArray(needle)
            while (index >= 0) {
                count++
                index = indexOfSubArray(needle, index + needle.size)
            }
            return count
        }

        private fun ByteArray.indexOfSubArray(needle: ByteArray, from: Int = 0): Int {
            if (needle.isEmpty() || size < needle.size) return -1
            outer@ for (start in from..(size - needle.size)) {
                for (offset in needle.indices) {
                    if (this[start + offset] != needle[offset]) continue@outer
                }
                return start
            }
            return -1
        }

        private fun ByteArray.replace(needle: ByteArray, substitute: ByteArray): ByteArray {
            require(needle.size == substitute.size) { "replacement must preserve length" }
            val out = copyOf()
            var index = out.indexOfSubArray(needle)
            while (index >= 0) {
                substitute.copyInto(out, index)
                index = out.indexOfSubArray(needle, index + needle.size)
            }
            return out
        }
    }
}
