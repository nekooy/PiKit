/*
 * The app's one selection vocabulary.
 *
 * Every picker in PiKit used to be a `DropdownMenu` anchored to a row, written
 * out separately at each call site: language and thinking level in Settings, the
 * provider and the fetched model list in the model form, the terminal's open
 * sessions in the terminal header. They rendered as 2014 context menus — nine
 * items of language in a floating dropdown, a 280dp-wide menu that cut a long
 * model id off, nothing marking the current value but a bare tick glyph, and a
 * fetched-model list of several hundred entries in a dialog that could not
 * scroll.
 *
 * This file replaces all of that with one modal body ([PickerBody], on the layer
 * in `Sheets.kt`) and one way to open it:
 *
 *  - [PickerRow], a settings-style row that is the row *and* the sheet, for the
 *    Settings pages.
 *
 * A second, compact chip control lived here for a while, for rows with no space
 * for a settings row — the controls above the composer. The composer has its own
 * chip (`ChatScreen.ControlChip`) and nothing else ever called this one, so it was
 * removed rather than left as a second vocabulary for the same job.
 *
 * Everything user-visible — the sheet's title, every row's label and
 * description, every content description — is passed in by the caller, because
 * the app's text lives in the three catalogs under `locales/` and nothing here
 * may invent a string. There is no dismiss button for the same reason: the
 * scrim, the back gesture and a downward swipe all close the sheet without one.
 *
 * Nothing here is a window any more. `Sheets.kt` says why that mattered; the
 * short version is that a `ModalBottomSheet` is a dialog laid over the activity,
 * and a dialog closes the keyboard and outlives the state that opened it.
 */
package pi.kit.mob.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.PiLaunchOptions
import pi.kit.mob.ui.settings.SettingsRow

/**
 * One choice in a [PickerBody].
 *
 * [id] is whatever the caller's own state stores — `Lang.code`, a provider id, a
 * terminal session id, a model id — and the sheet never reads it except to
 * compare it with `selectedId`. It has to be unique within one option list: two
 * entries with the same id are two rows the caller cannot tell apart when one is
 * chosen.
 */
data class PickerOption(
    val id: String,
    val label: String,
    /** A second, muted line: what this choice does, or its current value's detail. */
    val description: String? = null,
    val enabled: Boolean = true,
    /** A leading glyph, for pickers whose entries are visually distinguishable. */
    val leading: (@Composable () -> Unit)? = null,
    /**
     * A control drawn at the row's trailing edge, after the label.
     *
     * For a row that has an action of its own beside the choice it represents: the
     * terminal picker is the only one — tapping a row switches to that shell and
     * the ✕ beside it closes that shell. The control is the caller's, so its glyph
     * and its wording are still the caller's catalog strings.
     *
     * Tapping it does not also choose the row: `selectable` is on the row and the
     * child's own pointer input consumes the down first, which is what keeps a
     * close button from switching to the session it just closed.
     */
    val trailing: (@Composable () -> Unit)? = null,
    /**
     * The heading this option sits under, when the list is more than one list.
     *
     * Options are grouped by *runs* of equal [group], in the order the caller
     * passes them, and a heading is drawn where the value changes — never
     * twice for the same group, and never for a group that is not contiguous. The
     * model picker is the reason: its entries are providers, and a provider with
     * four models should say its name once rather than four times.
     */
    val group: String? = null,
)

/**
 * The thinking levels as picker options.
 *
 * Built once for the two places that offer them — the composer's chip and the
 * model page's row — because the label, the explanation and the footnote are the
 * same three things, and two copies would drift on the first rewording. It is not a
 * `Composable`: it is a list of data, and both callers hand it to [PickerBody].
 *
 * The **label is the level's id**, not a translation of it: `off`, `minimal`, `low`,
 * `medium`, `high`, `xhigh`, `max` are the strings `set_thinking_level` carries, the
 * strings `--thinking` accepts and the strings pi's own model list prints. The
 * *description* is the interface's language, which is where the meaning belongs.
 *
 * [available] is what a running pi says the *current model* supports, and it is
 * offered instead of pi's seven whenever it is known. pi clamps a level the model
 * does not have, silently and forwards (`clampThinkingLevel`), so a list of seven
 * for a model with four is a menu whose rows do not do what they say: tapping
 * `medium` on a DeepSeek model leaves `high` on the chip. Null or empty — no agent
 * yet — falls back to the seven, which is also what pi itself answers when it has no
 * model.
 */
