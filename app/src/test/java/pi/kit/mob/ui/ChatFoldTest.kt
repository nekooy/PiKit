package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.pi.ConversationReducer
import pi.kit.mob.pi.ConversationState
import pi.kit.mob.pi.PiRecordParser

/**
 * The transcript's fold rules.
 *
 * A finished turn is supposed to collapse to its prompt, its answer and a
 * one-line summary. Getting this wrong is not a cosmetic bug — it either hides the
 * answer the user asked for, or leaves a forty-row tool transcript on screen and
 * makes the feature pointless. Both failure modes are cheap to check here and
 * expensive to notice on a device.
 *
 * The event sequence is the one captured from a real tool-using turn: two
 * assistant messages, the first ending in a tool call, the second producing the
 * answer, then `agent_settled`.
 */
class ChatFoldTest {

    /** Folds a transcript the way the chat page does, with a fixed clock. */
    private fun state(vararg lines: String): ConversationState {
        var clock = 1_000L
        return lines.fold(ConversationState()) { acc, line ->
            clock += 1_000
            ConversationReducer.reduce(acc, PiRecordParser.parse(line), clock)
        }
    }

    /** A complete turn: prompt, a tool call, then the answer. */
    private fun toolUsingTurn(): ConversationState = state(
        """{"type":"agent_start"}""",
        """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"list the files"}]}}""",
        """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
        """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"call_1","toolName":"bash"}}""",
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"call_1","name":"bash"}],"stopReason":"toolUse"}}""",
        """{"type":"tool_execution_start","toolCallId":"call_1","toolName":"bash","args":{"command":"ls"}}""",
        """{"type":"tool_execution_end","toolCallId":"call_1","toolName":"bash","result":{"content":[{"type":"text","text":"a.txt\nb.txt"}]},"isError":false}""",
        """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
        """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"There are two files."}}""",
        """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"There are two files."}],"stopReason":"stop"}}""",
        """{"type":"agent_settled"}""",
    )

    private fun List<TranscriptRow>.messages() = filterIsInstance<TranscriptRow.Message>().map { it.item }
    private fun List<TranscriptRow>.summaries() = filterIsInstance<TranscriptRow.TurnSummary>()

    /**
     * The `data` half of a `get_messages` response, built from the messages alone
     * so the tests read as history rather than as wire format.
     */
    private fun history(messages: String) =
        Json.parseToJsonElement("""{"messages":$messages}""")

    @Test
    fun `a finished turn folds to its prompt, its reply and a summary`() {
        val state = toolUsingTurn()
        val rows = transcriptRows(state, expanded = emptySet())

        val shown = rows.messages()
        assertEquals("the prompt and the reply, nothing between", 2, shown.size)
        assertTrue("the prompt is shown", shown[0] is ChatItem.User)
        val reply = shown[1]
        assertTrue("the reply is shown", reply is ChatItem.Assistant)
        assertEquals("There are two files.", (reply as ChatItem.Assistant).text)

        // The tool card is hidden, and the summary says how much is behind it.
        // Two rows, not one: the assistant message that carried only the tool call
        // has no text either, so it is part of what the fold collapses.
        assertTrue("the tool card is folded away", shown.none { it is ChatItem.Tool })
        val summary = rows.summaries().single()
        assertEquals("the tool call and its empty message are hidden", 2, summary.summary.stepCount)
        assertTrue("a turn takes time", (summary.summary.elapsedMs ?: 0) > 0)
        assertEquals("nothing failed", 0, summary.summary.failedSteps)
    }

    @Test
    fun `the summary sits above the turn, directly under its prompt`() {
        val rows = transcriptRows(toolUsingTurn(), expanded = emptySet())
        val prompt = rows.indexOfFirst { it is TranscriptRow.Message && it.item is ChatItem.User }
        val summary = rows.indexOfFirst { it is TranscriptRow.TurnSummary }
        val reply = rows.indexOfFirst {
            it is TranscriptRow.Message && (it.item as? ChatItem.Assistant)?.text == "There are two files."
        }
        assertTrue("the summary is under the prompt", summary > prompt)
        assertTrue("the summary is above the reply", summary < reply)
        // Nothing but the summary sits between them, so the control cannot be
        // pushed down the screen by the length of the answer below it.
        assertEquals("the summary is adjacent to the prompt", prompt + 1, summary)
    }

    /**
     * The bug this pins: the reply is the one row a folded turn still shows, so
     * its own 查看思考过程 row used to stay on screen while everything around it
     * was folded — a second fold control, contradicting the first.
     */
    @Test
    fun `a folded turn offers one fold control, not two`() {
        val state = state(
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"think"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"weighing it up"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"c1","toolName":"bash"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"bash"}],"stopReason":"toolUse"}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"almost done"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Answer."}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Answer."}],"stopReason":"stop"}}""",
            """{"type":"agent_settled"}""",
        )

        val folded = transcriptRows(state, expanded = emptySet())
        val reply = folded.filterIsInstance<TranscriptRow.Message>()
            .single { (it.item as? ChatItem.Assistant)?.text == "Answer." }
        assertFalse("the reply's reasoning folds in with the turn", reply.showReasoning)

        val opened = transcriptRows(state, expanded = setOf(0))
        val openedReply = opened.filterIsInstance<TranscriptRow.Message>()
            .single { (it.item as? ChatItem.Assistant)?.text == "Answer." }
        assertTrue("opening the turn brings the reasoning control back", openedReply.showReasoning)
    }

    /**
     * The counter-case to the test above: a turn that hides nothing must not
     * suppress anything, or reasoning would become unreachable.
     */
    @Test
    fun `a turn that hides nothing keeps its reasoning controls`() {
        val state = state(
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"just answer"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"brief"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Done."}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Done."}],"stopReason":"stop"}}""",
            """{"type":"agent_settled"}""",
        )

        val rows = transcriptRows(state, expanded = emptySet())
        val reply = rows.filterIsInstance<TranscriptRow.Message>()
            .single { (it.item as? ChatItem.Assistant)?.text == "Done." }
        assertTrue("nothing is hidden, so nothing is suppressed", reply.showReasoning)
        assertEquals("the row still reports the time", 0, rows.summaries().single().summary.stepCount)
    }

    @Test
    fun `expanding a turn brings its steps back`() {
        val state = toolUsingTurn()
        val rows = transcriptRows(state, expanded = setOf(0))

        assertTrue(
            "the tool card is visible again",
            rows.messages().any { it is ChatItem.Tool },
        )
        assertEquals("the reply is still shown exactly once", 1, rows.messages().count {
            it is ChatItem.Assistant && it.text == "There are two files."
        })
        assertEquals("a summary row is still offered", 1, rows.summaries().size)
    }

    /**
     * The case that must not regress: a turn whose agent produced no closing
     * sentence — it was aborted, or a tool stopped everything. Folding to a null
     * reply would leave the user staring at their own prompt.
     */
    @Test
    fun `a turn with no reply folds nothing away`() {
        val state = state(
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"do a thing"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"call_x","toolName":"bash"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"call_x","name":"bash"}],"stopReason":"toolUse"}}""",
            """{"type":"agent_settled"}""",
        )

        val shown = transcriptRows(state, expanded = emptySet()).messages()
        assertTrue("the prompt survives", shown.any { it is ChatItem.User })
        assertEquals(
            "with no reply there is nothing to fold to, so the tool card stays",
            1,
            shown.count { it is ChatItem.Tool },
        )
    }

    /**
     * A second prompt is a new turn. The first must stay folded while the second
     * runs, or the transcript would grow back to full length on every message.
     */
    @Test
    fun `folding applies per turn and leaves the running turn alone`() {
        val first = toolUsingTurn()
        val both = state(
            // Replay the first turn's records, then a second prompt that is still
            // running.
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"first"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"c1","toolName":"bash"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"bash"}],"stopReason":"toolUse"}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"first answer"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"first answer"}],"stopReason":"stop"}}""",
            """{"type":"agent_settled"}""",
            // Second turn, still running: its own tool call must be visible.
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"second"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"c2","toolName":"read"}}""",
        )

        assertEquals("two turns are recorded", 2, both.turns.size)
        val rows = transcriptRows(both, expanded = emptySet())
        val shown = rows.messages()

        assertEquals("the first turn's tool card is gone", 1, shown.count { it is ChatItem.Tool })
        assertEquals("the running turn's tool card is visible", "read", (shown.last { it is ChatItem.Tool } as ChatItem.Tool).name)
        assertTrue("the first answer is still shown", shown.any {
            it is ChatItem.Assistant && it.text == "first answer"
        })
        assertEquals("only the finished turn has a summary", 1, rows.summaries().size)
        assertFalse("the first turn is complete", both.turns[0].isComplete.not())
        assertFalse("the second turn is still running", both.turns[1].isComplete)
    }

    /**
     * Before any turn is recorded — the very first records of a session — the
     * transcript must render unfiltered rather than empty.
     */
    @Test
    fun `a transcript with no turns renders everything`() {
        val state = ConversationState(
            items = listOf(ChatItem.Notice(key = "notice-1", text = "hello", createdAt = 1)),
            turns = emptyList(),
        )
        assertEquals(1, transcriptRows(state, expanded = emptySet()).size)
    }

    /**
     * The regression this pins: switching conversations restored the messages but
     * not the turns, so every past turn came back fully unfolded — the fold looked
     * like it had been "lost" on a session switch. The two records below are the
     * shape `get_messages` returns, taken from a real session file.
     */
    @Test
    fun `a session restored from disk folds the same way a live one does`() {
        val history = history(
            """
            [
              {"role":"user","content":[{"type":"text","text":"list the files"}],"timestamp":1000},
              {"role":"assistant","content":[{"type":"toolCall","id":"call_1","name":"bash",
                "arguments":{"command":"ls"}}],"stopReason":"toolUse","timestamp":2000},
              {"role":"toolResult","toolCallId":"call_1","content":[{"type":"text","text":"a.txt"}],
                "timestamp":2500},
              {"role":"assistant","content":[{"type":"text","text":"There are two files."}],
                "stopReason":"stop","timestamp":4000}
            ]
            """.trimIndent(),
        )

        val state = ConversationReducer.replaceWithMessages(ConversationState(), history)
        assertEquals("the prompt opens a turn", 1, state.turns.size)
        assertTrue("the restored turn is closed", state.turns[0].isComplete)

        val rows = transcriptRows(state, expanded = emptySet())
        val shown = rows.messages()
        assertEquals("prompt and reply only", 2, shown.size)
        assertTrue("the tool card is folded away", shown.none { it is ChatItem.Tool })

        val summary = rows.summaries().single()
        // One row, not two: the live path appends an assistant row on
        // `message_start` before it knows the message carries only a tool call, so
        // a live turn also hides that empty row. History has no such row to hide.
        assertEquals("the tool call is hidden", 1, summary.summary.stepCount)
        assertEquals("the stored timestamps give the real duration", 3_000L, summary.summary.elapsedMs)
    }

    /**
     * A restored turn whose messages carry no timestamps must not claim a
     * duration: the row showed `1s` for every turn before this was handled.
     */
    @Test
    fun `a restored turn without timestamps reports no duration`() {
        val restored = history(
            """
            [
              {"role":"user","content":[{"type":"text","text":"hi"}]},
              {"role":"assistant","content":[{"type":"text","text":"hello"}],"stopReason":"stop"}
            ]
            """.trimIndent(),
        )

        val state = ConversationReducer.replaceWithMessages(ConversationState(), restored)
        val summary = transcriptRows(state, expanded = emptySet()).summaries().single()
        assertNull("no timestamps means no duration", summary.summary.elapsedMs)
    }

    /**
     * A turn that ran for hours between the prompt and the next one must not be
     * billed for the idle gap: the turn closes at its own last message.
     */
    @Test
    fun `a restored turn is billed to its own last message, not the next prompt`() {
        val restored = history(
            """
            [
              {"role":"user","content":[{"type":"text","text":"first"}],"timestamp":1000},
              {"role":"assistant","content":[{"type":"text","text":"one"}],"stopReason":"stop",
                "timestamp":5000},
              {"role":"user","content":[{"type":"text","text":"second"}],"timestamp":999000},
              {"role":"assistant","content":[{"type":"text","text":"two"}],"stopReason":"stop",
                "timestamp":999500}
            ]
            """.trimIndent(),
        )

        val state = ConversationReducer.replaceWithMessages(ConversationState(), restored)
        assertEquals(2, state.turns.size)
        assertEquals("the first turn ends with its own reply", 4_000L, state.turns[0].elapsed(0))
        assertEquals("the second turn is its own", 500L, state.turns[1].elapsed(0))
    }

    /**
     * The mark on the fold chip counts the steps that failed outright, and a
     * warning is not one of them: "compaction will retry" is the agent working as
     * intended, and counting it put a red mark on turns that succeeded.
     */
    @Test
    fun `only real failures are counted on the fold chip`() {
        val state = state(
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"go"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"c1","toolName":"bash"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"bash"}],"stopReason":"toolUse"}}""",
            """{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"ls nope"}}""",
            """{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","result":{"content":[{"type":"text","text":"Path not found"}]},"isError":true}""",
            """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"errorMessage":"transient"}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Recovered."}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Recovered."}],"stopReason":"stop"}}""",
            """{"type":"agent_settled"}""",
        )

        val summary = transcriptRows(state, expanded = emptySet()).summaries().single()
        assertEquals("the failed tool is counted", 1, summary.summary.failedSteps)
    }

    /**
     * An ordinary turn — no tool failures, no error notices — carries no mark.
     * The warning notice below must not raise one.
     */
    @Test
    fun `a warning notice does not mark the turn as failed`() {
        val state = state(
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"go"}]}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"c1","toolName":"read"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"read"}],"stopReason":"toolUse"}}""",
            """{"type":"tool_execution_start","toolCallId":"c1","toolName":"read","args":{"path":"a"}}""",
            """{"type":"tool_execution_end","toolCallId":"c1","toolName":"read","result":{"content":[{"type":"text","text":"ok"}]},"isError":false}""",
            """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"errorMessage":"transient"}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Done."}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Done."}],"stopReason":"stop"}}""",
            """{"type":"agent_settled"}""",
        )

        val summary = transcriptRows(state, expanded = emptySet()).summaries().single()
        assertEquals("a warning-level notice is not a failure", 0, summary.summary.failedSteps)
    }

    /**
     * A restored conversation has to report the same totals a live one does: the
     * header shows them, so losing them made the counter look broken rather than
     * empty.
     */
    @Test
    fun `a restored conversation adds up its token totals`() {
        val restored = history(
            """
            [
              {"role":"user","content":[{"type":"text","text":"hi"}],"timestamp":1000},
              {"role":"assistant","content":[{"type":"text","text":"one"}],"stopReason":"stop",
                "timestamp":2000,"usage":{"input":100,"output":10,"totalTokens":110}},
              {"role":"user","content":[{"type":"text","text":"more"}],"timestamp":3000},
              {"role":"assistant","content":[{"type":"text","text":"two"}],"stopReason":"stop",
                "timestamp":4000,"usage":{"input":250,"output":20,"totalTokens":270}}
            ]
            """.trimIndent(),
        )

        val state = ConversationReducer.replaceWithMessages(ConversationState(), restored)
        val totals = state.usage.total
        assertEquals("inputs are summed across messages", 350L, totals.input)
        assertEquals("outputs too", 30L, totals.output)
    }
}
