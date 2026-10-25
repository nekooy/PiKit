/*
 * The listing rules, which are the ones a file manager is judged by.
 *
 * These are JVM tests over a temporary directory: `readDirectory` is the only function
 * that touches the filesystem, and everything the page then does with its answer is a
 * pure function of the list — which is why it was split out of the screen (see
 * `FileListing.kt`). The two orderings that are easy to get wrong are the ones with a
 * test named after them: folders first whatever the order, and a filter that is a
 * substring rather than a glob.
 */
package pi.kit.mob.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FileListingTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `folders come first in every order`() {
        val root = temporary.newFolder("root")
        File(root, "b-folder").mkdir()
        File(root, "a.txt").writeText("1")
        File(root, "c.bin").writeText("x".repeat(2048))

        val entries = readDirectory(root)
        for (sort in FileSort.entries) {
            val names = arrange(entries, sort, "").map { it.name }
            assertEquals(
                "folders first under $sort",
                listOf("b-folder"),
                names.take(1),
            )
        }
    }

    @Test
    fun `name order ignores case`() {
        val root = temporary.newFolder("names")
        listOf("Zebra", "apple", "Mango").forEach { File(root, it).writeText("") }

        val names = arrange(readDirectory(root), FileSort.Name, "").map { it.name }

        assertEquals(listOf("apple", "Mango", "Zebra"), names)
    }

    @Test
    fun `size order puts the biggest first`() {
        val root = temporary.newFolder("sizes")
        File(root, "small").writeText("a")
        File(root, "big").writeText("x".repeat(5000))
        File(root, "middle").writeText("x".repeat(500))

        val names = arrange(readDirectory(root), FileSort.Size, "").map { it.name }

        assertEquals(listOf("big", "middle", "small"), names)
    }

    @Test
    fun `modified order puts the newest first`() {
        val root = temporary.newFolder("times")
        val old = File(root, "old").apply { writeText("a") }
        val recent = File(root, "recent").apply { writeText("b") }
        // A second apart rather than a millisecond: some filesystems store whole
        // seconds, and a test that depends on sub-second precision would pass here and
        // fail on the device.
        assertTrue(old.setLastModified(1_000_000_000_000L))
        assertTrue(recent.setLastModified(1_600_000_000_000L))

        val names = arrange(readDirectory(root), FileSort.Modified, "").map { it.name }

        assertEquals(listOf("recent", "old"), names)
    }

    @Test
    fun `the filter is a substring of the name and keeps the order`() {
        val root = temporary.newFolder("filter")
        File(root, "report-2024.md").writeText("")
        File(root, "report-2025.md").writeText("")
        File(root, "notes.txt").writeText("")

        val entries = readDirectory(root)

        assertEquals(
            listOf("report-2024.md", "report-2025.md"),
            arrange(entries, FileSort.Name, "REPORT").map { it.name },
        )
        assertTrue(arrange(entries, FileSort.Name, "*.md").isEmpty())
        assertEquals(3, arrange(entries, FileSort.Name, "  ").size)
    }

    @Test
    fun `a folder carries its child count and a file carries its size`() {
        val root = temporary.newFolder("counts")
        val folder = File(root, "inside").apply { mkdir() }
        File(folder, "a").writeText("")
        File(folder, "b").writeText("")
        File(root, "file").writeText("hello")

        val entries = readDirectory(root).associateBy { it.name }

        assertEquals(2, entries.getValue("inside").children)
        assertEquals(5L, entries.getValue("file").size)
        assertNull(entries.getValue("file").children)
    }

    @Test
    fun `a directory that cannot be listed reads as empty rather than throwing`() {
        val missing = File(temporary.root, "not-there")

        assertTrue(readDirectory(missing).isEmpty())
    }

    @Test
    fun `crumbs walk from home and end at the folder being shown`() {
        val home = temporary.newFolder("home")
        val workspace = File(home, "workspace").apply { mkdir() }
        val deep = File(workspace, "src").apply { mkdir() }

        assertEquals(listOf("~"), crumbs(home, home).map { it.label })
        assertEquals(listOf("~", "workspace"), crumbs(home, workspace).map { it.label })
        assertEquals(listOf("~", "workspace", "src"), crumbs(home, deep).map { it.label })
        assertEquals(deep, crumbs(home, deep).last().target)
    }

    @Test
    fun `a path outside home is one segment rather than a walk through private directories`() {
        val home = temporary.newFolder("home2")
        val outside = temporary.newFolder("elsewhere")

        val trail = crumbs(home, outside)

        assertEquals(1, trail.size)
        assertEquals(outside.absolutePath, trail.single().label)
    }

    @Test
    fun `depth counts components below the root`() {
        val home = temporary.newFolder("depth")
        val one = File(home, "one").apply { mkdir() }
        val two = File(one, "two").apply { mkdir() }

        assertEquals(0, home.depthUnder(home))
        assertEquals(1, one.depthUnder(home))
        assertEquals(2, two.depthUnder(home))
        assertTrue(two.depthUnder(home) > one.depthUnder(home))
    }

    @Test
    fun `sizes are bytes, kilobytes and megabytes`() {
        assertEquals("512 B", humanSize(512))
        assertTrue(humanSize(2048).endsWith("KB"))
        assertTrue(humanSize(5L * 1024 * 1024).endsWith("MB"))
    }

    @Test
    fun `a stamp is a time today, a day this year, and a date before that`() {
        val locale = Locale.getDefault()
        val now = 1_700_000_000_000L

        val today = stampFor(now, now)
        assertEquals(
            SimpleDateFormat("HH:mm", locale).format(Date(now)),
            today,
        )

        val earlierThisYear = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -3)
        }.timeInMillis
        assertEquals(
            SimpleDateFormat("MM-dd", locale).format(Date(earlierThisYear)),
            stampFor(earlierThisYear, now),
        )

        val yearsAgo = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.YEAR, -2)
        }.timeInMillis
        assertEquals(
            SimpleDateFormat("yyyy-MM-dd", locale).format(Date(yearsAgo)),
            stampFor(yearsAgo, now),
        )
    }

    @Test
    fun `an entry with no timestamp has no stamp`() {
        assertEquals("", stampFor(0L, 1_700_000_000_000L))
    }

    @Test
    fun `kind names a folder, a link, an extension and a bare file`() {
        val folder = File(temporary.root, "folder").apply { mkdir() }
        val script = File(temporary.root, "run.sh").apply { writeText("") }
        val bare = File(temporary.root, "README").apply { writeText("") }

        assertEquals(
            "Folder",
            fileKind(folder, isDirectory = true, isLink = false, "Folder", "Link", "File"),
        )
        assertEquals(
            "Link",
            fileKind(folder, isDirectory = true, isLink = true, "Folder", "Link", "File"),
        )
        assertEquals(
            "SH",
            fileKind(script, isDirectory = false, isLink = false, "Folder", "Link", "File"),
        )
        assertEquals(
            "File",
            fileKind(bare, isDirectory = false, isLink = false, "Folder", "Link", "File"),
        )
    }
}
