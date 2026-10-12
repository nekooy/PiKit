/*
 * The app's one modal layer.
 *
 * Every sheet in PiKit used to be a Material3 `ModalBottomSheet`, which is not a
 * view in the page: it is a *second window* (`ModalBottomSheetDialogWrapper`
 * extends `ComponentDialog`) laid over the activity. That is why it could not be
 * made to behave, and it failed in two ways that no amount of tuning at the call
 * site could fix:
 *
 *  - **It stole the keyboard.** A dialog window is focusable, so the moment it
 *    appeared the IME lost its connection and closed itself — measured on the
 *    emulator: `mImeWindowVis=3 mInputShown=true` before tapping a composer chip,
 *    `mImeWindowVis=0 mInputShown=false` 250 ms after. The app's own page then
 *    re-measured under the *sheet's* entrance animation (the composer's chips
 *    moved 610 px down the screen, from y=1153 to y=1763), and the sheet itself
 *    was laid out against an IME inset that was collapsing underneath it, so it
 *    arrived somewhere else than it settled. The user's description — "it forces
 *    the keyboard closed and everything jumps around" — is exactly these three
 *    animations disagreeing.
 *  - **It swallowed taps after closing.** The window outlives the state that
 *    asked for it: with the sheet dismissed, the composition cleared and the
 *    dialog window still on top, taps land on the dying window and do nothing.
 *    Measured by counting `pi.kit.mob` windows with `dumpsys window` after a
 *    dismiss-then-tap pair at increasing gaps: the tap was lost at 150, 300 and
 *    500 ms and only landed at 800 ms, i.e. a dead band of roughly 0.6-0.9 s.
 *
 * A sheet in this file is a `Box` in the page's own window instead. There is no
 * second window, so there is nothing to steal focus, nothing to re-measure, and
 * nothing to outlive the state: dismissing is a snapshot write like any other, and
 * the only thing left between the dismissal and the next tap is the exit animation
 * the user is watching. Measured the same way: the tap is lost at a gap of
 * 0.00-0.10 s — it lands on the panel, which is still over that spot — and lands
 * from 0.15 s on, where the old window was still eating it at 0.50 s.
 *
 * Three consequences are deliberate:
 *
 *  - **The keyboard stays up.** A sheet opened while typing appears *above* the
 *    keyboard rather than closing it, so the field keeps focus and the page
 *    never re-lays itself out. That is the whole point; a picker that closes the
 *    keyboard to ask a question about the text being typed is asking it in the
 *    wrong place.
 *  - **A leaving sheet stops listening.** The panel's drag and every row's tap are
 *    installed only while the host says the sheet is open, so the ~250 ms of exit
 *    cannot run an action the user aimed at the page behind it. A pointer modifier
 *    that is merely *disabled* is not enough, and that is worth knowing: it still
 *    claims the hit test, so a row on its way off screen would eat the tap instead
 *    of letting it through.
 *  - **The scrim is gone the moment the sheet is.** It is drawn for the length of
 *    the exit but takes no input, which is Material3's own behaviour and the
 *    opposite of a screen that stays dead until an animation is over.
 */
package pi.kit.mob.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pi.kit.mob.ui.design.PiShapes
import kotlin.math.roundToInt

/**
 * One sheet, as the root draws it: a key that says *which* sheet this is, and the
 * body to draw inside the panel.
 *
 * The body is a composable and not a data structure because one sheet in the app
 * is not a list — the file preview has a scrolling monospace body, a spinner
 * while it loads and two buttons under it. Everything else is a [PickerBody] or a
 * `ReadOnlySheet`, so the shared shape is still shared; this is only the escape
 * hatch that keeps the file preview from having to be re-expressed as rows.
 *
 * The body is built by the caller *at the moment it opens the sheet*, not on
 * every recomposition, so a body that must follow live data has to read that data
 * itself — from a `StateFlow` inside the lambda rather than from a captured
 * value. `ChatScreen`'s context sheet does exactly that; a body that captured the
 * conversation would freeze its numbers on the frame the chip was tapped.
 */
