package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
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

/** A message with one acknowledgement. */
@Composable
fun PiAlert(
    show: Boolean,
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = text,
        onDismissRequest = onConfirm,
    ) {
        TextButton(
            text = confirm,
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = dismiss,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
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
 * The app's one transient message, hosted once at the root.
 *
 * A snackbar rather than the old pill because Miuix *has* a snackbar and this app
 * would otherwise be drawing a second kind of transient message beside the one the
 * interface is made of. Screens reach it through [LocalPiToast], which is the same
 * shape the old toast host had: the message survives the page that raised it,
 * because the host is above every page.
 */
@Stable
class PiToastHost internal constructor(
    private val scope: CoroutineScope,
    internal val state: SnackbarHostState,
) {
    /**
     * One slot, like the pill it replaces.
     *
     * A second message replaces the first and restarts the clock. A queue here
     * would be a log nobody reads — the same argument as the one-sheet rule — and
     * two messages about one tap ("Saved", then "Agent restarted") are one
     * sentence, not two. Miuix's host draws every visible entry, so the previous
     * one is dismissed before the new one is shown rather than being left to its
     * own timeout.
     */
    fun show(message: String) {
        scope.launch {
            state.newestSnackbarData()?.dismiss()
            state.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
}

val LocalPiToast = compositionLocalOf<PiToastHost?> { null }

/** Creates the host and its scope; call once, at the root. */
@Composable
fun rememberPiToastHost(): PiToastHost {
    val scope = rememberCoroutineScope()
    val state = remember { SnackbarHostState() }
    return remember(scope, state) { PiToastHost(scope, state) }
}

/** Provides [host] to everything below and draws nothing itself. */
@Composable
fun PiToastProvider(host: PiToastHost, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPiToast provides host, content = content)
}

/** The snackbar slot: goes in the root scaffold's `snackbarHost`. */
@Composable
fun PiToastHostLayer(host: PiToastHost, modifier: Modifier = Modifier) {
    SnackbarHost(state = host.state, modifier = modifier)
}

/** A label in Miuix's body style, for the rows a dialog body needs. */
@Composable
fun PiDialogBody(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurface,
    )
}
