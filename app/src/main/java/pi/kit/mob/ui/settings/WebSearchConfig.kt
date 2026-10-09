package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import pi.kit.mob.locales.LocalLanguage
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.WEB_ACCESS_PARAMS
import pi.kit.mob.locales.WebAccessParam
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.WebSearchEntry
import pi.kit.mob.pi.WebSearchStore
import pi.kit.mob.pi.WEB_SEARCH_OWNED_PATHS
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.components.showPicker
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSheetActions
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiShapes

/**
 * The "add an option" half of the search settings page.
 *
 * ## What replaced what
 *
 * This section used to be a text editor holding the whole annotated document: every
 * key the extension reads, the ones in effect live and the rest as comments with an
 * example, and the file was written by stripping the comments out of whatever the
 * user left in the box. It was honest and it was complete, and it asked the user to
 * write JSON to set one API key: know the key's exact name, know whether the value
 * wants quotes, keep the commas straight — on a phone keyboard, in a box about 46
 * columns wide. What replaced it is the list of what is set plus one way to add a
 * key, which is the shape this section has had since.
 *
 *  - **A list** of the file's keys that the controls above do not own. Read from
 *    the file, so a key this app has never heard of is still visible.
 *  - **One row**, which opens the extension's own documented options — the same
 *    [WEB_ACCESS_PARAMS] list that used to be rendered as comments, each row
 *    carrying the description the file used to carry.
 *  - **A value sheet** whose shape follows the option's type: text is a string,
 *    `true`/`false` is a switch, a number is a number, and only the options that
 *    really take a structure ask for JSON.
 *
 * Nothing is lost by this: adding a key writes it into the same document, on top of
 * what is already in effect, and removing one touches nothing else. What *is* gone
 * is the ability to hand-edit the document — for a file that is not a JSON object
 * the editor is still shown ([WebSearchDocumentEditor] is its fallback), because
 * that is the one case where the structured controls cannot start.
 *
 * ## Why the removal asks first
 *
 * It is the one destructive action *in this section* that does not have a control of
 * its own to undo it, and an API key that is removed is not recoverable from the UI.
 * (Resetting the whole file is destructive too, and it asks in its own section.) The
 * key's value is deliberately not repeated in the dialog: the row above it already
 * shows it, and a credential copied into one more place is one more place to look at.
 *
 * ## The add row, in the storage page's shape
 *
 * Adding a key used to be a bare title with a chevron, and that is the one row on the
 * page whose shape did not match anything else: a chevron meant "this opens a page",
 * and a menu entry is not what this is. The storage page's add-a-folder has had the
 * right shape for the same job since it was written — icon, title, one line saying
 * what the control is for — so this is that row with this page's words in it. The
 * chevron is still there, because the design language changed what it means: `PiRow`
 * draws one on every row that acts, which is how this row is told apart from the key
 * rows above it, and the picker rows on the page's first group carry a badge where
 * their own value goes instead.
 *
 * ## The group holds rows and nothing else, which is what keeps the press feedback even
 *
 * The add row is the group's **last** row, the way the storage page's add-a-folder is,
 * and the sentences around it live outside the group. That was originally forced by
 * `SettingsSection` clipping its contents to the card's rounded shape: a row that was
 * the card's last one had a rounded ripple at its bottom edge and a row with anything
 * under it — a note — had a square one. Measured on this page: the add row's press
 * outline changed after the first option was added, because the outcome note appeared
 * *inside* the card under it, while the storage page's add-a-folder never moved. A
 * group is a set of peer rows now and each row clips its own outline, so that
 * particular failure cannot recur — but the outcome still belongs outside it, because a
 * group is rows and a sentence is not one, and the acting row still belongs last. So
 * the outcome is a [ConfigNote] below the group, and the group itself is the list plus
 * the one row that acts on it. The explanation that used to sit above it is gone:
 * what the section is for is one line of a field's own help, and a paragraph over a
 * list nobody reads is clutter (§13).
 *
 * The hairline above the row exists only when the list has something in it: it
 * separates the list from the row, and a rule with one thing on each side of it is a
 * boundary drawn between two things that are not there.
 */
