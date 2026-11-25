/*
 * A conversation's name, generated once, from a request of our own.
 *
 * ## Why not pi
 *
 * pi has no title command: a session stays unnamed until something calls
 * `set_session_name`, and its own picker falls back to the first prompt. Spawning a
 * second `pi` process to ask a model for five words would cost a Node start
 * (~120 MB, ~2 s) for a request that is one small POST, so the request is written
 * here instead. It is the app's only direct provider call and it is deliberately
 * narrow: one short prompt, one short answer, no streaming, no tools, no session.
 *
 * ## What it borrows from DSH
 *
 * The instruction is DSH's first-prompt title policy, because that policy is the
 * one measured to produce stable, short, plain titles: return one line in the
 * messages' language, about five words or ten CJK characters, with no Markdown,
 * quotes, prefix or explanation; a best-effort title when there is little to name.
 * The output parser follows the same rules (first non-empty line, unwrap asterisk
 * emphasis, reject tool-call and length-capped finishes) and the title is clipped to
 * one line and a fixed budget.
 *
 * ## What it deliberately does not do
 *
 * It runs **once per conversation**, on the first turn that settles without an
 * error, and only while the title switch is on. A failed request is not retried on
 * every later turn: the point of the feature is a cheap convenience, and a provider
 * that refused once is not made cheaper by asking again. A name the reader set is
 * never overwritten — every write checks that the session is still unnamed.
 */
package pi.kit.mob.pi

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import pi.kit.mob.data.CustomApi
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.normalizeApiBaseUrl

/** One conversation's one title attempt. */
internal class TitleGenerator(private val host: PiHost) {

    /**
     * Sessions this process has already asked about.
     *
     * A conversation can settle several times (a tool turn, a retry, a follow-up);
     * the first successful answer is the only one that should pay for a title. The
     * set also keeps a failed request from being repeated on every later turn.
     */
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    /**
     * Called when a turn goes idle. Cheap and synchronous: the decision is a few
     * field reads, and the request itself runs on the host's scope.
     */
    fun maybeGenerate(session: PiAgentSession) {
        if (!host.settingsStore.read().chatTitleEnabled) return
        val path = session.sessionPath ?: return
        val state = session.conversation.value
        if (!state.sessionName.isNullOrBlank()) return
        val turn = state.turns.lastOrNull()?.takeIf { it.isComplete } ?: return
        val answer = state.items.firstOrNull { it.key == turn.finalReplyKey } as? ChatItem.Assistant
            ?: return
        if (answer.status != ReplyStatus.Done || answer.text.isBlank()) return
        if (state.lastError != null) return
        val prompt = state.items
            .filterIsInstance<ChatItem.User>()
            .firstOrNull()
            ?.let { parsePromptExtras(it.text).displayText }
            ?.trim()
            .orEmpty()
        if (prompt.isBlank()) return
        if (!attempted.add(path)) return

        val profile = host.settingsStore.profiles.activeProfile
        if (profile == null || profile.apiKey.isBlank() || profile.modelId.isBlank()) {
            // Nothing was spent and nothing was learned; a later turn may have a
            // configured profile, so this session is not marked as attempted.
            attempted.remove(path)
            return
        }
        host.scope.launch {
            val title = TitleRequest.generate(profile, prompt.take(MAX_INPUT_CHARS)) ?: return@launch
            // The reader may have renamed the conversation while the request was in
            // flight, and the conversation may have moved to another file.
            if (session.sessionPath != path) return@launch
            if (!session.conversation.value.sessionName.isNullOrBlank()) return@launch
            session.setSessionName(title)
        }
    }

    private companion object {
        /**
         * The first prompt, not the whole conversation: a title describes what the
         * conversation is about, and the opening question is the cheapest and most
         * representative slice of it. Two thousand characters is several screens of
         * prose and still under a thousand tokens on every provider.
         */
        const val MAX_INPUT_CHARS = 2_000
    }
}

/** The four shapes a title request can take. */
internal enum class TitleApi {
    OpenAiCompletions,
    OpenAiResponses,
    AnthropicMessages,
    GoogleGenerative,
}

