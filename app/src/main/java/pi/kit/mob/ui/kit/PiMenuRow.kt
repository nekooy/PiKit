package pi.kit.mob.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One row of a `ListPopupColumn` menu, built from the primitives the library
 * cannot break.
 *
 * ## Why this is not a `BasicComponent`
 *
 * Miuix 0.9.4 cannot measure `BasicComponent` with `startAction` or `endActions`
 * under an unbounded width, and a `ListPopupColumn` asks exactly that of its
 * children: `BasicComponent`'s end-action budget is `(available - 8dp) * 6 / 10`,
 * which overflows `Int` at `Constraints.Infinity` and hands `Constraints.copy` a
 * negative `maxWidth`. The crash is `IllegalArgumentException: maxWidth must be >=
 * than minWidth` from `BasicComponent.measure`, reproduced on the composer's `+`
 * menu — whose three rows carry leading marks — with `android.view.View.measure`
 * frames above it in the stack, because the popup's own measuring pass is where the
 * infinity arrives. A `BasicComponent` *without* either slot survives the same
 * measurement, which is why the command menu did not crash before it was converted.
 *
 * The metrics are `DropdownImpl`'s own popup-mode ones, read out of the library at
 * `0.9.4`: `body1` at medium weight for the title, `body2` for the summary, 20dp at
 * the sides, 20dp above the first row and below the last, 12dp between them, and
 * `12dp` between a leading mark and the title. Copying them rather than inventing a
 * row is what lets a menu of these sit beside a menu of `DropdownImpl`s without a
 * seam — the two are used in the same app, sometimes in the same screen.
 *
 * ## Selecting, not merely clicking
 *
 * [selected] is what a row that is *one of a set* passes — the terminal roster, a
 * model list — and it turns the row into a `selectable` with the radio-button role,
 * so a screen reader says "selected" instead of "button" and the row's own mark is
 * then decoration. A row that *acts* (a command, "close all shells") leaves it null
 * and is a plain button.
 */
@Composable
fun PiMenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    /**
     * Whether this row is the chosen member of a set, or null for a row that acts.
     *
     * A menu of choices and a menu of commands look identical and are read
     * differently: one has a current answer and the other has none. This is the
     * parameter that says which, and it is the only thing that changes the
     * semantics — the mark a caller draws in [trailing] is its own business.
     */
    selected: Boolean? = null,
    /**
     * The title's colour, for the one row in a menu that is not a choice.
     *
     * A menu's rows are things to pick, and the title colour is the scheme's
     * surface ink like every other row. The exception is a row that *acts* rather
     * than picks — "close all shells" — which carries the error role, because a
     * destructive action that looks like one more choice in the list is the one
     * place a menu can be dangerous. Read only while [enabled]; a disabled row is
     * the disabled ink whatever this says.
     */
    titleColor: androidx.compose.ui.graphics.Color = MiuixTheme.colorScheme.onSurface,
    startAction: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    // A set's row is `selectable` and a command's is `clickable`; the two differ in
    // the role they report and in whether the node carries a checked state, which is
    // the whole of what a screen reader hears.
    val interaction = if (selected != null) {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(interaction)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startAction != null) {
            Box(Modifier.padding(end = 12.dp)) { startAction() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    titleColor
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = if (enabled) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 12.dp)) { trailing() }
        }
    }
}
