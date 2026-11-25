package pi.kit.mob.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/** Each endpoint/key pair owns a Pi provider namespace, including the first official profile. */
object ProfileAddressing {
    const val PREFIX = "pikit-"
    private const val ROUTE_PREFIX = "${PREFIX}profile-"
    private const val KEY_PREFIX = "PIKIT_KEY_"

    /** Never include the key in a provider ID: Pi writes this ID to sessions and logs. */
    fun runtimeProviderId(profile: ModelProfile): String = ROUTE_PREFIX + routeId(profile)

    fun keyVariable(profile: ModelProfile): String = KEY_PREFIX + routeId(profile)

    fun apiKeyReference(profile: ModelProfile): String = "\$" + keyVariable(profile)

    /** Stale shells cannot authenticate a changed endpoint using their old environment. */
    private fun routeId(profile: ModelProfile): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(profile.id, profile.provider, profile.baseUrl.trim(), profile.api)
            .joinToString(separator = "\u0000").toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

    /** Never populate Pi's shared native env vars with a saved profile's credential. */
    fun providerEnvironment(snapshot: PiConfigSnapshot): Map<String, String> = buildMap {
        for (profile in snapshot.profiles) {
            val provider = profile.providerEntry ?: continue
            val key = profile.apiKey.trim()
            if (key.isNotEmpty()) put(keyVariable(profile), key)
            else if (provider in PiProvider.needsBaseUrl) put(keyVariable(profile), "not-required")
        }
    }
}

internal fun JsonObject.apiName(): String =
    (this["api"] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.baseUrlName(): String =
    (this["baseUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
