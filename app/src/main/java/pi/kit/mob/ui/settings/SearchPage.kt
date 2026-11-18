package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.WebSearchSettings
import pi.kit.mob.pi.WebSearchStore
import pi.kit.mob.ui.kit.LocalPiNavigationInset
import pi.kit.mob.ui.kit.PiDialogAnswers
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The bundled `pi-web-access` extension's options.
 *
 * ## What this page is, and what it deliberately is not
 *
 * The extension reads around 110 configuration keys. The page is **three layers**,
 * and the split is the design:
 *
 *  - **Controls** for the keys PiKit is willing to write: the master switch, the
 *    workflow and the provider here, and — one level down, behind the `Advanced`
 *    row — the inline-content limit, the fetch timeout, the proxy and the fake-IP
 *    switch ([SearchAdvancedPage]). The rows here are peer rows in **one group**,
 *    which is the whole point of the frame: the line between the layers is the
 *    page's argument and a single frame is what draws it — everything inside it is
 *    PiKit's, everything outside it is the file's. Each row states its own value,
 *    and the reasons live in the picker sheets.
 *  - **The configuration section** ([WebSearchConfigOptions]), for everything
 *    else: the file's own keys that the controls do not own, listed with their
 *    values, plus one row that adds a new one from the extension's documented
 *    options — each with the description that used to live in a comment.
 *  - **The reset, in a section of its own** ([WebSearchResetSection]), because it
 *    acts on the whole file — the controls' keys as well as the listed ones — and a
 *    control that does that is not one more row in a list of keys.
 *
 * The three fetch parameters used to be rows of this page, and the page read as the
 * whole configuration rather than as the handful of choices a reader makes: what is
 * left here is a switch, two decisions and a way into the numbers. They are one
 * page down rather than in a sheet because they are three controls with their own
 * values, not one question with one answer, and the row that opens them names all
 * three ([SearchAdvancedPage] carries the argument and the alternative).
 *
 * The second layer used to be a plain text editor of the whole annotated document,
 * and it was complete and honest and asked the user to write JSON to set one API
 * key. A switch for every option would be a page nobody reads; a text box is a page
 * only a programmer can use. The list is the middle: *what is set* plus *what can be
 * added*, in the app's own rows. The editor still exists, and is shown only when the
 * file is not a JSON object — the one state the structured controls cannot start
 * from ([WebSearchDocumentEditor]).
 *
 * The line between the layers is still not "the important keys" but **"the keys
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
 * opens a `SettingsPickerSheet` — so each choice is one line whose value is the
 * current one.
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
 * The workflow and the provider are written when the picker closes on a choice, and
 * that is the whole page: the three fields and the fake-IP switch are
 * [SearchAdvancedPage]'s, and their own KDoc says when they write. An added option
 * is written on its own button, from a sheet that is dismissed by it: it is a
 * deliberate act with a value the user typed, and it is not something to do on a
 * keystroke. The restart is the write's own consequence rather than a sentence
 * telling the reader to make one: the extension registers its tools when pi starts,
 * so each write that changes what pi reads at launch schedules it (the trade
 * `WebSearchConfigOptions` records).
 *
 * ## A provider that cannot answer yet says so
 *
 * The picker lists the extension's own providers, and most of the keyed ones cannot
 * answer until a key is in the file — which the page adds through the "add an option
 * or key" row below, not through the picker. A reader who has just chosen `brave`
 * would otherwise see a selected provider and no result and have no way to know the
 * next step, so the choice raises one dialog: which keys that provider needs, and
 * where the value goes ([WebSearchSettings.PROVIDER_CONFIG_KEYS] is the list and its
 * KDoc the rule for who is on it).
 *
 * The provider stays selected while the dialog is open — the dialog is a hint, not a
 * gate — and the keys are named in the extension's own spelling, because that is
 * what the picker below filters on. The alternative, checking the file first and
 * staying quiet when the key is already there, is not available honestly: a key can
 * also come from the environment (`BRAVE_API_KEY`) or from a `$VARIABLE` reference,
 * and this page cannot see either — a dialog that guessed would be silent in exactly
 * the case where the answer is wrong.
 */
