package pi.kit.mob.pi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.locales.Lang
import pi.kit.mob.locales.stringsFor

/**
 * What PiKit writes into, and reads out of, `$HOME/.pi/agent/web-search.json`.
 *
 * The extension's own config, and it holds the user's API keys; the settings page is
 * the only thing in the app that writes it. Three properties matter more than the
 * rest and none is visible on a device:
 *
 *  - **What lands on disk is strict JSON with no comment in it.** The extension
 *    parses the file with a bare `JSON.parse`, so the annotated document — which is
 *    what the page shows — must never be the file. That was the bug: every web tool
 *    failed with `Unexpected token '/' … is not valid JSON`.
 *  - **Nothing is lost by a control being tapped**, because that file is the only
 *    copy of whatever the user configured. Checked by rendering and reading back.
 *  - **A file an older build annotated is repaired**, or the fix would only reach
 *    fresh installs.
 */
class WebSearchStoreTest {

    private val empty = JsonObject(emptyMap())

    private val header = listOf("test document")

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun render(
        document: JsonObject = empty,
        settings: WebSearchSettings = WebSearchSettings(),
    ): String = renderWebSearchDocument(document, settings, Lang.ENGLISH, header)

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.value(key: String): JsonPrimitive = this[key]!!.jsonPrimitive

    /** A value inside a nested object, e.g. `fetch.timeout`. */
    private fun JsonObject.nested(parent: String, key: String): JsonPrimitive =
        obj(parent).value(key)

    /** True when [key] appears as a real key rather than inside a comment. */
    private fun String.hasLiveKey(key: String): Boolean =
        lines().any { it.trimStart().startsWith("\"$key\"") }

    // ------------------------------------------------------- the comment rule

    @Test
    fun `comments are stripped the way pi strips them`() {
        assertEquals("{}", stripJsonComments("{}\n// trailing").trim())
        assertEquals("{\"a\": 1}", stripJsonComments("{\"a\": 1,}").trim())
        assertEquals("{\"a\": [1, 2]}", stripJsonComments("{\"a\": [1, 2,],}").trim())
        // A comment is removed up to the newline; the whitespace it left is not this
        // function's business, which is why the assertions that matter parse instead.
        assertEquals(JsonPrimitive(1), readWebSearchDocument("{\"a\": 1 // note\n}")!!.value("a"))
        assertEquals(
            "a `//` inside a value is not a comment",
            JsonPrimitive("https://example.com"),
            readWebSearchDocument("""{"url": "https://example.com"}""")!!.value("url"),
        )
        // A comment run leaves an empty object behind, not a broken one.
        assertEquals(empty, readWebSearchDocument("{\n// one\n// two\n}"))
        // Block comments are *not* pi's rule, and are not this one's either: a file
        // using one is a file pi cannot read, so it must not read here either.
        assertNull(readWebSearchDocument("""{"a": 1 /* nope */}"""))
    }

    @Test
    fun `a rendered document is one pi could read`() {
        val document = readWebSearchDocument(render())

        assertNotNull("the rendered default parses once comments are stripped", document)
    }

    // ------------------------------------------------- the file, not the document

    @Test
    fun `the file PiKit writes is strict JSON with not one comment in it`() {
        // The bug this pins: the extension reads `web-search.json` with a bare
        // `JSON.parse` — `loadConfiguredProxy` and every provider's `loadConfig` — so
        // the annotated document *cannot* be what lands on disk. It was, and the
        // result was `Failed to load proxy config … Unexpected token '/' … is not
        // valid JSON` on every web_search, fetch_content and source_check call.
        val annotated = render(settings = WebSearchSettings(provider = "brave", proxy = "socks5h://p:1"))

        assertTrue("the document is annotated", annotated.lines().any { it.trimStart().startsWith("//") })
        val written = strictDocument(annotated)

        assertFalse(
            "no comment line reaches the file",
            written.lines().any { it.trimStart().startsWith("//") },
        )
        // A `//` *inside a value* is not a comment, and the proxy is the case that
        // proves the stripper's string-literal rule is doing its job.
        assertEquals("socks5h://p:1", readWebSearchSettings(written)!!.proxy)
        // A *bare* parse: nothing stripped, which is what the extension does.
        assertEquals(parse(stripJsonComments(annotated)), parse(written))
        // And the properties that were being relied on before are now free: the file
        // has no trailing comma and no blank-line artefact for anyone to strip.
        assertFalse(Regex(""",\s*[}\]]""").containsMatchIn(written))
        assertTrue("it ends with one newline", written.endsWith("}\n") && !written.endsWith("\n\n"))
    }