@Composable
internal fun WebSearchConfigOptions(store: WebSearchStore) {
    val text = strings
    val language = LocalLanguage.current
    val scope = rememberCoroutineScope()
    val host = LocalSheetHost.current
    val entries by store.extra.collectAsState()

    // The last thing a button did, which is the only status this section has.
    var outcome by remember { mutableStateOf<ConfigOutcome?>(null) }
    var pendingRemoval by remember { mutableStateOf<WebSearchEntry?>(null) }

    // Outside the group, deliberately: see the note above on the press feedback.
    PiSectionHeader(text.settings.searchConfig)
    PiGroup {
        entries.forEachIndexed { index, entry ->
            if (index > 0) PiRowDivider(inset = false)
            PiRow(
                title = entry.path,
                // The value is shown on the row's own second line rather than in
                // the trailing slot, and as JSON rather than as prose: the row is a
                // reminder of what is set, and the value sheet is where a long one
                // is edited. The second line is where a machine string belongs —
                // `VALUE_DISPLAY_CHARS` of JSON in an unweighted trailing slot is
                // the width the old value column was capped at 0.35 of the row to
                // prevent it from taking.
                subtitle = entry.value.display(),
                trailing = {
                    IconButton(onClick = { pendingRemoval = entry }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = text.settings.searchConfigRemove(entry.path),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        if (entries.isNotEmpty()) PiRowDivider(inset = false)

        // A caption, and the storage page's add-a-folder shape: icon, title, one
        // line saying what the picker it opens is for. The chevron is `PiRow`'s own —
        // it goes on every row that acts — and it is the one mark that tells this row
        // apart from the key rows above it, which carry a ✕ and no chevron.
        PiRow(
            title = text.settings.searchConfigAdd,
            subtitle = text.settings.searchConfigAddBody,
            leading = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = {
                outcome = null
                val options = WEB_ACCESS_PARAMS
                    // The keys the controls above own are written from the settings on
                    // every render, so a value added here would be overwritten by the
                    // next tap anywhere on the page. They are left out rather than
                    // shown-and-ignored.
                    .filterNot { it.path in WEB_SEARCH_OWNED_PATHS }
                    .map { param ->
                        PickerOption(
                            id = param.path,
                            label = param.path,
                            // The description is the option's own note — the same
                            // sentence the commented document carried — with the shape
                            // of its value appended, because that is what the sheet that
                            // follows is going to ask for.
                            description = "${param.note(language)}  (${param.type})",
                        )
                    }
                host.showPicker(
                    title = text.settings.searchConfigPickTitle,
                    options = options,
                    selectedId = null,
                    onPick = { path ->
                        val param = WEB_ACCESS_PARAMS.firstOrNull { it.path == path }
                            ?: return@showPicker
                        host.show(
                            Sheet(key = "web-search-value:${param.path}") {
                                WebSearchValueSheet(
                                    param = param,
                                    text = text,
                                    onAdd = { value ->
                                        scope.launch {
                                            outcome = store.setValue(param.path, value).fold(
                                                onSuccess = { ConfigOutcome.Added },
                                                onFailure = { ConfigOutcome.Failed },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    },
                    footnote = text.settings.searchConfigPickFootnote,
                    searchHint = text.settings.searchConfigPickSearch,
                )
            },
        )
    }

    // The result of the last action, under the group that holds the row that performed
    // it. It used to sit inside the card, between that row and the list, which put
    // "已保存，下次启动 Agent 时生效" on the wrong side of the button the reader had just
    // pressed — it read as a caption of the list rather than as the answer to the tap —
    // and moving it under the row left it inside the card, which is what squared off that
    // row's press ripple (see the note on this composable). Each message names its own
    // outcome (saved / removed / write failed), so one place under the section's action is
    // also the right place for a removal's note.
    outcome?.let { state ->
        ConfigNote(
            message = when (state) {
                ConfigOutcome.Added -> text.settings.searchConfigSaved
                ConfigOutcome.Removed -> text.settings.searchConfigRemoved
                ConfigOutcome.Failed -> text.settings.searchConfigFailed
            },
            color = when (state) {
                ConfigOutcome.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }

    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(text.settings.searchConfigRemoveTitle) },
            text = { Text(text.settings.searchConfigRemoveBody(entry.path)) },
            confirmButton = {
                // A `TextButton` and not a `PiButton`: the confirmation's own colour
                // is the design's `error` role and `PiButton` has no colour of its
                // own to give it. The dialog's two slots are Material's text-button
                // slots, so nothing here is a control this app invented.
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        scope.launch {
                            outcome = store.removeValue(entry.path).fold(
                                onSuccess = { ConfigOutcome.Removed },
                                onFailure = { ConfigOutcome.Failed },
                            )
                        }
                    },
                ) {
                    Text(
                        text.settings.searchConfigRemoveAction,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(text.common.cancel) }
            },
        )
    }
}

/**
 * The reset, in a section of its own.
 *
 * ## Why it is not one more row in the list above
 *
 * It was, and that is what was wrong with it: the configuration section is "the keys
 * in this file, and how to add one", and a control that throws away *all of them*
 * sitting at the bottom of that list reads as one more entry in it. It is not an
 * entry — it is the only action on the page that touches keys it does not name — so
 * it gets the shape the storage page gives "take every permission back": its own
 * labelled section, below the one it acts on, with the destructive colour on its
 * title.
 *
 * ## What it does, which is more than the list shows
 *
 * The list above is only the keys the page's own controls do not own; the six controls
 * at the top of the page own others. This resets **the whole file** — the controls'
 * keys back to PiKit's defaults *and* every key the list was showing — because that is
 * what "restore defaults" means and because a reset that left half the file alone
 * would be a reset the user has to audit. It is also the way out of a file the page
 * cannot parse at all ([WebSearchDocumentEditor]): it writes the default document
 * without ever needing to read the broken one, and the page then comes back editable.
 *
 * The confirmation is here rather than in the section above because the question
 * belongs to the control that asks it, and the outcome note lands under the group the
 * same way the list's own notes do — **outside** it, because the group is its rows
 * (see [WebSearchConfigOptions] for the measurement that settled that for the add row,
 * which this row is the same shape as).
 */
@Composable
internal fun WebSearchResetSection(store: WebSearchStore) {
    val text = strings
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ResetOutcome?>(null) }

    PiSectionHeader(text.settings.searchConfigRestore)
    PiGroup {
        PiRow(
            title = text.settings.searchConfigRestore,
            subtitle = text.settings.searchConfigRestoreSubtitle,
            leading = {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            // The title's own colour is the destructive one: a row that reports a
            // thing that cannot be taken back is the one place the `error` role
            // belongs on this page.
            titleColor = MaterialTheme.colorScheme.error,
            onClick = {
                outcome = null
                confirming = true
            },
        )
    }

    outcome?.let { state ->
        ConfigNote(
            message = when (state) {
                ResetOutcome.Restored -> text.settings.searchConfigRestored
                ResetOutcome.Failed -> text.settings.searchConfigFailed
            },
            color = when (state) {
                ResetOutcome.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text.settings.searchConfigRestoreTitle) },
            text = { Text(text.settings.searchConfigRestoreBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        scope.launch {
                            outcome = store.restoreDocument().fold(
                                onSuccess = { ResetOutcome.Restored },
                                onFailure = { ResetOutcome.Failed },
                            )
                        }
                    },
                ) {
                    Text(
                        text.settings.searchConfigRestore,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(text.common.cancel) }
            },
        )
    }
}

/** What the last button press in the configuration section did. */
private enum class ConfigOutcome { Added, Removed, Failed }

/** What the reset section's own button did. */
private enum class ResetOutcome { Restored, Failed }

/**
 * The sheet that takes one option's value.
 *
 * A sheet rather than a dialog for the same reason every other input in this app is
 * one (`Sheets.kt`): this app's modal layer is a view in the page, so the keyboard
 * stays up and the field keeps focus. A `AlertDialog` here would close the keyboard
 * the moment it appeared, which is the one thing a field cannot survive.
 *
 * The field starts empty with the option's own example as its placeholder, and the
 * example is *not* pre-filled: adding `true` or `$XAI_API_KEY` to a file because
 * that was what the placeholder said is a configuration the user never chose.
 */
@Composable
private fun WebSearchValueSheet(
    param: WebAccessParam,
    text: Strings,
    onAdd: (JsonElement) -> Unit,
) {
    val host = LocalSheetHost.current
    val language = LocalLanguage.current
    var draft by remember(param.path) { mutableStateOf("") }
    var invalid by remember(param.path) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        // The option's own note is the sheet's subtitle rather than a paragraph
        // under it: it says what the key is for, which is the question the reader
        // arrived with, and it lines up with the title instead of a paragraph
        // starting 4dp to its left.
        PiSheetTitle(
            title = text.settings.searchConfigValueTitle(param.path),
            subtitle = param.note(language),
        )
        // The extension's own example, shown before the field rather than inside it:
        // it is what a value of this option looks like, and it is not the value.
        Text(
            text = param.example,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )

        // A `PiTextField` cannot be used here: it offers no way to turn autocorrect
        // off, and that is load-bearing for this field. The rest of it is what
        // `PiTextField` draws — the app's field radius, and no container of its own —
        // so the two are indistinguishable on screen.
        OutlinedTextField(
            value = draft,
            onValueChange = { next ->
                draft = next
                invalid = false
            },
            label = { Text(text.settings.searchConfigValueLabel) },
            placeholder = { Text(param.example) },
            // What the field accepts, as its own supporting text rather than as a
            // paragraph between it and the button: it is help for this box.
            supportingText = { Text(text.settings.searchConfigValueNote) },
            singleLine = !param.type.contains("object"),
            // Machine text: an autocorrected key or `true` is a value the extension
            // ignores, and the failure is invisible until a search does nothing.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            shape = PiShapes.card,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                // 12/4, the inset every other value field uses (was 12/6).
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )

        if (invalid) {
            ConfigNote(text.settings.searchConfigValueInvalid, MaterialTheme.colorScheme.error)
        }

        // The design's own action row, holding the sheet's one real action. No
        // dismiss beside it: a sheet is closed by the scrim, the back gesture or a
        // downward drag, and this file's cancel was a fourth way to do the same
        // thing.
        PiSheetActions {
            PiButton(
                text = text.settings.searchConfigValueAdd,
                onClick = {
                    val parsed = parseValue(param, draft)
                    if (parsed == null) {
                        invalid = true
                    } else {
                        host.dismiss()
                        onAdd(parsed)
                    }
                },
                size = PiButtonSize.Small,
            )
        }
    }
}

/**
 * The JSON value [raw] means for an option of [param]'s type, or null when it means
 * nothing.
 *
 * The type string is the extension documentation's own wording, not a schema: it is
 * `string`, `number`, `boolean`, `boolean | object`, and so on. Two rules cover all
 * of them, and they are ordered so the friendly case wins:
 *
 *  1. **A string option takes the text as typed.** These are the API keys and model
 *     ids — the options a person actually adds by hand — and asking for `"sk-…"`
 *     with the quotes is exactly the JSON tax this section was built to remove.
 *     `$XAI_API_KEY` falls out of it for free, which is the form the extension
 *     resolves from the environment.
 *  2. **Everything else is JSON.** `true`, `50` and `{"host": "…"}` are what the
 *     remaining options want, they are unambiguous, and refusing text that does not
 *     parse is better than inventing a meaning for it — a `"50"` where a number
 *     belongs is a setting the extension silently ignores.
 *
 * A `boolean | object` therefore goes down the JSON path, and both of its shapes
 * parse.
 */
private fun parseValue(param: WebAccessParam, raw: String): JsonElement? {
    if (param.type == "string") return JsonPrimitive(raw)
    return runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull()
}

/** A value as the list shows it: JSON, on one line, capped. */
private fun JsonElement.display(): String {
    val encoded = toString().replace('\n', ' ').replace(Regex("\\s{2,}"), " ")
    return if (encoded.length <= VALUE_DISPLAY_CHARS) encoded else encoded.take(VALUE_DISPLAY_CHARS - 1) + "…"
}

/** A value's row is a reminder, not a reading surface: past this it is elided. */
private const val VALUE_DISPLAY_CHARS = 120
