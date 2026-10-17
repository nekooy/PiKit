package pi.kit.mob.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest a reinstall restores from.
 *
 * These are the rules that decide whether a `pkg install` survives an app
 * update, so they are pinned rather than left to the installer to discover on a
 * phone: a status paragraph dpkg writes but the parser does not read is a
 * package silently dropped from the manifest, and a merge that drops the
 * earlier list is a restore that forgets whatever the last attempt missed.
 */
class PackageManifestTest {

    @Test
    fun `a status document parses to name and version`() {
        val status = """
            Package: android-tools
            Version: 34.0.4-2
            Architecture: aarch64
            Maintainer: @termux
            Description: Android platform tools
             A longer description that spans
             more than one line.

            Package: zlib
            Version: 1.3.1
            Description: compression library
        """.trimIndent()

        assertEquals(
            listOf(
                PackageManifest.Entry("android-tools", "34.0.4-2"),
                PackageManifest.Entry("zlib", "1.3.1"),
            ),
            PackageManifest.parseStatus(status),
        )
    }

    @Test
    fun `a continuation line does not split a paragraph`() {
        // dpkg marks a field continuation with a leading space; without one the
        // line really would be a second paragraph's field.
        val status = "Package: first\n" +
            "Version: 1\n" +
            "Description: starts here\n" +
            " Package: not-a-second-package\n" +
            " Version: ignored\n" +
            "\n" +
            "Package: second\n" +
            "Version: 2\n"

        assertEquals(
            listOf(
                PackageManifest.Entry("first", "1"),
                PackageManifest.Entry("second", "2"),
            ),
            PackageManifest.parseStatus(status),
        )
    }

    @Test
    fun `the user's packages are the installed set minus the shipped set`() {
        val installed = listOf(
            PackageManifest.Entry("bash", "5.2"),
            PackageManifest.Entry("nodejs", "26.4.0"),
            PackageManifest.Entry("android-tools", "34.0.4"),
        )
        val shipped = setOf("bash", "nodejs", "zlib")

        assertEquals(
            listOf(PackageManifest.Entry("android-tools", "34.0.4")),
            PackageManifest.userInstalled(installed, shipped),
        )
    }

    @Test
    fun `a manifest round-trips through its text form`() {
        val entries = listOf(
            PackageManifest.Entry("android-tools", "34.0.4-2"),
            PackageManifest.Entry("ripgrep", ""),
        )
        assertEquals(entries, PackageManifest.parseList(PackageManifest.renderList(entries)))
    }

    @Test
    fun `what the reinstalled tree already has is not restored`() {
        val captured = listOf(
            PackageManifest.Entry("android-tools", "1"),
            PackageManifest.Entry("python", "2"),
        )
        val now = listOf(PackageManifest.Entry("python", "3"))

        assertEquals(
            listOf(PackageManifest.Entry("android-tools", "1")),
            PackageManifest.missingAfter(captured, now),
        )
    }

    @Test
    fun `a second capture keeps what the first one could not restore`() {
        val earlier = listOf(PackageManifest.Entry("offline-only", "1"))
        val later = listOf(PackageManifest.Entry("android-tools", "2"))

        assertEquals(
            listOf(
                PackageManifest.Entry("offline-only", "1"),
                PackageManifest.Entry("android-tools", "2"),
            ),
            PackageManifest.merge(earlier, later),
        )
    }

    @Test
    fun `a later capture of the same name wins`() {
        val merged = PackageManifest.merge(
            earlier = listOf(PackageManifest.Entry("zlib", "1.2")),
            later = listOf(PackageManifest.Entry("zlib", "1.3")),
        )
        assertEquals(listOf(PackageManifest.Entry("zlib", "1.3")), merged)
    }

    @Test
    fun `the report round-trips and keeps pending apart from ready`() {
        val report = PackageManifest.Report(
            ready = listOf(PackageManifest.Entry("android-tools", "1")),
            pending = listOf(PackageManifest.Entry("gone-upstream", "2")),
        )
        assertEquals(report, PackageManifest.parseReport(PackageManifest.renderReport(report)))
    }

    @Test
    fun `an empty capture is an empty manifest, not a blank line`() {
        val entries = emptyList<PackageManifest.Entry>()
        assertEquals("", PackageManifest.renderList(entries))
        assertTrue(PackageManifest.parseList("").isEmpty())
    }

    @Test
    fun `versions order the way the update hint needs`() {
        // The hint answers "does the image ship newer than the device has", so
        // what matters is order, not dpkg's whole algorithm: an epoch, a
        // missing revision, and the tilde Debian uses for a prerelease.
        assertTrue(PackageManifest.compareVersions("26.5.0-1", "26.4.0-1") > 0)
        assertTrue(PackageManifest.compareVersions("11.21.1", "11.22.0") < 0)
        assertEquals(0, PackageManifest.compareVersions("1.3.1", "1.3.1"))
        assertTrue(PackageManifest.compareVersions("1.3.1", "1.3.1-1") < 0)
        assertTrue(PackageManifest.compareVersions("1:3.6.5", "3.6.5") > 0)
        assertTrue(PackageManifest.compareVersions("1.0~rc1", "1.0") < 0)
    }

    @Test
    fun `only a strictly newer shipped version counts as an update`() {
        val installed = listOf(
            PackageManifest.Entry("nodejs", "26.4.0-1"),      // image ships 26.5.0-1: newer
            PackageManifest.Entry("zlib", "1.3.1"),           // equal: not an update
            PackageManifest.Entry("npm", "11.22.0"),          // device is ahead: not an update
            PackageManifest.Entry("android-tools", "34.0.4"), // the image ships no version for it
        )
        val shipped = mapOf(
            "nodejs" to "26.5.0-1",
            "zlib" to "1.3.1",
            "npm" to "11.21.1",
        )
        assertEquals(
            listOf("nodejs"),
            PackageManifest.upgradable(installed, shipped).map { it.name },
        )
    }
}
