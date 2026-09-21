package pi.kit.mob.locales

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference the settings page renders into `web-search.json`.
 *
 * This is data about another program's configuration, and it is wrong *quietly*: a
 * row with no Chinese note does not fail to compile — `note` falls back to English —
 * a row with no example renders a comment with `null` in it, and an example that is
 * not valid JSON is a line the reader is invited to uncomment into a broken file.
 * None of that is visible to a reviewer reading a long diff of prose, so all of it
 * is checked here.
 */
class WebAccessParamsTest {

    @Test
    fun `every row is written in all three languages`() {
        val missing = mutableListOf<String>()
        WEB_ACCESS_PARAMS.forEach { param ->
            Lang.entries.forEach { lang ->
                if (lang !in param.notes.keys) missing += "${param.path}/${lang.code}"
            }
        }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `no key is listed twice, and every row is complete`() {
        val paths = WEB_ACCESS_PARAMS.map { it.path }
        val blank = mutableListOf<String>()

        assertEquals(
            "a duplicated path is a row that describes the wrong key",
            paths.size,
            paths.toSet().size,
        )
        WEB_ACCESS_PARAMS.forEach { param ->
            if (param.type.isBlank()) blank += "${param.path} has no type"
            if (param.example.isBlank()) blank += "${param.path} has no example"
            Lang.entries.forEach { lang ->
                if (param.note(lang).isBlank()) blank += "${param.path}/${lang.code} is blank"
                // A newline would end the comment the renderer puts this note in, and
                // take every line after it with it.
                if (param.note(lang).contains('\n')) blank += "${param.path}/${lang.code} is multi-line"
            }
        }

        assertEquals(emptyList<String>(), blank)
    }

    @Test
    fun `every example is valid JSON`() {
        // The examples are spliced into the document verbatim. One that does not
        // parse is a line the reader is invited to uncomment into a file the agent
        // then refuses to read.
        WEB_ACCESS_PARAMS.forEach { param ->
            val parsed = runCatching { Json.parseToJsonElement(param.example) }.getOrNull()
            assertTrue("${param.path}: ${param.example}", parsed != null)
        }
    }

    @Test
    fun `the reference covers every part of the extension's configuration`() {
        // The rows are the page's own headings; what matters is that no *area* of
        // the extension's file is missing a row, because a user looking one up
        // concludes the option does not exist.
        val paths = WEB_ACCESS_PARAMS.map { it.path }.toSet()

        listOf(
            "provider",
            "searchProvider",
            "searchRouting.providers",
            "searchRouting.useCurrentModel",
            "searchRouting.fallbackOn",
            "searchModel",
            "summaryModel",
            "summaryGenerationDeadlineMs",
            "curatorTimeoutSeconds",
            "fetch.timeout",
            "fetch.answerProvider",
            "fetch.answerModel",
            "fetchRouting.providers",
            "fetchRouting.allowRemoteHostedProviders",
            "fetchContent.domainPolicy.allow",
            "fetchContent.domainPolicy.deny",
            "maxInlineContentChars",
            "githubClone.maxRepoSizeMB",
            "githubClone.cloneTimeoutSeconds",
            "githubClone.clonePath",
            "githubPrIssue.enabled",
            "tools.sourceCheck.enabled",
            "toolNames.webSearch",
            "commands.curator.enabled",
            "workflow",
            "autoOpenBrowser",
            "curatorRemote",
            "allowBrowserCookies",
            "browserCookies.profile",
            "shortcuts.curate",
            "shortcuts.activity",
            "proxy",
            "ssrf.allowRanges",
            "ssrf.trustEnvProxy",
            "authFetch.example.com",
            "pdf.provider",
            "pdf.datalabMode",
            "video.preferredModel",
            "video.maxSizeMB",
            "youtube.enabled",
            "image.enabled",
            "searxngBaseUrl",
            "searxngHeaders",
            "datalabApiKey",
            "crawl4aiApiToken",
            "brightdataSerpZone",
            "brightdataUnlockerZone",
            "serpdiveModel",
            "xaiSearchTools",
            "mistralSearchTool",
            "geminiAuth",
            "geminiProject",
            "geminiLocation",
            // The six 0.30.0 adds. They are named here rather than left to the row
            // list because the point of this test is that a *new* area of the
            // extension's file cannot be missed, and a bump that adds one is exactly
            // when nobody looks.
            "fetch.defaultMode",
            "fetch.allowedModes",
            "webSearch.allowedProviders",
            "openaiUseProviderBaseUrl",
            "openaiUseAlphaSearch",
            "serplyApiKey",
        ).forEach { path ->
            assertTrue("$path is documented", path in paths)
        }

        // Keys the extension does not read must not appear: a document that invented
        // options would send a user to configure something nothing looks at.
        listOf("retrieval", "search", "maxResults", "enabled").forEach { invented ->
            assertEquals(
                "$invented is not a key of this extension",
                emptyList<String>(),
                paths.filter { it == invented },
            )
        }
    }

    @Test
    fun `a pattern key is spelled as a usable example`() {
        // `toolNames.*` and `authFetch.<name>` are how the extension's documentation
        // describes them, and neither can be a key in a document the user is meant to
        // uncomment. The row's path is therefore one concrete key, and the pattern is
        // in its description.
        val paths = WEB_ACCESS_PARAMS.map { it.path }
        assertTrue(paths.none { it.contains('*') || it.contains('<') || it.contains('>') })
        assertTrue(paths.any { it == "toolNames.webSearch" })
        assertTrue(paths.any { it == "authFetch.example.com" })
    }
}
