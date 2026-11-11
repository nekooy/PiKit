package pi.kit.mob.ui.kit

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
 * The app's ephemeral messages, drawn by the platform.
 *
 * A toast is a moment: one short sentence, gone after a couple of seconds, never
 * interacted with. The platform already owns that component — its queue, the placement
 * its navigation bar and display dictate, the accessibility settings and the text
 * scale. The app kept its own glass capsule for a while; keeping it meant keeping a
 * second copy of all of that for one sentence.
 * The message now goes through `Toast.makeText(...).show()` behind [LocalPiToast], the
 * interface a page already used, so no screen learned which framebuffer it lands in.
 */
@Stable
class PiToast internal constructor(private val context: Context) {

    /**
     * One message, now.
     *
     * The platform owns the queue: a second toast replaces the one on screen on most
     * versions and queues behind it on some, and both are the system's rule rather
     * than this app's to re-implement. A blank message is dropped — a toast with no
     * text is a rectangle.
     */
    fun show(message: String) {
        if (message.isBlank()) return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/** The toast, provided once at the root. A page that has no provider gets none. */
val LocalPiToast = staticCompositionLocalOf<PiToast?> { null }

/**
 * Provides a [PiToast] to everything below and draws nothing itself.
 *
 * The context is the composition's, taken once: `Toast` holds it only long enough to
 * post the message to the window manager, so the application context is the right one
 * to keep and the one that cannot leak an Activity.
 */
@Composable
fun PiToastProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val toast = remember(context) { PiToast(context) }
    CompositionLocalProvider(LocalPiToast provides toast, content = content)
}

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
