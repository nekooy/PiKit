package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A titled group of rows: Miuix's own settings shape.
 *
 * The small title sits outside the card and the rows inside it, which is what
 * makes a long settings page read as groups rather than as one list — the same
 * arrangement the library's own settings page uses, and the reason the app has no
 * section-heading component of its own any more.
 *
 * [title] is null for an untitled group (a card that is obviously one thing), and
 * the group is drawn without a gap above it in that case.
 */
@Composable
fun PiSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            SmallTitle(text = title)
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            content = content,
        )
    }
}

/**
 * A paragraph inside a group, for the sentence a row cannot carry.
 *
 * Miuix's rows hold a title and a one-line summary; a settings page sometimes has
 * to explain a mechanism rather than name a value, and that text is not a summary —
 * it is prose, it wraps, and it belongs inside the card so that what it explains is
 * the thing it sits under. Drawn in the summary colour because it is explanation,
 * not a control.
 */
@Composable
fun PiHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * A group's final row: a full-width text button, outside the card.
 *
 * The destructive and the additive row are the two that sit here — "clear the
 * cache", "add a profile" — and both are *actions on the group*, not values in it,
 * which is why they are outside rather than another row inside.
 */
@Composable
fun PiGroupAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        enabled = enabled,
    )
}
