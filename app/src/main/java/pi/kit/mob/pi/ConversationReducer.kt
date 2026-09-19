package pi.kit.mob.pi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// --------------------------------------------------------------------- model

enum class ToolState { Pending, Running, Succeeded, Failed }

enum class NoticeKind { Info, Warning, Error }

/** One row in the transcript. */
sealed interface ChatItem {
    val key: String

    /**
     * Wall-clock time the row was created, as milliseconds since the epoch.
     *
     * Used to report how long a turn took, and to let the UI group rows into the
     * turn that produced them. Filled from the record's own `timestamp` where pi
     * sends one, so a replay of a saved session reads the same as the live stream.
     */
    val createdAt: Long

    /**
     * A prompt from the user.
     *
     * [imageCount] is a count rather than the images themselves: pi echoes back
     * whatever was sent, base64 and all, and holding a megabyte of that per turn
     * only to render a thumbnail is what made scrolling stutter.
     */
    data class User(
        override val key: String,
        val text: String,
        val imageCount: Int = 0,
        override val createdAt: Long = 0L,
    ) : ChatItem

    data class Assistant(
        override val key: String,
        val text: String = "",
        val thinking: String = "",
        val isStreaming: Boolean = true,
        val error: String? = null,
        override val createdAt: Long = 0L,
    ) : ChatItem

    /**
     * A tool call.
     *
     * [contentIndex] is the position of the call inside its assistant message,
     * and it is what `toolcall_delta` records are correlated by: measured against
     * pi 0.85.1, 55 out of 55 `toolcall_delta` records carried a `contentIndex`
     * and **none** carried an `id`, so an implementation that reads `id` there
     * drops every streamed argument update without any error being raised.
     */
    data class Tool(
        override val key: String,
        val name: String,
        val argumentsJson: String = "",
        val state: ToolState = ToolState.Pending,
        val output: String = "",
        val contentIndex: Int = -1,
        override val createdAt: Long = 0L,
    ) : ChatItem

    data class Notice(
        override val key: String,
        val text: String,
        val kind: NoticeKind = NoticeKind.Info,
        override val createdAt: Long = 0L,
    ) : ChatItem
}

/**
 * One completed exchange: a user prompt, everything the agent did about it, and
 * the reply it settled on.
 *
 * The chat UI collapses a finished turn down to [finalReplyKey], because the
 * interesting part of "read this file, run that command, fix this" is the
 * sentence at the end. Nothing is discarded — the folded rows are still in
 * [ConversationState.items] and expand on a tap.
 */
data class ChatTurn(
    /** The prompt that opened the turn, or null when it began without one. */
    val promptKey: String?,
    /** The assistant reply the turn settled on. */
    val finalReplyKey: String?,
    val startedAt: Long,
    /** Null while the turn is still running. */
    val finishedAt: Long? = null,
) {
    val isComplete: Boolean get() = finishedAt != null

    /** Elapsed time in milliseconds, or the time so far while still running. */
    fun elapsed(now: Long): Long = (finishedAt ?: now) - startedAt
}

data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val contextWindow: Int,
    val supportsImages: Boolean,
) {
    /**
     * pi reports a placeholder model rather than `null` when nothing is
     * authenticated, so presence alone does not mean the agent is usable.
     */
    val isUsable: Boolean get() = provider != "unknown" && contextWindow > 0
}

/**
 * Token and cost totals for the session, and for the message being streamed.
 *
 * Two numbers, because pi's reports mean two different things and conflating them
 * double-counts:
 *
 *  - `message_update.usage` is **cumulative for the message being streamed** —
 *    measured, one message reported `totalTokens` 0 → 2669 while its output grew
 *    to 44. Adding each report into a session total counted the same tokens once
 *    per streamed chunk.
 *  - Each finalized assistant message reports its own usage, and a tool-using turn
 *    is several messages, so the session total is the *sum over messages*.
 *
 * [session], [message], and [previous] hold those separately. [total] is what the
 * UI shows: the finished messages plus whatever the live one has used so far.
 */
