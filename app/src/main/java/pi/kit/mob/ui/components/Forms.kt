/*
 * The app's one selection vocabulary: the modal list of choices, and the row that
 * opens it.
 *
 * Every picker in PiKit used to be a `DropdownMenu` anchored to a row, written
 * out separately at each call site: language in Settings, the
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
 * ## The inside of a sheet is the design system's now
 *
 * [PiSheetTitle] over a [PiSheetList] of [PiSheetRow]s is the one row shape the
 * app draws inside the panel: a sheet's row is a *menu* — read once, tapped once,
 * dismissed — so it is taller than a settings row, centres its two lines, and
 * marks the current choice with a tick at the end rather than with a filled
 * container. What is left in this file is what the design system does not carry:
 * the option list, its filter, its grouping, and the footnote under it.
 *
 * ## The host is consulted inside every tap
 *
 * The old rows left their pointer modifier *off* while the sheet was leaving, so a
 * tap during the exit could not run an action aimed at the page behind it.
 * [PiSheetRow] installs its own tap and cannot be built without one, so the same
 * guard moved into the handler: the row first asks [SheetHost.isOpen] and does
 * nothing if the sheet it belongs to has already been dismissed. The outcome is
 * the one that matters — the ~250 ms of exit cannot run an action — and it is the
 * reason every row below reads its onClick that way.
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

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.LocalStrings
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.PiLaunchOptions
import pi.kit.mob.ui.design.PiSearchField
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSheetList
import pi.kit.mob.ui.design.PiSheetRow
import pi.kit.mob.ui.design.PiSheetTitle
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
     * Tapping it does not also choose the row: the row's own tap is on the row and
     * the child's pointer input consumes the down first, which is what keeps a
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
 * Built once for the two places that offer them — the composer's slider sheet and
 * (historically) the model page's row — because the label, the explanation and the
 * footnote are the same three things, and two copies would drift on the first
 * rewording. It is not a `Composable`: it is a list of data. The composer reads
 * the ids and descriptions off it for `ThinkingLevelSheet`'s track.
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
 * A modal list of choices, with the selected one marked. This is the *only*
 * selection UI in the app.
 *
 * The body is drawn inside the root's one modal layer (`Sheets.kt`), which brings
 * the scrim, the drag gesture, the back handling and the entrance animation with
 * it; this is the list. A body rather than a whole sheet because the layer owns
 * the panel: two sheets that drew their own panel would be two panels that drift
 * apart on the first change to either.
 *
 * The sheet is deliberately quiet, and the design system draws that: a selected
 * row carries a tick and nothing else, where an earlier draft filled it with
 * `primaryContainer` — in this palette `#DCE6F2` in light mode against a
 * `#DCE3EB` sheet, a 3% difference that read as a rendering artifact rather than
 * as a selection.
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

    Column(
        Modifier
            .fillMaxWidth()
            // 16dp between the last row and the panel's padding, which is what
            // keeps the final row from looking cut off against the edge.
            .padding(bottom = 16.dp),
    ) {
        PiSheetTitle(title)

        if (searchHint != null) {
            // The app's search field: a filled pill with a magnifier and a clear
            // button. A filter over a list of names is a search, and the pill is
            // what says so; the field it replaced was an outlined box, which on a
            // panel with no other outline read as a third frame.
            PiSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = searchHint,
                clearContentDescription = LocalStrings.current.common.clear,
                // 12h/4v, the inset every other control in this file uses, so the
                // field's box lines up with the rows under it.
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // 60% of the height the sheet is allowed to take. Past that the list
        // stops being something you glance at and becomes a page, and the
        // sheet hides the screen it is being chosen from. This is a ceiling
        // and not a height: a two-item picker keeps its own.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            PiSheetList(Modifier.heightIn(max = maxHeight * SHEET_LIST_FRACTION)) {
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
                        item(key = "group:$index:$group") { PiSectionHeader(group) }
                    }
                    item(key = "$index:${option.id}") {
                        PickerSheetRow(
                            option = option,
                            selected = option.id == selectedId,
                            enabled = option.enabled,
                            onChoose = { onPick(option.id) },
                        )
                    }
                }
            }
        }
        if (action != null) {
            action()
        }
        if (!footnote.isNullOrBlank()) {
            // A sentence under the list rather than a notice: it explains the
            // *list*, and `PiNotice` is a container for something that happened.
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
 * spaced sheet for the things they cannot. The grabber, the title and the row
 * height are one implementation ([PiSheetTitle] and [PiSheetRow] on the layer's
 * panel); only the marking differs.
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        PiSheetTitle(title)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            PiSheetList(
                modifier = Modifier.heightIn(max = maxHeight * SHEET_LIST_FRACTION),
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
 * a settings row that opens something uses.
 *
 * Tapping an action closes the sheet, and the two happen together: the row is a
 * menu entry, and a menu that stays open behind the thing it just did is a menu
 * the user has to dismiss by hand. The host is consulted first — see the file
 * header — so a tap aimed at the page underneath a leaving sheet reaches the page
 * rather than being eaten by a row that no longer exists.
 *
 * [description] may be blank, in which case the row shows its label and its
 * [value] only — used where a fact has no current value.
 *
 * [monospaceValue] and [valueLines] are the two things the design row's own value
 * slot cannot do, and they are why this row draws its value itself when either is
 * set: a session file is a 60-character name whose id is what distinguishes it
 * from the file under it, and a path read back in proportional digits is a path
 * read back wrongly. A value that needs either therefore takes the row's trailing
 * slot, and the two never both apply — a value that needs that slot is the whole
 * of the row's right-hand side at that point.
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
    val wideValue = value != null && (monospaceValue || valueLines > 1)
    val trailing: (@Composable () -> Unit)? = if (wideValue) {
        { SheetValue(value.orEmpty(), monospaceValue, valueLines) }
    } else if (onClick != null) {
        {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        null
    }

    PiSheetRow(
        label = label,
        onClick = {
            if (host.isOpen) {
                host.dismiss()
                onClick?.invoke()
            }
        },
        modifier = modifier,
        subtitle = description,
        value = if (wideValue) null else value,
        trailing = trailing,
    )
}

/**
 * The value of a [ReadOnlySheetRow] that cannot use the design row's own slot.
 *
 * Drawn in the row's trailing position rather than in a column of its own, which
 * is where the value of a sheet row belongs, and with 4dp of its own on that end:
 * the value is end-aligned, so its last glyph is the one thing in the row that
 * lands on the panel's gutter, and a machine string there reads as clipped rather
 * than as aligned.
 */
