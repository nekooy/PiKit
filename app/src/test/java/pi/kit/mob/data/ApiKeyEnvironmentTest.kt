package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The credential a `pi` started by hand in the terminal tab inherits.
 *
 * The chat page has always worked without this because the agent is launched with
 * `--provider`, `--model` and the key in its environment. A hand-run `pi` gets no
 * argv at all: `findInitialModel` falls through its scopes, finds no provider with
 * auth configured and prints "No model selected." — so the name of the variable has
 * to be exactly the one pi reads for the profile's provider, which is what this
 * pins.
 */
class ApiKeyEnvironmentTest {

    @Test
    fun `a built-in provider maps to the variable pi reads for it`() {
        val environment = apiKeyEnvironment(
            PiSettings(provider = PiProvider.DEEPSEEK, apiKey = "sk-x"),
        )

        assertEquals(mapOf("DEEPSEEK_API_KEY" to "sk-x"), environment)
    }

    @Test
    fun `a custom endpoint maps to the variable its models json entry references`() {
        val environment = apiKeyEnvironment(
            PiSettings(provider = PiProvider.CUSTOM, apiKey = "sk-relay", baseUrl = "https://r.example"),
        )

        // `CustomEndpoint.API_KEY_ENV`, and the same string its generated provider
        // entry puts in `apiKey`; a mismatch would leave the relay unauthenticated.
        assertEquals(mapOf(CustomEndpoint.API_KEY_ENV to "sk-relay"), environment)
    }

    @Test
    fun `a blank key passes nothing rather than an empty variable`() {
        val environment = apiKeyEnvironment(
            PiSettings(provider = PiProvider.DEEPSEEK, apiKey = "   "),
        )

        // An empty `DEEPSEEK_API_KEY` is not "no credential" to pi — it is a
        // credential that is present and wrong, which turns a clear "No model
        // selected." into an authentication error on the first prompt.
        assertEquals(emptyMap<String, String>(), environment)
    }

    @Test
    fun `no provider passes nothing`() {
        assertEquals(emptyMap<String, String>(), apiKeyEnvironment(PiSettings(apiKey = "sk-x")))
    }

    @Test
    fun `the key is trimmed before it is exported`() {
        // The launcher passes the stored key as it is (`PiProcessLauncher.launch`);
        // a profile whose key was pasted with a trailing newline must still reach pi
        // as a usable credential rather than as an authentication failure. Trimming
        // here and testing for blank after it is what keeps the pasted-whitespace
        // case from producing a *present but wrong* variable.
        val environment = apiKeyEnvironment(
            PiSettings(provider = PiProvider.OPENAI, apiKey = " sk-y\n"),
        )

        assertEquals(mapOf("OPENAI_API_KEY" to "sk-y"), environment)
    }
}
