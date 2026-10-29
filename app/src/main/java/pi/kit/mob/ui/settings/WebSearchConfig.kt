package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.WebSearchEntry
import pi.kit.mob.pi.WebSearchStore
import pi.kit.mob.pi.WEB_SEARCH_OWNED_PATHS
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.piFieldColors
import pi.kit.mob.ui.kit.piFieldCursor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 * ## The add row
 *
 * Adding a key used to be a bare title with a chevron, and that is the one row on the
 * page whose shape did not match anything else: a chevron meant "this opens a page",
 * and a menu entry is not what this is. The shape it wants is the add-a-row one — icon,
 * title, one line saying what the control is for — and Miuix already draws it:
 * `ArrowPreference` is the library's row *that opens something*, and its chevron is the
 * component's own, which is how this row is told apart from the key rows above it. Those
 * are `BasicComponent`s — a row that states something and opens nothing — and their own
 * control is the ✕ in the trailing slot, where the picker rows on the page's first group
 * carry a value instead.
 *
 * ## The group holds rows and nothing else, which is what keeps the press feedback even
 *
 * The add row is the group's **last** row, and the sentences around it live outside the
 * group. That was originally forced by the card clipping its contents to its rounded
 * shape: a row that was the card's last one had a rounded ripple at its bottom edge and
 * a row with anything under it — a note — had a square one. Measured on this page: the
 * add row's press outline changed after the first option was added, because the outcome
 * note appeared *inside* the card under it. The outcome is a **toast** now
 * (`LocalPiToast`, the app's one transient message), which is what actually settles it: a
 * sentence about an action that has finished belongs nowhere on the page, and the acting
 * row still belongs last. The explanation that used to sit above the list is gone too:
 * what the section is for is one line of a field's own help, and a paragraph over a list
 * nobody reads is clutter (§13).
 *
 * There is no hairline between the key rows, and the one that used to be there was the
 * page's only exception to the shape: Miuix's group is a card whose rows touch, and the
 * separation is the row's own press feedback rather than a rule between every pair. A
 * `HorizontalDivider` there also had to be inset to the titles' column to avoid touching
 * the card's edge, which is a second number to keep in step with the row margin for no
 * gain.
 *
 * ## A write restarts the agent itself
 *
 * The extension registers its tools when pi starts, so a key written under a running
 * agent is one the model does not see until its next launch. The page used to say so —
 * "it applies the next time the agent starts" — which left the reader to remember it,
 * and the strings no longer say it because the write now does it: `scheduleRestart`
 * is called after every write here, and it waits for a turn in flight before touching
 * the process, so a restart can never interrupt an answer.
 */
@Composable
internal fun WebSearchConfigOptions(store: WebSearchStore, session: PiAgentSession) {
    val text = strings
    val language = LocalLanguage.current
    val scope = rememberCoroutineScope()
    val toasts = LocalPiToast.current
    val entries by store.extra.collectAsState()

    var pendingRemoval by remember { mutableStateOf<WebSearchEntry?>(null) }
    var picking by remember { mutableStateOf(false) }
    // The option the value sheet is open on, rather than a boolean: the sheet's title,
    // its example and what the value is parsed as are all the option's, and a sheet that
    // remembered only "open" would have to look the option up again to ask its question.
    var adding by remember { mutableStateOf<WebAccessParam?>(null) }
    // And what the panel is *showing*, which is the same thing one dismissal longer.
    // `show` and the content have to disagree for the length of Miuix's exit animation,
    // or the panel plays its last 200 ms with an empty title and no field.
    var shown by remember { mutableStateOf<WebAccessParam?>(null) }
    // Bumped on every open, so the sheet's draft and its "invalid" mark are fresh each
    // time: the panel outlives a dismissal (see `shown`), and a draft keyed on the option
    // alone would hand the next opening of the *same* key the last one's half-typed
    // value. The old sheet host threw its content away on dismiss, which is what this is.
    var opening by remember { mutableStateOf(0) }

    PiSection(title = text.settings.searchConfig) {
        entries.forEach { entry ->
            BasicComponent(
                title = entry.path,
                // The value is shown on the row's own second line rather than in
                // the trailing slot, and as JSON rather than as prose: the row is a
                // reminder of what is set, and the value sheet is where a long one
                // is edited. The second line is where a machine string belongs —
                // `VALUE_DISPLAY_CHARS` of JSON in an unweighted trailing slot is
                // the width the old value column was capped at 0.35 of the row to
                // prevent it from taking.
                summary = entry.value.display(),
                endActions = {
                    // The ✕ is the row's own control, in the summary ink: it removes one
                    // key and is not the row's subject, which is the key's name and value.
                    IconButton(onClick = { pendingRemoval = entry }) {
                        Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = text.settings.searchConfigRemove(entry.path),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                },
            )
        }

        // The add row: icon, title, one line saying what the picker it opens is for, and
        // Miuix's own chevron — `ArrowPreference` is the row that opens something, which
        // is the one mark that tells this row apart from the key rows above it.
        ArrowPreference(
            title = text.settings.searchConfigAdd,
            summary = text.settings.searchConfigAddBody,
            startAction = { StartIcon(MiuixIcons.Add) },
            onClick = { picking = true },
        )
    }

    SettingsPickerSheet(
        show = picking,
        title = text.settings.searchConfigPickTitle,
        // The keys the controls above own are written from the settings on every render,
        // so a value added here would be overwritten by the next tap anywhere on the page.
        // They are left out rather than shown-and-ignored.
        options = WEB_ACCESS_PARAMS
            .filterNot { it.path in WEB_SEARCH_OWNED_PATHS }
            .map { param ->
                SettingsPick(
                    id = param.path,
                    label = param.path,
                    // The description is the option's own note — the same sentence the
                    // commented document carried — with the shape of its value appended,
                    // because that is what the sheet that follows is going to ask for.
                    description = "${param.note(language)}  (${param.type})",
                )
            },
        selectedId = null,
        onPick = { path ->
            picking = false
            val found = WEB_ACCESS_PARAMS.firstOrNull { it.path == path }
            adding = found
            shown = found ?: shown
            if (found != null) opening++
        },
        onDismiss = { picking = false },
        footnote = text.settings.searchConfigPickFootnote,
        searchHint = text.settings.searchConfigPickSearch,
    )

    val param = adding ?: shown
    PiSheet(
        show = adding != null,
        title = text.settings.searchConfigValueTitle(param?.path.orEmpty()),
        onDismiss = { adding = null },
    ) {
        if (param != null) {
            WebSearchValueSheet(
                param = param,
                text = text,
                opening = opening,
                onAdd = { value ->
                    adding = null
                    scope.launch {
                        // The extension registers its tools when pi starts, so a key
                        // written under a running agent is one the model does not see
                        // until the next launch. Rather than telling the reader to
                        // restart it, the write says so itself — `scheduleRestart` waits
                        // for any turn in flight, so this cannot interrupt an answer
                        // (`PiAgentSession`).
                        toasts?.show(
                            store.setValue(param.path, value).fold(
                                onSuccess = {
                                    session.scheduleRestart()
                                    text.settings.searchConfigSaved
                                },
                                onFailure = { text.settings.searchConfigFailed },
                            ),
                        )
                    }
                },
                onDismiss = { adding = null },
            )
        }
    }

    // The result of the last action is a toast (see the KDoc above), so nothing is
    // drawn here: a group is its rows and a sentence is not one, and a sentence about
    // an action that has already finished is what the toast layer is for.

    val removing = pendingRemoval
    PiConfirm(
        show = removing != null,
        title = text.settings.searchConfigRemoveTitle,
        text = text.settings.searchConfigRemoveBody(removing?.path.orEmpty()),
        confirm = text.settings.searchConfigRemoveAction,
        dismiss = text.common.cancel,
        // Destructive, so the confirming word carries the error role: "remove" and
        // "keep" should not look like two equivalent answers.
        destructive = true,
        onConfirm = {
            val entry = removing ?: return@PiConfirm
            pendingRemoval = null
            scope.launch {
                toasts?.show(
                    store.removeValue(entry.path).fold(
                        onSuccess = {
                            session.scheduleRestart()
                            text.settings.searchConfigRemoved
                        },
                        onFailure = { text.settings.searchConfigFailed },
                    ),
                )
            }
        },
        onDismiss = { pendingRemoval = null },
    )
}

/** The mark in front of a row that adds something, inset from the row's title. */
@Composable
private fun StartIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(end = 12.dp),
    )
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
 * belongs to the control that asks it, and the outcome is a toast for the same reason
 * the list's own outcomes are (see [WebSearchConfigOptions]): a sentence about
 * something already done does not belong on the page it was done to.
 */
@Composable
internal fun WebSearchResetSection(store: WebSearchStore, session: PiAgentSession) {
    val text = strings
    val scope = rememberCoroutineScope()
    val toasts = LocalPiToast.current
    var confirming by remember { mutableStateOf(false) }

    // Untitled: the card holds this one row and the row's own title is the name of the
    // action, so a label above it would be the heading drawn twice. The KDoc above says
    // the section exists to *separate* this action from the list; a card of its own does
    // that without repeating the sentence.
    PiSection {
        BasicComponent(
            title = text.settings.searchConfigRestore,
            summary = text.settings.searchConfigRestoreSubtitle,
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Reset,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
            // The title's own colour is the destructive one: a row that reports a
            // thing that cannot be taken back is the one place the `error` role
            // belongs on this page. `BasicComponent` rather than `ArrowPreference`
            // because this row does its work where it stands — it asks a question —
            // and a chevron is Miuix's mark for a row that opens something.
            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
            onClick = { confirming = true },
        )
    }

    PiConfirm(
        show = confirming,
        title = text.settings.searchConfigRestoreTitle,
        text = text.settings.searchConfigRestoreBody,
        confirm = text.settings.searchConfigRestore,
        dismiss = text.common.cancel,
        destructive = true,
        onConfirm = {
            confirming = false
            scope.launch {
                // Rewriting the file also rewrites the `tools.*` keys, so the agent is
                // restarted from here rather than the reader being told to do it — the
                // same trade the add and remove paths make.
                toasts?.show(
                    store.restoreDocument().fold(
                        onSuccess = {
                            session.scheduleRestart()
                            text.settings.searchConfigRestored
                        },
                        onFailure = { text.settings.searchConfigFailed },
                    ),
                )
            }
        },
        onDismiss = { confirming = false },
    )
}

/**
 * The sheet that takes one option's value.
 *
 * A sheet rather than a dialog because the value is something the reader types: the
 * field sits in Miuix's bottom panel with the keyboard under it, where a dialog's small
 * panel would put the keyboard over its own buttons. [PiSheet] is the app's one sheet,
 * and this is a body inside it rather than a second modal layer.
 *
 * The field starts empty, and the option's own example is shown *above* it rather than
 * filled into it: adding `true` or `$XAI_API_KEY` to a file because that was what the
 * placeholder said is a configuration the user never chose.
 */
@Composable
private fun WebSearchValueSheet(
    param: WebAccessParam,
    text: Strings,
    /** Changes on every opening — see `opening` at the call site. */
    opening: Int,
    onAdd: (JsonElement) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalLanguage.current
    var draft by remember(param.path, opening) { mutableStateOf("") }
    var invalid by remember(param.path, opening) { mutableStateOf(false) }

    // No bottom inset of its own: `PiSheet` already leaves the panel's own gap under its
    // content, and a second one here would be 16dp of nothing above the navigation bar.
    Column(Modifier.fillMaxWidth()) {
        // The option's own note says what the key is for, which is the question the
        // reader arrived with; it is the sheet body's first paragraph because Miuix's
        // sheet title has no subtitle slot.
        Text(
            text = param.note(language),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
        // The extension's own example: what a value of this option looks like, in the
        // monospace face because it is a value rather than a sentence.
        Text(
            text = param.example,
            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )

        // `SettingsTextField` cannot be used here: its keyboard options carry the
        // keyboard type only, and switching autocorrect off is load-bearing for this
        // field. Everything else is what that helper draws — Miuix's field, no container
        // of its own, and a supporting line under it in the summary ink.
        TextField(
            cornerRadius = PiCorner.radius,
            value = draft,
            onValueChange = { next ->
                draft = next
                invalid = false
            },
            label = text.settings.searchConfigValueLabel,
            singleLine = !param.type.contains("object"),
            textStyle = MiuixTheme.textStyles.main,
            // Machine text: an autocorrected key or `true` is a value the extension
            // ignores, and the failure is invisible until a search does nothing.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                autoCorrectEnabled = false,
            ),
            // No focus ring, and the caret stated instead: see `piFieldColors`.
            colors = piFieldColors(),
            cursorBrush = piFieldCursor(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = text.settings.searchConfigValueNote,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
        )
        if (invalid) {
            // The one thing this sheet can refuse is text that is not the value the
            // option's type wants, and the reason is the field's own problem rather
            // than the page's: it stays attached to the box it is about.
            Text(
                text = text.settings.searchConfigValueInvalid,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            )
        }

        // The sheet's one real action, and it fills the row: an action that is alone on
        // its line is a row-wide control here as it is on the pages behind the sheet.
        // No dismiss beside it — a sheet is closed by the scrim, the back gesture or a
        // downward drag, and a cancel here would be a fourth way to do the same thing.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsAction(
                text = text.settings.searchConfigValueAdd,
                onClick = {
                    val parsed = parseValue(param, draft)
                    if (parsed == null) {
                        invalid = true
                    } else {
                        onDismiss()
                        onAdd(parsed)
                    }
                },
                modifier = Modifier.weight(1f),
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
