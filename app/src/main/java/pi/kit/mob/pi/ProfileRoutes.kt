package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import pi.kit.mob.data.CustomApi
import pi.kit.mob.data.CustomEndpoint
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.PiConfigSnapshot
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.ProfileAddressing
import pi.kit.mob.data.apiName
import pi.kit.mob.data.baseUrlName
import pi.kit.mob.data.normalizeApiBaseUrl
import pi.kit.mob.data.withThinkingShapes
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal data class ProfileRoute(val provider: String, val model: String, val api: String, val baseUrl: String) {
    fun matches(value: JsonObject?): Boolean =
        value != null && value.string("provider") == provider && value.string("id") == model &&
            value.apiName() == api && value.baseUrlName() == baseUrl
}

/** What one child can resolve and authenticate; selection alone does not change that document. */
internal class ProfileRoutes(
    private val snapshot: PiConfigSnapshot,
    val entries: Map<String, JsonObject>,
) {
    fun route(profile: ModelProfile?): ProfileRoute? {
        if (profile == null) return null
        val id = ProfileAddressing.runtimeProviderId(profile)
        val entry = entries[id] ?: return null
        val definition = (entry["models"] as? JsonArray)?.firstNotNullOfOrNull { element ->
            (element as? JsonObject)?.takeIf { it.string("id") == profile.modelId }
        } ?: return null
        return ProfileRoute(
            id,
            profile.modelId,
            definition.apiName().ifBlank { entry.apiName() },
            definition.baseUrlName().ifBlank { entry.baseUrlName() },
        )
    }

    fun canUse(profile: ModelProfile): Boolean {
        val loaded = snapshot.profiles.firstOrNull { it.id == profile.id } ?: return false
        return route(profile) != null && loaded.provider == profile.provider &&
            loaded.api == profile.api && loaded.baseUrl == profile.baseUrl &&
            loaded.apiKey.trim() == profile.apiKey.trim() &&
            loaded.settingsFor(profile.modelId) == profile.settingsFor(profile.modelId)
    }
}

internal fun prepareProfileRoutes(prefix: File, store: File, snapshot: PiConfigSnapshot): ProfileRoutes {
    val thinking = HashMap<String, Map<String, JsonObject>>()
    val native = HashMap<String, Map<String, JsonObject>>()
    val cached = HashMap<String, Map<String, JsonObject>>()
    val entries = buildMap {
        for (profile in snapshot.profiles) {
            val provider = profile.providerEntry ?: continue
            val entry = if (provider in PiProvider.needsBaseUrl) {
                val protocol = profile.api.ifBlank { CustomApi.DEFAULT_ID }
                if (CustomApi.entries.none { it.id == protocol }) continue
                CustomEndpoint.providerObject(
                    baseUrl = profile.baseUrl,
                    modelIds = profile.selectableModels,
                    settings = profile.modelSettings.withThinkingShapes(
                        thinking.getOrPut(protocol) { cataloguedThinkingShapes(store, protocol) },
                    ),
                    api = protocol,
                    apiKey = ProfileAddressing.apiKeyReference(profile),
                )?.takeIf { endpointOrigin(it.baseUrlName()) != null }
            } else {
                if (profile.apiKey.isBlank()) continue
                isolatedNativeProfile(
                    profile,
                    native.getOrPut(provider.id) { BundledModelFacts.load(prefix, provider.id) },
                    cached.getOrPut(provider.id) { storeProviderModels(store, provider.id) },
                )
            }
            if (entry != null) put(ProfileAddressing.runtimeProviderId(profile), entry)
        }
    }
    return ProfileRoutes(snapshot, entries)
}

/** The installed Pi's own offline catalog; never read a user models.json for official routes. */
private object BundledModelFacts {
    private const val DATA = "lib/node_modules/@earendil-works/pi-coding-agent/node_modules/@earendil-works/pi-ai/dist/providers/data"
    private data class Cached(val stamp: Long, val size: Long, val models: Map<String, JsonObject>)
    private val documents = ConcurrentHashMap<String, Cached>()

