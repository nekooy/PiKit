package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * columns wide. The report asked for the obvious alternative, and this is it.
 *
 *  - **A list** of the file's keys that the controls above do not own. Read from
 *    the file, so a key this app has never heard of is still visible.
 *  - **One button**, which opens the extension's own documented options — the same
 *    [WEB_ACCESS_PARAMS] list that used to be rendered as comments, each row
 *    carrying the description the file used to carry.
 *  - **A value sheet** whose shape follows the option's type: text is a string,
 *    `true`/`false` is a switch, a number is a number, and only the options that
 *    really take a structure ask for JSON.
 *
 * Nothing is lost by this: adding a key writes it into the same document, on top of
 * what is already in effect, and removing one touches nothing else. What *is* gone
 * is the ability to hand-edit the document — for a file that is not a JSON object
 * the editor is still shown ([WebSearchDocument] is its fallback), because that is
 * the one case where the structured controls cannot start.
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
 * page whose shape did not match anything else: a chevron means "this opens a page",
 * and a menu entry is not what this is. The storage page's add-a-folder has had the
 * right shape for the same job since it was written — icon, title, one line saying
 * what the control is for — so this is that row with this page's words in it. See
 * [WebSearchResetSection] for where the reset went.
 *
 * ## The card holds rows and nothing else, which is what makes the press feedback match
 *
 * The add row is the card's **last** row, the way the storage page's add-a-folder is,
 * and the text around it lives outside the card. That is not tidiness: `SettingsSection`
 * clips its contents to the card's rounded shape, so a row that is the card's last one
 * has a rounded ripple at its bottom edge and a row with anything under it — a note —
 * has a square one. Measured as the report: on this page the add row's press outline
 * changed after the first option was added, because the outcome note appeared *inside*
 * the card under it, while the storage page's add-a-folder never moved. The notes above
 * the row squared its top edge the same way, in every state — the storage page's card
 * holds no notes at all. So the explanation and the empty hint are [SettingsNote]s above
 * the section, the outcome is a [ConfigNote] below the card, and the card itself is the
 * list plus the one row that acts on it.
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

    // Outside the card, deliberately: see the note above on the press feedback.
    SettingsNote(text.settings.searchConfigNote)
    if (entries.isEmpty()) SettingsNote(text.settings.searchConfigEmpty)

    SettingsSection(text.settings.searchConfig) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                title = entry.path,
                // Monospace, and one line: this is a JSON value, and the row is a
                // reminder of what is set rather than the place to read it — the
                // value sheet is where a long one is edited.
                subtitle = entry.value.display(),
                monospace = true,
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
        if (entries.isNotEmpty()) SettingsDivider()

        // No chevron, and a caption: the same row the storage page adds a folder with.
        SettingsRow(
            title = text.settings.searchConfigAdd,
            subtitle = text.settings.searchConfigAddBody,
            icon = Icons.Filled.Add,
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

    // The result of the last action, under the card that holds the row that performed
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
 * It was, and that was the report: the configuration section is "the keys in this
 * file, and how to add one", and a control that throws away *all of them* sitting at
 * the bottom of that list reads as one more entry in it. It is not an entry — it is
 * the only action on the page that touches keys it does not name — so it gets the
 * shape the storage page gives "take every permission back": its own labelled section,
 * below the one it acts on, with the destructive colour on its title.
 *
 * ## What it does, which is more than the list shows
 *
 * The list above is only the keys the page's own controls do not own; the six controls
 * at the top of the page own others. This resets **the whole file** — the controls'
 * keys back to PiKit's defaults *and* every key the list was showing — because that is
 * what "restore defaults" means and because a reset that left half the file alone
 * would be a reset the user has to audit. It is also the way out of a file the page
 * cannot parse at all ([WebSearchDocument]): it writes the default document without
 * ever needing to read the broken one, and the page then comes back editable.
 *
 * The confirmation is here rather than in the section above because the question
 * belongs to the control that asks it, and the outcome note lands under the card the
 * same way the list's own notes do — **outside** it, because a note inside the card
 * under this row would square off that row's press ripple ([WebSearchConfigOptions]
 * has the measurement for the add row, and this row is the same shape).
 */
@Composable
internal fun WebSearchResetSection(store: WebSearchStore) {
    val text = strings
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<ResetOutcome?>(null) }

    SettingsSection(text.settings.searchConfigRestore) {
        SettingsRow(
            title = text.settings.searchConfigRestore,
            subtitle = text.settings.searchConfigRestoreSubtitle,
            icon = Icons.Filled.RestartAlt,
            danger = true,
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
        Text(
            text = text.settings.searchConfigValueTitle(param.path),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )
        Text(
            text = param.note(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        Text(
            text = param.example,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { next ->
                draft = next
                invalid = false
            },
            label = { Text(text.settings.searchConfigValueLabel) },
            placeholder = { Text(param.example) },
            singleLine = !param.type.contains("object"),
            // Machine text: an autocorrected key or `true` is a value the extension
            // ignores, and the failure is invisible until a search does nothing.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Text(
            text = text.settings.searchConfigValueNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        if (invalid) {
            ConfigNote(text.settings.searchConfigValueInvalid, MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = host::dismiss) { Text(text.common.cancel) }
            Button(
                onClick = {
                    val parsed = parseValue(param, draft)
                    if (parsed == null) {
                        invalid = true
                    } else {
                        host.dismiss()
                        onAdd(parsed)
                    }
                },
            ) {
                Text(text.settings.searchConfigValueAdd)
            }
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
