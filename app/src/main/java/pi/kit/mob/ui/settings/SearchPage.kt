package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.WebSearchSettings
import pi.kit.mob.pi.WebSearchStore
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.showPicker
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPageBottom
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiSwitchRow
import pi.kit.mob.ui.design.PiTextField
import pi.kit.mob.ui.design.PiTone

/**
 * The bundled `pi-web-access` extension's options.
 *
 * ## What this page is, and what it deliberately is not
 *
 * The extension reads around 110 configuration keys. The page is **two layers**,
 * and the split is the design:
 *
 *  - **Controls** for the six keys PiKit is willing to write: the master switch, the
 *    workflow, the provider, the inline-content limit, the fetch timeout and the
 *    proxy. All six are peer rows in **one group** — that is the whole point of the
 *    frame, because the line between the two layers is the page's argument and a
 *    single frame is what draws it: everything inside it is PiKit's, everything
 *    outside it is the file's. Each row states its own value, and the reasons live in
 *    the picker sheets.
 *  - **The configuration section** ([WebSearchConfigOptions]), for everything
 *    else: the file's own keys that the controls do not own, listed with their
 *    values, plus one row that adds a new one from the extension's documented
 *    options — each with the description that used to live in a comment.
 *  - **The reset, in a section of its own** ([WebSearchResetSection]), because it
 *    acts on the whole file — the controls' keys as well as the listed ones — and a
 *    control that does that is not one more row in a list of keys.
 *
 * The second layer used to be a plain text editor of the whole annotated document,
 * and it was complete and honest and asked the user to write JSON to set one API
 * key. A switch for every option would be a page nobody reads; a text box is a page
 * only a programmer can use. The list is the middle: *what is set* plus *what can be
 * added*, in the app's own rows. The editor still exists, and is shown only when the
 * file is not a JSON object — the one state the structured controls cannot start
 * from ([WebSearchDocumentEditor]).
 *
 * The line between the two layers is still not "the important keys" but **"the keys
 * PiKit is willing to own"**: a control that is only *drawn* from PiKit's default
 * still has to be *written* by PiKit, and `githubClone.enabled` defaults to `true` —
 * so a switch the page drew as "on" because it had never seen the key would silently
 * re-enable cloning over a file that had turned it off. Everything the controls do
 * not own is the user's own, passed through untouched.
 *
 * The five keys a switch stands over are the same statement: the switch writes the
 * extension's `webSearch.enabled` shorthand *and* the four `tools.<name>.enabled`,
 * because it is a master over the search, source-check, fetch and retrieval tools —
 * and it deliberately does not take the extension's four commands with it, because
 * every key it owns is rewritten from its one boolean on every render and a
 * hand-set `commands.search.enabled: false` would be overwritten by the next tap
 * anywhere on the page (ARCHITECTURE §9.3).
 *
 * ## Picker rows rather than radio lists
 *
 * The workflow and the search provider were inline runs of radio rows: three plus
 * thirty-one rows on one page, each list as long as its own contents, which is what
 * made it read as a wall. They are the app's one selection control — a row that
 * opens a picker sheet — so each choice is one line whose value is the current one.
 *
 * ## Where the state lives
 *
 * In [WebSearchStore], owned by the session. Unlike the updater and the storage
 * self-test — which `SettingsScreen` holds because they are processes that must
 * survive leaving their page — this is one small file, and reading it again every
 * time the page is opened is what keeps the page and pi's file from drifting apart.
 *
 * ## What is saved when
 *
 * The two number fields save as they are typed: neither restarts anything — the
 * extension reads the file per request — and a working directory that saves as it
 * is typed is the precedent (`AgentPage`). Each field keeps a local draft keyed on
 * the stored value, so a number that does not parse yet is left alone in the field
 * rather than rounded into something else. An added option is written on its own
 * button, from a sheet that is dismissed by it: it is a deliberate act with a value
 * the user typed, and it is not something to do on a keystroke. The restart note at
 * the end of the page is what says a change does not apply to the running agent —
 * the extension registers its tools when pi starts.
 */
