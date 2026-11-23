/*
 * The furniture every settings page is built from — the Miuix half of it.
 *
 * Settings is the one destination where a *group* of peer rows is the whole page
 * (ARCHITECTURE §13), and Miuix already names every piece of that: `SmallTitle` is a
 * group's label, `Card` is its frame, and the `*Preference` components are the rows.
 * This file is therefore deliberately thin. It holds only what the library's
 * vocabulary does not cover — a value in a row's trailing slot, a paragraph inside a
 * card, a field with a supporting line, a confirmation in the error role, a swatch,
 * an avatar — and every one of those is drawn with Miuix's own text styles and colour
 * roles rather than with a palette of its own.
 *
 * ## What is gone, and why nothing here replaces it row by row
 *
 * The previous vocabulary (`PiRow`, `PiGroup`, `PiSectionHeader`, `PiRowDivider`,
 * `PiSwitchRow`, `PiChoiceRow`) drew a row as *mark + title + value + chevron*, and a
 * group as a rounded frame with a hairline between rows. Miuix's rows have neither a
 * leading mark nor a hairline: a group is a card whose rows touch, and the separation
 * is the card's own surface, which is why the pages no longer interleave dividers. The
 * marks were dropped rather than re-illustrated — Miuix's icon set has no glyph for
 * several of the concepts these pages name (a model profile, a relay protocol, an API
 * key), and re-deciding a glyph per row is a new visual vocabulary rather than a
 * migration onto the library's.
 *
 * ## Rows are the library's, not this file's
 *
 * A page builds its rows out of `BasicComponent`, `ArrowPreference`,
 * `SwitchPreference`, `SliderPreference` and `TextField` directly. The one
 * settings-specific row is `SettingsChoiceRow`, and it exists for a semantic the
 * library's radio row does not name: the chosen member of a set, marked with a tick
 * instead of a ring and an accent title (see its KDoc). A general wrapper over
 * `BasicComponent` would have to re-expose the library's parameters one at a time, and
 * the two would drift apart on the first change to either.
 *
 */
package pi.kit.mob.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.ui.kit.LocalPiBackdrop
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.LocalPiNavigationInset
import pi.kit.mob.ui.kit.LocalPiPageOverscroll
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiCustomDialog
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.PiSheetSection
import pi.kit.mob.ui.kit.PiTone
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piFieldHeightForPlaceholder
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun Modifier.settingsPageScroll(padding: PaddingValues, scroll: ScrollState): Modifier {
    val direction = LocalLayoutDirection.current
    return fillMaxSize()
        .padding(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
        )
        .piOverScroll(onOffsetChanged = LocalPiPageOverscroll.current)
        .verticalScroll(scroll)
        .padding(top = padding.calculateTopPadding() + 4.dp, bottom = 12.dp + LocalPiNavigationInset.current)
}

/**
 * A row's trailing value, in the quiet role Miuix gives a summary.
 *
 * Muted is what a value the *app* reports is drawn in; [emphasised] is a value the user
 * chose, which has to be tellable from one pi would have used anyway without a second
 * row saying it. [monospace] is for a version, a revision or a number — a machine string
 * read back against another machine string.
 *
 * One line and elided: a settings row's trailing slot takes its natural width out of
 * the title's share, so a value that grew to three lines would push the label it
 * belongs to off the row. The pages hand paths and commands in as a row's *summary*
 * instead, which has the full width under the title.
 */
