/*
 * Fields and controls: the two text inputs, the switch row, the slider row, the
 * row that chooses one of a set, and the search field.
 *
 * ## Every one of these is a row, not a field
 *
 * This app's settings are a list of facts about the environment, and a fact is read
 * far more often than it is changed. So the controls here are *rows*: a label on the
 * start, the control on the end, and a press target that covers the whole row where
 * the whole row is meaningful. A form of labelled boxes stacked vertically — which
 * is what the settings pages used to be — makes a page of reading look like a page
 * of homework.
 *
 * ## The one field that is a field
 *
 * Text entry is the exception, and it is a real field: the search on the history
 * page, the rename dialog, and the three settings that hold a string the user types.
 * Those use the app's content radius rather than a pill, because a pill-shaped
 * input is a search affordance and a rename field is not a search.
 */
package pi.kit.mob.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The search field: a filled pill with a leading magnifier and a clear button.
 *
 * Filled rather than outlined because it sits directly under the app bar on a page
 * whose whole body is the list it filters, and an outlined box there reads as a
 * third frame between the bar and the list. The clear button appears only when
 * there is something to clear, which is the one place in the app a control is added
 * and removed rather than faded — a disabled magnifier's cross is noise in a field
 * the reader has not typed in yet.
 */
@Composable
fun PiSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    clearContentDescription: String? = null,
    enabled: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        placeholder = {
            Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = clearContentDescription,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        singleLine = true,
        shape = PiShapes.pill,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

/** A text field for a value the user types: a name, a URL, a key. */
@Composable
fun PiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    // For a field whose content must not be read over a shoulder — the model
    // page's API key is the one caller. Without this it has to be a raw
    // `OutlinedTextField` restyled to look like this one, which is a second place
    // for the app's field look to drift.
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // For a field whose content is a path, a command or an identifier. A file
    // editor and the JSON value fields are the two callers, and a proportional
    // face makes a path hard to read back — the same reason `PiRow` has the flag.
    monospace: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isError = isError,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        shape = PiShapes.card,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

/**
 * A row whose control is a switch.
 *
 * The whole row toggles. A switch with a 32dp-wide hit area beside a label that does
 * nothing is the most common way a settings page wastes its reader's taps, and the
 * only row here that does *not* do this is one whose label is itself a link.
 */
@Composable
fun PiSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(PiShapes.row)
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * A row with a slider and its value.
 *
 * The value is drawn as a badge on the *end* of the row rather than under the slider,
 * so the number stays here while the thumb moves and the row does not change height
 * as the label's width changes — a value that reflows from "12 pt" to "100 pt"
 * under a moving thumb is the thing that makes a slider feel unsettled.
 */
@Composable
fun PiSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    // Positional, because the third parameter is named differently in this release's
    // two `rememberSliderState` overloads and only its type is stable between them.
    val state = rememberSliderState(value, steps, valueRange)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(PiShapes.row)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (leading != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            PiBadge(valueLabel, tone = PiTone.Accent)
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            state = state,
            onValueChange = onValueChange,
            enabled = enabled,
        )
    }
}

/**
 * A row that chooses one option: a radio for the settings, a tick for a set.
 *
 * [role] picks which, and the two are different questions: a radio says "the others
 * are now off", a tick says "this one is on as well". Using one for the other is the
 * bug where a multi-select list looks like it can only hold one answer.
 */
@Composable
fun PiChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = PiMotion.defaultEffects(),
        label = "choiceFill",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(PiShapes.row)
            .background(container)
            .clickable(enabled = enabled, role = role) { onSelect() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = if (selected) {
                    MaterialTheme.typography.bodyLargeEmphasized
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                maxLines = 2,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (role == Role.RadioButton) {
            RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        } else {
            Checkbox(checked = selected, onCheckedChange = { onSelect() }, enabled = enabled)
        }
    }
}

/** A read-only row: a label, its value, and nothing to tap. */
@Composable
fun PiValueRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    valueTone: PiTone = PiTone.Neutral,
    valueColor: Color = valueTone.ink(MaterialTheme.colorScheme),
    maxValueLines: Int = 2,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = maxValueLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A vertical gap at the app's section rhythm. */
@Composable
fun PiGap(size: Dp = 8.dp) {
    Box(Modifier.size(size))
}
