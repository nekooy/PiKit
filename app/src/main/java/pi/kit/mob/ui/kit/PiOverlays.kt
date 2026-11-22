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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The app's dialogs and sheets: OS4 glass where a page is behind them, Miuix windows
 * where there is no page.
 *
 * A dialog raised from a destination has a `LocalPiBackdrop` and is drawn as Miuix's
 * `GlassDialog`/`GlassBottomSheet`, so its material samples the page behind it and it
 * belongs to the same surface as the bar. The first-launch permission prompts are
 * raised from the root before any page exists and a pi question can arrive over any
 * destination; those have no backdrop to sample and keep the window variants, which
 * need no `Scaffold` host. The window path's cost is the platform's dialog window
 * around Miuix content — the content still inherits the theme, density and language,
 * and Miuix draws its own background, corner radius, dim and animation inside it.
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
    // A page that carries a backdrop gets OS4's glass panel; the first-launch prompts
    // run above every page and therefore above every backdrop, and keep the window
    // dialog that works without a Scaffold. The two paths share the answers row, so a
    // question looks the same wherever it is raised.
    val backdrop = LocalPiBackdrop.current
    if (backdrop != null) {
        GlassDialog(
            show = show,
            backdrop = backdrop,
            modifier = modifier,
            title = title,
            summary = text,
            onDismissRequest = { onDismissRequest?.invoke() },
        ) {
            PiDialogAnswers(
                dismiss = dismiss,
                onDismiss = onDismiss,
                confirm = confirm,
                onConfirm = onConfirm,
                destructive = destructive,
                glass = true,
            )
        }
    } else {
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
}

/**
 * A custom-content dialog with the same material split as [PiConfirm].
 *
 * A page that has a backdrop gets Miuix's [GlassDialog]; a caller above every page —
 * the first-launch prompts and pi's questions — keeps [WindowDialog], which needs no
 * `Scaffold` host. Screens with a body that is not a sentence (a language list, an
 * avatar crop, a workspace picker, a failure body) use this rather than writing the
 * split at each call site: the direct [GlassDialog] call is checked to live only in
 * this file, and a call site that reaches for `WindowDialog` while a page backdrop
 * exists is the opaque-window bug the split exists to avoid.
 *
 * `insideMargin` is shared by both branches because a dialog whose content is full
 * bleed needs the same answer from both materials; the default is Miuix's own.
 */
@Composable
fun PiCustomDialog(
    show: Boolean,
    title: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalPiBackdrop.current
    if (backdrop != null) {
        GlassDialog(
            show = show,
            backdrop = backdrop,
            modifier = modifier,
            title = title,
            summary = summary,
            insideMargin = insideMargin,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
        ) {
            content()
        }
    } else {
        WindowDialog(
            show = show,
            modifier = modifier,
            title = title,
            summary = summary,
            insideMargin = insideMargin,
            onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished,
        ) {
            content()
        }
    }
}