@Composable
fun SettingsValue(
    text: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    emphasised: Boolean = false,
    danger: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MiuixTheme.textStyles.body2,
        color = when {
            danger -> MiuixTheme.colorScheme.error
            emphasised -> MiuixTheme.colorScheme.onSurface
            // A value the app reports is a summary, not an action label: the 40%
            // `onSurfaceVariantActions` role made every unselected value read as a
            // disabled control beside its live title.
            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        fontFamily = if (monospace) FontFamily.Monospace else null,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One member of a set, as a settings row: title, an optional sentence, and the
 * chosen member marked with the library's tick.
 *
 * Miuix's `RadioButtonPreference` is the same question but answers it with a radio
 * ring and tints the whole title in the accent. On a settings sheet that is two
 * signals too many: the reference gives a chosen row one mark at its trailing edge
 * and leaves the words in their normal ink, so a list can be scanned for names
 * before it is read for state. The check is `MiuixIcons.Ok` — the glyph the model
 * list already uses for "this one is in effect" — not a hand-drawn mark.
 *
 * [checkAtStart] exists for the one row shape whose trailing slot is already taken
 * by a destructive action (a saved workspace with its bin). Everywhere else the
 * tick sits at the end, where the reference puts it.
 *
 * The row performs `PiHaptics.choose()` itself when the tap changes the answer,
 * because `BasicComponent` — unlike the library's radio — has no haptic of its own.
 */
@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    checkAtStart: Boolean = false,
) {
    val haptics = LocalPiHaptics.current
    val mark: @Composable () -> Unit = {
        Icon(
            imageVector = MiuixIcons.Ok,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
    BasicComponent(
        modifier = modifier.semantics { this.selected = selected },
        title = title,
        summary = summary,
        startAction = if (checkAtStart && selected) {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mark()
                    startAction?.invoke()
                }
            }
        } else {
            startAction
        },
        endActions = {
            endActions?.invoke(this)
            if (!checkAtStart && selected) {
                Spacer(Modifier.width(8.dp))
                mark()
            }
        },
        onClick = {
            if (!selected) haptics.choose()
            onClick()
        },
        role = Role.RadioButton,
        holdDownState = holdDownState,
        enabled = enabled,
    )
}

/**
 * A group whose label carries a fact about the group: a count, a version.
 *
 * `PiSection` — and `SmallTitle` under it — is a label and nothing else, and the one
 * page that needs more is the model form, whose "Models" heading also states how many
 * are listed. The alternative was a row inside the group repeating the number, which
 * reads as a model called "2 configurations".
 *
 * The label is drawn with `SmallTitleDefaults.InsideMargin` and
 * `MiuixTheme.textStyles.subtitle` — the same 28/8 inset and the same face `SmallTitle`
 * uses — so this is that component with a trailing slot rather than a second heading
 * style. The frame under the label is [PiSection]'s, taken with no title of its own:
 * one group frame in the app rather than a second copy of it that would drift on the
 * first change to either.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (trailing == null) {
            SmallTitle(text = title, textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(SmallTitleDefaults.InsideMargin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.subtitle,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = trailing,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        PiSection(content = content)
    }
}

/**
 * A paragraph inside a card, for the sentence a row cannot carry.
 *
 * Miuix's rows hold a title and a one-line summary; a settings page sometimes has to
 * explain a mechanism rather than name a value, and that text is prose — it wraps, and
 * it belongs inside the card so that what it explains is the thing it sits under.
 * Drawn in the summary colour because it is explanation, not a control, and in the
 * error colour when what it explains is a thing that cannot be taken back.
 */
@Composable
fun SettingsNote(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = if (danger) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
    )
}

/**
 * Prose outside a card, on the column the group labels use.
 *
 * The licence and the credits are the long-form texts this draws — one page each, opened
 * from the About page's legal group — and the backup page's default-values note is the
 * other caller. A card around a paragraph whose only content is that paragraph is a
 * frame with nothing to frame. 28dp is `SmallTitle`'s own inset, so a paragraph and a
 * heading over it start on the same column. Extra room above it, because a paragraph
 * that begins on the line a heading ended leaves the heading reading as part of the
 * sentence.
 */
