package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.ModelProfile

/**
 * The `models.json` entry a **second** profile of a built-in provider needs.
 *
 * pi keys one credential and one `baseUrl` per provider id, so a saved second key for
 * DeepSeek cannot share the id `deepseek`. It gets an id of this app's own, and the entry
 * written under it has to carry everything pi's own provider entry would have carried —
 * the host, the protocol and every catalogue field of every model, `compat` included.
 * Without the copy, DeepSeek's reasoning format (`compat.thinkingFormat`) is gone and the
 * model answers without its thinking; without the endpoint there is no provider at all.
 */
class ProfileProviderEntryTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun fact(
        id: String = "deepseek-flash",
        baseUrl: String = "https://api.deepseek.com",
        api: String = "openai-completions",
    ) = json(
        """
        {
          "id": "$id",
          "name": "DeepSeek V4.1 Flash",
          "api": "$api",
          "baseUrl": "$baseUrl",
          "provider": "deepseek",
          "reasoning": true,
          "thinkingLevelMap": {"minimal": null, "low": "low", "high": "high"},
          "input": ["text", "image"],
          "cost": {"input": 0.3, "output": 1.2, "cacheRead": 0.006, "cacheWrite": 0},
          "contextWindow": 1000000,
          "maxTokens": 384000,
          "compat": {"thinkingFormat": "deepseek", "maxTokensField": "max_tokens"}
        }
        """.trimIndent(),
    )

    private fun profile(
        id: String = "b",
        provider: String = "deepseek",
        baseUrl: String = "",
        modelId: String = "deepseek-flash",
    ) = ModelProfile(
        id = id,
        name = "Second key",
        provider = provider,
        apiKey = "sk-b",
        modelId = modelId,
        baseUrl = baseUrl,
    )

    @Test
    fun `the entry names the host and the protocol the catalogue states`() {
        val entry = builtInProviderObject(profile(), mapOf("deepseek-flash" to fact()))!!

        assertEquals("https://api.deepseek.com", entry["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("openai-completions", entry["api"]!!.jsonPrimitive.content)
        // The reference, not the key: the value lives in the environment every launch and
        // every terminal shell carries, so pi's file never holds a secret.
        assertEquals("${'$'}PIKIT_KEY_b", entry["apiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a model keeps everything the catalogue said about it`() {
        val entry = builtInProviderObject(profile(), mapOf("deepseek-flash" to fact()))!!
        val model = entry["models"]!!.jsonArray.single().jsonObject

        assertEquals(1000000L, model["contextWindow"]!!.jsonPrimitive.content.toLong())
        assertEquals(384000L, model["maxTokens"]!!.jsonPrimitive.content.toLong())
        // `compat` is the field a shallow copy loses, and it is not cosmetic: it is where
        // DeepSeek's reasoning format lives.
        assertEquals(
            "deepseek",
            model["compat"]!!.jsonObject["thinkingFormat"]!!.jsonPrimitive.content,
        )
        // The three fields that describe the *host* are provider-level here instead, so
        // they must not be repeated per model.
        assertFalse("provider", "provider" in model)
        assertFalse("baseUrl", "baseUrl" in model)
        assertFalse("api", "api" in model)
    }

    @Test
    fun `the profile's own endpoint wins over the catalogue's`() {
        val entry = builtInProviderObject(
            profile(baseUrl = "https://proxy.example.com/v1"),
            mapOf("deepseek-flash" to fact()),
        )!!

        assertEquals("https://proxy.example.com/v1", entry["baseUrl"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nothing to copy and no endpoint of its own is no entry`() {
        // Inventing a host here is the failure mode this refuses: pi would answer a `401`
        // from an address the user never named, which reads as a bad key.
        assertNull(builtInProviderObject(profile(), emptyMap()))
    }

    @Test
    fun `every model of the profile is registered, not only the active one`() {
        val facts = mapOf(
            "deepseek-flash" to fact(),
            "deepseek-v4-pro" to fact(id = "deepseek-v4-pro"),
        )
        val both = profile().copy(models = listOf("deepseek-v4-pro"))

        val entry = builtInProviderObject(both, facts)!!

        // `set_model` can only reach a model that is in the entry, so a provider
        // registered with one model is one the picker can only switch *to* by relaunching.
        assertEquals(
            listOf("deepseek-flash", "deepseek-v4-pro"),
            entry["models"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `the profile's own numbers are laid over the catalogue's`() {
        val entry = builtInProviderObject(
            profile().copy(
                modelSettings = mapOf(
                    "deepseek-flash" to pi.kit.mob.data.ModelSettings(contextWindow = 64000),
                ),
            ),
            mapOf("deepseek-flash" to fact()),
        )!!
        val model = entry["models"]!!.jsonArray.single().jsonObject

        assertEquals(64000L, model["contextWindow"]!!.jsonPrimitive.content.toLong())
        // The other number is not a statement about this model, so the catalogue's stays.
        assertEquals(384000L, model["maxTokens"]!!.jsonPrimitive.content.toLong())
        assertTrue(model.containsKey("cost"))
    }

    // ------------------------------------------- when the refresh has to run for this

    private val owner = pi.kit.mob.data.ModelProfile(
        id = "a",
        provider = "deepseek",
        apiKey = "sk-a",
        modelId = "deepseek-flash",
    )
    private val second = owner.copy(id = "b", apiKey = "sk-b")

    @Test
    fun `a duplicate profile asks for the refresh when the store cannot describe it`() {
        // This is the device state the launch fix was made for: the store's refresh had
        // been failing, so nothing could be copied and the entry was never written. The
        // refresh that would fix it is inside its own retry window, whose deadline the
        // failure itself wrote — so the launch has to ask for it by need.
        assertTrue(
            "no facts at all is a reason",
            needsCatalogueFacts(listOf(owner, second)) { emptyMap() },
        )
        assertTrue(
            "facts for other models are not facts about this profile's",
            needsCatalogueFacts(listOf(owner, second)) { mapOf("deepseek-v4-pro" to fact()) },
        )
        assertFalse(
            "the profile's own model is described, so nothing is missing",
            needsCatalogueFacts(listOf(owner, second)) { mapOf("deepseek-flash" to fact()) },
        )
    }

    @Test
    fun `a provider's first profile asks for the refresh while the store is empty`() {
        // Its entry needs nothing, but its *levels* do: a provider whose catalogue is not
        // inside pi's package (`xiaomi`, whose `mimo-v2.6-pro` pi.dev maps to
        // `{off: off, high: high}`) resolves as a fallback model while the store is empty,
        // and pi answers `["off"]` for it. That is the report of a level that "only
        // follows" another model: the level was not the question.
        assertTrue(
            "no facts at all is a reason, even with no entry to build",
            needsCatalogueFacts(listOf(owner)) { emptyMap() },
        )
        assertFalse(
            "the store describes the model, so nothing is missing",
            needsCatalogueFacts(listOf(owner)) { mapOf("deepseek-flash" to fact()) },
        )
    }

    @Test
    fun `a relay asks for nothing`() {
        val relay = pi.kit.mob.data.ModelProfile(
            id = "r",
            provider = "pikit-custom",
            apiKey = "sk-r",
            modelId = "m",
            baseUrl = "https://relay.example/v1",
        )

        // A relay is built from the profile: it needs neither a catalogue entry nor the
        // levels of an id pi.dev might know.
        assertFalse(needsCatalogueFacts(listOf(relay)) { emptyMap() })
    }

    // ------------------------------------------------- the entry, out of a real store

    private fun store(vararg models: JsonObject): java.io.File {
        val file = java.io.File.createTempFile("models-store", ".json")
        file.deleteOnExit()
        file.writeText("""{"deepseek": {"models": ${models.joinToString(",", "[", "]")}}}""")
        return file
    }

    @Test
    fun `the entry is built from the store as pi wrote it`() {
        // The regression this pins: reading the store through `storeModelFacts` gives the
        // *model* half of each entry, and `api`/`baseUrl` are stripped there because a
        // relay must keep its own host. Building a provider object from that map left both
        // blank, `builtInProviderObject` answered null, no `pikit-…` id was ever written,
        // and every switch to a second profile of a built-in provider fell back to a
        // relaunch — measured as a profile that restarted on every pick.
        val models = storeProviderModels(store(fact()), "deepseek")

        val entry = builtInProviderObject(profile(), models)!!

        assertEquals("https://api.deepseek.com", entry["baseUrl"]!!.jsonPrimitive.content)
        assertEquals("openai-completions", entry["api"]!!.jsonPrimitive.content)
        // And the per-model strip still happens: the host is stated once, on the provider.
        val model = entry["models"]!!.jsonArray.single().jsonObject
        assertFalse("api" in model)
        assertFalse("baseUrl" in model)
    }

    @Test
    fun `the stripped reader and the whole one are not interchangeable`() {
        val file = store(fact())

        assertFalse(
            "the model half cannot name a host",
            "api" in storeModelFacts(file, "deepseek").getValue("deepseek-flash"),
        )
        assertEquals(
            "openai-completions",
            storeProviderModels(file, "deepseek")
                .getValue("deepseek-flash")["api"]!!.jsonPrimitive.content,
        )
    }
}
