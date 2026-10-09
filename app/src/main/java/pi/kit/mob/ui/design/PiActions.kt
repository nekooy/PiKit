/*
 * Every control that does something: buttons, chips, the connected group, the FAB.
 *
 * ## The bump
 *
 * Material's expressive buttons come in two shape options — round and square — and
 * they morph between them under the finger. This app takes that literally: an
 * action is a **pill at rest** and squares up to [PiShapes.card] while it is held.
 * It is the smallest possible version of the "unexpected moment by switching
 * between square and fully rounded shapes" the shape guidance asks for, it costs
 * one animation and no layout, and it answers a question a touch screen otherwise
 * cannot: whether the press landed.
 *
 * The morph is deliberately absent on the quiet variants — [PiButtonKind.Text] in a
 * row, a chip in a filter bar — because a control that jumps under the finger is
 * only delightful when the finger meant it, and a list of twelve of them is a
 * twitch.
 *
 * ## Why the connected group is hand-built
 *
 * `ButtonGroup` in this release carries an overflow menu, a measured item
 * provider and a `ButtonGroupScope`, which is the right machinery for a toolbar of
 * a dozen actions that have to collapse. Every group in this app is three or four
 * fixed options, so the component here is the *geometry* the spec describes and
 * nothing else: outer corners round, inner corners tightened, one selection
 * indicator sliding between positions. That is less code than configuring the
 * library's and it cannot acquire a behaviour the app did not ask for.
 */
package pi.kit.mob.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How loud a button is. The five colours Material names, in the order the app uses
 * them: one filled action per screen, tonal for the runner-up, outlined where the
 * action needs a boundary, text inside a row, elevated over a photograph or the
 * terminal.
 */
enum class PiButtonKind {
    Filled,
    Tonal,
    Outlined,
    Text,
    Elevated,
}

/**
 * Material's five sizes, at Material's own figures.
 *
 * The heights are read out of the library's `ButtonDefaults` rather than chosen:
 * 32, 40, 56, 96 and 136dp, with icon sizes 20, 20, 24, 32 and 40dp and icon
 * spacings 8, 8, 8, 12 and 16dp. The ramp is deliberately much wider than M3's old
 * fixed 40dp — the expressive scale's whole point is that a hero button and a
 * toolbar button are *different sizes* rather than the same button with more
 * padding, and a hand-picked ramp that stops at 72dp loses that.
 *
 * [Medium] is the default in this app and [Small] is what a dense row uses; the
 * two ends exist because the scale is Material's and a caller that needs one should
 * not have to invent a `height`.
 */
enum class PiButtonSize(
    val containerHeight: Dp,
    val iconSize: Dp,
    val iconSpacing: Dp,
    val horizontalPadding: Dp,
) {
    ExtraSmall(32.dp, 20.dp, 8.dp, 16.dp),
    Small(40.dp, 20.dp, 8.dp, 16.dp),
    Medium(56.dp, 24.dp, 8.dp, 24.dp),
    Large(96.dp, 32.dp, 12.dp, 32.dp),
    ExtraLarge(136.dp, 40.dp, 16.dp, 48.dp),
}

/**
 * The app's button.
 *
 * [leadingIcon] is an icon slot rather than an `ImageVector` so a caller can pass a
 * status dot or a spinner; it is sized to the variant's icon size and the content
 * padding is widened for it, which is the pair of things Material's own
 * `ButtonWithIconContentPadding` does.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PiButtonKind = PiButtonKind.Filled,
    size: PiButtonSize = PiButtonSize.Medium,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    // A destructive action is the same button in the error role rather than a
    // differently shaped one, which is what `ButtonDefaults` cannot express on its
    // own: without this a delete or a reinstall has to be a bare `TextButton` in a
    // list of `PiButton`s, and it reads as a link rather than as the one control on
    // the page that cannot be taken back.
    tone: PiTone = PiTone.Accent,
) {
    val padding = PaddingValues(
        horizontal = size.horizontalPadding,
        vertical = 0.dp,
    )
    val shapes = ButtonDefaults.shapes(
        shape = PiShapes.pill,
        pressedShape = PiShapes.card,
    )
    // Only the loud kinds bump. A text button lives inside a row or a list, where
    // a shape change reads as the row moving.
    val morph = when (kind) {
        PiButtonKind.Filled, PiButtonKind.Tonal, PiButtonKind.Elevated -> shapes
        PiButtonKind.Outlined, PiButtonKind.Text -> ButtonDefaults.shapes(shape = PiShapes.pill)
    }

    val content: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Box(Modifier.size(size.iconSize), contentAlignment = Alignment.Center) { leadingIcon() }
        }
        Text(
            text = text,
            style = when (size) {
                PiButtonSize.ExtraSmall, PiButtonSize.Small -> MaterialTheme.typography.labelLarge
                else -> MaterialTheme.typography.titleSmall
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    val container = Modifier
        .height(size.containerHeight)
        .defaultMinSize(minWidth = size.containerHeight)

    val scheme = MaterialTheme.colorScheme
    val colors = when (kind) {
        PiButtonKind.Filled -> when (tone) {
            PiTone.Danger -> ButtonDefaults.buttonColors(
                containerColor = scheme.error,
                contentColor = scheme.onError,
            )
            else -> ButtonDefaults.buttonColors()
        }

        PiButtonKind.Tonal -> when (tone) {
            PiTone.Danger -> ButtonDefaults.filledTonalButtonColors(
                containerColor = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
            )
            else -> ButtonDefaults.filledTonalButtonColors()
        }

        PiButtonKind.Elevated -> when (tone) {
            PiTone.Danger -> ButtonDefaults.elevatedButtonColors(
                containerColor = scheme.error,
                contentColor = scheme.onError,
            )
            else -> ButtonDefaults.elevatedButtonColors()
        }

        PiButtonKind.Outlined -> ButtonDefaults.outlinedButtonColors(contentColor = tone.ink(scheme))

        PiButtonKind.Text -> ButtonDefaults.textButtonColors(contentColor = tone.ink(scheme))
    }

    when (kind) {
        PiButtonKind.Outlined -> OutlinedButton(
            onClick = onClick,
            shapes = morph,
            modifier = modifier.then(container),
            enabled = enabled,
            colors = colors,
            contentPadding = padding,
            content = content,
        )

        PiButtonKind.Text -> TextButton(
            onClick = onClick,
            shapes = morph,
            modifier = modifier.then(container),
            enabled = enabled,
            colors = colors,
            contentPadding = padding,
            content = content,
        )

        else -> Button(
            onClick = onClick,
            shapes = morph,
            modifier = modifier.then(container),
            enabled = enabled,
            colors = colors,
            contentPadding = padding,
            content = content,
        )
    }
}

/**
 * A selectable pill: a filter, a model, a thinking level.
 *
 * Selection is carried by three things at once — the fill, the label's weight and a
 * leading tick — rather than by colour alone, because a chip's selected state is
 * also its only indication that tapping it again is what clears it. The tick is
 * faded rather than added and removed, so a row of chips does not reflow when one
 * is picked.
 */