fun thinkingLevelOptions(text: Strings, available: List<String>? = null): List<PickerOption> =
    (available?.takeIf { it.isNotEmpty() } ?: PiLaunchOptions.THINKING_LEVELS).map { level ->
        PickerOption(
            id = level,
            label = level,
            description = text.chat.thinkingLevelDescription(level),
        )
    }

/**
 * The picker's footnote: why the menu is shorter than pi's seven, or why it is not a
 * menu at all.
 *
 * One function rather than the expression each caller used to repeat, and the second
 * case is why. pi answers `["off"]` for a model without reasoning support
 * (`getSupportedThinkingLevels`), and a one-item list went through the "a model that
 * offers fewer levels" branch: the sheet explained that `off` was "the only level
 * this model has", which is a sentence about a choice where there is none. The
 * model does not reason; that is the fact, and [Strings.Chat.thinkingDisabled] is
 * the sentence for it.
 *
 * Null when there is nothing to explain: all seven offered, or no agent has answered
 * yet and the fallback list is pi's own seven.
 */
fun thinkingLevelFootnote(text: Strings, available: List<String>?): String? {
    val levels = available.orEmpty().filter { it.isNotBlank() }
    if (levels.isEmpty()) return null
    if (levels.size == 1 && levels.first() == "off") return text.chat.thinkingDisabled
    if (levels.size >= PiLaunchOptions.THINKING_LEVELS.size) return null
    return text.chat.thinkingModelNote(levels.joinToString(", "))
}

/**
 * A modal list of choices, with the selected one marked. This is the *only*
 * selection UI in the app.
 *
 * The body is drawn inside the root's one modal layer (`Sheets.kt`), which brings
 * the scrim, the drag gesture, the back handling and the entrance animation with
 * it; this is the list. A body rather than a whole sheet because the layer owns
 * the panel: two sheets that drew their own panel would be two panels that drift
 * apart on the first change to either.
 *
 * The sheet is deliberately quiet. The selected row carries a tick and a
 * [MaterialTheme.colorScheme.primary] label and nothing else; every other row is
 * plain `onSurface` on the sheet's own `surfaceContainerLow`. The first draft
 * filled the selected row with `primaryContainer`, which in this palette is
 * `#DCE6F2` in light mode against a `#DCE3EB` sheet — a 3% difference that read
 * as a rendering artifact rather than as a selection, which is why the marking is
 * a tick and a colour rather than a fill.
 *
 * Every exit — the scrim, the system back gesture, a downward swipe, and picking
 * a row — goes through [SheetHost.dismiss]. The list scrolls once it is taller
 * than [SHEET_LIST_FRACTION] of the height the sheet may take, so a long picker
 * covers the page behind it for a moment instead of for good.
 *
 * @param title the sheet's heading; the caller's catalog string for the thing
 *   being chosen, e.g. the language row's own title.
 * @param selectedId the chosen [PickerOption.id], or null when nothing is chosen
 *   yet — which draws no tick and no error.
 * @param onPick called once, on the tap, with the id of the row that was tapped.
 *   It runs *before* the panel has finished leaving: a control that acts a
 *   quarter of a second after it is pressed reads as a dropped tap. Rows whose
 *   option is disabled are not tappable at all, so this is never called with one.
 * @param footnote a muted paragraph under the list for an explanatory note.
 * @param action an optional control drawn under the list, above the footnote: an
 *   action that belongs to the whole list rather than to one row.
 * @param searchHint when set, a filter field is drawn above the list and the hint
 *   is its placeholder. For a picker whose list is long enough that finding a row
 *   is the problem rather than choosing between rows — the web-access page's
 *   eighty options are the case it was added for. Filtering matches the label, the
 *   id and the description, case-insensitively.
 */
