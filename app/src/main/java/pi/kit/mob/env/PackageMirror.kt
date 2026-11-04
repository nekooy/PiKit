package pi.kit.mob.env

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The apt mirror, chosen by measuring the mirrors the bundled termux-tools ships.
 *
 * Termux's own `termux-change-repo` offers a "Mirror group" answer whose last step is
 * `pkg --check-mirror update`, and that command does **not** measure anything: it tests
 * every mirror for reachability and then picks one of the reachable ones **at random,
 * weighted by each mirror file's `WEIGHT`** (`packages-cf.termux.dev` carries 10). On a
 * phone in China that can land on a mirror on another continent that happens to answer,
 * which is a choice that costs minutes on the first `pkg install` rather than saving them.
 * Measured on the emulator: with every mirror reachable, one `--check-mirror update` run
 * picked `mirrors.in.sahilister.net`, and the previous run picked the Cloudflare host.
 *
 * So the choice here is a real race: every mirror file under
 * `$PREFIX/etc/termux/mirrors` is a shell fragment naming its `MAIN=` repository, and the
 * fastest ones to answer `dists/stable/InRelease` win. The winners are **pinned**, not just
 * written into `sources.list`: the pin is a `chosen_mirrors` group — the shape
 * `termux-change-repo` itself writes — because that is the one thing `pkg` will not
 * overwrite on its next check, and a group lets `pkg` fall back to the second winner when
 * the first stops answering. A `chosen_mirrors` the reader created is never touched — see
 * `PiHost.ensureTerminalSetup`.
 *
 * The race is bounded rather than exhaustive in time: [PROBE_TIMEOUT_MS] per mirror and
 * [PROBE_CONCURRENCY] at once, so one dead mirror costs a slot for its timeout and no more.
 */
internal object PackageMirror {

    /** One bundled mirror: the file that describes it, and the main repository it names. */
    data class Mirror(val file: File, val main: String)

    /** How long one mirror gets to answer, connection and first byte together. */
    const val PROBE_TIMEOUT_MS = 4_000

    /** How many mirrors are measured at once. Termux's own check uses ten. */
    const val PROBE_CONCURRENCY = 10

    /** How many of the fastest mirrors the pin offers `pkg`, so a dead one is not fatal. */
    const val PINNED_CANDIDATES = 3

    /** The directory under `mirrors/` that holds the pinned candidates. */
    private const val PIN_GROUP = "pikit"

    /**
     * The `WEIGHT` of the fastest candidate, and of each fallback.
     *
     * `pkg` picks a reachable group member at random, weighted: at 10:1:1 the measured
     * fastest is chosen ten times in twelve when it answers, and a fallback still gets its
     * turn. The scale is Termux's own — its default mirror file carries `WEIGHT=10` — and
     * the weight is ignored for a mirror that failed the availability test, so a dead
     * fastest falls straight through to the others.
     */
    private const val FASTEST_WEIGHT = 10
    private const val FALLBACK_WEIGHT = 1

    private val WEIGHT_LINE = Regex("(?m)^\\s*WEIGHT\\s*=.*$")

    private val MAIN_LINE = Regex("""^\s*MAIN\s*=\s*"?([^"\s]+)"?\s*$""")

    /**
     * Every mirror the bundled termux-tools lists, from the default file and each group.
     *
     * The default file is not inside a group directory, and the group directories carry
     * the same hosts in different regions — `packages.termux.dev` appears in `europe` and
     * as the default — so the result is de-duplicated by repository URL rather than by
     * file name. A file without a `MAIN=` line is not a mirror and is skipped.
     */
    fun bundled(prefix: File): List<Mirror> {
        val root = File(prefix, "etc/termux/mirrors")
        val files = buildList {
            val default = File(root, "default")
            if (default.isFile) add(default)
            GROUPS.forEach { group ->
                File(root, group).listFiles()
                    ?.filter { it.isFile }
                    ?.sortedBy { it.name }
                    ?.let(::addAll)
            }
        }
        return files.mapNotNull(::parse).distinctBy { it.main }
    }

    /** The mirror the `pkg` script will prefer over its own pick, if the reader set one. */
    fun chosen(prefix: File): File = File(prefix, "etc/termux/chosen_mirrors")

    /** Whether a `chosen_mirrors` link or file exists, including a dangling one. */
    fun hasChosen(prefix: File): Boolean {
        val chosen = chosen(prefix)
        return chosen.exists() || runCatching { Files.isSymbolicLink(chosen.toPath()) }.getOrDefault(false)
    }

