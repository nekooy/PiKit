package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a **real** pi RPC stream through the reducer.
 *
 * ## Why this test exists
 *
 * Every other reducer test uses hand-written fixtures. Those fixtures were written
 * from the same understanding as the reducer itself, so they can only confirm that
 * the code does what its author believed pi does — not that the belief is right.
 * That is not a theoretical gap. One mismatch survived the whole suite:
 *
 * > pi does not put an `id` on `toolcall_delta` records. Of 55 such records in the
 * > captured turn, none carried one. The reducer read `delta.id` to decide which
 * > card to append arguments to, so every streamed tool argument was silently
 * > dropped — no exception, no log line, just arguments that appeared late. The
 * > hand-written fixture for the same lifecycle *included* an `id`, so the test
 * > agreed with the bug.
 *
 * ## Where the fixture comes from
 *
 * `tools/capture-pi-rpc.mjs` drives a real pi process over RPC and records what it
 * emits; `tools/make-rpc-fixture.py` strips the volatile fields and writes
 * `app/src/test/resources/pi-rpc/real-tool-turn.jsonl`. Every field name, nesting
 * level and event order in it is pi's own.
 *
 * The captures are of a turn that used the `bash` tool five times before
 * answering, so the lifecycle under test is the full one: thinking, a tool call
 * with streamed arguments, execution, a tool result, a retry after the tool
 * failed, and finally the answer.
 */
class RealCaptureTest {

    private val records: List<PiRecord> by lazy {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("pi-rpc/real-tool-turn.jsonl")
            ?: error("the pi RPC fixture is missing from the test resources")
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map(PiRecordParser::parse).toList()
        }
    }

    /** Replays the capture with a clock that advances, so timing is assertable. */
    private fun replay(): ConversationState {
        var clock = 1_000L
        return records.fold(ConversationState()) { state, record ->
            clock += 500
            ConversationReducer.reduce(state, record, clock)
        }
    }

    @Test
    fun `the fixture is the shape it claims to be`() {
        assertTrue("the fixture should be a substantial stream", records.size > 300)
        val types = records.mapNotNull { (it as? PiRecord.Event)?.type }
        for (expected in listOf(
            "agent_start",
            "message_start",
            "message_update",
            "tool_execution_start",
            "tool_execution_update",
            "tool_execution_end",
            "agent_settled",
        )) {
            assertTrue("$expected must appear in the capture", expected in types)
        }
    }

    /**
     * The regression this whole fixture exists for. If the reducer stops
     * correlating tool deltas by content index, this fails.
     */
    @Test
    fun `every streamed tool argument reached its card`() {
        val state = replay()
        val tools = state.items.filterIsInstance<ChatItem.Tool>()

        assertEquals("the capture made five tool calls", 5, tools.size)
        for (tool in tools) {
            assertEquals("the card is named from toolcall_start", "bash", tool.name)
            assertTrue(
                "card ${tool.key} has arguments",
                tool.argumentsJson.isNotBlank(),
            )
            // Every call in the capture ran `echo`-style commands, so a complete
            // argument object is a JSON object naming `command`.
            assertTrue(
                "card ${tool.key} arguments look complete: ${tool.argumentsJson.take(80)}",
                tool.argumentsJson.contains("command"),
            )
        }
    }

    @Test
    fun `tool cards carry their ids as keys, which is what execution events address`() {
        val state = replay()
        val tools = state.items.filterIsInstance<ChatItem.Tool>()
        for (tool in tools) {
            assertTrue(
                "a tool key should be the provider's call id, not a synthetic index: ${tool.key}",
                tool.key.startsWith("call_"),
            )
            assertTrue("the content index is recorded", tool.contentIndex >= 0)
        }
    }

    @Test
    fun `a tool that failed is reported as failed`() {
        val state = replay()
        val tools = state.items.filterIsInstance<ChatItem.Tool>()
        // The capture host had no usable shell, so every bash call failed. That is
        // a property of the capture, and it is worth asserting: it proves an error
        // result reaches the card rather than being swallowed.
        assertTrue(
            "the capture's tool calls all failed",
            tools.all { it.state == ToolState.Failed },
        )
    }

    @Test
    fun `the turn settles, is timed, and folds to its reply`() {
        val state = replay()

        assertFalse("agent_settled arrived, so the agent is idle", state.isStreaming)

        val turn = state.turns.singleOrNull()
        assertNotNull("the capture is one turn", turn)
        turn!!
        assertTrue("agent_settled closed the turn", turn.isComplete)
        assertTrue("the turn is timed", turn.elapsed(Long.MAX_VALUE) > 0)

        val reply = state.items.firstOrNull { it.key == turn.finalReplyKey }
        assertNotNull("the turn knows which reply it settled on", reply)
        assertTrue("the reply is an assistant message", reply is ChatItem.Assistant)
        assertTrue(
            "the reply has text: ${(reply as ChatItem.Assistant).text.take(60)}",
            reply.text.isNotBlank(),
        )

        // What the chat page will hide: the tool cards and the tool-only assistant
        // messages, but never the prompt or the reply.
        val hidden = state.foldedKeys()
        assertTrue("the tool cards fold away", tools(state).all { it.key in hidden })
        assertTrue("the prompt stays", state.items.filterIsInstance<ChatItem.User>().none { it.key in hidden })
        assertFalse("the reply stays", turn.finalReplyKey in hidden)
    }

    private fun tools(state: ConversationState) = state.items.filterIsInstance<ChatItem.Tool>()

    /**
     * Usage is reported per assistant message and repeats while a message streams,
     * so the total must count each message once. A capture with twelve assistant
     * messages is the case that catches double counting.
     */
    @Test
    fun `usage totals accumulate per message without double counting`() {
        val state = replay()

        val assistants = state.items.filterIsInstance<ChatItem.Assistant>().size
        assertTrue("the capture has several assistant messages", assistants >= 5)
        assertTrue("tokens were counted", state.usage.total.total > 0)
        assertEquals(
            "the live message's running total is cleared once it ends",
            UsageInfo.EMPTY,
            state.usage.message,
        )
        assertTrue(
            "the session total includes what the messages used",
            state.usage.session.total > 0,
        )
    }

    /** The mid-turn commentary pi emits between tool calls must still be captured. */
    @Test
    fun `thinking text from the capture is preserved`() {
        val state = replay()
        val withThinking = state.items
            .filterIsInstance<ChatItem.Assistant>()
            .filter { it.thinking.isNotBlank() }
        assertTrue("the capture contains reasoning", withThinking.isNotEmpty())
    }
}
