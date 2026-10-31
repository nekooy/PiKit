package pi.kit.mob.pi

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the conversation list needs to know about one saved session. */
data class SessionMeta(
    /** The name from the last `session_info` record, if the user ever set one. */
    val name: String? = null,
    /** Text of the first user message — pi's own fallback label for a session. */
    val firstMessage: String? = null,
    val messageCount: Int = 0,
    /**
     * The directory pi was started in, from the file's first record.
     *
     * pi writes `{"type":"session","version":1,"id":…,"cwd":"…"}` as line one, which is
     * the only place a saved conversation records the workspace it belonged to — a fact
     * the history page groups by and the agent needs in order to reopen the conversation
     * in the directory its tool calls were written for. Null for a file written by a pi
     * old enough not to record one, and for a session created by a launch that had not
     * answered `get_state` yet.
     */
    val workspace: String? = null,
)

/**
 * Reads titles and message counts out of pi's session JSONL.
 *
 * pi does not generate titles: a session stays unnamed until something names it,
 * and pi's own picker then shows the first user message. Reproducing that here
 * means the app's list reads the same as pi's, and it is why the parsing lives in
 * a pure function — the shapes being parsed are pi's, not this app's, so they are
 * the part most likely to be got wrong.
 *
 * Kept deliberately tolerant: the cheap substring checks exist because a session
 * with several hundred messages is megabytes, and deserializing every assistant
 * turn just to find the first user message would make listing slow for nothing.
 */
object SessionMetadata {

    private val SESSION_INFO = Regex("\"type\"\\s*:\\s*\"session_info\"")
    /**
     * The header record's own marker: `{"type":"session"` — line one of every file pi
     * writes, and not the `session_info` record beside it. Compared as text rather than
     * parsed first because this runs over every line of every session on every list.
     */
    private const val SESSION_HEADER_MARK = "{\"type\":\"session\""
    private val MESSAGE = Regex("\"type\"\\s*:\\s*\"message\"")
    private val USER_ROLE = Regex("\"role\"\\s*:\\s*\"user\"")
    private val ASSISTANT_ROLE = Regex("\"role\"\\s*:\\s*\"assistant\"")
    private val WHITESPACE = Regex("\\s+")

