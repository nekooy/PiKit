package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupSizing

/**
 * A floating menu at a control, in OS4's glass — the app's one popup.
 *
 * ## Why the library's glass popup and not `OverlayListPopup`
 *
 * `OverlayListPopup` and the `OverlayDropdownPopup` built on it draw Miuix's **basic**
 * popup panel: an opaque `surfaceContainer` rectangle at a 16dp rounded corner, with no
 * blur, no bloom stroke and no shadow. That is the pre-OS4 material, and it is what every
 * menu in this app was still drawing — the composer's nine, the transcript's card menu,
 * the history drawer's row menu, the files page's row menu and the terminal's session
 * roster — a flat rectangle beside a page whose bar, capsules and dialogs are glass (§13).
 *
 * `GlassPopup` is the library's OS4 replacement: a 24dp continuous-corner panel cut from
 * `popupViewGlass` over the page's backdrop, with the small bloom edge, the panel shadow
 * and the same rows. Its material is the library's default rather than
 * [PiGlass.material]: a menu is a page-sized panel, and the 28dp blur a 32dp chip is cut
 * with is the wrong reach for it.
 *
 * ## The panel is placed by the library
 *
 * `GlassPopup` places its panel with `placeGlassPopup`: end-aligned to the anchor, below
 * it when the panel fits under the anchor and above it otherwise, clamped into the
 * window's safe area, and with **no centred branch** — the branch that put a composer
 * menu over the field it belongs to and that made the app's own
 * `aboveAnchorPositionProvider` necessary. On the chip row the library's own test reaches
 * the same answer the provider did: a chip at y≈2020 in a 2337px window has 305px below
 * it and a 416dp menu is ≈1090px, so it opens above, its bottom edge on the chip's.
 *
 * ## Where the panel is drawn, and why it is not where the trigger is
 *
 * A panel samples the page's backdrop, so it must be **outside** the layer that records
 * the page — a control that samples a recording of itself is the RenderThread stack
 * overflow docs/UI.md's first red line documents — and it has to be measured against the
 * window, not against the row that opened it: a `fillMaxSize` panel composed inside a
 * drawer row or a 32dp chip is clipped to that node.
 *
 * [PiPopupMenu] therefore *registers* into [PiPopupHostProvider], which a page provides
 * beside its body, and the host draws the panel full-window from there. The call site
 * stays at the control — one `PiPopupMenu` beside the chip or the row, reading the same
 * `show` flag — and the placement is the host's. That is the same division Miuix's own
 * `OverlayListPopup` makes (its panel is drawn in the root `Scaffold`'s popup host).
 *
 * A menu still belongs to the composition that raised it: a row that leaves its list takes
 * its menu with it, because the registration is a `DisposableEffect` on that call site.
 * That is the right answer for a row's own actions — the panel is about a row the reader
 * can no longer see. The transcript's card menu is the exception, and it is why that one is
 * registered by the page rather than by the card: a long conversation recycles cards
 * constantly, and a menu that vanished mid-read because its card scrolled one screen away
 * would be a menu the reader cannot use.
 *
 * ## The fallback is the library's, and it is opaque
 *
 * Below API 33 [LocalPiBackdrop] is null, and `GlassPopup` then draws its
 * `containerColor` — an opaque fill — while keeping the bloom stroke and the shadow. That
 * is the same gate every other glass call site in the app passes through (docs/UI.md's
 * fourth red line), and it is why this component may be used on the Android 8 floor at
 * all.
 *
 * @param anchor the trigger's bounds, reported by [Modifier.piMenuAnchor].
 * @param placement which side of the anchor the panel opens on. [PiPopupPlacement.Auto]
 *   is the library's own fit test, which is right for a row anywhere on a page;
 *   [PiPopupPlacement.Above] is for a control in a band pinned to the bottom of the
 *   window, where the space "below" is either the input field or the keyboard.
 * @param content the rows. [PiMenuRow] is the app's row, at the library's popup metrics.
 */