@Composable
internal fun SearchPage(
    session: PiAgentSession,
    onBack: () -> Unit,
    onOpenAdvanced: () -> Unit,
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

    var workflowPicker by remember { mutableStateOf(false) }
    var providerPicker by remember { mutableStateOf(false) }
    // The provider the "this one needs a key" dialog is about, and whether the window
    // is up. Two states rather than one because a Miuix window is driven by `show`:
    // clearing the id on dismissal would empty the body while the panel is still
    // animating out (the rule `PiConfirm`'s call sites follow).
    var configProvider by remember { mutableStateOf<String?>(null) }
    var showingConfigDialog by remember { mutableStateOf(false) }

    PiPage(
        title = text.settings.searchTitle,
        subtitle = text.settings.searchPageSubtitle,
        onBack = onBack,
    ) { padding ->
        // The page draws through the navigation strip now, so the strip's height is content
        // padding at the end of the scroll: the last row can still be scrolled clear of the
        // gesture handle, which a bottom padding on the viewport would have prevented.
        val inset = LocalPiNavigationInset.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The page's elastic end — and this page has a one-note state, the
                // extension not being bundled, which must still answer a drag. See
                // `piOverScroll`.
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp + inset),
        ) {
            if (extension == null) {
                // Nothing interactive at all: there is no file for these controls
                // to be about, so the note is the whole page.
                PiSection {
                    SettingsNote(text.settings.searchNotBundled, danger = true)
                }
                return@Column
            }

            if (unreadable) {
                // The controls below are hidden rather than merely refused: the
                // store will not write over a file it could not read, so every one
                // of them would be a control that appears to work and does not.
                PiSection {
                    SettingsNote(text.settings.searchConfigUnreadable, danger = true)
                }
            } else {
                // The extension's version used to ride this heading as a trailing
                // number. It is gone: it is a fact about the bundled image rather
                // than about anything the reader does here, it is not translated,
                // and a version the reader cannot act on was noise on the one line
                // whose whole job is to name the group.
                PiSection(title = text.settings.webAccess) {
                    // One switch over the extension's five keys. It is a real
                    // master: off takes the search, source-check, fetch and
                    // retrieval tools all with it, which is what the summary
                    // beside it promises.
                    //
                    // The four `tools.<name>.enabled` keys it writes are read when pi
                    // registers the extension's tools, so the restart is the write's
                    // own consequence rather than a sentence telling the reader to
                    // make one — the trade `WebSearchConfigOptions` records.
                    SwitchPreference(
                        title = text.settings.webAccess,
                        summary = text.settings.webAccessSubtitle,
                        checked = settings.enabled,
                        onCheckedChange = { wanted ->
                            store.update { it.copy(enabled = wanted) }
                            session.scheduleRestart()
                        },
                    )
                    ArrowPreference(
                        title = text.settings.searchWorkflow,
                        summary = text.settings.searchWorkflowSubtitle,
                        endActions = {
                            SettingsValue(workflowLabel(settings.workflow, text))
                        },
                        onClick = { workflowPicker = true },
                    )
                    ArrowPreference(
                        title = text.settings.searchProvider,
                        summary = text.settings.searchProviderSubtitle,
                        endActions = {
                            SettingsValue(settings.provider ?: text.settings.providerAutomatic)
                        },
                        onClick = { providerPicker = true },
                    )
                    // The numbers moved one page down rather than into a sheet: they
                    // are three controls with three values, and the row says which
                    // three. See [SearchAdvancedPage].
                    ArrowPreference(
                        title = text.settings.searchAdvanced,
                        summary = text.settings.searchAdvancedSubtitle,
                        onClick = onOpenAdvanced,
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
                WebSearchDocumentEditor(store = store, session = session)
            } else {
                WebSearchConfigOptions(store = store, session = session)
            }

            // Then the one action that resets the *whole* file, in a section of its
            // own: the list above is the keys the page does not own, and a control
            // that throws all of them away — the controls' keys included — is not
            // one more entry in that list. It is shown in both states, because it is
            // also the way out of a file nothing can parse. See [WebSearchResetSection].
            WebSearchResetSection(store = store, session = session)
        }
    }

    SettingsPickerSheet(
        show = workflowPicker,
        title = text.settings.searchWorkflow,
        subtitle = text.settings.searchWorkflowSubtitle,
        options = listOf(
            WebSearchSettings.WORKFLOW_NONE,
            WebSearchSettings.WORKFLOW_AUTO_SUMMARY,
            WebSearchSettings.WORKFLOW_SUMMARY_REVIEW,
        ).map { id ->
            SettingsPick(
                id = id,
                label = workflowLabel(id, text),
                description = text.settings.workflowDescription(id),
            )
        },
        selectedId = settings.workflow,
        onPick = { id ->
            workflowPicker = false
            store.update { it.copy(workflow = id) }
        },
        onDismiss = { workflowPicker = false },
    )

    SettingsPickerSheet(
        show = providerPicker,
        title = text.settings.searchProvider,
        subtitle = text.settings.searchProviderSubtitle,
        options = listOf(
            SettingsPick(
                id = WebSearchSettings.PROVIDER_AUTO,
                label = text.settings.providerAutomatic,
                description = text.settings.providerDescription(WebSearchSettings.PROVIDER_AUTO),
            ),
        ) + WebSearchSettings.SEARCH_PROVIDERS.map { id ->
            // The extension's own provider ids, untranslated: they are product
            // names, and they are the spelling its documentation and its `provider`
            // parameter use. The line under each says what it needs, so the list can
            // be read without the extension's README.
            SettingsPick(
                id = id,
                label = id,
                description = text.settings.providerDescription(id),
            )
        },
        selectedId = settings.provider ?: WebSearchSettings.PROVIDER_AUTO,
        onPick = { id ->
            providerPicker = false
            // Omitted and `"auto"` mean the same thing to the extension, and the
            // omission is what it writes.
            store.update {
                it.copy(
                    provider = id.takeIf { chosen -> chosen != WebSearchSettings.PROVIDER_AUTO },
                )
            }
            // The choice has landed — the dialog is a hint, not a gate — and it is
            // raised only for the providers whose configuration the extension's file
            // is the only source of: see `PROVIDER_CONFIG_KEYS` for the rule.
            if (WebSearchSettings.providerConfigKeys(id).isNotEmpty()) {
                configProvider = id
                showingConfigDialog = true
            }
        },
        onDismiss = { providerPicker = false },
        // The one thing a picker of thirty rows owes the user: the list is the
        // extension's, not a selection of favourites.
        footnote = text.settings.searchProviderFootnote(WebSearchSettings.SEARCH_PROVIDERS.size),
        // Thirty-odd rows, so finding the provider is the problem rather than choosing
        // between them. The hint is the catalog's own "search providers": the list is the
        // same kind of list as the model form's provider picker.
        searchHint = text.settings.providerPickSearch,
    )

    // The provider's own configuration, named in the extension's spelling: the reader
    // is about to type those names into the picker below, so the dialog must use the
    // same strings the page does rather than prose names for them.
    val needsConfig = configProvider
    WindowDialog(
        show = showingConfigDialog,
        title = text.settings.searchProviderNeedsConfigTitle,
        onDismissRequest = { showingConfigDialog = false },
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (needsConfig != null) {
                Text(
                    text = text.settings.searchProviderNeedsConfigBody(
                        needsConfig,
                        WebSearchSettings.providerConfigKeys(needsConfig),
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            // One answer, the app's own button shape: there is nothing to decide here,
            // and the dismiss word is the acknowledgement (`PiDialogAnswers`' single
            // form, which `SettingsAlert` also uses).
            PiDialogAnswers(
                dismiss = text.common.ok,
                onDismiss = { showingConfigDialog = false },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
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