    @Test
    fun `a file left annotated by an older build is repaired, and only that shape is`() {
        // The upgrade path: every install that ran the build with the bug has a
        // commented file, and the extension cannot read a single key of it. The
        // repair is `strictDocument` of the same document; nothing else is touched.
        val commented = render(settings = WebSearchSettings(provider = "kagi"))
        assertFalse("the annotated document is not strict JSON", isStrictJsonObject(commented))

        val repaired = strictDocument(commented)
        assertTrue(isStrictJsonObject(repaired))
        assertEquals(parse(stripJsonComments(commented)), parse(repaired))
        assertEquals("kagi", readWebSearchSettings(repaired)!!.provider)

        // A file that is already strict is left alone — including one a user wrote
        // by hand in a shape this app would not produce, which is why the test is on
        // the *bytes* rather than on a re-render.
        val handwritten = "{\n    \"workflow\": \"none\"\n}\n"
        assertTrue(isStrictJsonObject(handwritten))
        // And prose is neither repaired nor refused as strict: it is left for the
        // editor, which is the only way out of a file nothing can read.
        assertFalse(isStrictJsonObject("not json at all"))
        assertFalse(isStrictJsonObject("""{"workflow": "none","""))
    }

    @Test
    fun `every key of the document is in the file, and no example is`() {
        val annotated = render(
            document = parse("""{"kagiApiKey": "keep-me", "fetch": {"retries": 3}}"""),
            settings = WebSearchSettings(fetchTimeoutSeconds = 60),
        )

        val written = parse(strictDocument(annotated))

        assertEquals(JsonPrimitive("keep-me"), written.value("kagiApiKey"))
        assertEquals(JsonPrimitive(3), written.nested("fetch", "retries"))
        assertEquals(JsonPrimitive(60), written.nested("fetch", "timeout"))
        // An example line is a comment, so the key it names is not in the file at
        // all: `braveApiKey` is documented above it and configured by nothing.
        assertNull(written["braveApiKey"])
        assertNull(written["searxngHeaders"])
    }

    @Test
    fun `writing the file is a fixed point too`() {
        // A control tap rewrites the file from what is on disk, and the settings page
        // re-renders the editor from it: neither may drift, so the pair has to be a
        // fixed point in both directions.
        val settings = WebSearchSettings(provider = "brave", proxy = "http://127.0.0.1:8080")
        val once = strictDocument(render(settings = settings))

        val twice = strictDocument(
            renderWebSearchDocument(
                readWebSearchDocument(once)!!,
                readWebSearchSettings(once)!!,
                Lang.ENGLISH,
                header,
            ),
        )

        assertEquals(once, twice)
    }

    // ------------------------------------------------------------ rendering

    @Test
    fun `the defaults are live and everything else is a comment`() {
        val rendered = render()

        // PiKit's six keys, uncommented: this is what makes a fresh install run
        // with `workflow: "none"` rather than the extension's browser curator.
        listOf("workflow", "maxInlineContentChars", "fetch", "webSearch", "tools").forEach {
            assertTrue("$it is live", rendered.hasLiveKey(it))
        }
        // …and the options that are *not* set are comments, which is the whole
        // point: a commented example configures nothing. `fetch` is the container of
        // a live key, so the check is on the *leaves* — `answerProvider` is inside it
        // and is still only an example.
        listOf("provider", "proxy", "braveApiKey", "searxngHeaders", "firecrawlApiKey").forEach {
            assertFalse("$it is not live", rendered.hasLiveKey(it))
        }
        assertTrue("the example is still there", rendered.contains("// \"braveApiKey\""))

        val settings = readWebSearchSettings(rendered)!!
        assertEquals(WebSearchSettings(), settings)
    }

    @Test
    fun `a branch an empty object would break is commented out entirely`() {
        val rendered = render()

        // `searchRouting.providers` and `fetchRouting.providers` are required once
        // their parent exists, so an empty object there is a file the agent refuses
        // to start on — these two are a commented block instead of a live `{}`.
        assertFalse(rendered.hasLiveKey("searchRouting"))
        assertFalse(rendered.hasLiveKey("fetchRouting"))
        assertTrue(rendered.contains("// \"searchRouting\": {"))
        assertTrue(rendered.contains("// \"fetchRouting\": {"))
        // The rest of the document may be an empty object: the extension reads an
        // absent key and an object without it alike.
        assertTrue(rendered.hasLiveKey("githubClone"))
        assertTrue(rendered.hasLiveKey("pdf"))
    }

    @Test
    fun `the document explains itself`() {
        val rendered = render()

        // Every key in the reference appears with its description, and every key
        // that is *not* live also has an example line the reader can uncomment —
        // that is the page's documentation now. The example line carries the key as
        // it appears inside its parent, not the full dotted path.
        assertTrue(rendered.contains("// [in effect] fetch.timeout — "))
        assertTrue(
            "an owned key has no example line, because it is already set",
            !rendered.contains("// \"timeout\""),
        )
        assertTrue(rendered.contains("// fetch.answerProvider — "))
        assertTrue(rendered.contains("// \"answerProvider\": \"openai\""))
        assertTrue(rendered.contains("// githubClone.clonePath — "))
        assertTrue(rendered.contains("// \"clonePath\": \"/tmp/pi-github-repos\""))
    }

