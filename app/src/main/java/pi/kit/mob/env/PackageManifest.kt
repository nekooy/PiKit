package pi.kit.mob.env

/**
 * The packages the user installed themselves — the ones the image does not
 * carry — recorded so a reinstall can put them back.
 *
 * A reinstall replaces `$PREFIX` wholesale, and everything `pkg install` ever
 * put there lives inside it. The image brings its own packages back by being
 * unpacked again; the user's have no such luck unless somebody remembers them.
 * This is that memory: a name list captured before the wipe, replayed against
 * the new tree afterwards, and a report saying what came back and what did not.
 *
 * The rule for "the user's" is the status file minus what the image ships: the
 * baseline is read from the tree the reinstall is *about to* activate, so a
 * package the new image happens to add counts as the image's and is not
 * reinstalled over itself. A package the image drops and the user had is
 * restored — it was theirs to keep.
 */
internal object PackageManifest {

    /** One package as dpkg's status file records it. */
    data class Entry(val name: String, val version: String)

    /**
     * Parses a dpkg `status` document into entries.
     *
     * Paragraphs are blank-line separated and field continuation lines start
     * with a space, so only lines that *start* with the field name count.
     */
    fun parseStatus(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        for (paragraph in text.split("\n\n")) {
            var name: String? = null
            var version: String? = null
            for (line in paragraph.lineSequence()) {
                when {
                    line.startsWith("Package: ") -> name = line.removePrefix("Package: ").trim()
                    line.startsWith("Version: ") -> version = line.removePrefix("Version: ").trim()
                }
            }
            if (name != null) entries.add(Entry(name, version.orEmpty()))
        }
        return entries
    }

    /** Parses the captured list: one `name=version` per line. */
    fun parseList(text: String): List<Entry> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val split = line.indexOf('=')
                if (split < 0) Entry(line, "") else Entry(line.take(split), line.drop(split + 1))
            }
            .toList()

    /** The captured list, one `name=version` per line. */
    fun renderList(entries: List<Entry>): String =
        entries.joinToString("") { "${it.name}=${it.version}\n" }

    /** Of [installed], the entries whose names [shipped] does not provide. */
    fun userInstalled(installed: List<Entry>, shipped: Set<String>): List<Entry> =
        installed.filterNot { it.name in shipped }

    /** The captured entries the reinstalled environment does not have. */
    fun missingAfter(captured: List<Entry>, installed: List<Entry>): List<Entry> {
        val present = installed.map { it.name }.toSet()
        return captured.filterNot { it.name in present }
    }

    /**
     * A union by name with [later] winning.
     *
     * Capture runs again after an interrupted restore, and the packages that
     * did not come back are no longer in `status` — so they are no longer in
     * the fresh capture either. Keeping the earlier list is what stops the
     * half of a restore that failed from being forgotten. The cost is that a
     * package the user *removed* by hand after an interrupted restore is
     * restored too; the rarer mistake, and the recoverable one.
     */
    fun merge(earlier: List<Entry>, later: List<Entry>): List<Entry> {
        val byName = LinkedHashMap<String, Entry>()
        for (entry in earlier) byName[entry.name] = entry
        for (entry in later) byName[entry.name] = entry
        return byName.values.toList()
    }

    /** What a replay did: the ones that are installed again and the ones that are not. */
    data class Report(val ready: List<Entry>, val pending: List<Entry>)

    /**
     * The report, one `ready name=version` / `pending name=version` per line.
     *
     * Line-per-record rather than JSON on purpose: the settings page shows
     * counts and the terminal can `cat` it, and neither needs a parser.
     */
    fun renderReport(report: Report): String = buildString {
        for (entry in report.ready) append("ready ${entry.name}=${entry.version}\n")
        for (entry in report.pending) append("pending ${entry.name}=${entry.version}\n")
    }

    /** Parses [renderReport]'s output back. */
    fun parseReport(text: String): Report {
        val ready = mutableListOf<Entry>()
        val pending = mutableListOf<Entry>()
        for (line in text.lineSequence()) {
            val (prefix, rest) = when {
                line.startsWith("ready ") -> ready to line.removePrefix("ready ")
                line.startsWith("pending ") -> pending to line.removePrefix("pending ")
                else -> continue
            }
            val split = rest.indexOf('=')
            val entry =
                if (split < 0) Entry(rest.trim(), "") else Entry(rest.take(split).trim(), rest.drop(split + 1).trim())
            prefix.add(entry)
        }
        return Report(ready, pending)
    }

    /**
     * Orders two package versions, Debian-style enough for a hint.
     *
     * Not dpkg's algorithm — that has rules for letters beside digits and for a
     * missing revision that only an archive build needs. This compares the epoch
     * first, then the segments `.` and `-` split the rest into, numerically when
     * both look like numbers and lexically otherwise, with `~` sorting before
     * everything (Debian's "any release is newer than a tilde"). The one
     * question it answers is "does the image ship a newer version than the
     * device has", and a tie-break that errs toward *showing* the hint is the
     * safe direction: the worst case is one line too many on a maintenance page,
     * never a missed environment.
     */
    fun compareVersions(a: String, b: String): Int {
        fun epochOf(version: String): Int =
            if (':' in version) version.substringBefore(':').toIntOrNull() ?: 0 else 0

        val epoch = epochOf(a).compareTo(epochOf(b))
        if (epoch != 0) return epoch

        fun bodyOf(version: String): String =
            if (':' in version) version.substringAfter(':') else version

        val left = bodyOf(a).split('.', '-', '_', '+')
        val right = bodyOf(b).split('.', '-', '_', '+')

        /** Numeric when both look like numbers, lexical otherwise. */
        fun comparePieces(l: String, r: String): Int {
            val ln = l.toLongOrNull()
            val rn = r.toLongOrNull()
            return if (ln != null && rn != null) ln.compareTo(rn) else l.compareTo(r)
        }

        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(index).orEmpty()
            val r = right.getOrNull(index).orEmpty()
            if (l == r) continue
            // A `~` anywhere in a segment is Debian's prerelease marker: it
            // sorts before everything, *including* a segment that simply ran
            // out — `1.0~rc1` is older than `1.0`, and `0~rc1` older than `0`,
            // which is why the marker is looked for inside the segment and not
            // only at its start. The head before the marker decides when the
            // heads differ; when they are equal, the one still carrying a
            // marker is the older.
            val lt = l.indexOf('~')
            val rt = r.indexOf('~')
            if (lt >= 0 || rt >= 0) {
                val heads = comparePieces(
                    if (lt >= 0) l.take(lt) else l,
                    if (rt >= 0) r.take(rt) else r,
                )
                if (heads != 0) return heads
                if (lt >= 0 && rt < 0) return -1
                if (rt >= 0 && lt < 0) return 1
                return comparePieces(l.drop(lt + 1), r.drop(rt + 1))
            }
            return comparePieces(l, r)
        }
        return 0
    }

    /**
     * Of [installed], the entries [shipped] names at a strictly newer version.
     *
     * The shipped map is the image's `build-metadata.json`, so this is the
     * answer a device can give offline: the environment carries versions the
     * app has already moved past. `pkg upgrade` is the fix — which is also why
     * the reverse direction is not reported. A device *ahead* of the image has
     * done the upgrading already, and "you are up to date" is the true sentence
     * there.
     */
    fun upgradable(installed: List<Entry>, shipped: Map<String, String>): List<Entry> =
        installed.filter { entry ->
            val wanted = shipped[entry.name]
            wanted != null && compareVersions(wanted, entry.version) > 0
        }
}
