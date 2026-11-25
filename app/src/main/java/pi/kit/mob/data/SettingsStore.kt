package pi.kit.mob.data

import android.content.Context
import pi.kit.mob.locales.Lang
import pi.kit.mob.pi.imageLimitObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Provider ids pi accepts, mapped to the environment variable each one reads.
 *
 * Passing credentials through the environment is deliberate: `--api-key` is
 * visible in the process command line to anything that can read `/proc`, and pi
 * additionally rejects it unless a model is also given on the command line.
 * (PiKit passes both for the launched profile, and puts every *other* saved
 * profile's key in the environment as `PIKIT_KEY_<profile id>`, which is the variable
 * each of their `models.json` entries names — see [ProfileAddressing].)
 *
 * ## Which of pi's providers are here, and which are deliberately not
 *
 * This is pi's own list, taken from `getApiKeyEnvVars` in the bundled
 * `pi-ai/providers/all` (`dist/bundle/chunks`, the same table pi's own help text
 * documents), filtered down to the providers a *phone form* can actually configure — a
 * provider id, and one secret pasted into a field:
 *
 *  * **Everything with a single API key is here.** That is the whole list below, and it
 *    includes several regional variants of one service (`moonshotai` and `moonshotai-cn`,
 *    the four Xiaomi endpoints, Qwen's three) because pi treats them as separate providers
 *    with separate base URLs and separate model sets — two of them can share one
 *    environment variable name and still resolve different catalogues, which is exactly
 *    what the user is choosing between.
 *  * **OAuth-only providers are not** (`openai-codex`, `github-copilot`): their normal
 *    credential comes from an interactive login PiKit has no flow for, so the field would
 *    ask for a token the user has no way to obtain.
 *  * **Providers that need more than a key are not** (`amazon-bedrock` wants AWS
 *    credentials, `azure-openai-responses` a resource endpoint, `google-vertex` a project
 *    and a location, the two Cloudflare ones an account id and for the gateway a slug,
 *    `radius` a gateway URL from a service whose config has to be fetched first). Each of
 *    those needs a second or third input this page does not have, and adding the id
 *    without them would produce a provider that looks configurable and cannot work. The
 *    custom endpoint covers the same ground for anything that speaks the OpenAI API.
 *
 * `llama.cpp` is missing for a different reason: it is a local server with no credential
 * at all, so there is nothing for the key field to hold.
 */
enum class PiProvider(val id: String, val envVar: String, val label: String) {
    ANTHROPIC("anthropic", "ANTHROPIC_API_KEY", "Anthropic"),
    OPENAI("openai", "OPENAI_API_KEY", "OpenAI"),
    DEEPSEEK("deepseek", "DEEPSEEK_API_KEY", "DeepSeek"),
    GOOGLE("google", "GEMINI_API_KEY", "Google Gemini"),
    OPENROUTER("openrouter", "OPENROUTER_API_KEY", "OpenRouter"),
    GROQ("groq", "GROQ_API_KEY", "Groq"),
    XAI("xai", "XAI_API_KEY", "xAI"),
    MISTRAL("mistral", "MISTRAL_API_KEY", "Mistral"),
    CEREBRAS("cerebras", "CEREBRAS_API_KEY", "Cerebras"),
    ZAI("zai", "ZAI_API_KEY", "ZAI"),
    ZAI_CODING_CN("zai-coding-cn", "ZAI_CODING_CN_API_KEY", "Z.AI Coding CN"),
    MOONSHOTAI("moonshotai", "MOONSHOT_API_KEY", "Moonshot AI (Kimi)"),
    MOONSHOTAI_CN("moonshotai-cn", "MOONSHOT_API_KEY", "Moonshot AI CN (Kimi)"),
    KIMI_CODING("kimi-coding", "KIMI_API_KEY", "Kimi For Coding"),
    MINIMAX("minimax", "MINIMAX_API_KEY", "MiniMax"),
    MINIMAX_CN("minimax-cn", "MINIMAX_CN_API_KEY", "MiniMax CN"),
    META("meta", "META_API_KEY", "Meta (Muse)"),
    XIAOMI("xiaomi", "XIAOMI_API_KEY", "Xiaomi (MiMo)"),
    XIAOMI_TOKEN_PLAN_CN("xiaomi-token-plan-cn", "XIAOMI_TOKEN_PLAN_CN_API_KEY", "Xiaomi Token Plan CN"),
    XIAOMI_TOKEN_PLAN_AMS("xiaomi-token-plan-ams", "XIAOMI_TOKEN_PLAN_AMS_API_KEY", "Xiaomi Token Plan AMS"),
    XIAOMI_TOKEN_PLAN_SGP("xiaomi-token-plan-sgp", "XIAOMI_TOKEN_PLAN_SGP_API_KEY", "Xiaomi Token Plan SGP"),
    QWEN_TOKEN_PLAN("qwen-token-plan", "QWEN_TOKEN_PLAN_API_KEY", "Qwen Token Plan"),
    QWEN_TOKEN_PLAN_CN("qwen-token-plan-cn", "QWEN_TOKEN_PLAN_CN_API_KEY", "Qwen Token Plan CN"),
    QWEN_TOKEN_PLAN_INDIVIDUAL(
        "qwen-token-plan-individual",
        "QWEN_TOKEN_PLAN_API_KEY",
        "Qwen Token Plan Individual",
    ),
    QWEN_TOKEN_PLAN_INDIVIDUAL_CN(
        "qwen-token-plan-individual-cn",
        "QWEN_TOKEN_PLAN_CN_API_KEY",
        "Qwen Token Plan Individual CN",
    ),
    NVIDIA("nvidia", "NVIDIA_API_KEY", "NVIDIA"),
    TOGETHER("together", "TOGETHER_API_KEY", "Together"),
    FIREWORKS("fireworks", "FIREWORKS_API_KEY", "Fireworks"),
    BASETEN("baseten", "BASETEN_API_KEY", "Baseten"),
    HUGGINGFACE("huggingface", "HF_TOKEN", "Hugging Face"),
    TYPESAFE("typesafe", "TYPESAFE_API_KEY", "TypeSafe"),
    VERCEL_AI_GATEWAY("vercel-ai-gateway", "AI_GATEWAY_API_KEY", "Vercel AI Gateway"),
    OPENCODE("opencode", "OPENCODE_API_KEY", "OpenCode Zen"),
    OPENCODE_GO("opencode-go", "OPENCODE_API_KEY", "OpenCode Go"),
    ANT_LING("ant-ling", "ANT_LING_API_KEY", "Ant Ling"),

    /**
     * An endpoint the user supplies — a relay, a gateway, or a self-hosted server.
     *
     * pi has no built-in provider for these, so one is registered through
     * `~/.pi/agent/models.json` at launch; see [CustomEndpoint]. The id here is
     * the provider name written into that file, and [envVar] is the variable its
     * `apiKey` references — PiKit sets both, so the name is an internal contract
     * rather than one the user has to know.
     */
    CUSTOM(CustomEndpoint.PROVIDER_ID, CustomEndpoint.API_KEY_ENV, "Custom endpoint");

    companion object {
        fun fromId(id: String?): PiProvider? = entries.firstOrNull { it.id == id }

        /** The providers that need a base URL typed in before they work. */
        val needsBaseUrl: Set<PiProvider> get() = setOf(CUSTOM)
    }
}

/**
 * The base URL PiKit hands pi, completed for the request path of [api].
 *
 * Trailing slashes are trimmed, and the version segment is settled the way the
 * **client that will post to it** builds its path:
 *
 *  * `openai-completions` and `openai-responses` post to `<baseUrl>/chat/completions`
 *    and `<baseUrl>/responses` through the OpenAI SDK, whose base every one of pi's
 *    documented examples puts `/v1` on. A bare host therefore gains `/v1`: a relay
 *    reached at `https://host` instead of `https://host/v1` fails as three connection
 *    retries — the report of severe lag and frequent disconnects on a custom endpoint
 *    that was one path segment short. A URL that already names a path is left alone,
 *    because relays differ on where the version belongs and
 *    `https://gateway.example.com/openai` is a real shape this must not rewrite.
 *  * `anthropic-messages` posts through `@anthropic-ai/sdk` with
 *    `baseURL: model.baseUrl`, and the SDK appends **`/v1/messages` itself**
 *    (`resources/messages`: `this._client.post('/v1/messages', …)`, `buildURL` being
 *    `baseURL + path`). Its base is therefore the host — or the gateway's own prefix —
 *    and never a `/v1`: pi's own catalogue states exactly that for every provider it
 *    speaks this protocol to (`https://api.anthropic.com`,
 *    `https://api.minimax.io/anthropic`, and OpenRouter's per-model
 *    `https://openrouter.ai/api`). Completing one with `/v1` doubles the segment, which
 *    is the reported `404 … Invalid URL (POST /v1/v1/messages)`: a 404 for the URL the
 *    app wrote, not a verdict on the key or the model. A trailing `/v1` is removed for
 *    the same reason — a relay's dashboard documents that spelling for its
 *    *OpenAI*-shaped endpoint, and the same string pasted onto an Anthropic-shaped one
 *    is one segment too long even though it looks right.
 *
 * Anything that is not an absolute http(s) URL is refused rather than completed:
 * `localhost:11434` has a scheme-like colon and no host from `URI`'s point of view, and
 * writing it into `models.json` produces a provider that cannot be reached and an error
 * that names neither the field nor the missing scheme.
 */
fun normalizeApiBaseUrl(raw: String, api: String = CustomApi.DEFAULT_ID): String {
    val base = raw.trim().trimEnd('/')
    if (base.isEmpty()) return base
    val uri = runCatching { java.net.URI(base) }.getOrNull() ?: return ""
    val scheme = uri.scheme?.lowercase() ?: return ""
    if (scheme != "http" && scheme != "https") return ""
    if (uri.host.isNullOrBlank()) return ""
    val path = uri.path ?: ""
    if (uri.rawQuery == null && uri.fragment == null) return completeBasePath(base, path, api)
    // A query or fragment is not part of the API root. Left in place it is
    // *appended to* by every path this app builds — `https://h/v1?x=1/models`
    // is what `$base/models` becomes — and the request goes somewhere the
    // provider never named. Rebuilt without them rather than truncated at `?`,
    // so a base that legitimately carries userinfo or a port survives.
    return runCatching {
        java.net.URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            completeBasePath(path, path, api).ifEmpty { null },
            null,
            null,
        ).toString().trimEnd('/')
    }.getOrDefault("")
}