    @Test
    fun `a key in the file is marked, and a documented one is not`() {
        // The reader's report this answers: three hundred lines, of which eight are
        // configuration, and `//` was the only thing telling them apart. The marker
        // is written for the keys the file actually gets — the header says what it
        // means — and left off the documentation, which is nearly every line and
        // where a tag would only push the descriptions further right.
        val rendered = render(settings = WebSearchSettings(provider = "brave"))

        listOf("workflow", "maxInlineContentChars", "webSearch", "tools", "fetch").forEach {
            assertTrue("$it is live and marked", rendered.contains("// [in effect] "))
        }
        // Documented only: no marker anywhere in front of its path. The list covers a
        // root key, a nested one, and one inside a whole commented-out block (whose
        // note line `commentedOut` leaves alone because it is already a comment).
        listOf("braveApiKey", "proxy", "fetch.answerProvider", "searchRouting.providers", "githubClone.clonePath")
            .forEach { path ->
                assertTrue("$path is documented", rendered.contains("// $path — "))
                assertFalse("$path carries no marker", rendered.contains("[in effect] $path — "))
            }
        // `provider` is set here, so *its* marker is the one that must be there:
        // the marker follows the value, not the key.
        assertTrue(rendered.contains("[in effect] provider — "))

        // And nothing the marker touches ever reaches the file: it is inside a
        // comment, so the strict document is unchanged by it.
        val written = strictDocument(rendered)
        assertFalse("no marker in the file", written.contains("[in effect]"))
        assertEquals("brave", readWebSearchSettings(written)!!.provider)
    }

    @Test
    fun `the marker is the interface's, and the header names it`() {
        // The document is read by a person, so its legend and its marker are in the
        // language they are using — and the legend is where the marker is explained.
        Lang.entries.forEach { lang ->
            val settings = stringsFor(lang).settings
            val marker = settings.searchConfigInEffect
            assertTrue("$lang has a marker", marker.isNotBlank())
            assertTrue("$lang names its marker in the header", marker in settings.searchConfigHeader)

            val rendered = renderWebSearchDocument(
                empty,
                WebSearchSettings(),
                lang,
                settings.searchConfigHeader.lines(),
            )
            assertTrue("$lang uses it in the document", rendered.contains("[$marker] workflow — "))
        }
    }

    @Test
    fun `settings survive a render and a read`() {
        val settings = WebSearchSettings(
            enabled = false,
            workflow = WebSearchSettings.WORKFLOW_SUMMARY_REVIEW,
            provider = "kagi",
            maxInlineContentChars = 12_000,
            fetchTimeoutSeconds = 45,
            proxy = "http://127.0.0.1:8080",
        )

        val rendered = render(settings = settings)

        assertEquals(settings, readWebSearchSettings(rendered))
        // Set keys are live; the master switch writes all five of its keys.
        assertTrue(rendered.hasLiveKey("provider"))
        assertTrue(rendered.hasLiveKey("proxy"))
        assertTrue(rendered.hasLiveKey("webSearch"))
        val written = parse(stripJsonComments(rendered))
        listOf("webSearch", "sourceCheck", "fetchContent", "getSearchContent").forEach { tool ->
            assertEquals(
                "tools.$tool.enabled",
                JsonPrimitive(false),
                written.obj("tools").obj(tool).value("enabled"),
            )
        }
    }

    @Test
    fun `nothing in the file is lost by a control being tapped`() {
        // Every one of these is something the page has no control for: a credential,
        // a key a newer extension added, a sibling of a key PiKit *does* write, and
        // a whole block PiKit does not know.
        val existing = parse(
            """
            {
              "workflow": "none",
              "provider": "kagi",
              "kagiApiKey": "kagi-keep-me",
              "fetch": {"timeout": 5, "retries": 3},
              "pdf": {"provider": "datalab", "maxSizeMB": 30},
              "githubClone": {"enabled": false},
              "fetchRouting": {"providers": ["http", "jina"]},
              "chromeProfile": "Profile 2"
            }
            """.trimIndent(),
        )

        // A control is tapped: the timeout changes and the provider goes away.
        val rendered = render(document = existing, settings = WebSearchSettings(fetchTimeoutSeconds = 60))
        val result = parse(stripJsonComments(rendered))

        assertEquals(JsonPrimitive(60), result.nested("fetch", "timeout"))
        assertEquals("a fetch sibling survives", JsonPrimitive(3), result.nested("fetch", "retries"))
        assertEquals("the credential is still theirs", JsonPrimitive("kagi-keep-me"), result.value("kagiApiKey"))
        assertEquals("a GitHub switch the page no longer has is not re-enabled", JsonPrimitive(false), result.nested("githubClone", "enabled"))
        assertEquals("a PDF limit the page does not show is untouched", JsonPrimitive(30), result.nested("pdf", "maxSizeMB"))
        assertEquals(existing["fetchRouting"], result["fetchRouting"])
        assertEquals(JsonPrimitive("Profile 2"), result.value("chromeProfile"))
        // And the credential is now *live*, because it was live before: this is what
        // makes uncommenting a line in the editor permanent.
        assertTrue(rendered.hasLiveKey("kagiApiKey"))
    }

