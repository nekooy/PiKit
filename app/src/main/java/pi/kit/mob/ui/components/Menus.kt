/*
 * The anchored half of the modal layer: the floating menu.
 *
 * A menu is the same modal a sheet is — one at a time, closed by the back gesture
 * and by a tap outside, drawn by `SheetLayer` — and it is not a `DropdownMenu`.
 * The app's history with anchored menus is written down in §7.1: a `Popup` is a
 * second window that takes focus (the keyboard's, for the composer's chips) and
 * emits a zero-size layout node into the composable it hangs from, which a
 * `Row` with `Arrangement.spacedBy` charges a gap for — the conversation list's
 * row menu slid the title sideways for as long as the app used one. A menu here
 * is a `Box` in the page's own window, exactly as `Sheets.kt` says a sheet is.
 *
 * What makes it a *floating* menu rather than a sheet is where the panel goes and
 * what it costs:
 *
 *  - **An anchor, not the window's edge.** The trigger reports its own
 *    window-relative bounds through `onGloballyPositioned` — a read-only
 *    callback, so the layout the trigger sits in never learns about the menu —
 *    and the panel is placed below that rect, flipping up when the space below
 *    is too small and clamped inside the window when neither side fits.
 *  - **No dim, no drag, no grabber.** A dropdown over the page it came from is
 *    not a screen the reader has to be told is modal: the catcher keeps its
 *    tap-to-dismiss and nothing else, and the panel fades in and out rather than
 *    sliding from the bottom edge.
 *  - **The body is rows, not a lazy list.** The panel's column scrolls when it
 *    has to, but the rows are `PiMenuRow`s measured to the widest of them — a
 *    menu's width follows its content, which is what a dropdown does and what a
 *    `LazyColumn` (which reports no intrinsic width) could not be asked for.
 *
 * The one-slot rule holds across both kinds of panel: opening a menu dismisses
 * the open sheet and the other way round, because `SheetHost` holds one modal
 * and `PiSheetRow`'s and `PiMenuRow`'s tap gates read the same `isOpen`.
 */
package pi.kit.mob.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import pi.kit.mob.ui.design.PiShapes
import kotlin.math.roundToInt

/**
 * Where a menu opens from: the trigger's own window-relative bounds.
 *
 * Held by the caller, kept current by [Modifier.menuAnchor] wherever the trigger
 * is composed, and read at the moment the menu is asked for — so the panel opens
 * where the trigger *is*, not where it was when the screen was built.
 */
@Stable
class MenuAnchor {
    internal var bounds by mutableStateOf(IntRect.Zero)
}

/** One anchor per trigger, remembered like the trigger itself is. */
@Composable
fun rememberMenuAnchor(): MenuAnchor = remember { MenuAnchor() }

/**
 * Reports this node's window-relative bounds into [anchor], continuously.
 *
 * `onGloballyPositioned` only — it observes the layout and changes nothing about
 * it, which is the property the last `DropdownMenu` could not offer: a `Popup`
 * emits a zero-size node into whatever holds it, and the row holding it paid for
 * the menu even while the menu was closed.
 */
fun Modifier.menuAnchor(anchor: MenuAnchor): Modifier = onGloballyPositioned { coordinates ->
    val bounds = coordinates.boundsInWindow()
    anchor.bounds = IntRect(
        left = bounds.left.roundToInt(),
        top = bounds.top.roundToInt(),
        right = bounds.right.roundToInt(),
        bottom = bounds.bottom.roundToInt(),
    )
}

/**
 * Opens a floating menu anchored at [anchor].
 *
 * The bounds are read here, at the call, rather than trusted from the trigger's
 * last report beyond checking that one exists: the report is continuous, so the
 * value read on the tap is the trigger's position on the frame the finger went
 * down. A trigger that has never been positioned has nothing to anchor to and
 * the request is dropped rather than drawn at the window's corner.
 *
 * [alignEnd] hangs the menu off the anchor's trailing edge instead of its
 * leading one — what a row's action menu wants (§7.1).
 */
fun SheetHost.showMenu(
    key: String,
    anchor: MenuAnchor,
    alignEnd: Boolean = false,
    preferAbove: Boolean = false,
    content: @Composable () -> Unit,
) {
    val bounds = anchor.bounds
    if (bounds != IntRect.Zero) {
        show(
            Sheet(
                key = key,
                anchor = bounds,
                alignEnd = alignEnd,
                preferAbove = preferAbove,
                content = content,
            ),
        )
    }
}

