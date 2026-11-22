package pi.kit.mob.ui.kit

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

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
 * ## Motion and placement follow the trigger
 *
 * Context menus fade in at full size; the composer and a glass icon button morph from
 * their measured trigger. The composer's endpoint is a viewport above it, so the menu
 * cannot open over its input field when the IME raises the chip row. A glass icon
 * button's endpoint is the bar it belongs to, or the button itself when no bar bottom
 * is supplied.
 *
 * The morph is the kit's own rather than Miuix's `GlassTransformPopup`: the library's
 * popup keeps its full-page dismiss layer composed through the exit spring, and that
 * layer consumes every tap until the panel has finished leaving. A trigger tapped twice
 * in quick succession — close, then open again — lost the second tap inside the
 * invisible layer. [PiAnchoredMenu] draws the panel itself and drops the dismiss layer
 * the moment `open` turns false, so the trigger underneath answers again while the exit
 * is still playing.
 *
 * A **dropdown row's panel is not a menu**, and it is the one panel in this file that does
 * not take that fade: its alternatives *are* the row's value unfolded, so it opens with the
 * library's arc out of the value cell, the same motion the plain form of
 * [PiDropdownPreference] gets from `GlassDropdownPreference`. [PiDropdownPopup] is that
 * panel. A menu lists actions for a control the reader pressed; a dropdown states what a
 * value can become, and a value that faded in at full size read as no motion at all beside
 * the arc of the row next to it.
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
 * Below API 33 the host's backdrop is null, and the glass surface then draws its
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
    RegisterPiPopup(anchor) { backdrop ->
        PiAnchoredMenu(
            show = show,
            onDismissRequest = onDismissRequest,
            anchor = anchor,
            placement = placement,
            backdrop = backdrop,
            sizing = sizing,
            content = content,
        )
    }
}

/**
 * A menu opened from a glass icon button, growing out of the button itself.
 *
 * The button reports its bounds through [Modifier.piGlassMenuAnchor]; the panel is
 * [PiAnchoredMenu]'s morph, not Miuix's `GlassTransformPopup` — see the file's
 * *Motion and placement follow the trigger* for the dismissal defect that keeps the
 * library's version out of the kit.
 */
@Composable
fun PiPopupMenu(
    show: () -> Boolean,
    onDismissRequest: () -> Unit,
    anchor: PiGlassMenuAnchor,
    openBelow: Float = 0f,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterPiPopup(anchor) { backdrop ->
        PiAnchoredMenu(
            show = show(),
            onDismissRequest = onDismissRequest,
            anchor = anchor.menu,
            placement = PiPopupPlacement.FromAnchor,
            backdrop = backdrop,
            sizing = GlassPopupSizing(),
            below = openBelow,
            content = content,
        )
    }
}

/**
 * A dropdown row's panel: the library's own `GlassDropdownPopup`, in Miuix's popup layer.
 *
 * This is the popup `GlassDropdownPreference` raises for the plain form of
 * [PiDropdownPreference], used directly because the summarised form's alternatives are the
 * app's [PiMenuRow]s rather than the library's `GlassPopupItem`s. Taking the library's
 * component rather than the anchored menu above is what makes the two forms of one row open
 * the same way: the panel grows out of the value cell along the library's arc, starting as a
 * flat capsule at `GlassMotion.ARC_START_WIDTH` (69%) of its settled width, and the value it
 * replaces is faded out while it is open. Re-deriving that motion on top of [PiAnchoredMenu]
 * was the alternative and was rejected — the arc is four springs and a frame function in the
 * library, and a second copy of them would be a second thing to keep in step.
 *
 * The panel samples the page's backdrop, so it has to stay outside the recording that holds
 * the row (docs/UI.md's first red line). It registers in Miuix's root popup layer through
 * `PopupLayout` — the same mount `GlassDropdownPreference` uses — rather than in the page's
 * [PiPopupHostProvider]. An earlier version used that host and the panel measured at a
 * zero-sized frame and never drew, while its empty overlay still swallowed every touch on
 * the page.
 *
 * @param show whether the panel is open. The panel animates out through this flag, so it
 *   stays composed while it leaves; the caller keeps the value rather than composing this
 *   inside `if (show)`.
 * @param anchor the row's value cell, attached with `Modifier.glassPopupAnchorValue`. It is
 *   the panel's collapsed endpoint: the arc starts at its bounds and the library hides the
 *   value it draws while the panel is open.
 * @param bounds the row's own bounds, reported by [Modifier.piMenuAnchor]. The library falls
 *   back to them only while the value cell has not been measured.
 * @param content the rows. [PiMenuRow] is the app's row, at the library's popup metrics.
 */
