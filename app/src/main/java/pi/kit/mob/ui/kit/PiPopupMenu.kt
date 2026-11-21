package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassPopupAnchor
import top.yukonga.miuix.kmp.glass.GlassTransformPopup
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * `GlassPopup` places its panel with `placeGlassPopup`: end-aligned to the anchor, below
 * it when the panel fits under the anchor and above it otherwise, clamped into the
 * window's safe area. That edge reveal suits a contextual menu, not a control changing
 * shape. Glass icon buttons use `GlassTransformPopup`; the composer uses [PiUpwardMenu]
 * because automatic placement can put a short menu over the input field when the IME
 * raises the chip row.
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
 * Below API 33 the host's backdrop is null, and `GlassPopup` then draws its
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
        if (placement == PiPopupPlacement.Above) {
            PiUpwardMenu(show, onDismissRequest, anchor, backdrop, sizing, content)
        } else {
            val visible = show && anchor.bounds != Rect.Zero
            GlassPopup(
                show = visible,
                onDismissRequest = { if (visible) onDismissRequest() },
                anchorBounds = anchor.bounds,
                backdrop = backdrop,
                modifier = if (visible) Modifier else Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                },
                sizing = sizing,
                cornerRadius = PiCorner.popup,
                content = content,
            )
        }
    }
}

/** Matches Miuix's control-to-panel morph without composing inside the scene recording. */
@Composable
fun PiPopupMenu(
    show: () -> Boolean,
    onDismissRequest: () -> Unit,
    anchor: PiGlassMenuAnchor,
    anchorContent: @Composable () -> Unit,
    openBelow: Float = 0f,
    content: @Composable ColumnScope.() -> Unit,
) {
    RegisterPiPopup(anchor) { backdrop ->
        GlassTransformPopup(
            show = show(),
            onDismissRequest = onDismissRequest,
            anchor = anchor.glass,
            anchorContent = anchorContent,
            backdrop = backdrop,
            gap = with(LocalDensity.current) { (openBelow - anchor.top).coerceAtLeast(0f).toDp() },
            cornerRadius = PiCorner.popup,
            content = content,
        )
    }
}

@Stable
class PiGlassMenuAnchor internal constructor(internal val glass: GlassPopupAnchor) {
    internal var top by mutableStateOf(0f)
}

@Composable
fun rememberPiGlassMenuAnchor(): PiGlassMenuAnchor {
    val glass = rememberGlassPopupAnchor()
    return remember(glass) { PiGlassMenuAnchor(glass) }
}

fun Modifier.piGlassMenuAnchor(anchor: PiGlassMenuAnchor, cornerRadius: Dp = 20.dp): Modifier =
    onGloballyPositioned { anchor.top = it.boundsInWindow().top }
        .glassPopupAnchor(anchor.glass, cornerRadius)

fun Modifier.piGlassMenuAnchorContent(anchor: PiGlassMenuAnchor): Modifier =
    glassPopupAnchorContent(anchor.glass)

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
