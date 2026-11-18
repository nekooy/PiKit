package pi.kit.mob.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The app's conversations, in Miuix's own windows.
 *
 * These are *window* dialogs and sheets rather than the overlay variants, and the
 * reason is the shape of this app rather than taste: a permission explanation is
 * raised from the root before there is a page, a pi question can arrive over any
 * destination, and a sheet belongs to a page that may be disposed while it closes.
 * A window-level component does not need a `Scaffold` host to exist above it, so
 * it works in all three cases with the same call.
 *
 * The cost is the platform's dialog window — the content still inherits the
 * composition's theme, density and language, and Miuix draws its own background,
 * corner radius, dim and slide animation inside that window, so it is Miuix's
 * shape with a platform window around it.
 */

/**
 * A message with a confirm and a dismiss.
 *
 * [onDismiss] is the *cancel* answer, not the window's dismissal callback: the
 * dialogs this app raises are questions with two answers, and a question closed by
 * tapping outside has been answered "no". A caller that must not allow that
 * passes the same lambda to both, which is how a first-launch prompt stays on
 * screen until one of its buttons is used.
 */
@Composable
fun PiConfirm(
    show: Boolean,
    title: String,
    text: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = onDismiss,
    /**
     * Whether the confirm answer cannot be taken back.
     *
     * The confirm label is drawn in the scheme's error colour and the dismiss
     * label stays neutral, which is the one distinction a two-button question
     * needs: *delete* and *keep* should not look like two equivalent choices.
     */
    destructive: Boolean = false,
) {
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = text,
        onDismissRequest = onDismissRequest,
    ) {
        PiDialogAnswers(
            dismiss = dismiss,
            onDismiss = onDismiss,
            confirm = confirm,
            onConfirm = onConfirm,
            destructive = destructive,
        )
    }
}

/**
 * The answer row every dialog in the app draws.
 *
 * ## One shape, stated once
 *
 * This is the app's only dialog-button row, and it is the shape the *rebuild the
 * runtime* question established: two `TextButton`s of equal weight, 12dp apart, in the
 * library's own filled pair — so both answers are one width and one height, and the
 * destructive one is told apart by its label's colour rather than by being the only
 * button with a fill.
 *
 * The dialogs that were written before that question were not all the same. Three of
 * them passed `flatDialogAction()` — a transparent container — on the argument that a
 * dialog already holds a card of that same near-white and a filled answer beside it
 * reads as a second panel; the effect was that the app's confirmations had two different
 * weights depending on which page raised them, and the flat ones looked disabled next to
 * the filled ones on the page behind. A dialog's answers are the *only* controls on its
 * own surface, so they are the one place a fill is unambiguous.
 *
 * [confirm] is null for a dialog with a single answer — an acknowledgement has nothing
 * to be told apart from — and the dismiss answer then takes the whole row rather than
 * half of it with a gap beside it.
 */
@Composable
fun PiDialogAnswers(
    dismiss: String,
    onDismiss: () -> Unit,
    confirm: String? = null,
    onConfirm: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    /**
     * Whether the confirm answer can be used yet.
     *
     * A dialog whose question needs an answer — a rename with no name in the field — has
     * nothing to confirm, and the library's disabled fill is what says so. The dismiss
     * answer stays live in every case: a question that cannot be answered has to be
     * escapable.
     */
    confirmEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            cornerRadius = PiCorner.radius,
            text = dismiss,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
        if (confirm != null && onConfirm != null) {
            TextButton(
                cornerRadius = PiCorner.radius,
                text = confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error,
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                },
            )
        }
    }
}

/**
 * A bottom sheet, Miuix's own.
 *
 * [content] is a plain composable rather than a `ColumnScope`: Miuix's sheet wraps
 * the content in its own padding and scrolling, and a caller that wants a column
 * still composes one. Passing the scope through would have looked tidier and made
 * the sheet's own layout impossible to reason about from a call site.
 */
@Composable
fun PiSheet(
    show: Boolean,
    title: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        modifier = modifier,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            content()
        }
    }
}