data class UsageTotals(
    /** Summed usage of every finalized assistant message. */
    val session: UsageInfo = UsageInfo.EMPTY,
    /** The live message's usage, replaced on each update. */
    val message: UsageInfo = UsageInfo.EMPTY,
    /**
     * The **most recent** finalized message's usage, not a sum.
     *
     * This is what the context window is measured against: each request carries
     * the whole conversation so far, so the newest message's total *is* how full
     * the window is. Summing across messages would report the session's cost
     * instead, which grows without bound and would show a small conversation as
     * 100% full after a dozen tool calls.
     */
    val last: UsageInfo = UsageInfo.EMPTY,
    /**
     * What this session had used before the current one, when the live session
     * was resumed rather than started. Not folded into [session] because pi
     * reports it separately and it is already a total.
     */
    val previous: UsageInfo = UsageInfo.EMPTY,
) {
    /** What the header should show right now. */
    val total: UsageInfo get() = session + message + previous

    /**
     * How full the context window is: the newest message while one is streaming,
     * otherwise the newest finalized one.
     */
    val context: UsageInfo get() = if (message.total > 0) message else last

    val isEmpty: Boolean get() = session == UsageInfo.EMPTY && message == UsageInfo.EMPTY
}

/**
 * Token and cost totals for one assistant message.
 *
 * [reasoning] is reported separately by OpenAI-compatible providers and is a
 * subset of [output]; it is kept because it is the only signal of how much of a
 * "thinking" level was actually spent.
 */
data class UsageInfo(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val total: Long = 0,
    val costUsd: Double = 0.0,
) {
    operator fun plus(other: UsageInfo): UsageInfo = UsageInfo(
        input = input + other.input,
        output = output + other.output,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite = cacheWrite + other.cacheWrite,
        reasoning = reasoning + other.reasoning,
        total = total + other.total,
        costUsd = costUsd + other.costUsd,
    )

    companion object {
        val EMPTY = UsageInfo()
    }
}

data class ConversationState(
    val items: List<ChatItem> = emptyList(),
    val isStreaming: Boolean = false,
    val isCompacting: Boolean = false,
    val steeringQueue: List<String> = emptyList(),
    val followUpQueue: List<String> = emptyList(),
    val sessionId: String? = null,
    val sessionName: String? = null,
    /** Absolute path of the live session's JSONL file, as pi reports it. */
    val sessionFile: String? = null,
    val model: ModelInfo? = null,
    val thinkingLevel: String? = null,
    /**
     * The thinking levels the current model supports, as pi reports them.
     *
     * Empty until a running agent has answered `get_available_thinking_levels`.
     * pi derives the list from the model's own `thinkingLevelMap`
     * (`getSupportedThinkingLevels`, `pi-ai/dist/models.js`), so a reasoning model
     * that maps only some levels — DeepSeek's `{low, high, max}` — supports four
     * where pi knows seven. The UI offers exactly these, which is what keeps a
     * tapped level and the level pi ends up on the same word.
     */
    val availableThinkingLevels: List<String> = emptyList(),
    val usage: UsageTotals = UsageTotals(),
    /** Transient status shown above the composer, e.g. "Working". */
    val statusMessage: String? = null,
    /** A dialog pi is waiting on; the UI must answer it. */
    val pendingDialog: PiRecord.UiRequest? = null,
    /** Most recent transport- or command-level failure. */
    val lastError: String? = null,
    /**
     * Monotonic counter that gives each generated transcript row a stable key.
     * Part of the state so that reduction stays a pure function.
     */
    val seq: Int = 0,
    /** Key of the assistant row currently being streamed, if any. */
    val currentAssistantKey: String? = null,
    /**
     * Content index to tool row key, for the assistant message being streamed.
     *
     * `toolcall_start` carries both a `contentIndex` and an `id`, but the
     * `toolcall_delta` records that follow carry only the index. Remembering the
     * mapping here is what lets a streamed argument reach the right card; see
     * [ChatItem.Tool.contentIndex].
     */
    val toolKeysByIndex: Map<Int, String> = emptyMap(),
    /** Turns in order: the last one may still be running. */
    val turns: List<ChatTurn> = emptyList(),
) {

    /**
     * Rows a finished turn hides behind its final reply.
     *
     * A turn's own rows are everything after its prompt and before the next
     * turn's prompt, minus the reply it settled on. Rows that belong to no turn —
     * notices from a direct `!command`, for instance — are never hidden.
     *
     * A turn with **no** final reply hides nothing. That is not a corner case: it
     * is what an aborted run looks like, and what a tool that stopped everything
     * looks like. Folding there would hide the only output there was and leave the
     * user looking at their own prompt, so the turn is left open — it still counts
     * as finished for the summary row.
     */
    fun foldedKeys(): Set<String> {
        // Bound once, not per item: a long session has hundreds of items per turn,
        // and resolving a turn with a search per item ran on every recomposition.
        val bounds: Map<String, ChatTurn> = turns
            .mapNotNull { turn -> turn.promptKey?.let { it to turn } }
            .toMap()

        val hidden = HashSet<String>()
        var open: ChatTurn? = null
        for (item in items) {
            // A prompt ends the previous turn and opens the next.
            val next = bounds[item.key]
            if (next != null) {
                open = next
                continue
            }
            val turn = open ?: continue
            if (!turn.isComplete) continue
            if (turn.finalReplyKey == null || turn.finalReplyKey == item.key) continue
            hidden += item.key
        }
        return hidden
    }
}

