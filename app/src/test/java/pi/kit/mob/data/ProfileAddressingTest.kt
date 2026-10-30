package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a saved profile is addressed and how its credential reaches pi.
 *
 * The chat page has always worked without this because the agent is launched with
 * `--provider`, `--model` and one key in its environment. What changed is that the
 * environment now carries **every** saved profile: pi decides which providers are usable
 * from credentials alone, so a profile whose credential is present is a model the model
 * picker can switch to with `set_model`, and one whose credential is missing is a
 * relaunch. These tests pin the naming rules, because a variable name that does not match
 * the one `models.json` references is a relay that answers `401`.
 */
class ProfileAddressingTest {

    private fun profile(
        id: String,
        provider: PiProvider,
        key: String = "sk-$id",
        baseUrl: String = "",
        modelId: String = "model-$id",
    ) = ModelProfile(
        id = id,
        provider = provider.id,
        apiKey = key,
        modelId = modelId,
        baseUrl = baseUrl,
    )

    // ------------------------------------------------------------ the address

    @Test
    fun `the first profile of a built-in provider keeps pi's own id`() {
        val first = profile("a", PiProvider.DEEPSEEK)
        val second = profile("b", PiProvider.DEEPSEEK)

        assertEquals("deepseek", ProfileAddressing.runtimeProviderId(first, listOf(first, second)))
    }

    @Test
    fun `a second profile of one provider gets an id of this app's own`() {
        val first = profile("a", PiProvider.DEEPSEEK)
        val second = profile("b", PiProvider.DEEPSEEK)

        // pi keys one credential and one baseUrl per provider id, so two saved keys for
        // DeepSeek cannot both be called `deepseek` — the second would be unreachable
        // and the first would lose its endpoint.
        assertEquals(
            ProfileAddressing.PREFIX + "b",
            ProfileAddressing.runtimeProviderId(second, listOf(first, second)),
        )
    }

    @Test
    fun `a custom endpoint is never addressed by the shared id`() {
        val relay = profile("r", PiProvider.CUSTOM, baseUrl = "https://r.example")

        // `pikit-custom` was a single endpoint: every relay saved under it would be the
        // same provider to pi, with one key and one host.
        assertEquals(
            ProfileAddressing.PREFIX + "custom-r",
            ProfileAddressing.runtimeProviderId(relay, listOf(relay)),
        )
        assertTrue(
            "and it does not collide with a second profile's id",
            ProfileAddressing.runtimeProviderId(relay, listOf(relay)) !=
                ProfileAddressing.PREFIX + "r",
        )
    }

    @Test
    fun `a provider this build does not know is passed through unchanged`() {
        val unknown = ModelProfile(id = "x", provider = "some-new-provider", apiKey = "sk", modelId = "m")

        assertEquals("some-new-provider", ProfileAddressing.runtimeProviderId(unknown, listOf(unknown)))
    }

    // -------------------------------------------------- the id a launch may name

    @Test
    fun `a launch names the profile's own id only when the file defines it`() {
        val first = profile("a", PiProvider.DEEPSEEK)
        val second = profile("b", PiProvider.DEEPSEEK)
        val profiles = listOf(first, second)

        assertEquals(
            ProfileAddressing.PREFIX + "b",
            ProfileAddressing.launchProviderId(second, profiles, setOf(ProfileAddressing.PREFIX + "b")),
        )
    }

    @Test
    fun `an id the file does not define falls back to the provider pi ships`() {
        val first = profile("a", PiProvider.DEEPSEEK)
        val second = profile("b", PiProvider.DEEPSEEK)
        val profiles = listOf(first, second)

        // Measured on a device: a second DeepSeek profile whose entry could not be built
        // was launched as `--provider pikit-ba076308`, and pi answered
        // `Unknown provider "pikit-ba076308"` and exited — a chat page that cannot start
        // and cannot be fixed by tapping Retry. pi's own id at least runs, with the
        // profile's key as `--api-key`.
        assertEquals("deepseek", ProfileAddressing.launchProviderId(second, profiles, emptySet()))
    }

    @Test
    fun `the profile that owns a provider's id never needs an entry`() {
        val first = profile("a", PiProvider.DEEPSEEK)
        val second = profile("b", PiProvider.DEEPSEEK)

        assertEquals(
            "deepseek",
            ProfileAddressing.launchProviderId(first, listOf(first, second), emptySet()),
        )
    }

    @Test
    fun `a relay without an entry falls back to the id it was stored under`() {
        val relay = profile("r", PiProvider.CUSTOM, baseUrl = "https://r.example")

        // There is no pi-shipped provider behind a relay, so the fallback cannot rescue
        // one whose entry is missing: the launch fails exactly as it did before per-profile
        // ids existed. What must not happen is a *different* unknown id.
        assertEquals(
            PiProvider.CUSTOM.id,
            ProfileAddressing.launchProviderId(relay, listOf(relay), emptySet()),
        )
    }