/**
 * Where a title request goes and how it authenticates.
 *
 * [url] is complete except for Google's model and key, which are part of the path
 * and therefore built with the endpoint.
 */
internal data class TitleEndpoint(
    val api: TitleApi,
    val url: String,
    val headers: Map<String, String>,
    val model: String,
    val key: String,
)

/**
 * The endpoint for one saved profile, or null when this app cannot address it.
 *
 * A built-in provider with no base URL is resolved from the same host PiKit's model
 * list uses. A provider whose chat base this app does not know is refused rather
 * than guessed: a wrong URL would be a request to somebody else's server carrying
 * the reader's key, which is the one failure worth being strict about.
 */
internal fun titleEndpoint(profile: ModelProfile): TitleEndpoint? {
    val provider = PiProvider.fromId(profile.provider)
    val custom = provider == PiProvider.CUSTOM
    val api = when {
        provider == PiProvider.GOOGLE -> TitleApi.GoogleGenerative
        provider == PiProvider.ANTHROPIC ||
            provider == PiProvider.MINIMAX ||
            provider == PiProvider.MINIMAX_CN -> TitleApi.AnthropicMessages
        provider == PiProvider.META -> TitleApi.OpenAiResponses
        custom -> when (CustomApi.fromId(profile.api)) {
            CustomApi.ANTHROPIC_MESSAGES -> TitleApi.AnthropicMessages
            CustomApi.OPENAI_RESPONSES -> TitleApi.OpenAiResponses
            CustomApi.OPENAI_COMPLETIONS -> TitleApi.OpenAiCompletions
        }

        else -> TitleApi.OpenAiCompletions
    }
    val rawBase = profile.baseUrl.trim()
        .ifBlank { profile.writtenBaseUrl.trim() }
        .ifBlank { builtinBase(provider) }
        ?: return null
    val base = when (api) {
        TitleApi.OpenAiCompletions ->
            normalizeApiBaseUrl(rawBase, CustomApi.OPENAI_COMPLETIONS.id)

        TitleApi.OpenAiResponses ->
            normalizeApiBaseUrl(rawBase, CustomApi.OPENAI_RESPONSES.id)

        TitleApi.AnthropicMessages ->
            normalizeApiBaseUrl(rawBase, CustomApi.ANTHROPIC_MESSAGES.id)

        TitleApi.GoogleGenerative -> rawBase.trimEnd('/')
    }
    if (base.isBlank()) return null
    val url = when (api) {
        TitleApi.OpenAiCompletions -> "$base/chat/completions"
        TitleApi.OpenAiResponses -> "$base/responses"
        TitleApi.AnthropicMessages -> "$base/v1/messages"
        TitleApi.GoogleGenerative -> "$base/models/${profile.modelId}:generateContent"
    }
    val headers = buildMap {
        put("Content-Type", "application/json")
        when (api) {
            TitleApi.AnthropicMessages -> {
                put("x-api-key", profile.apiKey)
                put("anthropic-version", "2023-06-01")
            }

            TitleApi.GoogleGenerative -> Unit
            else -> put("Authorization", "Bearer ${profile.apiKey}")
        }
    }
    return TitleEndpoint(api, url, headers, profile.modelId, profile.apiKey)
}

