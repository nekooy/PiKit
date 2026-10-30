package pi.kit.mob.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How a saved profile is addressed inside pi's own files, and the environment that
 * carries its credential.
 *
 * ## Why a profile needs an address at all
 *
 * A profile is a provider-and-key pair, and pi keys *both* of those by provider id:
 * one credential and one `baseUrl` per id, in `models.json` and in the environment.
 * So two saved profiles of one provider — two keys, or one provider reached two ways —
 * cannot both be called `deepseek`. The second one is registered under an id of this
 * app's own, [PREFIX] plus the profile's id, and everything below exists so that the
 * name a profile is launched with, the name `set_model` is told, and the name its
 * `models.json` entry is written under are **one answer** rather than three that have
 * to be kept in step by hand.
 *
 * ## What "loaded at start" then means
 *
 * Every profile writes its credential into the environment under its own
 * [keyVariable], and its `models.json` entry names that variable (`"$PIKIT_KEY_…"`,
 * which pi interpolates — measured on 1.4.0: a provider whose `apiKey` is an
 * unresolvable `$NAME` is not "configured", and one whose variable is set is). pi
 * decides which providers are usable from credentials alone, so a launch with every
 * profile's variable present makes every profile usable **in the running process** —
 * which is what lets the model picker switch across profiles with `set_model` instead
 * of relaunching the agent.
 *
 * The same map is handed to the terminal tab's login shell, so a `pi` typed there
 * sees the profiles the agent sees. That is the whole of "shared configuration": pi's
 * files are the shared copy, and this app writes them on every launch.
 */
object ProfileAddressing {

    /** The prefix every provider id this app generates carries. */
    const val PREFIX = "pikit-"

    /** The prefix of the environment variable that carries one profile's key. */
    const val KEY_VARIABLE_PREFIX = "PIKIT_KEY_"

    /** The environment variable that carries [profileId]'s key. */
    fun keyVariable(profileId: String): String = KEY_VARIABLE_PREFIX + profileId

    /** What a `models.json` entry names in `apiKey`, for pi to interpolate. */
    fun apiKeyReference(profileId: String): String = "$" + keyVariable(profileId)

    /**
     * The provider id pi knows [profile] by.
     *
     * A custom endpoint is only ever reachable through an id of this app's own — two
     * relays both called `pikit-custom` is one relay pi knows — so every relay gets its
     * profile's id. A built-in provider keeps pi's own id for the **first** profile that
     * names it, because that id is what gives the provider pi's catalogue, compat table
     * and default endpoint; a second profile for the same provider has to be somebody
     * else, and taking pi's id away from the first would move every saved number with it.
     *
     * "First" is the order the profiles are stored in, which is the order they were
     * saved. It therefore only changes when a profile is deleted, and a deletion is a
     * settings change that relaunches the agent anyway.
     */
    fun runtimeProviderId(profile: ModelProfile, profiles: List<ModelProfile>): String {
        val provider = profile.providerEntry ?: return profile.provider
        if (provider in PiProvider.needsBaseUrl) return PREFIX + "custom-" + profile.id
        val owner = profiles.firstOrNull { it.provider == profile.provider }
        return if (owner == null || owner.id == profile.id) provider.id else PREFIX + profile.id
    }

    /**
     * The environment every launch carries: each profile's key under its own variable,
     * and the owning profile's key under the provider's own variable as well.
     *
     * The second half is for pi's built-in providers, whose credentials it reads from
     * their documented variables — a launch that only carried `PIKIT_KEY_…` would have a
     * `deepseek` provider with no credential and would report it as unusable. Where two
     * providers share one variable (Moonshot and its CN endpoint, the three Qwen ids) the
     * first profile in store order wins, which is the same rule [runtimeProviderId] uses.
     */
    fun providerEnvironment(snapshot: PiConfigSnapshot): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        for (profile in snapshot.profiles) {
            val key = profile.apiKey.trim()
            if (key.isEmpty()) continue
            environment[keyVariable(profile.id)] = key
            val provider = profile.providerEntry ?: continue
            if (runtimeProviderId(profile, snapshot.profiles) == provider.id) {
                environment.putIfAbsent(provider.envVar, key)
            }
        }
        return environment
    }
}

/** The `api` a catalogue entry states, or blank when it names none. */
internal fun JsonObject.apiName(): String =
    (this["api"] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** The `baseUrl` a catalogue entry states, or blank when it names none. */
internal fun JsonObject.baseUrlName(): String =
    (this["baseUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty()
