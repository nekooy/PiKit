package pi.kit.mob.pi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pi.kit.mob.env.TermuxEnv
import java.io.File

/**
 * One service the extension can search or fetch through.
 *
 * The list is the extension's own [tools/pikit-web.ts] `PROVIDERS` table, and the
 * settings page is generated from it: every provider's fields are drawn from
 * [fields], so a provider the extension supports cannot be missing from the page.
 * Product names are not translated — `DeepSeek` is a brand, not a sentence.
 */
data class WebProvider(
    val id: String,
    val label: String,
    val fields: List<WebProviderField>,
    val search: Boolean,
    val fetch: Boolean = false,
    /** A key is useful but not required (Exa Free has none; Jina's free tier works). */
    val keyOptional: Boolean = false,
    /** The provider cannot be used until [WebProviderField.BASE_URL] is set. */
    val baseUrlRequired: Boolean = false,
)

enum class WebProviderField { API_KEY, BASE_URL, ACCOUNT_ID, MODEL }

/** One provider's stored credentials and endpoint. */
data class WebProviderConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val accountId: String = "",
    val model: String = "",
) {
    fun isEmpty(): Boolean = apiKey.isBlank() && baseUrl.isBlank() && accountId.isBlank() && model.isBlank()
}

/**
 * The web extension's configuration, as the settings page and the file see it.
 *
 * The file is `$HOME/.pikit/web.json`, written by [WebSearchStore] and read by
 * `tools/pikit-web.ts` on every tool call. One file, one writer: the page never
 * keeps a second copy in SharedPreferences, so the thing the reader edits and
 * the thing the agent reads cannot disagree.
 */
data class WebSearchSettings(
    val enabled: Boolean = true,
    /** `auto` lets the extension pick; otherwise one of [WebProvider.id]. */
    val searchProvider: String = PROVIDER_AUTO,
    val maxResults: Int = DEFAULT_MAX_RESULTS,
    val searchTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** `auto` means direct HTTP, the keyless, cookie-less fetcher. */
    val fetchProvider: String = FETCH_AUTO,
    val maxChars: Int = DEFAULT_MAX_CHARS,
    val fetchTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val providers: Map<String, WebProviderConfig> = emptyMap(),
) {
    fun configOf(id: String): WebProviderConfig = providers[id] ?: WebProviderConfig()

    /** Whether the settings page draws this provider as configured. */
    fun configured(id: String): Boolean {
        val provider = WEB_PROVIDERS.firstOrNull { it.id == id } ?: return false
        val config = configOf(id)
        if (provider.fields.contains(WebProviderField.API_KEY) && !provider.keyOptional && config.apiKey.isBlank()) return false
        if (provider.baseUrlRequired && config.baseUrl.isBlank()) return false
        if (provider.fields.contains(WebProviderField.ACCOUNT_ID) && config.accountId.isBlank()) return false
        return true
    }

    companion object {
        /** Relative to `$HOME`; `PIKIT_WEB_CONFIG` points the extension at it. */
        const val FILE_NAME = ".pikit/web.json"

        const val PROVIDER_AUTO = "auto"
        const val FETCH_AUTO = "auto"
        const val FETCH_DIRECT = "direct"

        const val DEFAULT_MAX_RESULTS = 8
        const val MAX_RESULTS = 50
        const val DEFAULT_MAX_CHARS = 20_000
        const val MAX_CHARS = 500_000
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 600

        /** The full table, in the order the pickers show it. */
        val WEB_PROVIDERS: List<WebProvider> = listOf(
            WebProvider("deepseek", "DeepSeek", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true, fetch = true),
            WebProvider("zai", "Z.ai", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true),
            WebProvider("exa", "Exa", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true, fetch = true),
            WebProvider("exa-free", "Exa Free", emptyList(), search = true),
            WebProvider("duckduckgo", "DuckDuckGo", emptyList(), search = true),
            WebProvider("tavily", "Tavily", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true, fetch = true),
            WebProvider("ollama", "Ollama", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true, fetch = true),
            WebProvider("brave", "Brave", listOf(WebProviderField.API_KEY), search = true),
            WebProvider("jina", "Jina", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true, fetch = true, keyOptional = true),
            WebProvider("openai", "OpenAI", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL, WebProviderField.MODEL), search = true),
            WebProvider("gemini", "Gemini", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL, WebProviderField.MODEL), search = true),
            WebProvider("kimi", "Kimi", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true),
            WebProvider("xai", "xAI", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL, WebProviderField.MODEL), search = true),
            WebProvider("searxng", "SearXNG", listOf(WebProviderField.BASE_URL), search = true, baseUrlRequired = true),
            WebProvider("cloudflare", "Cloudflare", listOf(WebProviderField.API_KEY, WebProviderField.ACCOUNT_ID), search = true),
            WebProvider("searchapi", "SearchAPI", listOf(WebProviderField.API_KEY, WebProviderField.BASE_URL), search = true),
        )

        /** Search providers, plus the automatic row the picker shows first. */
        val SEARCH_PROVIDERS: List<WebProvider> = WEB_PROVIDERS.filter { it.search }

        /** Fetch providers the extension implements, including direct HTTP. */
        val FETCH_PROVIDERS: List<String> = listOf("direct") + WEB_PROVIDERS.filter { it.fetch }.map { it.id }

        fun provider(id: String): WebProvider? = WEB_PROVIDERS.firstOrNull { it.id == id }
    }
}