    @Test
    fun `an unset provider and an unset proxy go back to being comments`() {
        val existing = parse("""{"provider": "exa", "proxy": "http://mcr:4444"}""")

        val rendered = render(document = existing, settings = WebSearchSettings())

        assertFalse(rendered.hasLiveKey("provider"))
        assertFalse(rendered.hasLiveKey("proxy"))
        assertNull(readWebSearchSettings(rendered)!!.provider)
        assertNull(readWebSearchSettings(rendered)!!.proxy)
    }

    @Test
    fun `rendering is a fixed point`() {
        // A launch, a control tap and a save must not keep growing or reordering the
        // file: rendering what was rendered has to give the same bytes back.
        val once = render(settings = WebSearchSettings(provider = "brave"))

        val twice = renderWebSearchDocument(
            readWebSearchDocument(once)!!,
            readWebSearchSettings(once)!!,
            Lang.ENGLISH,
            header,
        )

        assertEquals(once, twice)
    }

    @Test
    fun `a non-object where the schema expects an object is kept`() {
        val existing = parse("""{"tools": false}""")

        val rendered = render(document = existing)

        assertTrue(rendered.hasLiveKey("tools"))
        assertEquals(JsonPrimitive(false), parse(stripJsonComments(rendered)).value("tools"))
    }

    // ------------------------------------------------------------- reading

    @Test
    fun `non-JSON text reads as null so the writer refuses`() {
        // Every one of these is a file the writer must leave exactly as it is, and
        // every one of them is also what the document editor refuses to save.
        assertNull("prose", readWebSearchSettings("not json at all"))
        assertNull("a truncated object", readWebSearchSettings("""{"workflow": "none","""))
        assertNull("an array", readWebSearchSettings("[1, 2, 3]"))
        assertNull("a bare string", readWebSearchSettings("\"none\""))
        assertNull("a comment can hide a missing brace", readWebSearchSettings("{\n// nope\n"))
        assertNull("empty", readWebSearchSettings(""))
    }

    @Test
    fun `a hand-written file with comments and trailing commas is readable`() {
        val text = """
        {
          // this is mine
          "workflow": "auto-summary",
          "provider": "kagi",
          "fetch": { "timeout": 12, },
        }
        """.trimIndent()

        val settings = readWebSearchSettings(text)!!

        assertEquals(WebSearchSettings.WORKFLOW_AUTO_SUMMARY, settings.workflow)
        assertEquals("kagi", settings.provider)
        assertEquals(12, settings.fetchTimeoutSeconds)
    }

    @Test
    fun `the defaults are what the page shows`() {
        val defaults = WebSearchSettings()

        assertTrue("web access is on until it is turned off", defaults.enabled)
        assertEquals(WebSearchSettings.WORKFLOW_NONE, defaults.workflow)
        assertEquals(30_000, defaults.maxInlineContentChars)
        assertEquals(30, defaults.fetchTimeoutSeconds)
        assertNull("the provider is unset, so the extension routes", defaults.provider)
        assertNull(defaults.proxy)

        // The wire values, pinned: a typo here is a key the extension ignores.
        assertEquals("none", WebSearchSettings.WORKFLOW_NONE)
        assertEquals("auto-summary", WebSearchSettings.WORKFLOW_AUTO_SUMMARY)
        assertEquals("summary-review", WebSearchSettings.WORKFLOW_SUMMARY_REVIEW)
        assertFalse(
            "auto is offered as the provider's own row, never as an id",
            WebSearchSettings.PROVIDER_AUTO in WebSearchSettings.SEARCH_PROVIDERS,
        )
    }

    @Test
    fun `the master switch is the five keys the extension resolves, together`() {
        val off = parse(stripJsonComments(render(settings = WebSearchSettings(enabled = false))))

        assertEquals(JsonPrimitive(false), off.nested("webSearch", "enabled"))
        listOf("webSearch", "sourceCheck", "fetchContent", "getSearchContent").forEach { tool ->
            assertEquals(
                "tools.$tool.enabled",
                JsonPrimitive(false),
                off.obj("tools").obj(tool).value("enabled"),
            )
        }
        assertFalse(readWebSearchSettings(render(settings = WebSearchSettings(enabled = false)))!!.enabled)
    }

    @Test
    fun `a file that disabled the four tools individually reads as off`() {
        // The shorthand never covered fetching, so its absence is not a statement
        // that anything is on: these four keys are the extension's own resolution,
        // and all four off is the master switch off.
        val text = """
        {
          "tools": {
            "webSearch": {"enabled": false},
            "sourceCheck": {"enabled": false},
            "fetchContent": {"enabled": false},
            "getSearchContent": {"enabled": false}
          }
        }
        """.trimIndent()

        assertFalse(readWebSearchSettings(text)!!.enabled)
    }

