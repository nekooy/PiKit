package pi.kit.mob.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link farm: its names, and the one property it must not break.
 *
 * The farm used to be built from a policy the user chose folder by folder, and this file
 * tested the planner — the naming rule for a folder the user added, the subsumption of a
 * child of a granted tree, the revoke path. The page is gone (§14): what is left is one
 * fixed list of links, created while Android's "all files access" is granted, so what is
 * worth pinning is the list itself.
 *
 * The names are a **contract with the shell**, not a design choice: `termux-setup-storage`
 * and every script written against it type `~/storage/downloads`, `~/storage/dcim`,
 * `~/storage/shared`. A rename here breaks commands in the terminal tab and in ten
 * thousand READMEs, and nothing would fail loudly — a missing directory reads as an empty
 * one to most tools.
 */
class StorageFarmTest {

    @Test
    fun `the farm uses the names termux-setup-storage creates`() {
        val names = StorageAccess.LINKS.map { it.first }
        assertEquals(
            listOf("shared", "downloads", "documents", "pictures", "dcim", "music", "movies"),
            names,
        )
    }

    @Test
    fun `each link points at the directory the phone actually has`() {
        val targets = StorageAccess.LINKS.toMap()
        assertEquals("", targets["shared"])
        assertEquals("Download", targets["downloads"])
        assertEquals("Documents", targets["documents"])
        assertEquals("Pictures", targets["pictures"])
        assertEquals("DCIM", targets["dcim"])
        assertEquals("Music", targets["music"])
        assertEquals("Movies", targets["movies"])
    }

    @Test
    fun `no two names resolve to one directory`() {
        // `~/storage/shared` is the whole of /sdcard and `~/storage/downloads` is one
        // folder inside it; that is one *name* per directory as long as no two entries
        // point at the same child. The farm used to be able to create both
        // `downloads` and `shared/Download`, and `termux-setup-storage`'s own output is
        // what a reader greps when two paths for one folder surprise them.
        val children = StorageAccess.LINKS.map { it.second }.filter { it.isNotEmpty() }
        assertEquals(children.size, children.distinct().size)
    }

    @Test
    fun `the farm lives under the home directory, not inside a workspace`() {
        // The links are the environment's, so they are in `$HOME` where a hand-run `pi`
        // sees them too — and outside `$HOME/workspace`, which is the directory a
        // conversation may be started in and the write root in the middle tier (§14).
        assertEquals("storage", StorageAccess.STORAGE_DIR_NAME)
        assertTrue(StorageAccess.STORAGE_DIR_NAME != "workspace")
    }
}
