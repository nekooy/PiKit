package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The endpoint URL this app hands pi, and the path shapes a model list can arrive in.
 *
 * The `/v1` completion is the fix for the custom-endpoint report of severe lag and
 * frequent disconnects: pi's `openai-completions` path posts to
 * `<baseUrl>/chat/completions`, every example in pi's own `docs/models.md` carries
 * `/v1`, and a relay reached one segment short fails as three connection retries.
 * A URL that already names a path is left alone — `https://gateway.example.com/openai`
 * is a real shape and rewriting it would break the one endpoint that worked.
 *
 * Which way the version segment goes is the *protocol's* answer rather than this
 * function's taste, and the other direction is the reported `404 … POST
 * /v1/v1/messages`: `anthropic-messages` reaches the endpoint through
 * `@anthropic-ai/sdk`, which appends `/v1/messages` to whatever base it is given.
 */
class NormalizeApiBaseUrlTest {

    @Test
    fun `a bare host gains the version segment pi posts under`() {
        assertEquals(
            "https://relay.example.com/v1",
            normalizeApiBaseUrl("https://relay.example.com"),
        )
        assertEquals(
            "https://relay.example.com/v1",
            normalizeApiBaseUrl("  https://relay.example.com/  "),
        )
    }

    @Test
    fun `a URL that already names a path is left alone`() {
        assertEquals(
            "https://relay.example.com/v1",
            normalizeApiBaseUrl("https://relay.example.com/v1"),
        )
        assertEquals(
            "https://gateway.example.com/openai",
            normalizeApiBaseUrl("https://gateway.example.com/openai"),
        )
        assertEquals(
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            normalizeApiBaseUrl(
                "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            ),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            normalizeApiBaseUrl("https://generativelanguage.googleapis.com/v1beta"),
        )
    }

    @Test
    fun `an anthropic base never carries the version its client appends`() {
        // `@anthropic-ai/sdk` is handed this base and posts `<base>/v1/messages` itself,
        // so a `/v1` here is the doubled path of the report:
        // `404 {"error":{"message":"Invalid URL (POST /v1/v1/messages)"…}}`.
        val anthropic = CustomApi.ANTHROPIC_MESSAGES.id
        assertEquals("https://cf.api.fan", normalizeApiBaseUrl("https://cf.api.fan", anthropic))
        assertEquals("https://cf.api.fan", normalizeApiBaseUrl("https://cf.api.fan/v1", anthropic))
        assertEquals("https://cf.api.fan", normalizeApiBaseUrl("  https://cf.api.fan/v1/  ", anthropic))
        // A gateway's own prefix is not the version segment: it stays, the `/v1` that a
        // provider's dashboard documents for its OpenAI endpoint comes off.
        assertEquals(
            "https://api.minimax.io/anthropic",
            normalizeApiBaseUrl("https://api.minimax.io/anthropic/v1", anthropic),
        )
        assertEquals(
            "https://gateway.example.com/anthropic",
            normalizeApiBaseUrl("https://gateway.example.com/anthropic", anthropic),
        )
        // A query is stripped the same way, and the shape survives it.
        assertEquals(
            "https://cf.api.fan",
            normalizeApiBaseUrl("https://cf.api.fan/v1?token=abc", anthropic),
        )
        assertEquals(
            "https://cf.api.fan",
            normalizeApiBaseUrl("https://cf.api.fan?token=abc", anthropic),
        )
    }

    @Test
    fun `the reported request path is not doubled`() {
        // The whole chain the report came through: a relay's OpenAI base pasted onto a
        // profile whose protocol is Anthropic, written into `models.json` by this app.
        val entry = CustomEndpoint.providerObject(
            baseUrl = "https://cf.api.fan/v1",
            modelIds = listOf("deepseek-flash"),
            api = CustomApi.ANTHROPIC_MESSAGES.id,
        )!!
        val base = entry["baseUrl"]!!.toString().trim('"')
        assertEquals("https://cf.api.fan", base)
        // What pi's Anthropic client will ask for, `path` being `/v1/messages`.
        assertEquals("https://cf.api.fan/v1/messages", "$base/v1/messages")
    }

    @Test
    fun `empty stays empty, and a bare host is not completed twice`() {
        assertEquals("", normalizeApiBaseUrl("   "))
        assertEquals("https://x.example/v1", normalizeApiBaseUrl("https://x.example/v1"))
    }

    @Test
    fun `a string that is not an absolute http(s) URL is refused`() {
        // `localhost:11434` looks like it has a scheme and does not: `URI` reads
        // `localhost` as the scheme. Writing it into `models.json` produces a
        // provider that cannot be reached and an error that names neither the
        // field nor the missing `https://`.
        assertEquals("", normalizeApiBaseUrl("localhost:11434"))
        assertEquals("", normalizeApiBaseUrl("relay.example.com/v1"))
        assertEquals("", normalizeApiBaseUrl("ftp://relay.example.com"))
        assertEquals("", normalizeApiBaseUrl("https://"))
    }

    @Test
    fun `a query or fragment is stripped rather than appended to`() {
        // `$base/models` is how every model-list URL is built. A query left in
        // the base becomes `https://h/v1?x=1/models` — a path the provider never
        // named — and the request fails for a URL that looked correct on screen.
        assertEquals(
            "https://relay.example.com/v1",
            normalizeApiBaseUrl("https://relay.example.com/v1?token=abc"),
        )
        assertEquals(
            "https://relay.example.com/v1",
            normalizeApiBaseUrl("https://relay.example.com?token=abc"),
        )
        assertEquals(
            "https://relay.example.com/v1",
            normalizeApiBaseUrl("https://relay.example.com/v1#frag"),
        )
    }

    @Test
    fun `a custom endpoint's registered baseUrl is completed`() {
        val provider = CustomEndpoint.providerObject(
            baseUrl = "https://relay.example.com",
            modelIds = listOf("m"),
        )!!
        assertEquals(
            "https://relay.example.com/v1",
            provider["baseUrl"]!!.toString().trim('"'),
        )
    }

    @Test
    fun `a custom endpoint with no usable URL registers nothing`() {
        assertEquals(
            null,
            CustomEndpoint.providerObject("localhost:11434", listOf("m")),
        )
    }
}