// ------------------------------------------------------------------ reducer

/**
 * Folds pi's event stream into the transcript the chat UI renders.
 *
 * Kept pure and Android-free so the whole conversation state machine can be
 * driven by unit tests using captured wire traffic. Notable rules, all taken
 * from observed pi behaviour:
 *
 *  - `agent_settled` — not `agent_end` — means idle. `agent_end` can be
 *    followed by an auto-retry, a compaction retry or a queued continuation.
 *  - `tool_execution_update.partialResult` is *cumulative*, so it replaces the
 *    displayed output instead of appending to it.
 *  - A `prompt` response with `success: true` only means "accepted"; real
 *    failures arrive later as an assistant message with `stopReason: "error"`.
 *  - `success` responses for `get_state` carry session metadata worth folding
 *    into the UI, so responses are handled too, not just events.
 */
object ConversationReducer {

    /**
     * Folds one record.
     *
     * [now] is a parameter rather than a call to `System.currentTimeMillis` inside
     * the reducer, so the whole state machine — including the elapsed time it
     * reports for a turn — stays a pure function of its inputs and the tests can
     * assert exact durations.
     */
    fun reduce(
        state: ConversationState,
        record: PiRecord,
        now: Long = System.currentTimeMillis(),
    ): ConversationState = when (record) {
        is PiRecord.Response -> reduceResponse(state, record)
        is PiRecord.Event -> reduceEvent(state, record, now)
        is PiRecord.UiRequest -> reduceUiRequest(state, record)
        is PiRecord.Malformed -> state
    }

    fun reduceAll(
        state: ConversationState,
        records: Iterable<PiRecord>,
        now: Long = System.currentTimeMillis(),
    ): ConversationState = records.fold(state) { acc, record -> reduce(acc, record, now) }

