package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web configuration document: the one file the settings page writes and
 * `tools/pikit-web.ts` reads.
 *
 * Pure on purpose — no Android types — because this is a contract between two
 * languages: a field the encoder writes and the decoder does not read is a
 * setting that snaps back on screen, and a provider missing from the Kotlin table
 * is a service the page cannot configure while the extension still supports it.
 */
class WebSearchStoreTest {

    private fun decode(text: String) = decodeWebConfig(text)

    @Test
    fun `the default document round-trips`() {
        val settings = WebSearchSettings()
        val decoded = decode(encodeWebConfig(settings))
        assertEquals(settings, decoded)
    }

    @Test
    fun `every field survives a round trip`() {
        val settings = WebSearchSettings(
            enabled = false,
            searchProvider = "brave",
            maxResults = 12,
            searchTimeoutSeconds = 45,
            fetchProvider = "jina",
            maxChars = 30_000,
            fetchTimeoutSeconds = 60,
            providers = mapOf(
                "brave" to WebProviderConfig(apiKey = "brv-key"),
                "searxng" to WebProviderConfig(baseUrl = "https://search.example.com"),
                "cloudflare" to WebProviderConfig(apiKey = "cf-key", accountId = "acct"),
                "openai" to WebProviderConfig(apiKey = "sk-key", model = "gpt-4.1-mini"),
            ),
        )

        assertEquals(settings, decode(encodeWebConfig(settings)))
    }

    @Test
    fun `empty provider entries are not written`() {
        val settings = WebSearchSettings(
            providers = mapOf(
                "brave" to WebProviderConfig(),
                "tavily" to WebProviderConfig(apiKey = "tv-key"),
            ),
        )
        val root = Json.parseToJsonElement(encodeWebConfig(settings)).jsonObject
        val providers = root["providers"]!!.jsonObject

        assertEquals(setOf("tavily"), providers.keys)
    }

    @Test
    fun `a missing key takes its default`() {
        val decoded = decode(
            """{"enabled":true,"search":{"provider":"duckduckgo"},"fetch":{},"providers":{}}""",
        )!!

        assertEquals(WebSearchSettings.DEFAULT_MAX_RESULTS, decoded.maxResults)
        assertEquals(WebSearchSettings.DEFAULT_MAX_CHARS, decoded.maxChars)
        assertEquals(WebSearchSettings.DEFAULT_TIMEOUT_SECONDS, decoded.searchTimeoutSeconds)
        assertEquals(WebSearchSettings.DEFAULT_TIMEOUT_SECONDS, decoded.fetchTimeoutSeconds)
        assertEquals(WebSearchSettings.FETCH_AUTO, decoded.fetchProvider)
    }

    @Test
    fun `a document that is not an object is unreadable`() {
        assertNull(decode("[]"))
        assertNull(decode("\"text\""))
        assertNull(decode("{"))
        assertNull(decode(""))
    }

    @Test
    fun `a value of the wrong type makes the document unreadable`() {
        assertNull(decode("""{"search":{"maxResults":"eight"}}"""))
        assertNull(decode("""{"providers":{"brave":{"apiKey":42}}}"""))
    }

    @Test
    fun `the provider table contains every service the extension supports`() {
        val expected = setOf(
            "deepseek", "zai", "exa", "exa-free", "duckduckgo", "tavily", "ollama",
            "brave", "jina", "openai", "gemini", "kimi", "xai", "searxng",
            "cloudflare", "searchapi",
        )

        assertEquals(expected, WebSearchSettings.WEB_PROVIDERS.map { it.id }.toSet())
        assertEquals(expected, WebSearchSettings.SEARCH_PROVIDERS.map { it.id }.toSet())
        assertTrue(WebSearchSettings.FETCH_PROVIDERS.containsAll(listOf("direct", "jina", "deepseek", "ollama", "tavily", "exa")))
    }

    @Test
    fun `every provider's fields are consistent with what it can do`() {
        for (provider in WebSearchSettings.WEB_PROVIDERS) {
            if (provider.id == "searxng") {
                assertTrue("SearXNG needs an address", provider.baseUrlRequired)
                assertTrue(provider.fields.contains(WebProviderField.BASE_URL))
            }
            if (provider.id == "cloudflare") {
                assertTrue(provider.fields.contains(WebProviderField.ACCOUNT_ID))
            }
            if (provider.fields.contains(WebProviderField.API_KEY) && !provider.keyOptional) {
                assertFalse("${provider.id} must not count as configured without a key", WebSearchSettings().configured(provider.id))
            }
        }
    }

    @Test
    fun `a keyless provider is configured and a keyed one is not`() {
        val settings = WebSearchSettings()

        assertTrue(settings.configured("exa-free"))
        assertTrue(settings.configured("duckduckgo"))
        assertFalse(settings.configured("brave"))
        assertFalse(settings.configured("searxng"))
        assertTrue(settings.copy(providers = mapOf("brave" to WebProviderConfig(apiKey = "k"))).configured("brave"))
        assertTrue(
            settings.copy(providers = mapOf("searxng" to WebProviderConfig(baseUrl = "https://s.example")))
                .configured("searxng"),
        )
        assertFalse(
            settings.copy(providers = mapOf("cloudflare" to WebProviderConfig(apiKey = "k")))
                .configured("cloudflare"),
        )
    }

    @Test
    fun `the encoded document is strict JSON and has no comments`() {
        val text = encodeWebConfig(WebSearchSettings())
        val root = Json.parseToJsonElement(text) as? JsonObject

        assertTrue("the document must parse as a JSON object", root != null)
        assertFalse("a comment reached the file", text.contains("//"))
        assertEquals(1, root!!["version"]!!.jsonPrimitive.content.toInt())
    }
}
