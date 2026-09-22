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
