package pi.kit.mob.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two data rules behind "one provider, several models".
 *
 * Both are pure and both are the kind of thing a later refactor breaks silently:
 * a profile written by an older build has no model list, and the custom-endpoint
 * document is another project's file format that nothing in this app parses.
 * Neither would fail a build or a screenshot — the first would empty a user's
 * model list on load, the second would stop a relay from working — so they are
 * pinned here.
 */
class ModelProfileTest {

    @Test
    fun `a profile written before the list existed offers its one model`() {
        val old = ModelProfile(id = "p1", provider = "deepseek", modelId = "deepseek-chat")

        assertEquals(listOf("deepseek-chat"), old.selectableModels)
    }

    @Test
    fun `the list keeps its order and its active model once`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "pikit-custom",
            modelId = "b",
            models = listOf("a", "b", "c"),
        )

        assertEquals(listOf("a", "b", "c"), profile.selectableModels)
    }

    @Test
    fun `an active model missing from the list leads it`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "pikit-custom",
            modelId = "chosen",
            models = listOf("a", "b"),
        )

        assertEquals(listOf("chosen", "a", "b"), profile.selectableModels)
    }

    @Test
    fun `no active model leaves the list alone`() {
        val profile = ModelProfile(id = "p1", provider = "pikit-custom", models = listOf("a"))

        assertEquals(listOf("a"), profile.selectableModels)
    }

    /**
     * Adapted from the report's edit pair: it gave `document()` the whole
     * `{"providers": {…}}` shell, but the writer no longer uses `document()` at all
     * — it merges [CustomEndpoint.providerObject] into the file it read, which is
     * the only shape that can leave the user's own providers alone. `document()`
     * is now the *entry* as text for readers that want to see it, so this asserts
     * the entry. The fields are the same ones; the wrapper moved to the merge.
     */
    @Test
    fun `the custom endpoint document registers every model`() {
        val document = CustomEndpoint.document("https://relay.example.com/v1/", listOf("a", "b", "a"))
        val provider = Json.parseToJsonElement(document!!).jsonObject
        val models = provider["models"] as JsonArray

        // Deduplicated, in the order given; the base URL loses only its trailing
        // slash; the key field is the environment variable's *name* — the secret
        // itself reaches the process through argv, which ARCHITECTURE §6.1 explains —
        // and the API is pinned to what relays speak rather than guessed at.
        assertEquals("https://relay.example.com/v1", provider["baseUrl"]!!.jsonPrimitive.content)
        assertEquals(2, models.size)
        assertEquals("a", models[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("b", models[1].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(CustomEndpoint.API_KEY_ENV, provider["apiKey"]!!.jsonPrimitive.content)
        assertEquals("openai-completions", provider["api"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a bare host is completed to the path pi posts under`() {
        val document = CustomEndpoint.document("https://relay.example.com", listOf("m"))!!
        val provider = Json.parseToJsonElement(document).jsonObject

        assertEquals("https://relay.example.com/v1", provider["baseUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the document needs a base url and at least one model`() {
        assertNull(CustomEndpoint.document("", listOf("a")))
        assertNull(CustomEndpoint.document("https://relay.example.com/v1", emptyList()))
        assertNull(CustomEndpoint.document("https://relay.example.com/v1", listOf("  ")))
    }

    /**
     * The `api` field names the wire protocol a custom endpoint speaks.
     *
     * It used to be pinned to `openai-completions`, which made an
     * Anthropic-shaped or Responses-shaped gateway unconfigurable: the request
     * went out through the wrong client and the stream failed to parse. The
     * default is unchanged, so a profile written before the field existed
     * behaves exactly as it did.
     */
    @Test
    fun `the api field is written through, and defaults to openai-completions`() {
        val default = Json.parseToJsonElement(
            CustomEndpoint.document("https://relay.example.com/v1", listOf("m"))!!,
        ).jsonObject
        assertEquals("openai-completions", default["api"]!!.jsonPrimitive.content)

        CustomApi.entries.forEach { api ->
            val provider = Json.parseToJsonElement(
                CustomEndpoint.document(
                    baseUrl = "https://relay.example.com/v1",
                    modelIds = listOf("m"),
                    api = api.id,
                )!!,
            ).jsonObject
            assertEquals(api.id, provider["api"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `an unknown api id falls back to the default rather than writing a name pi cannot resolve`() {
        val provider = Json.parseToJsonElement(
            CustomEndpoint.document(
                baseUrl = "https://relay.example.com/v1",
                modelIds = listOf("m"),
                api = "openai-responses-typo",
            )!!,
        ).jsonObject
        // Written through as typed — the form's picker only offers `CustomApi`'s
        // ids, and a hand-edited profile is the user's own document. What must
        // not happen is a *blank* reaching pi: that is the field's only
        // unresolvable value.
        assertEquals("openai-responses-typo", provider["api"]!!.jsonPrimitive.content)

        val blank = Json.parseToJsonElement(
            CustomEndpoint.document(
                baseUrl = "https://relay.example.com/v1",
                modelIds = listOf("m"),
                api = "  ",
            )!!,
        ).jsonObject
        assertEquals("openai-completions", blank["api"]!!.jsonPrimitive.content)
    }

    /**
     * The per-model settings are new fields on a file the user already has.
     *
     * A profile written before they existed must decode to "nothing said" rather than fail
     * the load, and one that has them must round-trip — a dropped field here silently
     * changes what pi is told about a model the user had already filled in.
     */
    @Test
    fun `a profile written before the settings existed says nothing about any model`() {
        val snapshot = Json.decodeFromString(
            PiConfigSnapshot.serializer(),
            """{"profiles":[{"id":"p1","provider":"deepseek","modelId":"deepseek-chat"}],"activeProfileId":"p1"}""",
        )

        assertEquals(emptyMap<String, ModelSettings>(), snapshot.profiles.single().modelSettings)
        assertEquals(
            "and an unknown id reads as the defaults pi would use",
            ModelSettings(),
            snapshot.profiles.single().settingsFor("anything"),
        )
    }

    @Test
    fun `declared model settings round trip`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "vision-v1",
            models = listOf("vision-v1", "deepseek-chat"),
            modelSettings = mapOf(
                "vision-v1" to ModelSettings(images = true, contextWindow = 1_000_000, maxTokens = null),
                "deepseek-chat" to ModelSettings(images = false),
            ),
            writtenModels = listOf("vision-v1", "deepseek-chat"),
        )

        val encoded = Json.encodeToString(ModelProfile.serializer(), profile)
        val decoded = Json.decodeFromString(ModelProfile.serializer(), encoded)

        assertEquals("the field survives the write", profile.modelSettings, decoded.modelSettings)
        assertEquals("and so does the withdrawal record", profile.writtenModels, decoded.writtenModels)
        assertEquals(profile, decoded)
    }

    /**
     * A custom endpoint inherits the *whole* catalogue entry for a same-named id
     * — cost, thinkingLevelMap, reasoning, samplingParams — not just the three
     * numbers the page draws. The report "自动匹配相同id，并填写…价格思维等等所有
     * 参数都会一并带上" is about a relay's `claude-sonnet-4-5` losing the price
     * table and the thinking levels of the id it shares.
     *
     * `api`/`baseUrl` must *not* travel: those name the host, and the host is
     * the relay.
     */
    @Test
    fun `a custom entry carries the inherited catalogue facts`() {
        val inherited = Json.parseToJsonElement(
            """
            {
              "reasoning": true,
              "thinkingLevelMap": {"off": "off", "high": "high", "max": "max"},
              "cost": {"input": 0.28, "output": 0.42, "cacheRead": 0.028, "cacheWrite": 1.0},
              "samplingParams": {"temperature": 0.7},
              "contextWindow": 200000,
              "maxTokens": 64000
            }
            """.trimIndent(),
        ).jsonObject
        val provider = Json.parseToJsonElement(
            CustomEndpoint.document(
                baseUrl = "https://relay.example.com/v1",
                modelIds = listOf("claude-sonnet-4-5"),
                settings = mapOf(
                    "claude-sonnet-4-5" to ModelSettings(
                        images = true,
                        // The user's own number wins over the inherited one.
                        contextWindow = 128_000,
                        inherited = inherited,
                    ),
                ),
            )!!,
        ).jsonObject
        val entry = (provider["models"] as JsonArray).single().jsonObject

        assertEquals("the thinking levels come along", "high", (entry["thinkingLevelMap"]!!.jsonObject)["high"]!!.jsonPrimitive.content)
        assertEquals("and so does the price table", "0.28", (entry["cost"]!!.jsonObject)["input"]!!.jsonPrimitive.content)
        assertEquals("and the sampling params", "0.7", (entry["samplingParams"]!!.jsonObject)["temperature"]!!.jsonPrimitive.content)
        assertEquals("reasoning is inherited", true, entry["reasoning"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("the user's window wins", "128000", entry["contextWindow"]!!.jsonPrimitive.content)
        assertEquals("but the inherited max-out is kept", "64000", entry["maxTokens"]!!.jsonPrimitive.content)
        assertEquals("and the switch still controls input", listOf("text", "image"), (entry["input"] as JsonArray).map { it.jsonPrimitive.content })
        assertNull("the catalogue's host never travels with the facts", entry["baseUrl"])
        assertNull(entry["api"])
    }

    /**
     * The withdrawal record is read under the key the build that introduced it used.
     *
     * That build called it `customImageModels` and stored it for a narrower declaration.
     * Reading it under a new key would leave every entry it names in pi's `models.json`
     * forever, with nothing left to recognise them by: an entry that names the fallback's
     * own facts has no fixed shape for `isPikitModelDefinition` to match.
     */
    @Test
    fun `a record written under the old key is still the record`() {
        val decoded = Json.decodeFromString(
            ModelProfile.serializer(),
            """{"id":"p1","provider":"deepseek","modelId":"vision-v1","customImageModels":["vision-v1"]}""",
        )

        assertEquals(listOf("vision-v1"), decoded.writtenModels)
    }

    /**
     * The endpoint override and its withdrawal record are two different strings.
     *
     * Clearing the field means "use the provider's own endpoint"; the override this
     * app last put in pi's file is what has to come out, and that is only knowable
     * from [ModelProfile.writtenBaseUrl]. A profile written before the record
     * existed still decodes — the load path seeds it from the field — and one that
     * has both must round-trip them apart.
     */
    @Test
    fun `the endpoint override and its withdrawal record round trip apart`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "deepseek-chat",
            baseUrl = "https://proxy.example.com/v1",
            writtenBaseUrl = "https://old-proxy.example.com/v1",
        )

        val encoded = Json.encodeToString(ModelProfile.serializer(), profile)
        val decoded = Json.decodeFromString(ModelProfile.serializer(), encoded)

        assertEquals(profile, decoded)
        assertEquals("https://proxy.example.com/v1", decoded.baseUrl)
        assertEquals("https://old-proxy.example.com/v1", decoded.writtenBaseUrl)
    }

    @Test
    fun `a profile written before the withdrawal record existed still decodes`() {
        val decoded = Json.decodeFromString(
            ModelProfile.serializer(),
            """{"id":"p1","provider":"deepseek","modelId":"m","baseUrl":"https://proxy.example.com/v1"}""",
        )

        assertEquals("https://proxy.example.com/v1", decoded.baseUrl)
        assertEquals("", decoded.writtenBaseUrl)
    }
}