@Immutable
class Sheet(
    /**
     * Identity. Showing a sheet with a key that is already on screen is a no-op,
     * so a sheet that is re-requested by a recomposition does not restart its own
     * entrance animation.
     */
    val key: String,
    /**
     * Where the panel goes. Null docks it to the window's bottom edge — the bottom
     * sheet. A window-relative rect floats it over the thing that opened it: the
     * anchored menu, whose panel, placement and fade belong to `Menus.kt`.
     */
    val anchor: IntRect? = null,
    /**
     * Whether an anchored panel aligns its *end* to the anchor's end instead of
     * its start to the anchor's start.
     *
     * A menu at a chip or a toolbar button starts where the control starts. A
     * menu of row actions wants the other edge: a row spans the page, its actions
     * have always lived at its trailing edge (`⋮` was there), and a menu at the
     * row's start is a menu at the screen's left margin (`历史消息页弹窗操作菜单
     * 显示要靠右而不是靠左`). The panel's scale grows out of whichever edge it is
     * aligned by, so the menu also *appears* from that edge.
     */
    val alignEnd: Boolean = false,
    /**
     * Whether the body runs to the panel's bottom edge and applies the
     * navigation-bar inset itself, as its scrollable list's content padding.
     *
     * The layer pads every body by the navigation bar's height, which is what
     * keeps a form's buttons clear of the gesture bar — and what stands between a
     * long list's text and the panel's bottom edge: the text stops at the padding,
     * the padding shows the panel's own fill, and the reader sees a bar of empty
     * panel under the last visible line for as long as the list scrolls (the
     * report "滑动时文字不会直接触底而是会被底下一个白色条块遮挡"). A body that
     * scrolls declares so, the layer hands it the inset through
     * [LocalSheetBottomInset] instead of padding it, and the list scrolls flush to
     * the window's bottom edge with the inset inside the scroll — so the last
     * *item* still clears the bar, but the text travels the full height.
     */
    val fullBleedBottom: Boolean = false,
    val content: @Composable () -> Unit,
)

/**
 * Holds the one sheet the app is allowed to have open.
 *
 * A single slot rather than a list or a stack: every sheet in PiKit is modal, so
 * two of them can never be legitimate at once, and a state that cannot represent
 * "two sheets open" is one fewer way to draw a scrim on top of a scrim.
 *
 * The state lives above the root's language key on purpose — a language switch
 * rebuilds every screen under it, and the sheet it was switched *from* must still
 * be able to finish sliding out.
 */
@Stable
class SheetHost {

    /** What the app has asked for, or null when nothing is open. */
    internal var requested by mutableStateOf<Sheet?>(null)
        private set

    /**
     * What the layer is drawing: [requested] while it is open, and the *last*
     * sheet while it slides away.
     *
     * The panel cannot be unmounted the instant it is dismissed — it would vanish
     * rather than leave — so the sheet that is on its way out is held here for the
     * length of the exit. This is what a `ModalBottomSheet`'s own window was doing
     * in the old design, with the difference that it is state in the page rather
     * than a window above it.
     */
    internal var displayed by mutableStateOf<Sheet?>(null)
        private set

    private val slide = Animatable(0f)

    /** 0 = off screen, 1 = fully in. Drives both the offset and the scrim. */
    internal val progress: Float get() = slide.value

    /**
     * Whether a sheet is open *and* still accepting input.
     *
     * False during the exit, which is what every row in a sheet consults before
     * acting: two taps on two rows of a closing picker must not both land, and the
     * answer to "is this still the sheet you are looking at" is this flag.
     */
    val isOpen: Boolean get() = requested != null

    /** Opens [sheet], replacing any sheet already on screen. */
    fun show(sheet: Sheet) {
        if (requested?.key == sheet.key) return
        requested = sheet
    }

    /** Starts the exit. Safe to call twice; the second call does nothing. */
    fun dismiss() {
        requested = null
    }

    internal fun retain(sheet: Sheet?) {
        displayed = sheet
    }

