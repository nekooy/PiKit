package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import kotlin.math.max
import kotlin.math.min

/**
 * A popup position provider that always opens **above** its anchor.
 *
 * The library's own `dropdownPositionProvider` picks by fit: below when the space under
 * the anchor holds the popup, above when it does not, and centred when neither does.
 * That is right for a menu whose anchor is anywhere on a page — a toolbar button, a
 * settings row — and it is wrong for the composer's chip row, which sits in a band
 * pinned to the bottom of the window with a keyboard under it.
 *
 * ## Why the composer's menus open upward
 *
 * The chip row is the *last* thing above the input field, which is the last thing above
 * the navigation bar. A popup that opens below the row therefore lands over the field
 * the reader is about to type into, over the send button, and — once the keyboard is up
 * — off the bottom of the visible window, where the fit test puts it in the "centred"
 * branch and the menu covers the field it belongs to. Opening upward puts the panel
 * over the transcript instead, which is the part of the page the reader is not using
 * while choosing a setting.
 *
 * The one menu that *must* open upward is the thinking level's: it is seven rows, so it
 * is the tallest of the four, and it is the one whose chip is leftmost — where the
 * library's fit test has the least room to work with.
 *
 * ## The arithmetic
 *
 * The anchor's top edge is the popup's bottom edge, offset by [verticalMargin]; the
 * panel's own top is then floored at the window's top edge, so a popup taller than the
 * space above the anchor slides down until its top is at the window's top rather than
 * being drawn off the status bar. Horizontal placement follows the library's rule —
 * start-aligned to the anchor, or end-aligned when the caller asked for `Align.End` —
 * and is clamped into the window.
 *
 * ## The window rectangle it is handed can predate the anchor
 *
 * `windowBounds` is a rectangle the library composed, not a live reading: Miuix builds it
 * in `rememberListPopupLayoutInfo` from `WindowInfo.containerSize` and the system-bar
 * insets, and `containerSize` is state Compose refreshes **only** from
 * `AndroidComposeView.onConfigurationChanged` (see `MeasuredWindowInfo` in `PiOverlays`,
 * which is the app's answer to that). A rectangle from before a window changed size
 * still holds the anchor's *old* place, so a clamp against its far edges is a clamp
 * against a window that is no longer there: with the small window's `bottom`, "keep the
 * popup inside the window" turns into "pull the popup up by the height the window has
 * gained", and a 320dp menu over a composer chip opens at the top of the screen instead
 * of on the chip it belongs to.
 *
 * The anchor is the fact that cannot be stale. Miuix measures it with
 * `onGloballyPositioned` on every open — the popup body is not composed until the anchor
 * has been measured, and the `remember` holding it is created after the `show` guard, so
 * closing the popup drops it — and it is measured in window coordinates by the same
 * composition that asks for the panel. The rectangle is therefore widened to hold the
 * anchor before it is used as a clamp ([windowBoundsAround]), and the vertical axis is
 * never clamped against the rectangle's bottom at all: an above-anchor menu's bottom edge
 * *is* its anchor's top edge, and a window that is current contains that anchor, so the
 * bottom clamp can only ever pull the panel away from the trigger and never towards it.
 */
fun aboveAnchorPositionProvider(
    verticalMargin: Dp = 8.dp,
    horizontalMargin: Dp = 0.dp,
): PopupPositionProvider = object : PopupPositionProvider {
    private val margins = PaddingValues(horizontal = horizontalMargin, vertical = verticalMargin)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset = aboveAnchorOffset(
        anchorBounds = anchorBounds,
        windowBounds = windowBounds,
        popupContentSize = popupContentSize,
        popupMargin = popupMargin,
        endAligned = isEndAligned(alignment, layoutDirection),
    )

    override fun getMargins(): PaddingValues = margins
}

