package pi.kit.mob.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
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
    title: String,
    message: String?,
    options: List<String>,
    placeholder: String?,
    prefill: String?,
    onValue: (String) -> Unit,
    onConfirmed: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val text = strings
    // Keyed on the prefill, so a question re-asked with a different starting point
    // starts from that point rather than from what was typed into the last one. It is
    // Miuix's state-based field because that is the API the library's `TextField`
    // takes now, and the answer is read out of the state at the moment the button is
    // tapped — nothing pushes a value back into it, so a keystroke cannot be undone
    // by a recomposition.
    val field = remember(prefill) { TextFieldState(prefill.orEmpty()) }

    // `confirm` carries neither options nor a field: the only branch that is a
    // question with no input at all.
    val confirmOnly = options.isEmpty() && placeholder == null && prefill == null && message != null

    WindowDialog(
        show = true,
        title = title,
        // The window's dismissal is the cancel answer, not a no-op: a question
        // closed by tapping outside has been answered "no".
        onDismissRequest = onCancel,
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
                    .heightIn(max = 320.dp)
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
                    options.isNotEmpty() -> Card(modifier = Modifier.fillMaxWidth()) {
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
                        state = field,
                        label = placeholder.orEmpty(),
                        // The placeholder is the field's label until something is
                        // typed, which is what a placeholder is; a label that
                        // floats above an empty box would add a second line of
                        // text to a dialog that is one question.
                        useLabelAsPlaceholder = true,
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A dialog keeps its dismiss even when the question has options:
                // a question with a destructive answer has to have both answers.
                TextButton(
                    text = text.common.cancel,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                // One confirm at most: an options question is answered by picking,
                // so the row is the cancel alone in that case.
                if (options.isEmpty()) {
                    TextButton(
                        text = if (confirmOnly) text.common.confirm else text.common.ok,
                        onClick = {
                            if (confirmOnly) {
                                onConfirmed(true)
                            } else {
                                onValue(field.text.toString())
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
