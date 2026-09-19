package pi.kit.mob.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings

/**
 * Renders a dialog raised by a pi extension.
 *
 * This is the only way pi ever asks the user for a decision — it ships no
 * built-in permission prompt by design — so an unanswered dialog blocks the
 * extension indefinitely. In particular `editor` has no server-side timeout and
 * must always be answered or cancelled.
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
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                message?.let { Text(it) }

                when {
                    options.isNotEmpty() -> options.forEach { option ->
                        TextButton(
                            onClick = { onValue(option) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(option) }
                    }

                    // `confirm` carries neither options nor a placeholder.
                    placeholder == null && prefill == null && message != null -> Unit

                    else -> OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = placeholder?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when {
                options.isNotEmpty() -> Unit
                placeholder == null && prefill == null && message != null ->
                    TextButton(onClick = { onConfirmed(true) }) { Text(text0.common.confirm) }

                else -> TextButton(onClick = { onValue(text) }) { Text(text0.common.ok) }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(text0.common.cancel) }
        },
    )
}
