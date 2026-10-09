package pi.kit.mob.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiTextField

/**
 * Renders a dialog raised by a pi extension.
 *
 * This is the only way pi ever asks the user for a decision — it ships no
 * built-in permission prompt by design — so an unanswered dialog blocks the
 * extension indefinitely. In particular `editor` has no server-side timeout and
 * must always be answered or cancelled.
 *
 * ## Why this stays a platform dialog
 *
 * Every other panel in the app is a sheet in the app's own modal layer, for the
 * two reasons measured in `components/Sheets.kt`. This one is the exception and
 * keeps it: the caller is pi waiting on an answer rather than the user navigating,
 * it can arrive over any destination and while a sheet is already open, and a
 * question that blocks a process is the one case where the platform's own
 * attention-grabbing dialog is the honest shape. It is drawn in the app's colours
 * and its corner from [PiShapes.panel], so it does not read as a stranger.
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
    val text0 = strings
    var text by remember(prefill) { mutableStateOf(prefill.orEmpty()) }

    AlertDialog(
        onDismissRequest = onCancel,
        shape = PiShapes.panel,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Bounded and scrolled inside the bound. A message an
                        // extension sends is a sentence or a specification,
                        // depending on the extension, and an unbounded one pushed
                        // the answer buttons off the bottom of the dialog — the
                        // same measurement the runtime warnings dialog cites.
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                when {
                    options.isNotEmpty() -> options.forEach { option ->
                        // A text button like the cancel under the whole dialog, and
                        // the separation is the full width the option takes rather
                        // than a border: the app's dialogs are all words that differ
                        // by colour and nothing else. An outlined option was a filled
                        // boundary around an answer, which made the cancel the odd one.
                        PiButton(
                            text = option,
                            onClick = { onValue(option) },
                            kind = PiButtonKind.Text,
                            size = PiButtonSize.Small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // `confirm` carries neither options nor a placeholder.
                    placeholder == null && prefill == null && message != null -> Unit

                    else -> PiTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = placeholder,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when {
                options.isNotEmpty() -> Unit
                placeholder == null && prefill == null && message != null ->
                    PiButton(
                        text = text0.common.confirm,
                        onClick = { onConfirmed(true) },
                        kind = PiButtonKind.Text,
                        size = PiButtonSize.Small,
                    )

                else -> PiButton(
                    text = text0.common.ok,
                    onClick = { onValue(text) },
                    kind = PiButtonKind.Text,
                    size = PiButtonSize.Small,
                )
            }
        },
        dismissButton = {
            // A text button, like the confirm beside it: a dialog is two words that
            // differ by colour, neither carrying a fill or a border. A dialog keeps
            // its dismiss — a sheet does not need one, but a question with a
            // destructive answer has to have both answers.
            PiButton(
                text = text0.common.cancel,
                onClick = onCancel,
                kind = PiButtonKind.Text,
                size = PiButtonSize.Small,
            )
        },
    )
}