/**
 * The app's ephemeral messages, drawn by the app.
 *
 * A toast is a moment: one short sentence, gone after a couple of seconds, never
 * interacted with. The platform's `Toast` was tried twice, and both times its card in
 * the system's colours with the launcher icon beside the text read as a notification
 * that had landed on the page. Miuix's snackbar was also tried; it is a full-width bar
 * pinned to the bottom edge, which is the wrong shape for a remark that belongs beside
 * the composer. The message is therefore the app's own glass capsule: the same fill as
 * the bar's capsules, above the composer rather than over it, and read as one floating
 * object among the page's other floating objects.
 *
 * The host is remembered once at the root, above every page, so the message survives
 * the page that raised it. A second message takes the slot rather than queueing behind
 * the first — an ephemeral message that arrives five seconds late is about a state the
 * reader has already left.
 */
@Stable
class PiToast internal constructor() {

    /** The message on screen, with a token so a second one restarts the timer. */
    internal val shown = MutableStateFlow<Message?>(null)

    /**
     * The measured height of the chat composer's band, including its bottom inset.
     *
     * The toast is anchored just above the band rather than at a guessed fixed height:
     * the band grows by the keyboard's inset, so a toast raised while the keyboard is
     * up would otherwise be behind it. `0` means no chat page is on screen (or none has
     * measured yet) and the provider falls back to [TOAST_WINDOW_BOTTOM] plus the
     * window's own bottom inset.
     */
    internal var anchorPx by mutableFloatStateOf(0f)

    internal data class Message(val text: String, val token: Long)

    /**
     * One message, now.
     *
     * A blank message is dropped — a capsule with no text is a rectangle — and a second
     * one replaces the first rather than queueing behind it: an ephemeral message that
     * arrives five seconds late is about a state the reader has already left.
     */
    fun show(message: String) {
        if (message.isBlank()) return
        shown.value = Message(message, System.nanoTime())
    }

    /** The chat page's composer band, refreshed on every band measurement. */
    internal fun setAnchor(heightPx: Float) {
        anchorPx = heightPx.coerceAtLeast(0f)
    }
}

/** The toast, provided once at the root. A page that has no provider gets none. */
val LocalPiToast = staticCompositionLocalOf<PiToast?> { null }

/**
 * Provides a [PiToast] to everything below and draws the capsule itself.
 *
 * The message is composed after the content, so it is the last thing drawn; it takes no
 * touches, so the page under it stays usable while it is up. The bottom offset is read
 * in the layout phase rather than in composition: the chat band changes with the
 * keyboard on every animation frame, and a recomposition of the whole page for that is
 * exactly the cost `bandBottom` was written to avoid.
 */
