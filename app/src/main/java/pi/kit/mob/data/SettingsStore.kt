package pi.kit.mob.data

import android.content.Context
import pi.kit.mob.locales.Lang
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

/**
 * Provider ids pi accepts, mapped to the environment variable each one reads.
 *
 * Passing credentials through the environment is deliberate: `--api-key` is
 * visible in the process command line to anything that can read `/proc`, and pi
 * additionally rejects it unless a model is also given on the command line.
 * (PiKit passes both — see [PiProcessLauncher] — because a provider registered through
 * `models.json` does not resolve the environment variable at all.)
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
    NVIDIA("nvidia", "NVIDIA_API_KEY", "NVIDIA"),
    TOGETHER("together", "TOGETHER_API_KEY", "Together"),
    FIREWORKS("fireworks", "FIREWORKS_API_KEY", "Fireworks"),
    BASETEN("baseten", "BASETEN_API_KEY", "Baseten"),
    HUGGINGFACE("huggingface", "HF_TOKEN", "Hugging Face"),
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
 * The base URL PiKit hands pi, ready for an OpenAI-compatible request path.
 *
 * Trailing slashes are trimmed, and `/v1` is appended when the URL names a bare
 * host. That second half is not politeness: pi's `openai-completions` path posts
 * to `<baseUrl>/chat/completions`, every documented example in pi's own
 * `docs/models.md` carries `/v1`, and a relay reached at `https://host` instead
 * of `https://host/v1` fails as three connection retries — the report of severe
 * lag and frequent disconnects on a custom endpoint that was one path segment
 * short. A URL that already names a path is left alone: relays differ on where
 * the version segment belongs, and `https://gateway.example.com/openai` is a
 * real shape this must not rewrite.
 */