/**
 * [base] with its version segment settled for [api] — see [normalizeApiBaseUrl].
 *
 * [base] is the text to return and [path] is that text's parsed path, kept apart
 * because `URI.path` is decoded while [base] is what the user typed, and a percent
 * escape in a path must survive.
 */
private fun completeBasePath(base: String, path: String, api: String): String {
    val trimmed = path.trimEnd('/')
    return when {
        api == CustomApi.ANTHROPIC_MESSAGES.id && trimmed.endsWith("/v1") ->
            base.removeSuffix("/v1")
        api == CustomApi.ANTHROPIC_MESSAGES.id -> base
        trimmed.isEmpty() -> "$base/v1"
        else -> base
    }
}

/**
 * The wire protocol a custom endpoint speaks, written into `models.json`'s `api`.
 *
 * pi reaches a model through a named client, and the name is the one thing a
 * relay cannot negotiate: an `anthropic-messages` gateway reached through the
 * `openai-completions` client produces a stream that fails to parse rather than
 * a clear error. It used to be pinned to `openai-completions` on the argument
 * that "that is what relays speak" — true of most, and wrong for the growing
 * set of Anthropic- and Responses-shaped gateways (Meta's Muse among them), which
 * were unconfigurable as a result.
 *
 * The labels are pi's own identifiers rather than translations: they are the
 * values in `models.json`, in pi's `docs/models.md`, and in the error a mismatch
 * produces, and a user reading a relay's docs needs the two to match. The prose
 * that says *which* one to pick is `Strings`'s.
 */
enum class CustomApi(val id: String) {
    /** OpenAI chat completions — `<baseUrl>/chat/completions`. The common case. */
    OPENAI_COMPLETIONS("openai-completions"),

    /**
     * Anthropic Messages API — the `/v1/messages` shape.
     *
     * The `/v1` is the *client's*: `@anthropic-ai/sdk` is handed the base and posts
     * `<base>/v1/messages`, so the base this app writes must not carry it. See
     * [normalizeApiBaseUrl], which is where that spelling is decided.
     */
    ANTHROPIC_MESSAGES("anthropic-messages"),

    /** OpenAI Responses API — `/v1/responses`. */
    OPENAI_RESPONSES("openai-responses"),
    ;

    companion object {
        const val DEFAULT_ID = "openai-completions"

        fun fromId(id: String?): CustomApi =
            entries.firstOrNull { it.id == id } ?: OPENAI_COMPLETIONS
    }
}

/**
 * The `models.json` entry PiKit writes for a custom endpoint.
 *
 * ## Why this file and not a command-line flag
 *
 * pi resolves a provider's endpoint from its own model catalog. For a relay —
 * anything that speaks the OpenAI API but is not one of pi's built-in providers —
 * the only supported way to add one is `models.json`, which pi reads from its
 * agent directory at startup. There is no `--base-url`.
 *
 * ## The fields, and what each is for
 *
 * `api` names the wire protocol the relay speaks — see [CustomApi]. It is the
 * one field a relay cannot be guessed for: the wrong name produces a stream that
 * fails to parse rather than a clear error, which is why the form offers it.
 *
 * `apiKey` is `$PIKIT_API_KEY`, not the key itself: the file is plain text in the
 * app's data directory, and the key already reaches the process through the
 * environment. Interpolating it means the secret exists in one place.
 *
 * `reasoning` is on because the models a relay fronts are usually reasoning models
 * and pi uses this to decide whether to offer thinking levels at all; a model that
 * does not support them ignores the parameter. An entry the user has settings for
 * overrides even this, like every other field: see [providerObject].
 *
 * `input` is `["text"]` unless the user declared otherwise, and that is a deliberate
 * change from what this wrote before. Every model of a custom endpoint used to be
 * registered as image-capable, on the argument that claiming support a model lacks costs
 * one clear API error while denying support it has costs a feature that appears broken.
 * The argument is sound and the premise was not: pi knows nothing about a relay, so a
 * blanket claim is not a declaration about a model, it is a guess written into a file pi
 * believes. The user now says so per model, on the model page, and the default is the
 * honest one — a relay's model is text-only until someone says otherwise.
 */
object CustomEndpoint {

    /**
     * The id that marks a profile as a **custom endpoint**, stored in
     * `ModelProfile.provider`.
     *
     * Not the id pi is told: every relay is registered under an id of its own
     * (`ProfileAddressing.runtimeProviderId`), because one shared id is one endpoint and
     * one credential as far as pi is concerned — the second relay saved would otherwise
     * overwrite the first.
     */
    const val PROVIDER_ID = "pikit-custom"

    /**
     * The variable a generated `apiKey` names when the caller does not give one.
     *
     * Kept for the document this wrote before a profile had an address; the launch path
     * names `$PIKIT_KEY_<profile id>` instead ([ProfileAddressing.apiKeyReference]).
     */
    const val API_KEY_ENV = "PIKIT_API_KEY"

    /** Where pi looks for it, relative to `$HOME`. */
    const val MODELS_FILE_RELATIVE = ".pi/agent/models.json"