@Composable
internal fun SearchPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    // The session owns this, not the page: PiKit writes one default into the
    // extension's file — `workflow: "none"`, because the extension's own default
    // opens a result curator in a browser — and that has to be true of a launch
    // that never opens this page. Reading the same instance also means a change
    // made here is not re-read from disk on the next visit.
    val store = session.webSearch
    val settings by store.settings.collectAsState()
    val unreadable by store.unreadable.collectAsState()
    // Read once: it comes from metadata the runtime image build wrote, and nothing
    // this app does can change it while the page is open.
    val extension = store.extension

    PiScaffold(
        title = text.settings.searchTitle,
        subtitle = text.settings.searchPageSubtitle,
        onBack = onBack,
        // Pinned rather than collapsing: the body is the point on this page, and a
        // one-line bar gives the rows back their height.
    ) { body ->
        Column(
            modifier = body
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding)
                .padding(top = 8.dp, bottom = PiPageBottom),
            // `PiGap`'s own rhythm: a group and the heading over it are 20dp apart
            // from the heading's own top padding, so this only has to be the step
            // between two sections.
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (extension == null) {
                // Nothing interactive at all: there is no file for these controls
                // to be about.
                PiNotice(text.settings.searchNotBundled, tone = PiTone.Danger)
                return@Column
            }

            if (unreadable) {
                // The six keys below are hidden rather than merely refused: the
                // store will not write over a file it could not read, so every one
                // of them would be a control that appears to work and does not.
                PiNotice(text.settings.searchConfigUnreadable, tone = PiTone.Danger)
            } else {
                // The extension's version used to ride this heading as a trailing
                // number. It is gone: it is a fact about the bundled image rather
                // than about anything the reader does here, it is not translated,
                // and a version the reader cannot act on was noise on the one line
                // whose whole job is to name the group.
                PiSectionHeader(text = text.settings.webAccess)
                PiGroup {
                    // One switch over the extension's five keys. It is a real
                    // master: off takes the search, source-check, fetch and
                    // retrieval tools all with it, which is what the subtitle
                    // beside it promises.
                    PiSwitchRow(
                        title = text.settings.webAccess,
                        checked = settings.enabled,
                        onCheckedChange = { wanted -> store.update { it.copy(enabled = wanted) } },
                        subtitle = text.settings.webAccessSubtitle,
                        leading = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    )
                    PiRowDivider()
                    OptionPickerRow(
                        title = text.settings.searchWorkflow,
                        subtitle = text.settings.searchWorkflowSubtitle,
                        value = workflowLabel(settings.workflow, text),
                        leading = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        options = listOf(
                            WebSearchSettings.WORKFLOW_NONE,
                            WebSearchSettings.WORKFLOW_AUTO_SUMMARY,
                            WebSearchSettings.WORKFLOW_SUMMARY_REVIEW,
                        ).map { id ->
                            PickerOption(
                                id = id,
                                label = workflowLabel(id, text),
                                description = text.settings.workflowDescription(id),
                            )
                        },
                        selectedId = settings.workflow,
                        onPick = { id -> store.update { it.copy(workflow = id) } },
                    )
                    PiRowDivider()
                    OptionPickerRow(
                        title = text.settings.searchProvider,
                        subtitle = text.settings.searchProviderSubtitle,
                        value = settings.provider ?: text.settings.providerAutomatic,
                        leading = { Icon(Icons.Filled.Search, contentDescription = null) },
                        options = listOf<PickerOption>(
                            PickerOption(
                                id = WebSearchSettings.PROVIDER_AUTO,
                                label = text.settings.providerAutomatic,
                                description = text.settings.providerDescription(
                                    WebSearchSettings.PROVIDER_AUTO,
                                ),
                            ),
                        ) + WebSearchSettings.SEARCH_PROVIDERS.map { id ->
                            // The extension's own provider ids, untranslated: they
                            // are product names, and they are the spelling its
                            // documentation and its `provider` parameter use. The
                            // line under each says what it needs, so the list can be
                            // read without the extension's README.
                            PickerOption(
                                id = id,
                                label = id,
                                description = text.settings.providerDescription(id),
                            )
                        },
                        selectedId = settings.provider ?: WebSearchSettings.PROVIDER_AUTO,
                        onPick = { id ->
                            // Omitted and `"auto"` mean the same thing to the
                            // extension, and the omission is what it writes.
                            store.update {
                                it.copy(
                                    provider = id.takeIf { chosen ->
                                        chosen != WebSearchSettings.PROVIDER_AUTO
                                    },
                                )
                            }
                        },
                        // The one thing a picker of thirty rows owes the user: the
                        // list is the extension's, not a selection of favourites.
                        footnote = text.settings.searchProviderFootnote(
                            WebSearchSettings.SEARCH_PROVIDERS.size,
                        ),
                    )
                    // No divider between the limit fields and the provider row
                    // above them: a text field draws its own boundary, and a
                    // hairline beside one is one boundary too many.
                    NumberField(
                        label = text.settings.inlineContentLimit,
                        stored = settings.maxInlineContentChars,
                        // The extension clamps to its own cap, so a larger number
                        // would be one the page showed and pi did not use.
                        max = WebSearchSettings.MAX_INLINE_CONTENT_CHARS,
                        onParsed = { parsed ->
                            store.update { it.copy(maxInlineContentChars = parsed) }
                        },
                    )
                    NumberField(
                        label = text.settings.fetchTimeout,
                        stored = settings.fetchTimeoutSeconds,
                        max = Int.MAX_VALUE,
                        onParsed = { parsed ->
                            store.update { it.copy(fetchTimeoutSeconds = parsed) }
                        },
                    )
                    ProxyField(
                        label = text.settings.searchProxy,
                        caption = text.settings.searchProxySubtitle,
                        stored = settings.proxy,
                        onChanged = { value -> store.update { it.copy(proxy = value) } },
                    )
                }
            }

            // The configuration: every key the extension reads that the controls
            // above do not write. Normally that is a list plus one row, and the
            // user never sees the file's syntax; when the file is not a JSON object
            // the text editor is the only way out of it, and it is the way out of
            // it that is shown. See [WebSearchConfigOptions], which draws its own
            // section: the explanations belong outside the group so that the group's
            // last row is the one that acts.
            if (unreadable) {
                PiSectionHeader(text.settings.searchConfig)
                WebSearchDocumentEditor(store = store)
            } else {
                WebSearchConfigOptions(store = store)
            }

            // Then the one action that resets the *whole* file, in a section of its
            // own: the list above is the keys the page does not own, and a control
            // that throws all of them away — the controls' keys included — is not
            // one more entry in that list. It is shown in both states, because it is
            // also the way out of a file nothing can parse. See [WebSearchResetSection].
            WebSearchResetSection(store = store)

        }
    }
}

