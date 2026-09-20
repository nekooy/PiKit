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
    fun `the document needs a base url and at least one model`() {
        assertNull(CustomEndpoint.document("", listOf("a")))
        assertNull(CustomEndpoint.document("https://relay.example.com/v1", emptyList()))
        assertNull(CustomEndpoint.document("https://relay.example.com/v1", listOf("  ")))
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
}
