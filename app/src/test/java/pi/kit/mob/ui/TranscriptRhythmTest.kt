package pi.kit.mob.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.stringsFor
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.ui.chat.TurnSummaryData
import pi.kit.mob.ui.chat.durationSeconds
import pi.kit.mob.ui.chat.turnSummaryLabel

/**
 * The transcript's vertical rhythm, the rows it does not draw, and the fold band's
 * label.
 *
 * The gaps are the kind of change that looks like a preference and behaves like a
 * bug: one number for every pair of rows is what made a prompt, its fold band, its
 * steps and its answer read as one undifferentiated stream, and an empty row that
 * still pays its own gap is 22dp of blank space in the middle of an expanded turn.
 * None of it is visible in a diff of the page, and none of it can be seen on a build
 * machine.
 */
class TranscriptRhythmTest {

    private fun user(key: String = "u") = TranscriptRow.Message(ChatItem.User(key = key, text = "hi"))

    private fun assistant(
        key: String = "a",
        text: String = "",
        thinking: String = "",
        streaming: Boolean = false,
        error: String? = null,
    ) = TranscriptRow.Message(
        ChatItem.Assistant(
            key = key,
            text = text,
            thinking = thinking,
            isStreaming = streaming,
            error = error,
        ),
    )

    private fun tool(key: String = "t") = TranscriptRow.Message(ChatItem.Tool(key = key, name = "bash"))

    private fun notice(key: String = "n") = TranscriptRow.Message(ChatItem.Notice(key = key, text = "!"))

    private fun summary(index: Int = 0) = TranscriptRow.TurnSummary(
        turnIndex = index,
        summary = TurnSummaryData(stepCount = 1, elapsedMs = 1_000, failedSteps = 0),
    )

    /** The gap above [row], as the list computes it. */
    private fun gap(previous: TranscriptRow?, row: TranscriptRow): Dp = rowGap(previous, row)

    @Test
    fun `every module inside a turn is spaced the same, in any order`() {
        // The four kinds of row a turn is made of, in both orders. This is the test the
        // reader's report asks for — the gap must not depend on which of the two rows
        // happens to come first — and it is what pins the unification: the rule used to
        // be 4dp between two calls, 8dp after a call and 12dp before one, so
        // "thinking → bash → thinking" showed three different distances.
        val modules = listOf(
            "thought" to assistant(text = "let me look"),
            "call" to tool(),
            "answer" to assistant(key = "a2", text = "done"),
            "notice" to notice(),
        )
        modules.forEach { (beforeName, before) ->
            modules.forEach { (afterName, after) ->
                assertEquals("$beforeName → $afterName", 8.dp, gap(before, after))
            }
        }
    }

    @Test
    fun `a duration never reads as zero`() {
        // A turn is a model round trip: `0.4s` invites reading meaning into noise, and
        // with units in the label a zero would come out as `工作 0 秒`.
        assertEquals(1L, durationSeconds(0))
        assertEquals(1L, durationSeconds(999))
        assertEquals(1L, durationSeconds(1_000))
        assertEquals(47L, durationSeconds(47_400))
        assertEquals(65L, durationSeconds(65_000))
    }

    @Test
    fun `the turn band says what it worked and what it hides, in the interface's units`() {
        val chinese = stringsFor(Lang.CHINESE).chat

        // The reference's own format, and the units come from the language: a Latin
        // `s` in a Chinese label was the report that got this rewritten.
        assertEquals("47 秒", chinese.duration(47))
        assertEquals("1 分 05 秒", chinese.duration(65))
        assertEquals("2 分", chinese.duration(120))
        assertEquals("工作 47 秒 · 5 个步骤", chinese.turnWorked(chinese.duration(47), 5))
        // A turn that hides nothing says so by saying nothing about steps.
        assertEquals("工作 47 秒", chinese.turnWorked(chinese.duration(47), null))
        assertEquals("47s", stringsFor(Lang.ENGLISH).chat.duration(47))
        assertEquals("47 秒", stringsFor(Lang.JAPANESE).chat.duration(47))
    }

    @Test
    fun `the fold row is silent about what the turn does not know`() {
        val text = stringsFor(Lang.CHINESE)

        fun label(steps: Int, elapsed: Long?) =
            turnSummaryLabel(TurnSummaryData(stepCount = steps, elapsedMs = elapsed, failedSteps = 0), text)

        assertEquals("工作 47 秒 · 5 个步骤", label(steps = 5, elapsed = 47_400))
        assertEquals("工作 47 秒", label(steps = 0, elapsed = 47_400))
        // A session restored from messages with no timestamps: the count is the only
        // honest thing the row has, and no duration is invented for it.
        assertEquals("5 个步骤", label(steps = 5, elapsed = null))
        // And neither fact: nothing, rather than `0 个步骤`.
        assertEquals("", label(steps = 0, elapsed = null))
    }

    @Test
    fun `the reasoning row carries its own size in every language`() {
        Lang.entries.forEach { lang ->
            val chat = stringsFor(lang).chat
            assertTrue("$lang", chat.reasoningLabel(120).contains("120"))
            // `1.2k` past a thousand, so the row does not grow a digit per token.
            assertTrue("$lang", chat.reasoningLabel(1_240).contains("1.2k"))
        }
    }

    @Test
    fun `the fold band is a module of its turn like any other`() {
        assertEquals(8.dp, gap(summary(), tool()))
        assertEquals(8.dp, gap(summary(), assistant(text = "the answer")))
    }

    @Test
    fun `the first row of the transcript has no gap above it`() {
        assertEquals(0.dp, gap(null, user()))
        assertEquals(0.dp, gap(null, assistant(text = "hello")))
    }

    @Test
    fun `a turn is still separated from its prompt and from the next turn`() {
        // The two numbers that survive the unification, and the reason they do: they
        // are statements about the transcript's *structure* rather than about which
        // row follows which inside a turn. Collapsing them too is what made a
        // conversation read as one undifferentiated stream.
        assertEquals(12.dp, gap(user(), assistant(text = "after a notice")))
        assertEquals(12.dp, gap(user(), summary()))
        assertEquals(12.dp, gap(user(), tool()))
        assertEquals(12.dp, gap(user(), notice()))
        assertEquals(20.dp, gap(assistant(text = "one answer"), user()))
        assertEquals(20.dp, gap(tool(), user()))
        assertEquals(20.dp, gap(notice(), user()))
    }

    @Test
    fun `an assistant row with nothing in it is not drawn`() {
        // The row pi opens for the message that carried a tool call and no text.
        assertTrue(assistant().drawsNothing())
        // A streaming row with nothing to show yet is the same case: the live cursor
        // that used to give it a body was removed, so it must not be measured as a
        // blank row that still pays its `rowGap`.
        assertTrue(assistant(streaming = true).drawsNothing())
        // The three states that do have something to say.
        assertFalse(assistant(text = "x").drawsNothing())
        assertFalse(assistant(thinking = "weighing it up").drawsNothing())
        assertFalse(assistant(error = "boom").drawsNothing())
        // Nothing else can be empty: a tool without arguments still shows its name,
        // and a notice always carries text.
        assertFalse(tool().drawsNothing())
        assertFalse(user().drawsNothing())
        assertFalse(notice().drawsNothing())
        assertFalse(summary().drawsNothing())
    }
}
