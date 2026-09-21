package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow

/**
 * The bundled `pi-web-access` extension's options.
 *
 * ## What this page is, and what it deliberately is not
 *
 * The extension has roughly eighty configuration keys. The page is **two layers**,
 * and the split is the design:
 *
 *  - **Controls** for the six keys a phone user actually changes: the master
 *    switch, the workflow, the provider, two content limits and the fetch
 *    timeout. Each is a row whose value is the current one, and the reasons live
 *    in the picker sheets.
 *  - **The configuration section** ([WebSearchConfigOptions]), for everything
 *    else: the file's own keys that the controls do not own, listed with their
 *    values, plus one button that adds a new one from the extension's documented
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
 * from ([WebSearchDocument]).
 *
 * The line between the two layers is still not "the important keys" but **"the keys
 * PiKit is willing to own"**: a control that is only *drawn* from PiKit's default
 * still has to be *written* by PiKit, and `githubClone.enabled` defaults to `true` —
 * so a switch the page drew as "on" because it had never seen the key would silently
 * re-enable cloning over a file that had turned it off. Everything the controls do
 * not own is the user's own, passed through untouched.
 *
 * ## Picker rows rather than radio lists
 *
 * The workflow and the search provider were inline runs of radio rows: three plus
 * eleven rows on one page, each list as long as its own contents, which is what
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

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.settings.searchTitle,
            subtitle = text.settings.searchPageSubtitle,
            onBack = onBack,
        )

        SettingsBody {
            // First, before anything the user could mistake for a requirement.
            SettingsNote(text.settings.searchFreeNote)

            if (extension == null) {
                // Nothing interactive at all: there is no file for these controls
                // to be about.
                SettingsNote(text.settings.searchNotBundled)
                return@SettingsBody
            }

            if (unreadable) {
                // The six keys below are hidden rather than merely refused: the
                // store will not write over a file it could not read, so every one
                // of them would be a control that appears to work and does not.
                SettingsNote(text.settings.searchConfigUnreadable)
            } else {
                SettingsSection(text.settings.webAccess) {
                    // One switch over the extension's five keys. It is a real
                    // master: off takes the search, source-check, fetch and
                    // retrieval tools all with it, which is what the subtitle
                    // beside it promises. It deliberately does not take the
                    // extension's four commands with it: one boolean over nine keys
                    // would overwrite a hand-set command with the next tap on any row
                    // (ARCHITECTURE §9.2).
                    SettingsSwitchRow(
                        title = text.settings.webAccess,
                        subtitle = text.settings.webAccessSubtitle,
                        // The version this runtime carries: the one fact about
                        // the extension that is otherwise a terminal away.
                        value = extension.version,
                        checked = settings.enabled,
                        icon = Icons.Filled.Cloud,
                        onChange = { wanted -> store.update { it.copy(enabled = wanted) } },
                    )
                }

                SettingsSection(text.settings.searchWorkflow) {
                    PickerRow(
                        title = text.settings.searchWorkflow,
                        subtitle = text.settings.searchWorkflowSubtitle,
                        value = workflowLabel(settings.workflow, text),
                        icon = Icons.Filled.Tune,
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
                }

                SettingsSection(text.settings.searchProvider) {
                    PickerRow(
                        title = text.settings.searchProvider,
                        subtitle = text.settings.searchProviderSubtitle,
                        value = settings.provider ?: text.settings.providerAutomatic,
                        icon = Icons.Filled.Search,
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
                }

                SettingsSection(text.settings.searchContent) {
                    // Two number fields and no dividers: a field draws its own
                    // outline, so a hairline between two of them is one boundary
                    // too many — the rule is on `SettingsDivider`.
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
                }

                SettingsSection(text.settings.searchAdvanced) {
                    SettingsRow(
                        title = text.settings.searchProxy,
                        subtitle = text.settings.searchProxySubtitle,
                        icon = Icons.Filled.Lock,
                    )
                    var proxy by remember(settings.proxy) {
                        mutableStateOf(settings.proxy.orEmpty())
                    }
                    OutlinedTextField(
                        value = proxy,
                        onValueChange = { value ->
                            proxy = value
                            store.update { it.copy(proxy = value.ifBlank { null }) }
                        },
                        label = { Text(text.settings.searchProxy) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            // The configuration: every key the extension reads that the controls
            // above do not write. Normally that is a list plus one button, and the
            // user never sees the file's syntax; when the file is not a JSON object
            // the text editor is the only way out of it, and it is the way out of
            // it that is shown. See [WebSearchConfigOptions], which draws its own
            // section: the explanations belong outside the card so that the card's
            // last row is the one that acts, which is what makes its press feedback
            // the same as the storage page's add-a-folder.
            if (unreadable) {
                SettingsSection(text.settings.searchConfig) {
                    WebSearchDocumentEditor(store = store)
                }
            } else {
                WebSearchConfigOptions(store = store)
            }

            // Then the one action that resets the *whole* file, in a section of its
            // own: the list above is the keys the page does not own, and a control
            // that throws all of them away — the controls' keys included — is not
            // one more entry in that list. It is shown in both states, because it is
            // also the way out of a file nothing can parse. See [WebSearchResetSection].
            WebSearchResetSection(store = store)

            // Last, because it qualifies everything above it: the extension
            // registers its tools when pi starts, so none of these takes effect
            // until the agent has been restarted.
            SettingsNote(text.settings.searchRestartNote)
        }
    }
}

/**
 * The id both the picker and the file use for "let the extension choose".
 *
 * [WebSearchSettings.provider] stores null for it, because the omission is what the
 * extension's file should say; the picker still needs an id to tick, which is
 * [WebSearchSettings.PROVIDER_AUTO].
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
    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            draft = raw
            raw.trim().toIntOrNull()?.coerceIn(1, max)?.let(onParsed)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