    /**
     * The fastest [count] mirrors in [mirrors] whose repository index answers.
     *
     * More than one, because the pin is a **group**: `pkg` tests every file in the group and
     * may fall back to the second when the first stops answering. A single pinned mirror
     * has no fallback at all — `pkg` answers "None of the mirrors are accessible" and stops
     * — which is the failure the extra two names are for, and all of them were fast in the
     * same race, so a fallback is not a slow mirror.
     *
     * [probe] is a parameter so the selection is testable on the JVM with a fake clock; the
     * real one is [inReleaseLatency].
     */
    suspend fun fastest(
        mirrors: List<Mirror>,
        count: Int = PINNED_CANDIDATES,
        probe: suspend (String) -> Long? = ::inReleaseLatency,
    ): List<Mirror> = coroutineScope {
        if (mirrors.isEmpty() || count <= 0) return@coroutineScope emptyList()
        val permits = Semaphore(PROBE_CONCURRENCY)
        mirrors.map { mirror ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    val latency = runCatching { probe(mirror.main) }.getOrNull()
                    if (latency == null) null else mirror to latency
                }
            }
        }.awaitAll()
            .filterNotNull()
            .sortedBy { it.second }
            .take(count)
            .map { it.first }
    }

    /**
     * Milliseconds until [main]'s `dists/stable/InRelease` starts arriving, or null.
     *
     * The index file is the first thing `apt update` asks for, and it is small (about
     * 14 KB), so this measures the round trip that the repository's *every* request pays —
     * DNS, TLS and the first byte — without downloading a package. One byte is read and
     * the stream closed: the body is not the subject.
     */
    fun inReleaseLatency(main: String): Long? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(main.trimEnd('/') + "/dists/stable/InRelease").openConnection()
                as? HttpURLConnection) ?: return null
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "PiKit")
            val start = System.nanoTime()
            if (connection.responseCode !in 200..299) {
                null
            } else {
                connection.inputStream.use { it.read() }
                (System.nanoTime() - start) / 1_000_000
            }
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Pins [winners] as a group and points apt at the first of them.
     *
     * The group is a directory of *copies* of the mirror files: `pkg` reads a
     * `chosen_mirrors` directory with `find … -type f`, and `find` does not follow a
     * symlinked file, so copies are the only shape that works. The copies are weighted
     * ([FASTEST_WEIGHT] against [FALLBACK_WEIGHT]) so `pkg`'s own weighted pick keeps
     * choosing the measured fastest while still having a fallback. The directory is
     * rewritten from scratch, so a re-run cannot keep a stale candidate. `chosen_mirrors`
     * is a symlink to that directory — the same shape `termux-change-repo` writes for a
     * mirror *group* — and `sources.list` is written byte-for-byte in the shape `pkg`
     * writes (`deb <MAIN> stable main`), because the script parses it to decide whether the
     * current mirror is still the one it chose. The bootstrap's other repositories
     * (`root`, `x11`) exist only where the image configures them, and this image does not.
     */
    fun apply(prefix: File, winners: List<Mirror>): Boolean = runCatching {
        if (winners.isEmpty()) return false
        val group = File(prefix, "etc/termux/mirrors/$PIN_GROUP")
        group.listFiles()?.forEach { it.delete() }
        group.mkdirs()
        winners.forEachIndexed { index, mirror ->
            val weight = if (index == 0) FASTEST_WEIGHT else FALLBACK_WEIGHT
            File(group, "$index-${mirror.file.name}").writeText(
                weightedMirrorFile(mirror.file.readText(), weight),
            )
        }
        File(prefix, "etc/apt/sources.list").writeText("deb ${winners.first().main} stable main\n")
        val link = chosen(prefix)
        link.parentFile?.mkdirs()
        // Delete first, and as a link: a stale pin to another mirror would otherwise be
        // followed and the new one would never be written.
        if (link.exists() || Files.isSymbolicLink(link.toPath())) link.delete()
        Files.createSymbolicLink(link.toPath(), group.toPath())
        true
    }.getOrDefault(false)

    /**
     * One mirror file with its `WEIGHT` replaced, for the copies the pin holds.
     *
     * The files are shell fragments, and `pkg` sources them for `MAIN` and `WEIGHT`; a file
     * without a weight line gets one appended so the bias does not silently disappear when
     * a mirror file leaves it out.
     */
    internal fun weightedMirrorFile(text: String, weight: Int): String {
        val line = "WEIGHT=$weight"
        // Matched rather than compared: a fallback whose weight already equals its file's
        // would leave the text unchanged, and an "unchanged means absent" test then
        // appended a second line.
        if (WEIGHT_LINE.containsMatchIn(text)) return WEIGHT_LINE.replace(text) { line }
        val trimmed = text.trimEnd('\n')
        return "$trimmed\n$line\n"
    }

    /** The region directories the bundled termux-tools ships, `default` excluded. */
    private val GROUPS = listOf(
        "asia",
        "chinese_mainland",
        "europe",
        "north_america",
        "oceania",
        "russia",
    )

    private fun parse(file: File): Mirror? {
        val main = runCatching {
            file.useLines { lines ->
                lines.firstNotNullOfOrNull { MAIN_LINE.find(it)?.groupValues?.get(1) }
            }
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Mirror(file, main)
    }
}