    fun load(prefix: File, provider: String): Map<String, JsonObject> {
        val file = File(prefix, "$DATA/$provider.json")
        val stamp = file.lastModified()
        val size = file.length()
        return documents.compute(file.absolutePath) { _, old ->
            if (old != null && old.stamp == stamp && old.size == size) old
            else Cached(stamp, size, runCatching {
                val root = Json.parseToJsonElement(file.readText()) as JsonObject
                root.values.filterIsInstance<JsonObject>()
                    .flatMap { it.values.filterIsInstance<JsonObject>() }
                    .filter { it.string("type").let { type -> type == null || type == "chat" } }
                    .mapNotNull { model -> model.string("id")?.let { it to model } }.toMap()
            }.getOrDefault(emptyMap()))
        }?.models.orEmpty()
    }
}

internal fun isolatedNativeProfile(
    profile: ModelProfile,
    bundled: Map<String, JsonObject>,
    cached: Map<String, JsonObject>,
): JsonObject? {
    val dialects = bundled.values.filter { it.apiName().isNotBlank() && it.baseUrlName().isNotBlank() }
    val routes = dialects.distinctBy { it.apiName() to it.baseUrlName() }
    val definitions = profile.selectableModels.filter(String::isNotBlank).distinct().mapNotNull { id ->
        val native = bundled[id]
        val fromCache = cached[id]?.takeIf { candidate ->
            val origin = endpointOrigin(candidate.baseUrlName()) ?: return@takeIf false
            (if (native != null) listOf(native) else routes).any { known ->
                known.apiName() == candidate.apiName() && endpointOrigin(known.baseUrlName()) == origin
            }
        }
        val facts = fromCache ?: native ?: routes.singleOrNull() ?: return@mapNotNull null
        val api = facts.apiName()
        if (api.isBlank()) return@mapNotNull null
        val baseUrl = if (profile.baseUrl.isBlank()) {
            native?.baseUrlName() ?: facts.baseUrlName()
        } else {
            normalizeApiBaseUrl(profile.baseUrl, api).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        }
        if (endpointOrigin(baseUrl) == null) return@mapNotNull null
        val modelFacts = modelDefinitionFacts(facts).filterKeys { it != "api" && it != "baseUrl" }.toMutableMap()
        val compat = (native ?: facts)["compat"] as? JsonObject
        if (compat != null) modelFacts["compat"] = compat
        val definition = pikitModelDefinition(ModelEntry(id, JsonObject(modelFacts), profile.settingsFor(id)))
        buildJsonObject {
            definition.forEach { (key, value) -> put(key, value) }
            put("api", api)
            put("baseUrl", baseUrl)
            if (profile.baseUrl.isBlank()) native?.get("headers")?.let { put("headers", it) }
        }
    }
    if (definitions.isEmpty()) return null
    return buildJsonObject {
        put("name", profile.displayName)
        put("apiKey", ProfileAddressing.apiKeyReference(profile))
        put("models", JsonArray(definitions))
    }
}

private data class EndpointOrigin(val scheme: String, val host: String, val port: Int)

private fun endpointOrigin(value: String): EndpointOrigin? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (uri.rawUserInfo != null) return null
    return EndpointOrigin(scheme, host, if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80)
}

internal fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

/** Compare values the old writer tracked; leave other user fields and providers alone. */
internal fun withdrawNativeProjection(existing: JsonObject, snapshot: PiConfigSnapshot): JsonObject {
    val legacy = snapshot.profiles.groupBy { it.provider }.mapValues { (_, profiles) ->
        ModelDefinitions(
            ours = profiles.flatMap { it.writtenModels }.distinct(),
            writtenBaseUrls = profiles.flatMap { profile ->
                CustomApi.entries.map { normalizeApiBaseUrl(profile.writtenBaseUrl, it.id) }
            }.filter { it.isNotBlank() }.distinct(),
        )
    }
    return modelsJsonWith(existing, definitions = legacy)
}

internal fun isAppProvider(id: String, entry: JsonObject?): Boolean =
    id == CustomEndpoint.PROVIDER_ID ||
        (id.startsWith(ProfileAddressing.PREFIX) && entry.string("apiKey").orEmpty().startsWith("$" + "PIKIT_KEY_"))