    @Test
    fun `the commands are not the switch's keys, so a file that sets them is read past`() {
        // The switch was widened to the four `commands.*` keys once and the change
        // was withdrawn (ARCHITECTURE §9.2): one boolean over nine keys overwrites a
        // hand-set key on the next tap anywhere on the page. So the commands stay the
        // file's, and this pins what that means — a file that turned them off is read
        // as "on" while any tool is left, because `/websearch` is still registered.
        val commandsOff = """
        {
          "commands": {
            "websearch": {"enabled": false},
            "curator": {"enabled": false},
            "search": {"enabled": false},
            "google-account": {"enabled": false}
          }
        }
        """.trimIndent()

        assertTrue(readWebSearchSettings(commandsOff)!!.enabled)
        assertTrue(
            "the switch does not write them either",
            WEB_SEARCH_OWNED_PATHS.none { it.startsWith("commands.") },
        )
        val written = parse(stripJsonComments(render(document = parse(commandsOff), settings = WebSearchSettings())))
        assertEquals(
            "a command the user turned off is left exactly as it was",
            JsonPrimitive(false),
            written.obj("commands").obj("search").value("enabled"),
        )
    }

    @Test
    fun `a value of the wrong type reads as absent`() {
        val text = """
        {
          "workflow": "auto-summary",
          "maxInlineContentChars": "lots",
          "fetch": {"timeout": "soon"},
          "proxy": ""
        }
        """.trimIndent()

        val settings = readWebSearchSettings(text)!!

        assertEquals(WebSearchSettings.WORKFLOW_AUTO_SUMMARY, settings.workflow)
        assertEquals(30_000, settings.maxInlineContentChars)
        assertEquals(30, settings.fetchTimeoutSeconds)
        assertNull("a blank proxy is not a proxy", settings.proxy)
    }

    @Test
    fun `auto and an omitted provider are the same setting`() {
        assertNull(readWebSearchSettings("""{"workflow": "none"}""")!!.provider)
        assertNull(readWebSearchSettings("""{"workflow": "none", "provider": "auto"}""")!!.provider)

        // PiKit writes the omission rather than the word.
        assertFalse(render(document = parse("""{"provider": "auto"}""")).hasLiveKey("provider"))
    }

    // ------------------------------------------------------ the page's own data

    @Test
    fun `the provider list is the extension's own, with no duplicates`() {
        val providers = WebSearchSettings.SEARCH_PROVIDERS

        // The number the bundled 0.46.0 resolves to (0.43.0 added Linkup; 0.46.0
        // added 9router, Cloudflare, SearchApi and Tabstack). It is pinned because
        // the page presents this list as "all of them", which is only true while it
        // is.
        assertEquals(43, providers.size)
        assertEquals("no provider is listed twice", providers.size, providers.toSet().size)
        assertEquals(
            "the two selectors are not providers",
            emptyList<String>(),
            providers.filter { it == "auto" || it == "all" },
        )
        // The ones the old eleven-row list was missing, which is the whole report.
        listOf("kagi", "serper", "serpapi", "xcrawl", "brightdata", "valyu", "bocha")
            .forEach { id -> assertTrue("$id is offered", id in providers) }
        // 0.46.0's four, each explicit-only in the extension's own list.
        listOf("ninerouter", "cloudflare", "searchapi", "tabstack")
            .forEach { id -> assertTrue("$id is offered", id in providers) }
    }

    @Test
    fun `every provider has a description in every language`() {
        // A picker of forty-three rows whose second line is blank for a language looks
        // like a rendering fault rather than like missing text, and the fallback in
        // `providerDescription` is the empty string.
        Lang.entries.forEach { lang ->
            val settings = stringsFor(lang).settings
            assertTrue(
                "auto/${lang.code}",
                settings.providerDescription(WebSearchSettings.PROVIDER_AUTO).isNotBlank(),
            )
            WebSearchSettings.SEARCH_PROVIDERS.forEach { id ->
                assertTrue("$id/${lang.code}", settings.providerDescription(id).isNotBlank())
            }
        }
    }

    @Test
    fun `the rendered document has no trailing comma for pi to strip`() {
        // Measured against the bundled extension's own `stripJsonComments`: on a document of this
        // shape it left two trailing commas behind, and `JSON.parse` then refused the
        // file outright — its string-literal alternative can swallow a comma that is
        // followed by comment-stripped blank lines. So the annotated document carries
        // no comma that needs stripping, which is now a property of the *editor's*
        // text rather than of the file: it is what the reader may uncomment a line of
        // and still be able to save.
        val rendered = render(
            document = parse("""{"provider": "brave", "fetch": {"retries": 3}}"""),
            settings = WebSearchSettings(proxy = "http://127.0.0.1:8080"),
        )
        val stripped = stripJsonComments(rendered)

        assertFalse(
            "a comma before a closing brace",
            Regex(""",\s*[}\]]""").containsMatchIn(stripped),
        )
        assertNotNull("and it parses with nothing stripped but the comments", Json.parseToJsonElement(stripped))
    }

