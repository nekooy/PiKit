package pi.kit.mob.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.ui.kit.placeUpwardMenu
import pi.kit.mob.ui.kit.popupMorphBounds
import pi.kit.mob.ui.kit.upwardMenuMaxHeight

class PopupPlacementTest {
    private val safe = Rect(24f, 63f, 1056f, 2337f)
    private val chip = Rect(120f, 1938f, 260f, 2022f)
    private val margin = 32f
    private val gap = 21f
    private val menu = Size(480f, 240f)

    @Test
    fun aShortMenuOpensAboveAndTowardLogicalEndEvenWhenItFitsBelow() {
        assertEquals(Rect(120f, 1677f, 600f, 1917f), placeUpwardMenu(chip, menu, safe, margin, gap, LayoutDirection.Ltr))
        val rightChip = Rect(820f, chip.top, 960f, chip.bottom)
        assertEquals(Rect(480f, 1677f, 960f, 1917f), placeUpwardMenu(rightChip, menu, safe, margin, gap, LayoutDirection.Rtl))
    }

    @Test
    fun clampingMovesOnlyTheSettledPanelNotTheTrigger() {
        val clippedChip = Rect(10f, chip.top, 152f, chip.bottom)
        val target = placeUpwardMenu(clippedChip, menu, safe, margin, gap, LayoutDirection.Ltr)
        assertEquals(56f, target.left)
        assertEquals(clippedChip, popupMorphBounds(clippedChip, target, 0f, 0f))
        assertEquals(target, popupMorphBounds(clippedChip, target, 1f, 1f))
    }

    @Test
    fun aTallMenuUsesOnlyTheSafeSpaceAboveAnImeRaisedTrigger() {
        val raised = chip.translate(0f, -1250f)
        val height = upwardMenuMaxHeight(raised, safe, margin, gap)
        assertEquals(572, height)
        val target = placeUpwardMenu(raised, menu.copy(height = height.toFloat()), safe, margin, gap, LayoutDirection.Ltr)
        assertEquals(safe.top + margin, target.top)
        assertEquals(raised.top - gap, target.bottom)
    }

    @Test
    fun noRoomAboveYieldsNoViewport() {
        assertEquals(0, upwardMenuMaxHeight(chip.copy(top = 70f), safe, margin, gap))
    }

    @Test
    fun aHostEndingAboveTheTriggerStillClampsTheViewportToItsBottom() {
        val shortHost = safe.copy(bottom = 900f)
        val height = upwardMenuMaxHeight(chip, shortHost, margin, gap)
        val target = placeUpwardMenu(chip, menu.copy(height = height.toFloat()), shortHost, margin, gap, LayoutDirection.Ltr)
        assertEquals(shortHost.bottom - margin, target.bottom)
        assertEquals(shortHost.top + margin, target.top)
    }

    @Test
    fun openingAndClosingTrackTheControlsCenterRatherThanPivotingAroundThePanelCorner() {
        val target = placeUpwardMenu(chip, menu, safe, margin, gap, LayoutDirection.Ltr)
        val frame = popupMorphBounds(chip, target, 0.5f, 0.25f)
        assertEquals((chip.width + target.width) / 2, frame.width)
        assertEquals((chip.height + target.height) / 2, frame.height)
        assertEquals(chip.center.x + (target.center.x - chip.center.x) * 0.25f, frame.center.x)
        assertTrue(frame.center.y < chip.center.y && frame.center.y > target.center.y)
        assertEquals(chip, popupMorphBounds(chip, target, 0f, 0f))
    }
}
