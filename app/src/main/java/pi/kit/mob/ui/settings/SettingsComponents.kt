package pi.kit.mob.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.ui.components.PageHeader

/**
 * The settings pages all use the shared header, so a sub-page differs from the
 * tab root only by its back action. Every page paints its own status bar band;
 * adding an inset here would double it.
 */
@Composable
fun SettingsPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
) {
    PageHeader(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        backContentDescription = LocalStrings.current.common.back,
    )
}

/**
 * A labelled group of rows.
 *
 * The label sits above the card rather than inside it, so the grouping reads at
 * a glance: the previous flat page gave every setting the same weight and the
 * model fields — the thing that actually stops the agent from working — were
 * indistinguishable from the licence text.
 */
@Composable
fun SettingsSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * One row: icon, title, muted subtitle, optional value and chevron.
 *
 * [onClick] is what makes it tappable; without it the row is informational and
 * keeps no ripple, which is how the runtime facts are told apart from the
 * actions.
 *
 * [monospace] is for rows whose subtitle is a path or a command: those are long
 * enough to wrap, and proportional digits make a path noticeably harder to read
 * back. [monospaceValue] is the same statement about [value] — a revision, a
 * version, a model id — rather than about the subtitle.
 *
 * ## The value is a demoted column, not a peer of the label
 *
 * A `Row` measures a child that has no `weight` against its *intrinsic* width
 * before it gives the weighted children what is left, so a value that takes its
 * natural width takes it out of the title's share. Measured on the emulator
 * before this change, on the Advanced page's runtime row: the value
 * `x86_64-440db5bca1496c14` sat at [511,401][911,443] — 400 px on one line, and
 * the whole width the row had left for the label beside it was 300 px
 * (`运行环境` starts at x=174, the value at 511, one 14dp gap between). The value
 * was wider than the entire label column. At font scale 1.0 the title still fit
 * inside those 300 px; at 1.8 the same string needs 720 px of the 706 px the row
 * has left after its icon and chevron, so the label column gets nothing and
 * `运行环境` draws as `运行…` — which is the report this was fixed from. Nothing
 * about the data made the title less important; the layout just had no opinion
 * about the order.
 *
 * So the value is capped at [VALUE_MAX_SHARE] of the row and wraps inside that
 * cap instead of elbowing the label aside, and a long *machine* string gets
 * three lines there rather than one. After the change, on the same row at font
 * scale 1.0: the value is [560,380][911,464] — 351 px, two lines, the whole
 * revision — and the label column has grown to 349 px. At font scale 1.8 the
 * same three nodes measure title [174,475][455,578] on one line, subtitle
 * [174,578][523,736] on two, value [560,492][911,720] on three, and no string in
 * the row is cut. The label keeps everything else and wraps to two lines rather
 * than ellipsising, because a title is a label and a label is never the thing
 * that gets clipped.
 *
 * A path is the one string this cap is still too sharp for: the pi CLI path is
 * 94 characters and a third of a row cannot show it. That is why paths are
 * handed in as [subtitle] (full width, [monospace], three lines) rather than as
 * a value — the rule is about *where a string belongs*, and a path belongs on a
 * line of its own.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    monospace: Boolean = false,
    monospaceValue: Boolean = false,
    /**
     * Draws [value] in the row's own text colour rather than the muted one.
     *
     * For a value the *user* put there, as opposed to one the app is reporting: the
     * model page's two number rows show a number the user set plainly and the number
     * pi will use if they leave it alone in grey, which is the difference the row has
     * to make visible without a second row saying it.
     */
    valueEmphasised: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    danger: Boolean = false,
    /**
     * False for a row that is present but does nothing — the storage page's "add a
     * folder" while the whole tree is already granted, for instance. A row that is
     * *removed* in that state would move everything under it, and one that stays
     * tappable and does nothing is worse than one that looks inert.
     */
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Read here rather than computed from the padding: the share is of the
        // whole row, so it needs no knowledge of the icon, the chevron or the
        // 16dp gutters, and it stays the same number on every page.
        val valueCap = maxWidth * VALUE_MAX_SHARE
        // Material3's own disabled content alpha, applied to the whole row rather
        // than to the title alone: a dimmed title over a full-strength subtitle
        // reads as a formatting bug.
        val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
        // The title's colour when the row is *not* dimmed has to stay
        // `Color.Unspecified` — the sentinel that means "draw in `LocalContentColor`",
        // which is what makes the row follow the theme. Dimming it is not possible:
        // `Unspecified.copy(alpha = …)` stops being the sentinel and becomes a colour
        // with an unspecified colour space, which renders black. On a dark surface
        // that is invisible, and it was reported as exactly that — a settings page
        // whose text vanished in dark mode. So the disabled case names its colour.
        val titleColor = when {
            danger -> MaterialTheme.colorScheme.error.copy(alpha = contentAlpha)
            enabled -> Color.Unspecified
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        // Clipped to the card's own corner radius before the ripple is
                        // attached, because a `clickable`'s indication is a *rectangle*: on
                        // the first and last row of a card it painted square corners outside
                        // the rounded ones, which is the "no rounded corners on the tap
                        // feedback" a reader reported on the model page's two number rows.
                        // Every row that is tappable had it; the two numbers were simply the
                        // ones with nothing else on them to look at.
                        //
                        // The radius is the section's own shape rather than a second number,
                        // so a theme change moves both.
                        Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = (
                        if (danger) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        ).copy(alpha = contentAlpha),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    // No `maxLines`, and that is the fix for a report rather than a
                    // style choice: the subtitle is the row's *explanation*, and it is
                    // the one thing in the row with no competitor for width — the icon
                    // and the chevron are fixed, and the value column's cap is what
                    // keeps the title's share. Cut at two lines it read as
                    // "小字没显示完全" on the maintenance page, whose subtitle lists the
                    // two commands the button runs: the sentence that says what the
                    // control does was the sentence that was missing its end.
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        fontFamily = if (monospace) FontFamily.Monospace else null,
                    )
                }
            }
            if (!value.isNullOrBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = (
                        if (valueEmphasised) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                        ).copy(alpha = contentAlpha),
                    fontFamily = if (monospaceValue) FontFamily.Monospace else null,
                    // End-aligned inside the cap, so a short value still sits on
                    // the row's trailing edge exactly where it did before the cap
                    // existed and the whole column keeps one right margin.
                    textAlign = TextAlign.End,
                    maxLines = if (monospaceValue) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = valueCap)
                        .padding(end = 2.dp),
                )
            }
            if (trailing != null) {
                trailing()
            } else if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The most of a row its value may take.
 *
 * Settled by the widest value the app actually renders in that slot —
 * `Custom endpoint` on the provider row, which measures 252 px of the 1016 px a
 * settings row has — plus room for a translated label to be longer. A settings
 * row is 1016 px wide on the measured 1080 px screen, so 0.35 puts the cap at
 * 356 px, which is that string on one line with 100 px to spare; the value node
 * of the Advanced page's runtime row renders at 351 px under it (the extra 5 px
 * is the 2dp that keeps the value off the chevron), and the label column keeps
 * the other 349 px however long the value is.
 *
 * It is a share rather than a dp value so the split survives a narrower phone
 * and a larger font scale; the 23-character revision the report was filed about
 * is the string that exercises it.
 */