    /**
     * The directory [file] records, read from its first record alone.
     *
     * The launch path needs this and nothing else: `pikit_agent_session` remembers which
     * conversation was open, and a restore has to start pi in the directory *that*
     * conversation was working in rather than in the default workspace — a pi standing in
     * the wrong tree shows the old transcript while every tool call acts elsewhere. The
     * whole file is not read: it is the first line, and a conversation can be megabytes.
     */
    fun workspaceOf(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val first = file.bufferedReader().use { it.readLine() } ?: return@runCatching null
        if (!first.contains(SESSION_HEADER_MARK)) return@runCatching null
        PiRecordParser.json.parseToJsonElement(first)
            .jsonObject["cwd"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun read(lines: Sequence<String>): SessionMeta {
        var name: String? = null
        var firstMessage: String? = null
        var workspace: String? = null
        var count = 0

        for (line in lines) {
            when {
                // Only the first header counts: pi writes another record of this type
                // when a session is forked, and that one carries the *parent's*
                // directory, which is the wrong answer for the file it lands in.
                workspace == null && line.contains(SESSION_HEADER_MARK) -> {
                    workspace = runCatching {
                        PiRecordParser.json.parseToJsonElement(line)
                            .jsonObject["cwd"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }

                SESSION_INFO.containsMatchIn(line) -> {
                    val parsed = runCatching {
                        PiRecordParser.json.parseToJsonElement(line)
                            .jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.trim()
                    // The last naming record wins, and a blank name is how pi
                    // clears one — so `null` and `""` both mean "no name".
                    name = parsed?.takeIf { it.isNotEmpty() }
                }

                MESSAGE.containsMatchIn(line) -> {
                    count++
                    if (firstMessage == null && USER_ROLE.containsMatchIn(line)) {
                        firstMessage = runCatching {
                            val message = PiRecordParser.json.parseToJsonElement(line)
                                .jsonObject["message"]?.jsonObject
                            if (message?.get("role")?.jsonPrimitive?.contentOrNull == "user") {
                                messageText(message["content"])
                            } else {
                                null
                            }
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }
        return SessionMeta(
            name = name,
            firstMessage = firstMessage,
            messageCount = count,
            workspace = workspace,
        )
    }

    /**
     * Message content is a string in older files and a block array in current ones.
     *
     * Named for the message rather than for its author because the content search
     * reads both roles: a question and its answer are one transcript.
     */
    private fun messageText(content: JsonElement?): String? = when (content) {
        is JsonArray -> content.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                obj["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }.joinToString("\n")

        is JsonPrimitive -> content.contentOrNull
        else -> null
    }

    /**
     * The prose of a whole conversation: the user's messages and the assistant's
     * replies, in order, and nothing else.
     *
     * Tool results and reasoning are left out on purpose. A tool result is usually
     * a file listing or a command's output — searching it would answer "which
     * conversation mentioned `package.json`" with every conversation that ever ran
     * `ls` — and pi echoes images into the transcript base64-and-all, so a
     * screenshot-heavy session has multi-megabyte lines that must never reach an
     * in-memory index. Only `type == "text"` blocks are read, which is also what
     * keeps base64 out of the cache.
     *
     * The text is *not* lowercased here: the caller matches with
     * `indexOf(ignoreCase = true)`, and the snippet it shows has to be the user's
     * own capitalisation.
     *
     * The same cheap substring prefilter as [read] guards the parse: a session is
     * megabytes of records and most lines are not messages at all, so the JSON
     * parser is only reached for the lines that can be one. A line that carries
     * the role of neither speaker — a `toolResult` message, a `model_change` — is
     * skipped before it is parsed.
     */
    fun searchText(lines: Sequence<String>): String = buildString {
        for (line in lines) {
            if (!MESSAGE.containsMatchIn(line)) continue
            if (!USER_ROLE.containsMatchIn(line) && !ASSISTANT_ROLE.containsMatchIn(line)) continue
            val spoken = runCatching {
                // The message object itself, or nothing: returning early is what makes
                // the two reads below plain `get`s rather than a chain of safe calls —
                // a record whose `message` is absent has no prose either way.
                val message = PiRecordParser.json.parseToJsonElement(line)
                    .jsonObject["message"]?.jsonObject ?: return@runCatching null
                val role = message["role"]?.jsonPrimitive?.contentOrNull
                if (role == "user" || role == "assistant") {
                    messageText(message["content"])
                } else {
                    null
                }
            }.getOrNull()
            if (!spoken.isNullOrBlank()) {
                // One message per line: the separator is what stops the tail of
                // one reply running into the head of the next in a snippet.
                if (isNotEmpty()) append('\n')
                append(spoken)
            }
        }
    }

    /**
     * A one-line window around the first match, with ellipses, or null if there is
     * none. Bounded so a match in the middle of a chapter does not put a whole
     * paragraph in a list row.
     *
     * The window starts a third of its length before the match rather than at it,
     * so the row shows some of the words that led up to the match; running off
     * either end of the text is marked with an ellipsis rather than silently
     * presenting a fragment as the beginning or the end.
     *
     * Whitespace is collapsed *after* the window is cut, not before: a match is
     * found in the original text so that the offsets stay the caller's, and a
     * window that spans a paragraph break would otherwise put a newline in a row
     * that is drawn as one line.
     */
    fun snippet(text: String, needle: String, maxChars: Int = 120): String? {
        val at = text.indexOf(needle, ignoreCase = true)
        if (at < 0) return null

        val start = (at - maxChars / 3).coerceAtLeast(0)
        val end = (start + maxChars).coerceAtMost(text.length)
        val window = WHITESPACE.replace(text.substring(start, end), " ").trim()

        return buildString {
            if (start > 0) append('\u2026')
            append(window)
            if (end < text.length) append('\u2026')
        }
    }

    /**
     * Turns a first message into a label: one line, front-loaded, bounded.
     *
     * A prompt is usually a paragraph and the first line carries its subject, so
     * the rest is dropped rather than wrapped into a two-line list row.
     */
    fun asTitle(message: String, maxChars: Int = 80): String {
        val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.length <= maxChars) return firstLine
        return firstLine.take(maxChars - 1).trimEnd() + "\u2026"
    }
}
