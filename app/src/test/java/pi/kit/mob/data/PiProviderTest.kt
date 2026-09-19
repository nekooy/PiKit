package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider list, against pi's own.
 *
 * A provider id or an environment variable name that does not match pi's is a *silent*
 * failure at the worst possible moment: the profile saves, the agent launches, and the
 * provider reports "no authentication method configured" or answers `401` with a key that
 * is perfectly good — with the wrong variable name nowhere in the message. So the table
 * below is pi's, copied from `getApiKeyEnvVars` in the bundled
 * `@earendil-works/pi-ai/providers/all` (`dist/bundle/chunks/chunk-JVUZSMYM.js`,
 * the same table pi's help text prints), and this test is what makes a typo in the enum a
 * failing build rather than a support question.
 *
 * It also pins the *scope* of the list, which is a decision rather than an omission: a
 * provider is here when a phone form with one key field can configure it. Pi's OAuth-only
 * providers and the ones that need a second input (a resource endpoint, a project, an
 * account id, AWS credentials) are deliberately absent, and their ids are listed below so
 * that "why is this not in the picker" is answered by the code instead of by memory.
 */
class PiProviderTest {

    /** pi's provider id -> the environment variable it reads, for the single-key ones. */
    private val piSingleKeyProviders = mapOf(
        "ant-ling" to "ANT_LING_API_KEY",
        "anthropic" to "ANTHROPIC_API_KEY",
        "baseten" to "BASETEN_API_KEY",
        "cerebras" to "CEREBRAS_API_KEY",
        "deepseek" to "DEEPSEEK_API_KEY",
        "fireworks" to "FIREWORKS_API_KEY",
        "google" to "GEMINI_API_KEY",
        "groq" to "GROQ_API_KEY",
        "huggingface" to "HF_TOKEN",
        "kimi-coding" to "KIMI_API_KEY",
        "minimax" to "MINIMAX_API_KEY",
        "minimax-cn" to "MINIMAX_CN_API_KEY",
        "mistral" to "MISTRAL_API_KEY",
        "moonshotai" to "MOONSHOT_API_KEY",
        "moonshotai-cn" to "MOONSHOT_API_KEY",
        "nvidia" to "NVIDIA_API_KEY",
        "openai" to "OPENAI_API_KEY",
        "opencode" to "OPENCODE_API_KEY",
        "opencode-go" to "OPENCODE_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "qwen-token-plan" to "QWEN_TOKEN_PLAN_API_KEY",
        "qwen-token-plan-cn" to "QWEN_TOKEN_PLAN_CN_API_KEY",
        "qwen-token-plan-individual" to "QWEN_TOKEN_PLAN_API_KEY",
        "together" to "TOGETHER_API_KEY",
        "vercel-ai-gateway" to "AI_GATEWAY_API_KEY",
        "xai" to "XAI_API_KEY",
        "xiaomi" to "XIAOMI_API_KEY",
        "xiaomi-token-plan-ams" to "XIAOMI_TOKEN_PLAN_AMS_API_KEY",
        "xiaomi-token-plan-cn" to "XIAOMI_TOKEN_PLAN_CN_API_KEY",
        "xiaomi-token-plan-sgp" to "XIAOMI_TOKEN_PLAN_SGP_API_KEY",
        "zai" to "ZAI_API_KEY",
        "zai-coding-cn" to "ZAI_CODING_CN_API_KEY",
    )

    /**
     * Providers pi has and the picker does not, and why.
     *
     * Not a list of bugs: each of these needs something the form has no field for, so
     * offering it would produce a provider that looks configurable and cannot work.
     * `GithubCopilot` and `OpenaiCodex` are the normal credential being an interactive
     * login; the rest need a second input.
     */
    private val deliberatelyAbsent = listOf(
        // OAuth login, which is the provider's normal credential path.
        "github-copilot",
        "openai-codex",
        // More than one input: a resource endpoint, a project and location, an account
        // id (+ a gateway slug), AWS credentials.
        "azure-openai-responses",
        "google-vertex",
        "cloudflare-workers-ai",
        "cloudflare-ai-gateway",
        "amazon-bedrock",
        // A gateway URL fetched from a service before anything can be selected.
        "radius",
        // A local server with no credential at all: llama.cpp is added by one of pi's own
        // extensions, and `LLAMA_API_KEY` is for a *hosted* llama.cpp, not the local one.
        "llama.cpp",
    )

    @Test
    fun `every single-key provider pi has is in the picker, with pi's own env var`() {
        val ours = PiProvider.entries
            .filter { it != PiProvider.CUSTOM }
            .associate { it.id to it.envVar }

        assertEquals(
            "the list is pi's getApiKeyEnvVars, filtered to the single-key providers",
            piSingleKeyProviders,
            ours,
        )
    }

    @Test
    fun `no provider pi cannot configure from one key is offered`() {
        deliberatelyAbsent.forEach { id ->
            assertFalse(
                "$id cannot be configured from this form; see the reasons in this test",
                PiProvider.entries.any { it.id == id },
            )
        }
    }

    @Test
    fun `every provider maps back from its own id`() {
        PiProvider.entries.forEach { provider ->
            assertEquals(provider, PiProvider.fromId(provider.id))
        }
        assertEquals(null, PiProvider.fromId("nope"))
        assertEquals(null, PiProvider.fromId(null))
    }

    @Test
    fun `ids and labels are usable`() {
        val ids = PiProvider.entries.map { it.id }
        assertEquals("no duplicate provider ids", ids.size, ids.distinct().size)
        PiProvider.entries.forEach { provider ->
            assertTrue(
                "${provider.id} must be a kebab-case pi id",
                provider.id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")),
            )
            assertTrue("${provider.id} needs a label", provider.label.isNotBlank())
            // pi's ids are the contract; PiKit's own reserved one is the only exception and
            // it is spelled in one place.
            if (provider != PiProvider.CUSTOM) {
                assertTrue(
                    "${provider.envVar} must look like an environment variable",
                    provider.envVar.matches(Regex("[A-Z][A-Z0-9_]*")),
                )
            }
        }
        assertEquals(CustomEndpoint.PROVIDER_ID, PiProvider.CUSTOM.id)
        assertEquals(CustomEndpoint.API_KEY_ENV, PiProvider.CUSTOM.envVar)
    }

    @Test
    fun `one secret can be shared by two providers, and the picker still tells them apart`() {
        // pi reads the same variable for Moonshot AI's two regions, Qwen's individual
        // plan and OpenCode's two endpoints. That is pi's design, not a mistake here —
        // what the user is choosing between is the model set behind the id — so the
        // labels have to differ.
        val moonshot = PiProvider.entries.filter { it.envVar == "MOONSHOT_API_KEY" }
        assertEquals(2, moonshot.size)
        assertEquals(2, moonshot.map { it.label }.distinct().size)

        val opencode = PiProvider.entries.filter { it.envVar == "OPENCODE_API_KEY" }
        assertEquals(2, opencode.size)
        assertEquals(2, opencode.map { it.label }.distinct().size)

        // And every label is unique, so a picker row is never ambiguous.
        val labels = PiProvider.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test
    fun `only the custom endpoint needs a base URL`() {
        assertEquals(setOf(PiProvider.CUSTOM), PiProvider.needsBaseUrl)
    }
}