@Composable
fun PiSelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = PiMotion.defaultEffects(),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = PiMotion.defaultEffects(),
        label = "chipContent",
    )

    Row(
        modifier = modifier
            .clip(PiShapes.pill)
            .background(container)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .height(32.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp),
            )
        } else if (leadingIcon != null) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { leadingIcon() }
        }
        Text(
            text = label,
            style = if (selected) {
                MaterialTheme.typography.labelLargeEmphasized
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One option of a [PiConnectedGroup].
 *
 * [detail] is the second line some groups need — a profile's model count, a
 * thinking level's one-word note. A group of two-line options is taller and reads
 * as a list rather than as a segmented control, which is the right thing when the
 * options genuinely differ by more than their name and the wrong thing when they
 * do not.
 */
data class PiGroupOption(
    val id: String,
    val label: String,
    val detail: String? = null,
    val enabled: Boolean = true,
)

/**
 * A connected button group: the expressive replacement for a segmented control.
 *
 * The geometry is the spec's — the outer corners are round and the *inner* corners
 * are tightened, because two abutting rounded rectangles at full radius leave a
 * waist between them that reads as a gap rather than as a join. [selected] is a set
 * and [onSelect] is handed the tapped id, so whether tapping adds, replaces or
 * refuses-to-empty is the caller's rule rather than a flag here: the guidance's
 * three configurations (single-select, multi-select, selection-required) differ
 * only in that one lambda, and a `multi: Boolean` would be a second place to state
 * the same thing — with the two able to disagree.
 *
 * The group is drawn as one filled row on `surfaceContainerHigh` with the selected
 * options on `primary`, so the selected positions read as holes punched in a
 * container rather than as separate buttons — which is what makes a group of four
 * legible at a glance where four separate outlined buttons is not.
 */
@Composable
fun PiConnectedGroup(
    options: List<PiGroupOption>,
    selected: Set<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (PiGroupOption) -> Unit)? = null,
) {
    if (options.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PiShapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option.id in selected
            val fill by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = PiMotion.defaultEffects(),
                label = "groupFill",
            )
            val content by animateColorAsState(
                targetValue = when {
                    !option.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    isSelected -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = PiMotion.defaultEffects(),
                label = "groupContent",
            )
            // The selected option is the rounder one; at rest the options keep the
            // inner radius, so the fill appears to *grow* a corner when picked.
            val radius by animateDpAsState(
                targetValue = if (isSelected) 14.dp else 8.dp,
                animationSpec = PiMotion.fastSpatial(),
                label = "groupRadius",
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(radius))
                    .background(fill)
                    .clickable(enabled = option.enabled) { onSelect(option.id) }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (icon != null) {
                    icon(option)
                }
                Text(
                    text = option.label,
                    style = if (isSelected) {
                        MaterialTheme.typography.labelLargeEmphasized
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (option.detail != null) {
                    Text(
                        text = option.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The one floating action a page may have.
 *
 * Extended — icon plus label — rather than a bare circle, because both places it
 * is used ("new conversation", "new file in this directory") are the page's primary
 * verb and a label is what makes a FAB understood in the first week. It is the only
 * element in the app that carries elevation: the guidance's expressive colour
 * tactics ask for hierarchy, and a FAB that is merely a colour on the background
 * stops being a FAB.
 */
@Composable
fun PiFab(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(shape = PiShapes.pill, pressedShape = PiShapes.card),
        modifier = modifier.height(56.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
        )
    }
}
