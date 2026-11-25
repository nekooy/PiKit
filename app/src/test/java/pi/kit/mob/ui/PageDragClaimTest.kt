package pi.kit.mob.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the drawer may take a gesture from the child it started on.
 *
 * The bug this pins is a gesture that was *starved*, not mistuned: the page used to wait out
 * the platform slop and cancel as soon as a child consumed a change, so a horizontal table or
 * the transcript's own scroll could swallow an opening swipe before the drawer ever saw it.
 * The rule now has to be answerable without a device, and the three refusals it can get wrong
 * are all here: a table's horizontal scroll whose vertical drift happens to be small, the
 * transcript's vertical scroll with a little sideways wobble, and a drag in the direction the
 * page is not allowed to move. The conversation card opens rightward; the open panel and its
 * overlay close leftward, so the direction is a parameter rather than a constant.
 *
 * The numbers are pixels of a density-independent scale, so the slop is a round 24 rather
 * than a dp conversion; the function takes the slop from `ViewConfiguration` at the call site.
 */
class PageDragClaimTest {

    private val slop = 24f

    @Test
    fun `a rightward swipe claims once it is past the slop`() {
        assertFalse(claimsPageDrag(18f, 3f, slop))
        assertTrue(claimsPageDrag(30f, 3f, slop))
    }

    @Test
    fun `exactly the slop is not enough`() {
        assertFalse(claimsPageDrag(slop, 0f, slop))
        assertTrue(claimsPageDrag(slop + 0.5f, 0f, slop))
    }

    @Test
    fun `a mostly vertical drag stays with the transcript`() {
        assertFalse(claimsPageDrag(60f, 120f, slop))
        assertFalse(claimsPageDrag(90f, 100f, slop))
        assertFalse(claimsPageDrag(130f, 120f, slop))
    }

    @Test
    fun `a leftward drag claims only when the page closes leftward`() {
        assertFalse(claimsPageDrag(-300f, 2f, slop))
        assertFalse(claimsPageDrag(-300f, 0f, slop))
        assertTrue(claimsPageDrag(-30f, 2f, slop, PageDragDirection.Left))
        assertFalse(claimsPageDrag(30f, 2f, slop, PageDragDirection.Left))
    }

    @Test
    fun `the dominance ratio decides a diagonal that is already past the slop`() {
        // A fifth more horizontal than vertical is the boundary: 126 against 100 is over it,
        // 119 against 100 is not, and both are far past the slop so only the ratio is in play.
        assertTrue(claimsPageDrag(126f, 100f, slop))
        assertFalse(claimsPageDrag(119f, 100f, slop))
    }

    @Test
    fun `a gesture that has not moved at all never claims`() {
        assertFalse(claimsPageDrag(0f, 0f, slop))
        assertFalse(claimsPageDrag(0f, 400f, slop))
    }
}