    /**
     * The document pi expects, or null when there is nothing to register.
     *
     * A trailing slash is trimmed, and the version segment follows [api]: a bare host
     * gains `/v1` for the OpenAI shapes, and an `anthropic-messages` base loses one
     * because its client appends `/v1/messages` itself. See [normalizeApiBaseUrl],
     * which is what turns `https://relay.example.com` into the path pi actually posts
     * to. A user who copies `https://example.com/v1` from a provider's dashboard is
     * left alone — unless the protocol says that segment is the client's to add.
     *
     * [modelIds] is *every* model the profile offers, not only the active one.
     * pi answers `set_model` by looking the model up in the catalog it built at
     * startup, so a provider registered with one model can only ever be switched
     * *away* from by restarting the agent — which is a process teardown, a fresh
     * handshake and the loss of the running turn, for a change pi would otherwise
     * apply over RPC. Measured on the emulator before this: picking the second
     * model of one custom endpoint relaunched the agent
     * (`launching: … --model mimo-v2.5` in the log) where picking the first had
     * been applied in place.
     *
     * [settings] is what the user said about each of those ids, and it is the only source
     * of the three fields an entry has to name. There is no fallback to read them from: pi
     * has no catalogue entry for a provider it has never heard of, so nothing here can be
     * inherited and pi's own defaults for a definition that names nothing are what an
     * untouched field means.
     */
    fun providerObject(
        baseUrl: String,
        modelIds: List<String>,
        settings: Map<String, ModelSettings> = emptyMap(),
        api: String = CustomApi.DEFAULT_ID,
        /**
         * What the entry names in `apiKey`: a `$NAME` reference for pi to interpolate,
         * or [API_KEY_ENV] for the single-endpoint document this wrote before every
         * profile had an address of its own.
         */
        apiKey: String = API_KEY_ENV,
    ): JsonObject? {
        val protocol = api.ifBlank { CustomApi.DEFAULT_ID }
        // The protocol decides what the base URL looks like, and that is not a detail:
        // pi hands `anthropic-messages`' `baseUrl` straight to `@anthropic-ai/sdk`,
        // which appends `/v1/messages` itself, so a `/v1` completed here is the
        // reported `404 … POST /v1/v1/messages`. See [normalizeApiBaseUrl].
        val base = normalizeApiBaseUrl(baseUrl, protocol)
        val models = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (base.isEmpty() || models.isEmpty()) return null

        // Data rather than text: this entry is now *merged* into whatever the user
        // already has in `models.json`, which pi's own docs invite them to edit by
        // hand, so the document is read, this one provider is upserted and every
        // other provider and key is carried over untouched.
        return buildJsonObject {
            put("name", "PiKit custom endpoint")
            put("baseUrl", base)
            put("api", protocol)
            put("apiKey", apiKey)
            put(
                "models",
                buildJsonArray { models.forEach { add(entry(it, settings[it] ?: ModelSettings())) } },
            )
        }
    }

    /**
     * The entry as text, for the readers that want to see it (tests, support).
     *
     * The hand-written template this replaced existed because `trimIndent`
     * re-indents interpolated lines — the version before it put every model at
     * column zero. Encoding a `JsonObject` removes the question; the note is kept
     * here because it is the reason this is not a string builder.
     */
    fun document(
        baseUrl: String,
        modelIds: List<String>,
        settings: Map<String, ModelSettings> = emptyMap(),
        api: String = CustomApi.DEFAULT_ID,
    ): String? = providerObject(baseUrl, modelIds, settings, api)
        ?.let { DOCUMENT_JSON.encodeToString(JsonObject.serializer(), it) + "\n" }

        /**
         * One model of a custom provider, as pi's catalog wants it.
         *
         * [settings] is the *whole* per-model map this app holds — the three
         * controls' output plus the catalogue facts a same-named id inherited —
         * and an id with nothing said about it resolves through pi's own
         * defaults for a definition that names nothing (`contextWindow: 128000`,
         * `maxTokens: 16384`, text-only input). Those numbers are written out
         * rather than left absent so the document is complete on its own;
         * [settings] on top is what the user actually chose.
         *
         * The inherited facts come first and the controls override them: a
         * relay's `claude-sonnet-4-5` is the same model as the catalogue's, and
         * dropping its `cost`, `thinkingLevelMap`, `reasoning` and
         * `samplingParams` on the floor is how a custom endpoint lost the price
         * table and the thinking levels of an id it shares. `api` and `baseUrl`
         * are never inherited — those name the host, and the host is the relay.
         */
        private fun entry(modelId: String, settings: ModelSettings): JsonObject {
            val inherited = settings.inherited.orEmpty()
            return buildJsonObject {
                put("id", modelId)
                put("name", modelId)
                // Every inherited fact the entry does not set itself. Written
                // first so the controls below win where the two overlap.
                for ((key, value) in inherited) {
                    if (key in ENTRY_OWNED_KEYS) continue
                    put(key, value)
                }
                put("reasoning", inherited["reasoning"] ?: JsonPrimitive(true))
                put(
                    "input",
                    buildJsonArray {
                        add(JsonPrimitive("text"))
                        val wantsImages = settings.images
                            ?: (inherited["input"] as? JsonArray)
                                ?.any { (it as? JsonPrimitive)?.contentOrNull == "image" }
                                ?: false
                        if (wantsImages) add(JsonPrimitive("image"))
                    },
                )
                // The user's number, else the catalogue's, else pi's own default
                // for a definition that names neither — which is what an empty
                // field and no inheritance mean here.
                put(
                    "contextWindow",
                    settings.contextWindow
                        ?: (inherited["contextWindow"] as? JsonPrimitive)?.longOrNull
                        ?: 128_000L,
                )
                put(
                    "maxTokens",
                    settings.maxTokens
                        ?: (inherited["maxTokens"] as? JsonPrimitive)?.longOrNull
                        ?: 16_384L,
                )
                // The image ceiling, named rather than inherited: a relay's entry would
                // otherwise keep whatever pi's default is, and a picture this app has
                // already fitted to 3000 would be rescaled back to 2000 on the way in.
                put("inputLimits", imageLimitObject())
            }
        }

        /**
         * Keys this entry always states itself, and therefore never takes from
         * [ModelSettings.inherited]. `api` and `baseUrl` are on the list even
         * though the inherited map should not carry them: a fact source that
         * leaked one would send the relay's traffic to the catalogue's host.
         */
        private val ENTRY_OWNED_KEYS = setOf(
            "id", "name", "input", "contextWindow", "maxTokens", "api", "baseUrl",
            "inputLimits",
        )