    // -------------------------------------------------------- the environment

    @Test
    fun `every profile contributes its own variable`() {
        val first = profile("a", PiProvider.DEEPSEEK, key = "sk-a")
        val second = profile("b", PiProvider.DEEPSEEK, key = "sk-b")
        val snapshot = PiConfigSnapshot(listOf(first, second), activeProfileId = "a")

        val environment = ProfileAddressing.providerEnvironment(snapshot)

        // One per profile, named after the profile: that is what its `models.json` entry
        // interpolates, and what makes the second DeepSeek key resolvable at all.
        assertEquals("sk-a", environment[ProfileAddressing.keyVariable("a")])
        assertEquals("sk-b", environment[ProfileAddressing.keyVariable("b")])
    }

    @Test
    fun `the built-in variable carries the profile pi knows that provider's id for`() {
        val first = profile("a", PiProvider.DEEPSEEK, key = "sk-a")
        val second = profile("b", PiProvider.DEEPSEEK, key = "sk-b")
        val snapshot = PiConfigSnapshot(listOf(first, second), activeProfileId = "b")

        val environment = ProfileAddressing.providerEnvironment(snapshot)

        // `deepseek` is pi's own provider id, so its credential comes from its documented
        // variable — and only one profile can own that id. The active profile does *not*
        // decide this: which id a profile is registered under is fixed at save order, or
        // a switch would move entries around in pi's file.
        assertEquals("sk-a", environment["DEEPSEEK_API_KEY"])
    }

    @Test
    fun `a custom endpoint contributes only its own variable`() {
        val relay = profile("r", PiProvider.CUSTOM, key = "sk-relay", baseUrl = "https://r.example")
        val snapshot = PiConfigSnapshot(listOf(relay), activeProfileId = "r")

        assertEquals(
            mapOf(ProfileAddressing.keyVariable("r") to "sk-relay"),
            ProfileAddressing.providerEnvironment(snapshot),
        )
    }

    @Test
    fun `a blank key passes nothing rather than an empty variable`() {
        val blank = profile("a", PiProvider.DEEPSEEK, key = "   ")
        val snapshot = PiConfigSnapshot(listOf(blank), activeProfileId = "a")

        // An empty `DEEPSEEK_API_KEY` is not "no credential" to pi — it is a credential
        // that is present and wrong, which turns a clear "No model selected." into an
        // authentication error on the first prompt.
        assertEquals(emptyMap<String, String>(), ProfileAddressing.providerEnvironment(snapshot))
    }

    @Test
    fun `the key is trimmed before it is exported`() {
        val pasted = profile("a", PiProvider.OPENAI, key = " sk-y\n")
        val snapshot = PiConfigSnapshot(listOf(pasted), activeProfileId = "a")

        assertEquals(
            mapOf(
                ProfileAddressing.keyVariable("a") to "sk-y",
                "OPENAI_API_KEY" to "sk-y",
            ),
            ProfileAddressing.providerEnvironment(snapshot),
        )
    }

    @Test
    fun `two providers sharing one variable do not overwrite each other`() {
        // Moonshot and its CN endpoint are separate providers with one documented
        // variable; the three Qwen ids are the same shape. The first profile in store
        // order wins, which is the rule `runtimeProviderId` already uses for the id.
        val moonshot = profile("m", PiProvider.MOONSHOTAI, key = "sk-m")
        val cn = profile("c", PiProvider.MOONSHOTAI_CN, key = "sk-c")
        val snapshot = PiConfigSnapshot(listOf(moonshot, cn), activeProfileId = "c")

        val environment = ProfileAddressing.providerEnvironment(snapshot)

        assertEquals("sk-m", environment["MOONSHOT_API_KEY"])
        assertEquals("sk-c", environment[ProfileAddressing.keyVariable("c")])
    }

    @Test
    fun `the reference a models json entry names is the variable this exports`() {
        // The two halves have to agree: a document that names `$PIKIT_KEY_x` and an
        // environment that exports `PIKIT_KEY_X` is a provider pi marks unconfigured.
        val p = profile("x", PiProvider.CUSTOM, baseUrl = "https://r.example")
        val reference = ProfileAddressing.apiKeyReference("x")

        assertTrue(reference.startsWith("$"))
        assertEquals(
            ProfileAddressing.keyVariable("x"),
            reference.removePrefix("$").replace("\${", "").replace("}", ""),
        )
        assertTrue(ProfileAddressing.keyVariable("x") in ProfileAddressing.providerEnvironment(PiConfigSnapshot(listOf(p), "x")))
    }
}
