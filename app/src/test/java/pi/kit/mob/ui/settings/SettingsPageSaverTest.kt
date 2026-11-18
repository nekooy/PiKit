package pi.kit.mob.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings stack's saved page.
 *
 * `SettingsScreen` keeps the current page in `rememberSaveable`, so a rotation — or
 * anything else that saves and restores the composition — has to encode the page as
 * the short string `SettingsPageSaver` writes and decode it back. Two properties are
 * easy to break and invisible in review: a page added to the interface without a
 * branch in either direction silently restores as the tab root, and a name a build
 * no longer knows has to land somewhere rather than throw out of `restore`, which
 * Compose does not catch.
 *
 * The two functions are the saver's own, taken out of its lambdas for exactly this
 * test: they are pure, and they are the whole of what a stored page means.
 */
class SettingsPageSaverTest {

    @Test
    fun `every page round-trips`() {
        val pages = listOf(
            SettingsPage.Root,
            SettingsPage.Model,
            SettingsPage.ModelEdit("profile-1"),
            SettingsPage.Agent,
            SettingsPage.Maintenance,
            SettingsPage.Backup,
            SettingsPage.Manual,
            SettingsPage.About,
            SettingsPage.Search,
            SettingsPage.SearchAdvanced,
            SettingsPage.Personalization,
        )

        pages.forEach { page ->
            assertEquals("$page restores as itself", page, decodeSettingsPage(page.encode()))
        }
    }

    @Test
    fun `a profile id keeps whatever it holds`() {
        // The id is appended whole, without escaping: a colon in it must not be read as
        // the start of a second suffix.
        val page = SettingsPage.ModelEdit("relay:prod")
        assertEquals("ModelEdit:relay:prod", page.encode())
        assertEquals(page, decodeSettingsPage(page.encode()))
        assertEquals(SettingsPage.ModelEdit(""), decodeSettingsPage("ModelEdit:"))
    }

    @Test
    fun `a name this build does not know restores to the root`() {
        // Reachable by a downgrade — a build that saved a page this one has no class
        // for — and by every page this app ever removed. Opening the tab root beats
        // throwing out of `restore`.
        listOf("", "ModelEdit", "Nope", "searchadvanced", "Search Advanced").forEach { encoded ->
            assertEquals("'$encoded' lands at the root", SettingsPage.Root, decodeSettingsPage(encoded))
        }
        // Three of those are the removed pages' own names, and they say so in
        // `decodeSettingsPage`; this is the branch that keeps an old save working.
        assertEquals(SettingsPage.Root, decodeSettingsPage("Runtime"))
        assertEquals(SettingsPage.Root, decodeSettingsPage("Advanced"))
        assertEquals(SettingsPage.Root, decodeSettingsPage("Storage"))
        // Context moved into the agent page rather than to the root, because the page it
        // became still exists.
        assertEquals(SettingsPage.Agent, decodeSettingsPage("AgentContext"))
    }

    @Test
    fun `the advanced page's back goes to search, and the deeper move is a push`() {
        // `SettingsScreen.open` decides the transition's direction by comparing depth,
        // which is the parent chain's length — so the two facts that make the row a
        // push rather than a pop are these.
        assertEquals(SettingsPage.Search, SettingsPage.SearchAdvanced.parent)
        assertEquals(SettingsPage.Root, SettingsPage.Search.parent)
        assertNull(SettingsPage.Root.parent)
    }
}