    /**
     * 2-space indent, the shape every other pi config file has.
     *
     * `prettyPrintIndent` is still experimental in this version of the
     * serialization library, and opting in here is what keeps every build from
     * warning about it — `WebSearchStore` does the same for the same reason.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private val DOCUMENT_JSON = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

/**
 * How the interface chooses between the light and dark palettes.
 *
 * [code] is what is written to preferences. An absent or unrecognised code
 * resolves to [DEFAULT] — the system's own dark-mode setting — which is also
 * what this app has always done before the choice existed.
 */
enum class ThemeMode(val code: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun fromCode(code: String?): ThemeMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * What the row's `100%` draws, against the library's own sizes.
 *
 * `0.95`, and it is one decision rather than a step in the ladder: at the ladder's own
 * unit — `FontSize.M`, `1.0`, the library's sizes drawn as it wrote them — the prose
 * read one step large next to the apps a phone is held beside, and the size the thumb
 * wanted was the one the row called **95%**. The answer is to re-base the whole ladder
 * rather than to move the default off the fourth step: a fresh install that reads 90%
 * is a percentage nobody can check against anything, while 100% meaning "the size this
 * app thinks text should be" is the one reading that needs no explaining.
 *
 * So every label keeps the round number the ladder was designed with (80, 90, 95, 100,
 * 110, 120, 130) and the whole of it is drawn five per cent smaller than those numbers
 * meant when it was written. The default is therefore exactly what the previous build
 * called 95% — the property `SettingsStoreTest` pins — and the two ends move with it:
 * the smallest step draws 12sp captions at 9.1sp rather than 9.6sp, and the largest
 * draws the 17sp prose at 21sp rather than 22.1sp.
 */
internal const val TEXT_SCALE_BASELINE = 0.95f

/**
 * How large the interface's text is, in seven steps.
 *
 * ## Why a multiplier and not a font size
 *
 * The value scales `LocalDensity`'s `fontScale`, so every `sp` in the app — every
 * row title, every subtitle, the chat transcript, the terminal banner — moves
 * together: nine type styles at seven sizes is 63 decisions, and one multiplier is
 * one. It multiplies the *platform's* own scale rather than replacing it, so a user
 * who has already set 1.3 in Android's display settings keeps their preference and
 * gets the step on top of it.
 *
 * ## The seven, and why the default is the fourth
 *
 * [M] is `1.0` and is what a fresh install has, so the row reads 100% until it is
 * touched. What that `1.0` draws is [TEXT_SCALE_BASELINE] of the library's own sizes
 * rather than the library's sizes themselves — that constant is where the reading that
 * settled it lives, and it is the reason the labels stay the round numbers they are.
 *
 * The range is deliberately narrow — 0.80 to 1.30 — because the layout is a phone's:
 * the measured limits in §9.2 were taken at font scale 1.8 and they are where a
 * row's label starts being clipped, not where it looks best. A step below 0.8 stops
 * being readable at 12sp, which is already the smallest style the app draws.
 */
enum class FontSize(val code: String, val scale: Float) {
    /** 0.80 — 12sp captions at 9.1sp on the re-based ladder. The floor of what stays legible. */
    XXS("xxs", 0.80f),

    /** 0.90 — 15.3sp prose at the re-based ladder's own scale. */
    XS("xs", 0.90f),

    /** 0.95 */
    S("s", 0.95f),

    /** 1.00 — the ladder's unit, and what the row's percentage counts from. */
    M("m", 1.00f),

    /** 1.10 */
    L("l", 1.10f),

    /** 1.20 */
    XL("xl", 1.20f),

    /** 1.30 — the ceiling §9.2's measurements leave room for. */
    XXL("xxl", 1.30f),
    ;

    /** The step as the interface prints it: `100%`, `80%`, `130%`. */
    val percent: Int get() = (scale * 100f).roundToInt()

    companion object {
        /** The fourth of seven, and what an install that never touched the row has. */
        val DEFAULT = M

        fun fromCode(code: String?): FontSize =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/** Immutable snapshot of user configuration. */data class PiSettings(    val provider: PiProvider? = null,
    val modelId: String = "",
    val apiKey: String = "",
    /**
     * The endpoint a provider is served from — a relay, a gateway, or a
     * self-hosted server.
     *
     * For [PiProvider.CUSTOM] this is the only endpoint and is required. For a
     * built-in provider it is an *optional* override: blank means pi's own URL,
     * and a value routes every model of that provider through it
     * (`applyModelsJson` rewrites each model's `baseUrl` from the provider-level
     * one). That is how a proxy in front of DeepSeek or OpenAI is configured
     * without giving up the provider's catalogue, cost table and thinking map.
     */
    val baseUrl: String = "",
    val thinkingLevel: String = "medium",
    /**
     * The thinking levels pi last reported for the model this profile launches.
     *
     * A *cache of pi's answer*, not a setting, and it exists so the thinking chip
     * can show the level the model will actually be on during the second before pi
     * has answered for itself (`thinkingLevelsFor`). Without it the chip showed the
     * unclamped preference and then moved — `medium` on a model that only has
     * `high`, which is the jump this replaces.
     *
     * What it deliberately is *not* is a rewrite of [thinkingLevel]. The app used to
     * persist pi's clamped answer into that field, which made one model's clamping
     * permanent for every model — and since pi answers `["off"]` for a model that
     * does not reason at all, using such a model once left `--thinking off` on the
     * command line for reasoning models afterwards.
     *
     * Empty until a running agent has answered `get_available_thinking_levels`.
     */
    val availableThinkingLevels: List<String> = emptyList(),
    /**
     * The model id [availableThinkingLevels] was pi's answer *for*.
     *
     * The list is per model — `deepseek-v4-pro` offers three levels where
     * `deepseek-flash` offers four — so remembering the levels without remembering
     * which model they were about is how the picker would show the previous model's
     * menu for the second between a switch and pi's answer. A list whose key is not
     * the model in use reads as no memory at all ([rememberedThinkingLevels]), and
     * the picker falls back to pi's seven until pi answers.
     */
    val availableThinkingLevelsFor: String = "",
    /**
     * The workspace a *new* conversation starts in, as an absolute path.
     *
     * Blank means `$HOME/workspace`, which is the directory every install ships with and
     * the one [WorkspaceStore.builtIn] names. It replaced a free-text "working directory"
     * field: the directory is now one of a *list* of workspaces (see [WorkspaceStore]),
     * the composer picks among them, and this is the one the list calls the default.
     */
    val defaultWorkspace: String = "",
    /**
     * Whether new conversations launch the work agent or the plain chat agent.
     *
     * Persisted rather than held in memory because it decides how every later process
     * is spawned. [AgentMode.WORK] is the default so an install that never switches
     * keeps the agent it had; see [AgentMode] for what [AgentMode.CHAT] removes.
     */
    val agentMode: AgentMode = AgentMode.DEFAULT,
    /**
     * What a new chat conversation's network-tools switch starts as.
     *
     * Off is the shipped default and the clean-context promise §17 describes; On
     * and Follow the last are for a reader whose chats usually look something up.
     * A conversation that already has a file keeps its own record in
     * [SessionModeStore], so this field only answers a conversation with no file
     * yet — the same rule [agentMode] follows for the launch mode.
     */
    val chatWebAccessDefault: ChatWebAccessDefault = ChatWebAccessDefault.DEFAULT,
    /**
     * The network-tools switch's value the last time a chat conversation was active.
     *
     * A memory rather than a setting: [ChatWebAccessDefault.LAST] reads it to answer
     * a conversation with no record yet. It is updated whenever a chat conversation
     * is activated or its switch moves, and travels in `pikit_settings` with the
     * rest of the preferences.
     */
    val lastChatWebAccess: Boolean = false,
    /**
     * Whether a conversation's title is generated after its first successful turn.
     *
     * On by default: a drawer of first prompts is hard to scan, and the request is one
     * small call per conversation rather than one per turn. The first answer that
     * settles without an error, and the opening of a saved conversation whose title
     * was never generated, send the first prompt to the same provider and write the
     * returned line with `set_session_name`; a name the reader set is never overwritten
     * (see `TitleGenerator`).
     */
    val chatTitleEnabled: Boolean = true,
    /**
     * Whether the current step's reasoning opens while it streams and folds when it ends.
     *
     * Off by default: a transcript that grows a screenful of reasoning on every tool
     * call is the behaviour the reasoning row was made a disclosure to avoid. On, a
     * step whose thinking is arriving is opened and its block follows the newest token;
     * the block folds when that step finishes, leaving the finished transcript as the
     * one the reader had before.
     */
    val expandThinkingWhileStreaming: Boolean = false,
    /**
     * The chat prompt the single-document editor stored before presets existed.
     *
     * **Read once, as a migration input.** The prompt is a [ChatPreset]'s now
     * ([ChatPresetStore]); an install from before the presets has its text here, and the
     * first preset is made from it so an update cannot silently replace the reader's
     * document with the bare time token. The field is no longer edited anywhere and may
     * be removed once no install is old enough to migrate; the value itself is still a
     * *template* — [CHAT_TIME_PLACEHOLDER] was replaced at launch then and is rendered per
     * preset now. ARCHITECTURE §17 has the preset model.
     */
    val chatSystemPrompt: String = DEFAULT_CHAT_SYSTEM_PROMPT,
    /** Interface language. Persisted here because it is not part of a profile. */
    val language: Lang = Lang.DEFAULT,
    /**
     * Whether a cold start opens a new conversation instead of the last one.
     *
     * On by default: a launch is a new question, and the previous conversation is
     * one tap away in the history. Off restores the remembered session the way
     * every launch used to — see `PiAgentSession.restoreRememberedSession`.
     */
    val openNewOnLaunch: Boolean = true,
    /**
     * Light, dark, or the system's own answer.
     *
     * Persisted here because it is not part of a profile. The neutral palette is still
     * Miuix's own, taken whole from the library; the one hue the reader may move is
     * [accentColor], and `PiKitMiuixTheme` applies it to the accent roles only.
     */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    /**
     * The hue the interface's actions, selections and marks are drawn in.
     *
     * Persisted here for the same reason [themeMode] is. `PiKitMiuixTheme` derives the
     * light and dark accent roles from [AccentColor.seed] and leaves every neutral,
     * error and surface role at the library's own value; [AccentColor.DEFAULT] keeps
     * the library's blue roles exactly, so the default appearance is unchanged.
     */
    val accentColor: AccentColor = AccentColor.DEFAULT,
    /**
     * The interface's text size. Persisted here for the same reason [themeMode] is:
     * it is not part of a profile, and it applies to the app rather than to a launch.
     *
     * It is read once, in `MainActivity`, where it scales the composition's
     * `LocalDensity` — so a change is visible on the page that made it, without a
     * restart and without a single call site asking about it.
     */
    val fontSize: FontSize = FontSize.DEFAULT,
    /**
     * Which launcher icon the home screen and recent tasks show. Persisted here for
     * the same reason [themeMode] is: it is not part of a profile.
     *
     * The home screen is the system's to draw, from the alias [applyLauncherIcon]
     * enables; the recent-tasks card is the task's own icon, written by `MainActivity`
     * from the same value. The app's page in system settings keeps the manifest's
     * fixed application icon and cannot follow this field.
     */
    val launcherIcon: LauncherIcon = LauncherIcon.DEFAULT,
    /**
     * How much the agent may change: [PermissionMode.READ_ONLY], the default
     * [PermissionMode.WORKSPACE_WRITE], or [PermissionMode.FULL_ACCESS].
     *
     * Persisted rather than held in memory so the tier survives a restart, and read by
     * `env/PermissionPolicy` to write the file the extension judges every tool call by —
     * see [PermissionMode] for why the mode is a file rather than an environment
     * variable. The composer's control and this preference are the same value: a change
     * made there takes effect on the next tool call without restarting the agent.
     */
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
    /**
     * Whether the transcript draws an avatar over each message.
     *
     * On by default: a question and its answer are told apart by their side and by
     * the card's fill, and the avatar over each is a *name* for the two speakers —
     * the agent and the reader — rather than a second cue for the side. Off removes
     * both and leaves the transcript as it was before the names existed.
     */
    val showAvatars: Boolean = true,
    /**
     * Whether a history row puts the conversation's date and time at its end.
     *
     * Off by default: the list is a column of the reader's own questions, and the
     * row's first job is the title. The timestamp is a fact about the file rather
     * than part of the question, so it is a choice the reader turns on when they
     * are looking for a conversation by when it happened; the string itself is
     * formatted by `SimpleDateFormat` from the session's `modifiedAt`.
     */
    val showHistoryTime: Boolean = false,
    /**
     * Whether a message in the transcript wears its date and time underneath.
     *
     * On by default, unlike [showHistoryTime]: the transcript is a record read in
     * order, and a message's time is part of what it says. The switcher exists for
     * a reader who wants the page as prose — every message row's furniture goes
     * together, and the setting only hides the stamp, never the message.
     */
    val showMessageTime: Boolean = true,
    /**
     * The agent's avatar: a name, a picture, and the colour of its circle.
     *
     * Three things, and the order they are tried in is in `PiAvatar`: the picture
     * wins, then the speaker's own figure — the app's PiKit mark for the agent, a person
     * for the reader — so a fresh install shows the mark without this field having to
     * hold anything.
     *
     * [aiAvatarName] is **not** drawn inside the disc: it is the name shown beside the
     * avatar in the transcript, which is why it can be longer than a letter and why a
     * blank one means "no name" rather than "the mark".
     *
     * [aiAvatarImage] is an absolute path to a file this app owns — the one
     * `AvatarStore` writes when the reader picks a picture — not a content URI.
     * A URI from the system picker is a grant that expires with the process, so an
     * avatar that pointed at one would be a blank circle on the next launch; the
     * copy in `files/avatars/` is what survives, and it is what a backup carries
     * (the archive's `SETTINGS` category includes that directory).
     *
     * **Null is "follow the accent colour".** A fresh install leaves both colours null,
     * so the discs track [accentColor]; tapping a swatch in the avatar editor stores
     * one of the eight and the disc stops following. The blank string in preferences
     * is that null, which is why the three states ([AvatarColor] chosen, follow, and
     * an old install whose stored value predates this one) stay distinguishable.
     */
    val aiAvatarName: String = "",
    val aiAvatarImage: String = "",
    val aiAvatarColor: AvatarColor? = null,
    /**
     * The reader's avatar, the same three. No picture is the person silhouette rather
     * than the agent's mark, so the two speakers never fall back to the same figure, and
     * [userAvatarName] is drawn to the *left* of it in the transcript — the reader's own
     * side.
     */
    val userAvatarName: String = "",
    val userAvatarImage: String = "",
    val userAvatarColor: AvatarColor? = null,
) {
    /**
     * The agent's disc colour in effect: the reader's choice, or the accent when the
     * avatar has never been given one.
     *
     * The accent's seed *is* one of [AvatarColor]'s eight, so following costs no new
     * drawing rule: the avatar keeps reading a colour and a disc.
     */
    val aiAvatarDisc: AvatarColor get() = aiAvatarColor ?: accentColor.avatar

    /** The reader's disc colour in effect; see [aiAvatarDisc]. */
    val userAvatarDisc: AvatarColor get() = userAvatarColor ?: accentColor.avatar

    /**
     * Whether the agent can actually be started with this.
     *
     * A custom provider needs its endpoint as well as a model, and reporting
     * "configured" without one is how the launch ends up failing with pi saying
     * it does not know the provider — an error that names neither the cause nor
     * the field to fill in. A built-in provider needs its key for the same
     * reason: pi will start, answer every prompt with an auth error, and the
     * chat page has no way to point at the empty field.
     */
    val isConfigured: Boolean
        get() = provider != null &&
            modelId.isNotBlank() &&
            // The *normalised* URL is what the launch writes into `models.json`;
            // a non-blank field that normalises to nothing (`localhost:11434`) is
            // no endpoint at all, and reporting "configured" here is how a custom
            // endpoint reached pi as `Unknown provider "pikit-custom"`.
            (provider !in PiProvider.needsBaseUrl || normalizeApiBaseUrl(baseUrl).isNotEmpty()) &&
            // A relay may sit in front of a gateway that needs no key of its own;
            // every built-in provider does.
            (provider in PiProvider.needsBaseUrl || apiKey.isNotBlank())
}

/**
 * A credential for every built-in provider, so a catalogue refresh visits all of them.
 *
 * ## What this is for
 *
 * `pi update --models` refreshes the catalogue of the providers it considers
 * **configured**, and pi's test for that is whether a credential *resolves*
 * (`Models.refresh` skips a provider whose `resolveRefreshCredential` answers nothing).
 * PiKit holds one provider's key, so a refresh run with the app's own environment
 * refreshes that one provider and silently leaves the other thirty-one stale — which is
 * the whole of the report's "some models cannot be used": the model the user wants is in
 * a provider they have a key for, but not in the profile the agent happens to be launched
 * with.
 *
 * The **value** of the credential plays no part in downloading a catalogue — pi.dev's
 * per-provider catalogue is public, and the key is only what makes pi ask for it. So a
 * placeholder is enough, and that is exactly how `ModelDiscoveryClient` already reads a
 * provider's catalogue before the user has typed a key (`PROBE_API_KEY`).
 *
 * ## Where it must not go
 *
 * Nowhere near the *agent* process. pi's "is this provider configured" test is the same
 * one the model list, the model picker and `get_available_models` are built from, so an
 * agent launched with these would report every one of the ~1100 models of all
 * thirty-two providers as available, and offer providers the user has no account with.
 * The two legitimate callers are the two processes whose whole job is to refresh or read
 * the catalogue and which then exit: [pi.kit.mob.pi.PiProcessLauncher.refreshCatalogue]
 * and the launch path's own scheduled call to it.
 *
 * [PiProvider.CUSTOM] is not included. Its id is PiKit's own, and the credential it
 * authenticates with is the one the user pasted — a placeholder there would make an
 * unconfigured relay look configured to a refresh that cannot read a catalogue for it
 * anyway.
 *
 * The real key wins where a provider appears in both, because the caller applies this
 * map first (`PiLaunchOptions.extraEnv` is written before `apiKeyEnvVar`).
 */
fun catalogueRefreshEnvironment(): Map<String, String> =
    PiProvider.entries
        .filterNot { it in PiProvider.needsBaseUrl }
        .associate { it.envVar to CATALOGUE_PLACEHOLDER_KEY }

/**
 * The placeholder credential `catalogueRefreshEnvironment` hands pi.
 *
 * Deliberately not key-shaped and deliberately not a real prefix (`sk-…`): it must never
 * be mistaken for a secret in a log or in `/proc`, and nothing is ever sent *with* it —
 * it exists only to make pi consider a provider configured long enough to fetch the
 * provider's public catalogue.
 */
private const val CATALOGUE_PLACEHOLDER_KEY = "pikit-catalogue-refresh"

/**
 * The thinking levels remembered for [modelId], or none.
 *
 * pi's answer is a fact about one model, so the memory is only usable for the model
 * it was recorded against. Anything else — a different model, an id the running agent
 * has not reported yet — reads as no memory, and the caller falls back to pi's own
 * seven levels until pi answers, which is what it would do with no memory at all.
 *
 * Pure and Android-free, like the rest of this file's rules, so the keying is
 * checkable without a device: the failure it prevents is a picker showing the
 * *previous* model's menu, which is a wrong list rather than a missing one.
 */
fun PiSettings.rememberedThinkingLevels(modelId: String?): List<String> {
    val key = availableThinkingLevelsFor
    if (key.isBlank() || modelId.isNullOrBlank() || key != modelId) return emptyList()
    return availableThinkingLevels
}

/**
 * Persists [PiSettings]. Backed by `SharedPreferences` rather than DataStore so
 * that reading settings is synchronous — the agent launcher needs them while
 * building a process, and an extra suspend hop there buys nothing.
 *
 * Provider, model and API key are a projection of the active profile, not
 * independent state: they are kept here because
 * [pi.kit.mob.pi.PiLaunchOptions] is built from this data class. The profile
 * set itself lives in [PiConfigFile], which has exactly one writer — this class.
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Held so that the profile store can refresh the snapshot from the
    // constructor, before a StateFlow would exist to be published.
    private val current = AtomicReference(
        PiSettings(
            // Only the values that are not part of a profile come from
            // preferences; the model fields arrive with the active profile.
            thinkingLevel = thinkingLevel(),
            defaultWorkspace = prefs.getString(KEY_WORKSPACE, "").orEmpty(),
            agentMode = AgentMode.fromCode(prefs.getString(KEY_AGENT_MODE, null)),
            chatWebAccessDefault = ChatWebAccessDefault.fromCode(
                prefs.getString(KEY_CHAT_WEB_DEFAULT, null),
            ),
            lastChatWebAccess = prefs.getBoolean(KEY_LAST_CHAT_WEB_ACCESS, false),
            chatTitleEnabled = prefs.getBoolean(KEY_CHAT_TITLE_ENABLED, true),
            expandThinkingWhileStreaming = prefs.getBoolean(KEY_EXPAND_THINKING_WHILE_STREAMING, false),
            chatSystemPrompt = chatSystemPrompt(),
            language = Lang.fromCode(prefs.getString(KEY_LANGUAGE, null)),
            openNewOnLaunch = prefs.getBoolean(KEY_OPEN_NEW_ON_LAUNCH, true),
            themeMode = ThemeMode.fromCode(prefs.getString(KEY_THEME, null)),
            accentColor = AccentColor.fromCode(prefs.getString(KEY_ACCENT, null)),
            fontSize = fontStep(),
            launcherIcon = LauncherIcon.fromCode(prefs.getString(KEY_LAUNCHER_ICON, null)),
            availableThinkingLevels = levelsFromPreference(prefs.getString(KEY_LEVELS, null)),
            availableThinkingLevelsFor = prefs.getString(KEY_LEVELS_FOR, "").orEmpty(),
            permissionMode = PermissionMode.fromCode(prefs.getString(KEY_PERMISSION, null)),
            showAvatars = prefs.getBoolean(KEY_SHOW_AVATARS, true),
            showHistoryTime = prefs.getBoolean(KEY_SHOW_HISTORY_TIME, false),
            showMessageTime = prefs.getBoolean(KEY_SHOW_MESSAGE_TIME, true),
            aiAvatarName = prefs.getString(KEY_AI_AVATAR_NAME, "").orEmpty(),
            aiAvatarImage = prefs.getString(KEY_AI_AVATAR_IMAGE, "").orEmpty(),
            aiAvatarColor = AvatarColor.fromCodeOrNull(prefs.getString(KEY_AI_AVATAR_COLOR, null)),
            userAvatarName = prefs.getString(KEY_USER_AVATAR_NAME, "").orEmpty(),
            userAvatarImage = prefs.getString(KEY_USER_AVATAR_IMAGE, "").orEmpty(),
            userAvatarColor = AvatarColor.fromCodeOrNull(
                prefs.getString(KEY_USER_AVATAR_COLOR, null),
            ),
        ),
    )

    private val _settings = MutableStateFlow(current.get())

    /**
     * The model profiles. Constructed before the flow is published, so the
     * snapshot below is never a settings object with no model in it.
     */
    val profiles: ProfileStore = ProfileStore(
        filesDir = appContext.filesDir,
        legacy = object : LegacySettingsSource {
            override fun providerId(): String? = prefs.getString(KEY_PROVIDER, null)

            override fun modelId(): String = prefs.getString(KEY_MODEL, "").orEmpty()

            override fun apiKey(): String = prefs.getString(KEY_API_KEY, "").orEmpty()

            override fun clearModelKeys() {
                prefs.edit()
                    .remove(KEY_PROVIDER)
                    .remove(KEY_MODEL)
                    .remove(KEY_API_KEY)
                    .apply()
            }
        },
        applyToSettings = { profile ->
            publish(
                provider = profile?.providerEntry,
                modelId = profile?.modelId.orEmpty(),
                apiKey = profile?.apiKey.orEmpty(),
                baseUrl = profile?.baseUrl.orEmpty(),
            )
        },
    ).also { it.applyActive() }

    val settings: StateFlow<PiSettings> = _settings.asStateFlow()

    fun read(): PiSettings = current.get()

    /**
     * Updates the settings.
     *
     * The three profile fields are routed into the active profile so the old
     * flat API keeps working: a caller that only knows about [PiSettings] still
     * changes what the agent is launched with, and the change lands in the
     * config file like every other edit.
     */
    fun update(transform: (PiSettings) -> PiSettings): PiSettings {
        val currentSettings = current.get()
        val next = transform(currentSettings)

        val active = profiles.activeProfile
        if (active != null) {
            val edited = active.copy(
                provider = next.provider?.id.orEmpty(),
                modelId = next.modelId,
                apiKey = next.apiKey,
                baseUrl = next.baseUrl,
            )
            if (edited != active) profiles.upsert(edited)
        }

        prefs.edit()
            .putString(KEY_THINKING, next.thinkingLevel)
            .putString(KEY_LEVELS, next.availableThinkingLevels.joinToString(","))
            .putString(KEY_LEVELS_FOR, next.availableThinkingLevelsFor)
            .putString(KEY_WORKSPACE, next.defaultWorkspace)
            .putString(KEY_AGENT_MODE, next.agentMode.code)
            .putString(KEY_CHAT_WEB_DEFAULT, next.chatWebAccessDefault.code)
            .putBoolean(KEY_LAST_CHAT_WEB_ACCESS, next.lastChatWebAccess)
            .putBoolean(KEY_CHAT_TITLE_ENABLED, next.chatTitleEnabled)
            .putBoolean(
                KEY_EXPAND_THINKING_WHILE_STREAMING,
                next.expandThinkingWhileStreaming,
            )
            .putString(KEY_CHAT_SYSTEM_PROMPT, next.chatSystemPrompt)
            .putString(KEY_PERMISSION, next.permissionMode.code)
            .putString(KEY_LANGUAGE, next.language.code)
            .putBoolean(KEY_OPEN_NEW_ON_LAUNCH, next.openNewOnLaunch)
            .putString(KEY_THEME, next.themeMode.code)
            .putString(KEY_ACCENT, next.accentColor.code)
            .putString(KEY_FONT_SIZE, next.fontSize.code)
            .putString(KEY_LAUNCHER_ICON, next.launcherIcon.code)
                        .putBoolean(KEY_SHOW_AVATARS, next.showAvatars)
            .putBoolean(KEY_SHOW_HISTORY_TIME, next.showHistoryTime)
            .putBoolean(KEY_SHOW_MESSAGE_TIME, next.showMessageTime)
            .putString(KEY_AI_AVATAR_NAME, next.aiAvatarName)
            .putString(KEY_AI_AVATAR_IMAGE, next.aiAvatarImage)
            .putString(KEY_AI_AVATAR_COLOR, next.aiAvatarColor?.code.orEmpty())
            .putString(KEY_USER_AVATAR_NAME, next.userAvatarName)
            .putString(KEY_USER_AVATAR_IMAGE, next.userAvatarImage)
            .putString(KEY_USER_AVATAR_COLOR, next.userAvatarColor?.code.orEmpty())
            .apply()
        // The model fields are read back from the active profile rather than
        // taken from `next`, which keeps one source of truth for them. Every
        // preference field is named below rather than left to `publish`'s
        // defaults, which are the *current* values: one that is forgotten here
        // is written to the file and never published — the control snaps back
        // while the preference says it was set.
        val resolved = publish(
            thinkingLevel = next.thinkingLevel,
            availableThinkingLevels = next.availableThinkingLevels,
            availableThinkingLevelsFor = next.availableThinkingLevelsFor,
            defaultWorkspace = next.defaultWorkspace,
            agentMode = next.agentMode,
            chatWebAccessDefault = next.chatWebAccessDefault,
            lastChatWebAccess = next.lastChatWebAccess,
            chatTitleEnabled = next.chatTitleEnabled,
            expandThinkingWhileStreaming = next.expandThinkingWhileStreaming,
            chatSystemPrompt = next.chatSystemPrompt,
            language = next.language,
            openNewOnLaunch = next.openNewOnLaunch,
            themeMode = next.themeMode,
            accentColor = next.accentColor,
            fontSize = next.fontSize,
            launcherIcon = next.launcherIcon,
            permissionMode = next.permissionMode,
            showAvatars = next.showAvatars,
            showHistoryTime = next.showHistoryTime,
            showMessageTime = next.showMessageTime,
            aiAvatarName = next.aiAvatarName,
            aiAvatarImage = next.aiAvatarImage,
            aiAvatarColor = next.aiAvatarColor,
            userAvatarName = next.userAvatarName,
            userAvatarImage = next.userAvatarImage,
            userAvatarColor = next.userAvatarColor,
        )
        return resolved
    }

    /**
     * The stored text size, with the retired build's rewrite undone once.
     *
     * An unreleased build moved [FontSize.DEFAULT] to `XS` and, to reach installs that
     * already carried a stored `m`, rewrote that step to `xs` and recorded it under
     * [KEY_FONT_SIZE_REBASED]. The ladder is re-based now instead (see
     * [TEXT_SCALE_BASELINE]): `100%` draws what those labels called 95%, the default is
     * the fourth step again, and a stored `m` needs no rewriting at all. What that build
     * did has to be undone, because an install that ran it carries `xs` the reader never
     * chose — so a file holding the flag and that exact step is put back to the default
     * and the flag is dropped. A step the reader did choose on that build (the flag with
     * anything other than `xs`) is left alone.
     *
     * Both readers of the key come through here: the constructor, and [reload] after a
     * restore. A restore applies an archive key by key and cannot take the flag away, so
     * a backup written by the retired build is corrected on the same read as the file it
     * was restored into.
     */
    private fun fontStep(): FontSize {
        val stored = FontSize.fromCode(prefs.getString(KEY_FONT_SIZE, null))
        if (!prefs.getBoolean(KEY_FONT_SIZE_REBASED, false)) return stored
        val editor = prefs.edit().remove(KEY_FONT_SIZE_REBASED)
        val corrected = if (stored == FontSize.XS) {
            editor.putString(KEY_FONT_SIZE, FontSize.DEFAULT.code)
            FontSize.DEFAULT
        } else {
            stored
        }
        editor.apply()
        return corrected
    }

    /** The thinking level stored in preferences, or the default when none is. */
    private fun thinkingLevel(): String =
        prefs.getString(KEY_THINKING, DEFAULT_THINKING_LEVEL).orEmpty().ifBlank { DEFAULT_THINKING_LEVEL }

    /**
     * The chat prompt template, or the current-time default.
     *
     * A stored blank is a real value: chat mode's extension turns it into no system
     * prompt, and the editor's Restore default writes the time token back. Only a
     * preference that was never written takes [DEFAULT_CHAT_SYSTEM_PROMPT].
     */
    private fun chatSystemPrompt(): String =
        prefs.getString(KEY_CHAT_SYSTEM_PROMPT, DEFAULT_CHAT_SYSTEM_PROMPT).orEmpty()

    /**
     * Re-reads preferences and the profile file and publishes the result.
     *
     * The restore path needs this and nothing else does. This store is the only
     * reader of `pikit_settings`, and it read the file once, when the process
     * started; `SharedPreferences` hands the same cached instance to everyone, so a
     * restore that wrote through it would leave every value on screen — the theme,
     * the language, the working directory — exactly as it was at launch.
     *
     * The profile file is re-read through [ProfileStore.reload] rather than opened
     * again here, so the store stays the one reader of its own file, and the order
     * matters: the profiles' `applyToSettings` publishes the model fields by
     * defaulting the rest to what is already published, so the preference fields
     * have to be in place before it runs.
     */
    fun reload(): PiSettings {
        profiles.reload()
        return publish(
            thinkingLevel = thinkingLevel(),
            availableThinkingLevels = levelsFromPreference(prefs.getString(KEY_LEVELS, null)),
            availableThinkingLevelsFor = prefs.getString(KEY_LEVELS_FOR, "").orEmpty(),
            defaultWorkspace = prefs.getString(KEY_WORKSPACE, "").orEmpty(),
            agentMode = AgentMode.fromCode(prefs.getString(KEY_AGENT_MODE, null)),
            chatWebAccessDefault = ChatWebAccessDefault.fromCode(
                prefs.getString(KEY_CHAT_WEB_DEFAULT, null),
            ),
            lastChatWebAccess = prefs.getBoolean(KEY_LAST_CHAT_WEB_ACCESS, false),
            chatTitleEnabled = prefs.getBoolean(KEY_CHAT_TITLE_ENABLED, true),
            expandThinkingWhileStreaming = prefs.getBoolean(KEY_EXPAND_THINKING_WHILE_STREAMING, false),
            chatSystemPrompt = chatSystemPrompt(),
            language = Lang.fromCode(prefs.getString(KEY_LANGUAGE, null)),
            openNewOnLaunch = prefs.getBoolean(KEY_OPEN_NEW_ON_LAUNCH, true),
            themeMode = ThemeMode.fromCode(prefs.getString(KEY_THEME, null)),
            accentColor = AccentColor.fromCode(prefs.getString(KEY_ACCENT, null)),
            fontSize = fontStep(),
            launcherIcon = LauncherIcon.fromCode(prefs.getString(KEY_LAUNCHER_ICON, null)),
            permissionMode = PermissionMode.fromCode(prefs.getString(KEY_PERMISSION, null)),
            showAvatars = prefs.getBoolean(KEY_SHOW_AVATARS, true),
            showHistoryTime = prefs.getBoolean(KEY_SHOW_HISTORY_TIME, false),
            showMessageTime = prefs.getBoolean(KEY_SHOW_MESSAGE_TIME, true),
            aiAvatarName = prefs.getString(KEY_AI_AVATAR_NAME, "").orEmpty(),
            aiAvatarImage = prefs.getString(KEY_AI_AVATAR_IMAGE, "").orEmpty(),
            aiAvatarColor = AvatarColor.fromCodeOrNull(prefs.getString(KEY_AI_AVATAR_COLOR, null)),
            userAvatarName = prefs.getString(KEY_USER_AVATAR_NAME, "").orEmpty(),
            userAvatarImage = prefs.getString(KEY_USER_AVATAR_IMAGE, "").orEmpty(),
            userAvatarColor = AvatarColor.fromCodeOrNull(
                prefs.getString(KEY_USER_AVATAR_COLOR, null),
            ),
        )
    }

    /** Replaces the snapshot and pushes it to observers. */
    private fun publish(
        provider: PiProvider? = current.get().provider,
        modelId: String = current.get().modelId,
        apiKey: String = current.get().apiKey,
        baseUrl: String = current.get().baseUrl,
        thinkingLevel: String = current.get().thinkingLevel,
        availableThinkingLevels: List<String> = current.get().availableThinkingLevels,
        availableThinkingLevelsFor: String = current.get().availableThinkingLevelsFor,
        defaultWorkspace: String = current.get().defaultWorkspace,
        agentMode: AgentMode = current.get().agentMode,
        chatWebAccessDefault: ChatWebAccessDefault = current.get().chatWebAccessDefault,
        lastChatWebAccess: Boolean = current.get().lastChatWebAccess,
        chatTitleEnabled: Boolean = current.get().chatTitleEnabled,
        expandThinkingWhileStreaming: Boolean = current.get().expandThinkingWhileStreaming,
        chatSystemPrompt: String = current.get().chatSystemPrompt,
        language: Lang = current.get().language,
        openNewOnLaunch: Boolean = current.get().openNewOnLaunch,
        themeMode: ThemeMode = current.get().themeMode,
        accentColor: AccentColor = current.get().accentColor,
        fontSize: FontSize = current.get().fontSize,
        launcherIcon: LauncherIcon = current.get().launcherIcon,
        permissionMode: PermissionMode = current.get().permissionMode,
        showAvatars: Boolean = current.get().showAvatars,
        showHistoryTime: Boolean = current.get().showHistoryTime,
        showMessageTime: Boolean = current.get().showMessageTime,
        aiAvatarName: String = current.get().aiAvatarName,
        aiAvatarImage: String = current.get().aiAvatarImage,
        aiAvatarColor: AvatarColor? = current.get().aiAvatarColor,
        userAvatarName: String = current.get().userAvatarName,
        userAvatarImage: String = current.get().userAvatarImage,
        userAvatarColor: AvatarColor? = current.get().userAvatarColor,
    ): PiSettings {
        val next = current.updateAndGet {
            it.copy(
                provider = provider,
                modelId = modelId,
                apiKey = apiKey,
                baseUrl = baseUrl,
                thinkingLevel = thinkingLevel,
                availableThinkingLevels = availableThinkingLevels,
                availableThinkingLevelsFor = availableThinkingLevelsFor,
                defaultWorkspace = defaultWorkspace,
                agentMode = agentMode,
                chatWebAccessDefault = chatWebAccessDefault,
                lastChatWebAccess = lastChatWebAccess,
                chatTitleEnabled = chatTitleEnabled,
                expandThinkingWhileStreaming = expandThinkingWhileStreaming,
                chatSystemPrompt = chatSystemPrompt,
                language = language,
                openNewOnLaunch = openNewOnLaunch,
                themeMode = themeMode,
                accentColor = accentColor,
                fontSize = fontSize,
                launcherIcon = launcherIcon,
                permissionMode = permissionMode,
                showAvatars = showAvatars,
                showHistoryTime = showHistoryTime,
                showMessageTime = showMessageTime,
                aiAvatarName = aiAvatarName,
                aiAvatarImage = aiAvatarImage,
                aiAvatarColor = aiAvatarColor,
                userAvatarName = userAvatarName,
                userAvatarImage = userAvatarImage,
                userAvatarColor = userAvatarColor,
            )
        }
        _settings.value = next
        return next
    }

    companion object {
        /**
         * The default workspace, read straight from preferences.
         *
         * A companion function rather than a field on a `SettingsStore` instance
         * because its callers — `WorkspaceStore`, which answers the composer's chip on
         * every frame, and `ShellEnvironment`, which builds the environment for every
         * process PiKit spawns — must not construct a store to ask: `SettingsStore`'s
         * constructor reads the profile file and applies the active profile, which is
         * work with side effects and nothing to do with the question. The preference
         * name and the key stay here, so there is still one writer and one place they
         * are spelled.
         */
        fun defaultWorkspace(context: Context): String =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_WORKSPACE, "").orEmpty()

        /**
         * Clears the default when it names [path], for `WorkspaceStore.remove`.
         *
         * Removing a workspace that is the default has to leave a default behind: the
         * alternative is a preference pointing at a directory the list no longer offers,
         * which every reader then resolves by its own fallback rule.
         */
        fun clearDefaultWorkspace(context: Context, path: String) {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_WORKSPACE, "").orEmpty() != path) return
            prefs.edit().remove(KEY_WORKSPACE).apply()
        }

        /**
         * The permission tier in force, read straight from preferences.
         *
         * Same reason as [defaultWorkspace]: `env/PermissionPolicy` writes the file the
         * agent's extension reads, and it has no business constructing a store (and with
         * it the profile file) to answer one enum.
         */
        fun permissionMode(context: Context): PermissionMode =
            PermissionMode.fromCode(
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_PERMISSION, null),
            )

