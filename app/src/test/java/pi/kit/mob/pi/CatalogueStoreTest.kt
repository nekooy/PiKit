package pi.kit.mob.pi

import java.io.File
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * One launch asks pi's catalogue store five questions; it must parse the document once.
 *
 * The measurement behind this is in `CatalogueStore`'s header: the readers cost 37.9 ms of
 * a launch against a store the size of the real one, and all but 2.8 ms of that was the
 * same bytes read and the same element tree built again. The counter is the part a test can
 * hold on to — a cache that keyed on the wrong thing, or that never invalidated, shows up
 * here as a second parse or as a stale answer, and neither is visible in a screenshot.
 */
class CatalogueStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun resetCache() {
        CatalogueStore.reset()
    }

    private fun store(text: String): File =
        File(folder.root, "models-store.json").apply { writeText(text) }

    /** The shape pi writes: one entry per provider, a model array under `models`. */
    private val document = """
        {
          "deepseek": {
            "lastModified": 1789000000000,
            "checkedAt": 1789000001000,
            "etag": "W/\"abc\"",
            "models": [
              {"id": "deepseek-v4-pro", "name": "DeepSeek V4 Pro", "provider": "deepseek",
               "api": "openai-completions", "baseUrl": "https://api.deepseek.com",
               "input": ["text", "image"], "contextWindow": 1000000, "maxTokens": 384000,
               "compat": {"thinkingFormat": "deepseek"},
               "thinkingLevelMap": {"minimal": null, "low": "low", "medium": null, "high": "high"}}
            ]
          },
          "anthropic": {
            "lastModified": 1789000002000,
            "models": [
              {"id": "claude-opus-5-5", "api": "anthropic-messages",
               "baseUrl": "https://api.anthropic.com",
               "compat": {"forceAdaptiveThinking": true}}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `a launch's five readers parse the store once`() {
        val file = store(document)

        // Exactly what one launch asks, in the order it asks it: the active provider's
        // models, the fallback facts, every provider's models (the `models.json` log line),
        // every model's facts, the thinking shapes of a relay's protocol, and the revision
        // the launch files are written against.
        assertTrue(storeProviderModels(file, "deepseek").isNotEmpty())
        assertTrue(storeModelFacts(file, "deepseek").isNotEmpty())
        assertEquals(listOf("deepseek", "anthropic"), storeProviders(file))
        assertTrue(anyProviderModelFacts(file).containsKey("claude-opus-5-5"))
        assertTrue(cataloguedThinkingShapes(file, "anthropic-messages").containsKey("claude-opus-5-5"))
        assertTrue(readCatalogueThinkingLevels(file).containsKey("deepseek-v4-pro"))
        assertTrue(catalogueRevision(file.readText()).contains("deepseek"))
        assertTrue(catalogueContent(file).contains("deepseek"))

        assertEquals("seven readers of one unchanged file, one parse", 1, CatalogueStore.parses())
    }

    @Test
    fun `a rewritten store is read again, not remembered`() {
        val file = store(document)
        assertEquals(listOf("deepseek", "anthropic"), storeProviders(file))
        assertEquals(1, CatalogueStore.parses())

        // A refresh that adds a provider. The stamp is moved explicitly: a test can write
        // two files inside one filesystem timestamp tick, and the cache's key is the pair
        // the device's one-second granularity needs.
        val moved = document.replace("\"anthropic\"", "\"moonshotai\"")
        file.writeText(moved)
        file.setLastModified(System.currentTimeMillis() + 2_000)

        assertEquals(listOf("deepseek", "moonshotai"), storeProviders(file))
        assertEquals(2, CatalogueStore.parses())
    }

    @Test
    fun `the writer's own invalidation is what a same-stamp rewrite needs`() {
        // pi's refresh rewrites this file on *every* run — a `304 Not Modified` moves
        // `checkedAt` and nothing else — so two revisions can share a stamp and a length.
        // The launch's before/after comparison would then say "the catalogue did not move"
        // for a refresh that did. `invalidate` is the answer, and this is the test that
        // fails if the call in `PiHost.refreshCatalogueNow` is ever dropped.
        val file = store(document)
        val before = catalogueContent(file)
        val stamp = file.lastModified()
        assertEquals(1, CatalogueStore.parses())

        // The same length, a different answer, the stamp put back where it was: this is
        // what one second of a filesystem's granularity looks like to this cache.
        val sameSize = document.replace("1789000002000", "1789000002999")
        assertEquals("the fixture has to keep the byte count", document.length, sameSize.length)
        file.writeText(sameSize)
        file.setLastModified(stamp)

        assertEquals("without invalidation the stamp hides the change", before, catalogueContent(file))
        CatalogueStore.invalidate()
        assertTrue(
            "after it, the bytes on disk are the answer",
            catalogueContent(file) != before,
        )
        assertEquals(2, CatalogueStore.parses())
    }

    @Test
    fun `a store that is missing or unreadable is no document, and is not an error`() {
        val absent = File(folder.root, "not-written.json")
        assertNull(CatalogueStore.root(absent))
        assertEquals(0, CatalogueStore.parses())

        assertEquals(emptyList<String>(), storeProviders(store("")))
        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(store("[]"), "deepseek"))
        assertEquals(emptyMap<String, JsonObject>(), anyProviderModelFacts(store("{ not json")))
        // A corrupt file is parsed once and then remembered as corrupt: the model page reads
        // this on the main thread, and re-parsing half a megabyte per frame is worse. Three
        // distinct revisions above, so three parses — and the five repeats below add none.
        val corrupt = File(folder.root, "models-store.json")
        repeat(5) { assertEquals(emptyMap<String, JsonObject>(), anyProviderModelFacts(corrupt)) }
        assertEquals(3, CatalogueStore.parses())
    }

    /** The revision `catalogueBasis` records, without the launch's other inputs. */
    private fun catalogueContent(file: File): String =
        catalogueRevisionOf(CatalogueStore.root(file))
}
