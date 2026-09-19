package pi.kit.mob.pi

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches each conversation's searchable text, keyed by its file.
 *
 * Re-reading every transcript on every keystroke would parse megabytes of JSON per
 * character typed. A file is re-read only when its modification time or its length
 * changed, which is what a transcript does when it grows; the length is checked as
 * well as the stamp because a file rewritten inside the filesystem's timestamp
 * granularity would otherwise keep a stale text.
 *
 * The map is concurrent because the scan is cancelled cooperatively: a superseded
 * search can be finishing a file while the next one starts, and both would touch
 * the same entries. Failed reads are cached as empty rather than retried on every
 * keystroke.
 *
 * Android-free on purpose, like `ConversationReducer`: the staleness rule is the
 * part most likely to be wrong, and a JVM test can pin it without a device.
 */
class SessionSearchIndex {
    private class Entry(val modifiedAt: Long, val length: Long, val text: String)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * The conversation's searchable prose, from the cache when the file has not
     * changed since it was read.
     *
     * A file that has been deleted reads as empty rather than throwing: a search
     * can outlive the conversation it was started for, and a listing that has not
     * caught up is not an error.
     */
    fun text(file: File): String {
        val key = file.absolutePath
        val modifiedAt = file.lastModified()
        val length = file.length()
        entries[key]?.let { cached ->
            if (cached.modifiedAt == modifiedAt && cached.length == length) return cached.text
        }
        val text = runCatching {
            file.bufferedReader().use { reader -> SessionMetadata.searchText(reader.lineSequence()) }
        }.getOrDefault("")
        entries[key] = Entry(modifiedAt, length, text)
        return text
    }

    /** Forgets every conversation that is no longer listed. */
    fun retain(paths: Set<String>) {
        entries.keys.retainAll(paths)
    }
}