@Composable
fun PiPopupMenu(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchor: PiMenuAnchor,
    placement: PiPopupPlacement = PiPopupPlacement.Auto,
    sizing: GlassPopupSizing = GlassPopupSizing(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalPiPopupHost.current
    val entry = remember(anchor) { PiPopupEntry(anchor) }
    // Written in a side effect rather than in the entry's constructor: `content` is a new
    // lambda instance on every recomposition of the caller, and the host reads the latest
    // one. `rememberUpdatedState` is not enough here because the *host* composes it.
    SideEffect {
        entry.show = show
        entry.onDismissRequest = onDismissRequest
        entry.placement = placement
        entry.sizing = sizing
        entry.content = content
    }
    DisposableEffect(host, entry) {
        host?.entries?.add(entry)
        onDispose { host?.entries?.remove(entry) }
    }
    // A page that provides no host still gets a menu: it is drawn in place, which is only
    // wrong when the caller is inside a bounded node (a drawer row, a chip). Every page in
    // the app provides the host, so this is the answer for a composable that is not on one
    // rather than a mode the app uses.
    if (host == null) {
        PiPopupPanel(entry)
    }
}

/**
 * Which side of its trigger a menu opens on.
 *
 * Two values, not a position provider: the library places the panel itself and there is
 * no hook for a caller's arithmetic (that hook is what the app used to hand
 * `OverlayListPopup`). The one thing a call site still has to say is which way a band
 * pinned to the bottom of the window opens, and that is this.
 */
enum class PiPopupPlacement {
    /** Below the trigger when the panel fits, above it otherwise. The library's own test. */
    Auto,

    /**
     * Always above the trigger, its bottom edge on the trigger's top.
     *
     * For the composer's chip row and its toolbar: the row is the last thing above the
     * input field, so a panel placed "below" lands on the field the reader is about to
     * type into, and — with the keyboard up — under it, because the library's fit test
     * measures against the window and the keyboard is an inset of the content rather than
     * of the window. Opening upward puts the panel over the transcript instead, which is
     * the part of the page the reader is not using while choosing a setting.
     */
    Above,
}

/**
 * The window-space bounds a [PiPopupMenu] opens against.
 *
 * A menu is drawn by the page's host (see [PiPopupMenu]), so the trigger's place has to
 * travel separately from the trigger's composition. The value is in **window
 * coordinates** because that is what `GlassPopup` places in, so a page that starts below a
 * status bar is placed correctly without the call site subtracting anything.
 *
 * The last reported rectangle is kept: a row that scrolls out of its list stops reporting,
 * and the panel stays where it opened rather than following a row that no longer exists.
 */
@Stable
class PiMenuAnchor {

    /** The trigger's bounds in window coordinates, or [Rect.Zero] before it is measured. */
    var bounds: Rect by mutableStateOf(Rect.Zero)
}

/** An anchor for one control's menu. */
@Composable
fun rememberPiMenuAnchor(): PiMenuAnchor = remember { PiMenuAnchor() }

/**
 * Reports this control's bounds to [anchor] so its menu opens at it.
 *
 * Apply to the node the menu should align to — the chip, the button or the row itself, not
 * a wrapper larger than it looks: the panel's end edge is this node's end edge, and a menu
 * that opens at the top edge of a row is the anchor's top edge.
 *
 * [openBelow] pushes the reported rectangle down by that much, which moves the panel with
 * it. It exists for a control in the page's **bar**: the bar is chrome and is deliberately
 * outside the layer the page records (`layerBackdrop` must not contain the glass controls
 * that sample it), so a panel drawn over the bar has nothing to blur there and the bar's
 * own title and subtitle show through it. Passing the bar's height opens the panel under
 * the chrome instead of over it; [LocalPiBarHeight] is where that number comes from.
 */
@Composable
fun Modifier.piMenuAnchor(anchor: PiMenuAnchor, openBelow: Dp = 0.dp): Modifier {
    val shift = with(LocalDensity.current) { openBelow.toPx() }
    return onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        anchor.bounds = if (shift == 0f) {
            bounds
        } else {
            Rect(
                left = bounds.left,
                top = bounds.top + shift,
                right = bounds.right,
                bottom = bounds.bottom + shift,
            )
        }
    }
}

/**
 * Draws the glass menus its subtree registers.
 *
 * One per page, beside the body and outside the recorded layer (see [PiPopupMenu]). The
 * panels are drawn after the content, so a menu is over the page it belongs to; nothing is
 * composed while no menu is open, so the host costs no frame of its own.
 *
 * The content and the panels share one `Box`: the panels are `fillMaxSize`, so a host whose
 * two halves were siblings would lay them out one after the other in whatever parent the
 * caller happened to be in — a page inside a `Column` would get its `Scaffold` at the top
 * and the popup layer below it, off screen. Overlapping them is the only arrangement that
 * means "over the page" rather than "next to the page".
 */
