package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.ConversationReducer
import pi.kit.mob.pi.ConversationState
import pi.kit.mob.pi.PiRecordParser

/**
 * Which rows wear the copy button and the timestamp.
 *
 * Every message used to. A tool-using turn is several assistant messages, and the
 * sentences in the middle are *fragments* of the answer — "let me look", a tool
 * card, "here it is" — so one answer wore three or four copy glyphs and times, and
 * the furniture read as the content. The rule now is the fold's own: a prompt, and
 * the reply its turn settled on.
 *
 * The fixtures are the shape of a real turn rather than one message, because the
 * case that matters is exactly the one a single-message fixture cannot show: an
 * assistant message that carries text *and* a tool call, and the answer after it.
 * The tests read the flag [TranscriptRow.Message.showMeta] the composable reads,
 * so what is pinned is which row draws the row — not that a composable was called.
 */
class TranscriptFooterTest {

    /** Folds a transcript the way the chat page does, with a fixed clock. */
    private fun state(vararg lines: String): ConversationState {
        var clock = 1_000L
        return lines.fold(ConversationState()) { acc, line ->
            clock += 1_000
            ConversationReducer.reduce(acc, PiRecordParser.parse(line), clock)
        }
    }

    /**
     * One tool-using turn: the model says what it is about to do, calls a tool, and
     * then answers.
     *
     * Both assistant messages carry text, which is the whole point — the first one
     * is the fragment whose furniture the turn no longer wears.
     */
    private fun turn(prompt: String, opening: String, answer: String, call: String) = listOf(
        """{"type":"agent_start"}""",
        """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"$prompt"}]}}""",
        """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
        """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$opening"}}""",
        """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"$call","toolName":"bash"}}""",
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$opening"},{"type":"toolCall","id":"$call","name":"bash"}],"stopReason":"toolUse"}}""",
        """{"type":"tool_execution_start","toolCallId":"$call","toolName":"bash","args":{"command":"ls"}}""",
        """{"type":"tool_execution_end","toolCallId":"$call","toolName":"bash","result":{"content":[{"type":"text","text":"a.txt"}]},"isError":false}""",
        """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
        """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$answer"}}""",
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"$answer"}],"stopReason":"stop"}}""",
        """{"type":"agent_settled"}""",
    )

    /** A conversation of two tool-using turns, in the order pi emits them. */
    private fun twoTurns(): ConversationState = state(
        *(turn("list the files", "Let me look.", "There are two files.", "call_1") +
            turn("read them", "Reading now.", "Both are empty.", "call_2")).toTypedArray(),
    )

    /** The assistant rows that would draw a copy button and a time, by their text. */
    private fun answersWearing(rows: List<TranscriptRow>): List<String> =
        rows.filterIsInstance<TranscriptRow.Message>()
            .filter { it.showMeta }
            .mapNotNull { (it.item as? ChatItem.Assistant)?.text }

    @Test
    fun `only the reply a turn settled on wears the copy button and the time`() {
        // Expanded, so every row of both turns is on screen: the fragments are
        // visible and are still not the answer.
        val rows = transcriptRows(twoTurns(), expanded = setOf(0, 1))

        assertEquals(
            "the two answers, and neither of the two fragments",
            listOf("There are two files.", "Both are empty."),
            answersWearing(rows),
        )
    }

    @Test
    fun `a prompt keeps both, so every turn has somewhere to copy from`() {
        val rows = transcriptRows(twoTurns(), expanded = setOf(0, 1))
        val prompts = rows.filterIsInstance<TranscriptRow.Message>()
            .filter { it.item is ChatItem.User }
        assertEquals("both prompts are on screen", 2, prompts.size)
        assertEquals("and both of them wear the row", 2, prompts.count { it.showMeta })
    }

    @Test
    fun `expanding a turn adds its steps without moving the button`() {
        val folded = transcriptRows(twoTurns(), expanded = emptySet())
        val expanded = transcriptRows(twoTurns(), expanded = setOf(0, 1))
        assertEquals(
            "the same replies wear it either way, and the fragments never do",
            answersWearing(expanded),
            answersWearing(folded),
        )
        assertEquals(
            "which is both answers, since expanding hides nothing about them",
            listOf("There are two files.", "Both are empty."),
            answersWearing(folded),
        )
    }

    /**
     * A greeting a resumed session opens with is inside no turn at all
     * (`replaceWithMessages` leaves leading assistant messages unattributed on
     * purpose), so no turn's answer can be it. Dropping its row would leave a
     * conversation of one assistant message with no way to copy the only thing on
     * the page.
     */
    @Test
    fun `a leading greeting belongs to no turn and keeps both`() {
        val history = Json.parseToJsonElement(
            """
            {"messages":[
              {"role":"assistant","content":[{"type":"text","text":"Welcome back."}],"timestamp":1000},
              {"role":"user","content":[{"type":"text","text":"hi"}],"timestamp":2000},
              {"role":"assistant","content":[{"type":"text","text":"Hello."}],"timestamp":3000}
            ]}
            """.trimIndent(),
        )
        val restored = ConversationReducer.replaceWithMessages(ConversationState(), history)

        assertEquals(
            "the greeting and the answer, not the steps in between",
            listOf("Welcome back.", "Hello."),
            answersWearing(transcriptRows(restored, expanded = setOf(0))),
        )
    }
}
