package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

/**
 * What the model page reads out of a catalogued model's own entry.
 *
 * Two things are load-bearing and neither is visible in the app's behaviour until it is
 * badly wrong:
 *
 *  - the **whitelist**, because these facts are what the page shows as a number's starting
 *    point and what the definition writer copies into an entry — a key pi's `ModelDefinition`
 *    schema does not know makes the whole `models.json` a rejected document, which takes
 *    every provider in the file with it, the user's own relay included;
 *  - the **two dropped keys**, `api` and `baseUrl`, because these same facts are what a
 *    custom endpoint's model inherits from a same-named catalogued id. Copying the
 *    catalogue's endpoint into a relay's declaration is a request that leaves for
 *    `api.deepseek.com` with the user's relay key, and a test is the only place that can
 *    see it before it does.
 */
class CatalogueFactsTest {

    private fun model(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    /** What pi actually answers with: a resolved `Model`, not a `ModelDefinition`. */
    private val resolved = model(
        """
        {
          "id": "deepseek-v4-flash",
          "name": "DeepSeek V4 Flash",
          "api": "openai-completions",
          "provider": "deepseek",
          "baseUrl": "https://api.deepseek.com",
          "reasoning": true,
          "input": ["text", "image"],
          "cost": {"input": 0.14, "output": 0.28, "cacheRead": 0.0028, "cacheWrite": 0},
          "contextWindow": 1000000,
          "maxTokens": 384000,
          "thinkingLevelMap": {"low": "low", "high": "high", "max": "max"}
        }
        """.trimIndent(),
    )

    @Test
    fun `a catalogued model's facts are keyed by id and carry the three parameters`() {
        val facts = cataloguedModelFacts(listOf(resolved))

        val entry = facts.getValue("deepseek-v4-flash")
        assertEquals("1000000", entry["contextWindow"]!!.jsonPrimitive.content)
        assertEquals("384000", entry["maxTokens"]!!.jsonPrimitive.content)
        // The image switch reads this list, so it has to be pi's own capability list rather
        // than a boolean this app inferred from it.
        assertEquals(
            listOf("text", "image"),
            (entry["input"] as JsonArray).map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `the endpoint is dropped, so a relay never inherits the catalogue's host`() {
        val entry = cataloguedModelFacts(listOf(resolved)).getValue("deepseek-v4-flash")

        assertNull("api must not travel into a custom endpoint", entry["api"])
        assertNull("nor the base URL", entry["baseUrl"])
        // And nothing of `Model` that its schema does not know either — `provider` is the
        // field that would make pi reject the document.
        assertNull(entry["provider"])
        assertNull(entry["id"])
        assertNull(entry["name"])
    }

    @Test
    fun `an entry with no id is skipped rather than keyed by nothing`() {
        val facts = cataloguedModelFacts(listOf(model("""{"contextWindow": 1000}"""), resolved))

        assertEquals(setOf("deepseek-v4-flash"), facts.keys)
    }

    @Test
    fun `a blank or missing field is absent rather than named`() {
        // "Absent" is the state the page reads as "the catalogue did not say", and naming a
        // field with a null is a document pi's schema rejects (`Optional` means absent).
        val sparse = cataloguedModelFacts(
            listOf(model("""{"id":"m","baseUrl":"","contextWindow":1000}""")),
        ).getValue("m")

        assertNull(sparse["baseUrl"])
        assertNull(sparse["maxTokens"])
        assertEquals(setOf("contextWindow"), sparse.keys)
    }

    @Test
    fun `a hand-written list of models is what the page keys on, ids included`() {
        // The shape `get_available_models` sends is `{models: [...]}`, and the id is the
        // only join between the probe and the model the page is drawing a row for.
        val facts = cataloguedModelFacts(
            listOf(resolved, model("""{"id":"deepseek-v4-pro","contextWindow":1000000}""")),
        )

        assertEquals(setOf("deepseek-v4-flash", "deepseek-v4-pro"), facts.keys)
        assertEquals(
            "1000000",
            facts.getValue("deepseek-v4-pro")["contextWindow"]!!.jsonPrimitive.content,
        )
        assertNull("and each entry carries only its own facts", facts.getValue("deepseek-v4-pro")["maxTokens"])
    }

    // ------------------------------------------- the same facts, off disk

    /** A store of the shape pi writes: one entry per provider, an id array under `models`. */
    @get:Rule
    val folder = TemporaryFolder()

    private fun store(text: String): java.io.File =
        java.io.File(folder.root, "models-store.json").apply { writeText(text) }

    private val storeDocument = """
        {
          "deepseek": {
            "lastModified": 1789000000000,
            "checkedAt": 1789000001000,
            "etag": "W/\"abc\"",
            "models": [
              {"id": "deepseek-v4-pro", "name": "DeepSeek V4 Pro", "provider": "deepseek",
               "api": "openai-completions", "baseUrl": "https://api.deepseek.com",
               "input": ["text"], "contextWindow": 1000000, "maxTokens": 384000,
               "cost": {"input": 0.435, "output": 0.87}},
              {"id": "deepseek-v4-flash", "contextWindow": 1000000, "maxTokens": 384000}
            ]
          },
          "minimax": {"lastModified": 0, "checkedAt": 1, "models": []}
        }
    """.trimIndent()

    @Test
    fun `a store of the shape pi writes answers for a provider with no process at all`() {
        // The point of reading the store: this question used to cost a node start, which is
        // the app's slowest and most fragile path. The store is pi's own copy of pi.dev's
        // per-provider catalogue, refreshed by the launch path for every built-in provider.
        val facts = storeModelFacts(store(storeDocument), "deepseek")

        assertEquals(setOf("deepseek-v4-pro", "deepseek-v4-flash"), facts.keys)
        assertEquals(
            "1000000",
            facts.getValue("deepseek-v4-pro")["contextWindow"]!!.jsonPrimitive.content,
        )
        // The same two dropped keys as the process path: these facts are also what a custom
        // endpoint inherits from a same-named id, so the catalogue's own endpoint must not
        // travel with them.
        assertNull(facts.getValue("deepseek-v4-pro")["baseUrl"])
        assertNull(facts.getValue("deepseek-v4-pro")["api"])
    }

    @Test
    fun `a provider with no entry in the store is empty rather than an error`() {
        // `minimax` here is the shape a provider with no models of its own gets. Note what
        // the caller must *not* conclude: absence from the store is not "pi does not know
        // this id" — the store is only pi.dev's half of the catalogue, and pi's built-in half
        // lives inside its package. That is why the process is still there for an id the
        // store cannot answer for.
        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(store(storeDocument), "minimax"))
        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(store(storeDocument), "anthropic"))
    }

    @Test
    fun `a store that is missing, blank or not an object answers nothing`() {
        // Each of these leaves the process as the only recourse, which is the behaviour
        // before there was a store to read at all. None of them may throw: this runs on the
        // model page's effect, and an exception here is a form that never finishes loading.
        val absent = java.io.File(folder.root, "not-written.json")

        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(absent, "deepseek"))
        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(store(""), "deepseek"))
        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(store("[]"), "deepseek"))
        assertEquals(emptyMap<String, JsonObject>(), storeModelFacts(store("{ not json"), "deepseek"))
        // A provider whose entry is the wrong shape is the same as one that is absent.
        assertEquals(
            emptyMap<String, JsonObject>(),
            storeModelFacts(store("""{"deepseek":{"models":"oops"}}"""), "deepseek"),
        )
        // And an element of the array that is not an object is skipped, not fatal — pi's file
        // is written by pi, but the user's own `models.json` habits are not pi's.
        assertEquals(
            setOf("a"),
            storeModelFacts(store("""{"p":{"models":["oops",{"id":"a"}]}}"""), "p").keys,
        )
    }
}
