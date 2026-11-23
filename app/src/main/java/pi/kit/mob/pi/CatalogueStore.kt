package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * pi's catalogue store — `$HOME/.pi/agent/models-store.json` — as one parsed document.
 *
 * ## Why this is not read at each call site
 *
 * The store is pi.dev's catalogue for every provider pi knows: on a phone with a dozen
 * providers configured it is a few hundred kilobytes of JSON, and **five readers read it
 * on one launch**. `PiAgentSession.writeModelsJson` wants one provider's models, the ids
 * the catalogue contains *now*, the fallback facts and the thinking shapes; the launch
 * also asks for every provider's ids (the `models.json` log line) and
 * `PiHost.reloadCatalogueThinkingLevels` walks every model of every provider. Each of
 * those used to `readText` the whole file and parse it again, and a launch happens on
 * every conversation start, every settings change and every restart.
 *
 * Measured on the desktop JVM against a store the size of the real one (680 KB, 32
 * providers, 1280 models): one launch's worth of readers cost **37.9 ms** of which a
 * single parse is **2.8 ms** — the rest is the same bytes read six more times and six
 * more element trees built to be thrown away. On a phone the same work is on the launch
 * path, which is a tap.
 *
 * So the document is parsed **once per revision**. The key is `(path, mtime, length)`,
 * the pair the session listing and the search index already use, and for the same
 * reason: a file rewritten inside the filesystem's timestamp granularity keeps its
 * modification time, so the stamp alone is not an answer.
 *
 * ## The one writer invalidates
 *
 * A freshness rule alone is not enough here, and the reason is specific: pi's refresh
 * rewrites the file on **every** run — including a `304 Not Modified`, whose stored
 * `checkedAt` moves while nothing else does — so two revisions can have the same length,
 * and `PiHost.refreshCatalogueNow` compares the revision *before* against the revision
 * *after* to decide whether to restart the conversations. A stale second read there would
 * answer "the catalogue did not move" for a refresh that moved it.
 *
 * So the file's only writer — `pi update --models`, run from `PiHost.refreshCatalogueNow`
 * and nowhere else — calls [invalidate] as soon as its process has exited, before the
 * after-snapshot is taken. A reader that runs while that process is alive is reading the
 * document pi is about to replace anyway; the invalidation is what makes the next read
 * the new one.
 *
 * ## What it costs, and what it does not
 *
 * One parsed tree is kept for as long as the app runs (~2–3 MB for the store above,
 * against the several hundred megabytes of Node processes the pool talks about), and it
 * is replaced — never accumulated — when the revision moves. A store that is missing,
 * unreadable or not a JSON object is cached as *no document* under its own stamp too: the
 * model page reads this on the main thread, and re-parsing a corrupt half-megabyte per
 * frame is worse than remembering that it is corrupt.
 *
 * Android-free, like the rest of pi's file shapes in this package, so the one-parse rule
 * is pinned on the JVM.
 */
internal object CatalogueStore {

    /** One document with the stamp it was read under, or a remembered failure. */
    private class Parsed(
        val path: String,
        val modifiedAt: Long,
        val length: Long,
        val root: JsonObject?,
    )

    private val lock = Any()
    private var cached: Parsed? = null
    private var parses = 0

    /**
     * The store's document, or null when it is absent or is not a JSON object.
     *
     * Null is also what an unreadable file answers, which is the contract every caller
     * here already had: absence is not an error, and the caller falls back to pi itself.
     *
     * The parse happens **inside** the lock rather than before it. Two conversations can
     * start together on one cold launch, and a second thread arriving during the parse
     * should wait for that parse and reuse it rather than start a second one — the whole
     * point of the cache is that one revision is parsed once. Nothing inside the parse
     * calls back into this object, so the lock is not re-entered.
     */
    fun root(store: File): JsonObject? {
        val path = store.absolutePath
        // Two stats, and they are the cache key: a file that is not there has no document
        // to remember, and `lastModified`/`length` on a missing file answer 0.
        if (!store.isFile) return null
        val modifiedAt = store.lastModified()
        val length = store.length()
        synchronized(lock) {
            val hit = cached
            if (hit != null && hit.path == path && hit.modifiedAt == modifiedAt && hit.length == length) {
                return hit.root
            }
            val text = runCatching { store.readText() }.getOrNull()
            val root = runCatching {
                text?.takeIf { it.isNotBlank() }?.let { JSON.parseToJsonElement(it) as? JsonObject }
            }.getOrNull()
            parses++
            cached = Parsed(path, modifiedAt, length, root)
            return root
        }
    }

    /**
     * Forgets the cached document.
     *
     * Called by the one writer — `PiHost.refreshCatalogueNow`, after `pi update --models`
     * has exited — and by nothing else. A restore is not a caller: §10.2 withholds the
     * catalogue cache from an archive in both directions, so a restore cannot replace this
     * file behind the app's back.
     */
    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    /**
     * How many parses this process has done, for the test that pins the one-parse rule.
     *
     * A counter rather than a flag because the interesting assertion is "seven readers,
     * one parse" — a cache that remembered the wrong revision would show up as a second
     * parse, and one that never invalidated as a stale answer.
     */
    internal fun parses(): Int = synchronized(lock) { parses }

    /** Forgets everything, counter included, so a test starts from a known state. */
    internal fun reset() {
        synchronized(lock) {
            cached = null
            parses = 0
        }
    }

    /**
     * The same parser the wire used, rather than a second configuration of it.
     *
     * `Json`'s defaults are what the readers used before this cache existed, and none of
     * the tolerant flags `PiRecordParser` needs for pi's stdout belong on a document pi
     * writes itself with `JSON.stringify`.
     */
    private val JSON = Json
}