    /**
     * Slides the panel to [target], on the clock the sheet kind belongs to.
     *
     * A docked sheet takes [SHEET_MILLIS] — the same 250 ms as the platform's
     * sub-page transition, so a sheet and a page move at the same speed when one
     * follows the other. An anchored menu is a dropdown, and a dropdown answers a
     * tap at dropdown speed: [MENU_MILLIS], the 125 ms the platform's own
     * `DropdownMenu` spends on its fade-and-scale. The report behind the split is
     * "点击到出现都不够迅速且不流畅丝滑" — 250 ms of fade was the *rapid* half of
     * that complaint, and the smooth half is fixed in `Menus.kt` by placing the
     * panel in one measure pass.
     */
    internal suspend fun slideTo(target: Float, millis: Int = SHEET_MILLIS) {
        slide.animateTo(target, tween(durationMillis = millis, easing = FastOutSlowInEasing))
    }
}

/**
 * The host, from anywhere inside the app.
 *
 * A `staticCompositionLocalOf` rather than `compositionLocalOf`: the value is one
 * object that never changes, and a static local does not subscribe its readers to
 * anything — the recomposition a sheet needs comes from the snapshot state inside
 * the host (which is read through [SheetHost.isOpen]), not from the local.
 */
val LocalSheetHost = staticCompositionLocalOf<SheetHost> {
    error("No SheetHost: sheets are drawn by the root, which provides one")
}

/**
 * A page's own back action, which yields to any sheet above it.
 *
 * The modal layer registers its handler at the root, and the pages are composed
 * *inside* a transition. `Crossfade` and `AnimatedContent` subcompose their
 * content during the layout pass, so a page's `BackHandler` reaches the
 * dispatcher **after** the layer's one, and the dispatcher runs the most recently
 * added enabled callback first. A page that was merely `enabled` therefore won the
 * back gesture against the sheet drawn over it: the sheet stayed open and the page
 * went back underneath — the report that "back on the thinking-level picker did
 * not close the picker, it left the model page".
 *
 * Ordering the two handlers correctly would mean depending on the order two
 * different composition passes happen to run in. The rule is written down here
 * instead: a page consumes back only while nothing is open above it.
 */
@Composable
fun PageBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val host = LocalSheetHost.current
    BackHandler(enabled = enabled && !host.isOpen) { onBack() }
}

/**
 * The modal layer itself. Goes in the root's window-filling `Box`, after the tab
 * strip, so a sheet covers the whole window including that strip.
 *
 * [modifier] is expected to fill the window. The layer draws nothing at all while
 * no sheet is displayed, so it costs a `LaunchedEffect` and one null check on
 * every other frame of the app's life.
 */
