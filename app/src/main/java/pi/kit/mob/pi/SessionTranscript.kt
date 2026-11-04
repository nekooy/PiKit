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
}