@Composable
internal fun PickerBody(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    footnote: String? = null,
    action: (@Composable () -> Unit)? = null,
    searchHint: String? = null,
) {
    val host = LocalSheetHost.current

    SheetScaffold(title) {
        // Local to the sheet: a filter is a question you are asking right now, and
        // one that came back set the next time the sheet was opened would hide the
        // list the user came to see.
        var query by remember(searchHint) { mutableStateOf("") }
        val needle = query.trim()
        val shown = if (searchHint == null || needle.isEmpty()) {
            options
        } else {
            options.filter { option ->
                option.label.contains(needle, ignoreCase = true) ||
                    option.id.contains(needle, ignoreCase = true) ||
                    option.description?.contains(needle, ignoreCase = true) == true
            }
        }

        if (searchHint != null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(searchHint) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                // A key name is machine text: an autocorrected `xaiApiKey` is a
                // filter that silently matches nothing.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }

        // 60% of the height the sheet is allowed to take. Past that the list
        // stops being something you glance at and becomes a page, and the
        // sheet hides the screen it is being chosen from. This is a ceiling
        // and not a height: a two-item picker keeps its own 120dp.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * SHEET_LIST_FRACTION),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Keyed on the index *and* the id: [PickerOption.id] is only
                // unique within one source. The fetched-model list holds the
                // same model id twice when pi's catalog and the provider's own
                // list both offer it — the dialog this replaces keyed those
                // rows by source and id for exactly that reason — and a key of
                // the bare id would throw "Key was already used" on a list
                // that is otherwise correct.
                //
                // A run of options that share a [PickerOption.group] gets one
                // heading in front of it, emitted here rather than by the caller
                // so that a grouped list and a flat one cannot space their rows
                // differently.
                shown.forEachIndexed { index, option ->
                    val group = option.group
                    if (!group.isNullOrBlank() && group != shown.getOrNull(index - 1)?.group) {
                        item(key = "group:$index:$group") { PickerGroupHeading(group) }
                    }
                    item(key = "$index:${option.id}") {
                        PickerSheetRow(
                            option = option,
                            selected = option.id == selectedId,
                            enabled = option.enabled,
                            onChoose = {
                                host.dismiss()
                                onPick(option.id)
                            },
                        )
                    }
                }
            }
        }
        if (action != null) {
            action()
        }
        if (!footnote.isNullOrBlank()) {
            Text(
                text = footnote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
        }
    }
}

/**
 * A heading over a run of [PickerOption]s that share a group.
 *
 * The same shape as `SettingsSection`'s label — a small uppercase muted line —
 * so a grouped picker reads like the page the sheet was opened from. It is a
 * heading and not a selectable row: it carries no tick column and no ripple, or
 * a group name would look like one more thing to choose.
 */
@Composable
private fun PickerGroupHeading(label: String) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Opens [PickerBody] in the app's modal layer.
 *
 * The key is the title: one picker is open at a time, and a sheet that is asked
 * for twice with the same title is the same sheet — re-showing it would restart
 * an entrance animation the user is already watching.
 */
internal fun SheetHost.showPicker(
    title: String,
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    footnote: String? = null,
    searchHint: String? = null,
) {
    show(
        Sheet(key = "picker:$title") {
            PickerBody(title, options, selectedId, onPick, footnote, searchHint = searchHint)
        },
    )
}

/**
 * A modal list of rows that are read rather than chosen.
 *
 * The shape every bottom sheet in the app shares with [PickerBody] is not a
 * coincidence and not a copy: a user who has just learned that a sheet means
 * "here are the things you can pick" must not be shown a second, differently
 * spaced sheet for the things they cannot. The grabber, the title, the hairline
 * and the row height are one implementation ([SheetScaffold] plus
 * [ReadOnlySheetRow] on the layer's panel); only the marking differs.
 *
 * Used for the conversation's numbers and for pi's command list, where the rows
 * are facts and anything tappable is an action rather than a selection.
 *
 * The content is a `LazyColumn`, not a `Column`: the command list is as long as
 * the extensions installed, and a sheet whose body is taller than the sheet is a
 * sheet whose last rows cannot be reached at all. It stops growing at
 * [SHEET_LIST_FRACTION] of the height the sheet may take, the same ceiling
 * [PickerBody] uses, so the two feel like one control.
 */
@Composable
internal fun ReadOnlyBody(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    SheetScaffold(title) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * SHEET_LIST_FRACTION),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content,
            )
        }
    }
}

/**
 * One row of a [ReadOnlyBody]: a label and a value, or a tappable action.
 *
 * [onClick] is what makes a row an action. A row that acts carries a trailing
 * chevron, because a ripple is not an affordance: it only exists *after* the tap
 * it was supposed to invite, and on a sheet whose other rows are inert facts there
 * is nothing else on screen to tell the two apart. The chevron is the same glyph
 * and the same 20dp a settings row that opens something uses.
 *
 * Tapping an action closes the sheet, and the two happen together: the row is a
 * menu entry, and a menu that stays open behind the thing it just did is a menu
 * the user has to dismiss by hand. The click is not even *installed* while the
 * sheet is on its way out — see [SheetHost.isOpen] — so a tap aimed at the page
 * underneath a leaving sheet reaches the page rather than being eaten by a row
 * that no longer exists.
 *
 * [description] may be blank, in which case the row shows its label and its
 * [value] only — used where a fact has no current value.
 *
 * A description wraps to as many lines as it needs and is never ellipsised. It is a
 * sentence telling the reader what the row does or what a command is for, and a
 * sentence cut off at its most informative clause is worse than a taller row — which
 * is the "小字没显示完全" report this cap was. The one caller that used to ask for more
 * than the default (`descriptionLines = 4`, the composer's `!` note) was the same
 * complaint arriving through a parameter.
 */
