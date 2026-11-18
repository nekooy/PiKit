package pi.kit.mob.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.ui.kit.aboveAnchorOffset
import pi.kit.mob.ui.kit.windowBoundsAround

/**
 * The composer's above-anchor arithmetic, driven with the two windows a phone actually
 * has: a small floating one and fullscreen.
 *
 * The margins and sizes are pixels, because pixels are what the library hands a
 * `PopupPositionProvider`; 21 is its 8dp vertical margin at the emulator's density
 * (2.625), and the menu is 320dp tall, the bound `MENU_MAX_HEIGHT` puts on the
 * composer's tallest menus.
 *
 * The regression this file exists for: a window that changed size without the popup
 * path seeing it hands the provider the *old*, smaller rectangle, and the clamp against
 * that rectangle's bottom used to pull the panel up off its anchor by the height the
 * window had gained — the menu opened too high in the reopened fullscreen window. The
 * anchor is measured fresh on every open, so the panel's bottom edge must stay on the
 * anchor's top edge no matter which rectangle it was handed.
 */
class PopupPositionTest {

    private val margin = IntRect(left = 0, top = 21, right = 0, bottom = 21)

    /** 320dp of rows at 2.625. */
    private val menu = IntSize(width = 500, height = 840)

    /** The composer's short menus: one to three rows. */
    private val shortMenu = IntSize(width = 500, height = 300)

    /** A row's menu, narrower than the composer chip it hangs off. */
    private val rowMenu = IntSize(width = 200, height = 300)

    private val freeformWindow = IntRect(left = 0, top = 0, right = 600, bottom = 1_000)
    private val freeformChip = IntRect(left = 40, top = 700, right = 300, bottom = 800)

    private val fullscreenWindow = IntRect(left = 0, top = 63, right = 1_080, bottom = 2_337)
    private val fullscreenChip = IntRect(left = 40, top = 2_020, right = 300, bottom = 2_120)

    private fun position(
        anchor: IntRect,
        window: IntRect,
        popup: IntSize,
        endAligned: Boolean = false,
    ): IntOffset = aboveAnchorOffset(
        anchorBounds = anchor,
        windowBounds = window,
        popupContentSize = popup,
        popupMargin = margin,
        endAligned = endAligned,
    )

    @Test
    fun opensOnItsChipInFullscreen() {
        val offset = position(fullscreenChip, fullscreenWindow, shortMenu)
        assertEquals(fullscreenChip.left, offset.x)
        assertEquals(fullscreenChip.top - shortMenu.height - margin.top, offset.y)
        // The invariant, stated as the reader sees it: the panel's bottom edge is the
        // chip's top edge, eight dp apart.
        assertEquals(fullscreenChip.top - margin.top, offset.y + shortMenu.height)
    }

    @Test
    fun opensOnItsChipInTheSmallWindow() {
        val offset = position(freeformChip, freeformWindow, shortMenu)
        assertEquals(freeformChip.left, offset.x)
        assertEquals(freeformChip.top - shortMenu.height - margin.top, offset.y)
    }

    @Test
    fun aTallMenuIsFlooredAtTheWindowTopInTheSmallWindow() {
        // The 320dp menu does not fit above the chip in a 1000px window: it slides down
        // until its own top is at the window's top, and covers the chip rather than
        // being drawn off the status bar.
        assertEquals(
            freeformWindow.top + margin.top,
            position(freeformChip, freeformWindow, menu).y,
        )
    }

    @Test
    fun theSmallWindowsRectangleDoesNotMoveTheFullscreenPanel() {
        // The anchor is the fullscreen chip; the rectangle is the small window the popup
        // path was handed before the window grew. This is the bug: the panel used to be
        // clamped to `freeformWindow.bottom`, 1337px above where the chip is.
        val stale = position(fullscreenChip, freeformWindow, menu)
        val fresh = position(fullscreenChip, fullscreenWindow, menu)
        assertEquals(fresh, stale)
        assertEquals(fullscreenChip.top - menu.height - margin.top, stale.y)
        assertEquals(fullscreenChip.top - margin.top, stale.y + menu.height)
    }

    @Test
    fun aStaleRectangleNeverClampsThePanelOntoItsChip() {
        // Whatever rectangle arrives, the panel's bottom is at its chip: it is never
        // pushed down over the trigger and never pulled up away from it.
        val rectangles = listOf(
            freeformWindow,
            fullscreenWindow,
            IntRect(left = 0, top = 0, right = 1_080, bottom = 2_400),
            IntRect(left = 60, top = 120, right = 400, bottom = 900),
        )
        for (window in rectangles) {
            val offset = position(fullscreenChip, window, shortMenu)
            assertEquals(fullscreenChip.top - margin.top, offset.y + shortMenu.height)
        }
    }

    @Test
    fun endAlignmentPutsThePanelsRightEdgeOnTheAnchors() {
        val offset = position(
            anchor = fullscreenChip,
            window = fullscreenWindow,
            popup = rowMenu,
            endAligned = true,
        )
        assertEquals(fullscreenChip.right - rowMenu.width, offset.x)
    }

    @Test
    fun theRectangleIsWidenedOnlyWhereTheAnchorLeavesIt() {
        // A current window contains the anchor, so the clamp bounds are the window's own.
        assertEquals(
            fullscreenWindow,
            windowBoundsAround(fullscreenChip, fullscreenWindow),
        )
        // The small window is the stale one: the anchor has moved past its bottom, and
        // its left/right/top still hold.
        val stale = windowBoundsAround(fullscreenChip, freeformWindow)
        assertEquals(freeformWindow.left, stale.left)
        assertEquals(freeformWindow.top, stale.top)
        assertTrue(stale.bottom >= fullscreenChip.bottom)
        assertTrue(stale.right >= fullscreenChip.right)
    }

    @Test
    fun aRectangleThatStillHoldsTheAnchorIsNotWidened() {
        val window = IntRect(left = 0, top = 0, right = 1_080, bottom = 2_400)
        assertEquals(window, windowBoundsAround(freeformChip, window))
    }
}