/**
 * The anchored menu's panel, drawn by [SheetLayer] instead of the docked sheet.
 *
 * The anchor is window-relative and this panel is placed in the layer's
 * window-filling box, so the two coordinate spaces are the same one — the layer
 * sits at the window's origin, which is the assumption the whole anchored half
 * rests on. Placement: below the anchor when it fits, above it otherwise, and
 * clamped inside the window when the panel is taller than either side — a menu
 * taller than the space it has scrolls inside itself rather than leaving the
 * window. [Sheet.preferAbove] inverts the rule for the composer's menus, whose
 * triggers sit above the field being typed into: above is the only answer while
 * the space above can hold the panel. The keyboard bounds the bottom while it is
 * up, for the same reason the sheet's panel is lifted: the composer's chips — the
 * triggers that open menus mid-sentence — sit above it, and a menu that ran under
 * the keyboard would run under the field the reader is typing in.
 *
 * ## One measure pass, and an animation read at draw time
 *
 * The panel is placed by [SubcomposeLayout]: the content is composed, measured
 * and placed inside one layout pass, so the frame the menu first appears on is
 * already the frame it stays on. The version that measured itself afterwards —
 * a zero size for one frame, placement computed from it, then a correction —
 * jumped visibly mid-fade whenever the menu flipped above its trigger, which is
 * every chip in the composer. That jump under a slow fade is the "不流畅" of the
 * report "点击到出现都不够迅速且不流畅丝滑"; the "不迅速" half is the clock,
 * [MENU_MILLIS], the platform `DropdownMenu`'s own 125 ms.
 *
 * The fade-and-scale reads [SheetHost.progress] inside `graphicsLayer`, at draw
 * time: the animation costs redraws and no recomposition, where reading it in
 * composition rebuilt the whole layer on every frame. The scale grows out of the
 * corner that faces the trigger — the top edge when the menu drops below it, the
 * bottom edge when it flips above, and horizontally the edge the panel is
 * *aligned* by (start for a chip or a toolbar button, end for a row's action
 * menu, [Sheet.alignEnd]) — which is what a dropdown does and what the sheet's
 * translation could not: that one carries a panel up from the window's edge,
 * straight through the control it opened from.
 */
@Composable
internal fun AnchoredMenu(
    sheet: Sheet,
    host: SheetHost,
) {
    val anchor = sheet.anchor ?: return
    val density = LocalDensity.current
    val marginPx = with(density) { SCREEN_MARGIN.roundToPx() }
    val gapPx = with(density) { ANCHOR_GAP.roundToPx() }
    val menuElevationPx = with(density) { MENU_ELEVATION.toPx() }
    val topLimitPx = WindowInsets.statusBars.getTop(density) + marginPx
    val imePx = WindowInsets.ime.getBottom(density)
    // Written by the layout pass and read by the panel's draw lambda: which
    // corner the scale grows out of is only known once the panel has been
    // measured and placed — horizontally the edge it is *aligned* by (see
    // [Sheet.alignEnd]), vertically the edge that faces the trigger.
    var pivotX = 0f
    var pivotY = 0f

    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val windowWidth = constraints.maxWidth
        val windowHeight = constraints.maxHeight
        val bottomLimitPx = windowHeight - if (imePx > 0) imePx else marginPx
        val maxPanelHeight = (bottomLimitPx - topLimitPx).coerceAtLeast(0)

        val placeables = subcompose("anchored-menu") {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = PiShapes.card,
                modifier = Modifier
                    .graphicsLayer {
                        val p = host.progress
                        alpha = p
                        val scale = MENU_SCALE_FROM + (1f - MENU_SCALE_FROM) * p
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(
                            pivotFractionX = pivotX,
                            pivotFractionY = pivotY,
                        )
                        // The shadow rides this layer rather than the Surface's
                        // `shadowElevation`: a platform elevation shadow is
                        // composited *outside* the alpha that fades the panel, so
                        // the first version's shadow arrived at full strength on
                        // the first frame while the panel was still at alpha 0 —
                        // the report "后面的阴影过于生硬，是突然出现的那种".
                        // Growing the elevation with the progress draws no shadow
                        // at 0 and the menu's full shadow at 1, on the same clock.
                        shape = PiShapes.card
                        shadowElevation = menuElevationPx * p
                    }
                    .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH),
            ) {
                Column(
                    Modifier
                        // The widest row is the menu's width; the rest fill it. The
                        // vertical scroll is the panel's own ceiling at work: a menu
                        // taller than the space it has scrolls inside itself.
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = MENU_VERTICAL_PADDING),
                ) {
                    sheet.content()
                }
            }
        }.map {
            it.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = minOf(MENU_MAX_WIDTH.roundToPx(), windowWidth - 2 * marginPx),
                    minHeight = 0,
                    maxHeight = maxPanelHeight,
                ),
            )
        }

        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0

        // Start-aligned to the trigger by default, end-aligned when the caller
        // asks ([Sheet.alignEnd]) or when a start-aligned panel would run past
        // the window's end. The scale grows out of the edge the panel is aligned
        // by, so a menu whose panel hangs to the *left* of its trigger — the `+`
        // at the composer's right corner, which has nothing but the screen edge
        // to its right — still appears from under the button instead of coming
        // in from the screen's other side ("按钮在右边，动画效果却是菜单从左边
        // 弹出来的").
        val endAligned = sheet.alignEnd || anchor.left + width > windowWidth - marginPx
        var x = if (endAligned) anchor.right - width else anchor.left
        x = x.coerceIn(marginPx, (windowWidth - marginPx - width).coerceAtLeast(marginPx))

        val belowY = anchor.bottom + gapPx
        val aboveY = anchor.top - gapPx - height
        val roomBelow = bottomLimitPx - belowY
        val roomAbove = anchor.top - gapPx - topLimitPx
        // `preferAbove` is the caller overriding the auto rule: the composer's menus
        // hang off controls above the field being typed into, and a panel that drops
        // onto that field covers it — so above wins whenever the space above can hold
        // the panel, and the auto rule answers only when it cannot (the window clamp
        // below still bounds a panel taller than either side).
        val below = if (sheet.preferAbove) {
            height > roomAbove && height <= roomBelow
        } else {
            height <= roomBelow || roomBelow >= roomAbove
        }
        val y = (if (below) belowY else aboveY)
            .coerceIn(topLimitPx, (bottomLimitPx - height).coerceAtLeast(topLimitPx))

        pivotX = if (endAligned) 1f else 0f
        pivotY = if (below) 0f else 1f

        layout(windowWidth, windowHeight) {
            placeables.forEach { it.place(x, y) }
        }
    }
}