@Composable
fun PiPopupHostProvider(content: @Composable () -> Unit) {
    val host = remember { PiPopupHost() }
    CompositionLocalProvider(LocalPiPopupHost provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            host.entries.forEach { entry ->
                key(entry) { PiPopupPanel(entry) }
            }
        }
    }
}

/** The page's registered menus. `null` outside a [PiPopupHostProvider]. */
val LocalPiPopupHost = staticCompositionLocalOf<PiPopupHost?> { null }

/** One page's set of registered menus, drawn by [PiPopupHostProvider]. */
@Stable
class PiPopupHost internal constructor() {
    internal val entries = mutableStateListOf<PiPopupEntry>()
}

/**
 * One registered menu: the anchor, the flag that drives it, and its rows.
 *
 * The fields are state so the host recomposes only the panel whose value moved, and
 * `content` is held as a lambda because the host is what composes it.
 */
@Stable
internal class PiPopupEntry(val anchor: PiMenuAnchor) {
    var show: Boolean by mutableStateOf(false)
    var onDismissRequest: () -> Unit by mutableStateOf({})
    var placement: PiPopupPlacement by mutableStateOf(PiPopupPlacement.Auto)
    var sizing: GlassPopupSizing by mutableStateOf(GlassPopupSizing())
    var content: @Composable ColumnScope.() -> Unit by mutableStateOf({})
}

/**
 * One panel, drawn against the page's own backdrop.
 *
 * The full-size box is the panel's host and its outside-tap layer: `GlassPopup` composes
 * that layer itself, but it needs the window's rectangle to place against, so it is given
 * one here.
 */
@Composable
private fun PiPopupPanel(entry: PiPopupEntry) {
    val window = LocalWindowInfo.current
    Box(Modifier.fillMaxSize()) {
        GlassPopup(
            show = entry.show,
            onDismissRequest = { entry.onDismissRequest() },
            anchorBounds = when (entry.placement) {
                PiPopupPlacement.Auto -> entry.anchor.bounds
                PiPopupPlacement.Above -> aboveOpeningBounds(
                    anchor = entry.anchor.bounds,
                    windowHeight = with(LocalDensity.current) {
                        window.containerSize.height.toFloat()
                    },
                )
            },
            backdrop = LocalPiBackdrop.current,
            sizing = entry.sizing,
            cornerRadius = PiCorner.popup,
            content = entry.content,
        )
    }
}

/**
 * The rectangle that makes `GlassPopup` open **above** its trigger, whatever fits below.
 *
 * `GlassPopup` places its own panel and takes no placement override, so the only way to
 * answer "which way" is the rectangle it is handed. Its arithmetic
 * (`placeGlassPopup`, read out of `miuix-glass` 0.9.5-beta02) is:
 *
 * ```
 * below    = window.bottom - margin - anchor.top
 * above    = anchor.bottom - min(window.top + margin, anchor.top)
 * alignTop = size.height <= below || below >= above
 * top      = if (alignTop) anchor.top else anchor.bottom - size.height
 * ```
 *
 * `alignTop` is the "opens downward" branch, so forcing it false forces the other one,
 * whose `top` is `anchor.bottom - size.height` — the panel's bottom edge on the anchor's.
 * Two things in the rectangle do that, and both are needed:
 *
 * - **`top` is past the window's bottom**, which drives `below` negative. A negative
 *   `below` fails both halves of the test whatever the panel measures, so this is not a
 *   fit test the app is guessing at — it is unreachable for any content height.
 * - **`bottom` is the anchor's top edge**, which is where the panel's own bottom then
 *   lands. The trigger keeps its position in the horizontal extents, so the panel is
 *   still end-aligned to it.
 *
 * The alternative — reimplementing the panel so the app could place it — was rejected:
 * `GlassPopup` is the library's OS4 surface (morph, bloom stroke, shadow, scroll), and a
 * copy of it here would be a second material to keep in step with every release. The
 * third option, leaving the library's own fit test in charge, is what put a chip's menu
 * over the composer field: a chip row is the last thing above the input, so "there is
 * room below" is true and wrong.
 */
internal fun aboveOpeningBounds(anchor: Rect, windowHeight: Float): Rect = Rect(
    left = anchor.left,
    // Past the window's bottom, so the library's `below` is negative (see the KDoc).
    top = windowHeight,
    right = anchor.right,
    bottom = anchor.top,
)
