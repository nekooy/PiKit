package pi.kit.mob.env

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The mirror chooser: the files it reads, the race it runs, and the pin it must not break.
 *
 * The behaviour under test is the one `pkg --check-mirror update` gets wrong for this app:
 * that command tests reachability and then picks a reachable mirror **at random, weighted**,
 * so a phone in China can be pointed at a mirror in India that merely answered. The choice
 * here is the lowest measured latency, and it is pinned through `chosen_mirrors`, which is
 * the one answer `pkg` itself will not overwrite.
 *
 * The HTTP probe is not exercised — the selection takes a probe function, and the fake one
 * below is what a device's network cannot make testable.
 */
class PackageMirrorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `every group and the default are read, once per repository`() {
        val prefix = temporary.newFolder("prefix")
        mirror(prefix, "default", "https://packages-cf.termux.dev/apt/termux-main")
        // The same host ships under a region directory: one repository, not two mirrors.
        mirror(prefix, "europe/packages.termux.dev", "https://packages-cf.termux.dev/apt/termux-main")
        mirror(prefix, "chinese_mainland/mirrors.aliyun.com", "https://mirrors.aliyun.com/termux/termux-main")
        // A file without a MAIN line is not a mirror and must not become one with a blank URL.
        notAMirror(prefix, "chinese_mainland/README")

        val mirrors = PackageMirror.bundled(prefix)
        assertEquals(
            listOf(
                "https://packages-cf.termux.dev/apt/termux-main",
                "https://mirrors.aliyun.com/termux/termux-main",
            ),
            mirrors.map { it.main },
        )
    }

    @Test
    fun `the fastest answers are ordered and a silent mirror is not a candidate`() = runBlocking {
        val prefix = temporary.newFolder("prefix")
        mirror(prefix, "default", "https://slow.example/termux-main")
        mirror(prefix, "asia/fast.example", "https://fast.example/termux-main")
        mirror(prefix, "asia/middle.example", "https://middle.example/termux-main")
        mirror(prefix, "asia/dead.example", "https://dead.example/termux-main")

        val latency = mapOf(
            "https://slow.example/termux-main" to 240L,
            "https://fast.example/termux-main" to 35L,
            "https://middle.example/termux-main" to 120L,
            "https://dead.example/termux-main" to null,
        )
        val winners = PackageMirror.fastest(PackageMirror.bundled(prefix)) { latency[it] }

        // Three at most, in measured order: the group exists so pkg can fall back, and a
        // fallback that was slow in the same race is not a fallback.
        assertEquals(
            listOf(
                "https://fast.example/termux-main",
                "https://middle.example/termux-main",
                "https://slow.example/termux-main",
            ),
            winners.map { it.main },
        )
    }

    @Test
    fun `the number of winners is bounded by the pin`() = runBlocking {
        val prefix = temporary.newFolder("prefix")
        mirror(prefix, "default", "https://a.example/termux-main")
        mirror(prefix, "asia/b.example", "https://b.example/termux-main")
        mirror(prefix, "asia/c.example", "https://c.example/termux-main")
        mirror(prefix, "asia/d.example", "https://d.example/termux-main")

        val winners = PackageMirror.fastest(
            PackageMirror.bundled(prefix),
            count = 2,
        ) { 10L }

        assertEquals(2, winners.size)
    }

    @Test
    fun `nothing reachable is no answer rather than a blank URL`() = runBlocking {
        val prefix = temporary.newFolder("prefix")
        mirror(prefix, "default", "https://slow.example/termux-main")

        assertTrue(PackageMirror.fastest(PackageMirror.bundled(prefix)) { null }.isEmpty())
        assertTrue(PackageMirror.fastest(emptyList()) { 1L }.isEmpty())
    }

    @Test
    fun `the pinned copies bias pkg toward the fastest and keep its fallbacks`() {
        val source = "# mirror\nWEIGHT=1\nMAIN=\"https://a.example/termux-main\"\n"

        val fastest = PackageMirror.weightedMirrorFile(source, 10)
        assertTrue(fastest.contains("WEIGHT=10\n"))
        assertFalse(fastest.contains("WEIGHT=1\n"))

        // A file without the line still gets the bias, or a mirror file that omits it
        // would turn the tie back into a coin toss.
        val noWeight = PackageMirror.weightedMirrorFile("# no weight\nMAIN=\"x\"\n", 1)
        assertTrue(noWeight.contains("WEIGHT=1"))

        // Replacing "1" with "1" must still count as finding the line; an unchanged-text
        // test appended a duplicate here.
        assertEquals(1, Regex("(?m)^WEIGHT=").findAll(PackageMirror.weightedMirrorFile(source, 1)).count())
    }

    @Test
    fun `a reader's chosen mirror is detected and left alone`() {
        val prefix = temporary.newFolder("prefix")
        assertFalse(PackageMirror.hasChosen(prefix))

        PackageMirror.chosen(prefix).apply { parentFile?.mkdirs() }.writeText("a regular file, not a link")
        assertTrue(PackageMirror.hasChosen(prefix))
    }

    private fun mirror(prefix: File, relative: String, main: String) {
        val file = file(prefix, relative)
        file.writeText("# mirror\nWEIGHT=1\nMAIN=\"$main\"\nROOT=\"$main\"\n")
    }

    private fun notAMirror(prefix: File, relative: String) {
        file(prefix, relative).writeText("# no MAIN here\n")
    }

    private fun file(prefix: File, relative: String): File {
        val file = File(File(prefix, "etc/termux/mirrors"), relative)
        file.parentFile?.mkdirs()
        return file
    }
}