        private const val PREFS = "pikit_settings"

        private const val KEY_PROVIDER = "provider"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_THINKING = "thinking_level"

        /**
         * What pi is asked for when nothing has been chosen.
         *
         * Named rather than written at both call sites: the constructor and
         * [reload] both have to answer this, and two spellings of the default is a
         * restore that changes the thinking level by accident.
         */
        private const val DEFAULT_THINKING_LEVEL = "medium"

        private const val KEY_LEVELS = "thinking_levels"
        private const val KEY_LEVELS_FOR = "thinking_levels_for"
        private const val KEY_WORKSPACE = "working_dir"

        /** [AgentMode.code]; absent means [AgentMode.DEFAULT]. */
        private const val KEY_AGENT_MODE = "agent_mode"

        /** [ChatWebAccessDefault.code]; absent means [ChatWebAccessDefault.DEFAULT]. */
        private const val KEY_CHAT_WEB_DEFAULT = "chat_web_default"

        /** The last chat conversation's network-tools switch; see [PiSettings.lastChatWebAccess]. */
        private const val KEY_LAST_CHAT_WEB_ACCESS = "last_chat_web_access"
        private const val KEY_CHAT_TITLE_ENABLED = "chat_title_enabled"

        /** Whether the streaming step's reasoning opens itself; see [PiSettings]. */
        private const val KEY_EXPAND_THINKING_WHILE_STREAMING = "expand_thinking_while_streaming"