@Composable
fun SheetLayer(host: SheetHost, modifier: Modifier = Modifier) {
    val requested = host.requested
    val sheet = host.displayed
    val scope = rememberCoroutineScope()

    // The finger's own offset, in px, added on top of the animation's. Reset once
    // the panel is off screen and out of composition, so the next sheet does not
    // inherit a drag it never had.
    var drag by remember { mutableFloatStateOf(0f) }
    var panelHeight by remember { mutableFloatStateOf(0f) }

    // One animation for both directions, keyed on the request so that a second
    // sheet asked for mid-exit takes the panel over from wherever it currently is
    // instead of the two animations fighting over the same offset.
    LaunchedEffect(requested?.key) {
        val target = requested
        if (target != null) {
            host.retain(target)
            // A sheet asked for while the last one is still being thrown away
            // inherits the finger's offset. It unwinds at the same rate as the
            // entrance instead of being dropped, because dropping it moves the
            // panel by the whole fling in one frame — and leaving it would park
            // the new sheet that far below the window's edge for as long as it is
            // open.
            if (drag != 0f) {
                launch {
                    animate(
                        initialValue = drag,
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = SHEET_MILLIS),
                    ) { value, _ -> drag = value }
                }
            }
            host.slideTo(1f, panelMillis(target))
        } else {
            // `displayed` is the panel on its way out; it is what says whether the
            // leaving thing is a menu or a sheet, so the exit runs on the same
            // clock the entrance did.
            host.slideTo(0f, panelMillis(host.displayed))
            drag = 0f
            host.retain(null)
        }
    }

    // Back closes the sheet before it reaches any page. Registered here, after
    // every page's own handler, so it is the innermost thing on the back stack —
    // which is what a modal is.
    BackHandler(enabled = requested != null) { host.dismiss() }

    if (sheet == null) return

    // A sheet that has been dismissed keeps drawing until it is off screen, but
    // stops taking input: every pointer modifier below is layered on `live`.
    val live = requested != null
    // An anchored menu is drawn by its own half of the layer (`Menus.kt`), and the
    // two differ in more than position: a menu fades rather than slides, carries
    // no drag, and dims nothing — a dropdown over the page it came from is not a
    // screen the reader has to be told is modal.
    val anchored = sheet.anchor != null && sheet.anchor != IntRect.Zero

    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)

    // The panel's own bottom inset. With the keyboard up the panel is already
    // lifted clear of the gesture bar — the IME's inset includes it — so adding
    // the navigation bar's on top would leave a gesture bar's worth of dead space
    // above the keyboard.
    val contentBottom = with(density) { (if (imeBottom > 0) 0 else navBottom).toDp() }

    // Where a drag lands once the finger lets go: past the threshold the sheet
    // leaves, otherwise it goes back. Shared by the two ways a drag can end —
    // the panel's own `draggable`, and the nested scroll that picks up a pull the
    // list inside the sheet could not use.
    fun settleDrag(velocity: Float) {
        val thrown = velocity > FLING_VELOCITY
        val pulled = drag > panelHeight * DRAG_DISMISS_FRACTION
        if (thrown || pulled) {
            host.dismiss()
        } else {
            // Not far enough: put it back. A spring, because the panel is
            // returning to a position rather than playing a transition, and a
            // tween here reads as the sheet lagging behind the finger it just
            // followed.
            scope.launch {
                animate(
                    initialValue = drag,
                    targetValue = 0f,
                    animationSpec = spring(),
                ) { value, _ -> drag = value }
            }
        }
    }

    // A downward pull that the sheet's own list cannot use — the top of a list
    // that is already at its top — drags the sheet.
    //
    // Without this the only draggable part of a sheet is its header, because the
    // list claims the gesture first: a scrollable child consumes the drag before
    // any `draggable` on its parent sees it, and a nested scroll connection is the
    // only way back up. Material3's sheet carries the same connection for the same
    // reason.
    val overscroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                // Downwards only, for the same reason the panel's own drag is.
                drag = (drag + available.y).coerceAtLeast(0f)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (drag > 0f) settleDrag(available.y)
                return Velocity.Zero
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        // The catcher between the modal and the page. A sheet dims; a menu does
        // not, and keeps only the tap. `detectTapGestures` rather than `clickable`:
        // a scrim is not a control, and a ripple on a full-screen dim layer reads
        // as a flash.
        //
        // The fade is applied in `graphicsLayer` and reads [SheetHost.progress] at
        // *draw* time. Reading it in composition — which this layer used to do —
        // recomposes the whole modal layer on every frame of every entrance and
        // exit, and that per-frame recomposition is half of what made a menu feel
        // sluggish under a tap.
        //
        // `graphicsLayer` comes **before** `background`, and that order is
        // load-bearing: a modifier wraps the ones after it in the chain, so a
        // layer's alpha reaches a background placed inside it and cannot reach one
        // placed outside. The first version had the two the other way round, which
        // drew the scrim at full strength in the parent's layer whatever the alpha
        // said — every menu and every sheet opened over a black screen
        // (`点击按钮弹出悬浮菜单后，后面的内容全部黑屏了`).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (anchored) 0f else SCRIM_ALPHA * host.progress
                }
                .background(MaterialTheme.colorScheme.scrim)
                .then(
                    if (live) {
                        Modifier.pointerInput(host) { detectTapGestures { host.dismiss() } }
                    } else {
                        Modifier
                    },
                ),
        )

        if (anchored) {
            AnchoredMenu(sheet = sheet, host = host)
            return@Box
        }

        Box(
            Modifier
                .fillMaxSize()
                // Lifts the panel clear of the keyboard. The catcher above is
                // deliberately *not* lifted: it covers the window, and the strip
                // behind the keyboard is a strip nobody can see.
                .windowInsetsPadding(WindowInsets.ime.only(WindowInsetsSides.Bottom)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // The panel's ceiling, and the reason it is here rather than in a body.
            //
            // A panel taller than the space between the keyboard and the status bar
            // used to be laid out from the window's bottom edge with nothing stopping
            // it, so its top — the grabber and the sheet's title — was drawn *over* the
            // status bar's clock and battery. The measured case is the AGENTS.md
            // editor: a 240-440dp field with two buttons under it, and a keyboard that
            // takes 300dp of a 411x891dp window.
            //
            // The bound is the height this box has (the window, less the keyboard,
            // because the inset above is consumed on the way in) minus the status bar,
            // which is what keeps the panel's top edge below the clock. Every body
            // under it is measured against the ceiling, so a body that can scroll
            // scrolls and a body that cannot is at least never over the system bars —
            // and `PiSheetList`'s own 0.7-of-the-panel cap follows the new ceiling
            // because it is a share of what it is given.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val statusTop = with(density) {
                    WindowInsets.statusBars.getTop(density).toDp()
                }
                val ceiling = with(density) { constraints.maxHeight.toDp() } - statusTop

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    // The panel's corners are the app's sheet shape, which is `PiShapes`'s
                    // 32dp pair and square at the bottom: the bottom two corners sit on
                    // the window's own edge, so rounding them would let the scrim show
                    // through underneath.
                    shape = PiShapes.sheetTop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = SHEET_MAX_WIDTH)
                        .heightIn(max = ceiling)
                        .onSizeChanged { panelHeight = it.height.toFloat() }
                        // `graphicsLayer` rather than `offset`: the translation is a
                        // property of the sheet's *own* height, which is only known
                        // after it has been measured, and a `graphicsLayer` lambda is
                        // read at draw time — an `offset` modifier built from state
                        // measured in the same frame would draw one frame of the panel
                        // sitting at its final position before it started moving.
                        //
                        // ## Why the translation is rounded to whole pixels
                        //
                        // This is a text-rendering fix, not a taste for round numbers.
                        // `drag` is the finger's own delta and `progress` is a tween, so
                        // while the panel moves its translation is almost never an
                        // integer — and Android rasterises text inside a RenderNode that
                        // sits at a fractional offset with grayscale antialiasing rather
                        // than subpixel (LCD) antialiasing, because LCD text has to be
                        // aligned to the pixel grid. Every glyph in the sheet therefore
                        // changes weight and position slightly from frame to frame while
                        // the panel is dragged, and snaps back to what it was the moment
                        // the drag stops. That is the report "拖动时和停止状态切换时感觉
                        // 文字会闪烁或者变糊", and this panel is the only place in the app
                        // that translates text at a fractional offset — the transcript's
                        // list places its rows at whole-pixel offsets (`LazyListState`
                        // rounds its scroll), so nothing there needs the same treatment.
                        //
                        // Rounded *here* rather than where `drag` accumulates, and that
                        // is what keeps the accumulation exact: a drag that moves half a
                        // pixel per frame still travels, instead of dropping the
                        // remainder on every frame and stalling against the finger.
                        .graphicsLayer {
                            translationY = ((1f - host.progress) * size.height + drag)
                                .roundToInt()
                                .toFloat()
                        }
                        .then(
                            if (live) {
                                Modifier
                                    .nestedScroll(overscroll)
                                    .draggable(
                                        orientation = Orientation.Vertical,
                                        state = rememberDraggableState { delta ->
                                            // Downwards only: a sheet that can be
                                            // dragged up past its own top edge is a
                                            // sheet that detaches from the edge it is
                                            // docked to.
                                            drag = (drag + delta).coerceAtLeast(0f)
                                        },
                                        onDragStopped = { velocity -> settleDrag(velocity) },
                                    )
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        SheetGrabber()
                        if (sheet.fullBleedBottom) {
                            // The body scrolls to the panel's bottom edge and
                            // puts the navigation bar's height inside its own
                            // scroll, as content padding — see [Sheet]'s
                            // `fullBleedBottom` for the report that moved the
                            // inset out of the layout.
                            CompositionLocalProvider(LocalSheetBottomInset provides contentBottom) {
                                Box { sheet.content() }
                            }
                        } else {
                            Box(Modifier.padding(bottom = contentBottom)) {
                                sheet.content()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The grabber on the panel's top edge.
 *
 * 32dp by 4dp, the size of Material3's own docked handle
 * (`SheetBottomTokens.DockedDragHandleWidth` / `DockedDragHandleHeight`). Drawn
 * here rather than by `BottomSheetDefaults.DragHandle()` because that one adds its
 * own 22dp of vertical padding, and this panel's distance from the grabber to the
 * sheet's title is one number next to the title's own padding.
 *
 * `onSurfaceVariant` at 0.4 rather than at full strength: at full strength a 4dp
 * bar on the near-white sheet reads as a scratch, and the grabber is an
 * affordance, not content. Its shape is `PiShapes.pill` — the app's action voice,
 * and the shape a 4dp bar would take anyway; naming it keeps the one place that
 * decides what "round" means.
 */
@Composable
private fun SheetGrabber() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp, bottom = 2.dp)
                .size(width = GRABBER_WIDTH, height = GRABBER_HEIGHT)
                .clip(PiShapes.pill)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = GRABBER_ALPHA)),
        )
    }
}