@Composable
fun PiDropdownPopup(
    show: Boolean,
    onDismissRequest: () -> Unit,
    anchor: GlassPopupAnchor,
    bounds: Rect,
    sizing: GlassPopupSizing = GlassPopupSizing(),
    content: @Composable ColumnScope.() -> Unit,
) {
    // The panel is hosted by Miuix's root popup layer, not by `PiPopupHostProvider`.
    // `GlassDropdownPopup` measures itself against the window and draws its own
    // full-page dismiss layer; composed through the in-page host its panel was laid
    // out at a zero-sized frame and never appeared, while the library's own
    // `GlassDropdownPreference` — which uses `PopupLayout` — opened normally on the
    // page next to it. This is the same mount the library uses. `show` drives the
    // panel's own arc and its exit animation; the host itself is mounted only while
    // the panel is open (plus a short exit tail), because a `PopupLayout` left
    // mounted is a full-window popup window over a page nobody can touch.
    var mounted by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (show) {
            mounted = true
        } else if (mounted) {
            delay(DROPDOWN_EXIT_MS)
            mounted = false
        }
    }
    if (!mounted) return

    val visible = remember { mutableStateOf(true) }
    val backdrop = LocalPiBackdrop.current
    PopupLayout(
        visible = visible,
        enableWindowDim = false,
        enableBackHandler = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
    ) {
        Box(Modifier.fillMaxSize()) {
            GlassDropdownPopup(
                show = show,
                onDismissRequest = onDismissRequest,
                anchorBounds = bounds,
                backdrop = backdrop,
                anchor = anchor,
                sizing = sizing,
                cornerRadius = PiCorner.popup,
                content = content,
            )
        }
    }
}

/** The library's dropdown exit arc, with a frame of slack before its host is removed. */
private const val DROPDOWN_EXIT_MS = 350L

@Stable
class PiGlassMenuAnchor internal constructor(internal val menu: PiMenuAnchor)

@Composable
fun rememberPiGlassMenuAnchor(): PiGlassMenuAnchor =
    remember { PiGlassMenuAnchor(PiMenuAnchor()) }

/** Reports this glass icon button's own bounds as the menu's collapsed endpoint. */
fun Modifier.piGlassMenuAnchor(anchor: PiGlassMenuAnchor): Modifier =
    onGloballyPositioned { anchor.menu.bounds = it.boundsInWindow() }

@Composable
private fun RegisterPiPopup(anchor: Any, panel: @Composable BoxScope.(Backdrop?) -> Unit) {
    val host = checkNotNull(LocalPiPopupHost.current) { "PiPopupMenu requires a bounded PiPopupHostProvider" }
    val entry = remember(anchor) { PiPopupEntry() }
    SideEffect { entry.panel = panel }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { host.entries.remove(entry) }
    }
}

/**
 * Which side of its trigger a menu opens on.
 *
 * Point and row context menus use the library's fit test. Bottom chrome must open upward
 * regardless of the space the window reports below it.
 */
enum class PiPopupPlacement {
    /** Below the trigger when the panel fits, above it otherwise. The library's own test. */
    Auto,

    /**
     * Always above the trigger, with a small gap and logical-start alignment.
     *
     * For the composer's chip row and its toolbar: the row is the last thing above the
     * input field, so a panel placed "below" lands on the field the reader is about to
     * type into, and — with the keyboard up — under it, because the library's fit test
     * measures against the window and the keyboard is an inset of the content rather than
     * of the window. Opening upward puts the panel over the transcript instead, which is
     * the part of the page the reader is not using while choosing a setting.
     */
    Above,

    /**
     * Morphs out of the measured trigger and settles at its end edge, below it when the
     * panel fits — the glass icon button's shape. [PiPopupMenu]'s button overload supplies
     * the button's bounds; the settled rectangle may be translated by `openBelow` to hang
     * from a bar's bottom instead of the button itself.
     */
    FromAnchor,
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
 * a wrapper larger than it looks: this is also the collapsed size of an upward opening menu.
 *
 */
fun Modifier.piMenuAnchor(anchor: PiMenuAnchor): Modifier {
    return onGloballyPositioned { coordinates ->
        anchor.bounds = coordinates.boundsInWindow()
    }
}

/**
 * Draws the glass menus its subtree registers.
 *
 * Records the whole scene (including chrome) and draws the menus as a sibling of that
 * recording. Glass controls in [content] must keep sampling their own inner body layers.
 * The overlay matches the scene's size rather than taking constraints from a trigger.
 */
@Composable
fun PiPopupHostProvider(
    base: Color = MiuixTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val host = remember { PiPopupHost() }
    val backdrop = rememberPiBackdrop(base)
    CompositionLocalProvider(LocalPiPopupHost provides host) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
                content()
            }
            Box(Modifier.matchParentSize()) {
                host.entries.forEach { entry ->
                    key(entry) { entry.panel(this, backdrop) }
                }
            }
        }
    }
}

/** The page's registered menus. `null` outside a [PiPopupHostProvider]. */
private val LocalPiPopupHost = staticCompositionLocalOf<PiPopupHost?> { null }

/** One page's set of registered menus, drawn by [PiPopupHostProvider]. */
@Stable
private class PiPopupHost {
    val entries = mutableStateListOf<PiPopupEntry>()
}

/**
 * One registered menu: the anchor, the flag that drives it, and its rows.
 *
 * The fields are state so the host recomposes only the panel whose value moved, and
 * `content` is held as a lambda because the host is what composes it.
 */
@Stable
private class PiPopupEntry {
    var panel: @Composable BoxScope.(Backdrop?) -> Unit by mutableStateOf({})
}