// ------------------------------------------------------------------ document

@OptIn(ExperimentalSerializationApi::class)
private val JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private const val VERSION = 1
private const val KEY_VERSION = "version"
private const val KEY_ENABLED = "enabled"
private const val KEY_SEARCH = "search"
private const val KEY_FETCH = "fetch"
private const val KEY_PROVIDER = "provider"
private const val KEY_MAX_RESULTS = "maxResults"
private const val KEY_MAX_CHARS = "maxChars"
private const val KEY_TIMEOUT = "timeoutSeconds"
private const val KEY_PROVIDERS = "providers"
private const val KEY_API_KEY = "apiKey"
private const val KEY_BASE_URL = "baseUrl"
private const val KEY_ACCOUNT_ID = "accountId"
private const val KEY_MODEL = "model"

/** Encodes [settings] as the strict JSON document the extension reads. */
internal fun encodeWebConfig(settings: WebSearchSettings): String {
    val document = buildJsonObject {
        put(KEY_VERSION, VERSION)
        put(KEY_ENABLED, settings.enabled)
        putJsonObject(KEY_SEARCH) {
            put(KEY_PROVIDER, settings.searchProvider)
            put(KEY_MAX_RESULTS, settings.maxResults)
            put(KEY_TIMEOUT, settings.searchTimeoutSeconds)
        }
        putJsonObject(KEY_FETCH) {
            put(KEY_PROVIDER, settings.fetchProvider)
            put(KEY_MAX_CHARS, settings.maxChars)
            put(KEY_TIMEOUT, settings.fetchTimeoutSeconds)
        }
        putJsonObject(KEY_PROVIDERS) {
            settings.providers
                .filterValues { !it.isEmpty() }
                .toSortedMap()
                .forEach { (id, config) ->
                    putJsonObject(id) {
                        if (config.apiKey.isNotBlank()) put(KEY_API_KEY, config.apiKey)
                        if (config.baseUrl.isNotBlank()) put(KEY_BASE_URL, config.baseUrl)
                        if (config.accountId.isNotBlank()) put(KEY_ACCOUNT_ID, config.accountId)
                        if (config.model.isNotBlank()) put(KEY_MODEL, config.model)
                    }
                }
        }
    }
    return JSON.encodeToString(JsonObject.serializer(), document) + "\n"
}

