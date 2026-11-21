package pi.kit.mob.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * One row of a [PiPopupMenu], at OS4's own popup metrics.
 *
 * ## The metrics are the library's glass row, copied on purpose
 *
 * `GlassPopupItem` is the row `GlassPopup` draws, and its numbers are the popup's
 * language: a 44dp floor, 20dp at the sides, 10dp above and below, a 24dp leading mark
 * 8dp from the title, a 24dp tick cell after it, and a press fill inset 7dp from the
 * panel's edge at a 15dp corner. This row states them itself rather than wrapping
 * `GlassPopupItem` because a menu here needs three things that component cannot say
 * (below), and a wrapper that re-exposed it a parameter at a time would leave two
 * vocabularies in the tree (§13, docs/UI.md).
 *
 * The metrics still have to *be* the library's, or a menu of these beside a menu of its
 * own rows is two rows at two heights in one panel. `GlassPopupDefaults` is read where a
 * value is a public constant, and the arithmetic of the press fill is repeated: the fill
 * covers the row less [PRESS_INSET], and the content is inset by the same amount so the
 * text does not move when the fill appears.
 *
 * ## Three kinds of row, and the one parameter that tells them apart
 *
 * A menu row is a **choice**, an **action**, or a **fact**, and the three are read
 * differently:
 *
 * - a choice ([selected] is true or false) is `selectable` with the radio-button
 *   role, so a screen reader says "selected" instead of "button", and its title is
 *   drawn in the accent when it is the chosen member — the same ink `GlassPopupItem`
 *   gives its selected row, which is what keeps the terminal's session list and the
 *   composer's model list reading as one control. The report that made this a rule
 *   was "终端页列表文字为什么没随打钩变色": the tick was blue and the title was not.
 * - an action ([onClick] set, no [selected]) is a plain button;
 * - a fact ([onClick] null) is neither, and is drawn in normal ink: it is a row of
 *   *readings* (the context report), and the library's disabled ink made a page of
 *   true numbers look like a page of unavailable choices. Nothing consumes a tap, so
 *   the row is not a control and does not pretend to be one.
 *
 * A choice also *sounds* like the library's: `RadioButton` and `RadioButtonPreference`
 * perform the toggle haptic inside themselves, so a hand-built radio row that stayed
 * silent would make one act buzz on a settings page and not in the terminal's roster.
 * The row therefore calls [PiHaptics.choose], on the tap that changes the answer — a
 * second tap on the chosen row leaves the set exactly as it was, and the library buzzes
 * there only because a control that knows nothing but its own state cannot tell the two
 * taps apart. Actions and facts stay silent: their effect is the answer.
 *
 * ## The tick cell is always there
 *
 * `GlassPopupItem` draws its check glyph at every row and tints it transparent when the
 * row is not chosen, which is what keeps two rows' titles on one column whether or not
 * one of them is ticked. This row does the same when it is a choice and no [trailing]
 * control is given; a row that states a fact reserves the same cell for the same reason.
 *
 * ## Why this is not a `BasicComponent`
 *
 * Miuix 0.9.4 could not measure `BasicComponent` with `startAction` or `endActions`
 * under an unbounded width, and a popup column asked exactly that of its children:
 * `BasicComponent`'s end-action budget is `(available - 8dp) * 6 / 10`, which overflows
 * `Int` at `Constraints.Infinity` and hands `Constraints.copy` a negative `maxWidth`.
 * The crash is `IllegalArgumentException: maxWidth must be >= than minWidth` from
 * `BasicComponent.measure`, reproduced on the composer's `+` menu — whose three rows
 * carry leading marks — with `android.view.View.measure` frames above it in the stack,
 * because the popup's own measuring pass is where the infinity arrives. **0.9.5-beta01
 * fixed it** with a `constraints.hasBoundedWidth` branch; this row remains because it
 * carries the popup metrics and the three semantics below, not because the component
 * would still crash.
 */