        /** The chat prompt template; see [PiSettings.chatSystemPrompt]. */
        private const val KEY_CHAT_SYSTEM_PROMPT = "chat_system_prompt"

        private const val KEY_LANGUAGE = "language"

        /** True unless the user asked for a cold start to continue the last talk. */
        private const val KEY_OPEN_NEW_ON_LAUNCH = "open_new_on_launch"

        /** [ThemeMode.code]; absent means [ThemeMode.DEFAULT]. */
        private const val KEY_THEME = "theme_mode"

        /** [AccentColor.code]; absent means [AccentColor.DEFAULT]. */
        private const val KEY_ACCENT = "accent_color"

        /** [FontSize.code]; absent means [FontSize.DEFAULT] — the fourth of seven. */
        private const val KEY_FONT_SIZE = "font_size"

        /**
         * A mark one retired build left when it rewrote a stored text size.
         *
         * That build moved the default a step down and wrote `xs` over a stored `m`, so
         * that it would reach installs which had already persisted every key. The
         * correction is gone — the ladder is re-based instead ([TEXT_SCALE_BASELINE]) —
         * and this flag is now only read to undo what the build did, once, in
         * `fontStep`. It is never written again.
         */
        private const val KEY_FONT_SIZE_REBASED = "font_size_rebased"