/**
 * Reads [text] as the document [encodeWebConfig] writes.
 *
 * A missing key takes its default; a value of the wrong type makes the whole
 * document unreadable rather than silently partial, because the page would
 * otherwise show a default over a value the extension is actually reading.
 */
internal fun decodeWebConfig(text: String): WebSearchSettings? {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    fun objectAt(key: String): JsonObject? = when (val value = root[key]) {
        null -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> null
    }
    val search = objectAt(KEY_SEARCH) ?: return null
    val fetch = objectAt(KEY_FETCH) ?: return null
    val providersNode = objectAt(KEY_PROVIDERS) ?: return null
    val enabled = root[KEY_ENABLED]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true
    fun string(node: JsonObject, key: String): String? = when (val value = node[key]) {
        null -> ""
        is JsonPrimitive -> if (value.isString) value.contentOrNull ?: return null else return null
        else -> return null
    }
    fun integer(node: JsonObject, key: String, fallback: Int): Int? = when (val value = node[key]) {
        null -> fallback
        is JsonPrimitive -> if (value.isString) return null else value.intOrNull ?: return null
        else -> return null
    }
    val providers = mutableMapOf<String, WebProviderConfig>()
    for ((id, value) in providersNode) {
        val node = value as? JsonObject ?: return null
        val config = WebProviderConfig(
            apiKey = string(node, KEY_API_KEY) ?: return null,
            baseUrl = string(node, KEY_BASE_URL) ?: return null,
            accountId = string(node, KEY_ACCOUNT_ID) ?: return null,
            model = string(node, KEY_MODEL) ?: return null,
        )
        if (!config.isEmpty()) providers[id] = config
    }
    return WebSearchSettings(
        enabled = enabled,
        searchProvider = string(search, KEY_PROVIDER).orEmpty().ifBlank { WebSearchSettings.PROVIDER_AUTO },
        maxResults = integer(search, KEY_MAX_RESULTS, WebSearchSettings.DEFAULT_MAX_RESULTS) ?: return null,
        searchTimeoutSeconds = integer(search, KEY_TIMEOUT, WebSearchSettings.DEFAULT_TIMEOUT_SECONDS) ?: return null,
        fetchProvider = string(fetch, KEY_PROVIDER).orEmpty().ifBlank { WebSearchSettings.FETCH_AUTO },
        maxChars = integer(fetch, KEY_MAX_CHARS, WebSearchSettings.DEFAULT_MAX_CHARS) ?: return null,
        fetchTimeoutSeconds = integer(fetch, KEY_TIMEOUT, WebSearchSettings.DEFAULT_TIMEOUT_SECONDS) ?: return null,
        providers = providers,
    )
}

// ------------------------------------------------------------------ store

/**
 * The only writer of [WebSearchSettings.FILE_NAME].
 *
 * Reads and writes run on [Dispatchers.IO] and are serialised through one mutex;
 * [update]'s transform runs inside the lock so a keystroke that arrives before
 * the first read cannot be applied to the placeholder defaults and then written
 * back over the user's keys. A file that does not parse is never overwritten:
 * [unreadable] is true and the page offers the explicit reset instead.
 */
