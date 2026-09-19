package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the reducer with the event ordering observed from a live pi 0.85.1
 * process, so the transcript shown to the user is pinned to real wire behaviour.
 */
class ConversationReducerTest {

    private fun fold(vararg lines: String): ConversationState =
        ConversationReducer.reduceAll(ConversationState(), lines.map(PiRecordParser::parse))

    private fun ConversationState.assistants() = items.filterIsInstance<ChatItem.Assistant>()
    private fun ConversationState.users() = items.filterIsInstance<ChatItem.User>()
    private fun ConversationState.tools() = items.filterIsInstance<ChatItem.Tool>()

    @Test
    fun `folds a complete turn into a user and an assistant message`() {
        val state = fold(
            """{"id":"1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"claude-sonnet-4-5","name":"Claude Sonnet 4.5","api":"anthropic-messages","provider":"anthropic","contextWindow":1000000,"input":["text","image"]},"thinkingLevel":"medium","isStreaming":false,"sessionId":"01a08e58"}}""",
            """{"id":"2","type":"response","command":"prompt","success":true}""",
            """{"type":"agent_start"}""",
            """{"type":"turn_start"}""",
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"say hi"}],"timestamp":1789094592681}}""",
            """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"say hi"}],"timestamp":1789094592681}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","usage":{"input":10,"output":1,"totalTokens":11,"cost":{"total":0.001}},"assistantMessageEvent":{"type":"text_start","contentIndex":0}}""",
            """{"type":"message_update","usage":{"input":10,"output":2,"totalTokens":12,"cost":{"total":0.002}},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello"}}""",
            """{"type":"message_update","usage":{"input":10,"output":3,"totalTokens":13,"cost":{"total":0.003}},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":", world"}}""",
            """{"type":"message_update","usage":{"input":10,"output":3,"totalTokens":13,"cost":{"total":0.003}},"assistantMessageEvent":{"type":"text_end","contentIndex":0,"content":"Hello, world"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"Hello, world"}],"stopReason":"stop","usage":{"input":10,"output":3,"totalTokens":13,"cost":{"total":0.003}}}}""",
            """{"type":"turn_end","message":{},"toolResults":[]}""",
            """{"type":"agent_end","messages":[],"willRetry":false}""",
            """{"type":"agent_settled"}""",
        )

        assertEquals(1, state.users().size)
        assertEquals("say hi", state.users().first().text)

        assertEquals(1, state.assistants().size)
        assertEquals("Hello, world", state.assistants().first().text)
        assertFalse(state.assistants().first().isStreaming)
        assertNull(state.assistants().first().error)

        assertFalse(state.isStreaming)
        assertEquals("claude-sonnet-4-5", state.model?.id)
        assertEquals("anthropic", state.model?.provider)
        assertTrue(state.model!!.isUsable)
        assertTrue(state.model!!.supportsImages)
        assertEquals("01a08e58", state.sessionId)
        // `usage` now separates the live message's running total from the session
        // total, because pi's per-message reports are cumulative and adding each
        // one counted the same tokens repeatedly. `usage.total` is what the UI
        // shows: the finalized messages plus the live one.
        assertEquals(13L, state.usage.total.total)
        assertEquals("the message streamed and then ended, so it is counted once", 13L, state.usage.session.total)
        assertEquals(UsageInfo.EMPTY, state.usage.message)
    }

    /**
     * Captured verbatim from the device, where the UI showed `deepseek/` with an
     * empty model id even though pi returned one. Pinning the real payload here
     * is what localises that class of bug.
     */
    @Test
    fun `reads provider and model id from a real get_state payload`() {
        val state = fold(
            """{"id":"1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"deepseek-flash","name":"deepseek-flash","api":"openai-completions","baseUrl":"https://api.deepseek.com","provider":"deepseek","reasoning":true,"input":["text"],"cost":{"input":0.435,"output":0.87,"cacheRead":0.003625,"cacheWrite":0},"contextWindow":1000000,"maxTokens":384000,"compat":{"supportsStore":false},"thinkingLevelMap":{"high":"high"}},"thinkingLevel":"high","isStreaming":false,"isCompacting":false,"steeringMode":"one-at-a-time","followUpMode":"one-at-a-time","sessionFile":"/data/user/0/pi.kit.mob/files/pi-sessions/x.jsonl","sessionId":"01a08ef0","autoCompactionEnabled":true,"messageCount":0,"pendingMessageCount":0}}""",
        )
        assertEquals("deepseek", state.model?.provider)
        assertEquals("deepseek-flash", state.model?.id)
        assertTrue(state.model!!.isUsable)
    }

    @Test
    fun `reads provider and model id from a real get_state response`() {
        val state = fold(
            """{"id":"s1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"claude-sonnet-4-5","name":"Claude Sonnet 4.5","api":"anthropic-messages","provider":"anthropic","contextWindow":1000000,"input":["text","image"]},"thinkingLevel":"medium","sessionId":"x"}}""",
        )
        assertEquals("claude-sonnet-4-5", state.model?.id)
        assertEquals("anthropic", state.model?.provider)
    }

    /**
     * A stub model is reported when nothing is authenticated. Presence does not
     * mean usability, and the UI relies on this distinction.
     */
    @Test
    fun `detects the unauthenticated placeholder model`() {
        val state = fold(
            """{"id":"s1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"unknown","name":"unknown","api":"unknown","provider":"unknown","baseUrl":"","contextWindow":0,"input":[]},"thinkingLevel":"off","sessionId":"x"}}""",
        )
        assertNotNull(state.model)
        assertFalse(state.model!!.isUsable)
    }

    /**
     * The capability the image switch exists for, read the way pi reports it.
     *
     * A hand-typed model id that pi's catalog does not contain is resolved from a
     * *copy* of the provider's default model (`buildFallbackModel`), so the
     * `get_state` that comes back carries that model's `input` — `["text"]` for
     * DeepSeek — and an attached image is dropped before the request is built. The
     * switch's whole purpose is to turn this false into a true, so the two readings
     * are pinned here rather than left to a screenshot.
     */
    @Test
    fun `reads the image capability from the model pi reports`() {
        val textOnly = fold(
            """{"id":"s1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"deepseek-handtyped","name":"deepseek-handtyped","api":"openai-completions","provider":"deepseek","contextWindow":128000,"input":["text"]},"thinkingLevel":"medium","sessionId":"x"}}""",
        )
        val vision = fold(
            """{"id":"s1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"deepseek-handtyped","name":"deepseek-handtyped","api":"openai-completions","provider":"deepseek","contextWindow":128000,"input":["text","image"]},"thinkingLevel":"medium","sessionId":"x"}}""",
        )

        assertFalse("what pi reports for a model its catalog does not know", textOnly.model!!.supportsImages)
        assertTrue("what it reports once models.json declares the model", vision.model!!.supportsImages)
    }

    @Test
    fun `a model with no input list at all does not claim image support`() {
        val state = fold(
            """{"id":"s1","type":"response","command":"get_state","success":true,"data":{"model":{"id":"m","name":"m","provider":"deepseek","contextWindow":1000},"sessionId":"x"}}""",
        )
        assertFalse(state.model!!.supportsImages)
    }

    /**
     * The reply the thinking picker is built from.
     *
     * pi answers `get_available_thinking_levels` with the *model's* levels, derived
     * from its `thinkingLevelMap`, and the UI offers exactly those: a model with
     * four of pi's seven clamps a request it cannot honour to the next one above, so
     * a list of seven would be a menu whose rows do not do what they say. Captured
     * shape, from `deepseek-v4-pro`'s map `{minimal: null, low: null, medium: null,
     * high: "high", max: "max"}`.
     */
    @Test
    fun `reads the model's own thinking levels`() {
        val state = fold(
            """{"id":"1","type":"response","command":"get_available_thinking_levels","success":true,"data":{"levels":["off","high","max"]}}""",
        )
        assertEquals(listOf("off", "high", "max"), state.availableThinkingLevels)
    }

    @Test
    fun `an empty level list leaves the seven pi knows in place`() {
        // Not a state to publish: the picker would tick nothing, and the seven are
        // still a better answer than a blank menu.
        val state = fold(
            """{"id":"1","type":"response","command":"get_available_thinking_levels","success":true,"data":{"levels":[]}}""",
        )
        assertTrue(state.availableThinkingLevels.isEmpty())
    }

    @Test
    fun `agent_end does not mark the agent idle but agent_settled does`() {
        val afterStart = fold("""{"type":"agent_start"}""")
        assertTrue(afterStart.isStreaming)

        val afterEnd = ConversationReducer.reduce(
            afterStart,
            PiRecordParser.parse("""{"type":"agent_end","messages":[],"willRetry":true}"""),
        )
        assertTrue("agent_end can be followed by a retry, so it is not idle", afterEnd.isStreaming)

        val settled = ConversationReducer.reduce(afterEnd, PiRecordParser.parse("""{"type":"agent_settled"}"""))
        assertFalse(settled.isStreaming)
    }

    @Test
    fun `renders a tool call with its streamed arguments and result`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"toolu_01","toolName":"ls"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":"{\"path\""}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":":\".\"}"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","contentIndex":1,"toolCall":{"type":"toolCall","id":"toolu_01","name":"ls","arguments":{"path":"."}}}}""",
            """{"type":"tool_execution_start","toolCallId":"toolu_01","toolName":"ls","args":{"path":"."}}""",
            """{"type":"tool_execution_update","toolCallId":"toolu_01","toolName":"ls","partialResult":{"content":[{"type":"text","text":"AGENTS.md"}]}}""",
            """{"type":"tool_execution_end","toolCallId":"toolu_01","toolName":"ls","result":{"content":[{"type":"text","text":"AGENTS.md\nREADME.md\nsrc\ntest\n"}]},"isError":false}""",
        )

        assertEquals(1, state.tools().size)
        val tool = state.tools().first()
        assertEquals("ls", tool.name)
        assertEquals(ToolState.Succeeded, tool.state)
        assertTrue(tool.output.contains("README.md"))
        assertTrue(tool.argumentsJson.contains("\"path\""))
    }

    /** Partial results are cumulative, so they replace rather than append. */
    @Test
    fun `replaces tool output on each partial update`() {
        val state = fold(
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"t1","toolName":"bash"}}""",
            """{"type":"tool_execution_update","toolCallId":"t1","partialResult":{"content":[{"type":"text","text":"line 1"}]}}""",
            """{"type":"tool_execution_update","toolCallId":"t1","partialResult":{"content":[{"type":"text","text":"line 1\nline 2"}]}}""",
        )
        assertEquals("line 1\nline 2", state.tools().first().output)
    }

    @Test
    fun `marks a failed tool call`() {
        val state = fold(
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"t2","toolName":"bash"}}""",
            """{"type":"tool_execution_end","toolCallId":"t2","toolName":"bash","result":{"content":[{"type":"text","text":"command not found"}]},"isError":true}""",
        )
        assertEquals(ToolState.Failed, state.tools().first().state)
    }

    /** Post-acceptance failures arrive as an assistant message, not a response. */
    @Test
    fun `surfaces an error stop reason on the assistant message`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"403 forbidden"}}""",
        )
        assertEquals("403 forbidden", state.assistants().first().error)
        assertNull("an in-band error is not a command failure", state.lastError)
    }

    @Test
    fun `records a rejected command as a transport level error`() {
        val state = fold(
            """{"id":"n5","type":"response","command":"set_model","success":false,"error":"Model not found: nope/nope"}""",
        )
        assertEquals("Model not found: nope/nope", state.lastError)
    }

    @Test
    fun `asks the ui only for dialogs that expect an answer`() {
        val dialog = ConversationReducer.reduce(
            ConversationState(),
            PiRecordParser.parse(
                """{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Really?","message":"Confirm"}""",
            ),
        )
        assertNotNull(dialog.pendingDialog)
        assertEquals("confirm", dialog.pendingDialog!!.method)

        val notification = ConversationReducer.reduce(
            ConversationState(),
            PiRecordParser.parse(
                """{"type":"extension_ui_request","id":"n1","method":"notify","message":"hi"}""",
            ),
        )
        assertNull("fire-and-forget hints must not block the agent", notification.pendingDialog)
    }

    @Test
    fun `tracks the steering and follow up queues`() {
        val state = fold(
            """{"type":"queue_update","steering":["do this instead"],"followUp":["and then this"]}""",
        )
        assertEquals(listOf("do this instead"), state.steeringQueue)
        assertEquals(listOf("and then this"), state.followUpQueue)

        val cleared = ConversationReducer.reduce(
            state,
            PiRecordParser.parse("""{"type":"agent_settled"}"""),
        )
        assertTrue(cleared.steeringQueue.isEmpty())
        assertTrue(cleared.followUpQueue.isEmpty())
    }

    @Test
    fun `reports compaction and retry as notices`() {
        val state = fold(
            """{"type":"compaction_start","reason":"threshold"}""",
            """{"type":"compaction_end","reason":"threshold","aborted":false,"willRetry":false}""",
            """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":1000,"errorMessage":"rate limited"}""",
            """{"type":"auto_retry_end","success":true,"attempt":1}""",
        )
        val notices = state.items.filterIsInstance<ChatItem.Notice>()
        assertEquals(3, notices.size)
        assertTrue(notices.any { it.text.contains("rate limited") })
        assertFalse(state.isCompacting)
    }

    @Test
    fun `tracks the session name from the undocumented event`() {
        val state = fold("""{"type":"session_info_changed","name":"refactor auth"}""")
        assertEquals("refactor auth", state.sessionName)
    }

    @Test
    fun `ignores malformed records without disturbing state`() {
        val state = fold(
            """{"type":"agent_start"}""",
            """not json at all""",
            """{"type":"session_info_changed","name":"x"}""",
        )
        assertTrue(state.isStreaming)
        assertEquals("x", state.sessionName)
    }

    @Test
    fun `keeps thinking separate from assistant text`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"weighing options"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"the answer"}}""",
        )
        val assistant = state.assistants().first()
        assertEquals("the answer", assistant.text)
        assertEquals("weighing options", assistant.thinking)
    }

    @Test
    fun `counts attached images without keeping their payload`() {
        val state = fold(
            // What pi echoes back after a prompt with two images: the base64 is
            // part of the message, and the transcript must not carry it.
            """{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"what is this"},{"type":"image","data":"AAAAAAAA","mimeType":"image/png"},{"type":"image","data":"BBBBBBBB","mimeType":"image/jpeg"}],"timestamp":1}}""",
        )
        val user = state.users().single()
        assertEquals("what is this", user.text)
        assertEquals(2, user.imageCount)
        // The raw payload must not have leaked into any rendered field.
        assertFalse(user.text.contains("AAAA"))
    }

    @Test
    fun `shows an image-only prompt instead of dropping it`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"user","content":[{"type":"image","data":"AAAA","mimeType":"image/png"}],"timestamp":1}}""",
        )
        val user = state.users().single()
        assertEquals("", user.text)
        assertEquals(1, user.imageCount)
    }

    /**
     * The tool-call records below are the **field sets pi 0.85.1 actually sends**,
     * copied from a capture of a real turn (tools/capture-pi-rpc.mjs, audited by
     * tools/audit-capture.py).
     *
     * This is the test that was missing. The earlier fixture for the same
     * lifecycle included an `id` on `toolcall_delta`, which pi never sends — of 55
     * such records in one captured turn, **none** carried one. The reducer read
     * `delta.id` to find the card, so every streamed argument update was silently
     * dropped and the fixture agreed with the bug. Nothing failed; the arguments
     * simply appeared later than they should have.
     */
    @Test
    fun `streams tool arguments using the content index, as pi actually sends them`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"call_c4cf250d83d04fd1ad29048f","toolName":"bash"}}""",
            // No `id` here, exactly as on the wire.
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":"{\"command\": "}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":"\"echo pik"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":"it-capture-ok\"}"}}""",
        )

        val tool = state.tools().single()
        assertEquals("bash", tool.name)
        assertEquals("the id from toolcall_start keys the card", "call_c4cf250d83d04fd1ad29048f", tool.key)
        assertEquals(1, tool.contentIndex)
        assertEquals(
            "arguments must accumulate while the call is still being streamed",
            """{"command": "echo pikit-capture-ok"}""",
            tool.argumentsJson,
        )
    }

    @Test
    fun `a new message does not stream its arguments into the previous message's tool card`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"call_first","toolName":"bash"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":"first"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"toolUse"}}""",
            // The next message reuses content index 1 for a different call.
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"call_second","toolName":"read"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":1,"delta":"second"}}""",
        )

        val tools = state.tools()
        assertEquals(2, tools.size)
        assertEquals("first", tools[0].argumentsJson)
        assertEquals("call_first", tools[0].key)
        assertEquals("second", tools[1].argumentsJson)
        assertEquals("call_second", tools[1].key)
    }

    /**
     * A turn's structure, which is what the chat page folds on.
     *
     * The event order is the one captured from a real tool-using turn: a user
     * message, then six `turn_start`/`turn_end` pairs of which five end in a tool
     * call and the last produces the answer, then `agent_settled`.
     */
    @Test
    fun `records a turn's reply and its elapsed time`() {
        var clock = 1_000L
        fun tick(step: Long = 10L): Long = clock.also { clock += step }

        var state = ConversationState()
        fun apply(line: String) {
            state = ConversationReducer.reduce(state, PiRecordParser.parse(line), tick())
        }

        apply("""{"type":"agent_start"}""")
        apply("""{"type":"message_start","message":{"role":"user","content":[{"type":"text","text":"do it"}]}}""")
        apply("""{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""")
        apply("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"call_1","toolName":"bash"}}""")
        apply("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"call_1","name":"bash"}],"stopReason":"toolUse"}}""")
        apply("""{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""")
        apply("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"done"}}""")
        apply("""{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}],"stopReason":"stop"}}""")
        apply("""{"type":"agent_settled"}""")

        val turn = state.turns.single()
        assertTrue("the turn must be closed by agent_settled", turn.isComplete)
        assertEquals("the prompt that opened it", state.users().single().key, turn.promptKey)
        assertEquals("the reply that answered it", state.assistants().last().key, turn.finalReplyKey)
        assertTrue("elapsed time must be positive", turn.elapsed(clock) > 0)

        // The tool-only assistant message is not the turn's reply, and the rows a
        // finished turn hides are everything except the prompt and the answer.
        val hidden = state.foldedKeys()
        assertTrue("the tool card is folded away", state.tools().single().key in hidden)
        assertFalse("the reply stays visible", turn.finalReplyKey in hidden)
        assertFalse("the prompt stays visible", turn.promptKey in hidden)
    }

    /**
     * pi reports a message's usage cumulatively as it streams, then repeats it on
     * `message_end`. Counting each report would multiply the tokens by the number
     * of streamed chunks.
     */
    @Test
    fun `counts a streamed message's usage once, not once per chunk`() {
        val state = fold(
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending"}}""",
            """{"type":"message_update","usage":{"input":10,"output":1,"totalTokens":11},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"a"}}""",
            """{"type":"message_update","usage":{"input":10,"output":2,"totalTokens":12},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"b"}}""",
            """{"type":"message_update","usage":{"input":10,"output":3,"totalTokens":13},"assistantMessageEvent":{"type":"text_end","contentIndex":0,"content":"ab"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"ab"}],"stopReason":"stop","usage":{"input":10,"output":3,"totalTokens":13}}}""",
        )

        assertEquals("13, not 11+12+13", 13L, state.usage.total.total)
        assertEquals(UsageInfo.EMPTY, state.usage.message)
    }
}
