package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.ModelProfile

/**
 * The title request is the app's only direct provider call, so its pure parts are
 * pinned here: where each provider's endpoint is built, what each envelope's text
 * looks like, and how a model's answer becomes one drawer-sized line.
 */
class TitleGeneratorTest {

    private fun profile(
        provider: String,
        model: String = "test-model",
        baseUrl: String = "",
        api: String = "",
    ) = ModelProfile(
        id = "p1",
        provider = provider,
        apiKey = "secret",
        modelId = model,
        baseUrl = baseUrl,
        api = api,
    )

    @Test
    fun `deepseek is an OpenAI-compatible endpoint`() {
        val endpoint = titleEndpoint(profile("deepseek"))!!
        assertEquals(TitleApi.OpenAiCompletions, endpoint.api)
        assertEquals("https://api.deepseek.com/v1/chat/completions", endpoint.url)
        assertEquals("Bearer secret", endpoint.headers["Authorization"])
    }

    @Test
    fun `anthropic posts to its own messages path`() {
        val endpoint = titleEndpoint(profile("anthropic"))!!
        assertEquals(TitleApi.AnthropicMessages, endpoint.api)
        assertEquals("https://api.anthropic.com/v1/messages", endpoint.url)
        assertEquals("secret", endpoint.headers["x-api-key"])
    }

    @Test
    fun `a bare custom base gains the OpenAI version segment`() {
        val endpoint = titleEndpoint(
            profile("pikit-custom", baseUrl = "https://relay.example.com", api = "openai-completions"),
        )!!
        assertEquals("https://relay.example.com/v1/chat/completions", endpoint.url)
    }

    @Test
    fun `a custom Anthropic base is not doubled at the version segment`() {
        val endpoint = titleEndpoint(
            profile("pikit-custom", baseUrl = "https://relay.example.com/v1", api = "anthropic-messages"),
        )!!
        assertEquals("https://relay.example.com/v1/messages", endpoint.url)
    }

    @Test
    fun `an unknown provider with no base URL is refused rather than guessed`() {
        assertNull(titleEndpoint(profile("kimi-coding")))
        assertNull(titleEndpoint(profile("pikit-custom", baseUrl = "")))
    }

    @Test
    fun `each envelope's text is read`() {
        assertEquals(
            "Fix login redirects",
            titleFromResponse(
                TitleApi.OpenAiCompletions,
                """{"choices":[{"message":{"content":"Fix login redirects"}}]}""",
            ),
        )
        assertEquals(
            "Fix login redirects",
            titleFromResponse(
                TitleApi.AnthropicMessages,
                """{"content":[{"type":"text","text":"Fix login redirects"}]}""",
            ),
        )
        assertEquals(
            "Fix login redirects",
            titleFromResponse(
                TitleApi.OpenAiResponses,
                """{"output":[{"content":[{"type":"output_text","text":"Fix login redirects"}]}]}""",
            ),
        )
        assertEquals(
            "Fix login redirects",
            titleFromResponse(
                TitleApi.GoogleGenerative,
                """{"candidates":[{"content":{"parts":[{"text":"Fix login redirects"}]}}]}""",
            ),
        )
    }

    @Test
    fun `a title is one line with the markdown a model adds removed`() {
        assertEquals("Fix login redirects", normalizeTitle("**Fix login redirects**\nextra commentary"))
        assertEquals("Fix login redirects", normalizeTitle("Title: \"Fix login redirects\""))
        assertEquals("Fix login redirects", normalizeTitle("  Fix   login  redirects  "))
        assertEquals("修复登录跳转", normalizeTitle("「修复登录跳转」"))
    }

    @Test
    fun `a title is capped to the drawer row`() {
        val long = "一".repeat(120)
        val title = normalizeTitle(long)
        assertTrue(title.length <= 40)
        assertTrue(title.toByteArray(Charsets.UTF_8).size <= 80)
    }

    @Test
    fun `the body carries the prompt and asks for a short answer`() {
        val endpoint = titleEndpoint(profile("deepseek"))!!
        val request = titleRequestBody(endpoint, "帮我写一个快速排序")
        assertTrue(request.contains("帮我写一个快速排序"))
        assertTrue(request.contains("\"max_tokens\":64"))
        assertTrue(request.contains("plain text of natural language"))
    }
}