/**
 * One row of a floating menu.
 *
 * The same kind of line a [PiSheetRow] draws — read once, tapped once, dismissed,
 * the current choice a tick at the end — at the height and width a dropdown
 * carries: 44dp, content-wide, and the menu as wide as its widest row rather
 * than as the window. The tap is gated on the host still being open for the same
 * reason [PiSheetRow]'s is: the panel keeps drawing for the length of its exit,
 * and a second tap aimed at the page behind it must not land on a row of a menu
 * that no longer exists.
 */
@Composable
fun PiMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val host = LocalSheetHost.current
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PiShapes.row)
            .clickable(enabled = enabled && host.isOpen, onClick = onClick)
            .heightIn(min = MENU_ROW_HEIGHT)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Two lines, not one: the command menu's rows carry a
                    // sentence each and the `!` row carries three, and a menu of
                    // ellipsised half-sentences explained nothing. The rows that
                    // have a short value stay one line anyway.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The air between the panel and the window's edges. */
private val SCREEN_MARGIN = 8.dp

/** The air between the panel and the thing it is anchored to. */
private val ANCHOR_GAP = 8.dp

/**
 * Material3's own menu elevation (`MenuDefaults`' container at level 2).
 *
 * Applied inside the panel's `graphicsLayer` and scaled by the animation's
 * progress, so the shadow grows out of nothing on the same clock the panel fades
 * in on — a platform `shadowElevation` on the `Surface` would be composited
 * outside that fade and arrive at full strength on the first frame.
 */
private val MENU_ELEVATION = 3.dp

/** Material's floor for a menu (`MenuDefaults.MinMenuWidth`), so three short rows still read as one panel. */
private val MENU_MIN_WIDTH = 168.dp

/** The ceiling a model id stops at; the sheet picker is where a list is browsed. */
private val MENU_MAX_WIDTH = 320.dp

/** Material3's own vertical padding for a menu's content. */
private val MENU_VERTICAL_PADDING = 8.dp

/** A dropdown row is a line, not a sheet's 56dp row. */
private val MENU_ROW_HEIGHT = 44.dp

/**
 * The scale the panel fades in from, about the edge that faces its anchor.
 *
 * 0.85 rather than the platform dropdown's 0.8: a 320dp-wide list scaling a
 * fifth of itself in one frame of the fade reads as a lunge, where a chip-sized
 * menu does not — the platform's number is for menus of text rows. The motion is
 * the platform's own shape, softened at this size.
 */
private const val MENU_SCALE_FROM = 0.85f