    /**
     * Replaces the transcript with a session's stored history.
     *
     * pi persists every session as JSONL, and `get_messages` returns it. Without
     * this, switching to an earlier session would leave the screen empty even
     * though the conversation still exists on disk.
     *
     * The **turns** have to be rebuilt here too, and that is not a detail: the
     * chat page folds a finished turn by looking it up in [ConversationState.turns],
     * so a transcript restored without them renders completely unfolded — every
     * tool card of every past turn back on screen. That was the bug: switching
     * sessions appeared to lose the folding, because the history path never built
     * the turn list the live path builds from `agent_start` / `agent_settled`.
     *
     * pi stores the messages but not the events that bracketed them, so the turns
     * are re-derived from the same rule the live reducer uses:
     *
     *  - a user message opens a turn,
     *  - the last assistant message that carried **text** is its answer (a
     *    tool-call-only message is not),
     *  - the next user message closes it.
     *
     * A turn is closed at the timestamp of its own last message rather than at the
     * next prompt's, because that prompt can be hours later and would report the
     * first turn as having taken all of it.
     */
    fun replaceWithMessages(
        state: ConversationState,
        data: kotlinx.serialization.json.JsonElement?,
    ): ConversationState {
        val messages = (data as? JsonObject)?.get("messages") as? JsonArray ?: return state

        val items = ArrayList<ChatItem>()
        val turns = ArrayList<ChatTurn>()
        val toolIndices = HashMap<String, Int>()
        var seq = 0

        // Summed over the stored messages, exactly as the live path sums them over
        // finalized ones. Without this a reopened conversation showed no token
        // totals at all while a live one showed them, which reads as the counter
        // having been lost rather than as never having been loaded.
        var usage = UsageInfo.EMPTY
        // Kept apart from the sum: this is the context occupancy, and the last
        // message is the only one that measures it.
        var lastUsage = UsageInfo.EMPTY

        // The turn being rebuilt. `open` is separate from `promptKey != null` so
        // that leading assistant messages — a greeting, a resumed session — stay
        // attributed to no turn and are therefore never folded away.
        var open = false
        var promptKey: String? = null
        var finalReplyKey: String? = null
        var startedAt = 0L
        var lastAt = 0L

        fun closeTurn() {
            if (!open) return
            turns += ChatTurn(
                promptKey = promptKey,
                finalReplyKey = finalReplyKey,
                startedAt = startedAt,
                finishedAt = lastAt,
            )
            open = false
            promptKey = null
            finalReplyKey = null
            startedAt = 0L
            lastAt = 0L
        }

        for (element in messages) {
            val message = element as? JsonObject ?: continue
            // Every stored message carries its own timestamp. Kept as 0 when it is
            // missing, which the UI reads as "duration unknown" rather than
            // inventing one.
            val at = message.long("timestamp").takeIf { it > 0 } ?: 0L

            when (message.str("role")) {
                "user" -> {
                    val text = extractContentText(message["content"])
                    val images = countImages(message["content"])
                    if (text.isEmpty() && images == 0) continue
                    val key = "history-user-${seq++}"
                    closeTurn()
                    items += ChatItem.User(key, text, images, at)
                    open = true
                    promptKey = key
                    startedAt = at
                    lastAt = at
                }

                "assistant" -> {
                    val text = extractContentText(message["content"])
                    val thinking = extractThinkingText(message["content"])
                    // Per message on the wire, so they add up across a tool-using
                    // turn rather than replacing one another.
                    message.obj("usage")?.let {
                        val parsed = parseUsage(it)
                        usage += parsed
                        if (parsed.total > 0) lastUsage = parsed
                    }
                    if (text.isNotEmpty() || thinking.isNotEmpty()) {
                        val key = "history-assistant-${seq++}"
                        items += ChatItem.Assistant(
                            key = key,
                            text = text,
                            thinking = thinking,
                            isStreaming = false,
                            error = message.str("errorMessage"),
                            createdAt = at,
                        )
                        // The answer is the last message with text, so a later
                        // one replaces an earlier one.
                        if (open && text.isNotBlank()) finalReplyKey = key
                    }
                    // Remember where each tool call landed so its result can be
                    // attached when the matching toolResult message arrives.
                    (message["content"] as? JsonArray)?.forEach { block ->
                        val call = block as? JsonObject ?: return@forEach
                        if (call.str("type") != "toolCall") return@forEach
                        val callId = call.str("id") ?: return@forEach
                        toolIndices[callId] = items.size
                        items += ChatItem.Tool(
                            key = callId,
                            name = call.str("name").orEmpty(),
                            argumentsJson = call["arguments"]?.toString().orEmpty(),
                            createdAt = at,
                        )
                    }
                }

                "toolResult" -> {
                    val callId = message.str("toolCallId") ?: continue
                    val index = toolIndices[callId] ?: continue
                    val existing = items.getOrNull(index) as? ChatItem.Tool ?: continue
                    items[index] = existing.copy(
                        output = extractResultText(message),
                        state = if (message.bool("isError") == true) {
                            ToolState.Failed
                        } else {
                            ToolState.Succeeded
                        },
                    )
                }
            }

            // Advanced *after* the branch, not before it: a user message closes the
            // previous turn at its own last message, and moving `lastAt` up to the
            // new prompt first would bill the whole idle gap between two prompts to
            // the turn that just ended.
            if (open && at > lastAt) lastAt = at
        }

        closeTurn()

        return state.copy(
            items = items,
            seq = seq,
            turns = turns,
            usage = state.usage.copy(
                session = usage,
                message = UsageInfo.EMPTY,
                last = lastUsage,
            ),
            currentAssistantKey = null,
            isStreaming = false,
            isCompacting = false,
            statusMessage = null,
            lastError = null,
            pendingDialog = null,
            steeringQueue = emptyList(),
            followUpQueue = emptyList(),
        )
    }

    // ------------------------------------------------------------ responses

