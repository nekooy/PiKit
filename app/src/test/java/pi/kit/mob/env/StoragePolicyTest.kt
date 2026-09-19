package pi.kit.mob.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folders the user granted, and the names they get inside the environment.
 *
 * `linkPlanFor` is what turns a policy into the symlink farm, and it is the only
 * place that has to invent a name: the seven built-in folders have had theirs for
 * years (`~/storage/dcim`), while a folder the user adds gets one derived from its
 * path. Two folders can therefore want the same name, and a plan that silently
 * drops one of them would be a grant that does nothing.
 *
 * Pure on the JVM: the built-in roots need `Environment.getExternalStorageDirectory`
 * and are skipped here, which is exactly why the custom half of the plan is
 * testable on its own.
 */
class StoragePolicyTest {

    // ------------------------------------------------------------- link names

    @Test
    fun `a link name is the folder, lower case and shell safe`() {
        assertEquals("download", StorageAccess.linkNameFor("/storage/emulated/0/Download"))
        assertEquals("my-stuff", StorageAccess.linkNameFor("/sdcard/My Stuff"))
        assertEquals("notes.txt", StorageAccess.linkNameFor("/sdcard/archive/notes.txt"))
        assertEquals("camera-2026", StorageAccess.linkNameFor("/sdcard/Camera (2026)/"))
        assertEquals("under_score", StorageAccess.linkNameFor("/sdcard/under_score"))
    }

    @Test
    fun `a folder whose name has nothing usable still gets one`() {
        assertEquals("folder", StorageAccess.linkNameFor("/sdcard/---"))
        assertEquals("folder", StorageAccess.linkNameFor("/"))
    }

    @Test
    fun `two folders with the same basename both get a link`() {
        val plan = StorageAccess.linkPlanFor(
            StorageAccess.Policy(
                roots = emptySet(),
                custom = setOf("/sdcard/A/photos", "/sdcard/B/photos"),
            ),
        )

        assertEquals(setOf("photos", "photos-2"), plan.keys)
        assertEquals("/sdcard/A/photos", plan["photos"])
        assertEquals("/sdcard/B/photos", plan["photos-2"])
    }

    @Test
    fun `three folders with the same basename all get one`() {
        val plan = StorageAccess.linkPlanFor(
            StorageAccess.Policy(
                roots = emptySet(),
                custom = setOf("/sdcard/A/notes", "/sdcard/B/notes", "/sdcard/C/notes"),
            ),
        )

        assertEquals(setOf("notes", "notes-2", "notes-3"), plan.keys)
    }

    // ---------------------------------------------------------------- policy

    @Test
    fun `nothing granted is the empty policy`() {
        assertTrue(StorageAccess.Policy.NONE.isEmpty)
        assertFalse(StorageAccess.Policy.NONE.isUnrestricted)
    }

    @Test
    fun `a custom folder alone makes the policy non-empty`() {
        val policy = StorageAccess.Policy.NONE.plus("/sdcard/Notes")

        assertFalse("a custom grant is a grant", policy.isEmpty)
        assertFalse("and it is not the whole tree", policy.isUnrestricted)
        assertEquals(setOf("/sdcard/Notes"), policy.custom)
    }

    @Test
    fun `adding and removing are idempotent`() {
        val once = StorageAccess.Policy.NONE.plus("/sdcard/Notes")
        assertEquals(once, once.plus("/sdcard/Notes"))
        assertTrue(once.minus("/sdcard/Notes").isEmpty)
        assertTrue(StorageAccess.Policy.NONE.minus("/sdcard/Notes").isEmpty)
    }

    @Test
    fun `the whole tree is the unrestricted policy`() {
        val all = StorageAccess.Policy.NONE.toggled(StorageAccess.Root.Shared, true)

        assertTrue(all.isUnrestricted)
        assertFalse(all.isEmpty)
        assertTrue(all.toggled(StorageAccess.Root.Shared, false).isEmpty)
    }

    @Test
    fun `a folder under the whole tree keeps its grant but gets no link of its own`() {
        val policy = StorageAccess.Policy.NONE
            .toggled(StorageAccess.Root.Downloads, true)
            .toggled(StorageAccess.Root.Shared, true)

        // The grant is still recorded — the page reads `roots` to draw the switch
        // as included, and switching `shared` off has to bring the link back — but
        // `/sdcard/Download` must not be named twice.
        assertTrue(StorageAccess.Root.Downloads in policy.roots)
        assertTrue(policy.isSubsumed(StorageAccess.Root.Downloads))
        assertFalse(policy.isSubsumed(StorageAccess.Root.Shared))
    }

    @Test
    fun `switching the whole tree off leaves the folder's own grant in place`() {
        val policy = StorageAccess.Policy.NONE
            .toggled(StorageAccess.Root.Downloads, true)
            .toggled(StorageAccess.Root.Shared, true)
            .toggled(StorageAccess.Root.Shared, false)

        assertFalse(policy.isSubsumed(StorageAccess.Root.Downloads))
        assertEquals(setOf(StorageAccess.Root.Downloads), policy.roots)
    }

    @Test
    fun `a named folder and a custom one are told apart`() {
        val policy = StorageAccess.Policy.NONE
            .toggled(StorageAccess.Root.Downloads, true)
            .plus("/sdcard/Notes")

        assertEquals(setOf(StorageAccess.Root.Downloads), policy.roots)
        assertEquals(setOf("/sdcard/Notes"), policy.custom)
    }

    // ------------------------------------------------------- the guard's list

    @Test
    fun `the allowed list carries a custom folder`() {
        val allowed = StorageAccess.allowedSpellings(
            StorageAccess.Policy.NONE.plus("/sdcard/Notes"),
        )

        assertTrue("a custom folder is granted to the guard", "/sdcard/Notes" in allowed)
    }

    @Test
    fun `the allowed list of the empty policy is empty`() {
        assertTrue(StorageAccess.allowedSpellings(StorageAccess.Policy.NONE).isEmpty())
    }

    // ------------------------------------------------- the guard's link farm

    @Test
    fun `the link farm is published as name=target pairs, one per line`() {
        // The format is a contract with `linkList` in `tools/pi-safety-guard.ts`,
        // which is what turns `~/storage/shared/Download/a.txt` into
        // `/sdcard/Download/a.txt`. A separator the parser does not expect is a
        // policy that silently stops being enforced — and it was enforced by nothing
        // at all before this variable existed.
        val plan = StorageAccess.linkPlanFor(
            StorageAccess.Policy(
                roots = emptySet(),
                custom = setOf("/sdcard/A/photos", "/sdcard/B/photos"),
            ),
        )

        assertEquals(
            "photos=/sdcard/A/photos\nphotos-2=/sdcard/B/photos",
            StorageAccess.storageLinksValue(plan),
        )
        // The name is what `linkNameFor` produced, so the key can never contain the
        // `=` the parser splits on nor a newline the loop splits on.
        plan.keys.forEach { name ->
            assertTrue(name, name.none { it == '=' || it == '\n' })
        }
    }

    @Test
    fun `nothing granted publishes no link at all`() {
        assertEquals("", StorageAccess.storageLinksValue(StorageAccess.linkPlanFor(StorageAccess.Policy.NONE)))
    }
}