class WebSearchStore(
    private val env: TermuxEnv,
    private val scope: CoroutineScope,
) {
    private val file: File get() = File(env.home, WebSearchSettings.FILE_NAME)

    private val _settings = MutableStateFlow(WebSearchSettings())
    val settings: StateFlow<WebSearchSettings> = _settings.asStateFlow()

    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()

    /** True when this runtime image carries the extension the app launches. */
    val available: Boolean get() = env.webExtension?.entryPoint?.isFile == true

    private val lock = Mutex()
    private var loaded = false

    init {
        reload()
    }

    /** Re-reads the file after [ensureDocument] has had its chance to seed it. */
    fun reload() {
        scope.launch {
            lock.withLock {
                withContext(Dispatchers.IO) { ensureDocument() }
                val loaded = withContext(Dispatchers.IO) { load() }
                _settings.value = loaded.settings
                _unreadable.value = loaded.unreadable
                this@WebSearchStore.loaded = true
            }
        }
    }

    /** Applies [transform] to the current settings and writes the result. */
    fun update(transform: (WebSearchSettings) -> WebSearchSettings) {
        scope.launch {
            lock.withLock {
                // A tap can arrive before the init read has run. Applying the transform
                // to the placeholder defaults and writing them back would erase every
                // key the file already held, so the first update reads first.
                if (!loaded) {
                    withContext(Dispatchers.IO) { ensureDocument() }
                    val current = withContext(Dispatchers.IO) { load() }
                    _settings.value = current.settings
                    _unreadable.value = current.unreadable
                    loaded = true
                }
                val next = transform(_settings.value)
                _settings.value = next
                withContext(Dispatchers.IO) { persist(next) }
            }
        }
    }

    /** Changes one provider's stored values, keeping every other provider intact. */
    fun updateProvider(id: String, transform: (WebProviderConfig) -> WebProviderConfig) {
        update { settings ->
            val next = transform(settings.configOf(id))
            val providers = settings.providers.toMutableMap()
            if (next.isEmpty()) providers.remove(id) else providers[id] = next
            settings.copy(providers = providers)
        }
    }

    /**
     * Creates the file with the defaults when it does not exist.
     *
     * Called before every launch, because the extension reads the file when pi
     * starts and the page may never be opened. A file that exists is left alone:
     * an invalid one is the page's problem and the reset is its way out, not a
     * silent overwrite at startup.
     */
    fun ensureDocument() {
        if (file.isFile) return
        writeFile(encodeWebConfig(WebSearchSettings()))
    }

    /** Replaces the whole document with the fresh-install default. */
    suspend fun restoreDefaults(): Result<Unit> = lock.withLock {
        val written = withContext(Dispatchers.IO) { writeFile(encodeWebConfig(WebSearchSettings())) }
        if (!written) {
            Result.failure(IllegalStateException("${WebSearchSettings.FILE_NAME} could not be written"))
        } else {
            _settings.value = WebSearchSettings()
            _unreadable.value = false
            Result.success(Unit)
        }
    }

    private class Loaded(val settings: WebSearchSettings, val unreadable: Boolean)

    private fun load(): Loaded {
        if (!file.isFile) return Loaded(WebSearchSettings(), unreadable = false)
        val text = runCatching { file.readText() }.getOrNull()
            ?: return Loaded(WebSearchSettings(), unreadable = true)
        if (text.isBlank()) return Loaded(WebSearchSettings(), unreadable = false)
        val decoded = decodeWebConfig(text) ?: return Loaded(WebSearchSettings(), unreadable = true)
        return Loaded(decoded, unreadable = false)
    }

    private fun persist(next: WebSearchSettings) {
        val existing = if (file.isFile) runCatching { file.readText() }.getOrNull() else ""
        if (existing == null) {
            _unreadable.value = true
            return
        }
        if (existing.isNotBlank() && decodeWebConfig(existing) == null) {
            _unreadable.value = true
            return
        }
        _unreadable.value = false
        val encoded = encodeWebConfig(next)
        if (existing == encoded) return
        writeFile(encoded)
    }

    /**
     * Writes through a sibling temp file and a rename: the file holds API keys
     * and is read by the extension on every call, so a half-written one is worse
     * than no write at all.
     */
    private fun writeFile(encoded: String): Boolean = runCatching {
        val parent = file.parentFile
        parent?.mkdirs()
        val temporary = File(parent, "web.json.tmp")
        temporary.writeText(encoded)
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
        true
    }.onFailure { Log.w(TAG, "could not write ${WebSearchSettings.FILE_NAME}", it) }
        .getOrDefault(false)

    private companion object {
        const val TAG = "PiKit"
    }
}
