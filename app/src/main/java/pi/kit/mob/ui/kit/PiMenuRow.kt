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
 * Miuix 0.9.4 cannot measure `BasicComponent` with `endActions` under an
 * unbounded width, and a `ListPopupColumn` asks exactly that of its children:
 * `BasicComponent`'s end-action budget is `(available - 8dp) * 6 / 10`, which overflows
 * `Int` at `Constraints.Infinity` and hands `Constraints.copy` a negative `maxWidth`.
 * The crash is `IllegalArgumentException: maxWidth must be >= than minWidth` from
 * `BasicComponent.measure`, and it was reproduced on the terminal's session roster
 * before this row existed. A `BasicComponent` *without* end actions survives the
 * same measurement, which is why the command and attachment menus did not crash —
 * and why this row exists rather than a blanket ban: a menu row with a trailing
 * tick is the one shape the library cannot currently hold.
 *
 * The metrics are Miuix's own menu-item ones — a 56dp minimum, 20dp at the sides,
 * the title in the headline size at medium weight and the summary in body2 — so a
 * row drawn here sits beside a library row without a seam.
 */
@Composable
fun PiMenuRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startAction != null) {
            Box(Modifier.padding(end = 12.dp)) { startAction() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
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
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 8.dp)) { trailing() }
        }
    }
}
