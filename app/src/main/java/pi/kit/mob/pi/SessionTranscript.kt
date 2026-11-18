package pi.kit.mob.pi

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * A saved conversation's messages, read straight from pi's session file.
 *
 * ## Why this exists
 *
 * Opening a conversation used to mean *starting pi*: launch the child, wait for its
 * handshake, `switch_session`, `get_state`, then `get_messages` — two seconds of process
 * startup to display a transcript that was already sitting on disk in full. The reader's
 * report is the honest one: "看历史要毛的 pi 进程".
 *
 * pi's session file is JSONL and every message is one record —
 * `{"type":"message","timestamp":…,"message":{"role":…,"content":…}}` — which is the same
 * shape `get_messages` answers with. So the file can be turned into exactly the payload
 * [ConversationReducer.replaceWithMessages] already folds, with no process involved: the
 * agent is started when the reader *sends* something, and not before.
 *
 * The reader is deliberately forgiving: a record that cannot be parsed is skipped rather
 * than failing the whole restore, because one corrupt line in a long file must not hide
 * the conversation around it.
 */
object SessionTranscript {

    /** The largest file this will read in one go; past it, the tail is what matters. */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /**
     * The `{"messages": […]}` payload for [file], or null when it is not a session.
     *
     * The envelope is pi's own: `get_messages` answers an object with a `messages` array,
     * and [ConversationReducer.replaceWithMessages] reads exactly that.
     */
    fun payload(file: File): JsonObject? {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val messages = buildJsonArray {
            file.useLines { lines ->
                for (line in lines) {
                    if (!line.contains("\"message\"")) continue
                    val record: JsonObject = try {
                        PiRecordParser.json.parseToJsonElement(line).jsonObject
                    } catch (e: Exception) {
                        continue
                    }
                    val message = record["message"] as? JsonObject ?: continue
                    add(withTimestamp(message, record))
                }
            }
        }
        if (messages.isEmpty()) return null
        return buildJsonObject { put("messages", messages) }
    }

    /**
     * The message with the record's own timestamp filled in when the message lacks one.
     *
     * pi writes the clock on the record, not on the message, and the reducer reads the
     * message's own `timestamp` to date a turn — without this every restored message
     * would report "duration unknown".
     */
    private fun withTimestamp(message: JsonObject, record: JsonObject): JsonElement {
        if (message.containsKey("timestamp")) return message
        val at = (record["timestamp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: return message
        return buildJsonObject {
            message.forEach { (key, value) -> put(key, value) }
            put("timestamp", JsonPrimitive(at))
        }
    }

    /**
     * Every picture a saved conversation's prompts carried, and [action] for each.
     *
     * The transcript keeps a *cache name* per picture and no pixels, so a conversation
     * that was written before the app had a picture cache — or whose cache entry has
     * since been pruned — names pictures that are nowhere. The session file is where
     * they still are: pi persists the base64 of every attached picture inside the user
     * message that carried it, which is also the string the send path encoded, so the
     * name this app derives from it is the name the transcript is asking for.
     *
     * `action` returns whether to keep going; false stops the read, which is what makes
     * a scan that found everything it wanted cost nothing for the rest of a long file.
     * The base64 is handed over as it was stored — decoding is the caller's business,
     * and this stays free of `android.*` so it is testable on the JVM.
     */
    fun forEachPromptPicture(file: File, action: (base64: String, mimeType: String) -> Boolean) {
        if (!file.isFile) return
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    // Both prefilters before the parse: an image line in a session is
                    // megabytes, and most lines in the file are not messages at all.
                    if (!IMAGE_BLOCK.containsMatchIn(line)) continue
                    if (!USER_ROLE.containsMatchIn(line)) continue
                    val message = PiRecordParser.json.parseToJsonElement(line)
                        .jsonObject["message"] as? JsonObject ?: continue
                    if (message.str("role") != "user") continue
                    val content = message["content"] as? kotlinx.serialization.json.JsonArray ?: continue
                    for (block in content) {
                        val picture = block as? JsonObject ?: continue
                        if (picture.str("type") != "image") continue
                        val data = picture.str("data") ?: continue
                        if (!action(data, picture.str("mimeType").orEmpty())) return@useLines
                    }
                }
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** Cheap prefilters the two checks above match against. */
    private val IMAGE_BLOCK = Regex("\"type\"\\s*:\\s*\"image\"")
    private val USER_ROLE = Regex("\"role\"\\s*:\\s*\"user\"")
}