/**
 * A row whose value is the current one and whose tap opens the list of the others.
 *
 * The app's one selection control, drawn as [PiRow]: a settings page is read far
 * more often than it is changed, so the current value is what the row is for and the
 * list is one tap behind it.
 *
 * The value is the row's own plain `value` slot and the chevron is [PiRow]'s, which
 * is exactly the shape every other picker in the app has — the language row on the
 * settings root is the same control (`PickerRow`). It used to end in a [PiBadge]
 * instead, on the theory that "what is chosen" and "that tapping opens a list" are
 * two facts that need two marks; the pill turned out to read as a *state* — a chip
 * that looks like it can be tapped to change something in place — where the row
 * itself is the target, and it made these two rows the only pickers in the app that
 * did not look like the others.
 *
 * The sheet itself is the app's existing one (`SheetHost.showPicker`), because the
 * modal layer, its filter field and its dismissal are not this page's to restyle.
 */
@Composable
private fun OptionPickerRow(
    title: String,
    subtitle: String,
    value: String,
    options: List<PickerOption>,
    selectedId: String?,
    onPick: (String) -> Unit,
    leading: @Composable () -> Unit,
    footnote: String? = null,
) {
    val host = LocalSheetHost.current
    PiRow(
        title = title,
        subtitle = subtitle,
        leading = leading,
        value = value,
        onClick = { host.showPicker(title, options, selectedId, onPick, footnote) },
    )
}

/**
 * The workflow's label, for the picker's rows and for the row that opens it.
 *
 * The id is the extension's own (`none`, `auto-summary`, `summary-review`) because it
 * is what the file stores; the label is the catalog's, because a picker whose rows
 * were the raw ids would be a list of three words in nobody's language.
 */
private fun workflowLabel(id: String, text: Strings): String = when (id) {
    WebSearchSettings.WORKFLOW_AUTO_SUMMARY -> text.settings.workflowAutoSummary
    WebSearchSettings.WORKFLOW_SUMMARY_REVIEW -> text.settings.workflowSummaryReview
    else -> text.settings.workflowNone
}

/**
 * A whole-number field that saves what parses and keeps what does not.
 *
 * The draft is keyed on the stored number, so the field is a copy of it the way
 * `AgentPage`'s working directory is: a reload — or a clamp — rewrites the field,
 * and a value that is still being typed (`""`, `"30"` on the way to `"30000"`) is
 * left in the field instead of being rounded to something the user did not type.
 */
@Composable
private fun NumberField(
    label: String,
    stored: Int,
    max: Int,
    onParsed: (Int) -> Unit,
) {
    var draft by remember(stored) { mutableStateOf(stored.toString()) }
    PiTextField(
        value = draft,
        onValueChange = { raw ->
            draft = raw
            raw.trim().toIntOrNull()?.coerceIn(1, max)?.let(onParsed)
        },
        label = label,
        singleLine = true,
        keyboardType = KeyboardType.Number,
        // 12/4, the inset every other field inside a group uses: 4dp of vertical
        // air beside a row's own 2dp, so a field and the rows above it are spaced
        // by their containers rather than by a margin of their own.
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * The proxy, as one field with its caption.
 *
 * It used to be a label row *and* a field, which named the setting twice: the row's
 * title and the field's label were the same string, and the caption under them was
 * the only part that was not the name again. The caption is the field's own
 * supporting text now, so one control states the setting, what it is for and the
 * value, and the `Lock` the row carried was decoration on top of a name.
 */
@Composable
private fun ProxyField(
    label: String,
    caption: String,
    stored: String?,
    onChanged: (String?) -> Unit,
) {
    var draft by remember(stored) { mutableStateOf(stored.orEmpty()) }
    PiTextField(
        value = draft,
        onValueChange = { value ->
            draft = value
            onChanged(value.ifBlank { null })
        },
        label = label,
        supportingText = caption,
        singleLine = true,
        keyboardType = KeyboardType.Uri,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
