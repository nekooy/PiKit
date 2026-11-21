package pi.kit.mob.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.ui.kit.aboveOpeningBounds

/**
 * The rectangle the app hands `GlassPopup` to make a chip's menu open upward.
 *
 * The panel is placed by the library, and its own fit test is the thing that was wrong on
 * the composer: a chip row is the last thing above the input field, so "there is room
 * below" is true and lands the menu on the field — and with the keyboard up, under it,
 * because the keyboard shrinks the content rather than the window the library measures.
 *
 * The arithmetic these cases pin is `placeGlassPopup`'s, read out of `miuix-glass`
 * 0.9.5-beta02 and quoted in `aboveOpeningBounds`' KDoc:
 *
 * ```
 * below    = window.bottom - margin - anchor.top
 * above    = anchor.bottom - min(window.top + margin, anchor.top)
 * alignTop = size.height <= below || below >= above
 * ```
 *
 * `alignTop` is the downward branch. The test is written against that formula rather than
 * against the app's own code, so a Miuix release that moves the placement is a failing
 * case here rather than a menu over the composer again.
 */
class PopupPlacementTest {

    private val window = Rect(left = 0f, top = 63f, right = 1080f, bottom = 2337f)
    private val margin = 31.5f // 12dp at the emulator's 2.625 density

    /** The composer's chip row: a 32dp pill near the bottom of a 2337px window. */
    private val chip = Rect(left = 308f, top = 1938f, right = 448f, bottom = 2064f)

    /** A three-row menu, and the 416dp panel a long one becomes. */
    private val shortMenu = 300f
    private val tallMenu = 1090f

    /** `placeGlassPopup`'s downward branch, with the app's rectangle as the anchor. */
    private fun opensDownward(anchor: Rect, size: Float): Boolean {
        val below = window.bottom - margin - anchor.top
        val minTop = minOf(window.top + margin, anchor.top).coerceAtLeast(window.top)
        val above = anchor.bottom - minTop
        return size <= below || below >= above
    }

    /** The panel's top edge, from the same function. */
    private fun panelTop(anchor: Rect, size: Float): Float {
        val minTop = minOf(window.top + margin, anchor.top).coerceAtLeast(window.top)
        val wanted = if (opensDownward(anchor, size)) anchor.top else anchor.bottom - size
        return wanted.coerceIn(minTop, (window.bottom - size - margin).coerceAtLeast(minTop))
    }

    @Test
    fun aChipThatFitsBelowStillOpensUpward() {
        // The case that was wrong: 300px of menu under a chip with ~250px of room is a
        // "yes" from the fit test, and the panel landed on the composer field.
        assertTrue(opensDownward(chip, shortMenu))
        val forced = aboveOpeningBounds(anchor = chip, windowHeight = window.bottom)
        assertFalse(opensDownward(forced, shortMenu))
        assertFalse(opensDownward(forced, tallMenu))
    }

    @Test
    fun thePanelsBottomEdgeLandsOnTheChipsTop() {
        val forced = aboveOpeningBounds(anchor = chip, windowHeight = window.bottom)
        // Within the window's own floor, the panel's bottom is the chip's top edge —
        // the invariant the reader sees as "the menu is attached to the pill".
        assertEquals(chip.top, panelTop(forced, shortMenu) + shortMenu)
    }

    @Test
    fun theHorizontalExtentsAreTheChips() {
        // The panel is end-aligned to the anchor, so its right edge is the pill's right
        // edge: the vertical trick must not move it sideways.
        val forced = aboveOpeningBounds(anchor = chip, windowHeight = window.bottom)
        assertEquals(chip.left, forced.left)
        assertEquals(chip.right, forced.right)
    }

    @Test
    fun aTallerPanelIsStillAboveTheChip() {
        // The tallest menu the composer has (seven thinking levels, a full model list) is
        // 416dp, and it must not be pushed back down by the window's own top clamp.
        val forced = aboveOpeningBounds(anchor = chip, windowHeight = window.bottom)
        assertFalse(opensDownward(forced, tallMenu))
    }

    @Test
    fun aToolbarButtonAboveTheFieldAlsoOpensUpward() {
        // The composer's own row — the `+`, `⌘`, the permission tier — is below the chip
        // band and opens the same way.
        val button = Rect(left = 48f, top = 2189f, right = 153f, bottom = 2294f)
        val forced = aboveOpeningBounds(anchor = button, windowHeight = window.bottom)
        assertFalse(opensDownward(forced, shortMenu))
        assertEquals(button.top, panelTop(forced, shortMenu) + shortMenu)
    }
}