@Composable
fun PiMenuRow(
    title: String,
    /**
     * What a tap does, or null for a row that only states something.
     *
     * A read-only row is still not a `Text`: it keeps the row's height, insets and
     * typography, because it is drawn beside rows that are controls.
     */
    onClick: (() -> Unit)? = null,
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
    titleColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    /** A leading mark, in the row's 24dp cell. */
    icon: ImageVector? = null,
    /** A leading composable instead of a glyph, for a mark that is not a vector. */
    startAction: (@Composable () -> Unit)? = null,
    /** A control at the row's end, in place of the tick cell. */
    trailing: (@Composable () -> Unit)? = null,
) {
    // The library's radio rows buzz inside themselves (see the KDoc); this row stands
    // in for them in a popup, so it owes the same answer.
    val haptics = LocalPiHaptics.current
    // The press fill is the panel's own answer to a finger, and it is animated exactly as
    // `GlassPopupItem` animates it: snapped on at the down event, released on the
    // library's short spring. A fill that faded in would trail a fast tap. The source is
    // shared with the gesture below, so the fill and the action are one press.
    val interactionSource = remember { MutableInteractionSource() }
    // A set's row is `selectable` and a command's is `clickable`; the two differ in
    // the role they report and in whether the node carries a checked state, which is
    // the whole of what a screen reader hears. A fact carries no gesture at all.
    // `indication = null` on both: the fill below *is* the indication, and the library's
    // ripple would square off against the panel's continuous corner.
    val interaction = when {
        onClick == null || !LocalPiMenuInteractive.current -> Modifier
        selected != null -> Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                // Only a tap that changes the answer: one on the row already chosen
                // leaves the set as it was, so there is no choice to announce.
                if (!selected) haptics.choose()
                onClick()
            },
        )

        else -> Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
    }

    val pressed by interactionSource.collectIsPressedAsState()
    val pressFill = animateColorAsState(
        targetValue = if (pressed && enabled && onClick != null) pressColor() else Color.Transparent,
        animationSpec = if (pressed) snap() else GlassMotion.popupPressExit(),
        label = "piMenuRowPress",
    )
    val pressRadius = remember { GlassPopupDefaults.ItemPressRadius }
    val pressInset = GlassPopupDefaults.ItemPressInset
    val titleInk = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSurface
        selected == true -> MiuixTheme.colorScheme.primary
        else -> titleColor
    }
    val summaryInk = if (enabled) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    } else {
        MiuixTheme.colorScheme.disabledOnSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GlassPopupDefaults.ItemMinHeight)
            .padding(horizontal = pressInset)
            .drawBehind {
                drawRoundRect(
                    color = pressFill.value,
                    cornerRadius = CornerRadius(pressRadius.toPx()),
                )
            }
            .then(interaction)
            .padding(
                horizontal = GlassPopupDefaults.ItemPaddingHorizontal - pressInset,
                vertical = GlassPopupDefaults.ItemPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            startAction != null -> Box(Modifier.padding(end = GlassPopupDefaults.ItemIconGap)) { startAction() }
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = GlassPopupDefaults.ItemIconGap)
                    .size(
                        width = maxOf(GlassPopupDefaults.ItemIconSize, icon.defaultWidth),
                        height = maxOf(GlassPopupDefaults.ItemIconSize, icon.defaultHeight),
                    ),
                tint = titleInk,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = titleInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = summaryInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = GlassPopupDefaults.ItemIconGap)) { trailing() }
        } else {
            // The tick cell is reserved whether or not this row is a choice, so two rows'
            // titles share one column — the same reason `GlassPopupItem` draws a
            // transparent check on an unselected row.
            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = GlassPopupDefaults.ItemIconGap)
                    .size(GlassPopupDefaults.ItemTickSize),
                tint = if (selected == true && enabled) {
                    MiuixTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            )
        }
    }
}

/**
 * The fill under a pressed menu row.
 *
 * The library's `GlassPopupDefaults.itemPressedColor()`: a 10% wash of the theme's own
 * polarity — white on a dark scheme, black on a light one. It is a *contrast* rather than
 * a colour, which is what lets it read on a glass panel whose own tone is the blurred
 * page behind it.
 */
@Composable
private fun pressColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
    Color.White.copy(alpha = 0.1f)
} else {
    Color.Black.copy(alpha = 0.1f)
}