fun normalizeApiBaseUrl(raw: String): String {
    val base = raw.trim().trimEnd('/')
    if (base.isEmpty()) return base
    val uri = runCatching { java.net.URI(base) }.getOrNull() ?: return ""
    // Anything that is not an absolute http(s) URL is refused rather than
    // completed: `localhost:11434` has a scheme-like colon and no host from
    // `URI`'s point of view, and writing it into `models.json` produces a
    // provider that cannot be reached and an error that names neither the field
    // nor the missing scheme.
    val scheme = uri.scheme?.lowercase() ?: return ""
    if (scheme != "http" && scheme != "https") return ""
    if (uri.host.isNullOrBlank()) return ""
    val path = uri.path ?: ""
    return if (path.isEmpty() || path == "/") "$base/v1" else base
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
 * `api` is pinned to `openai-completions` because that is what relays speak; a
 * custom Anthropic-compatible endpoint is a real case but a rarer one, and
 * guessing wrong produces a stream that fails to parse rather than a clear error,
 * so it is not guessed at.
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

    /** The provider name written into `models.json`, and passed to `--provider`. */
    const val PROVIDER_ID = "pikit-custom"

    /** The environment variable the generated `apiKey` references. */
    const val API_KEY_ENV = "PIKIT_API_KEY"

    /** Where pi looks for it, relative to `$HOME`. */
    const val MODELS_FILE_RELATIVE = ".pi/agent/models.json"

    /**
     * The document pi expects, or null when there is nothing to register.
     *
     * A trailing slash is trimmed. A bare host also gains `/v1` — see
     * [normalizeApiBaseUrl], which is what turns `https://relay.example.com` into
     * the path pi's OpenAI client actually posts to. A user who copies
     * `https://example.com/v1` from a provider's dashboard is left alone.
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
    ): JsonObject? {
        val base = normalizeApiBaseUrl(baseUrl)
        val models = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (base.isEmpty() || models.isEmpty()) return null

        // Data rather than text: this entry is now *merged* into whatever the user
        // already has in `models.json`, which pi's own docs invite them to edit by
        // hand, so the document is read, this one provider is upserted and every
        // other provider and key is carried over untouched.
        return buildJsonObject {
            put("name", "PiKit custom endpoint")
            put("baseUrl", base)
            put("api", "openai-completions")
            put("apiKey", API_KEY_ENV)
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
    ): String? = providerObject(baseUrl, modelIds, settings)
        ?.let { DOCUMENT_JSON.encodeToString(JsonObject.serializer(), it) + "\n" }

        /**
         * One model of a custom provider, as pi's catalog wants it.
         *
         * [settings] is the *whole* per-model map this app holds — the three
         * controls' output — and an id with nothing said about it resolves
         * through pi's own defaults for a definition that names nothing
         * (`contextWindow: 128000`, `maxTokens: 16384`, text-only input). Those
         * numbers are written out rather than left absent so the document is
         * complete on its own; [settings] on top is what the user actually chose.
         */
        private fun entry(modelId: String, settings: ModelSettings): JsonObject = buildJsonObject {
            put("id", modelId)
            put("name", modelId)
            put("reasoning", true)
            put(
                "input",
                buildJsonArray {
                    add(JsonPrimitive("text"))
                    if (settings.images == true) add(JsonPrimitive("image"))
                },
            )
            // pi's own defaults for a definition that names neither, which is what an empty
            // field means here: there is no fallback model to inherit them from.
            put("contextWindow", settings.contextWindow ?: 128_000L)
            put("maxTokens", settings.maxTokens ?: 16_384L)
        }

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

/** Immutable snapshot of user configuration. */
data class PiSettings(
    val provider: PiProvider? = null,
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
    /** Agent working directory; `$HOME` when blank. */
    val workingDir: String = "",
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
    /** Interface theme. Persisted here because it is not part of a profile. */
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    /**
     * Which launcher icon the home screen shows. Persisted here for the same
     * reason [themeMode] is: it is not part of a profile.
     *
     * The app itself does not draw this — the system does, from the alias
     * [applyLauncherIcon] enables — so the only thing this field decides is which
     * alias it turns on.
     */
    val launcherIcon: LauncherIcon = LauncherIcon.DEFAULT,
    /**
     * Whether PiKit's tool-call guard is in force.
     *
     * An environment fact rather than a preference the app acts on: the value is
     * published to every process PiKit spawns (`ShellEnvironment`, as
     * `SafetyGuard.ENV_VAR`) and read once by the extension when pi loads it. On by
     * default, and the switch that turns it off asks first — see the storage page.
     */
    val safetyExtension: Boolean = true,
) {
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
            (provider !in PiProvider.needsBaseUrl || baseUrl.isNotBlank()) &&
            // A relay may sit in front of a gateway that needs no key of its own;
            // every built-in provider does.
            (provider in PiProvider.needsBaseUrl || apiKey.isNotBlank())
}

/**
 * The active profile's key as the environment variable pi reads for that provider.
 *
 * The same pair the agent process is launched with (`PiProcessLauncher.launch`), so a
 * `pi` the user types in the terminal tab authenticates exactly like the agent does.
 * Android-free on purpose: it is the rule that decides whether a hand-run `pi` can
 * find an *available* model at all, and that is checkable on the JVM.
 */
fun apiKeyEnvironment(settings: PiSettings): Map<String, String> {
    val provider = settings.provider ?: return emptyMap()
    val key = settings.apiKey.trim()
    return if (key.isEmpty()) emptyMap() else mapOf(provider.envVar to key)
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
            workingDir = prefs.getString(KEY_WORKDIR, "").orEmpty(),
            language = Lang.fromCode(prefs.getString(KEY_LANGUAGE, null)),
            openNewOnLaunch = prefs.getBoolean(KEY_OPEN_NEW_ON_LAUNCH, true),
            themeMode = ThemeMode.fromCode(prefs.getString(KEY_THEME, null)),
            launcherIcon = LauncherIcon.fromCode(prefs.getString(KEY_LAUNCHER_ICON, null)),
            availableThinkingLevels = levelsFromPreference(prefs.getString(KEY_LEVELS, null)),
            availableThinkingLevelsFor = prefs.getString(KEY_LEVELS_FOR, "").orEmpty(),
            safetyExtension = prefs.getBoolean(KEY_SAFETY, true),
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
            .putString(KEY_WORKDIR, next.workingDir)
            .putString(KEY_LANGUAGE, next.language.code)
            .putBoolean(KEY_OPEN_NEW_ON_LAUNCH, next.openNewOnLaunch)
            .putString(KEY_THEME, next.themeMode.code)
            .putString(KEY_LAUNCHER_ICON, next.launcherIcon.code)
            .putBoolean(KEY_SAFETY, next.safetyExtension)
            .apply()
        // The model fields are read back from the active profile rather than
        // taken from `next`, which keeps one source of truth for them.
        val resolved = publish(
            thinkingLevel = next.thinkingLevel,
            availableThinkingLevels = next.availableThinkingLevels,
            availableThinkingLevelsFor = next.availableThinkingLevelsFor,
            workingDir = next.workingDir,
            language = next.language,
            openNewOnLaunch = next.openNewOnLaunch,
            themeMode = next.themeMode,
            launcherIcon = next.launcherIcon,
            safetyExtension = next.safetyExtension,
        )
        return resolved
    }

    /** The thinking level stored in preferences, or the default when none is. */
    private fun thinkingLevel(): String =
        prefs.getString(KEY_THINKING, DEFAULT_THINKING_LEVEL).orEmpty().ifBlank { DEFAULT_THINKING_LEVEL }

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
            workingDir = prefs.getString(KEY_WORKDIR, "").orEmpty(),
            language = Lang.fromCode(prefs.getString(KEY_LANGUAGE, null)),
            openNewOnLaunch = prefs.getBoolean(KEY_OPEN_NEW_ON_LAUNCH, true),
            themeMode = ThemeMode.fromCode(prefs.getString(KEY_THEME, null)),
            launcherIcon = LauncherIcon.fromCode(prefs.getString(KEY_LAUNCHER_ICON, null)),
            safetyExtension = prefs.getBoolean(KEY_SAFETY, true),
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
        workingDir: String = current.get().workingDir,
        language: Lang = current.get().language,
        openNewOnLaunch: Boolean = current.get().openNewOnLaunch,
        themeMode: ThemeMode = current.get().themeMode,
        launcherIcon: LauncherIcon = current.get().launcherIcon,
        safetyExtension: Boolean = current.get().safetyExtension,
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
                workingDir = workingDir,
                language = language,
                openNewOnLaunch = openNewOnLaunch,
                themeMode = themeMode,
                launcherIcon = launcherIcon,
                safetyExtension = safetyExtension,
            )
        }
        _settings.value = next
        return next
    }

    companion object {
        /**
         * Whether the tool-call guard is in force, read straight from preferences.
         *
         * A companion function rather than a field on a `SettingsStore` instance
         * because its one caller outside this class is `ShellEnvironment`, which
         * builds the environment for every process PiKit spawns and must not
         * construct a store to do it: `SettingsStore`'s constructor reads the profile
         * file and applies the active profile, which is work with side effects and
         * nothing to do with the question. The preference name and the key stay here,
         * so there is still one writer and one place they are spelled.
         */
        fun safetyExtension(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SAFETY, true)

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
        private const val KEY_WORKDIR = "working_dir"
        private const val KEY_LANGUAGE = "language"

        /** True unless the user asked for a cold start to continue the last talk. */
        private const val KEY_OPEN_NEW_ON_LAUNCH = "open_new_on_launch"

        /** [ThemeMode.code]; absent means [ThemeMode.DEFAULT]. */
        private const val KEY_THEME = "theme_mode"

        /** [LauncherIcon.code]; absent means [LauncherIcon.DEFAULT]. */
        private const val KEY_LAUNCHER_ICON = "launcher_icon"

        /** True unless the user switched the tool-call guard off; see [PiSettings]. */
        private const val KEY_SAFETY = "safety_extension"

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