/**
 * Material3's own ceiling for a docked sheet (`BottomSheetDefaults.SheetMaxWidth`).
 * Only reachable on a tablet; on a phone the panel is the window's width.
 */
private val SHEET_MAX_WIDTH = 640.dp

/**
 * 250 ms, the same clock the platform's own sub-page transition runs on, so a
 * sheet and a page move at the same speed when one follows the other.
 */
private const val SHEET_MILLIS = 250

/**
 * 125 ms, the platform `DropdownMenu`'s own fade-and-scale clock, for the
 * anchored half. A dropdown answers a tap at dropdown speed; a sheet's 250 ms
 * reads as a pause when the panel is appearing *at* the control that was tapped.
 */
private const val MENU_MILLIS = 125

/** The clock one panel moves on: a menu at dropdown speed, a sheet at page speed. */
private fun panelMillis(sheet: Sheet?): Int =
    if (sheet?.anchor != null && sheet.anchor != IntRect.Zero) MENU_MILLIS else SHEET_MILLIS

/**
 * Material3's scrim opacity (`ScrimTokens.ContainerOpacity`), applied to
 * `colorScheme.scrim` — which is what `BottomSheetDefaults.ScrimColor` resolves to.
 */
private const val SCRIM_ALPHA = 0.32f

/**
 * A drag past this share of the panel is a dismissal even if the finger stopped
 * moving: half the panel is Material3's own positional threshold for a docked
 * sheet, and it is also the point past which carrying on looks like a mistake.
 */
private const val DRAG_DISMISS_FRACTION = 0.5f

/**
 * 1000 px/s, measured against this device's own scale (420 dpi, so about
 * 240 dp/s). Past this the finger is throwing the sheet rather than placing it,
 * and waiting for it to travel half the panel before honouring the gesture reads
 * as the sheet sticking.
 */
private const val FLING_VELOCITY = 1000f

/**
 * The bottom inset a full-bleed sheet body must apply itself, as the scrollable
 * list's content padding.
 *
 * `0dp` — the default, and the value everywhere except under a
 * [Sheet.fullBleedBottom] panel — means the layer has already padded the body and
 * the body owes nothing. Bodies that are the same list either way read this
 * rather than branching on the sheet's flag: `Forms.kt`'s picker and read-only
 * bodies add it to their list's content padding, which is what lets the text of a
 * long list travel to the window's bottom edge while its last item still clears
 * the gesture bar.
 */
val LocalSheetBottomInset = staticCompositionLocalOf { 0.dp }

private val GRABBER_WIDTH = 32.dp

private val GRABBER_HEIGHT = 4.dp

private const val GRABBER_ALPHA = 0.4f
