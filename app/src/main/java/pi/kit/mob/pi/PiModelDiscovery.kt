package pi.kit.mob.pi

import android.content.Context
import android.util.Log
import pi.kit.mob.data.PiProvider
import pi.kit.mob.env.SafeDelete
import pi.kit.mob.env.TermuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where a discovered model id came from. */
enum class ModelSource(val label: String) {
    /** The provider's own `/models` endpoint, i.e. what this key can use. */
    PROVIDER("from the provider"),

    /** pi's built-in catalog, reported by a running agent process. */
    PI_CATALOG("from pi's catalog"),
}

data class DiscoveredModel(
    val id: String,
    val name: String = "",
    val source: ModelSource,
    val contextWindow: Long? = null,
    val supportsImages: Boolean = false,
    val reasoning: Boolean = false,
) {
    val label: String get() = name.takeIf { it.isNotBlank() && it != id } ?: id
}

/** Outcome of a "Fetch models" request. */
sealed interface ModelDiscovery {
    data class Success(val models: List<DiscoveredModel>) : ModelDiscovery

    /**
     * [message] is what the user is shown. Several things can go wrong on the
     * way to a model list and the user needs to see all of them, because the
     * fix differs: a rejected key, a provider without a models endpoint, or a
     * pi catalog that refused to start.
     */
    data class Failure(val message: String) : ModelDiscovery
}

/**
 * What pi's own catalogue says about one provider, asked of a process that cannot see
 * PiKit's `models.json`. See [ModelDiscoveryClient.catalogueProbe].
 *
 * @param ids the model ids pi's catalogue contains. These are the ones PiKit must not
 *   write a `models` entry for, and the ones the model page's per-model controls are not
 *   drawn for.
 * @param fallback the model pi resolved for [ModelDiscoveryClient.PROBE_MODEL_ID] —
 *   `buildFallbackModel`'s copy of the provider's default model, which is what an id pi's
 *   catalogue does not contain resolves to. It is the source of the facts PiKit's own
 *   entry has to carry, and null when pi answered the catalogue question but not the state
 *   one.
 * @param basis the state of that catalogue *before* pi was asked, as [catalogueBasis]
 *   computes it. The answer is a claim about that state and nothing later: the launch path
 *   refreshes `models-store.json` in the background, so a save that recorded the basis it
 *   saw *after* the probe could vouch for a verdict the catalogue had already moved past.
 *   It is also the cache key — see [CatalogueCache].
 */
data class CatalogueProbe(
    val ids: Set<String>,
    val fallback: JsonObject?,
    val basis: String,
    /**
     * Each catalogued id's own definition, as pi reported it, keyed by id.
     *
     * The page needs it for the three parameters it lets the user change: a model the
     * catalogue contains has a window, a max-out and a capability list of its own, and
     * showing the *fallback's* values for it would be showing the provider's default
     * model's numbers under another model's name. `get_available_models` answers with the
     * full `Model` objects (`ModelRuntime.getAvailableSnapshot` is the composed model list
     * itself), so pi hands them over; they are passed through [modelDefinitionFacts], the
     * same whitelist the definition writer uses, because they are read by the same code
     * and because the rest of a `Model` — `provider`, `headers` — is not this app's to
     * carry around.
     *
     * An id missing from here is one the process did not report, which the page reads as
     * "nothing known" and falls back to [fallback] for.
     */
    val facts: Map<String, JsonObject> = emptyMap(),
    /**
     * Set when the check did **not** answer, with what went wrong, and everything else in
     * here empty.
     *
     * A failed check is a value rather than a null so that the model page can say *why*
     * nothing can be declared yet. It used to be a null with the reason written to logcat
     * only, and the page's row then read "could not read pi's catalog — tap to try again"
     * for every possible cause: a `pi` that will not start, a bundle that answered nothing,
     * a scratch directory that could not be created. That is the report this exists for —
     * "after choosing a provider and typing an id, the custom parameters never appear" —
     * where the controls are withheld (correctly: without an answer there is no way to know
     * whether an id needs an override or a definition) and the screen gave no indication of
     * what to do about it.
     *
     * A caller must not read an empty [ids] from one of these as "pi catalogues nothing":
     * the difference between "no models" and "no answer" is exactly this field.
     */
    val error: String? = null,
) {
    /** Whether the check answered at all. See [error]. */
    val answered: Boolean get() = error == null
}

