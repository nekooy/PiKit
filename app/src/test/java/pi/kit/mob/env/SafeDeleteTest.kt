package pi.kit.mob.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The rules that keep a recursive delete from reaching a user's files.
 *
 * This is the app-side half of the storage redesign: the policy decides what the
 * agent can *reach* and the tool-call guard decides what the model may *do*, but
 * this is what stops the app itself from walking out of its own data directory.
 * It is a JVM test because the whole point is the path arithmetic, and that needs
 * no device.
 *
 * `SafeDelete` reads symlinks through `android.system.Os`, which returns null
 * under the unit-test stubs — so a link is treated as a regular file here. That is
 * recorded rather than hidden: the symlink-specific behaviour is verified on
 * device, and what is asserted below is the boundary.
 */
class SafeDeleteTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var filesDir: File

    private fun setUpFilesDir(): File {
        filesDir = temporary.newFolder("files")
        return filesDir
    }

    @Test
    fun `deletes a tree inside the data directory`() {
        val root = setUpFilesDir()
        val staging = File(root, "usr-staging/deep/er")
        staging.mkdirs()
        File(staging, "a").writeText("a")
        File(staging, "b").writeText("b")

        val outcome = SafeDelete.recursively(File(root, "usr-staging"), root)

        assertTrue("expected a deletion, got $outcome", outcome is SafeDelete.Outcome.Deleted)
        assertTrue("the tree should be gone", !File(root, "usr-staging").exists())
    }

    @Test
    fun `refuses a path outside the data directory`() {
        val root = setUpFilesDir()
        val outside = temporary.newFolder("sdcard")
        File(outside, "photo.jpg").writeText("irreplaceable")

        val outcome = SafeDelete.recursively(outside, root)

        assertTrue("expected a refusal, got $outcome", outcome is SafeDelete.Outcome.Refused)
        assertTrue("the file must still be there", File(outside, "photo.jpg").isFile)
    }

    @Test
    fun `refuses shared storage even when it is named through the data directory`() {
        val root = setUpFilesDir()
        // `$HOME` lives inside the data directory, and `~/storage` inside that —
        // but a link's *target* is outside. A caller that resolved a link and
        // passed the result must still be refused.
        val shared = temporary.newFolder("emulated/0/DCIM")
        File(shared, "IMG_0001.jpg").writeText("camera roll")

        val outcome = SafeDelete.recursively(shared, root)

        assertTrue(outcome is SafeDelete.Outcome.Refused)
        assertTrue(File(shared, "IMG_0001.jpg").isFile)
    }

    @Test
    fun `refuses the data directory itself`() {
        val root = setUpFilesDir()
        File(root, "keep.txt").writeText("settings live here")

        val outcome = SafeDelete.recursively(root, root)

        assertTrue(outcome is SafeDelete.Outcome.Refused)
        assertTrue(File(root, "keep.txt").isFile)
    }

    @Test
    fun `refuses android's own directories`() {
        val root = setUpFilesDir()
        val sensitive = File(root, "shared_prefs")
        sensitive.mkdirs()
        File(sensitive, "pikit_settings.xml").writeText("<map/>")

        assertTrue(
            SafeDelete.recursively(sensitive, root) is SafeDelete.Outcome.Refused,
        )
        assertTrue(SafeDelete.recursively(File(root, "databases"), root) is SafeDelete.Outcome.Refused)
        assertTrue(File(sensitive, "pikit_settings.xml").isFile)
    }

    @Test
    fun `reports a path that does not exist instead of pretending to delete it`() {
        val root = setUpFilesDir()
        val outcome = SafeDelete.recursively(File(root, "never-existed"), root)
        assertTrue(outcome is SafeDelete.Outcome.Refused)
    }

    @Test
    fun `deletes nested directories deepest first so the parents empty out`() {
        val root = setUpFilesDir()
        val prefix = File(root, "usr")
        File(prefix, "bin").mkdirs()
        File(prefix, "lib/sub").mkdirs()
        File(prefix, "bin/node").writeText("elf")
        File(prefix, "lib/sub/libx.so").writeText("elf")

        val outcome = SafeDelete.recursively(prefix, root)

        // Two files and four directories. The directories are reported after the
        // files that were inside them — `lib/sub`, `lib`, `bin` and `usr` all have
        // to be empty before they can be removed, which is what the walk's
        // children-first order is for.
        assertEquals(
            "every entry should have been removed",
            SafeDelete.Outcome.Deleted(entries = 6, links = 0),
            outcome,
        )
        assertTrue(!prefix.exists())
    }

    @Test
    fun `a sibling directory survives`() {
        val root = setUpFilesDir()
        val prefix = File(root, "usr")
        prefix.mkdirs()
        val home = File(root, "home")
        home.mkdirs()
        File(home, "session.jsonl").writeText("{}")

        assertTrue(SafeDelete.recursively(prefix, root) is SafeDelete.Outcome.Deleted)
        assertTrue(
            "the home directory must survive a runtime replacement",
            File(home, "session.jsonl").isFile,
        )
    }
}
