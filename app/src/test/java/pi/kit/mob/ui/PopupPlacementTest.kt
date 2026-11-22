package pi.kit.mob.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.ui.kit.anchorCopyMotion
import pi.kit.mob.ui.kit.menuSettleTrigger
import pi.kit.mob.ui.kit.placeContextMenu
import pi.kit.mob.ui.kit.placeUpwardMenu
import pi.kit.mob.ui.kit.popupMorphBounds
import pi.kit.mob.ui.kit.popupRevealBounds
import pi.kit.mob.ui.kit.upwardMenuMaxHeight

class PopupPlacementTest {
    private val safe = Rect(24f, 63f, 1056f, 2337f)
    private val chip = Rect(120f, 1938f, 260f, 2022f)
    private val margin = 32f
    private val gap = 21f
    private val menu = Size(480f, 240f)

    @Test
    fun aBarButtonsMenuHangsFromTheBarBottomButMorphsFromTheButton() {
        val button = Rect(900f, 70f, 980f, 150f)
        val barBottom = 210f
        val settle = menuSettleTrigger(button, barBottom)
        assertEquals(barBottom, settle.top)
        assertEquals(button.left, settle.left)
        assertEquals(button.right, settle.right)
        val target = placeContextMenu(settle, menu, safe, margin, LayoutDirection.Ltr)
        assertEquals(barBottom, target.top)
        assertEquals(button, popupMorphBounds(button, target, 0f, 0f))
    }

    @Test
    fun aControlBelowTheBarKeepsItsOwnSettledRect() {
        val sortButton = Rect(900f, 420f, 980f, 500f)
        assertEquals(sortButton, menuSettleTrigger(sortButton, 210f))
        assertEquals(sortButton, menuSettleTrigger(sortButton, 0f))
    }

    @Test
    fun aContextMenuHasFullPanelBoundsThroughoutItsReveal() {
        val point = Rect(700f, 900f, 700f, 900f)
        val settled = placeContextMenu(point, menu, safe, margin, LayoutDirection.Ltr)
        val start = popupRevealBounds(settled, point, safe, 16f, 0f)
        assertEquals(settled.translate(0f, -16f), start)
        for (fraction in listOf(0f, 0.25f, 0.5f, 1f, 1.1f)) {
            assertEquals(menu, popupRevealBounds(settled, point, safe, 16f, fraction).size)
        }
        assertEquals(settled, popupRevealBounds(settled, point, safe, 16f, 1f))
    }

    @Test
    fun aContextMenuDoesNotSlidePastTheSafeArea() {
        val point = Rect(700f, safe.top + 2f, 700f, safe.top + 2f)
        val settled = placeContextMenu(point, menu, safe, margin, LayoutDirection.Ltr)
        assertEquals(Rect(220f, safe.top, 700f, safe.top + menu.height), popupRevealBounds(settled, point, safe, 16f, 0f))
    }

    @Test
    fun aContextMenuNearTheBottomFitsAboveItsTrigger() {
        val row = Rect(100f, 2160f, 1020f, 2270f)
        val ltr = placeContextMenu(row, menu, safe, margin, LayoutDirection.Ltr)
        assertEquals(Rect(540f, 2030f, 1020f, 2270f), ltr)
        val rtl = placeContextMenu(row, menu, safe, margin, LayoutDirection.Rtl)
        assertEquals(Rect(100f, 2030f, 580f, 2270f), rtl)
        assertEquals(ltr.translate(0f, 16f), popupRevealBounds(ltr, row, safe, 16f, 0f))
    }

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
    fun aGlassButtonsGlyphGrowsFromTheButtonIntoThePanel() {
        val button = Rect(900f, 420f, 980f, 500f)
        val settled = Rect(200f, 420f, 980f, 660f)
        val start = anchorCopyMotion(button, settled.center, settled.width, 0f, 0f)
        assertEquals(0f, start.translation.x, 1e-4f)
        assertEquals(0f, start.translation.y, 1e-4f)
        assertEquals(1f, start.scale, 1e-4f)
        val end = anchorCopyMotion(button, settled.center, settled.width, 1f, 1f)
        assertEquals(settled.center.x - button.center.x, end.translation.x, 1e-4f)
        assertEquals(settled.center.y - button.center.y, end.translation.y, 1e-4f)
        assertEquals(settled.width / button.width, end.scale, 1e-4f)
        val half = anchorCopyMotion(button, settled.center, settled.width, 0.5f, 0.5f)
        assertEquals((settled.center.x - button.center.x) * 0.5f, half.translation.x, 1e-4f)
        assertEquals((button.width + settled.width) / 2 / button.width, half.scale, 1e-4f)
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