    @Test
    fun `the rendered header is in the interface language`() {
        // The file is written for a reader, so its heading is in the language they
        // are using — and every language's header has to survive being turned into
        // comments, which a newline in the middle of a sentence would not.
        Lang.entries.forEach { lang ->
            val rendered = renderWebSearchDocument(
                empty,
                WebSearchSettings(),
                lang,
                stringsFor(lang).settings.searchConfigHeader.lines(),
            )
            assertTrue("$lang parses", readWebSearchDocument(rendered) != null)
            stringsFor(lang).settings.searchConfigHeader.lines().forEach { line ->
                assertTrue("$lang keeps '$line'", rendered.contains("// $line"))
            }
        }
    }

    // --------------------------------------------------- adding to the file

    /**
     * The keys the page's own controls write, and the keys it may add.
     *
     * The two lists have to be the same one, or the "add an option" picker offers a
     * key whose value the next tap on any control silently overwrites. The renderer
     * is the other half of the invariant: with every owned setting given a value,
     * the live keys of the document are exactly the owned set — no more, no less.
     */
    @Test
    fun `the keys the controls own are the keys the renderer writes live`() {
        val settings = WebSearchSettings(
            enabled = true,
            workflow = WebSearchSettings.WORKFLOW_AUTO_SUMMARY,
            provider = "exa",
            maxInlineContentChars = 1_234,
            fetchTimeoutSeconds = 7,
            proxy = "socks5h://proxy:1",
        )

        assertEquals(WEB_SEARCH_OWNED_PATHS, leafPaths(parse(strictDocument(render(settings = settings)))))
    }

    @Test
    fun `a key the controls own is never offered, and a key they do not own always is`() {
        // `fetch` is a *branch* of the schema and `fetch.timeout` is a leaf the
        // controls own: the branch is not something to add, its one live member is
        // the control's. The unknown key is nobody's, so it is listed.
        val document = parse(
            """{"fetch": {"timeout": 30, "retries": 3}, "myOwnKey": "mine", "xaiApiKey": "k"}""",
        )

        assertEquals(
            listOf("fetch.retries", "myOwnKey", "xaiApiKey"),
            webSearchEntries(document).map { it.path },
        )
    }

    @Test
    fun `an object-valued option is one entry, not one per member`() {
        // The page lists what the user added and removes it as a unit, so a
        // documented object option has to be reported whole. The unknown object has
        // no name of its own, so it is walked down to the leaves.
        val document = parse("""{"curatorRemote": {"host": "box", "bind": "10.0.0.1"}}""")

        assertEquals(listOf("curatorRemote"), webSearchEntries(document).map { it.path })
        assertEquals(
            listOf("mystery.host"),
            webSearchEntries(parse("""{"mystery": {"host": "box"}}""")).map { it.path },
        )
    }

    @Test
    fun `setting a value creates the branch it needs and keeps everything else`() {
        val document = parse("""{"kagiApiKey": "keep-me", "fetch": {"retries": 3}}""")

        val updated = document.withValueAt(listOf("xaiApiKey"), JsonPrimitive("sk-new"))
        val nested = updated.withValueAt(listOf("fetch", "timeout"), JsonPrimitive(45))

        // The key the user had, the unknown key inside a known branch, and the new
        // one all survive: adding an option is a write to one path, not a rewrite.
        assertEquals(JsonPrimitive("keep-me"), updated.value("kagiApiKey"))
        assertEquals(JsonPrimitive(3), updated.nested("fetch", "retries"))
        assertEquals(JsonPrimitive("sk-new"), updated.value("xaiApiKey"))
        assertEquals(JsonPrimitive(45), nested.nested("fetch", "timeout"))
        assertEquals(JsonPrimitive(3), nested.nested("fetch", "retries"))
    }

    @Test
    fun `a boolean where the path needs to descend is replaced, not merged into`() {
        // `githubClone` holding `true` cannot also hold `.enabled`; merging something
        // into a boolean is not a thing, so the write replaces it — and the page
        // re-renders from the result, so the user sees exactly what they got.
        val document = parse("""{"githubClone": true}""")

        val updated = document.withValueAt(listOf("githubClone", "enabled"), JsonPrimitive(false))

        assertEquals(JsonPrimitive(false), updated.obj("githubClone").value("enabled"))
    }

    @Test
    fun `removing a value prunes the branch it empties`() {
        // An empty `{}` is not the same as an absent key: the extension reads a
        // present-but-empty branch as configured, and the page would list `fetch`
        // for ever after its last member was removed.
        val document = parse("""{"fetch": {"timeout": 30}, "kagiApiKey": "keep-me"}""")

        val removed = document.withoutValueAt(listOf("fetch", "timeout"))

        assertNull(removed["fetch"])
        assertEquals(JsonPrimitive("keep-me"), removed.value("kagiApiKey"))
        // A branch with something else in it stays, minus the one member.
        val kept = parse("""{"fetch": {"timeout": 30, "retries": 3}}""")
            .withoutValueAt(listOf("fetch", "timeout"))
        assertEquals(JsonPrimitive(3), kept.nested("fetch", "retries"))
        // Removing something that is not there changes nothing.
        assertEquals(document, document.withoutValueAt(listOf("fetch", "missing")))
    }

