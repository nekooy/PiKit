package pi.kit.mob.pi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializers for pi's RPC commands.
 *
 * Every command is built as a [JsonObject] rather than assembled as text. That
 * is a correctness requirement, not a style choice: pi 0.85.1 dereferences
 * `command.id` without a null check, so a bare `null` record on stdin throws
 * inside the handler, escapes as an unhandled rejection and kills the process
 * with exit code 1. Building objects makes that class of bug unrepresentable.
 */
object PiCommand {

    fun build(id: String, type: String, extra: (JsonObjectBuilder.() -> Unit)? = null): String =
        buildJsonObject {
            put("id", id)
            put("type", type)
            extra?.invoke(this)
        }.toString()

    // ---- prompting -------------------------------------------------------

    /**
     * @param streamingBehaviour `"steer"` or `"followUp"` while the agent is
     *   already processing. Omitting it during a run is rejected by pi.
     */
    fun prompt(
        id: String,
        message: String,
        streamingBehaviour: String? = null,
        images: List<ImageAttachment> = emptyList(),
    ): String = build(id, "prompt") {
        put("message", message)
        if (streamingBehaviour != null) put("streamingBehavior", streamingBehaviour)
        if (images.isNotEmpty()) put("images", imagesJson(images))
    }

    fun steer(id: String, message: String, images: List<ImageAttachment> = emptyList()): String =
        build(id, "steer") {
            put("message", message)
            if (images.isNotEmpty()) put("images", imagesJson(images))
        }

    fun followUp(id: String, message: String, images: List<ImageAttachment> = emptyList()): String =
        build(id, "follow_up") {
            put("message", message)
            if (images.isNotEmpty()) put("images", imagesJson(images))
        }

    fun abort(id: String): String = build(id, "abort")

    fun clearQueue(id: String): String = build(id, "clear_queue")

    fun newSession(id: String, parentSession: String? = null): String = build(id, "new_session") {
        if (parentSession != null) put("parentSession", parentSession)
    }

    /**
     * Reopens a saved session.
     *
     * @param sessionPath an **absolute** `*.jsonl` path. pi treats a bare id as
     *   a path fragment and, when it cannot resolve one, falls back to asking on
     *   stdin — which would corrupt the RPC stream.
     */
    fun switchSession(id: String, sessionPath: String): String = build(id, "switch_session") {
        put("sessionPath", sessionPath)
    }

    // ---- state / model ---------------------------------------------------

    fun getState(id: String): String = build(id, "get_state")

    fun setModel(id: String, provider: String, modelId: String): String = build(id, "set_model") {
        put("provider", provider)
        put("modelId", modelId)
    }

    fun getAvailableModels(id: String): String = build(id, "get_available_models")

    // ---- thinking / queue modes -----------------------------------------

    /**
     * Sets the reasoning level.
     *
     * [PiLaunchOptions.THINKING_LEVELS] is pi 0.85.1's list, and the *picker's* list
     * is pi's own answer (`get_available_thinking_levels`), which is authoritative:
     * `xhigh` and `max` exist only for models whose `thinkingLevelMap` names them,
     * and a later pi is free to add a level this build has never heard of. So the
     * guard here rejects only a blank level — the app's own bug — and forwards
     * anything else. pi clamps a level it does not know to the first one the model
     * has (`clampThinkingLevel`, `pi-ai`), which is also what a `require` used to
     * turn into a thrown exception and an error banner on a level that pi had just
     * reported as available.
     */
    fun setThinkingLevel(id: String, level: String): String {
        require(level.isNotBlank()) { "A thinking level must be named" }
        return build(id, "set_thinking_level") { put("level", level) }
    }

    fun getAvailableThinkingLevels(id: String): String = build(id, "get_available_thinking_levels")

    /** Pi stores an unrecognised mode verbatim and echoes it from `get_state`. */
    fun setSteeringMode(id: String, mode: String): String {
        require(mode == "all" || mode == "one-at-a-time") { "Unknown steering mode: $mode" }
        return build(id, "set_steering_mode") { put("mode", mode) }
    }

    fun setFollowUpMode(id: String, mode: String): String {
        require(mode == "all" || mode == "one-at-a-time") { "Unknown follow-up mode: $mode" }
        return build(id, "set_follow_up_mode") { put("mode", mode) }
    }

    // ---- compaction / retry ---------------------------------------------

    fun compact(id: String, customInstructions: String? = null): String = build(id, "compact") {
        if (!customInstructions.isNullOrBlank()) put("customInstructions", customInstructions)
    }

    fun setAutoCompaction(id: String, enabled: Boolean): String =
        build(id, "set_auto_compaction") { put("enabled", enabled) }

    fun setAutoRetry(id: String, enabled: Boolean): String =
        build(id, "set_auto_retry") { put("enabled", enabled) }

    // ---- bash ------------------------------------------------------------

    fun bash(id: String, command: String, excludeFromContext: Boolean? = null): String =
        build(id, "bash") {
            put("command", command)
            if (excludeFromContext != null) put("excludeFromContext", excludeFromContext)
        }

    fun abortBash(id: String): String = build(id, "abort_bash")

    // ---- session ---------------------------------------------------------

    fun getSessionStats(id: String): String = build(id, "get_session_stats")

    fun getMessages(id: String): String = build(id, "get_messages")

    /**
     * Writes the whole session to a self-contained HTML file.
     *
     * [outputPath] is an **absolute** path inside the runtime's own filesystem.
     * pi documents this command without one as "a default location", and a default
     * this app cannot name is a file the user cannot find — so the caller always
     * passes one, and the answer carries the path back (`data.path`).
     */
    fun exportHtml(id: String, outputPath: String): String = build(id, "export_html") {
        put("outputPath", outputPath)
    }

    /**
     * Duplicates the active branch of the session into a new one.
     *
     * The response's `data.cancelled` is the interesting field: a
     * `session_before_fork` extension handler may refuse, and pi reports that as
     * `success: true` with a cancelled payload — the same shape `new_session` and
     * `switch_session` use. Reading only `success` here would tell the user their
     * session was duplicated when it was not.
     */
    fun clone(id: String): String = build(id, "clone")

    fun setSessionName(id: String, name: String): String =
        build(id, "set_session_name") { put("name", name) }

    // ---- extension UI ----------------------------------------------------

    /*
     * These are the only commands whose sole `id` is the *dialog's* id. pi
     * intercepts `extension_ui_response` before command dispatch, so it never
     * emits a `response` record for them — call them with `send`, not
     * `request`, and never add a correlation id of our own.
     */

    /** Answers a `select`, `input` or `editor` dialog. */
    fun extensionUiValue(requestId: String, value: String): String =
        uiResponse(requestId) { put("value", value) }

    fun extensionUiConfirmed(requestId: String, confirmed: Boolean): String =
        uiResponse(requestId) { put("confirmed", confirmed) }

    /** Dismisses a dialog; the extension receives `undefined`/`false`. */
    fun extensionUiCancelled(requestId: String): String =
        uiResponse(requestId) { put("cancelled", true) }

    private fun uiResponse(requestId: String, extra: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("type", "extension_ui_response")
            put("id", requestId)
            extra()
        }.toString()

    private fun imagesJson(images: List<ImageAttachment>): JsonArray =
        JsonArray(
            images.map { image ->
                buildJsonObject {
                    put("type", "image")
                    // Raw base64 only — no `data:` URL prefix.
                    put("data", image.base64)
                    put("mimeType", image.mimeType)
                }
            },
        )
}

/** An image attached to a prompt. [base64] must not carry a `data:` prefix. */
data class ImageAttachment(val base64: String, val mimeType: String)