/**
 * The answers this app run has already got, one per provider.
 *
 * Nothing in a catalogue answer moves except the two things [catalogueBasis] names, so a
 * stored answer whose basis still matches *is* the answer: pi's bundle has not changed and
 * `models-store.json` has not been rewritten. A hit is therefore not a shortcut with a
 * caveat, it is the same claim re-checked cheaply — and it is what makes the model page's
 * check cost a couple of file reads on every visit after the first.
 *
 * Keyed by provider alone rather than by (provider, key): the catalogue is composed from
 * pi's own data filtered by *whether* a provider has a credential, not by which one, which
 * is the same reason the check itself does not depend on the key.
 *
 * Nothing here survives the process, deliberately. A new app run re-reads the catalogue
 * from disk anyway (the launch path refreshes it), so a persisted cache would be stale on
 * the one occasion it mattered and would need invalidating by the same file reads that make
 * it cheap.
 */
private object CatalogueCache {

    private val answers = ConcurrentHashMap<String, CatalogueProbe>()

    fun get(providerId: String, basis: String): CatalogueProbe? =
        answers[providerId]?.takeIf { it.basis == basis }

    fun put(providerId: String, answer: CatalogueProbe) {
        answers[providerId] = answer
    }
}

/**
 * Fetches the model ids a provider and key can actually use.
 *
 * The provider's own endpoint is tried first and pi's catalog second, which is
 * the opposite of the obvious order and deliberate: pi ships a fixed catalog
 * that lists models the key may not be entitled to, so a model picked from it
 * can fail on the first prompt. Asking the provider answers "what can this key
 * use"; pi's catalog answers "what does pi know about", which is a useful
 * fallback for providers that have no models endpoint at all.
 *
 * pi does not query provider `/models` endpoints itself, so this is the only
 * place that does. Nothing here adds a dependency: `HttpURLConnection` ships
 * with Android and the response is parsed with the serialization library the
 * app already links.
 */