/**
 * The answer row every dialog in the app draws.
 *
 * ## One shape, stated once
 *
 * This is the app's only dialog-button row, and it is the shape the *rebuild the
 * runtime* question established: two equal-weight answers 12dp apart, one width and one
 * height. A glass dialog draws them as Miuix's `GlassDialogButton`s (normal, primary or
 * destructive role); the window fallback keeps the filled `TextButton` pair. Both paths
 * state the same fills: dismiss is a 15% wash of the surface ink, primary is the scheme's
 * primary, and a destructive confirm is the scheme's error — a colour distinction that
 * survives on an opaque window as well as on glass.
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
     * nothing to confirm, and the filled disabled answer is what says so. The dismiss
     * answer stays live in every case: a question that cannot be answered has to be
     * escapable.
     */
    confirmEnabled: Boolean = true,
    /** Whether the answers are drawn on Miuix's glass button row. */
    glass: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glass) {
            GlassDialogButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Normal,
                cornerRadius = PiCorner.radius,
                // The library's `Normal` role is a 6% wash of the ink: on a glass
                // dialog it is a rectangle the same colour as the panel, so the
                // dismiss answer reads as a word with no button around it. The
                // dialog panel's glass body sits around rgb(223) in the light
                // scheme, and `secondaryVariant` (#E6E6E6, rgb(230)) was *lighter*
                // than that panel — the measured cause of the "看不清" pair. A 15%
                // wash of the surface ink lands at rgb(190) on the same panel, a
                // legible grey step in both schemes, while the label keeps the
                // scheme's onSurface ink. The disabled state keeps that rectangle
                // and only fades the ink, so an empty rename field still has a
                // visible answer.
                colors = ButtonDefaults.buttonColors(
                    color = PiTone.control(),
                    disabledColor = PiTone.control(),
                    contentColor = MiuixTheme.colorScheme.onSurface,
                    disabledContentColor = MiuixTheme.colorScheme.onSurface.copy(
                        alpha = DIALOG_DISABLED_INK_ALPHA,
                    ),
                ),
            ) {
                Text(text = dismiss, style = MiuixTheme.textStyles.button)
            }
        } else {
            TextButton(
                cornerRadius = PiCorner.radius,
                text = dismiss,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(
                    color = PiTone.control(),
                    disabledColor = PiTone.control(),
                    textColor = MiuixTheme.colorScheme.onSurface,
                    disabledTextColor = MiuixTheme.colorScheme.onSurface.copy(
                        alpha = DIALOG_DISABLED_INK_ALPHA,
                    ),
                ),
            )
        }
        if (confirm != null && onConfirm != null) {
            if (glass) {
                GlassDialogButton(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = confirmEnabled,
                    role = if (destructive) {
                        GlassDialogButtonRole.Destructive
                    } else {
                        GlassDialogButtonRole.Primary
                    },
                    cornerRadius = PiCorner.radius,
                    // A primary answer stays the scheme's primary fill with white
                    // ink; a destructive one is the error fill with its own
                    // knockout, rather than the library's error-ink-on-a-translucent
                    // wash, which left the word floating on the glass. Both keep a
                    // filled rectangle on the disabled state (the same 15% ink wash
                    // as dismiss), so an empty rename field still shows a button
                    // whose label fades instead of a pale primary rectangle.
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.error,
                            disabledColor = PiTone.control(),
                            contentColor = MiuixTheme.colorScheme.onError,
                            disabledContentColor = MiuixTheme.colorScheme.onSurface.copy(
                                alpha = DIALOG_DISABLED_INK_ALPHA,
                            ),
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            color = MiuixTheme.colorScheme.primary,
                            disabledColor = PiTone.control(),
                            contentColor = MiuixTheme.colorScheme.onPrimary,
                            disabledContentColor = MiuixTheme.colorScheme.onSurface.copy(
                                alpha = DIALOG_DISABLED_INK_ALPHA,
                            ),
                        )
                    },
                ) {
                    Text(text = confirm, style = MiuixTheme.textStyles.button)
                }
            } else {
                TextButton(
                    cornerRadius = PiCorner.radius,
                    text = confirm,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = confirmEnabled,
                    colors = if (destructive) {
                        ButtonDefaults.textButtonColors(
                            color = MiuixTheme.colorScheme.error,
                            disabledColor = PiTone.control(),
                            textColor = MiuixTheme.colorScheme.onError,
                            disabledTextColor = MiuixTheme.colorScheme.onSurface.copy(
                                alpha = DIALOG_DISABLED_INK_ALPHA,
                            ),
                        )
                    } else {
                        ButtonDefaults.textButtonColorsPrimary(
                            disabledColor = PiTone.control(),
                            disabledTextColor = MiuixTheme.colorScheme.onSurface.copy(
                                alpha = DIALOG_DISABLED_INK_ALPHA,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/**
 * The last non-null value, held while the window that draws it leaves.
 *
 * A Miuix window animates out through `show`, and its content is whatever the caller
 * passes on that frame — the library retains nothing (measured on 0.9.5-beta01: no
 * state in `WindowDialogKt`/`GlassDialogKt`; title and summary pass straight through).
 * A caller that reads its subject from the nullable flag (`show = x != null`, body
 * `x?.name.orEmpty()`) therefore empties the panel the moment the answer clears `x`:
 * the exit plays over a blank question. This holds the last value for that exit while
 * `show` still follows the live flag.
 *
 * The toast host predates this helper with the same one-liner; a dialog body should
 * use this rather than its own `var last`.
 */
@Composable
fun <T : Any> rememberLastPresent(value: T?): T? {
    var last by remember { mutableStateOf<T?>(null) }
    if (value != null) last = value
    return last
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
    val backdrop = LocalPiBackdrop.current
    if (backdrop != null) {
        GlassBottomSheet(
            show = show,
            backdrop = backdrop,
            modifier = modifier,
            title = title,
            onDismissRequest = onDismiss,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                content()
            }
        }
    } else {
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
                    // The toast is the app's only transient message, and it is not a
                    // focusable control: without a polite live region a screen reader
                    // never hears "Saved" or "Copied" at all.
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
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

/** The label of a disabled answer: the rectangle is the state, the ink still reads. */
private const val DIALOG_DISABLED_INK_ALPHA = 0.45f

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
    // `systemBars` carries the caption bar as well as the navigation bar, and the
    // display cutout can be on a short edge in a freeform window: a sheet whose last
    // row only clears `navigationBars` can sit under the caption bar in either case.
    WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom)
        .asPaddingValues()
        .calculateBottomPadding()

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