@Composable
fun ReadOnlySheetRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    monospaceValue: Boolean = false,
    valueLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val host = LocalSheetHost.current
    val live = host.isOpen

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (onClick != null && live) {
                    Modifier.clickable {
                        host.dismiss()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .heightIn(min = READ_ONLY_ROW_MIN_HEIGHT)
            // End padding is larger than the start's because the value is
            // end-aligned: measured, a value ending at x=1022 on a 1080px sheet
            // with a 22dp inset leaves it 6px from the sheet's own edge, which
            // reads as clipped rather than as aligned.
            .padding(start = 22.dp, end = 26.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                // An action's own name is the row's primary text, not a caption:
                // onSurface for a tappable row, the muted label colour for a fact
                // whose value is the datum.
                color = if (onClick != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (onClick != null) FontWeight.Medium else FontWeight.Normal,
                // Two lines rather than one. This row's label is a short catalog
                // word ("Model", "Session") and its value is the datum, so the
                // value keeps its intrinsic width here — unlike `SettingsRow`,
                // where the value is capped because it is the secondary fact.
                // Two lines is what stops a translated label or a long value
                // from turning the label into an ellipsis, which is the failure
                // the settings rows had.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                modifier = Modifier.padding(start = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospaceValue) FontFamily.Monospace else null,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                // Two lines where the value is a path or a file name: a session
                // file is a 60-character name and a single ellipsised line would
                // show its timestamp and never the id that distinguishes it.
                maxLines = valueLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(20.dp),
            )
        }
    }
}

/**
 * The part of a sheet that is the same for every sheet: the title over a hairline.
 *
 * The panel's own bottom inset — the gesture bar, or nothing while the keyboard is
 * up — is applied by the layer, so this is a body and not a page: it starts just
 * under the grabber, which is the layer's, and ends at the panel's padding.
 *
 * A hairline under the title rather than a gap: a long list scrolls under the
 * heading, and without it the first row's ripple runs into the heading with
 * nothing between them.
 */
@Composable
private fun SheetScaffold(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // 16dp between the last row and the sheet's padding, which is what
            // keeps the final row from looking cut off against the edge.
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
        content()
    }
}

/**
 * A settings-style row that opens a picker when tapped. Renders exactly
 * like `SettingsRow` but this one is the row *and* the sheet, so a call site is
 * one line.
 *
 * The row is `SettingsRow` itself rather than a second copy of its layout. The
 * one thing this component has to guarantee is that a row that opens a picker is
 * indistinguishable from the rows beside it — same 16dp horizontal and 12dp
 * vertical padding, same title weight, same 20dp chevron — and two
 * implementations of one row drift apart on the first change to either of them.
 * It costs an import from `ui.components` into `ui.settings`; a copy would cost
 * the guarantee.
 *
 * The chevron is always drawn: tapping opens the sheet, and the row's whole
 * surface is the target.
 *
 * @param title also the sheet's title, because the sheet answers the question
 *   this row asks.
 * @param value the current choice, rendered muted at the row's trailing edge.
 *   Pass the chosen option's `label` — the row has no way to look it up, because
 *   `selectedId` may be an id the options no longer contain.
 */
@Composable
fun PickerRow(
    title: String,
    value: String,
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    footnote: String? = null,
    /**
     * A filter field above the list, with this as its placeholder.
     *
     * For a picker whose options are a list of *things that exist* rather than a handful
     * of alternatives — the provider row, since pi ships thirty-odd of them — finding the
     * row is the problem rather than choosing between the rows. See [PickerBody].
     */
    searchHint: String? = null,
) {
    val host = LocalSheetHost.current

    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        value = value,
        showChevron = true,
        onClick = { host.showPicker(title, options, selectedId, onPick, footnote, searchHint) },
        modifier = modifier,
    )
}

