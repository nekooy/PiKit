package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.PiSettings
import pi.kit.mob.env.BundledExtension
import java.io.File

/**
 * What `$HOME/.pi/agent/settings.json` says after PiKit has touched it.
 *
 * This is the rule that decides which built-in tools the model can see, and it
 * runs against pi's own settings file, which already holds whatever else the user
 * has configured. Getting it wrong is invisible in the app: pi starts, the
 * session works, and a tool the user asked for is simply not in the list. It is
 * pure, so it is checked here rather than through a device.
 */
class ToolDefaultsTest {

    private val tools = PiLaunchOptions.DEFAULT_TOOLS

    private fun settings(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun JsonObject.defaultTools(): List<String> =
        this["defaultTools"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

    @Test
    fun `absent key becomes exactly the built-in list`() {
        val result = settingsWithDefaultTools(settings("""{"theme":"dark"}"""), tools)

        assertEquals("pi enables nothing extra on its own", tools, result.defaultTools())
    }

    @Test
    fun `an existing list is unioned, the user's entries kept and nothing duplicated`() {
        val existing = settings("""{"defaultTools":["read","web_search","read"]}""")

        val result = settingsWithDefaultTools(existing, tools)

        // `web_search` is the case that matters: pi reads this file as its own
        // config, so an entry put there by the user must survive PiKit's write.
        assertEquals(
            listOf("read", "web_search") + tools.filter { it != "read" },
            result.defaultTools(),
        )
        assertEquals("no entry appears twice", result.defaultTools().distinct(), result.defaultTools())
        // Not merely present — if the array were *replaced* the bug above would
        // still be invisible to `contains`.
        assertTrue("the user's own tool survives", "web_search" in result.defaultTools())
    }

    @Test
    fun `every other key is left untouched`() {
        val existing = settings(
            """
            {
              "defaultTools": ["read"],
              "theme": "dark",
              "compaction": {"enabled": false},
              "extensions": ["guard"]
            }
            """.trimIndent(),
        )

        val result = settingsWithDefaultTools(existing, tools)

        assertEquals("theme", JsonPrimitive("dark"), result["theme"])
        assertEquals(
            "a nested object is carried over verbatim",
            existing["compaction"],
            result["compaction"],
        )
        assertEquals(
            "so is every other array",
            JsonArray(listOf(JsonPrimitive("guard"))),
            result["extensions"],
        )
        assertEquals("no key is added or dropped", existing.keys, result.keys)
    }

    // ------------------------------------------------- pi's own model defaults
    //
    // The chat page gets its model from `--provider`/`--model` on the agent's
    // command line, so it worked while a `pi` typed in the terminal tab reported
    // "No model selected." — that process has no argv and nothing in pi's own
    // settings to fall back on. These are the keys that close that gap, and the
    // rule that they are not written from a half-filled profile.

    private fun merged(existing: JsonObject, settings: PiSettings): JsonObject =
        settingsWithPiDefaults(existing, tools, settings)

    private fun configured(provider: PiProvider = PiProvider.DEEPSEEK): PiSettings = PiSettings(
        provider = provider,
        modelId = "deepseek-chat",
        apiKey = "sk-x",
        thinkingLevel = "high",
    )

    @Test
    fun `a configured profile writes pi's own model defaults`() {
        val result = merged(settings("""{"theme":"dark"}"""), configured())

        assertEquals("deepseek", result["defaultProvider"]!!.jsonPrimitive.content)
        assertEquals("deepseek-chat", result["defaultModel"]!!.jsonPrimitive.content)
        assertEquals("high", result["defaultThinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the defaults are merged, not written as a fresh document`() {
        val existing = settings(
            """
            {
              "theme": "dark",
              "compaction": {"enabled": false},
              "extensions": ["guard"],
              "defaultModel": "something-else"
            }
            """.trimIndent(),
        )

        val result = merged(existing, configured())

        // `existing.keys ⊆ result.keys` is the assertion that matters: this file is
        // pi's own and the user's `theme`, `compaction` and extension list must come
        // through PiKit's write untouched, with only the named keys replaced.
        assertTrue("no key the user had is dropped", result.keys.containsAll(existing.keys))
        assertEquals("theme", JsonPrimitive("dark"), result["theme"])
        assertEquals(existing["compaction"], result["compaction"])
        assertEquals(existing["extensions"], result["extensions"])
        assertEquals("pi's key is replaced, not appended to", "deepseek-chat", result["defaultModel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unconfigured profile leaves pi's existing defaults alone`() {
        val existing = settings("""{"defaultProvider":"openai","defaultModel":"gpt-5"}""")

        val result = merged(existing, PiSettings())

        // Writing a half-filled default would make a hand-run pi fall back to *some*
        // provider rather than saying that nothing is configured — worse than the
        // "No model selected." it prints today.
        assertEquals(existing["defaultProvider"], result["defaultProvider"])
        assertEquals(existing["defaultModel"], result["defaultModel"])
        assertNull("nothing new is invented", result["defaultThinkingLevel"])
    }

    @Test
    fun `a custom provider with no base url is not configured`() {
        val existing = settings("""{"defaultProvider":"openai"}""")

        val result = merged(
            existing,
            PiSettings(provider = PiProvider.CUSTOM, modelId = "m", apiKey = "sk", baseUrl = "  "),
        )

        assertEquals(existing["defaultProvider"], result["defaultProvider"])
        assertNull(result["defaultModel"])
    }

    // ---------------------------------------------------- the bundled extension

    private val extension = BundledExtension(
        name = "pi-web-access",
        version = "1.0.0",
        directory = File("/data/data/pi.kit.mob/files/usr/lib/node_modules/pi-web-access"),
    )

    private fun JsonObject.packages(): List<String> =
        (this["packages"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun withExtension(existing: JsonObject): JsonObject =
        settingsWithPiDefaults(existing, tools, PiSettings(), bundledExtension = extension)

    @Test
    fun `the bundled extension is registered by absolute directory path`() {
        val result = withExtension(settings("{}"))

        // A plain path is pi's *local path* package source, and the path is the
        // directory the build wrote under `$PREFIX`, not a relative one: pi resolves
        // it from whatever the process's cwd happens to be.
        assertEquals(listOf(extension.directory.absolutePath), result.packages())
    }

    @Test
    fun `an entry the user added is never removed`() {
        val existing = settings(
            """{"packages":["npm:pi-something","/home/user/.pi/extensions/mine"]}""",
        )

        val result = withExtension(existing)

        assertEquals(
            listOf("npm:pi-something", "/home/user/.pi/extensions/mine", extension.directory.absolutePath),
            result.packages(),
        )
    }

    @Test
    fun `an entry that is already present is not added again`() {
        // Built as a `JsonObject` rather than interpolated into a string: on a
        // Windows test JVM the absolute path carries backslashes, and a raw `\d`
        // inside a JSON literal is an invalid escape — the test would fail on the
        // host for a reason that has nothing to do with the merge rule. On the
        // device the path is a plain `/data/data/…`, which is what the app writes.
        val existing = JsonObject(
            mapOf(
                "packages" to JsonArray(listOf(JsonPrimitive(extension.directory.absolutePath))),
            ),
        )

        val result = withExtension(existing)

        // `defaultTools` is unioned in unconditionally, so the whole document is
        // not equal; the assertion that matches the rule — and the one that keeps
        // the file's mtime, because `writePiDefaults` skips an unchanged encoding —
        // is that `packages` came back exactly as it went in, once.
        assertEquals(listOf(extension.directory.absolutePath), result.packages())
    }

    @Test
    fun `a runtime without the extension writes no packages key`() {
        val existing = settings("""{"theme":"dark"}""")

        val result = settingsWithPiDefaults(existing, tools, PiSettings(), bundledExtension = null)

        assertNull(result["packages"])
    }

    @Test
    fun `a packages key that is not an array is not reinterpreted`() {
        // pi's own schema wants an array here; a string is a file this app does not
        // understand, and the rule for those is to leave the key alone rather than
        // replace it with PiKit's idea of the value.
        val existing = settings("""{"packages":"npm:pi-something"}""")

        val result = withExtension(existing)

        assertEquals(JsonPrimitive("npm:pi-something"), result["packages"])
    }
}