@Composable
fun SettingsParagraph(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 2.dp, bottom = 12.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/**
 * How far a field's box is pulled in from the card's edge.
 *
 * Miuix's `TextField` draws its own filled box, and with no wrapper the box fills the
 * card: its side walls then touch the card's edge, so the page reads as one stretched
 * control rather than as a field placed on a card. The box is therefore inset by
 * [FIELD_INSET] on each side, which leaves the card's fill visible around it and gives
 * the field's own rounded corners something to sit inside.
 *
 * **The library's inside margin is not reduced to pay for this.** Earlier versions
 * treated the two paddings as one budget and kept their sum constant: 6dp of inset
 * with the inside margin at 10dp, then 12dp with the inside margin floored at 8dp.
 * The outline moved each time, but the *label and the value* ended up 8dp from their
 * own wall and the box only 4dp from the card's edge above and below — the two things
 * a field must not do, measured on the search page's timeout field. The two numbers
 * answer different questions: [FIELD_INSET] is the card's frame around the control,
 * and `TextFieldDefaults.InsideMargin` is the control's padding around its text. Both
 * are therefore the library's own 16dp, and the field's text starts 32dp inside the
 * card instead of sharing a row's 16dp column — a field is a box, and its text belongs
 * at the box's padding, not at the label column of the rows around it.
 */
private val FIELD_INSET = 16.dp

/**
 * How far a field's box sits from the card's top and bottom edge.
 *
 * The same argument as [FIELD_INSET], on the other axis: a field is a surface of its
 * own and needs the card's fill visible above and below it. 4dp — the value this
 * carried while the horizontal padding was being argued over — left a box that read as
 * squeezed into the card's rounded corner; 12dp is a visible frame and still smaller
 * than the 16dp a row's own content sits at.
 *
 * A run of fields is separated by two of these (24dp), because each field owns its
 * share of the seam rather than a section spacing them. The supporting line stays
 * inside its own field's share, so the gap below a box is its hint, not white space.
 */
private val FIELD_VERTICAL_INSET = 12.dp

/**
 * The column a field's own text and its supporting line share.
 *
 * The label is drawn inside the field's box by the library; the supporting line is
 * drawn outside it by this wrapper. Its inset is therefore the box's [FIELD_INSET]
 * plus the field's own inside margin, stated once so the line cannot drift from the
 * label it explains.
 */
internal val SettingsFieldTextInset: Dp = FIELD_INSET + TextFieldDefaults.InsideMargin.width

/**
 * A settings field: Miuix's own, with its supporting line under it.
 *
 * The field carries [FIELD_VERTICAL_INSET] above and below and [FIELD_INSET] on each
 * side, so a lone field sits inside the group with the card's fill visible on all four
 * sides rather than against two of its edges. [supporting] is the sentence under the
 * box: Miuix's `TextField` has a floating label and no supporting slot, and that
 * sentence is help for *this* field, so it stays with it.
 *
 * [placeholder] is drawn under the box while the field is empty, beside [supporting]
 * rather than instead of it: a call site that passes both wants the sentence about the
 * field *and* an example of what goes in it, and the version that drew one or the other
 * silently dropped every example the model form passes.
 *
 * [password] exists because an API key must not be drawn in the clear; everything else
 * about the two fields is the same, which is why this is a parameter rather than a
 * second field at each call site.
 *
 * The focus ring is off here and everywhere else a field is drawn — `piFieldColors`
 * is the pair that does it, and its KDoc says why the ring cannot simply be hidden.
 */
@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    password: Boolean = false,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = FIELD_VERTICAL_INSET)) {
        TextField(
            cornerRadius = PiCorner.radius,
            value = value,
            onValueChange = onValueChange,
            label = label,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = if (monospace) {
                MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
            } else {
                MiuixTheme.textStyles.main
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = when {
                visualTransformation != null -> visualTransformation
                password -> PasswordVisualTransformation()
                else -> VisualTransformation.None
            },
            // The library's focus ring is off, and its caret is stated instead — see
            // `piFieldColors`'s KDoc for why the two are one decision.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            // The library's own inside margin, unsqueezed: the box is a control placed
            // on the card, and its text is what that control contains. See [FIELD_INSET]
            // for why the two paddings are no longer traded against each other.
            insideMargin = TextFieldDefaults.InsideMargin,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FIELD_INSET),
        )
        // Both lines, when both are given: `supporting` explains the field and
        // `placeholder` shows an example of its content, and the version that drew the
        // placeholder only in the supporting slot's absence silently dropped every
        // example the model form passes (its name, its base URL, pi's fallback number).
        val hint = when {
            value.isNotBlank() -> supporting
            else -> listOfNotNull(supporting, placeholder).joinToString(" ").ifEmpty { null }
        }
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SettingsFieldTextInset,
                        end = SettingsFieldTextInset,
                        top = 4.dp,
                        bottom = 0.dp,
                    ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * A message with one acknowledgement, and an optional body of lines under it.
 *
 * The settings pages' shape for an *event* that came back wrong — announced, then
 * dismissed — rather than a strip that stays on the page until something replaces it.
 *
 * The sentence is *not* the dialog's `summary`: the library draws a summary with no
 * height bound (`DialogContentLayout` measures it as one `Text`), so a pi stderr of ten
 * lines pushed the acknowledgement off the bottom of the panel and left a dialog that
 * could not be closed by its own button. It is the body's first block here, bounded and
 * scrolled like [lines], and the acknowledgement sits under both.
 *
 * [lines] is the installer's own output, one line per file a walk could not rewrite:
 * there is no ceiling on that count, so it is bounded too.
 *
 * The acknowledgement is [PiDialogAnswers]' single-answer form, which is the app's one
 * dialog-button shape. It used to pass a transparent container on the argument that a
 * filled answer beside the dialog's own card reads as a second panel; what that produced
 * was an acknowledgement that looked disabled next to the filled answers on the page
 * behind it, and two different weights for the same control depending on which page
 * raised the dialog.
 */
@Composable
fun SettingsAlert(
    show: Boolean,
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    lines: List<String> = emptyList(),
) {
    val backdrop = LocalPiBackdrop.current
    val dialogBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ALERT_BODY_MAX)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            if (lines.isNotEmpty()) {
                // Miuix's own `Card`, not a rounded box of this file's: 16dp is the
                // library's corner radius and `surfaceContainer` its panel role, and a
                // second pair of each beside them read as a different kind of surface.
                Card(
                    cornerRadius = PiCorner.radius,
                    colors = CardDefaults.defaultColors(color = PiTone.cardOnGlass()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            // Bounded, then scrolled inside the bound: a walk reports one
                            // line per file it could not rewrite and there is no ceiling
                            // on that count.
                            .fillMaxWidth()
                            .heightIn(max = ALERT_LINES_MAX)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        lines.forEach { line ->
                            Text(
                                text = line,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            PiDialogAnswers(
                dismiss = confirm,
                onDismiss = onConfirm,
                glass = backdrop != null,
            )
        }
    }
    PiCustomDialog(
        show = show,
        modifier = modifier,
        title = title,
        onDismissRequest = onConfirm,
    ) {
        dialogBody()
    }
}

/** How tall a dialog's error lines may get before they scroll: fifteen lines. */
private val ALERT_LINES_MAX = 240.dp

/** How tall a dialog's sentence may get before it scrolls: about ten lines. */
private val ALERT_BODY_MAX = 240.dp

/** The spinner a run shows in a row: 20dp, the library's own circular size. */
private val WORKING_SIZE = 20.dp

/**
 * A colour a settings row offers, as a circle.
 *
 * The ring is the *selection* and the fill is the colour, which is why a selected
 * swatch is drawn inside a larger circle of `primary` rather than with a tick over it:
 * eight of these in a row are read as a palette, and a tick on top of one of them hides
 * the colour it is on.
 */
@Composable
fun SettingsSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    /** The colour's spoken name; a swatch has no text of its own. */
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    enabled: Boolean = true,
) {
    val haptics = LocalPiHaptics.current
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
            )
            // `selectable` rather than `clickable`: a set of colours is a radio group, so
            // the chosen state and the role are what a screen reader has to hear beside
            // the name. The circle already chosen changes nothing.
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = {
                    // `choose()`: a set's member becoming the chosen one, which a Miuix
                    // radio would buzz for itself.
                    if (!selected) haptics.choose()
                    onClick()
                },
            )
            .semantics { contentDescription = label }
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * What a run in flight says inside a row: a spinner, and optionally the word for it.
 *
 * [label] is null in a row whose own control the spinner replaces — a spinner where a
 * button was is that button saying it is busy — and named in a strip under a group,
 * which has no control to replace.
 */
@Composable
fun SettingsWorking(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(size = WORKING_SIZE)
        if (label != null) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/**
 * One action at the end of a row: "restart", "run", "ask".
 *
 * Miuix's `TextButton` with this app's own padding and no container: the library's
 * default inside margin (16dp horizontal, 13dp vertical) around a 40dp minimum makes a
 * control that is nearly as wide as the value it sits beside, so three of them on one
 * page read as a toolbar of buttons rather than as each row's own verb. 12/6 keeps the
 * 40dp touch target and gives the label back to the row, and the accent ink without a
 * fill keeps it a verb rather than competing with the row's title.
 *
 * There is no destructive variant. Every action that cannot be taken back is a question
 * first (`PiConfirm(destructive = true)`), and the confirming word of a question is
 * where the error role belongs — a row action that is itself red would be a second,
 * weaker copy of that warning.
 */
@Composable
fun SettingsAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        cornerRadius = PiCorner.radius,
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        // A row action is a verb beside its value, not a filled button competing with
        // the row's title. The filled primary pill this used to draw was the loudest
        // control on the Agent page — two blue pills in a card of plain rows — and the
        // reference's answer to the same slot is accent text with no container. The
        // 40dp touch target stays; only the fill goes.
        colors = ButtonDefaults.textButtonColors(
            color = Color.Transparent,
            disabledColor = Color.Transparent,
            textColor = MiuixTheme.colorScheme.primary,
            disabledTextColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        ),
    )
}