private const val VALUE_MAX_SHARE = 0.35f

/** Material3's disabled content alpha, for a row whose action is not available. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Between rows inside a [SettingsSection], never after the last one — and never
 * beside a text field.
 *
 * A divider separates two *rows*: a switch, a picker, a statement of fact. A text
 * field is not one of those, because it already draws its own boundary — and it is
 * inset 12dp inside the card while the divider spans the card's full width, so a
 * line under or over a field runs edge to edge *past* the rounded corners of the
 * box it is supposed to separate from, with 4dp of air on each side. On the search
 * page that was seven key fields with a divider over every one of them — six between
 * the boxes and one under the note — which reads as a rendering fault rather than as
 * a list. So a field follows whatever is
 * above it directly, the way it does on `ModelPages` and `AgentPage`, and a run of
 * fields is separated by their own outlines and by the 8dp that two `vertical = 4.dp`
 * paddings add up to.
 *
 * Insetting the divider to the field's own 12dp was the other candidate (that is
 * what Material's list insets do) and is worse: it still leaves a line floating in
 * the gap between two borders, only now it also stops short of the card's edges and
 * matches nothing else on the page.
 */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
    )
}

/**
 * The scrolling body every settings page shares.
 *
 * A plain scrolling `Column` rather than a `LazyColumn`: these pages are tens of
 * rows, and a lazy list would only add nested-scroll problems to text fields
 * that need to scroll themselves.
 */
@Composable
fun SettingsBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

/** A paragraph of explanation, for the pages that need one. */@Composable
fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/**
 * One row whose only control is the switch in its trailing slot.
 *
 * The row is deliberately **not** clickable, and that is the whole reason this is
 * a component rather than three lines at each call site: a clickable row with a
 * switch in its trailing slot runs both the row's `onClick` and the switch's
 * `onCheckedChange` for one tap, so the control appears to do two things — or,
 * where the two cancel out, nothing at all. On the model page that was literally
 * the bug: the row that chose the answering model also carried the image switch,
 * so tapping the switch moved the selection as well. The switch is the target and
 * the row is inert, which is also how `StoragePage`'s folder rows work.
 *
 * A [value] is drawn in the value column before the switch — the web-access row
 * uses it for the extension's version — and is monospaced when it is a machine
 * string, the way the runtime and pi version rows are.
 *
 * [enabled] is false for a switch that must not be moved, and [subtitle] carries the
 * reason — a disabled row with only the normal caption reads as a rendering fault.
 * Nothing uses it today: the model page's image switch was the one caller, and it is
 * drawn for every model now, catalogued or not (`modelDefinitions` writes an override
 * for a model pi knows and a definition for one it does not, so there is no model the
 * declaration cannot reach). It is kept because the state is a real one for any future
 * control that has to be shown and explained before it can be used.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        value = value,
        monospaceValue = value != null,
        modifier = modifier,
        enabled = enabled,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}
