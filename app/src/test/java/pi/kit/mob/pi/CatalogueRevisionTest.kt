package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What counts as "the model catalogue changed".
 *
 * The rule exists because a restart is itself an agent start, and an agent start runs
 * the refresh — so a comparison that does not converge is an agent that is torn down
 * and relaunched on a loop. Comparing the store's *file* did not converge: `pi update
 * --models` rewrites `models-store.json` on every run, including the `304 Not Modified`
 * path, which persists `{ ...stored, checkedAt }` and nothing else
 * (`remote-catalog-provider.js`), and `FileModelsStore.write` hands the whole document
 * to the lock to write with no comparison against what is there.
 *
 * The measured symptom was the chat header flickering between "starting" and "ready"
 * for as long as the app was open. These tests are what keep the file's own
 * bookkeeping from being mistaken for the catalogue again.
 */
class CatalogueRevisionTest {

    private val store = """
        {
          "deepseek": {
            "models": [{"id": "deepseek-flash", "provider": "deepseek"}],
            "checkedAt": 1000,
            "lastModified": 500,
            "etag": "W/\"a\""
          }
        }
    """.trimIndent()

    @Test
    fun `a revalidation is not a change`() {
        // Exactly the 304 path: `checkedAt` moves, the ETag moves with it, the models
        // and their `lastModified` do not. This is the pair that caused the loop.
        val revalidated = store
            .replace("\"checkedAt\": 1000", "\"checkedAt\": 2000")
            .replace("\"etag\": \"W/\\\"a\\\"\"", "\"etag\": \"W/\\\"b\\\"\"")

        assertNotEquals("the two documents must actually differ", store, revalidated)
        assertEquals(catalogueRevision(store), catalogueRevision(revalidated))
    }

    @Test
    fun `a new model is a change`() {
        val grown = store.replace(
            "[{\"id\": \"deepseek-flash\", \"provider\": \"deepseek\"}]",
            "[{\"id\": \"deepseek-flash\", \"provider\": \"deepseek\"}, " +
                "{\"id\": \"deepseek-v4-pro\", \"provider\": \"deepseek\"}]",
        )

        assertNotEquals(catalogueRevision(store), catalogueRevision(grown))
    }

    @Test
    fun `a moved lastModified is a change`() {
        // `remoteModels` compares this against the built-in catalogue's generation
        // date to decide whether the overlay applies at all, so it is a fact the
        // agent acts on even when the model list is identical.
        val moved = store.replace("\"lastModified\": 500", "\"lastModified\": 900")

        assertNotEquals(catalogueRevision(store), catalogueRevision(moved))
    }

    @Test
    fun `a store that is absent or unreadable is a change`() {
        // The first refresh writes a store that was not there, which is the one time
        // the overlay really did appear.
        assertEquals(CATALOGUE_UNREADABLE, catalogueRevision(null))
        assertEquals(CATALOGUE_UNREADABLE, catalogueRevision(""))
        assertEquals(CATALOGUE_UNREADABLE, catalogueRevision("{oops"))
        assertNotEquals(CATALOGUE_UNREADABLE, catalogueRevision(store))
    }

    @Test
    fun `providers cannot collide across entries`() {
        // Built by string concatenation, so the separator between two providers has
        // to actually separate them: `a` holding `x` and `b` holding `y` must not hash
        // the same as `a` holding `xy`.
        val two = """{"a":{"models":["x"],"lastModified":1},"b":{"models":["y"],"lastModified":2}}"""
        val one = """{"a":{"models":["xy"],"lastModified":1},"b":{"models":[],"lastModified":2}}"""

        assertNotEquals(catalogueRevision(two), catalogueRevision(one))
    }
}