/**
 * One choice a [SettingsPickerSheet] offers.
 *
 * [description] is the line under the label, and it is optional: a picker of three
 * workflow names needs to say what each one does, while a picker of thirty provider ids
 * needs the line to say what the id costs or needs. [id] is both the identity and the
 * sort key the caller builds the list with; the label is what is drawn, because a list of
 * raw ids would be a list of words in nobody's language.
 */
data class SettingsPick(
    val id: String,
    val label: String,
    val description: String? = null,
)

/**
 * A **long** list of choices, in Miuix's sheet.
 *
 * This is the control for a list that needs a *filter*: pi ships more than thirty provider
 * ids, the catalogue brings back a model list of unknown length, and a panel sized for a
 * handful cannot carry a search field. The row on the page states the current value and the
 * list is one tap behind it.
 *
 * ## The short list is a dropdown row, and the split is the length
 *
 * A value with a few alternatives is `PiDropdownPreference` — the kit's glass dropdown row,
 * used directly at the call site the way `ArrowPreference` is. It states the value at the
 * row's end, opens a glass panel anchored at the row with no scrim, and is what every MIUI
 * settings list gives a choice like this: the language, the theme, the launcher mark, the
 * chat web default, the wire protocol.
 *
 * The line between the two is the length of the list, not the page it is on: **a list a
 * reader has to search is this sheet, and a list a reader reads is a dropdown.** The panel
 * scrolls, so a dozen rows are fine in it; thirty providers with descriptions are not, and
 * that is what [searchHint] exists for.
 *
 * The rows are `SettingsChoiceRow`s when the list *has* a current value, and
 * `ArrowPreference`s when it does not: the tick is the mark for "one of these is in
 * effect", which is the question the provider picker asks, while the add-an-option and
 * fetched-model lists ask "which of these do I want" and a column of set marks would
 * answer that with the other question's mark. [searchHint] adds a filter field above the
 * list, for a list of *things that exist* rather than a handful of alternatives.
 *
 * An empty filter result draws no card at all. That is a gap rather than a decision: the
 * catalog has no "no matches" member on `Strings.Settings` — the sentence belongs to
 * `Strings.Files`, which is the file list's — and inventing one here is not this file's
 * call; the alternative was a card with nothing in it, which reads as a rendering fault.
 */
