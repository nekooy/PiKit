package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two shapes a "Fetch models" walk has to accept from a user-supplied endpoint.
 *
 * Relays disagree on where `/models` lives and on which envelope the list comes back
 * in. Guessing one of each produced the report that a custom endpoint "often cannot
 * fetch a model list": the host answered 200 with `{"models":[…]}` and the parser
 * looked only under `data`, so the user was told there were no models. Both the path
 * candidates and the three envelopes are pinned here rather than by whichever relay
 * the next report happens to use.
 */
class ModelListShapeTest {

    @Test
    fun `a base that is already versioned lists at the version and at its parent`() {
        assertEquals(
            listOf(
                "https://relay.example.com/v1/models",
                "https://relay.example.com/models",
            ),
            modelListUrls("https://relay.example.com/v1"),
        )
    }

    @Test
    fun `a bare host is completed and also asked without the version`() {
        // `normalizeApiBaseUrl` runs first, so the leading candidate is already `…/v1`.
        assertEquals(
            listOf(
                "https://relay.example.com/v1/models",
                "https://relay.example.com/models",
            ),
            modelListUrls("https://relay.example.com"),
        )
    }

    @Test
    fun `a host with some other path is asked with and without a version under it`() {
        assertEquals(
            listOf(
                "https://gateway.example.com/openai/models",
                "https://gateway.example.com/openai/v1/models",
            ),
            modelListUrls("https://gateway.example.com/openai"),
        )
    }

    @Test
    fun `empty base yields no candidates`() {
        assertEquals(emptyList<String>(), modelListUrls("   "))
    }

    @Test
    fun `the OpenAI envelope is read under data`() {
        assertEquals(
            listOf("gpt-4.1", "o4-mini"),
            parseModelIds(
                """{"object":"list","data":[{"id":"gpt-4.1"},{"id":"o4-mini"}]}""",
                listOf("data", "models"),
            ),
        )
    }

    @Test
    fun `a relay that answers under models is read`() {
        assertEquals(
            listOf("relay-a", "relay-b"),
            parseModelIds(
                """{"models":[{"id":"relay-a"},{"id":"relay-b"}]}""",
                listOf("data", "models"),
            ),
        )
    }

    @Test
    fun `a bare array is read`() {
        assertEquals(
            listOf("m1", "m2"),
            parseModelIds("""[{"id":"m1"},{"id":"m2"}]""", listOf("data", "models")),
        )
    }

    @Test
    fun `Google's models prefix is stripped and name is a fallback`() {
        assertEquals(
            listOf("gemini-2.5-pro", "gemma-4"),
            parseModelIds(
                """{"models":[{"name":"models/gemini-2.5-pro"},{"name":"gemma-4"}]}""",
                listOf("models"),
            ),
        )
    }

    @Test
    fun `an empty or unreadable body is an empty list, not a crash`() {
        assertEquals(emptyList<String>(), parseModelIds("", listOf("data")))
        assertEquals(emptyList<String>(), parseModelIds("not json", listOf("data")))
        assertEquals(emptyList<String>(), parseModelIds("""{"data":{}}""", listOf("data")))
        assertTrue(parseModelIds("""{"data":[{"id":"  "}]}""", listOf("data")).isEmpty())
    }
}