@Composable
private fun SheetValue(text: String, monospace: Boolean, lines: Int) {
    Text(
        text = text,
        modifier = Modifier.padding(end = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        textAlign = TextAlign.End,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One row of a [PickerBody].
 *
 * The design system's [PiSheetRow] rather than a row of this file's own: a sheet's
 * row is a menu entry, and the tick at its end is how the list says which one the
 * reader is on. The two things this adds are the caller's option object going in
 * and the dismiss going out.
 */
@Composable
private fun PickerSheetRow(
    option: PickerOption,
    selected: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
) {
    val host = LocalSheetHost.current

    PiSheetRow(
        label = option.label,
        onClick = {
            if (host.isOpen) {
                host.dismiss()
                onChoose()
            }
        },
        subtitle = option.description,
        selected = selected,
        enabled = enabled,
        leading = option.leading,
        trailing = option.trailing,
    )
}

/**
 * A settings-style row that opens a picker when tapped. Renders exactly
 * like [SettingsRow] but this one is the row *and* the sheet, so a call site is
 * one line.
 *
 * The row is `SettingsRow` itself rather than a second copy of its layout. The
 * one thing this component has to guarantee is that a row that opens a picker is
 * indistinguishable from the rows beside it — the same content inset, the same
 * title style, the same 20dp chevron — and two implementations of one row drift
 * apart on the first change to either of them. It costs an import from
 * `ui.components` into `ui.settings`; a copy would cost the guarantee.
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

/** The sheet's list stops growing here, as a share of the height the sheet may take. */
private const val SHEET_LIST_FRACTION = 0.6f

/**
 * A row's floor, so a picker of one-line entries is still a comfortable target.
 *
 * It is [PiSheetRow]'s own floor — a menu row is taller than a settings row,
 * because it is a target rather than a line of a table — and it is published
 * rather than private for a page that draws its own sheet rows: `StoragePage`'s
 * folder tree steps into a directory instead of choosing it, so its rows cannot be
 * picker rows, and a tree row that was a different height from the rows beside it
 * is the mismatch this figure exists to prevent. It was 52dp when the picker row
 * was this file's own; the design row's 56dp is the one that is real now.
 */
internal val PICKER_ROW_MIN_HEIGHT = 56.dp
