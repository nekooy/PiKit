package pi.kit.mob.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.ui.kit.PiCorner
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
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
            // A section label is a *label*, not a second title: the library's default
            // `onBackgroundVariant` carries a tint of the accent, which on the settings
            // page read as a link or a selected row above a card of plain rows. The
            // summary ink is the neutral grey the reference uses for the same line.
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        Card(
            cornerRadius = PiCorner.radius,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            content = content,
        )
    }
}

/**
 * A group's final row: a full-width text button, outside the card.
 *
 * The destructive and the additive row are the two that sit here — "clear the
 * cache", "add a profile" — and both are *actions on the group*, not values in it,
 * which is why they are outside rather than another row inside.
 *
 * Its fill is the library's secondary one; the light scheme moves that role to
 * `LIGHT_CONTROL` in `PiKitTheme.kt`, and that constant's KDoc says why. It is the fill
 * of every secondary control in the app, and the role used to sit on the page's own
 * colour here, which left this label with no button around it.
 */
/**
 * A titled group *inside a bottom sheet*.
 *
 * The page's [PiSection] cannot be used here: a sheet already spends its 16dp gutter
 * and `SmallTitle` adds 28dp more from the screen edge, which is the narrow column a
 * sheet's content had. This group keeps the card and states the smaller inset the
 * sheet's own measure uses, so a sheet reads as a stack of cards laid on the panel
 * rather than as a page squeezed into a window.
 *
 * The footer is the sentence a group of controls needs under it — "this many
 * providers need a key" — and it sits outside the card so the card stays a list of
 * controls and the prose stays prose.
 */
@Composable
fun PiSheetSection(
    title: String? = null,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (title != null) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp),
            )
        }
        Card(cornerRadius = PiCorner.radius, modifier = Modifier.fillMaxWidth(), content = content)
        if (footer != null) {
            Text(
                text = footer,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun PiGroupAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // A row in the group's own shape, not a filled button under it. The reference puts
    // "add" actions inside the list they extend — a plus at the leading edge and the
    // verb in the accent — and a button hanging below the card was the one control on
    // a settings page that belonged to a different visual family. `BasicComponent`
    // supplies the same 56dp row the rest of the group uses.
    Card(
        cornerRadius = PiCorner.radius,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        BasicComponent(
            title = text,
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp).size(20.dp),
                )
            },
            titleColor = BasicComponentDefaults.titleColor(
                color = MiuixTheme.colorScheme.primary,
            ),
            onClick = onClick,
            enabled = enabled,
        )
    }
}
