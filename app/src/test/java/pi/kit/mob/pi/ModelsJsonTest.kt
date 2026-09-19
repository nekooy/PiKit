package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.CustomEndpoint
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.ModelSettings
import pi.kit.mob.data.PiProvider

/**
 * The merge rule for pi's own `models.json`.
 *
 * This is the file the (c) report was about: it is pi's, pi's documentation invites
 * the user to edit it by hand, and an earlier build of the app deleted it on every launch
 * when a built-in provider was active. Every assertion here is one that delete-path cannot
 * satisfy, which is the point — a test that only checked PiKit's own entry would have
 * passed against the bug.
 *
 * ## The rule these tests pin
 *
 * pi reaches a model two ways and the app writes whichever one can carry the statement.
 * A `modelOverrides` entry **merges** into a model pi already resolved from its catalogue
 * — `applyModelOverride` is `override.contextWindow ?? model.contextWindow` and the same
 * for every other field — so it is the right mechanism for a model pi knows, and it is
 * the *only* one that leaves the window, the cost and the thinking map the catalogue's.
 * An id pi did not resolve never sees an override at all, so for those the app writes a
 * `models` entry — which *replaces* pi's own entry for the id, window, cost and thinking
 * map included. That entry therefore has to name every fact of the model pi would have
 * resolved the id to (`buildFallbackModel`'s copy of the provider's default), or declaring
 * one thing about the model would change everything else about it.
 *
 * Which of the two an id gets is [ModelProfile.knownCatalogueIds], recorded by the model
 * page's check. A `models` entry the app wrote for an id that later turns out to be
 * catalogued is withdrawn — that is the failure this whole path exists to avoid — and an
 * override is recognised by the `input` value the build that wrote it always produced.
 */
class ModelsJsonTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun JsonObject.providers(): JsonObject = this["providers"]!!.jsonObject

    private fun JsonObject.provider(id: String): JsonObject = providers()[id]!!.jsonObject

    private fun JsonObject.modelIds(providerId: String): List<String> =
        (provider(providerId)["models"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }

    /** The `models` entry for one id, which is the only mechanism that reaches a custom one. */
    private fun JsonObject.modelEntry(providerId: String, modelId: String): JsonObject? =
        (provider(providerId)["models"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it["id"]?.jsonPrimitive?.content == modelId }

    private fun JsonObject.overrideFor(providerId: String, modelId: String): JsonObject? =
        (provider(providerId)["modelOverrides"] as? JsonObject)
            ?.get(modelId) as? JsonObject

    private fun inputOf(entry: JsonObject): List<String> =
        (entry["input"] as JsonArray).map { it.jsonPrimitive.content }

    private fun text(entry: JsonObject, key: String): String? =
        (entry[key] as? JsonPrimitive)?.contentOrNull

    /** A hand-written file of the shape pi's docs describe. */
    private val userDocument = json(
        """
        {
          "providers": {
            "deepseek": {
              "name": "My DeepSeek",
              "baseUrl": "https://api.deepseek.com",
              "api": "openai-completions",
              "models": [
                {"id": "deepseek-chat", "name": "deepseek-chat", "input": ["text"]}
              ]
            },
            "my-relay": {
              "baseUrl": "https://relay.example.com/v1",
              "api": "openai-completions",
              "apiKey": "sk-user",
              "models": [{"id": "relay-model"}]
            }
          }
        }
        """.trimIndent(),
    )

    /** What a build with the definition path left in pi's file. */
    private fun legacyRegistration(modelId: String): String =
        """
        {"id": "$modelId", "name": "$modelId", "reasoning": true,
         "input": ["text", "image"], "contextWindow": 128000, "maxTokens": 16384}
        """.trimIndent()

    @Test
    fun `a built-in provider keeps every key of the user's document`() {
        val result = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf("deepseek" to ModelDefinitions()),
        )

        // The whole point of the merge: with a built-in provider active, the old
        // writer deleted this file. Every provider and every key inside one has to
        // come back equal.
        assertEquals(userDocument, result)
        assertEquals(userDocument.keys, result.keys)
        assertEquals("sk-user", result.provider("my-relay")["apiKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only PiKit's own provider id is withdrawn`() {
        val withOurs = JsonObject(
            userDocument + (
                "providers" to JsonObject(
                    userDocument.providers() + (
                        CustomEndpoint.PROVIDER_ID to
                            json("""{"baseUrl":"https://old.example.com","models":[{"id":"old"}]}""")
                        ),
                )
                ),
        )

        val result = modelsJsonWith(
            existing = withOurs,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf("deepseek" to ModelDefinitions()),
        )

        assertNull("PiKit's stale entry is gone", result.providers()[CustomEndpoint.PROVIDER_ID])
        assertTrue("the user's relay is not", "my-relay" in result.providers())
        assertTrue("nor the built-in they customised", "deepseek" in result.providers())
    }

    @Test
    fun `a custom endpoint is upserted without disturbing the rest`() {
        val custom = CustomEndpoint.providerObject("https://relay.example.com/v1", listOf("m"))!!
        val withOurs = JsonObject(
            userDocument + (
                "providers" to JsonObject(
                    userDocument.providers() + (CustomEndpoint.PROVIDER_ID to json("{}")),
                )
                ),
        )

        val result = modelsJsonWith(withOurs, CustomEndpoint.PROVIDER_ID, custom, emptyMap())

        assertEquals(
            "https://relay.example.com/v1",
            result.provider(CustomEndpoint.PROVIDER_ID)["baseUrl"]!!.jsonPrimitive.content,
        )
        assertTrue("the user's own provider survives", "my-relay" in result.providers())
    }

    // ------------------------------------------- the definition for a custom id

    /**
     * What the catalogue probe reads out of pi's own `get_state` reply for an id no
     * provider serves — that is, `buildFallbackModel`'s copy of DeepSeek's default model.
     *
     * Spelled out with the fields pi does *not* owe a definition (`provider`, extra keys
     * inside `cost`) so that `modelDefinitionFacts` has something to strip.
     */
    private val fallback = json(
        """
        {
          "id": "pikit-probe-model",
          "name": "pikit-probe-model",
          "api": "openai-completions",
          "provider": "deepseek",
          "baseUrl": "https://api.deepseek.com",
          "reasoning": true,
          "thinkingLevelMap": {"off": "off", "high": "high", "max": "max"},
          "input": ["text"],
          "cost": {"input": 0.28, "output": 0.42, "cacheRead": 0.028, "cacheWrite": 1.0,
                   "tiers": [{"from": 128000, "input": 0.56}]},
          "contextWindow": 1000000,
          "maxTokens": 384000,
          "samplingParams": {"temperature": 0.7}
        }
        """.trimIndent(),
    )

    private val facts: JsonObject = modelDefinitionFacts(fallback)

    private fun definitions(
        wanted: List<ModelEntry>,
        ours: List<String> = wanted.map { it.id },
        overrides: Map<String, ModelSettings> = emptyMap(),
    ): Map<String, ModelDefinitions> =
        mapOf("deepseek" to ModelDefinitions(wanted = wanted, ours = ours, overrides = overrides))

    private fun entry(id: String, settings: ModelSettings = ModelSettings()) =
        ModelEntry(id = id, facts = facts, settings = settings)

    /**
     * The half of the feature the override cannot serve.
     *
     * `applyModelOverride` runs over the models the provider resolved from pi's own
     * catalogue, so an id pi's catalogue does not contain never sees its override — for
     * that id pi builds a copy of the provider's default model (`buildFallbackModel`) and
     * the override is not consulted for the copy. A `models` entry is the only thing that
     * puts the id into the list at all, and this is what the model page writes when the
     * catalogue check said pi does not know the id.
     *
     * The entry is checked to be the fallback *plus* what the user said, which is the whole
     * requirement: a custom id follows what pi resolved for it, and the three settings are
     * the only things that move.
     */
    @Test
    fun `an id pi does not catalogue is added as an entry carrying pi's own facts`() {
        val result = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(listOf(entry("vision-v1", ModelSettings(images = true)))),
        )

        // The user's own entry is where it was, and PiKit's is appended to the array.
        assertEquals(listOf("deepseek-chat", "vision-v1"), result.modelIds("deepseek"))
        val added = result.modelEntry("deepseek", "vision-v1")!!
        assertEquals(listOf("text", "image"), inputOf(added))

        // Everything else is pi's own account of the model it resolves this id to — not
        // PiKit's placeholders, which is what the entry would otherwise carry.
        assertEquals("1000000", text(added, "contextWindow"))
        assertEquals("384000", text(added, "maxTokens"))
        assertEquals("https://api.deepseek.com", text(added, "baseUrl"))
        assertEquals("openai-completions", text(added, "api"))
        assertEquals(true, (added["reasoning"] as JsonPrimitive).booleanOrNull)
        assertEquals("0.28", (added["cost"]!!.jsonObject)["input"]!!.jsonPrimitive.content)
        assertEquals(
            mapOf("off" to "off", "high" to "high", "max" to "max"),
            (added["thinkingLevelMap"]!!.jsonObject).mapValues { it.value.jsonPrimitive.content },
        )

        // The user's own model is untouched: no entry is written for it, so pi's own
        // window and cost for it survive.
        assertEquals(listOf("text"), inputOf(result.modelEntry("deepseek", "deepseek-chat")!!))
    }

    @Test
    fun `the user's two numbers are what the entry says, over pi's own`() {
        val result = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                listOf(
                    entry(
                        "vision-v1",
                        ModelSettings(images = false, contextWindow = 64_000, maxTokens = 4_096),
                    ),
                ),
            ),
        )

        val added = result.modelEntry("deepseek", "vision-v1")!!
        assertEquals("64000", text(added, "contextWindow"))
        assertEquals("4096", text(added, "maxTokens"))
        // The switch was off, so the entry states pi's own text-only input rather than the
        // fallback's capability — a capability switch that did nothing would be the bug.
        assertEquals(listOf("text"), inputOf(added))
        // Everything the user did not touch is still pi's.
        assertEquals("0.28", (added["cost"]!!.jsonObject)["input"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty number field leaves the fallback's value in place`() {
        // The escape hatch: a box the user cleared is not a request for pi's 128k default,
        // it is a request to name nothing at all, so the fallback's own window is what the
        // model gets.
        val result = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(listOf(entry("vision-v1"))),
        )

        val added = result.modelEntry("deepseek", "vision-v1")!!
        assertEquals("1000000", text(added, "contextWindow"))
        assertEquals("384000", text(added, "maxTokens"))
        assertEquals(listOf("text"), inputOf(added))
    }

    @Test
    fun `an untouched switch keeps the fallback's own image support`() {
        // A model whose fallback takes images, and a user who only corrected its window:
        // the entry has to repeat the fallback's `input`, or writing it would take image
        // input away from a model that had it — an "off" switch nobody touched.
        val seeingFacts = modelDefinitionFacts(
            json("""{"api":"openai-completions","input":["text","image"],"contextWindow":200000}"""),
        )
        val result = modelsJsonWith(
            existing = JsonObject(emptyMap()),
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf(
                "deepseek" to ModelDefinitions(
                    wanted = listOf(
                        ModelEntry("vision-v1", seeingFacts, ModelSettings(contextWindow = 64_000)),
                    ),
                ),
            ),
        )

        val added = result.modelEntry("deepseek", "vision-v1")!!
        assertEquals(listOf("text", "image"), inputOf(added))
        assertEquals("64000", text(added, "contextWindow"))
    }

    @Test
    fun `an entry with no facts at all still names pi's own defaults`() {
        // A custom endpoint: pi has no catalogue entry for the provider, so there is no
        // fallback to inherit anything from and the entry has to be complete.
        val result = modelsJsonWith(
            existing = JsonObject(emptyMap()),
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf(
                "pikit-custom" to ModelDefinitions(
                    wanted = listOf(ModelEntry("m", JsonObject(emptyMap()), ModelSettings())),
                ),
            ),
        )

        val added = result.modelEntry("pikit-custom", "m")!!
        assertEquals("128000", text(added, "contextWindow"))
        assertEquals("16384", text(added, "maxTokens"))
        assertEquals("true", text(added, "reasoning"))
        assertEquals(listOf("text"), inputOf(added))
    }

    @Test
    fun `the facts are whitelisted to the fields a definition accepts`() {
        // pi's schema knows a subset of the resolved model's fields, and a document it
        // rejects takes every provider in the file down with it. `provider` is the field
        // that would do it here; `tiers` is dropped deliberately (see the function).
        //
        // `input` *is* copied, which is the opposite of what an earlier version of this
        // did and is load-bearing: an entry whose image switch is off has to state the
        // fallback's own capability, or turning the switch on — and never touching it
        // again — would turn images off for a model whose fallback takes them.
        assertEquals(
            setOf(
                "api",
                "baseUrl",
                "reasoning",
                "thinkingLevelMap",
                "input",
                "cost",
                "contextWindow",
                "maxTokens",
                "samplingParams",
            ),
            facts.keys,
        )
        assertEquals(setOf("input", "output", "cacheRead", "cacheWrite"), (facts["cost"]!!.jsonObject).keys)
    }

    @Test
    fun `a definition is withdrawn when the catalogue check behind it no longer holds`() {
        // The state after pi's catalogue gained the model, or after `pi` itself was
        // updated: the record of the check no longer describes the world, so
        // `modelDefinitions` stops wanting the id and the entry has to go — otherwise it
        // would keep overriding the window pi now has for the model, which is the bug
        // this whole path is written around.
        //
        // `ours` still names the id, and that is the only thing that can: an entry built
        // out of the fallback's facts has no fixed shape left for `isPikitModelDefinition`
        // to match.
        val defined = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(listOf(entry("vision-v1", ModelSettings(images = true)))),
        )
        assertEquals(listOf("deepseek-chat", "vision-v1"), defined.modelIds("deepseek"))

        val withdrawn = modelsJsonWith(
            existing = defined,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(wanted = emptyList(), ours = listOf("vision-v1")),
        )

        assertEquals(listOf("deepseek-chat"), withdrawn.modelIds("deepseek"))
    }

    @Test
    fun `dropping one model's settings takes that entry out and leaves the other`() {
        val defined = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                listOf(entry("vision-v1", ModelSettings(images = true)), entry("vision-v2")),
            ),
        )
        assertEquals(listOf("deepseek-chat", "vision-v1", "vision-v2"), defined.modelIds("deepseek"))

        val result = modelsJsonWith(
            existing = defined,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            // The record keeps the id the user just emptied, which is how its entry is
            // found again; only `wanted` shrinks.
            definitions = definitions(wanted = listOf(entry("vision-v2")), ours = listOf("vision-v1", "vision-v2")),
        )

        assertEquals(listOf("deepseek-chat", "vision-v2"), result.modelIds("deepseek"))
    }

    // ------------------------------------------- a catalogued id, which is an override

    /**
     * The mechanism the *other* half of the model page uses, and the reason it is a
     * different one.
     *
     * A `models` entry for a model pi knows *replaces* pi's own: `modelFromJson` gives every
     * field the entry does not name a fixed value of its own, so declaring a narrower
     * window on a catalogued model would also take away its cost, its thinking-level map
     * and its reasoning flag. A `modelOverrides` entry is merged field by field instead
     * (`applyModelOverride`), so saying one thing changes one thing.
     */
    @Test
    fun `a catalogued id is declared with an override, which names only what the user said`() {
        val result = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                wanted = emptyList(),
                ours = emptyList(),
                overrides = mapOf(
                    "deepseek-chat" to ModelSettings(images = true, contextWindow = 64_000),
                ),
            ),
        )

        val override = result.overrideFor("deepseek", "deepseek-chat")!!
        assertEquals(listOf("text", "image"), inputOf(override))
        assertEquals("64000", text(override, "contextWindow"))
        // The field the user did not touch is *absent*, which is what keeps it pi's. Naming
        // it with the catalogue's own value would pin the model to a number a later pi
        // release could no longer move.
        assertNull("an untouched max-out is not named", override["maxTokens"])
        assertNull("no definition field is smuggled in", override["reasoning"])
        assertNull(override["cost"])
        assertNull(override["thinkingLevelMap"])
        // And no `models` entry is written for it: that is the entry that would replace
        // everything the override is careful to leave alone.
        assertEquals(listOf("deepseek-chat"), result.modelIds("deepseek"))
    }

    @Test
    fun `an override never carries an endpoint, which is what keeps a relay's request local`() {
        // `modelDefinitionFacts` copies `api` and `baseUrl` — correct for a definition,
        // which has to stand alone — and `applyModelOverride` reads neither. Writing them
        // into an override for a custom endpoint's model is how a relay's request ends up
        // at `api.deepseek.com`, so `modelOverrideFor` must not name them whatever the
        // facts say.
        val override = modelOverrideFor(
            ModelSettings(contextWindow = 200_000),
            userWritten = null,
        )
        assertNull(override["api"])
        assertNull(override["baseUrl"])
        assertEquals(setOf("contextWindow"), override.keys)
    }

    @Test
    fun `a key the user wrote by hand in the override survives`() {
        // pi's documentation invites the user into this file, and a merge that dropped a
        // key of theirs to write its own would be the app overwriting a configuration it
        // did not make. PiKit's own three fields are the app's statement; everything else
        // is carried over.
        val existing = json(
            """
            {"providers":{"deepseek":{"modelOverrides":{
              "deepseek-chat":{"temperature":0.1,"reasoning":false}
            }}}}
            """.trimIndent(),
        )

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                wanted = emptyList(),
                ours = emptyList(),
                overrides = mapOf("deepseek-chat" to ModelSettings(images = false)),
            ),
        )

        val override = result.overrideFor("deepseek", "deepseek-chat")!!
        assertEquals("0.1", text(override, "temperature"))
        assertEquals("false", text(override, "reasoning"))
        assertEquals("the app's own statement is what it wrote", listOf("text"), inputOf(override))
    }

    @Test
    fun `an id that becomes catalogued loses its definition`() {
        // The transition the record exists for. The app wrote a `models` entry for a model
        // pi did not know; pi's catalogue then learned it, so the entry is now replacing
        // pi's own window, cost and thinking map for that model — the 128K bug, arriving
        // through a `pi update` rather than through a wrong decision.
        //
        // The entry cannot be recognised by shape: it carries the fallback's facts, which
        // is exactly what makes it faithful and shapeless. `ours` is the only handle, and
        // the page has to keep the id in it after the answer says "catalogued" — dropping it
        // there is how the entry would survive for ever.
        val defined = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(listOf(entry("vision-v1", ModelSettings(images = true)))),
        )
        assertEquals(listOf("deepseek-chat", "vision-v1"), defined.modelIds("deepseek"))

        val nowCatalogued = modelsJsonWith(
            existing = defined,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                wanted = emptyList(),
                // What `save()` writes once the answer says the id is catalogued: every
                // kept id, this one included.
                ours = listOf("vision-v1"),
                overrides = mapOf("vision-v1" to ModelSettings(images = true)),
            ),
        )

        assertEquals(
            "the replacing entry is gone and pi's own is back",
            listOf("deepseek-chat"),
            nowCatalogued.modelIds("deepseek"),
        )
        assertEquals(
            "the statement moved to the merging mechanism",
            listOf("text", "image"),
            inputOf(nowCatalogued.overrideFor("deepseek", "vision-v1")!!),
        )
    }

    @Test
    fun `an id that leaves the settings loses its override, and one that stays keeps it`() {
        val declared = modelsJsonWith(
            existing = userDocument,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                wanted = emptyList(),
                ours = emptyList(),
                overrides = mapOf(
                    "deepseek-chat" to ModelSettings(images = true),
                    "deepseek-reasoner" to ModelSettings(maxTokens = 8_192),
                ),
            ),
        )
        assertTrue("both are written", declared.overrideFor("deepseek", "deepseek-chat") != null)

        val after = modelsJsonWith(
            existing = declared,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(
                wanted = emptyList(),
                ours = emptyList(),
                overrides = mapOf("deepseek-reasoner" to ModelSettings(maxTokens = 8_192)),
            ),
        )

        assertNull("the row that left the list took its override", after.overrideFor("deepseek", "deepseek-chat"))
        assertEquals(
            "8192",
            text(after.overrideFor("deepseek", "deepseek-reasoner")!!, "maxTokens"),
        )
    }

    // ---------------------------------------------------- which ids may be defined

    /**
     * The routing rule, which is the one thing that decides whether a statement about a
     * model replaces what pi knows about it or merges into it.
     */
    @Test
    fun `a catalogued id is an override and an uncatalogued one is a definition`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "known",
            models = listOf("known", "unknown"),
            modelSettings = mapOf(
                "known" to ModelSettings(images = false),
                "unknown" to ModelSettings(images = true),
            ),
            writtenModels = listOf("unknown"),
            customModelFacts = facts,
            catalogueBasis = "basis",
            knownCatalogueIds = listOf("known"),
        )

        val definitions = modelDefinitions(PiProvider.DEEPSEEK, profile, "basis")["deepseek"]!!
        assertEquals(
            "only the id the catalogue does not contain is defined",
            listOf("unknown"),
            definitions.wanted.map { it.id },
        )
        assertEquals(
            "and the catalogued one is an override",
            mapOf("known" to ModelSettings(images = false)),
            definitions.overrides,
        )
        assertEquals(
            "the record of definitions names only the definition",
            listOf("unknown"),
            definitions.ours,
        )
    }

    @Test
    fun `a profile written before the catalogue answer was recorded routes every id to a definition`() {
        // `knownCatalogueIds` is empty on a profile saved by a build that had no field for
        // it, and the launch path must not invent an answer: an id routed to `modelOverrides`
        // is one pi ignores entirely if its catalogue does not contain it, so guessing
        // "catalogued" would silently drop a declaration. Guessing "not catalogued" is what
        // this app did before the field existed, and the basis gate is what keeps that from
        // overwriting a model pi has since learned.
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "some-id",
            models = listOf("some-id"),
            modelSettings = mapOf("some-id" to ModelSettings(images = true)),
            writtenModels = listOf("some-id"),
            customModelFacts = facts,
            catalogueBasis = "basis",
        )

        val definitions = modelDefinitions(PiProvider.DEEPSEEK, profile, "basis")["deepseek"]!!
        assertEquals(listOf("some-id"), definitions.wanted.map { it.id })
        assertEquals(emptyMap<String, ModelSettings>(), definitions.overrides)
    }

    @Test
    fun `an entry is wanted only while the catalogue basis it was checked against holds`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "vision-v1",
            models = listOf("vision-v1", "vision-v2"),
            modelSettings = mapOf(
                "vision-v1" to ModelSettings(images = true),
                "vision-v2" to ModelSettings(images = true),
            ),
            writtenModels = listOf("vision-v1", "vision-v2"),
            customModelFacts = facts,
            catalogueBasis = "1.2.3|abc",
        )

        assertEquals(
            listOf("vision-v1", "vision-v2"),
            modelDefinitions(PiProvider.DEEPSEEK, profile, "1.2.3|abc")["deepseek"]!!.wanted.map { it.id },
        )
        // pi updated, or `models-store.json` moved: nothing is written until the model
        // page checks again. Fail closed — an entry written for an id pi has since
        // learned about replaces what pi knows about it.
        listOf("1.2.4|abc", "1.2.3|def").forEach { basis ->
            val moved = modelDefinitions(PiProvider.DEEPSEEK, profile, basis)["deepseek"]!!
            assertEquals(emptyList<String>(), moved.wanted.map { it.id })
            // ...but the app still owns the entry, so the withdrawal can take it out.
            assertEquals(listOf("vision-v1", "vision-v2"), moved.ours)
        }
    }

    @Test
    fun `only ids the profile still offers may be written`() {
        // The row was removed from the model list but the record was not rewritten: the
        // entry must go with the row rather than outliving it.
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "vision-v2",
            models = listOf("vision-v2"),
            modelSettings = mapOf(
                "vision-v1" to ModelSettings(images = true),
                "vision-v2" to ModelSettings(images = true),
            ),
            writtenModels = listOf("vision-v1", "vision-v2"),
            customModelFacts = facts,
            catalogueBasis = "basis",
        )

        val definitions = modelDefinitions(PiProvider.DEEPSEEK, profile, "basis")["deepseek"]!!
        assertEquals(listOf("vision-v2"), definitions.wanted.map { it.id })
        assertEquals(listOf("vision-v1", "vision-v2"), definitions.ours)
    }

    @Test
    fun `the facts reach the entry`() {
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "vision-v1",
            models = listOf("vision-v1"),
            modelSettings = mapOf("vision-v1" to ModelSettings(images = true)),
            writtenModels = listOf("vision-v1"),
            customModelFacts = facts,
            catalogueBasis = "basis",
        )

        val wanted = modelDefinitions(PiProvider.DEEPSEEK, profile, "basis")["deepseek"]!!.wanted.single()
        assertEquals("vision-v1", wanted.id)
        assertEquals(facts, wanted.facts)
        assertEquals(true, wanted.settings.images)
    }

    @Test
    fun `a profile with nothing said about any model defines nothing`() {
        // The state after the user has emptied every box and turned every switch back to
        // what pi resolves on its own: the app has nothing to write, and its entry for the
        // id — if it ever wrote one — is still found through the record.
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "deepseek-chat",
            models = listOf("deepseek-chat"),
            writtenModels = listOf("deepseek-chat"),
        )

        val definitions = modelDefinitions(PiProvider.DEEPSEEK, profile, "1.2.3|abc")["deepseek"]!!
        assertEquals(emptyList<String>(), definitions.wanted.map { it.id })
        assertEquals(listOf("deepseek-chat"), definitions.ours)
    }

    @Test
    fun `a profile whose catalogue check never answered writes no entry`() {
        // No facts means the check did not run or did not answer, and an entry written
        // without them would name pi's hard-coded 128k/16k for a model whose real window
        // nobody asked pi about. The model page's controls are disabled in exactly this
        // state, so refusing here refuses nothing the user was able to ask for.
        val profile = ModelProfile(
            id = "p1",
            provider = "deepseek",
            modelId = "vision-v1",
            models = listOf("vision-v1"),
            modelSettings = mapOf("vision-v1" to ModelSettings(images = true)),
            writtenModels = listOf("vision-v1"),
            catalogueBasis = "basis",
        )

        val definitions = modelDefinitions(PiProvider.DEEPSEEK, profile, "basis")["deepseek"]!!
        assertEquals(emptyList<String>(), definitions.wanted.map { it.id })
        assertEquals(listOf("vision-v1"), definitions.ours)
    }

    @Test
    fun `a custom endpoint is never given definitions by this path`() {
        // A custom endpoint's provider object is written wholesale by
        // `CustomEndpoint.providerObject`, `models` array included, and pi has no entry for
        // the provider to replace — so there is nothing for this path to merge and nothing
        // for it to withdraw.
        val profile = ModelProfile(
            id = "p1",
            provider = "pikit-custom",
            modelId = "m",
            models = listOf("m"),
            modelSettings = mapOf("m" to ModelSettings(images = true)),
            writtenModels = listOf("m"),
            catalogueBasis = "basis",
        )

        assertEquals(
            emptyMap<String, ModelDefinitions>(),
            modelDefinitions(PiProvider.CUSTOM, profile, "basis"),
        )
    }

    @Test
    fun `a custom endpoint's entries are the defaults until the user changes them`() {
        // The other half of the report: every model of a custom endpoint used to be
        // registered as image-capable because pi knows nothing about the provider — a
        // guess written into a file pi believes. The default is now the honest one, and
        // the user says otherwise per model.
        val custom = CustomEndpoint.providerObject(
            baseUrl = "https://relay.example.com/v1",
            modelIds = listOf("plain", "vision"),
            settings = mapOf(
                "vision" to ModelSettings(images = true, contextWindow = 200_000),
            ),
        )!!

        val entries = (custom["models"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("text"), inputOf(entries[0]))
        assertEquals("128000", text(entries[0], "contextWindow"))
        assertEquals("16384", text(entries[0], "maxTokens"))

        assertEquals(listOf("text", "image"), inputOf(entries[1]))
        assertEquals("200000", text(entries[1], "contextWindow"))
        assertEquals("16384", text(entries[1], "maxTokens"))
    }

    // --------------------------------------------------- the withdrawal

    @Test
    fun `a registration an older build wrote is withdrawn and the user's entries are not`() {
        // The state an install that ran the old definition path is left in: PiKit's own
        // `models` entry for `vision-v1`, with the window it invented, next to an entry the
        // user wrote themselves. The record of the ids the app wrote is what finds it —
        // there is no `modelSettings` entry for it any more.
        val existing = json(
            """
            {
              "providers": {
                "deepseek": {
                  "models": [
                    {"id": "deepseek-chat", "name": "deepseek-chat", "input": ["text"]},
                    ${legacyRegistration("vision-v1")}
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        listOf(listOf("vision-v1"), emptyList()).forEach { record ->
            val result = modelsJsonWith(
                existing = existing,
                customProviderId = CustomEndpoint.PROVIDER_ID,
                customProvider = null,
                definitions = definitions(wanted = emptyList(), ours = record),
            )

            assertEquals(
                "the user's own entry is untouched, PiKit's is gone (record=$record)",
                listOf("deepseek-chat"),
                result.modelIds("deepseek"),
            )
        }
    }

    @Test
    fun `an entry that only looks similar is left alone`() {
        // Every one of these differs from what the old build wrote in exactly one field,
        // and pi's schema says each of them is a different statement about the model. The
        // shape withdrawal recognises the *shape* and nothing else, so none of them is
        // PiKit's to delete.
        val nearMisses = listOf(
            legacyRegistration("a").replace("\"reasoning\": true", "\"reasoning\": false"),
            legacyRegistration("b").replace("\"contextWindow\": 128000", "\"contextWindow\": 1000000"),
            legacyRegistration("c").replace("\"maxTokens\": 16384", "\"maxTokens\": 65536"),
            legacyRegistration("d").replace("[\"text\", \"image\"]", "[\"text\"]"),
        )

        nearMisses.forEachIndexed { index, entry ->
            val existing = json("""{"providers":{"deepseek":{"models":[$entry]}}}""")
            val result = modelsJsonWith(
                existing = existing,
                customProviderId = CustomEndpoint.PROVIDER_ID,
                customProvider = null,
                definitions = definitions(wanted = emptyList(), ours = emptyList()),
            )
            assertEquals("entry $index survives", 1, result.modelIds("deepseek").size)
        }
    }

    @Test
    fun `a user's own entry for an id the app never wrote is not withdrawn`() {
        // The dangerous direction of the record: an id the app has no memory of writing,
        // and is not asking to write now, must survive — otherwise the withdrawal would be
        // a delete of whatever the user put in the file.
        val existing = json(
            """
            {
              "providers": {
                "deepseek": {"models": [{"id": "mine", "name": "mine", "input": ["text"]}]}
              }
            }
            """.trimIndent(),
        )

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(wanted = emptyList(), ours = listOf("theirs")),
        )

        assertEquals(listOf("mine"), result.modelIds("deepseek"))
    }

    @Test
    fun `the input key a previous build put in an override is withdrawn`() {
        // This app writes no override any more, so every override carrying exactly its own
        // declaration is the previous build's and is taken back out — the key alone when
        // the user set other fields beside it, the whole entry when it was the only field.
        val existing = json(
            """
            {
              "providers": {
                "deepseek": {
                  "modelOverrides": {
                    "vision": {"input": ["text", "image"]},
                    "vision-with-keys": {"input": ["text", "image"], "reasoning": false},
                    "text-only": {"input": ["text"]}
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf("deepseek" to ModelDefinitions()),
        )

        assertNull("PiKit's own override is gone", result.overrideFor("deepseek", "vision"))
        assertNull(
            "and its `input` is taken out of one the user added fields to",
            result.overrideFor("deepseek", "vision-with-keys")!!["input"],
        )
        assertEquals(
            "false",
            result.overrideFor("deepseek", "vision-with-keys")!!["reasoning"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "a text-only declaration the user wrote is not PiKit's",
            listOf("text"),
            inputOf(result.overrideFor("deepseek", "text-only")!!),
        )
    }

    @Test
    fun `an override table left empty by the withdrawal is removed`() {
        val existing = json(
            """
            {"providers":{"deepseek":{"modelOverrides":{"vision":{"input":["text","image"]}}}}}
            """.trimIndent(),
        )

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf("deepseek" to ModelDefinitions()),
        )

        assertNull("the provider entry is dropped rather than left as an empty object", result.providers()["deepseek"])
    }

    // --------------------------------------------------- the rest of the file

    @Test
    fun `an entry that is not a json object survives in the user's array`() {
        // pi's own schema would reject this, but the app must not be the thing that
        // drops it: with nothing PiKit knows how to withdraw the array is copied
        // element-wise.
        val existing = json("""{"providers":{"deepseek":{"models":["oops",{"id":"a"}]}}}""")

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = mapOf("deepseek" to ModelDefinitions()),
        )

        assertEquals(2, (result.provider("deepseek")["models"] as JsonArray).size)
    }

    @Test
    fun `a provider entry that PiKit empties is dropped rather than left as an empty object`() {
        val existing = json(
            """{"providers":{"deepseek":{"models":[${legacyRegistration("vision-v1")}]}}}""",
        )

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(wanted = emptyList(), ours = listOf("vision-v1")),
        )

        assertNull(result.providers()["deepseek"])
    }

    @Test
    fun `a models key that is not an array is not interpreted`() {
        val existing = json("""{"providers":{"deepseek":{"models":{"id":"a"}}}}""")

        val result = modelsJsonWith(
            existing = existing,
            customProviderId = CustomEndpoint.PROVIDER_ID,
            customProvider = null,
            definitions = definitions(listOf(entry("vision-v1"))),
        )

        // Left exactly as the user wrote it. pi rejects this document on its schema check
        // either way, and inventing a meaning for that key is not this function's job.
        assertEquals(
            "{\"id\":\"a\"}",
            result.provider("deepseek")["models"].toString(),
        )
    }

    @Test
    fun `the old factless registration is still recognised by shape`() {
        // The entry the build before the record existed wrote, and whose id the profile has
        // never heard of: the shape is the only thing left that can find it.
        val entry = json(legacyRegistration("vision-v1"))
        assertTrue(isPikitModelDefinition(entry))
        assertFalse(isPikitModelDefinition(json(legacyRegistration("x").replace("\"input\": [\"text\", \"image\"]", "\"input\": [\"text\"]"))))
    }
}