    private fun reduceResponse(state: ConversationState, response: PiRecord.Response): ConversationState {
        if (!response.success) {
            val message = response.error ?: "pi rejected '${response.command}'"
            return state.copy(lastError = message)
        }
        return when (response.command) {
            "get_state" -> response.data?.let { applySessionState(state, it.jsonObject) } ?: state

            // pi answers this with the *model's* levels, not pi's seven. An empty
            // list is not a state to publish: it would tick nothing in the picker,
            // where the seven are still a better answer than a blank row.
            "get_available_thinking_levels" -> {
                val levels = (response.data?.jsonObject?.get("levels") as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .filter { it.isNotBlank() }
                if (levels.isEmpty()) state else state.copy(availableThinkingLevels = levels)
            }

            else -> state
        }
    }

    private fun applySessionState(state: ConversationState, data: JsonObject): ConversationState {
        val model = data.obj("model")?.let { m ->
            ModelInfo(
                id = m.str("id").orEmpty(),
                name = m.str("name").orEmpty(),
                provider = m.str("provider").orEmpty(),
                contextWindow = m.int("contextWindow") ?: 0,
                supportsImages = (m["input"] as? JsonArray)
                    ?.any { it.jsonPrimitive.contentOrNull == "image" } == true,
            )
        }
        return state.copy(
            model = model ?: state.model,
            sessionId = data.str("sessionId") ?: state.sessionId,
            sessionName = data.str("sessionName") ?: state.sessionName,
            sessionFile = data.str("sessionFile") ?: state.sessionFile,
            thinkingLevel = data.str("thinkingLevel") ?: state.thinkingLevel,
            isStreaming = data.bool("isStreaming") ?: state.isStreaming,
            isCompacting = data.bool("isCompacting") ?: state.isCompacting,
        )
    }

    // --------------------------------------------------------------- events

    private fun reduceEvent(
        state: ConversationState,
        event: PiRecord.Event,
        now: Long,
    ): ConversationState {
        val raw = event.raw
        // pi stamps most records with their own time. Preferring it means a
        // session reloaded from disk reports the durations it really had rather
        // than the time the app spent parsing it.
        val at = raw.long("timestamp").takeIf { it > 0 } ?: now
        return when (event.type) {
            "agent_start" -> state.copy(
                isStreaming = true,
                statusMessage = "Working",
                lastError = null,
                turns = state.turns.openAt(at),
            )

            // The only reliable idle signal: retries and queued continuations
            // can follow agent_end.
            "agent_settled" -> state.copy(
                isStreaming = false,
                isCompacting = false,
                statusMessage = null,
                steeringQueue = emptyList(),
                followUpQueue = emptyList(),
                turns = state.turns.closeAt(at, state.items),            )

            "message_start" -> startMessage(state, raw.obj("message"), at)
            "message_update" -> applyMessageUpdate(state, raw, at)
            "message_end" -> endMessage(state, raw.obj("message"), at)

            "tool_execution_start" -> state.updateTool(raw.str("toolCallId")) { tool ->
                tool.copy(
                    name = raw.str("toolName") ?: tool.name,
                    state = ToolState.Running,
                    argumentsJson = raw["args"]?.toString() ?: tool.argumentsJson,
                )
            }

            "tool_execution_update" -> state.updateTool(raw.str("toolCallId")) { tool ->
                // Cumulative: replace rather than append.
                tool.copy(output = extractResultText(raw["partialResult"]).ifEmpty { tool.output })
            }

            "tool_execution_end" -> state.updateTool(raw.str("toolCallId")) { tool ->
                tool.copy(
                    state = if (raw.bool("isError") == true) ToolState.Failed else ToolState.Succeeded,
                    output = extractResultText(raw["result"]).ifEmpty { tool.output },
                )
            }

            "queue_update" -> state.copy(
                steeringQueue = stringList(raw["steering"]),
                followUpQueue = stringList(raw["followUp"]),
            )

            "compaction_start" -> state.copy(isCompacting = true, statusMessage = "Compacting context")

            "compaction_end" -> {
                val reason = raw.str("reason").orEmpty()
                val error = raw.str("errorMessage")
                val willRetry = raw.bool("willRetry") == true
                val notice = when {
                    error != null -> "Compaction failed: $error"
                    willRetry -> "Compaction will retry"
                    else -> "Compacted context ($reason)"
                }
                state.copy(isCompacting = willRetry, statusMessage = null)
                    .addNotice(notice, if (error != null) NoticeKind.Warning else NoticeKind.Info)
            }

            "auto_retry_start" -> state.addNotice(
                "Retrying (attempt ${raw.int("attempt") ?: 1}/${raw.int("maxAttempts") ?: 1}) after: " +
                    (raw.str("errorMessage") ?: "transient error"),
                NoticeKind.Warning,
            )

            "auto_retry_end" -> state.addNotice(
                if (raw.bool("success") == true) "Retry succeeded" else "Retry failed",
                if (raw.bool("success") == true) NoticeKind.Info else NoticeKind.Error,
            )

            "extension_error" -> state.addNotice(
                "Extension error in ${raw.str("event").orEmpty()}: ${raw.str("error").orEmpty()}",
                NoticeKind.Error,
            )

            "session_info_changed" -> state.copy(sessionName = raw.str("name"))

            "thinking_level_changed" -> state.copy(thinkingLevel = raw.str("level"))

            else -> state
        }
    }

    private fun startMessage(
        state: ConversationState,
        message: JsonObject?,
        at: Long,
    ): ConversationState {
        if (message == null) return state
        return when (message.str("role")) {
            "user" -> {
                val text = extractContentText(message["content"])
                val images = countImages(message["content"])
                if (text.isEmpty() && images == 0) {
                    state
                } else {
                    val key = "user-${state.seq}"
                    state.append(ChatItem.User(key, text, images, at))
                        .copy(
                            seq = state.seq + 1,
                            // A user message is what opens a turn. Doing it here
                            // rather than on `prompt` means a turn reloaded from a
                            // session file is bracketed the same way as a live one.
                            turns = state.turns.openTurn(key, at),
                        )
                }
            }

            "assistant" -> {
                val key = "assistant-${state.seq}"
                state.append(ChatItem.Assistant(key = key, createdAt = at))
                    .copy(seq = state.seq + 1, currentAssistantKey = key)
            }

            // Tool results are already shown on the tool card; a second bubble
            // would duplicate them.
            else -> state
        }
    }

    private fun applyMessageUpdate(
        state: ConversationState,
        raw: JsonObject,
        at: Long,
    ): ConversationState {
        val usage = raw.obj("usage")?.let(::parseUsage)
        // Replaces rather than adds: this is the live message's running total, so
        // the newest report is the whole of what it has used so far.
        val withUsage = if (usage != null) state.copy(usage = state.usage.copy(message = usage)) else state
        val delta = raw.obj("assistantMessageEvent") ?: return withUsage

        // Text and thinking belong to the open assistant message, but tool calls
        // are tracked by their own id and must be recorded even if that message
        // has already been closed, so only the former require a key.
        val key = withUsage.currentAssistantKey
        val contentIndex = delta.int("contentIndex") ?: 0

        return when (delta.str("type")) {
            "text_start" -> withUsage
            "text_delta" -> withUsage.updateAssistant(key) {
                it.copy(text = it.text + delta.str("delta").orEmpty())
            }
            // text_end carries the authoritative text for the block.
            "text_end" -> withUsage.updateAssistant(key) {
                val authoritative = delta.str("content")
                if (authoritative.isNullOrEmpty()) it else it.copy(text = authoritative)
            }
            "thinking_start" -> withUsage
            "thinking_delta" -> withUsage.updateAssistant(key) {
                it.copy(thinking = it.thinking + delta.str("delta").orEmpty())
            }
            "thinking_end" -> withUsage.updateAssistant(key) {
                val authoritative = delta.str("content")
                if (authoritative.isNullOrEmpty()) it else it.copy(thinking = authoritative)
            }
            "toolcall_start" -> {
                val id = delta.str("id") ?: "tool-${state.seq}"
                if (withUsage.items.any { it.key == id }) {
                    withUsage
                } else {
                    withUsage
                        .append(
                            ChatItem.Tool(
                                key = id,
                                name = delta.str("toolName").orEmpty(),
                                contentIndex = contentIndex,
                                createdAt = at,
                            ),
                        )
                        .copy(
                            seq = withUsage.seq + 1,
                            // Remember which row this content index belongs to.
                            // Measured: `toolcall_delta` records carry a
                            // `contentIndex` and never an `id`, so without this
                            // every streamed argument is dropped.
                            toolKeysByIndex = withUsage.toolKeysByIndex + (contentIndex to id),
                        )
                }
            }
            // Correlated by content index, not by id: see the note on
            // `toolcall_start` above. This is the fix for arguments that used to
            // appear only once the tool had already started running.
            "toolcall_delta" -> withUsage.updateToolAt(contentIndex) { tool ->
                tool.copy(argumentsJson = tool.argumentsJson + delta.str("delta").orEmpty())
            }
            "toolcall_end" -> withUsage.updateToolAt(contentIndex) { tool ->
                val call = delta.obj("toolCall")
                tool.copy(
                    name = call?.str("name") ?: tool.name,
                    argumentsJson = call?.get("arguments")?.toString() ?: tool.argumentsJson,
                )
            }
            else -> withUsage
        }
    }

    private fun endMessage(
        state: ConversationState,
        message: JsonObject?,
        at: Long,
    ): ConversationState {
        if (message == null) return state
        if (message.str("role") != "assistant") return state

        val key = state.currentAssistantKey ?: return state
        val stopReason = message.str("stopReason")
        val errorMessage = message.str("errorMessage")
        // Reported per message, so the totals grow across a tool-using turn
        // instead of jumping. It also arrives more often than the prompt and
        // settle events, which is what makes the header count live.
        val usage = message.obj("usage")?.let(::parseUsage)

        val next = state
            .updateAssistant(key) {
                it.copy(
                    isStreaming = false,
                    error = errorMessage ?: if (stopReason == "aborted") "Aborted" else null,
                    text = it.text.ifEmpty { extractContentText(message["content"]) },
                    thinking = it.thinking.ifEmpty { extractThinkingText(message["content"]) },
                )
            }
            // Folded in once, at the end of the message: during the stream the
            // same tokens arrive repeatedly, so only the finalized figure belongs
            // in the session total.
            .let {
                if (usage != null) {
                    it.copy(
                        usage = it.usage.copy(
                            session = it.usage.session + usage,
                            message = UsageInfo.EMPTY,
                            last = usage,
                        ),
                    )
                } else {
                    it
                }
            }
            .copy(
                currentAssistantKey = null,
                // A new assistant message may use the same content indices, so the
                // mapping must not survive into it or a later tool call would
                // stream its arguments into the previous message's card.
                toolKeysByIndex = emptyMap(),
            )

        // The turn's reply is whichever assistant message carried text; a
        // tool-call-only message is not an answer. Recorded here as well as at
        // settle time so the fold is correct even if `agent_settled` never
        // arrives — a killed process, for instance.
        val replyText = next.items.firstOrNull { it.key == key } as? ChatItem.Assistant
        if (replyText == null || replyText.text.isBlank()) return next
        val open = next.turns.lastOrNull()?.takeIf { !it.isComplete } ?: return next
        return next.copy(turns = next.turns.dropLast(1) + open.copy(finalReplyKey = key))
    }

    private fun reduceUiRequest(state: ConversationState, request: PiRecord.UiRequest): ConversationState =
        if (request.expectsReply) state.copy(pendingDialog = request) else state

    // -------------------------------------------------------------- helpers

    private fun parseUsage(usage: JsonObject): UsageInfo = UsageInfo(
        input = usage.long("input"),
        output = usage.long("output"),
        cacheRead = usage.long("cacheRead"),
        cacheWrite = usage.long("cacheWrite"),
        reasoning = usage.long("reasoning"),
        total = usage.long("totalTokens"),
        costUsd = usage.obj("cost")?.let { it["total"]?.jsonPrimitive?.doubleOrNull } ?: 0.0,
    )

    private fun ConversationState.append(item: ChatItem): ConversationState =
        copy(items = items + item)

    private fun ConversationState.updateAssistant(
        key: String?,
        transform: (ChatItem.Assistant) -> ChatItem.Assistant,
    ): ConversationState {
        if (key == null) return this
        return copy(
            items = items.map { if (it.key == key && it is ChatItem.Assistant) transform(it) else it },
        )
    }

    private fun ConversationState.updateTool(
        toolCallId: String?,
        transform: (ChatItem.Tool) -> ChatItem.Tool,
    ): ConversationState {
        if (toolCallId == null) return this
        return copy(
            items = items.map { if (it.key == toolCallId && it is ChatItem.Tool) transform(it) else it },
        )
    }

    /**
     * Finds a tool card by the content index of its call inside the current
     * assistant message.
     *
     * The mapping is recorded on `toolcall_start`, because that is the only
     * record in the tool-call sequence that carries both a `contentIndex` and an
     * `id`. Measured against pi 0.85.1: all 55 `toolcall_delta` records in one
     * tool-using turn carried a `contentIndex` and none carried an `id`.
     */
    private fun ConversationState.updateToolAt(
        contentIndex: Int,
        transform: (ChatItem.Tool) -> ChatItem.Tool,
    ): ConversationState {
        val id = toolKeysByIndex[contentIndex] ?: return this
        return updateTool(id, transform)
    }

    private fun ConversationState.updateToolById(
        delta: JsonObject,
        transform: (ChatItem.Tool) -> ChatItem.Tool,
    ): ConversationState {
        val id = delta.str("id") ?: return this
        val index = items.indexOfFirst { it.key == id }
        if (index < 0) return this
        return copy(items = items.toMutableList().also { list ->
            val tool = list[index] as ChatItem.Tool
            list[index] = transform(tool)
        })
    }

    private fun ConversationState.addNotice(text: String, kind: NoticeKind): ConversationState =
        append(ChatItem.Notice(key = "notice-${seq}-${items.size}", text = text, kind = kind))
            .copy(seq = seq + 1)

    /**
     * Appends an out-of-band notice, e.g. the result of a direct bash command
     * issued from the composer rather than by the model.
     */
    fun notice(
        state: ConversationState,
        text: String,
        kind: NoticeKind = NoticeKind.Info,
    ): ConversationState = state.addNotice(text, kind)

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L

    /** Message content is an array of blocks on the wire, even for plain text. */
    private fun extractContentText(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
        is JsonArray -> content.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "text") obj.str("text") else null
        }.joinToString("\n")