/** The chat bases of pi's built-in providers this app can address directly. */
private fun builtinBase(provider: PiProvider?): String? = when (provider) {
    PiProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
    PiProvider.OPENAI -> "https://api.openai.com/v1"
    PiProvider.ANTHROPIC -> "https://api.anthropic.com"
    PiProvider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta"
    PiProvider.OPENROUTER -> "https://openrouter.ai/api/v1"
    PiProvider.GROQ -> "https://api.groq.com/openai/v1"
    PiProvider.XAI -> "https://api.x.ai/v1"
    PiProvider.MISTRAL -> "https://api.mistral.ai/v1"
    PiProvider.CEREBRAS -> "https://api.cerebras.ai/v1"
    PiProvider.ZAI -> "https://api.z.ai/api/paas/v4"
    PiProvider.MOONSHOTAI -> "https://api.moonshot.ai/v1"
    PiProvider.MOONSHOTAI_CN -> "https://api.moonshot.cn/v1"
    PiProvider.META -> "https://api.meta.ai/v1"
    PiProvider.MINIMAX -> "https://api.minimax.io/anthropic"
    PiProvider.MINIMAX_CN -> "https://api.minimaxi.com/anthropic"
    PiProvider.NVIDIA -> "https://integrate.api.nvidia.com/v1"
    PiProvider.TOGETHER -> "https://api.together.ai/v1"
    PiProvider.FIREWORKS -> "https://api.fireworks.ai/inference/v1"
    PiProvider.BASETEN -> "https://inference.baseten.co/v1"
    PiProvider.HUGGINGFACE -> "https://router.huggingface.co/v1"
    PiProvider.VERCEL_AI_GATEWAY -> "https://ai-gateway.vercel.sh/v1"
    PiProvider.QWEN_TOKEN_PLAN,
    PiProvider.QWEN_TOKEN_PLAN_INDIVIDUAL,
    -> "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"

    PiProvider.QWEN_TOKEN_PLAN_CN,
    PiProvider.QWEN_TOKEN_PLAN_INDIVIDUAL_CN,
    -> "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"

    else -> null
}

/**
 * The instructions, taken from DSH's first-prompt title provider.
 *
 * "Use the language of the messages" is what keeps a Chinese conversation from
 * getting an English title, and the length targets are the two units a short title
 * is measured in: words for alphabetic languages, characters for CJK.
 */
internal fun titleSystemPrompt(): String = listOf(
    "Create a concise title for an AI assistant conversation from the supplied human messages.",
    "Return only the title on one line, in plain text of natural language, with no quotes, prefix, explanation, Markdown, XML, or terminal control codes. No code is allowed.",
    "Use the language of the messages.",
    "Aim for about 5 words in non-CJK languages or 10 CJK characters.",
    "If the messages give little to name, still return a short best-effort title, such as Greeting, instead of explaining.",
).joinToString("\n")

/** The user turn: one message, framed as JSON so the text cannot break a delimiter. */
internal fun titleUserPrompt(prompt: String): String {
    val messages = buildJsonArray {
        add(buildJsonObject {
            put("seq", 1)
            put("text", prompt)
        })
    }
    return "Generate the session title from this JSON array of human messages:\n$messages"
}

/** The request body: one system instruction and the framed first prompt. */
internal fun titleRequestBody(endpoint: TitleEndpoint, prompt: String): String {
    val system = titleSystemPrompt()
    val user = titleUserPrompt(prompt)
    val objectBody = when (endpoint.api) {
        TitleApi.OpenAiCompletions -> buildJsonObject {
            put("model", endpoint.model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("stream", false)
        }

        TitleApi.OpenAiResponses -> buildJsonObject {
            put("model", endpoint.model)
            put("instructions", system)
            put("input", user)
            put("max_output_tokens", MAX_OUTPUT_TOKENS)
        }

        TitleApi.AnthropicMessages -> buildJsonObject {
            put("model", endpoint.model)
            put("system", system)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("max_tokens", MAX_OUTPUT_TOKENS)
        }

        TitleApi.GoogleGenerative -> buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) })
                })
            })
            put("generationConfig", buildJsonObject { put("maxOutputTokens", MAX_OUTPUT_TOKENS) })
        }
    }
    return objectBody.toString()
}

/** The one place a request is made. Never logs the prompt, the key or the body. */
internal object TitleRequest {

    fun generate(profile: ModelProfile, prompt: String): String? {
        val endpoint = titleEndpoint(profile) ?: return null
        val body = titleRequestBody(endpoint, prompt)
        val raw = post(endpoint, body) ?: return null
        return titleFromResponse(endpoint.api, raw)?.let(::normalizeTitle)?.takeIf { it.isNotBlank() }
    }