@Composable
fun SettingsPickerSheet(
    show: Boolean,
    title: String,
    options: List<SettingsPick>,
    selectedId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    footnote: String? = null,
    searchHint: String? = null,
) {
    // Keyed on `show`, so a sheet opened again starts with the whole list rather than with
    // the last search still hiding it. The filter is a question asked *now* — it is not a
    // preference, and a picker that remembered it would look like a list that had lost rows.
    //
    // The filter is the beta02 `GlassTextField`: this sheet is a glass surface, and a
    // value-bound `TextField` with the opaque secondary fill was a solid grey bar pasted
    // on the material. The state-based field is also the API the library asks new fields
    // to start from.
    val queryState = remember(show) { TextFieldState() }
    val needle = queryState.text.toString().trim()
    val shown = if (needle.isEmpty()) {
        options
    } else {
        options.filter { option ->
            option.label.contains(needle, ignoreCase = true) ||
                option.id.contains(needle, ignoreCase = true) ||
                option.description?.contains(needle, ignoreCase = true) == true
        }
    }

    PiSheet(show = show, title = title, onDismiss = onDismiss) {
        // The sheet's own edge is the window's: the last row clears the gesture strip.
        Column(Modifier.fillMaxWidth().padding(bottom = piSheetBottomInset())) {
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                )
            }
            if (searchHint != null) {
                GlassTextField(
                    state = queryState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .piFieldHeightForPlaceholder(),
                    label = searchHint,
                    useLabelAsPlaceholder = true,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    cornerRadius = PiCorner.radius,
                    // The search field is the one control a long picker wears above its
                    // list: the magnifier says what the field is for before the hint is
                    // read, and the clear mark appears only while there is something to
                    // clear. The field is the glass one — this sheet is a glass surface —
                    // with the focus ring off for the same reason every other field hides
                    // it.
                    leadingIcon = {
                        Icon(
                            imageVector = MiuixIcons.Search,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            // The same inset Miuix's own SearchBar gives its magnifier.
                            // A field with a leading icon drops the field's 16dp start
                            // padding entirely — the icon's own box is the whole start
                            // gutter — so the 4dp this used to carry put the glyph
                            // against the wall while the text beside it kept a wide gap.
                            modifier = Modifier
                                .padding(
                                    start = SearchBarDefaults.LeadingIconStartPadding,
                                    end = SearchBarDefaults.LeadingIconEndPadding,
                                )
                                .size(20.dp),
                        )
                    },
                    trailingIcon = if (needle.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { queryState.setTextAndPlaceCursorAtEnd("") },
                                modifier = Modifier.padding(
                                    start = SearchBarDefaults.TrailingIconStartPadding,
                                    end = SearchBarDefaults.TrailingIconEndPadding,
                                ),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Clear,
                                    contentDescription = LocalStrings.current.common.clear,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    colors = GlassTextFieldDefaults.textFieldColors(
                        borderColor = Color.Transparent,
                    ),
                    cursorBrush = piFieldCursor(),
                )
            }
            if (shown.isEmpty() && needle.isNotEmpty()) {
                SettingsNote(LocalStrings.current.settings.pickNothingMatches(needle))
            }
            if (shown.isNotEmpty()) {
                PiSheetSection {
                    // The list scrolls *inside* the card, so the card's own rounded clip is
                    // what the rows are cut by: a scroll modifier on the card itself would
                    // put the viewport outside the surface, and the corners would scroll
                    // away with the content.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // Bounded, then scrolled: the sheet caps its own height at the
                            // status bar, and a list taller than that is clipped rather than
                            // scrollable unless it scrolls itself.
                            .heightIn(max = PICKER_LIST_MAX)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        shown.forEach { option ->
                            // A check only when the list *has* a current value. The sheet
                            // is used for two different questions: "which of these is in
                            // effect" (the provider, the workflow, the API type) and
                            // "which of these do I want to add" (the extension's options,
                            // a fetched model), where `selectedId` is null and a set mark
                            // would tick an answer nobody gave. The row that opens
                            // something is `ArrowPreference`, so the add lists use that.
                            if (selectedId == null) {
                                ArrowPreference(
                                    title = option.label,
                                    summary = option.description,
                                    onClick = { onPick(option.id) },
                                )
                            } else {
                                SettingsChoiceRow(
                                    title = option.label,
                                    summary = option.description,
                                    selected = option.id == selectedId,
                                    onClick = { onPick(option.id) },
                                )
                            }
                        }
                    }
                }
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

/** How tall a picker's list may get before it scrolls: about eight rows. */
private val PICKER_LIST_MAX = 420.dp

/**
 * A page's own action: the foot of a form, the pair under a list.
 *
 * [primary] is Miuix's filled-primary button — the one action a page is asking the reader
 * to take — and the default is its secondary fill. One primary per screen is the rule the
 * old design system stated and this keeps: a form's foot has Save primary and Cancel
 * secondary, and a list's foot has no primary at all.
 *
 * The inside margin is the library's own 16/13; it is delegated rather than restated
 * because a filled button's padding is the button's shape, unlike [SettingsAction]'s,
 * which sits inside a row and has to leave the row's width for its own label.
 *
 * [busy] draws a spinner *inside* the button, before its label, for the one action on
 * the model form that waits on the network. The button's width is the row's half, so
 * the mark costs the label nothing and the two buttons beside each other do not move
 * when a run starts — which is what the text-swapping this replaces did ("fetch" became
 * "fetching…" and pushed its neighbour sideways).
 */
@Composable
fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    busy: Boolean = false,
) {
    Button(
        cornerRadius = PiCorner.radius,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = if (primary) {
            ButtonDefaults.buttonColorsPrimary()
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                size = BUTTON_SPINNER_SIZE,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    // The label's own ink: white on the filled primary, the surface ink
                    // on the secondary fill, and the *disabled* pair when the button is
                    // refusing taps — which it does for the length of the run.
                    foregroundColor = when {
                        !enabled && primary -> MiuixTheme.colorScheme.disabledOnPrimaryButton
                        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        primary -> MiuixTheme.colorScheme.onPrimary
                        else -> MiuixTheme.colorScheme.onSurface
                    },
                    backgroundColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(BUTTON_SPINNER_GAP))
        }
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}

/** The spinner inside a busy [SettingsButton], and the gap between it and the label. */
private val BUTTON_SPINNER_SIZE = 16.dp
private val BUTTON_SPINNER_GAP = 8.dp

