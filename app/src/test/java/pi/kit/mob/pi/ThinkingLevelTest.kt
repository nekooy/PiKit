package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.rememberedThinkingLevels

/**
 * pi's own rule for a thinking level, and the app's two additions to it.
 *
 * `getSupportedThinkingLevels` (`pi-ai`) filters pi's seven levels through the
 * model's `thinkingLevelMap`: a level mapped to `null` is not offered, `xhigh`/`max`
 * are offered **only** when the map names them, and a model with no reasoning support
 * gets exactly `["off"]`. `clampThinkingLevel` then walks a request it cannot honour
 * **forwards**, which is why asking a DeepSeek model for `medium` leaves pi on
 * `high`.
 *
 * The fixtures below are the real catalog maps from the bundled pi's
 * `deepseek` provider (`chunk-MM3LKT73.js` at pi 1.4.0), so a pi update that
 * changes one is caught here as a failing expectation rather than as a wrong
 * chip on a phone.
 */
class ThinkingLevelTest {

    /** `deepseek-v4-pro`: only high and max, plus off. */
    private val deepseekPro = listOf("off", "high", "max")

    /** `deepseek-flash` (V4.1 Flash): low, high and max, plus off. */
    private val deepseekFlash = listOf("off", "low", "high", "max")

    @Test
    fun `a supported level is kept as asked`() {
        assertEquals("high", clampThinkingLevel("high", deepseekPro))
        assertEquals("off", clampThinkingLevel("off", deepseekFlash))
    }

    @Test
    fun `an unsupported level moves up to the next one the model has`() {
        // The report: tapping `medium` on DeepSeek left pi on `high`.
        assertEquals("high", clampThinkingLevel("medium", deepseekFlash))
        assertEquals("high", clampThinkingLevel("minimal", deepseekPro))
        assertEquals("max", clampThinkingLevel("xhigh", deepseekPro))
    }

    @Test
    fun `with nothing above it a level falls back to the highest the model has`() {
        // `max` is the top of pi's order, so nothing is above it and the walk goes
        // back down; a model whose highest level is `high` answers with `high`.
        assertEquals("high", clampThinkingLevel("max", listOf("off", "low", "high")))
    }

    @Test
    fun `an unknown level and an unknown model both land on the first supported level`() {
        assertEquals("off", clampThinkingLevel("nonsense", deepseekPro))
        assertEquals("off", clampThinkingLevel("medium", listOf("off")))
    }

    @Test
    fun `an empty list leaves the level alone, because nothing is known yet`() {
        // Before a running pi has answered there is no list at all; the saved value
        // is what the agent was launched with and is the best answer available.
        assertEquals("medium", clampThinkingLevel("medium", emptyList()))
    }

    @Test
    fun `the remembered levels stand in until pi answers for the model`() {
        // What replaced persisting pi's clamped level. The saved preference is
        // `medium` and the model only has `high`: the remembered list is what lets
        // the chip read `high` from the first frame instead of showing `medium` and
        // then moving — without rewriting the preference, which is what used to
        // happen and which is how a non-reasoning model turned thinking off for good.
        val remembered = deepseekFlash
        assertEquals(deepseekFlash, levels(remembered = remembered))
        assertEquals("high", clampThinkingLevel("medium", levels(remembered = remembered)))
        // pi's answer for the model that is running always wins over the memory.
        assertEquals(deepseekFlash, levels(live = deepseekFlash, liveFor = "deepseek-flash"))
        // And with neither, the preference is shown unclamped.
        assertTrue(levels().isEmpty())
        assertEquals("medium", clampThinkingLevel("medium", levels()))
    }

    @Test
    fun `the levels offered for a model are its catalogue entry's`() {
        // The other half of "switching the model leaves the previous model's menu up
        // for a second": the list is a property of one model's `thinkingLevelMap`, and
        // the file that states it is already on disk, so the picker does not have to
        // wait for the two RPC replies a switch causes.
        assertEquals(
            deepseekFlash,
            levels(modelId = "deepseek-v4-pro", catalogued = deepseekFlash),
        )
        // A live answer for *another* model is not an answer, and counting it as one is
        // the bug: after `set_model`, pi's list is still the previous model's until its
        // second reply lands.
        assertEquals(
            deepseekPro,
            levels(
                live = deepseekFlash,
                liveFor = "deepseek-flash",
                modelId = "deepseek-v4-pro",
                remembered = deepseekPro,
            ),
        )
        // A live answer for *this* model outranks both caches.
        assertEquals(
            deepseekPro,
            levels(
                live = deepseekPro,
                liveFor = "deepseek-v4-pro",
                modelId = "deepseek-v4-pro",
                remembered = deepseekFlash,
                catalogued = deepseekFlash,
            ),
        )
        // Neither a live answer nor a memory nor a catalogue entry: nothing is known and
        // the caller falls back to pi's seven, which is what the app offered before any
        // of this existed.
        assertTrue(levels(modelId = "a-model-nobody-lists").isEmpty())
    }