        /** [LauncherIcon.code]; absent means [LauncherIcon.DEFAULT]. */
        private const val KEY_LAUNCHER_ICON = "launcher_icon"

        /** True unless the user switched the tool-call guard off; see [PiSettings]. */
        private const val KEY_PERMISSION = "permission_mode"

        /** The two avatars' own fields; see [PiSettings.aiAvatarName]. */
        private const val KEY_AI_AVATAR_IMAGE = "ai_avatar_image"
        private const val KEY_USER_AVATAR_IMAGE = "user_avatar_image"

        /** True unless the user switched the transcript's avatars off; see [PiSettings]. */
        private const val KEY_SHOW_AVATARS = "show_avatars"

        /** Whether a history row shows its date and time; off unless chosen; see [PiSettings]. */
        private const val KEY_SHOW_HISTORY_TIME = "show_history_time"

        /** Whether a transcript message shows its date and time; on unless switched off. */
        private const val KEY_SHOW_MESSAGE_TIME = "show_message_time"

        /**
         * The two avatars' labels (blank = the built-in mark) and their colours.
         *
         * A blank colour is the "follow the accent" state, so an old install whose
         * stored value predates the accent keeps that value and a fresh one follows.
         */
        private const val KEY_AI_AVATAR_NAME = "ai_avatar_name"
        private const val KEY_AI_AVATAR_COLOR = "ai_avatar_color"
        private const val KEY_USER_AVATAR_NAME = "user_avatar_name"
        private const val KEY_USER_AVATAR_COLOR = "user_avatar_color"

        /**
         * The remembered level list, comma-separated.
         *
         * A string preference rather than a string *set*: the order is pi's own and
         * it is what the picker draws, and `getStringSet` would hand it back in
         * whatever order the implementation felt like — the one thing this list
         * cannot be re-sorted into, because [pi.kit.mob.pi.clampThinkingLevel] walks
         * it in pi's order.
         */
        fun levelsFromPreference(stored: String?): List<String> =
            stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