    @Test
    fun `an added key is live on the next render, and removable again`() {
        // The round trip the page's list depends on: what `setValue` writes comes
        // back as a live key, and the entry it produces is the one the row removes.
        val annotated = render(document = empty, settings = WebSearchSettings())
        assertEquals(
            emptyList<String>(),
            webSearchEntries(readWebSearchDocument(annotated)).map { it.path },
        )

        val added = readWebSearchDocument(annotated)!!.withValueAt(listOf("xaiApiKey"), JsonPrimitive("sk-key"))
        val once = strictDocument(render(document = added, settings = WebSearchSettings()))

        assertEquals(listOf("xaiApiKey"), webSearchEntries(parse(once)).map { it.path })
        assertEquals("sk-key", parse(once).value("xaiApiKey").content)
        // And removing it puts the file back where it started.
        val back = strictDocument(render(document = parse(once).withoutValueAt(listOf("xaiApiKey"))))
        assertEquals(emptyList<String>(), webSearchEntries(parse(back)).map { it.path })
    }

    // ------------------------------- the default for a TUN/fake-IP proxy

    /** The file a fresh install ends up with: what `ensureDocument` writes when there is none. */
    private fun freshFile(): String = strictDocument(render(document = WEB_SEARCH_DEFAULT_SOURCE))

    /** `ssrf.allowRanges` as a list, or null when the key is not there. */
    private fun JsonObject.ssrfRanges(): List<String>? =
        (this["ssrf"] as? JsonObject)?.get("allowRanges")?.let { ranges ->
            (ranges as? JsonArray)?.map { it.jsonPrimitive.content }
        }

    /** [this] without `ssrf.allowRanges`, leaving the emptied object as `{}` — undoing a merge. */
    private fun JsonObject.withoutSsrfRange(): JsonObject {
        val ssrf = this["ssrf"] as? JsonObject ?: return this
        return JsonObject(toMutableMap().apply { put("ssrf", JsonObject(ssrf.filterKeys { it != "allowRanges" })) })
    }

    @Test
    fun `a fresh install's file carries the fake-IP proxy range`() {
        // The failure this pins: the extension's own default for `ssrf.allowRanges` is
        // `[]` (`loadSsrfConfig` answers `{ allowRanges: [], trustEnvProxy: false }` when
        // the key is absent), and its `assertPublicAddress` refuses 198.18.0.0/15 before
        // anything is connected — so on a phone behind a Clash-style TUN proxy, whose DNS
        // answers public hostnames out of that range, `fetch_content` and every page
        // `source_check` fetches were refused. The key is in the reference list; this is
        // the change that actually writes it.
        assertEquals(setOf("ssrf.allowRanges"), WEB_SEARCH_DEFAULT_KEYS.keys)
        val file = freshFile()

        assertTrue("the file is strict JSON", isStrictJsonObject(file))
        assertEquals(listOf(FAKE_IP_PROXY_RANGE), parse(file).ssrfRanges())
        // Not an owned key: an owned key is rewritten from the settings on every render
        // and never offered in the page's list, and this one has to be the file's.
        assertFalse("ssrf.allowRanges" in WEB_SEARCH_OWNED_PATHS)
        // It is an ordinary document key, so the editor explains it and the page lists
        // it — the list is the file's live keys that no control owns.
        val annotated = render(document = WEB_SEARCH_DEFAULT_SOURCE)
        assertTrue(annotated.contains("[in effect] ssrf.allowRanges — "))
        assertTrue("the value is live in the document", annotated.hasLiveKey("allowRanges"))
        assertTrue(
            "and the page's list offers it",
            "ssrf.allowRanges" in webSearchEntries(readWebSearchDocument(annotated)!!).map { it.path },
        )
        // The range is not a setting: what the page shows is still the default one.
        assertEquals(WebSearchSettings(), readWebSearchSettings(annotated))
    }

    @Test
    fun `an existing file that lacks the range gains it, and nothing else moves`() {
        // The install that has the bug: its file is already strict, so the repair that
        // makes a commented file strict never runs and nothing else rewrites it. The
        // merge is the write that reaches it, and it is an addition.
        val existing = strictDocument(render(settings = WebSearchSettings(provider = "brave")))
        assertNull("the file a tap writes has no range", parse(existing).ssrfRanges())

        val merged = mergedDocumentText(existing)!!

        assertEquals(listOf(FAKE_IP_PROXY_RANGE), parse(merged).ssrfRanges())
        assertEquals(
            "taking the range out gives the original document back, key for key",
            parse(existing),
            parse(merged).withoutSsrfRange(),
        )
        assertEquals("and the settings are still the file's", "brave", readWebSearchSettings(merged)!!.provider)
        // Nothing else moved: the only line that goes is the empty `ssrf` object's, which
        // becomes an object with the member in it.
        val gone = existing.lines().filterNot { it in merged.lines() }
        assertEquals("exactly one line is replaced", 1, gone.size)
        assertTrue("it is the empty ssrf object", gone.single().trimStart().startsWith("\"ssrf\": {"))
        // Idempotent, which is what makes this safe to run at every launch: a file that
        // has the range is not rewritten, so its bytes and formatting stay its own.
        assertNull("a second launch writes nothing", mergedDocumentText(merged))
    }