        is JsonPrimitive -> content.contentOrNull.orEmpty()
        else -> ""
    }

    private fun extractThinkingText(content: kotlinx.serialization.json.JsonElement?): String =
        (content as? JsonArray)?.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") == "thinking") obj.str("thinking") else null
        }?.joinToString("\n").orEmpty()

    /**
     * How many image blocks a message carries.
     *
     * The payload is not read: `pi` echoes the base64 back on `message_start`,
     * and copying that into the transcript would mean megabytes of strings kept
     * alive and re-parsed for every frame of a scroll.
     */
    private fun countImages(content: kotlinx.serialization.json.JsonElement?): Int =
        (content as? JsonArray)?.count { block ->
            (block as? JsonObject)?.str("type") == "image"
        } ?: 0

    /** Pulls the text out of an `AgentToolResult` / `partialResult` envelope. */
    private fun extractResultText(element: kotlinx.serialization.json.JsonElement?): String {
        val obj = element as? JsonObject ?: return ""
        return extractContentText(obj["content"])
    }

    private fun stringList(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

// ------------------------------------------------------------------- turns

/**
 * Turn bookkeeping, kept out of the event handler so the rules are readable.
 *
 * Two different records open a turn — `agent_start` and the user's
 * `message_start` — and either can arrive first, so opening twice has to be
 * harmless. `agent_settled` is what closes one, for the reason recorded on the
 * reducer: `agent_end` can be followed by a retry.
 */
private fun List<ChatTurn>.openAt(at: Long): List<ChatTurn> {
    val open = lastOrNull()?.takeIf { !it.isComplete }
    if (open != null) return this
    return this + ChatTurn(promptKey = null, finalReplyKey = null, startedAt = at)
}

private fun List<ChatTurn>.openTurn(promptKey: String, at: Long): List<ChatTurn> {
    val open = lastOrNull()?.takeIf { !it.isComplete }
    return if (open == null) {
        // `agent_start` can arrive after the user message when a prompt is sent
        // from a cold process, and before it on a retry. Either order ends with
        // one open turn carrying both facts.
        this + ChatTurn(promptKey = promptKey, finalReplyKey = null, startedAt = at)
    } else {
        dropLast(1) + open.copy(
            promptKey = open.promptKey ?: promptKey,
            startedAt = minOf(open.startedAt, at),
        )
    }
}

/**
 * Closes the open turn, deciding which reply it settled on.
 *
 * The last assistant message that produced text inside the turn is the answer; a
 * message that carried only a tool call is not one. A turn that ended without any
 * text — an aborted run, or a tool that failed and stopped everything — keeps a
 * null reply, and the UI then folds nothing rather than hiding the only content
 * there was.
 */
private fun List<ChatTurn>.closeAt(at: Long, items: List<ChatItem>): List<ChatTurn> {
    val open = lastOrNull()?.takeIf { !it.isComplete } ?: return this
    val promptIndex = open.promptKey?.let { key -> items.indexOfFirst { it.key == key } } ?: -1
    val withinTurn = if (promptIndex >= 0) items.drop(promptIndex + 1) else items
    val finalKey = withinTurn.asReversed()
        .firstOrNull { it is ChatItem.Assistant && it.text.isNotBlank() }
        ?.key
        ?: open.finalReplyKey
    return dropLast(1) + open.copy(finalReplyKey = finalKey, finishedAt = at)
}
