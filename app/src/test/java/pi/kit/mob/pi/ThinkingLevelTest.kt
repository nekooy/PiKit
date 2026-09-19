package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.rememberedThinkingLevels
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.ui.components.thinkingLevelFootnote
import pi.kit.mob.ui.components.thinkingLevelOptions

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
 * The fixtures below are the real catalog maps from the bundled pi
 * (`chunk-JVUZSMYM.js`, `deepseek_default`), so a pi update that changes one is
 * caught here as a failing expectation rather than as a wrong chip on a phone.
 */
class ThinkingLevelTest {

    /** `deepseek-v4-pro`: only high and max, plus off. */
    private val deepseekPro = listOf("off", "high", "max")

    /** `deepseek-v4-flash`: low, high and max, plus off. */
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
        assertEquals(deepseekFlash, thinkingLevelsFor(emptyList(), remembered))
        assertEquals("high", clampThinkingLevel("medium", thinkingLevelsFor(emptyList(), remembered)))
        // pi's answer for the model that is running always wins over the memory.
        assertEquals(deepseekPro, thinkingLevelsFor(deepseekPro, remembered))
        // And with neither, the preference is shown unclamped.
        assertTrue(thinkingLevelsFor(emptyList(), emptyList()).isEmpty())
        assertEquals("medium", clampThinkingLevel("medium", thinkingLevelsFor(emptyList(), emptyList())))
    }

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
        assertTrue(thinkingLevelsFor(emptyList(), none).isEmpty())
        assertEquals(
            PiLaunchOptions.THINKING_LEVELS,
            thinkingLevelOptions(stringsFor(Lang.ENGLISH), thinkingLevelsFor(emptyList(), none))
                .map { it.id },
        )
    }

    // ------------------------------------------------------- what the UI shows

    @Test
    fun `a level is named with pi's own id in every language`() {
        // The report: the chip said `中` and pi's status line said `medium` for the
        // same level. The name is the value that is sent, in every language; only
        // the sentence explaining it is translated.
        PiLaunchOptions.THINKING_LEVELS.forEach { level ->
            Lang.entries.forEach { lang ->
                val options = thinkingLevelOptions(stringsFor(lang))
                val option = options.single { it.id == level }
                assertEquals("$lang/$level", level, option.label)
                assertTrue("$lang/$level has a description", option.description.orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun `the footnote explains a short list, and the single-level case is its own sentence`() {
        val chinese = stringsFor(Lang.CHINESE)

        // All seven, or nothing known: nothing to explain.
        assertNull(thinkingLevelFootnote(chinese, null))
        assertNull(thinkingLevelFootnote(chinese, emptyList()))
        assertNull(thinkingLevelFootnote(chinese, PiLaunchOptions.THINKING_LEVELS))
        assertNull(thinkingLevelFootnote(chinese, PiLaunchOptions.THINKING_LEVELS + "ultra"))

        // A model with fewer levels: the note names them, in pi's own words.
        val note = thinkingLevelFootnote(chinese, deepseekFlash)
        assertEquals("当前模型只有这些等级：off, low, high, max。模型没有的等级，pi 会自动往上取最接近的一档。", note)

        // And a model that does not reason at all is not "a model with one level":
        // pi's own answer for it is `["off"]`, and there is nothing to choose.
        assertEquals(chinese.chat.thinkingDisabled, thinkingLevelFootnote(chinese, listOf("off")))
    }
}
