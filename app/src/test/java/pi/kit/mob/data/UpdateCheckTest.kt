package pi.kit.mob.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check's two pure pieces: what GitHub's answer becomes, and which
 * version is newer.
 *
 * The wire format is pinned rather than assumed. The check asks for
 * `…/releases/latest` and reads the URL it is *redirected* to, so the fixtures below are
 * the URLs GitHub actually lands on — a published release (`/releases/tag/v0.2.0`), a
 * repository with nothing published (`/releases`), and a repository that does not exist
 * (404, which is not this function's business). The failure mode of reading one of them
 * wrong is a row that says "up to date" forever, which is the exact outcome a version
 * check must not have.
 */
class UpdateCheckTest {

    private val tagUrl = "https://github.com/nekooy/PiKit/releases/tag/v0.2.0"

    @Test
    fun `reads the version and the page out of the url GitHub redirects to`() {
        val release = UpdateCheck.releaseFrom(tagUrl)

        assertEquals("0.2.0", release?.version)
        assertEquals(tagUrl, release?.pageUrl)
    }

    @Test
    fun `a bare tag is read the same way as a v-prefixed one`() {
        assertEquals(
            "0.2.0",
            UpdateCheck.releaseFrom("https://github.com/nekooy/PiKit/releases/tag/0.2.0")?.version,
        )
    }

    @Test
    fun `a query or a fragment after the tag is not part of the version`() {
        assertEquals(
            "0.2.0",
            UpdateCheck.releaseFrom("$tagUrl?expanded=true#assets")?.version,
        )
    }

    @Test
    fun `an answer that is not a release tag is not a release`() {
        // The URL the check asks: the redirect has not happened, so there is no tag yet.
        assertNull(UpdateCheck.releaseFrom(UpdateCheck.endpointFor("nekooy/PiKit")))
        // The releases *list*, which is where "nothing published" lands.
        assertNull(UpdateCheck.releaseFrom("https://github.com/nekooy/PiKit/releases"))
        // The tag marker with nothing after it.
        assertNull(UpdateCheck.releaseFrom("https://github.com/nekooy/PiKit/releases/tag/"))
        // Anywhere but GitHub: a captive portal or a proxy's own page must not be read as
        // a release, because the row would then offer to open that URL in a browser.
        assertNull(UpdateCheck.releaseFrom("https://example.invalid/releases/tag/v0.2.0"))
        assertNull(UpdateCheck.releaseFrom("http://github.com/nekooy/PiKit/releases/tag/v0.2.0"))
    }

    @Test
    fun `a tag is a version once the v is gone`() {
        assertEquals("0.2.0", UpdateCheck.versionOf("v0.2.0"))
        assertEquals("0.2.0", UpdateCheck.versionOf("V0.2.0"))
        assertEquals("0.2.0", UpdateCheck.versionOf(" 0.2.0 "))
        // A tag that is already bare is left alone rather than mangled.
        assertEquals("0.2.0", UpdateCheck.versionOf("0.2.0"))
    }

    @Test
    fun `a later release is newer, and the reverse is not`() {
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.1.0"))
        assertTrue(UpdateCheck.isNewer("0.2.0", "0.1.9"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.2.0"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateCheck.isNewer("0.1.0", "0.1.1"))
    }

    @Test
    fun `components are compared as numbers, not as text`() {
        // The one case a string comparison gets backwards, and the reason this is
        // not a string comparison: "0.10.0" < "0.9.0" lexically.
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0"))
        assertTrue(UpdateCheck.isNewer("1.0.0", "0.99.99"))
        assertTrue(UpdateCheck.isNewer("10.0.0", "9.0.0"))
    }

    @Test
    fun `a missing component counts as zero`() {
        assertEquals(0, UpdateCheck.compareVersions("1.0", "1.0.0"))
        assertEquals(0, UpdateCheck.compareVersions("1", "1.0.0.0"))
        assertTrue(UpdateCheck.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `a suffix does not stop the numeric components deciding`() {
        // Not a semver implementation — see `compareVersions` for why a suffix cannot
        // reach it from a build of this repository. What is pinned here is the part
        // that does matter: an unexpected suffix must not make the comparison stop
        // working on the components before it.
        assertTrue(UpdateCheck.isNewer("0.2.0-rc1", "0.1.0"))
        assertTrue(UpdateCheck.isNewer("0.3.0", "0.2.0-rc1"))
        assertEquals(0, UpdateCheck.compareVersions("0.2.0", "0.2.0"))
    }

    @Test
    fun `the endpoint is github's release page and names the repository it was given`() {
        // Pinned because the URL is assembled from `pikit.repository` at build time
        // and a wrong one is a check that always fails, which reads as the network's
        // fault rather than the build's. It is `github.com` and not `api.github.com`
        // because the API's 60-requests-per-address budget is spent on any address a
        // VPN exit shares — see `UpdateCheck`'s own note.
        assertEquals(
            "https://github.com/nekooy/PiKit/releases/latest",
            UpdateCheck.endpointFor("nekooy/PiKit"),
        )
        assertEquals(
            "https://github.com/someone/other/releases/latest",
            UpdateCheck.endpointFor("someone/other"),
        )
    }
}