@Composable
fun PiToastProvider(content: @Composable () -> Unit) {
    val toast = remember { PiToast() }
    val current by toast.shown.collectAsState()
    val ime = WindowInsets.ime
    val bars = WindowInsets.navigationBars
    // The root of the app is also the window Miuix places its popups in, and the
    // platform's answer for that is not refreshed by every window-size change: see
    // [MeasuredWindowInfo]. The size is taken from this box's own layout, which is
    // written by the same measure pass that a resize ends in.
    val measuredWindow = remember { mutableStateOf(IntSize.Zero) }
    val platformWindow = LocalWindowInfo.current
    val density = LocalDensity.current
    val window = remember(platformWindow, density.density, density.fontScale) {
        MeasuredWindowInfo(platformWindow, measuredWindow, density)
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { measuredWindow.value = it },
    ) {
        CompositionLocalProvider(
            LocalPiToast provides toast,
            LocalWindowInfo provides window,
            content = content,
        )

        val message = current
        // One timer per message: the token is in the key, so a second message restarts
        // the countdown instead of inheriting the rest of the first one's.
        LaunchedEffect(message?.token) {
            if (message != null) {
                delay(TOAST_MILLIS)
                toast.shown.value = null
            }
        }

        AnimatedVisibility(
            visible = message != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset {
                    val windowInset = maxOf(
                        ime.getBottom(this),
                        bars.getBottom(this),
                    )
                    val bottom = if (toast.anchorPx > 0f) {
                        toast.anchorPx + TOAST_BAND_GAP.toPx()
                    } else {
                        windowInset + TOAST_WINDOW_BOTTOM.toPx()
                    }
                    IntOffset(0, -bottom.roundToInt())
                }
                .padding(horizontal = 32.dp),
            enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(140)),
            label = "toast",
        ) {
            // The text comes from the last non-null message so the capsule can draw
            // while it leaves.
            val last = rememberLastMessage(message)
            Box(
                Modifier
                    .widthIn(max = TOAST_MAX_WIDTH)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(PiGlass.fill())
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = last,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The message to draw, kept through the exit animation so the capsule is not empty. */
@Composable
private fun rememberLastMessage(message: PiToast.Message?): String {
    var last by remember { mutableStateOf("") }
    if (message != null) last = message.text
    return last
}

/** How long a message stays up: long enough to read a sentence, short enough to ignore. */
private const val TOAST_MILLIS = 2_600L

/** The gap between the composer's band and the capsule above it. */
private val TOAST_BAND_GAP = 12.dp

/** Where the capsule sits on a page without a composer: above the window's bottom inset. */
private val TOAST_WINDOW_BOTTOM = 48.dp

/** A sentence wraps rather than running to the window's edges; this is where it stops. */
private val TOAST_MAX_WIDTH = 420.dp

/**
 * The navigation strip's height, for a sheet's own bottom edge.
 *
 * Miuix's bottom sheet is a window of its own with `decorFitsSystemWindows = false`, and
 * its inside margin is 24dp across and **zero** down — the panel's bottom edge *is* the
 * window's, which is the gesture bar. So a sheet's last row would sit under the gesture
 * bar unless the content clears it, and the inset belongs to the content rather than to
 * the panel: on a scrolling body it goes inside the scroll (after `verticalScroll`), so
 * the text travels through it and the panel's own fill still reaches the window's edge.
 *
 * One function rather than the same three lines in every sheet of the app.
 */
@Composable
fun piSheetBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * The window, as the composition has measured it rather than as the platform reports it.
 *
 * ## What reads it
 *
 * Miuix places every anchored popup inside the *window's* rectangle, and it takes that
 * rectangle from `LocalWindowInfo` (`rememberListPopupLayoutInfo` remembers a `windowBounds`
 * keyed on `WindowInfo.containerSize`): the popup position provider is asked to keep the
 * panel inside it, and a popup without an explicit `maxHeight` is measured against its
 * height. The composer's own provider (§7.1) receives the same rectangle as its
 * `windowBounds`, and the composer's menus, the transcript's card menu, the drawer's row
 * menu and the files page's menu are all placed by one of those two providers.
 *
 * ## Why the platform's answer is not enough
 *
 * `WindowInfo.containerSize` is a lazily-initialised state that Compose refreshes **only**
 * from `AndroidComposeView.onConfigurationChanged` — `ComposeViewContext.onConfigurationChanged`
 * calls `LazyWindowInfo.updateContainerSizeIfObserved`, and nothing else does. A window
 * that changed size without a configuration change the activity sees therefore keeps
 * handing out the size it had when the popup last composed, and every popup that opens
 * afterwards is clamped into the old rectangle: the panel is dragged up off its anchor by
 * the height the window has gained, which reads as "the menu opens far too high" after a
 * small window returns to fullscreen (§7.1's *The anchored half*).
 *
 * The size the root layout gave the app cannot be stale that way, because the measure
 * pass that a resize ends in is what writes it: `onSizeChanged` on the root box fires
 * *after* the box has been given the new size, and every consumer of
 * `containerSize` reads a snapshot state, so a popup that is already composed
 * re-composes with the new rectangle, and one that opens later reads it directly.
 *
 * Only the two size facts are replaced. Focus and the keyboard modifiers are the
 * platform's, and the px size falls back to the platform's until the root has been
 * measured once (which also keeps the first frame of a window that has never been laid
 * out from claiming a window of zero).
 */
private class MeasuredWindowInfo(
    private val platform: WindowInfo,
    private val measured: State<IntSize>,
    private val density: Density,
) : WindowInfo {

    override val isWindowFocused: Boolean get() = platform.isWindowFocused

    override val containerSize: IntSize
        get() = measured.value.takeIf { it != IntSize.Zero } ?: platform.containerSize

    override val containerDpSize: DpSize
        get() = DpSize(
            (containerSize.width / density.density).dp,
            (containerSize.height / density.density).dp,
        )

    override val keyboardModifiers get() = platform.keyboardModifiers
}
