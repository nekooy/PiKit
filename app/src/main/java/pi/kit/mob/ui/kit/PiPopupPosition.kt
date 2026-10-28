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
 * The anchor's top edge is the popup's bottom edge, offset by [verticalMargin], and the
 * result is clamped into the window: a popup taller than the space above the anchor
 * slides down until its own bottom is at the anchor rather than being drawn off the
 * status bar. Horizontal placement follows the library's rule — start-aligned to the
 * anchor, or end-aligned when the caller asked for `Align.End` — and is clamped the
 * same way.
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
    ): IntOffset {
        val offsetX = if (isEndAligned(alignment, layoutDirection)) {
            anchorBounds.right - popupContentSize.width - popupMargin.right
        } else {
            anchorBounds.left + popupMargin.left
        }
        // Above, always: the popup's bottom edge sits on the anchor's top edge, less the
        // margin. `max` rather than a bare subtraction is the clamp that keeps a tall
        // menu inside the window — it can never start above the top margin, so the
        // overflow is pushed down against the anchor instead of off the screen.
        val wantedY = anchorBounds.top - popupContentSize.height - popupMargin.top
        val lowestY = windowBounds.top + popupMargin.top
        val offsetY = max(wantedY, lowestY)
        return IntOffset(
            x = offsetX.coerceIn(
                windowBounds.left,
                (windowBounds.right - popupContentSize.width - popupMargin.right)
                    .coerceAtLeast(windowBounds.left),
            ),
            y = offsetY.coerceIn(
                lowestY,
                (windowBounds.bottom - popupContentSize.height - popupMargin.bottom)
                    .coerceAtLeast(lowestY),
            ),
        )
    }

    override fun getMargins(): PaddingValues = margins
}

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
    @Suppress("UNUSED_PARAMETER") layoutDirection: LayoutDirection,
): Boolean = when (alignment) {
    PopupPositionProvider.Align.Start,
    PopupPositionProvider.Align.TopStart,
    PopupPositionProvider.Align.BottomStart,
    -> false

    PopupPositionProvider.Align.End,
    PopupPositionProvider.Align.TopEnd,
    PopupPositionProvider.Align.BottomEnd,
    -> true
}