    @Test
    fun `the catalogue's thinkingLevelMap is read the way pi reads it`() {
        // pi's rule, on the maps the bundled catalogue actually ships. The two expected
        // answers are the two the RPC was measured giving (§7.2's table).
        assertEquals(
            listOf("off", "low", "high", "max"),
            supportedThinkingLevels(
                reasoning = true,
                thinkingLevelMap = mapOf(
                    "minimal" to null,
                    "low" to "low",
                    "medium" to null,
                    "high" to "high",
                    "max" to "max",
                ),
            ),
        )
        // The fallback model (`deepseek-v4-pro`): `minimal`, `low` and `medium` are all
        // refused, and `max` is named — three levels. Captured from the bundled
        // `deepseek.json`, so a pi update that changes a map lands here rather than on a
        // chip that offers a level the phone cannot use.
        assertEquals(
            listOf("off", "high", "max"),
            supportedThinkingLevels(
                reasoning = true,
                thinkingLevelMap = mapOf(
                    "minimal" to null,
                    "low" to null,
                    "medium" to null,
                    "high" to "high",
                    "max" to "max",
                ),
            ),
        )
        // `xhigh` and `max` are opt-in, and every other level is opt-*out*: a map that
        // does not mention `low` still has `low`. That asymmetry is pi's rule, not a
        // reading of it, and it is why a map is never treated as a whitelist.
        assertEquals(
            listOf("off", "minimal", "low", "medium", "high"),
            supportedThinkingLevels(true, emptyMap()),
        )
        assertEquals(
            listOf("off", "minimal", "low", "medium", "high", "max"),
            supportedThinkingLevels(true, mapOf("max" to "max")),
        )
        // A model with no reasoning support answers with one level, whatever its map
        // claims — the case that used to turn thinking off for every model.
        assertEquals(
            listOf("off"),
            supportedThinkingLevels(false, mapOf("high" to "high", "max" to "max")),
        )
    }

    /**
     * [thinkingLevelsFor] with the defaults a picker has on a first frame, so each test
     * below states only the source it is about.
     */
    private fun levels(
        live: List<String> = emptyList(),
        liveFor: String? = null,
        modelId: String? = "deepseek-flash",
        remembered: List<String> = emptyList(),
        catalogued: List<String> = emptyList(),
    ): List<String> = thinkingLevelsFor(live, liveFor, modelId, remembered, catalogued)

    @Test
    fun `the remembered levels are only used for the model they were measured on`() {
        // The levels are a property of the model's own `thinkingLevelMap`: v4-pro has
        // three, flash has four. So a memory without a model id is how a picker comes
        // to show the *previous* model's menu — a wrong list rather than a missing
        // one, which is worse — for the second between a switch and pi's answer.
        val saved = PiSettings(
            modelId = "deepseek-flash",
            availableThinkingLevels = deepseekFlash,
            availableThinkingLevelsFor = "deepseek-flash",
        )

        assertEquals(deepseekFlash, saved.rememberedThinkingLevels("deepseek-flash"))
        assertTrue(
            "another model's levels are not this model's",
            saved.rememberedThinkingLevels("deepseek-v4-pro").isEmpty(),
        )
        assertTrue(
            "and nothing is known before a model is",
            saved.rememberedThinkingLevels(null).isEmpty(),
        )
        assertTrue(
            "a list saved by a build that did not record the model is not trusted",
            PiSettings(availableThinkingLevels = deepseekFlash)
                .rememberedThinkingLevels("deepseek-flash")
                .isEmpty(),
        )

        // With no memory the caller has nothing, and the picker's own fallback takes
        // over: pi's seven, which is what pi itself answers when it has no model.
        val none = saved.rememberedThinkingLevels("something-else")
        assertTrue(none.isEmpty())
        assertTrue(levels(modelId = "something-else", remembered = none).isEmpty())
    }

}