class ModelDiscoveryClient(
    private val context: Context,
    private val env: TermuxEnv,
) {

    /**
     * @param baseUrl the endpoint for a custom provider. Ignored by the built-in
     *   ones, whose URLs pi already knows.
     */
    suspend fun discover(
        provider: PiProvider,
        apiKey: String,
        baseUrl: String = "",
    ): ModelDiscovery {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            return ModelDiscovery.Failure("Enter an API key first — a model list cannot be fetched without one.")
        }

        // A custom endpoint is a relay, which almost always speaks the OpenAI
        // API, so its model list is at `<baseUrl>/models`. Built here rather than
        // in `modelsEndpoint()` because that function only knows the provider.
        val endpoint = if (provider in PiProvider.needsBaseUrl) {
            customEndpoint(baseUrl)
        } else {
            provider.modelsEndpoint()
        }
        val failures = mutableListOf<String>()

        if (endpoint != null) {
            when (val result = fetchFromProvider(endpoint, key)) {
                is ModelDiscovery.Success -> return result
                is ModelDiscovery.Failure -> failures += result.message
            }
        } else {
            failures += if (provider in PiProvider.needsBaseUrl) {
                "Enter the endpoint's base URL first — the model list is fetched from " +
                    "`<endpoint>/models`."
            } else {
                "${provider.label} has no models endpoint pi knows how to read."
            }
        }

        // pi's own catalog is meaningless for a relay: it lists built-in
        // providers' models, none of which the relay is serving.
        if (provider in PiProvider.needsBaseUrl) {
            return ModelDiscovery.Failure(failures.joinToString("\n\n"))
        }

        when (val result = fetchFromPiCatalog(provider, key)) {
            is ModelDiscovery.Success -> return result
            is ModelDiscovery.Failure -> failures += result.message
        }

        return ModelDiscovery.Failure(failures.joinToString("\n\n"))
    }

    /**
     * What pi's own catalogue says about [provider]: which ids it contains, and what it
     * resolves an id it does *not* contain to.
     *
     * ## Where the answer comes from, in order
     *
     * 1. **This app run's cache** ([CatalogueCache]), when the catalogue state it was read in
     *    still matches. Confirming that state is two file reads.
     * 2. **`models-store.json`** — pi's own copy of pi.dev's per-provider catalogue, which the
     *    launch path refreshes for *every* built-in provider. This is the whole answer for an
     *    id that is in it, with no process at all, and it is where the answer comes from on
     *    any phone that has been online once. See [storeModelFacts].
     * 3. **A throwaway `pi --mode rpc`**, for the one thing the store cannot answer: an id it
     *    does not hold. Absence is not "pi does not know this id" — the store is only pi.dev's
     *    half of the catalogue and pi's built-in half lives inside its package — and the
     *    process is also the only way to read the *fallback* model, which is what such an id
     *    resolves to and what a `models` entry for it has to name.
     *
     * Step 3 is why this function can return nothing useful; steps 1 and 2 are why it usually
     * does not have to. Before the store was read here, every visit paid a node start, and a
     * node start that failed (a slow phone, a bundle that would not load) left the model page
     * with no answer at all — no way to declare anything about any model, including the ones
     * the store already described. See [CatalogueProbe.error] for what the page is told now.
     *
     * ## Why this is not [discover]
     *
     * [discover] asks the provider first and only falls back to pi's catalogue, which
     * makes it the wrong tool for one question: *does pi's catalogue contain this id?*
     * PiKit declares a custom model id image-capable by writing a `models` entry into
     * pi's own `models.json` (`modelsJsonWith`), and `applyModelsJson` builds a
     * provider's model list from that array — so an id PiKit has declared is in pi's
     * answer whether pi's catalogue knows it or not. Subtracting the ids the app wrote
     * cannot tell the two apart either: the entry it wrote is what replaced pi's own,
     * and detecting exactly that takeover is the point once pi's catalogue gains the
     * model through its network update.
     *
     * So the question is asked of a process that cannot see PiKit's `models.json`: one
     * throwaway `pi --mode rpc` whose agent directory is a scratch copy holding pi's
     * `models-store.json` and nothing else. That file is copied in rather than left out
     * because it *is* the network-updated half of the catalogue — without it the answer
     * would be "pi has never heard of it" for every model pi.dev added since the bundle
     * was built, which is the one verdict this call exists to get right.
     *
     * ## Why it is launched with a model id nothing serves
     *
     * The second half of the answer is the *fallback*: pi resolves an id its catalogue
     * does not contain by copying the provider's default model
     * (`buildFallbackModel` — `{...providerDefault, id, name}`), and PiKit's `models`
     * entry for such an id has to reproduce that copy, or the declaration would trade
     * the model's window, cost and thinking levels for PiKit's invented ones. The
     * request for the process's own state (`get_state`, which is also the readiness
     * probe) reports `session.model`, so asking it for [PROBE_MODEL_ID] — an id no
     * provider serves — makes that reply *be* `buildFallbackModel`'s output. Measured
     * against the bundled runtime, pi starts happily for an id it does not know and
     * warns `Model "…" not found for provider "…". Using custom model id.`; it only
     * refuses when the provider has no models at all, which is the same state the
     * catalogue half reports as a failure.
     *
     * ## What it costs, and where it is called from
     *
     * One node process, seconds, on a page the user visits to edit a profile, and nothing
     * blocks on it. The answer is cached for the life of the app run under the catalogue
     * state it was read in ([CatalogueCache]), because that state is what the answer is a
     * function of: pi's bundle and the cached catalogue. Confirming the state is two file
     * reads, so a second visit — or a second profile on the same provider — costs a
     * `readText` rather than a node start. A *failed* check is not cached, which is what
     * makes the model page's retry row worth having. The scratch directory is removed on
     * every path, through [SafeDelete], because it is a tree this app created inside its
     * own `filesDir`.
     *
     * @return the answer, or null when the CLI is missing, pi refused to start, or the
     *   catalogue was empty. An empty catalogue is deliberately a failure rather than
     *   "nothing is catalogued": pi lists a model only for a provider it can authenticate
     *   (`ModelRuntime.runAvailabilityRefresh` builds the available snapshot by filtering
     *   the composed catalogue through `configuredProviders`), so "no models" can also mean
     *   the credential did not resolve — and reading that as "pi knows none of them" is the
     *   one mistake that would make the app write an entry over a model pi's catalogue
     *   describes.
     *
     * ## The key field being empty is not a reason to refuse
     *
     * It used to be one, and that was a bug with a report behind it: with the field empty
     * the answer came back empty for a reason that has nothing to do with the models —
     * nothing *failed*, so the page could not even say what was wrong, and the catalogue it
     * claimed not to be able to read was sitting on disk the whole time. What is being
     * asked is a question about pi's bundle, so when the user has not typed a key yet this
     * hands pi [PROBE_API_KEY] instead: a credential only has to *resolve* for pi to
     * compose the provider's catalogue, and the catalogue's contents do not depend on the
     * key's value. `auth.json` is not in the scratch directory and is not touched; the key
     * exists for the length of one process and writes nothing.
     */
    suspend fun catalogueProbe(provider: PiProvider, apiKey: String): CatalogueProbe? =
        withContext(Dispatchers.IO) {
            // Read before the process is started: see [CatalogueProbe.basis].
            val basis = catalogueBasis(env, provider)
            // The answer is a function of pi's bundle and the cached catalogue, and of
            // nothing else — not of the key, not of the profile. Confirming that state is two
            // file reads, so re-opening the page (or opening a second profile on the same
            // provider) does not pay for a node start again. See [CatalogueCache].
            CatalogueCache.get(provider.id, basis)?.let { return@withContext it }
            // pi.dev's half of the catalogue, on disk, with no process: the store the launch
            // path already refreshes for every built-in provider. See [storeModelFacts] for
            // what that buys and what it deliberately does not answer.
            val stored = storeModelFacts(
                File(env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME),
                provider.id,
            )
            // The scratch directory is created, copied into and deleted here rather than
            // in the callers' context: this is called from a page's `LaunchedEffect`, so
            // the caller is the main thread, and three filesystem calls plus a recursive
            // delete belong off it.
            val scratch = scratchAgentDir() ?: return@withContext CatalogueProbe(
                ids = stored.keys,
                fallback = null,
                basis = basis,
                facts = stored,
                error = if (stored.isEmpty()) {
                    "PiKit could not prepare the scratch agent directory pi reads " +
                        "its catalog from."
                } else {
                    null
                },
            )
            var fallback: JsonObject? = null
            var catalogued: Map<String, JsonObject> = emptyMap()
            try {
                val result = fetchFromPiCatalog(
                    provider = provider,
                    apiKey = apiKey.ifBlank { PROBE_API_KEY },
                    agentDir = scratch.absolutePath,
                    modelId = PROBE_MODEL_ID,
                    onState = { state -> fallback = state },
                    onCatalog = { entries -> catalogued = entries },
                )
                when (result) {
                    is ModelDiscovery.Success -> {
                        // pi's own answer is the authority where it has one: it is the merged
                        // catalogue, so it knows about a model pi.dev added after the store was
                        // last written. The store's entries are the ones a process would
                        // repeat (and everything it was ever needed for on a phone that has
                        // been online), so they fill in whatever this process did not report —
                        // notably for a provider whose credential did not resolve *here*, for
                        // which the stored half is the whole answer.
                        val ids = result.models.map { it.id }.toSet() + stored.keys
                        val facts = catalogued + stored.filterKeys { it !in catalogued }
                        CatalogueProbe(ids, fallback, basis, facts)
                            .also { CatalogueCache.put(provider.id, it) }
                    }

                    // The process is the only route to the *fallback* model's facts, which an
                    // id that is not catalogued needs, so its failure is only fatal when the
                    // store could not answer either. When it could, the page has everything
                    // it needs for the ids that are listed and the process failing costs
                    // nothing the reader can see.
                    is ModelDiscovery.Failure -> if (stored.isEmpty()) {
                        CatalogueProbe(
                            ids = emptySet(),
                            fallback = null,
                            basis = basis,
                            error = result.message,
                        )
                    } else {
                        CatalogueProbe(stored.keys, fallback, basis, stored)
                            .also { CatalogueCache.put(provider.id, it) }
                    }
                }
            } finally {
                SafeDelete.recursively(scratch, env.filesDir)
            }
        }

    /**
     * An agent directory pi can start in that holds the catalogue pi.dev has cached and
     * no `models.json`. See [catalogueProbe].
     */
    private fun scratchAgentDir(): File? = runCatching {
        val dir = File(env.filesDir, SCRATCH_AGENT_DIR)
        // A probe interrupted by the app being closed leaves one behind, so the
        // directory is replaced rather than reused.
        if (dir.exists()) SafeDelete.recursively(dir, env.filesDir)
        check(dir.mkdirs() || dir.isDirectory) { "could not create $SCRATCH_AGENT_DIR" }
        val store = File(env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME)
        if (store.isFile) {
            store.copyTo(File(dir, PiAgentSession.CATALOGUE_STORE_NAME), overwrite = true)
        }
        dir
    }.onFailure { Log.w(TAG, "could not prepare a scratch agent directory", it) }.getOrNull()

    /** `<baseUrl>/models`, tolerating a base URL that already ends in a slash. */
    private fun customEndpoint(baseUrl: String): ModelsEndpoint? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty() || !base.startsWith("http")) return null
        return ModelsEndpoint(
            provider = PiProvider.CUSTOM,
            url = { "$base/models" },
            modelsPath = listOf("data"),
            headers = { key -> mapOf("Authorization" to "Bearer $key") },
        )
    }

    // ------------------------------------------------------------- provider

    private suspend fun fetchFromProvider(
        endpoint: ModelsEndpoint,
        key: String,
    ): ModelDiscovery = withContext(Dispatchers.IO) {
        try {
            val response = get(endpoint, key)
            when {
                response.code !in 200..299 -> ModelDiscovery.Failure(
                    "${endpoint.host()} rejected the request: HTTP ${response.code} ${response.message}" +
                        detailFrom(response.body),
                )

                else -> {
                    val models = parseModels(response.body, endpoint.modelsPath)
                    if (models.isEmpty()) {
                        ModelDiscovery.Failure(
                            "${endpoint.host()} answered with no model ids. It may have changed " +
                                "its response format; pi's own catalog is used instead.",
                        )
                    } else {
                        ModelDiscovery.Success(
                            models.map { DiscoveredModel(id = it, source = ModelSource.PROVIDER) },
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ModelDiscovery.Failure("Could not reach ${endpoint.host()}: ${e.message ?: e::class.java.simpleName}")
        }
    }

    private class HttpResult(val code: Int, val message: String, val body: String)

    private fun get(endpoint: ModelsEndpoint, key: String): HttpResult {
        val connection = URL(endpoint.url(key)).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            endpoint.headers(key).forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, connection.responseMessage.orEmpty(), body)
        } finally {
            connection.disconnect()
        }
    }

    /** Pulls `id` out of whichever shape the provider answered with. */
    private fun parseModels(body: String, modelsPath: List<String>): List<String> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyList()

        var node: Any = root
        modelsPath.forEach { key ->
            node = (node as? JsonObject)?.get(key) ?: return emptyList()
        }
        val entries = node as? JsonArray ?: return emptyList()

        return entries.mapNotNull { element ->
            when (element) {
                is JsonObject -> element.string("id") ?: element.string("name")
                else -> element.jsonPrimitive.contentOrNull
            }
        }.map { it.removePrefix("models/") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** A short excerpt of an error body: providers explain themselves there. */
    private fun detailFrom(body: String): String {
        val text = body.trim().replace(Regex("\\s+"), " ")
        return if (text.isEmpty()) "" else "\n${text.take(300)}"
    }

    // ---------------------------------------------------------------- catalog

    /**
     * Asks a throwaway `pi --mode rpc` process for its catalog.
     *
     * A second process rather than the live agent because the catalog is
     * filtered by the credentials the process was started with, and the whole
     * point of the fallback is to test the key the user just typed — which is
     * not necessarily the one the running agent holds. The process is closed on
     * every path; a failed discovery must not leave an orphan behind.
     *
     * [agentDir] points pi at a scratch agent directory instead of `$HOME/.pi/agent`;
     * null uses pi's own default. [modelId] selects the model the process resolves —
     * null lets pi pick, and [catalogueProbe] passes an id nothing serves so that the
     * resolved model *is* `buildFallbackModel`'s copy. [onState] receives that model, as
     * `get_state` reported it, on the one launch that asks for it.
     */
    private suspend fun fetchFromPiCatalog(
        provider: PiProvider,
        apiKey: String,
        agentDir: String? = null,
        modelId: String? = null,
        onState: ((JsonObject?) -> Unit)? = null,
        /**
         * Receives each catalogued id's own definition, whitelisted by
         * [modelDefinitionFacts] — the same subset the definition writer copies, which is
         * what the model page shows as a number's starting point. Only
         * [catalogueProbe] asks for it.
         */
        onCatalog: ((Map<String, JsonObject>) -> Unit)? = null,
    ): ModelDiscovery = withContext(Dispatchers.IO) {
        val cliEntry = PiInstallation.cliEntry(env)
            ?: return@withContext ModelDiscovery.Failure(
                "pi's catalog is not available either: the CLI is missing from the bundled runtime.",
            )

        val workingDir = env.workspace.also { it.mkdirs() }
        var client: PiRpcClient? = null
        try {
            val process = PiProcessLauncher.launch(
                context = context,
                env = env,
                options = PiLaunchOptions(
                    nodePath = env.node.absolutePath,
                    cliEntry = cliEntry.absolutePath,
                    workingDir = workingDir.absolutePath,
                    sessionDir = null,
                    provider = provider.id,
                    // pi only reports models for a provider when it has a model
                    // selected, and it has no way to guess one for an arbitrary
                    // key. `catalogueProbe` passes an id no provider serves, which
                    // makes the *resolved* model the provider's fallback copy of its
                    // default — the one thing a declaration for a custom id has to
                    // reproduce. See it for why that is asked for this way.
                    modelId = modelId,
                    apiKeyEnvVar = provider.envVar,
                    apiKey = apiKey,
                    agentDir = agentDir,
                    // Both are about reading rather than doing: no network, and no session
                    // file. See [PiLaunchOptions.offline] — pi's RPC mode otherwise starts a
                    // catalogue refresh whose result can land in the middle of this answer.
                    offline = true,
                    noSession = true,
                ),
            )
            val created = PiRpcClient.from(process).also { it.start() }
            client = created

            // Same readiness probe the agent launch uses: pi has no handshake. The reply
            // carries `model`, which is what the probe caller came for.
            val state = created.handshake(CATALOG_TIMEOUT)
            onState?.invoke(state.data?.jsonObject?.get("model") as? JsonObject)
            val response = created.request("get_available_models", CATALOG_TIMEOUT) {
                PiCommand.getAvailableModels(it)
            }
            if (!response.success) {
                ModelDiscovery.Failure("pi refused to list models: ${response.error ?: "unknown reason"}")
            } else {
                onCatalog?.invoke(catalogFacts(response))
                val models = parseCatalog(response)
                if (models.isEmpty()) {
                    ModelDiscovery.Failure(
                        "pi's catalog returned no models for ${provider.label}. " +
                            "Type the model id directly if you know it.",
                    )
                } else {
                    ModelDiscovery.Success(models)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pi catalog lookup failed", t)
            ModelDiscovery.Failure(
                "pi could not start to read its catalog: " +
                    (t.message ?: t::class.java.simpleName),
            )
        } finally {
            // Closing stdin is pi's clean shutdown; the client escalates to a
            // kill if the process ignores it.
            runCatching { client?.close() }
        }
    }

    private fun parseCatalog(response: PiRecord.Response): List<DiscoveredModel> {
        val array = response.data?.jsonObject?.get("models") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val model = element as? JsonObject ?: return@mapNotNull null
            val id = model.string("id") ?: return@mapNotNull null
            DiscoveredModel(
                id = id,
                name = model.string("name").orEmpty(),
                source = ModelSource.PI_CATALOG,
                contextWindow = model["contextWindow"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                // pi reports a capability list; "image" in it is what the model
                // actually accepts.
                supportsImages = model["input"]?.let { input ->
                    runCatching { input.jsonArray.any { it.jsonPrimitive.contentOrNull == "image" } }
                        .getOrDefault(false)
                } ?: false,
                reasoning = model["reasoning"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            )
        }
    }

    /**
     * Each catalogued id's own definition, keyed by id.
     *
     * The whitelist is [modelDefinitionFacts] — the same subset `models.json` accepts — so
     * that the page, which shows these as the starting point of its three controls, and
     * the writer, which copies the same fields into a definition, cannot disagree about
     * what a model's facts are. Two things are dropped on top of that whitelist:
     * `api`/`baseUrl`, because the page has no use for where a model is reached from and
     * carrying them into a *custom endpoint's* declaration is the one way a relay's request
     * could end up at `api.deepseek.com` (`CustomEndpoint` builds its own provider object),
     * and any entry without an id, which is not something the page can key anything by.
     */
    private fun catalogFacts(response: PiRecord.Response): Map<String, JsonObject> {
        val array = response.data?.jsonObject?.get("models") as? JsonArray ?: return emptyMap()
        return cataloguedModelFacts(array.mapNotNull { it as? JsonObject })
    }

    private companion object {
        const val TAG = "PiKit"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000

        /** The scratch agent directory [catalogueProbe] gives pi, under `filesDir`. */
        const val SCRATCH_AGENT_DIR = "catalogue-probe"

        /**
         * The model id the catalogue probe launches pi with.
         *
         * Deliberately one no provider serves: pi resolves an id its catalogue does not
         * contain from a copy of the provider's default model, so asking the process for
         * its own state with this id is how the app reads that copy instead of guessing
         * it. The spelling is PiKit's own plus `probe`, which no real provider uses and
         * which reads as PiKit's in a log.
         */
        const val PROBE_MODEL_ID = "pikit-probe-model"

        /**
         * What [catalogueProbe] authenticates its throwaway process with when the key field
         * is empty.
         *
         * pi composes a provider's catalogue — the built-in half and the cached
         * `models-store.json` overlay — for any provider whose credential *resolves*
         * (`hasConfiguredAuth`), and the value of the credential plays no part in that. So
         * a placeholder is enough to see the catalogue of a provider the user has not
         * typed a key for yet, which is the state the model form is in while the user is
         * filling in the model id. It never leaves the process: the scratch agent
         * directory has no `auth.json`, nothing is written, and pi never sends a request
         * with it — the probe asks for the catalogue and the process's own state and
         * nothing else.
         */
        const val PROBE_API_KEY = "pikit-catalogue-probe"

        /**
         * Spawning node and loading pi's bundle takes seconds on a phone, and
         * the first request has to wait for all of it; the default 30s timeout
         * is not enough headroom on a slow device.
         */
        val CATALOG_TIMEOUT: Duration = 60.seconds
    }
}

/**
 * A provider's own model list.
 *
 * Only endpoints that are known to return a JSON list are registered. Guessing
 * a URL for a provider whose API shape is unknown produces a 404 that reads to
 * the user as "your key is broken", which is worse than saying the provider is
 * not supported.
 */
private class ModelsEndpoint(
    val provider: PiProvider,
    /** Built per request because some providers carry the key in the URL. */
    val url: (String) -> String,
    /** Keys to walk from the response root to the array of entries. */
    val modelsPath: List<String>,
    val headers: (String) -> Map<String, String>,
) {
    private val host: String by lazy {
        runCatching { URL(url.invoke("")).host }.getOrDefault(provider.label)
    }

    fun host(): String = host
}

private fun PiProvider.modelsEndpoint(): ModelsEndpoint? = when (this) {
    PiProvider.DEEPSEEK -> bearer("https://api.deepseek.com/models")
    PiProvider.OPENAI -> bearer("https://api.openai.com/v1/models")
    PiProvider.XAI -> bearer("https://api.x.ai/v1/models")
    PiProvider.GROQ -> bearer("https://api.groq.com/openai/v1/models")
    PiProvider.OPENROUTER -> bearer("https://openrouter.ai/api/v1/models")
    PiProvider.MISTRAL -> bearer("https://api.mistral.ai/v1/models")
    PiProvider.CEREBRAS -> bearer("https://api.cerebras.ai/v1/models")

    PiProvider.ANTHROPIC -> ModelsEndpoint(
        provider = this,
        url = { "https://api.anthropic.com/v1/models" },
        modelsPath = listOf("data"),
        headers = { key ->
            mapOf(
                "x-api-key" to key,
                // Without this header the API rejects the request regardless of
                // the key.
                "anthropic-version" to "2023-06-01",
            )
        },
    )

    // Gemini authenticates with a query parameter rather than a header, and it
    // returns ids with a `models/` prefix that the API will not accept back.
    PiProvider.GOOGLE -> ModelsEndpoint(
        provider = this,
        url = { key -> "https://generativelanguage.googleapis.com/v1beta/models?key=$key" },
        modelsPath = listOf("models"),
        headers = { _ -> emptyMap() },
    )

    // ZAI is served through an OpenAI-compatible gateway. Unverified against a
    // live key, so it reports its own error rather than a silent empty list.
    PiProvider.ZAI -> bearer("https://api.z.ai/api/paas/v4/models")

    // The rest of pi's providers whose model list is a documented OpenAI-shaped
    // `GET /models` under the base URL pi already uses for them. One line each, and
    // the bearer header is the same for all of them.
    PiProvider.MOONSHOTAI -> bearer("https://api.moonshot.ai/v1/models")
    PiProvider.MOONSHOTAI_CN -> bearer("https://api.moonshot.cn/v1/models")
    PiProvider.NVIDIA -> bearer("https://integrate.api.nvidia.com/v1/models")
    PiProvider.TOGETHER -> bearer("https://api.together.ai/v1/models")
    PiProvider.FIREWORKS -> bearer("https://api.fireworks.ai/inference/v1/models")
    PiProvider.BASETEN -> bearer("https://inference.baseten.co/v1/models")
    PiProvider.HUGGINGFACE -> bearer("https://router.huggingface.co/v1/models")
    PiProvider.VERCEL_AI_GATEWAY -> bearer("https://ai-gateway.vercel.sh/v1/models")
    PiProvider.QWEN_TOKEN_PLAN ->
        bearer("https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/models")
    PiProvider.QWEN_TOKEN_PLAN_INDIVIDUAL -> bearer(
        "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/models",
    )
    PiProvider.QWEN_TOKEN_PLAN_CN ->
        bearer("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/models")

    // No registered endpoint for the others, and that is not "unsupported": a null
    // here means the model list comes from pi's own catalogue instead, which is the
    // same source the model page's catalogue check reads and the same one a hand-run
    // `pi --list-models` prints. Guessing a URL is what this avoids — a 404 comes back
    // to the user as "your key was rejected", and one provider's shapes are not
    // another's:
    //
    //  * `minimax`/`minimax-cn` are registered at their *Anthropic*-compatible base
    //    (`/anthropic`), which has no `/models`; their OpenAI-compatible base is a
    //    different host path that pi does not use.
    //  * `xiaomi`, `kimi-coding`, `opencode`, `opencode-go`, `zai-coding-cn` and
    //    `ant-ling` are OpenAI-compatible in shape but their `/models` route is not
    //    documented anywhere this app can check, and an unverified guess costs a
    //    rejected key's worth of confusion when the key is in fact fine.
    PiProvider.MINIMAX,
    PiProvider.MINIMAX_CN,
    PiProvider.XIAOMI,
    PiProvider.XIAOMI_TOKEN_PLAN_CN,
    PiProvider.XIAOMI_TOKEN_PLAN_AMS,
    PiProvider.XIAOMI_TOKEN_PLAN_SGP,
    PiProvider.KIMI_CODING,
    PiProvider.OPENCODE,
    PiProvider.OPENCODE_GO,
    PiProvider.ZAI_CODING_CN,
    PiProvider.ANT_LING,
    -> null

    // A custom endpoint is almost always a relay speaking the OpenAI API, so its
    // model list is at `<baseUrl>/models`. Returning null here would be simpler
    // and worse: the user would have to know the exact model id of a relay they
    // just typed the URL for, when the relay will happily list it.
    //
    // The URL cannot be built here — `modelsEndpoint` is a function of the
    // provider alone, and a custom endpoint has no URL until the profile supplies
    // one. [ModelDiscoveryClient] is handed the base URL separately and calls
    // this only for the built-in providers.
    PiProvider.CUSTOM -> null
}

private fun PiProvider.bearer(url: String): ModelsEndpoint = ModelsEndpoint(
    provider = this,
    url = { url },
    modelsPath = listOf("data"),
    headers = { key -> mapOf("Authorization" to "Bearer $key") },
)

/**
 * Each catalogued model's own definition, keyed by id, from pi's `get_available_models`
 * answer.
 *
 * The whitelist is [modelDefinitionFacts] — the subset `models.json` accepts — so the page,
 * which shows these as the starting point of its three controls, and the definition writer,
 * which copies the same fields into an entry, cannot disagree about what a model's facts
 * are. Two things are then dropped:
 *
 *  - `api` and `baseUrl`, because the page has no use for where a model is reached from, and
 *    because these facts are also what a **custom endpoint** inherits from a same-named
 *    catalogued id (the id is the only thing the two have in common). Carrying the
 *    catalogue's endpoint into a relay's declaration is how a request meant for a relay ends
 *    up at `api.deepseek.com`; `CustomEndpoint.providerObject` builds that provider's own
 *    base URL and api, and nothing read from here may override them.
 *  - any entry without an id, which is not something the page can key anything by.
 *
 * Top-level and pure so the two dropped keys are pinned by a test rather than by reading a
 * relay's logs: the failure they prevent is a request that leaves for the wrong host.
 */
internal fun cataloguedModelFacts(models: List<JsonObject>): Map<String, JsonObject> =
    models.mapNotNull { model ->
        val id = model["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        id to JsonObject(modelDefinitionFacts(model).filterKeys { it != "api" && it != "baseUrl" })
    }.toMap()

/**
 * The same facts, read out of pi's **own store** — `models-store.json` — rather than out of a
 * running pi.
 *
 * ## Why this exists
 *
 * The store is pi's local copy of pi.dev's per-provider catalogue, and it is written by the
 * launch path's refresh (`PiAgentSession.refreshCatalogueIfStale`, which asks for *every*
 * built-in provider's catalogue). pi.dev serves a catalogue for every provider pi's own key
 * table knows — measured by asking it directly: fourteen of them, and they include `minimax`,
 * `xiaomi`, `kimi-coding`, `opencode`, `zai-coding-cn` and `ant-ling`, answer `200` with a
 * model list whose byte count matches the built-in data file for the same provider almost
 * exactly. A store that has entries for only one or two providers is therefore not a fact
 * about pi.dev: it is a store written by a refresh that only had one provider's credentials,
 * which is what the app did before the refresh was widened.
 *
 * So "does pi's catalogue contain this id" — and, when it does, everything about that model —
 * is **already on disk**. Answering it from here needs no process, cannot fail on a slow
 * phone, and covers every provider. One node start per visit was the app's most expensive and
 * most fragile path for a question a file answers.
 *
 * ## What it deliberately does not do
 *
 * It says nothing about an id that is **absent**: the store is only pi.dev's half of the
 * catalogue, and the built-in half lives inside pi's package. An id missing here may still be
 * catalogued, so absence is *not* an answer and the caller keeps the process for it — see
 * [CatalogueProbe.facts]. A store that is missing, unreadable or not a JSON object also
 * yields nothing, which leaves the process as the only recourse, exactly as before.
 */
internal fun storeModelFacts(store: File, providerId: String): Map<String, JsonObject> {
    val text = if (store.isFile) runCatching { store.readText() }.getOrNull() else null
    if (text.isNullOrBlank()) return emptyMap()
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: return emptyMap()
    val entry = root[providerId] as? JsonObject ?: return emptyMap()
    val models = entry["models"] as? JsonArray ?: return emptyMap()
    return cataloguedModelFacts(models.mapNotNull { it as? JsonObject })
}
