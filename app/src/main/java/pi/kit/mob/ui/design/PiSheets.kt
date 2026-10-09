/*
 * The inside of a sheet: its title, its list, and its rows.
 *
 * These are *bodies*, not sheets. `components/Sheets.kt` owns the panel, the
 * grabber, the scrim and the drag, and it does so for reasons measured on a device
 * rather than chosen — a `ModalBottomSheet` is a second window, which is why the
 * keyboard used to close and why taps were swallowed for up to 0.9 s after a
 * dismissal. Nothing here re-opens that question; these composables are what goes
 * inside the one panel the app is allowed to have open.
 *
 * ## Why a row in a sheet is not a `PiRow`
 *
 * A sheet's row is a *menu*: it is read once, tapped once, and dismissed. It is
 * therefore taller, centred rather than top-aligned, and its selected state is a
 * mark at the end rather than a filled container — because a sheet is usually a
 * picker, and a filled row in a picker says "this panel is a form" when what it
 * means is "this is the one you are on". The settings page's rows are the opposite:
 * they are read repeatedly and their controls live at the end.
 */
package pi.kit.mob.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.ui.components.LocalSheetHost

/**
 * A sheet's heading: what the panel is for, and optionally the thing it is about.
 *
 * Two lines at most. A sheet's title is a *question* ("Rename conversation") and
 * the subtitle is its subject (the current name), which is the pair that stops the
 * reader having to look behind the scrim to remember what they tapped.
 */
@Composable
fun PiSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The scrollable body of a sheet.
 *
 * A `LazyColumn` with a shared look and a **ceiling**: a list of twelve sessions
 * would otherwise grow the panel until it covered the window, which stops it being
 * a sheet — the point of a sheet is that the thing it is about stays visible behind
 * it and one scrim-tap dismisses it. [maxHeightFraction] is the share of the height
 * a sheet is allowed to take before it scrolls instead of growing; the default is
 * high enough for the longest picker in the app and low enough that the page behind
 * it is never fully lost.
 *
 * The panel's own bottom inset is applied by the layer, so this only has to leave
 * room for a row's press outline not to be clipped by the panel's corners.
 */
@Composable
fun PiSheetList(
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.7f,
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * maxHeightFraction),
            content = content,
        )
    }
}

/**
 * One row of a sheet's list.
 *
 * [value] is the fact behind the choice, [selected] puts a tick at the end. Both
 * may be set: a model picker shows the provider beside the model and a tick beside
 * the one in use, and the row is the only place those two facts can appear together.
 *
 * ## The tap is gated on the sheet still being open
 *
 * A sheet that has been dismissed keeps drawing for the length of its exit, and a
 * row that stayed tappable through it would run an action the user aimed at the page
 * behind it — the rule `components/Sheets.kt` states and its rows implement. It is
 * enforced here rather than at each call site because the exit is ~250 ms of the
 * panel still moving under the finger, which is exactly the window in which nobody
 * remembers to check.
 */
@Composable
fun PiSheetRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val host = LocalSheetHost.current
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(PiShapes.row)
            .clickable(enabled = enabled && host.isOpen, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = if (danger) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A sheet's closing action row: a dismiss and up to two real actions. */
@Composable
fun PiSheetActions(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

/** A swatch row, for a colour or an icon choice. */
@Composable
fun PiSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size - 6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(color),
        )
    }
}
