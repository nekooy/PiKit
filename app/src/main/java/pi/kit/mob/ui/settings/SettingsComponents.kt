/*
 * The furniture every settings page is built from.
 *
 * Settings is the one destination where a *group* of peer rows is the whole page
 * (ARCHITECTURE §13): a section is a labelled frame, a row is one peer inside it,
 * and the hairline between two rows belongs to the rows rather than to the page.
 * Every visual in this file therefore comes from `pi.kit.mob.ui.design`, and the
 * file is the settings-specific half of that vocabulary — the section, the row
 * with a value column, the note, the action strip.
 *
 * [SettingsRow] is the one member that is not a plain [PiRow], because a settings
 * row carries more than the design row's two slots: a value that has to be capped
 * so it cannot elbow the label aside, a value that may be monospaced, a value
 * *and* a chevron together, and a subtitle that may be monospaced. [PiRow]'s
 * trailing slot is "a value or a chevron but never both", and its subtitle is one
 * style, so the row keeps its own value column and composes [PiRow] for
 * everything else — the title, the leading mark, the press outline, the padding.
 *
 * [SettingsSwitchRow] is deliberately **not** [PiSwitchRow]: that one toggles when
 * the whole row is tapped, and a settings page's switch must not (see its own
 * KDoc).
 */
package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiSectionHeader

/**
 * A labelled group of rows: a section label over one [PiGroup].
 *
 * The label sits above the frame rather than inside it, so the grouping reads at a
 * glance: a flat page gives every setting the same weight and the rows that
 * actually stop the agent from working are indistinguishable from the licence
 * text. [PiSectionHeader] carries the label's style — small, emphasized, in the
 * secondary role rather than the primary one, because a heading in the action
 * colour made every label look tappable.
 */
@Composable
fun SettingsSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        PiSectionHeader(label)
        PiGroup { content() }
    }
}

/**
 * One row: icon, title, muted subtitle, optional value and chevron.
 *
 * [onClick] is what makes it tappable; without it the row is informational and
 * keeps no ripple, which is how the runtime facts are told apart from the
 * actions.
 *
 * [monospace] marks a row whose subtitle is a path or a command; the slot is full
 * width and wraps to three lines for it. [monospaceValue] is the same statement
 * about [value] — a revision, a version, a model id — and that slot *is* drawn in
 * `FontFamily.Monospace` when it is set.
 *
 * ## The value is a capped column, not a peer of the label
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
 * `运行环境` draws as `运行…`. Nothing about the data made the title less
 * important; the layout just had no opinion about the order.
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
 * handed in as [subtitle] — full width, three lines — rather than as a value:
 * the rule is about *where a string belongs*, and a path belongs on a line of
 * its own.
 *
 * [monospace] is the one caller-visible thing the delegation to [PiRow] could not
 * carry, and it is the subtitle's *face* alone: the design row has exactly one
 * subtitle style, so a path in this slot is no longer drawn in `FontFamily
 * .Monospace`. The parameter stays because every call site passes it and the row
 * is this module's public surface; the *value* column, which this row still owns,
 * keeps [monospaceValue].
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
    // Read here rather than computed from PiRow's padding: the share is of the
    // whole row, so it needs no knowledge of the icon, the chevron or the
    // gutters, and it stays the same number on every page.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scheme = MaterialTheme.colorScheme
        val valueCap = maxWidth * VALUE_MAX_SHARE
        // Material3's own disabled content alpha, applied to every part of the row
        // rather than to the title alone: a dimmed title over a full-strength
        // subtitle reads as a formatting bug. PiRow dims its own two slots; the
        // mark and the value below are this row's, so they dim here.
        val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
        val markColor = (if (danger) scheme.error else scheme.onSurfaceVariant).copy(alpha = contentAlpha)

        PiRow(
            title = title,
            // The title's colour is named even when the row is enabled, rather
            // than left as `Color.Unspecified`: `Unspecified.copy(alpha = …)`
            // stops being the "draw in `LocalContentColor`" sentinel and becomes
            // a colour with an unspecified colour space, which renders black — on
            // a dark surface that is invisible text.
            titleColor = if (danger) scheme.error else scheme.onSurface,
            subtitle = subtitle,
            leading = if (icon != null) {
                { Icon(icon, contentDescription = null, tint = markColor) }
            } else {
                null
            },
            // Always supplied, even when it draws nothing, because PiRow adds a
            // chevron of its own to any row that is tappable and this row's
            // chevron is [showChevron]'s decision rather than the tap's.
            trailing = {
                if (!value.isNullOrBlank()) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = (
                            if (valueEmphasised) scheme.onSurface
                            else scheme.onSurfaceVariant
                            ).copy(alpha = contentAlpha),
                        fontFamily = if (monospaceValue) FontFamily.Monospace else null,
                        textAlign = TextAlign.End,
                        // Two lines: a machine string in the value column — a
                        // revision, a repository path — needs the second line, and
                        // one line cut `x86_64-440db5bca1496c14` into something that
                        // could not be checked against a bug report.
                        maxLines = 2,
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
                        tint = scheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            onClick = onClick,
            enabled = enabled,
        )
    }
}

/**
 * The column a group's own content starts at: where a note's text, a row's icon
 * and a control placed by hand all begin.
 *
 * 24dp is [PiRow]'s own figure — its 12dp outer padding plus its 12dp inner one —
 * so a note or a button added inside a group lands on the column the rows above it
 * use. Outside a group the same note gets 24dp on top of `PiPagePadding`, which is
 * where a row's *title* starts, so one inset serves both positions.
 *
 * Not 12dp: that is what a *text field* uses, and a field is different because it
 * has an outline of its own and is inset to sit inside its card, while a note and
 * a button have no box and have to line up with the content above them. The
 * maintenance page's buttons started there and sat 4dp left of the note under them
 * and 4dp left of the row's own icon, near enough to look like a mistake rather
 * than like a decision.
 */
private val CONTENT_INSET = 24.dp

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
 * and a larger font scale; the 23-character revision that settles it is the
 * string that exercises it.
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
 * inset inside the card while a rule used to span the card's full width, so a line
 * under or over a field ran edge to edge *past* the rounded corners of the box it
 * is supposed to separate from, with 4dp of air on each side.
 *
 * It is the design system's row divider insetted for a leading mark, because a
 * settings row always has one: [PiRow] starts its title one 24dp mark and a 16dp
 * gap in from its own padding, so the rule has to begin under the text rather than
 * under the icon. This is also what the history list uses
 * ([pi.kit.mob.ui.SessionsScreen]), so the two pages share one rule style.
 *
 * Still never beside a text field: a run of fields is separated by their own
 * outlines and by the 8dp that two `vertical = 4.dp` paddings add up to.
 */
@Composable
fun SettingsDivider() {
    PiRowDivider()
}

/**
 * The scrolling body every settings page shares.
 *
 * A plain scrolling `Column` rather than a `LazyColumn`: these pages are tens of
 * rows, and a lazy list would only add nested-scroll problems to text fields
 * that need to scroll themselves. The 12dp gutter is the app's page inset, which
 * is what puts a section's label and its rows on the same column.
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
            .padding(PiPagePadding)
            .padding(top = 8.dp, bottom = PiPageBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}


