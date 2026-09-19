package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One record received from pi's stdout.
 *
 * The wire is a union of three families sharing one stream: command responses,
 * extension UI requests, and session events. Only the envelope is modelled
 * strictly; event payloads stay as [JsonObject] so that a pi upgrade adding
 * fields cannot break deserialization. Pi also omits unset optional keys rather
 * than sending `null`, so every accessor here is nullable.
 */
sealed interface PiRecord {

    /** A reply to a command we sent. */
    data class Response(
        val id: String?,
        val command: String?,
        val success: Boolean,
        val data: JsonElement?,
        val error: String?,
    ) : PiRecord

    /**
     * A dialog pi wants the user to answer. Must be replied to via
     * [PiCommand.extensionUiResponse], otherwise the extension blocks.
     */
    data class UiRequest(
        val id: String,
        val method: String,
        val raw: JsonObject,
    ) : PiRecord {
        val title: String? get() = raw.str("title")
        val message: String? get() = raw.str("message")
        val placeholder: String? get() = raw.str("placeholder")
        val prefill: String? get() = raw.str("prefill")
        val text: String? get() = raw.str("text")
        val notifyType: String? get() = raw.str("notifyType")
        val statusKey: String? get() = raw.str("statusKey")
        val statusText: String? get() = raw.str("statusText")
        val options: List<String>
            get() = (raw["options"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
        val timeoutMs: Long? get() = raw["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        /** True when pi expects an answer; the others are display hints. */
        val expectsReply: Boolean get() = method in REPLY_METHODS

        private companion object {
            val REPLY_METHODS = setOf("select", "confirm", "input", "editor")
        }
    }

    /** Any session/agent event, addressed by its `type` discriminator. */
    data class Event(val type: String, val raw: JsonObject) : PiRecord

    /** A line that was not a JSON object. pi itself never produces these. */
    data class Malformed(val line: String) : PiRecord
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Parser for the records above. Pure, so it is directly unit-testable. */
object PiRecordParser {

    /**
     * `ignoreUnknownKeys` plus a JSON-object-first design keeps this resilient
     * across pi releases; pi's own docs and implementation already disagree in
     * several places.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
        allowSpecialFloatingPointValues = true
    }

    fun parse(line: String): PiRecord {
        val element = runCatching { json.parseToJsonElement(line) }.getOrNull()
            ?: return PiRecord.Malformed(line)
        val obj = element as? JsonObject ?: return PiRecord.Malformed(line)

        return when (obj.str("type")) {
            "response" -> PiRecord.Response(
                id = obj.str("id"),
                command = obj.str("command"),
                success = obj.bool("success") ?: false,
                data = obj["data"]?.takeIf { it !is JsonNull },
                error = obj.str("error"),
            )

            "extension_ui_request" -> {
                val id = obj.str("id")
                val method = obj.str("method")
                if (id == null || method == null) PiRecord.Malformed(line)
                else PiRecord.UiRequest(id = id, method = method, raw = obj)
            }

            null -> PiRecord.Malformed(line)

            else -> PiRecord.Event(type = obj.str("type")!!, raw = obj)
        }
    }
}