/**
 * One row of a [PickerBody].
 *
 * `selectable` rather than `clickable`: it reports the row as the chosen one to a
 * screen reader — the same fact the tick draws — and it carries the platform's
 * own ripple, clipped to the row's rounded shape because `clip` comes first in
 * the chain.
 *
 * The modifier is installed only while the sheet is still open, for the reason
 * spelled out on [ReadOnlySheetRow]: a pointer modifier that is merely *disabled*
 * still claims the hit test, so a row that is on its way off screen would swallow
 * a tap meant for the page underneath it.
 */
@Composable
private fun PickerSheetRow(
    option: PickerOption,
    selected: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    val host = LocalSheetHost.current
    val foreground = pickerForeground(selected = selected, enabled = enabled)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 52dp is the target, and it is the floor rather than the padding
            // that sets it: a one-line row measures 44dp of content, and only a
            // row with a two-line description grows past the floor.
            .heightIn(min = PICKER_ROW_MIN_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (host.isOpen) {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = onChoose,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The tick's column is always here, empty on the rows that are not the
        // chosen one. Letting the mark take part in the flow would shove that
        // row's label — and the entry's own glyph — sideways by the width of a
        // tick, so the whole list would jump as the selection moved.
        Box(Modifier.size(TICK_COLUMN), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    // Decorative: `selectable` above already announces this row as
                    // the chosen one, and a description here would make a screen
                    // reader say "selected" twice.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(TICK_COLUMN),
                )
            }
        }
        // The entry's own glyph goes after the tick, not before it: the tick has
        // to be in the same column on every row, so a glyph ahead of it would push
        // that row's label out of line with the rest.
        //
        // `LocalContentColor` is provided because a caller's `Icon` tints itself
        // with that local and its default is black — outside a Material surface
        // that is a black glyph on a dark sheet. The row's own colour is what the
        // glyph should follow, including the dimming when the row is disabled.
        if (option.leading != null) {
            CompositionLocalProvider(LocalContentColor provides foreground) {
                option.leading()
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = foreground,
                // Two lines, not one. The one-line rule kept the list's rhythm
                // even, but it cost the entry its identity: a model id is the
                // only thing that says which model the row *is*, it runs past
                // 50 characters on providers that namespace their ids
                // (`anthropic/claude-…`), and a row that reads
                // `anthropic/claude-3-5-son…` cannot be told from the row under
                // it. The id is the primary text of this row, so it wraps.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!option.description.isNullOrBlank()) {
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
                    },
                    // No cap, unlike the label above it: a capability line ("1M
                    // context · images · reasoning") is the row's explanation, and
                    // two lines with an ellipsis cut the third fact off exactly when
                    // it was the one the reader was looking for. The list scrolls, so
                    // a taller row costs nothing but a swipe.
                )
            }
        }
        if (option.trailing != null) {
            // 4dp, not 12: the row's own `spacedBy(12.dp)` already separates the
            // label from whatever is here, and a second full gap reads as a
            // different row.
            Box(Modifier.padding(start = 4.dp)) { option.trailing?.invoke() }
        }
    }
}

/**
 * The colour of a row's text, and of any glyph that sits in it.
 *
 * A disabled row is a theme colour at [DISABLED_ALPHA] rather than a grey: the
 * palette has no disabled token, and 0.38 is the alpha Material3's own components
 * apply to disabled content.
 */
@Composable
private fun pickerForeground(selected: Boolean, enabled: Boolean): Color = when {
    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    selected -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * The grabber lives on the layer's panel (`Sheets.kt`): it is chrome the sheet
 * owns, not something a body draws, because a body that drew its own would draw a
 * second one under the panel's.
 */

/** The sheet's list stops growing here, as a share of the height the sheet may take. */
private const val SHEET_LIST_FRACTION = 0.6f

/** A row's floor, so a picker of one-line entries is still a comfortable target. */
private val PICKER_ROW_MIN_HEIGHT = 52.dp

/**
 * A [ReadOnlyBody] row's floor.
 *
 * Lower than a picker row's, deliberately: a fact is not a target, and a page of
 * ten facts at 52dp each is a page of ten facts and no page. Anything tappable in
 * one of these sheets is one of two or three rows, so the smaller floor does not
 * cost a touch target its size.
 */
private val READ_ONLY_ROW_MIN_HEIGHT = 44.dp

/** The width reserved for the selected row's tick, on every row. */
private val TICK_COLUMN = 20.dp

/**
 * The disabled content alpha, as a fraction of the row's own colour. Material3's
 * own components use 0.38 for it.
 */
private const val DISABLED_ALPHA = 0.38f
