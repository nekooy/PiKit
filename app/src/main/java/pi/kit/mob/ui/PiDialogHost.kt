package pi.kit.mob.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Renders a dialog raised by a pi extension.
 *
 * This is the only way pi ever asks the user for a decision — it ships no
 * built-in permission prompt by design — so an unanswered dialog blocks the
 * extension indefinitely. In particular `editor` has no server-side timeout and
 * must always be answered or cancelled.
 *
 * ## Why this is a window-level dialog
 *
 * Every other panel in the app is a sheet, because the user is navigating. This
 * one keeps the platform's own window: the caller is pi waiting on an answer
 * rather than the user browsing, the question can arrive over any destination and
 * while a sheet is already open, and a question that blocks a process is the one
 * case where the window's own attention-grabbing panel is the honest shape.
 *
 * [WindowDialog] rather than Miuix's `OverlayDialog` for the same reason it is not
 * a sheet: an overlay dialog needs a `Scaffold` above it to draw its host, and this
 * is raised from the root, which is outside every page's scaffold. It is Miuix's
 * shape either way — the background, the title and the summary are the library's,
 * and so is the corner, which comes from the screen's own radius rather than from a
 * constant here.
 *
 * ## Why the host stays composed
 *
 * The window is *driven*, not raised: [show] is the switch, and the composable
 * itself is drawn once at the root for the life of the screen. Composing it only
 * while a question is pending — which is what `pending != null` around this call
 * does — tears the composable out the instant it is answered, so [WindowDialog]
 * never runs its exit animation, never reports [onDismissFinished], and the next
 * question starts a new window from nothing. The contract that makes the driven
 * form safe is in the parameters: while [show] is false the values must still be the
 * question that is leaving — the window is animating out with them — and
 * [onDismissFinished] is the moment the window has actually gone, for a caller that
 * wants to forget the question then rather than replace it when the next arrives.
 *
 * ## The three shapes it takes
 *
 * `options` is a question with a fixed answer, `placeholder`/`prefill` is a
 * question with a typed one, and neither is a plain confirmation. The branches
 * below are that distinction and nothing else; in particular a `confirm` with a
 * message *and* no field draws no field, which is the branch that would otherwise
 * put an empty text box under a sentence asking "are you sure".
 */
@Composable
fun PiDialogHost(
    show: Boolean,
    title: String,
    message: String?,
    options: List<String>,
    placeholder: String?,
    prefill: String?,
    onValue: (String) -> Unit,
    onConfirmed: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismissFinished: () -> Unit = {},
) {
    val text = strings
    // One field per *question*, not per composition: the host outlives a question
    // (see the KDoc), so a remembered buffer would otherwise carry the last answer
    // into the next one. It is refilled whenever a question begins, which is also
    // the moment the prefill can have changed; Miuix's state-based field takes the
    // text through this helper, so the cursor lands at the end of the prefill rather
    // than at its start.
    val field = remember { TextFieldState(prefill.orEmpty()) }
    LaunchedEffect(show) {
        if (show) field.setTextAndPlaceCursorAtEnd(prefill.orEmpty())
    }

    // `confirm` carries neither options nor a field: the only branch that is a
    // question with no input at all.
    val confirmOnly = options.isEmpty() && placeholder == null && prefill == null && message != null

    // The body's bound comes from the window it is drawn in, not from a constant: a
    // landscape phone's window is about 360dp tall, where a fixed 320dp body leaves
    // no room for the title and the two answers and pushes the buttons off the
    // screen. Half the window keeps room for both in either orientation, the 320dp
    // ceiling stops a portrait phone or a tablet stretching the body into a wall of
    // text, and the floor keeps a scroll region usable on a window too short for the
    // arithmetic to matter.
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val bodyMaxHeight = remember(windowHeight) {
        (windowHeight * DIALOG_BODY_FRACTION)
            .coerceIn(MIN_DIALOG_BODY_HEIGHT, MAX_DIALOG_BODY_HEIGHT)
    }

    WindowDialog(
        show = show,
        title = title,
        // The window's dismissal is the cancel answer, not a no-op: a question
        // closed by tapping outside has been answered "no".
        onDismissRequest = onCancel,
        // The moment the window has actually gone, after its exit animation: a
        // caller that wants to forget the question — rather than keep the last one
        // until the next arrives — lets go of it here.
        onDismissFinished = onDismissFinished,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The body is one bounded, scrolling column rather than two: an
            // extension's message is a sentence or a specification depending on
            // the extension, and an unbounded one pushed the answer buttons off
            // the bottom of the dialog. The bound is on the body, not on the
            // dialog, so the answers stay where the reader learned to find them
            // whatever the extension sent.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = bodyMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                when {
                    // The options are one list of one-of-these answers, which is
                    // what a radio row is: the circle says "choose one" and the
                    // row answers on tap, so a question whose answer closes the
                    // dialog never shows a tick it would have to take back.
                    //
                    // A card around them because the dialog's own background is the
                    // page's: Miuix's rows carry no surface of their own, and a set
                    // of answers reads as one group the way a settings page's do.
                    options.isNotEmpty() -> Card(modifier = Modifier.fillMaxWidth(), cornerRadius = PiCorner.radius) {
                        options.forEach { option ->
                            RadioButtonPreference(
                                title = option,
                                selected = false,
                                onClick = { onValue(option) },
                            )
                        }
                    }

                    confirmOnly -> Unit

                    else -> TextField(
                        cornerRadius = PiCorner.radius,
                        state = field,
                        label = placeholder.orEmpty(),
                        // The placeholder is the field's label until something is
                        // typed, which is what a placeholder is; a label that
                        // floats above an empty box would add a second line of
                        // text to a dialog that is one question.
                        useLabelAsPlaceholder = true,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        // No focus ring, and the caret stated instead — a dialog has
                        // one field in it, so the accent border was drawing the
                        // reader's attention to the only thing on the panel they could
                        // already only be typing into.
                        colors = piFieldColors(),
                        cursorBrush = piFieldCursor(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // The app's one dialog-button row. A dialog keeps its dismiss even when
            // the question has options — a question with a destructive answer has to
            // have both answers — and an options question has no confirm at all, since
            // it is answered by picking.
            PiDialogAnswers(
                dismiss = text.common.cancel,
                onDismiss = onCancel,
                confirm = if (options.isEmpty()) {
                    if (confirmOnly) text.common.confirm else text.common.ok
                } else {
                    null
                },
                onConfirm = {
                    if (confirmOnly) {
                        onConfirmed(true)
                    } else {
                        onValue(field.text.toString())
                    }
                },
            )
        }
    }
}

/** The share of the window's height the question body may take. */
private const val DIALOG_BODY_FRACTION = 0.5f

/** The body's bounds: a ceiling for tall windows, a floor for very short ones. */
private val MAX_DIALOG_BODY_HEIGHT = 320.dp
private val MIN_DIALOG_BODY_HEIGHT = 120.dp