/**
 * Where an above-anchor panel goes, in window pixels.
 *
 * The whole arithmetic of the provider, in one place and without a Compose type in its
 * signature, so the two windows a phone actually has — a small floating one and
 * fullscreen — can be driven from a JVM test (`PopupPositionTest`). The provider above
 * is the only caller.
 *
 * [endAligned] is the horizontal half of the library's `Align`: the panel's right edge on
 * the anchor's right edge when true, its left edge on the anchor's left edge otherwise.
 *
 * See [aboveAnchorPositionProvider]'s documentation for why the vertical axis is floored
 * at the window's top and never clamped against its bottom.
 */
internal fun aboveAnchorOffset(
    anchorBounds: IntRect,
    windowBounds: IntRect,
    popupContentSize: IntSize,
    popupMargin: IntRect,
    endAligned: Boolean,
): IntOffset {
    val offsetX = if (endAligned) {
        anchorBounds.right - popupContentSize.width - popupMargin.right
    } else {
        anchorBounds.left + popupMargin.left
    }
    val bounds = windowBoundsAround(anchorBounds, windowBounds)
    // Above, always: the popup's bottom edge sits on the anchor's top edge, less the
    // margin, and its own top is floored at the window's top. `max` is the whole of the
    // vertical clamp — see the provider's documentation for why there is deliberately no
    // clamp against `bounds.bottom`.
    val wantedY = anchorBounds.top - popupContentSize.height - popupMargin.top
    val lowestY = bounds.top + popupMargin.top
    return IntOffset(
        x = offsetX.coerceIn(
            bounds.left,
            (bounds.right - popupContentSize.width - popupMargin.right)
                .coerceAtLeast(bounds.left),
        ),
        y = max(wantedY, lowestY),
    )
}

/**
 * [windowBounds] widened to hold [anchorBounds].
 *
 * A window rectangle that does not contain the anchor it is asked to clamp an anchored
 * popup into is a rectangle from before the window changed size: the anchor is measured
 * in window coordinates by the same composition that asks for the popup, so a window
 * that is current always contains it. Widening restores the one property the clamp
 * relies on without inventing a rectangle from nothing — the far edges keep what the
 * library knows, and only an edge the anchor has moved past is replaced by the anchor's
 * own.
 */
internal fun windowBoundsAround(anchorBounds: IntRect, windowBounds: IntRect): IntRect =
    IntRect(
        left = min(windowBounds.left, anchorBounds.left),
        top = min(windowBounds.top, anchorBounds.top),
        right = max(windowBounds.right, anchorBounds.right),
        bottom = max(windowBounds.bottom, anchorBounds.bottom),
    )

/**
 * Whether [alignment] puts the popup's right edge on the anchor's right edge.
 *
 * The library's own `dropdownPositionProvider` answers this through a **private**
 * extension on its `Align` enum, which a caller outside the library cannot reach — so
 * the three values that mean "end" are listed here instead. `Start`, `TopStart` and
 * `BottomStart` are the start-aligned ones; the rest are end-aligned. That is the whole
 * of the rule, and it is the same set the library's own provider resolves to.
 *
 * The layout direction is taken into account the way the library does: an end alignment
 * in an RTL layout is the *left* edge, which is what the caller asked for by naming the
 * logical end rather than a physical side.
 */
private fun isEndAligned(
    alignment: PopupPositionProvider.Align,
    layoutDirection: LayoutDirection,
): Boolean {
    val logicalEnd = when (alignment) {
        PopupPositionProvider.Align.Start,
        PopupPositionProvider.Align.TopStart,
        PopupPositionProvider.Align.BottomStart,
        -> false

        PopupPositionProvider.Align.End,
        PopupPositionProvider.Align.TopEnd,
        PopupPositionProvider.Align.BottomEnd,
        -> true
    }
    // A logical end is the physical right in LTR and the physical left in RTL, which is
    // what the provider's boolean means (`aboveAnchorOffset`'s `endAligned` is the
    // physical right edge). The parameter used to be suppressed while this KDoc claimed
    // the flip; no RTL locale ships yet, so the bug was latent rather than visible.
    return if (layoutDirection == LayoutDirection.Ltr) logicalEnd else !logicalEnd
}