    @Test
    fun `a range the user set is never replaced by the default`() {
        // The two answers that are the user's, not PiKit's: `[]` is the extension's own
        // meaning for "exempt nothing", and a list of LAN ranges is their own network.
        // An owned key would rewrite both on the next tap anywhere on the page, which is
        // exactly what makes this a default rather than a sixth control.
        listOf(
            """{"ssrf": {"allowRanges": []}}""",
            """{"ssrf": {"allowRanges": ["10.0.0.0/8", "192.168.0.0/16"]}}""",
            """{"ssrf": {"allowRanges": null}}""",
            """{"ssrf": true}""",
        ).forEach { mine ->
            assertNull("no write over $mine", mergedDocumentText(mine))
        }
        // A control being tapped is the other writer, and it is equally hands-off: a
        // non-owned key is written from what the file has, so `[]` survives it too.
        val tapped = strictDocument(
            render(
                document = parse("""{"ssrf": {"allowRanges": []}}"""),
                settings = WebSearchSettings(provider = "exa"),
            ),
        )
        assertEquals(emptyList<String>(), parse(tapped).ssrfRanges())
        // Something else under `ssrf` gains the range *beside* it, never instead of it.
        val merged = mergedDocumentText("""{"ssrf": {"trustEnvProxy": true}}""")!!
        assertEquals(JsonPrimitive(true), parse(merged).obj("ssrf").value("trustEnvProxy"))
        assertEquals(listOf(FAKE_IP_PROXY_RANGE), parse(merged).ssrfRanges())
    }

    @Test
    fun `the range is a default a render never adds back`() {
        // What makes the page's ✕ a real removal: the renderer writes what the document
        // has and has no fallback of its own, so a file without the key stays without it
        // when a control is tapped. The launch merge is the only thing that puts it back,
        // which is the behaviour the chapter states rather than hides.
        val withoutIt = strictDocument(render(settings = WebSearchSettings(proxy = "socks5h://box:1080")))
        assertNull(parse(withoutIt).ssrfRanges())

        val tapped = strictDocument(
            render(document = parse(withoutIt), settings = WebSearchSettings(provider = "kagi")),
        )

        assertNull("a tap does not resurrect it", parse(tapped).ssrfRanges())
        // And the removal the page performs is a removal: the key goes, and the branch
        // it emptied is pruned with it, which is what stops the list showing `ssrf`.
        val removed = parse(mergedDocumentText(withoutIt)!!).withoutValueAt(listOf("ssrf", "allowRanges"))
        assertNull(removed["ssrf"])
    }

    @Test
    fun `a hand-written file keeps every key when the merge has to write it`() {
        // The merge re-encodes, so a file formatted by hand comes back in PiKit's shape —
        // the same shape the first control tap already gives it. What may not move is a
        // key or a value; ARCHITECTURE §9.3 says why a byte-level insert, which this file
        // would not survive, was rejected instead.
        val handwritten = """
        {
            "workflow": "none",
            "proxy": "socks5h://box:1080",
            "fetch": { "timeout": 5, "retries": 2 },
            "myOwnKey": {"nested": [1, 2]}
        }
        """.trimIndent()
        val expected = JsonObject(
            parse(handwritten).toMutableMap().apply {
                put(
                    "ssrf",
                    JsonObject(
                        mapOf("allowRanges" to JsonArray(listOf(JsonPrimitive(FAKE_IP_PROXY_RANGE)))),
                    ),
                )
            },
        )

        val merged = mergedDocumentText(handwritten)!!

        assertEquals("the document plus one key, and nothing else", expected, parse(merged))
        assertEquals("a documented branch survives whole", parse(handwritten).obj("fetch"), parse(merged).obj("fetch"))
        assertEquals(
            "and one the schema never heard of",
            parse(handwritten).obj("myOwnKey"),
            parse(merged).obj("myOwnKey"),
        )
        assertEquals(WebSearchSettings.WORKFLOW_NONE, readWebSearchSettings(merged)!!.workflow)
        assertEquals("socks5h://box:1080", readWebSearchSettings(merged)!!.proxy)
    }

    /** Every leaf path of a document, dotted, in file order. */
    private fun leafPaths(document: JsonObject, prefix: String = ""): Set<String> {
        val out = mutableSetOf<String>()
        document.forEach { (key, value) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject) out += leafPaths(value, path) else out += path
        }
        return out
    }
}