    private fun post(endpoint: TitleEndpoint, body: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = if (endpoint.api == TitleApi.GoogleGenerative) {
                "${endpoint.url}?key=${endpoint.key}"
            } else {
                endpoint.url
            }
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                endpoint.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                // The status is enough to tell a rejected key from a bad route. The
                // body can quote the prompt back, so it is not logged.
                Log.w(TAG, "title request failed: HTTP $code")
                null
            } else {
                text
            }
        } catch (e: Exception) {
            Log.w(TAG, "title request failed: ${e.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** One text line from each provider's response envelope. */
internal fun titleFromResponse(api: TitleApi, body: String): String? = runCatching {
    val root = PiRecordParser.json.parseToJsonElement(body).jsonObject
    when (api) {
        TitleApi.OpenAiCompletions -> {
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            val finish = choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
            if (finish == "tool_calls" || finish == "length" || finish == "content_filter") {
                return@runCatching null
            }
            val message = choice?.get("message")?.jsonObject
            when (val content = message?.get("content")) {
                is JsonPrimitive -> content.contentOrNull
                is JsonArray -> content.joinToString(" ") { block ->
                    (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                }

                else -> null
            }
        }

        TitleApi.OpenAiResponses -> {
            val status = root["status"]?.jsonPrimitive?.contentOrNull
            val incomplete = root["incomplete_details"]?.jsonObject
                ?.get("reason")?.jsonPrimitive?.contentOrNull
            if (status == "incomplete" && incomplete != null) return@runCatching null
            root["output"]?.jsonArray?.joinToString(" ") { item ->
                (item as? JsonObject)?.get("content")?.jsonArray?.joinToString(" ") { block ->
                    val part = block as? JsonObject ?: return@joinToString ""
                    val type = part["type"]?.jsonPrimitive?.contentOrNull
                    if (type == "output_text" || type == "text") {
                        part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    } else {
                        ""
                    }
                }.orEmpty()
            }
        }

        TitleApi.AnthropicMessages -> {
            val stop = root["stop_reason"]?.jsonPrimitive?.contentOrNull
            if (stop == "tool_use" || stop == "max_tokens" || stop == "refusal") {
                return@runCatching null
            }
            root["content"]?.jsonArray?.joinToString(" ") { block ->
                val part = block as? JsonObject ?: return@joinToString ""
                if (part["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    part["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                } else {
                    ""
                }
            }
        }

        TitleApi.GoogleGenerative -> {
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            val finish = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
            if (finish == "MAX_TOKENS" || finish == "MALFORMED_FUNCTION_CALL") {
                return@runCatching null
            }
            candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                ?.joinToString(" ") { part ->
                    (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                }
        }
    }
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * One safe title line from whatever the model returned.
 *
 * The first non-empty line is the title; models that ignore the one-line instruction
 * put the title first and commentary after it. Wrapping asterisk emphasis is
 * removed, a `Title:` prefix is dropped, quotes are trimmed, controls are removed
 * and whitespace is collapsed. The result is capped so a model that answers with a
 * paragraph cannot become a drawer row.
 */
internal fun normalizeTitle(raw: String): String {
    val line = raw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    var text = line
    text = text.replace(Regex("^(?i)(title|标题|タイトル)\\s*[:：]\\s*"), "")
    val emphasis = Regex("^(\\*{1,3})(\\S(?:.*\\S)?)\\1$").find(text)
    if (emphasis != null) text = emphasis.groupValues[2]
    text = text.trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』')
    text = text
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return truncate(text)
}

/** A code-point prefix within both the character and the byte budget. */
private fun truncate(text: String): String {
    if (text.codePointCount(0, text.length) <= MAX_TITLE_CHARS &&
        text.toByteArray(Charsets.UTF_8).size <= MAX_TITLE_BYTES
    ) {
        return text
    }
    val out = StringBuilder()
    var chars = 0
    var bytes = 0
    var index = 0
    while (index < text.length && chars < MAX_TITLE_CHARS) {
        val codePoint = text.codePointAt(index)
        val width = Character.charCount(codePoint)
        val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
        if (bytes + encoded > MAX_TITLE_BYTES) break
        out.appendCodePoint(codePoint)
        chars++
        bytes += encoded
        index += width
    }
    return out.toString().trim()
}

private const val TAG = "TitleGenerator"
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_TIMEOUT_MS = 20_000
private const val MAX_OUTPUT_TOKENS = 64
private const val MAX_TITLE_CHARS = 40
private const val MAX_TITLE_BYTES = 80
