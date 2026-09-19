package pi.kit.mob.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the environment's version out of the image builder's bootstrap tag.
 *
 * The value becomes `TERMUX_VERSION` for every process the app spawns, so what is
 * pinned here is that the tag's *version* is what comes out and the package-format
 * suffix does not — `2026.09.06-r1`, not `2026.09.06-r1+apt.android-7`, and never
 * something that does not look like a version at all.
 */
class BundledImageTest {

    @Test
    fun `a bootstrap tag becomes the version inside it`() {
        assertEquals("2026.09.06-r1", BundledImage.versionOf("bootstrap-2026.09.06-r1+apt.android-7"))
        assertEquals("2026.09.06", BundledImage.versionOf("bootstrap-2026.09.06+apt.android-7"))
        // Whitespace from a hand-edited metadata file is not a version change.
        assertEquals("2026.09.06-r1", BundledImage.versionOf("  bootstrap-2026.09.06-r1+apt.android-7 "))
    }

    @Test
    fun `the package format suffix is not part of the version`() {
        // The suffix names which Termux repository the packages came from, not which
        // release they are; keeping it would put `+apt.android-7` in a variable that
        // scripts compare as a version.
        assertEquals("2026.09.06-r2", BundledImage.versionOf("bootstrap-2026.09.06-r2+apt.android-7"))
        assertEquals("2026.09.06-r1", BundledImage.versionOf("bootstrap-2026.09.06-r1"))
    }

    @Test
    fun `anything that is not a bootstrap tag yields nothing`() {
        // The caller falls back to the app's own version rather than inventing one,
        // so a tag from another scheme has to be refused here rather than half-read.
        assertNull(BundledImage.versionOf(""))
        assertNull(BundledImage.versionOf("bootstrap-latest"))
        assertNull(BundledImage.versionOf("0.118.0"))
        assertNull(BundledImage.versionOf("bootstrap-2026.09+apt.android-7"))
    }
}
