package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pi.kit.mob.data.ChatWebAccessDefault
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.WebProviderField
import pi.kit.mob.pi.WebSearchSettings
import pi.kit.mob.pi.WebSearchStore
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.LocalPiNavigationInset
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Web access: one switch, a search section and a fetch section.
 *
 * The page follows dsh's web-search settings shape rather than the old
 * `pi-web-access` page. Each section has a provider menu directly under its
 * title, the selected provider's own fields follow the menu, and the numbers the
 * tool reads are in the same section. There is no second "advanced" page and no
 * free-form configuration editor: the extension's whole configuration is this
 * page, and a file it cannot parse is answered by the one reset action rather
 * than by asking the reader to write JSON on a phone.
 *
 * One section is the app's rather than the extension's: **New chat conversations**
 * chooses the network-tools switch a chat conversation with no file starts from.
 * It is here because this page is where the reader decides what web access the
 * agent has; the value lives in `PiSettings` and `PiAgentSession` applies it at a
 * chat launch (§17), not in `web.json`.
 */
@Composable
internal fun SearchPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val store = session.webSearch
    val settings by store.settings.collectAsStateWithLifecycle()
    val appSettings by session.settingsStore.settings.collectAsStateWithLifecycle()
    val unreadable by store.unreadable.collectAsStateWithLifecycle()
    val available = store.available

    var searchPicker by remember { mutableStateOf(false) }
    var fetchPicker by remember { mutableStateOf(false) }
    var resetConfirming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toasts = LocalPiToast.current
    val haptics = LocalPiHaptics.current

    val pageScroll = rememberScrollState()
    PiPage(
        title = text.settings.searchTitle,
        subtitle = text.settings.searchPageSubtitle,
        onBack = onBack,
        contentScrolled = pageScroll.canScrollBackward,
        // The two provider questions and the reset confirmation, in the page's own
        // backdrop: written after the `PiPage` call they would be opaque windows.
        overlay = {
            SettingsPickerSheet(
                show = searchPicker,
                title = text.settings.searchProvider,
                subtitle = text.settings.searchProviderSubtitle,
                options = listOf(
                    SettingsPick(
                        id = WebSearchSettings.PROVIDER_AUTO,
                        label = text.settings.providerAutomatic,
                        description = text.settings.providerDescription(
                            WebSearchSettings.PROVIDER_AUTO,
                        ),
                    ),
                ) + WebSearchSettings.SEARCH_PROVIDERS.map { provider ->
                    SettingsPick(
                        id = provider.id,
                        label = provider.label,
                        description = text.settings.providerDescription(provider.id),
                    )
                },
                selectedId = settings.searchProvider,
                onPick = { id ->
                    searchPicker = false
                    store.update { it.copy(searchProvider = id) }
                },
                onDismiss = { searchPicker = false },
                footnote = text.settings.searchProviderFootnote(
                    WebSearchSettings.SEARCH_PROVIDERS.size,
                ),
                searchHint = text.settings.providerPickSearch,
            )

            SettingsPickerSheet(
                show = fetchPicker,
                title = text.settings.fetchProvider,
                subtitle = text.settings.fetchProviderSubtitle,
                options = listOf(
                    SettingsPick(
                        id = WebSearchSettings.FETCH_AUTO,
                        label = text.settings.fetchAutomatic,
                        description = text.settings.providerDescription(
                            WebSearchSettings.FETCH_DIRECT,
                        ),
                    ),
                    SettingsPick(
                        id = WebSearchSettings.FETCH_DIRECT,
                        label = text.settings.fetchDirect,
                        description = text.settings.providerDescription(
                            WebSearchSettings.FETCH_DIRECT,
                        ),
                    ),
                ) + WebSearchSettings.WEB_PROVIDERS.filter { it.fetch }.map { provider ->
                    SettingsPick(
                        id = provider.id,
                        label = provider.label,
                        description = text.settings.providerDescription(provider.id),
                    )
                },
                selectedId = settings.fetchProvider,
                onPick = { id ->
                    fetchPicker = false
                    store.update { it.copy(fetchProvider = id) }
                },
                onDismiss = { fetchPicker = false },
                searchHint = text.settings.providerPickSearch,
            )

            // Restoring replaces the extension's whole file, so it asks first — the same
            // destructive-confirm shape the other settings pages use.
            PiConfirm(
                show = resetConfirming,
                title = text.settings.searchConfigRestoreTitle,
                text = text.settings.searchConfigRestoreBody,
                confirm = text.settings.searchConfigRestore,
                dismiss = text.common.cancel,
                destructive = true,
                onConfirm = {
                    resetConfirming = false
                    scope.launch {
                        toasts?.show(
                            store.restoreDefaults().fold(
                                onSuccess = {
                                    session.scheduleRestart()
                                    text.settings.searchConfigRestored
                                },
                                onFailure = { text.settings.searchConfigFailed },
                            ),
                        )
                    }
                },
                onDismiss = { resetConfirming = false },
            )
        },
    ) { padding ->
        val inset = LocalPiNavigationInset.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .piOverScroll()
                .verticalScroll(pageScroll)
                .padding(top = 4.dp, bottom = 12.dp + inset),
        ) {
            if (!available) {
                PiSection {
                    SettingsNote(text.settings.searchNotBundled, danger = true)
                }
                return@Column
            }

            if (unreadable) {
                PiSection {
                    SettingsNote(text.settings.searchConfigUnreadable, danger = true)
                }
            } else {
                PiSection(title = text.settings.webAccess) {
                    SwitchPreference(
                        title = text.settings.webAccess,
                        summary = text.settings.webAccessSubtitle,
                        checked = settings.enabled,
                        onCheckedChange = { wanted ->
                            store.update { it.copy(enabled = wanted) }
                            session.scheduleRestart()
                        },
                    )
                }

                // The chat-mode default is not part of `web.json`: the extension never
                // reads it, `PiAgentSession` does when it builds a chat launch. It sits
                // on this page because this page is where the reader decides what web
                // access the agent has, and a new chat conversation's answer to that is
                // the same question one step earlier. See ARCHITECTURE §9.3 and §17.
                //
                // A dropdown row rather than three radios, like every other "which of a
                // few" on the settings pages. This one keeps its per-option sentence in the
                // panel (`DropdownItem.summary`), and the row's own summary states the
                // option in effect — the explanation of the current value is exactly what
                // the row has room for once the alternatives are behind a tap.
                //
                // The group has **no** heading: the row is the whole of it, and the title
                // the three radios used to share with their heading would now be drawn twice
                // on one card — `PiSection`'s untitled form is the shape for a card that is
                // obviously one thing.
                PiSection {
                    val options = ChatWebAccessDefault.entries
                    val current = appSettings.chatWebAccessDefault
                    OverlayDropdownPreference(
                        entry = DropdownEntry(
                            items = options.map { option ->
                                DropdownItem(
                                    text = chatWebAccessLabel(option, text),
                                    summary = chatWebAccessSummary(option, text),
                                    selected = option == current,
                                    onClick = {
                                        // The library ticks when the panel opens and says
                                        // nothing when a row is picked, and picking the
                                        // value already in effect is a read, not a choice.
                                        if (option != current) {
                                            haptics.choose()
                                            session.settingsStore.update {
                                                it.copy(chatWebAccessDefault = option)
                                            }
                                        }
                                    },
                                )
                            },
                        ),
                        title = text.settings.chatWebAccessDefault,
                        summary = chatWebAccessSummary(current, text),
                    )
                }

                PiSection(title = text.settings.searchSection) {
                    ArrowPreference(
                        title = text.settings.searchProvider,
                        summary = text.settings.searchProviderSubtitle,
                        endActions = {
                            SettingsValue(providerLabel(settings.searchProvider, text))
                        },
                        onClick = { searchPicker = true },
                    )
                    ProviderFields(
                        store = store,
                        providerId = settings.searchProvider,
                        text = text,
                    )
                    NumberField(
                        label = text.settings.searchMaxResults,
                        supporting = text.settings.searchMaxResultsSubtitle,
                        stored = settings.maxResults,
                        min = 1,
                        max = WebSearchSettings.MAX_RESULTS,
                        onParsed = { parsed -> store.update { it.copy(maxResults = parsed) } },
                    )
                }

                PiSection(title = text.settings.fetchSection) {
                    ArrowPreference(
                        title = text.settings.fetchProvider,
                        summary = text.settings.fetchProviderSubtitle,
                        endActions = {
                            SettingsValue(fetchProviderLabel(settings.fetchProvider, text))
                        },
                        onClick = { fetchPicker = true },
                    )
                    ProviderFields(
                        store = store,
                        providerId = settings.fetchProvider.takeIf { it != WebSearchSettings.FETCH_AUTO },
                        text = text,
                    )
                    NumberField(
                        label = text.settings.fetchMaxChars,
                        supporting = text.settings.fetchMaxCharsSubtitle,
                        stored = settings.maxChars,
                        min = 1_000,
                        max = WebSearchSettings.MAX_CHARS,
                        onParsed = { parsed -> store.update { it.copy(maxChars = parsed) } },
                    )
                }

                PiSection(title = text.settings.networkSection) {
                    NumberField(
                        label = text.settings.searchTimeout,
                        supporting = text.settings.searchTimeoutSubtitle,
                        stored = settings.searchTimeoutSeconds,
                        min = 1,
                        max = WebSearchSettings.MAX_TIMEOUT_SECONDS,
                        onParsed = { parsed -> store.update { it.copy(searchTimeoutSeconds = parsed) } },
                    )
                    NumberField(
                        label = text.settings.fetchTimeout,
                        supporting = text.settings.fetchTimeoutSubtitle,
                        stored = settings.fetchTimeoutSeconds,
                        min = 1,
                        max = WebSearchSettings.MAX_TIMEOUT_SECONDS,
                        onParsed = { parsed -> store.update { it.copy(fetchTimeoutSeconds = parsed) } },
                    )
                }
            }

            ResetSection(text = text, onRestore = { resetConfirming = true })
        }
    }

}

/** The provider's own fields, directly under the menu that selected it. */
@Composable
private fun ProviderFields(store: WebSearchStore, providerId: String?, text: Strings) {
    if (providerId == null || providerId == WebSearchSettings.PROVIDER_AUTO) return
    val provider = WebSearchSettings.provider(providerId) ?: return
    if (provider.fields.isEmpty()) return
    val settings by store.settings.collectAsStateWithLifecycle()
    val config = settings.configOf(provider.id)
    val needsKey = provider.fields.contains(WebProviderField.API_KEY) &&
        !provider.keyOptional &&
        config.apiKey.isBlank()
    if (needsKey) {
        Text(
            text = text.settings.providerNeedsKey,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SettingsFieldTextInset, end = SettingsFieldTextInset, bottom = 4.dp),
        )
    }
    provider.fields.forEach { field ->
        when (field) {
            WebProviderField.API_KEY -> ProviderTextField(
                label = text.settings.providerApiKey,
                supporting = text.settings.providerApiKeySubtitle,
                stored = config.apiKey,
                password = true,
                onChanged = { value -> store.updateProvider(provider.id) { it.copy(apiKey = value) } },
            )
            WebProviderField.BASE_URL -> ProviderTextField(
                label = text.settings.providerBaseUrl,
                supporting = if (provider.baseUrlRequired) {
                    text.settings.providerBaseUrlRequired
                } else {
                    text.settings.providerBaseUrlSubtitle
                },
                stored = config.baseUrl,
                placeholder = providerBaseUrlPlaceholder(provider.id),
                keyboardType = KeyboardType.Uri,
                onChanged = { value -> store.updateProvider(provider.id) { it.copy(baseUrl = value) } },
            )
            WebProviderField.ACCOUNT_ID -> ProviderTextField(
                label = text.settings.providerAccountId,
                supporting = text.settings.providerAccountIdSubtitle,
                stored = config.accountId,
                onChanged = { value -> store.updateProvider(provider.id) { it.copy(accountId = value) } },
            )
            WebProviderField.MODEL -> ProviderTextField(
                label = text.settings.providerModel,
                supporting = text.settings.providerModelSubtitle,
                stored = config.model,
                placeholder = providerModelPlaceholder(provider.id),
                onChanged = { value -> store.updateProvider(provider.id) { it.copy(model = value) } },
            )
        }
    }
}

@Composable
private fun ProviderTextField(
    label: String,
    supporting: String,
    stored: String,
    onChanged: (String) -> Unit,
    password: Boolean = false,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var draft by remember(stored) { mutableStateOf(stored) }
    SettingsTextField(
        value = draft,
        onValueChange = { value ->
            draft = value
            onChanged(value)
        },
        label = label,
        supporting = supporting,
        placeholder = placeholder,
        password = password,
        keyboardType = keyboardType,
    )
}

private fun providerLabel(id: String, text: Strings): String =
    if (id == WebSearchSettings.PROVIDER_AUTO) text.settings.providerAutomatic
    else WebSearchSettings.provider(id)?.label ?: id

private fun fetchProviderLabel(id: String, text: Strings): String = when (id) {
    WebSearchSettings.FETCH_AUTO -> text.settings.fetchAutomatic
    WebSearchSettings.FETCH_DIRECT -> text.settings.fetchDirect
    else -> WebSearchSettings.provider(id)?.label ?: id
}

private fun chatWebAccessLabel(option: ChatWebAccessDefault, text: Strings): String = when (option) {
    ChatWebAccessDefault.OFF -> text.settings.chatWebAccessOff
    ChatWebAccessDefault.ON -> text.settings.chatWebAccessOn
    ChatWebAccessDefault.LAST -> text.settings.chatWebAccessLast
}

private fun chatWebAccessSummary(option: ChatWebAccessDefault, text: Strings): String =
    when (option) {
        ChatWebAccessDefault.OFF -> text.settings.chatWebAccessOffSubtitle
        ChatWebAccessDefault.ON -> text.settings.chatWebAccessOnSubtitle
        ChatWebAccessDefault.LAST -> text.settings.chatWebAccessLastSubtitle
    }

private fun providerBaseUrlPlaceholder(id: String): String? = when (id) {
    "deepseek" -> "https://api.deepseek.com"
    "zai" -> "https://api.z.ai/api/paas/v4"
    "exa" -> "https://api.exa.ai"
    "tavily" -> "https://api.tavily.com"
    "ollama" -> "https://ollama.com"
    "jina" -> "https://s.jina.ai"
    "openai" -> "https://api.openai.com/v1"
    "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
    "kimi" -> "https://api.moonshot.cn/v1"
    "xai" -> "https://api.x.ai/v1"
    "searchapi" -> "https://www.searchapi.io/api/v1"
    else -> null
}

private fun providerModelPlaceholder(id: String): String? = when (id) {
    "openai" -> "gpt-4.1-mini"
    "gemini" -> "gemini-2.5-flash"
    "xai" -> "grok-4.5"
    else -> null
}

/**
 * A whole-number field that saves what parses and keeps what does not.
 *
 * The draft is the field's, and the store is written beside it — never back into
 * it. Keying the draft on the stored number made a clamp rewrite the digit under
 * the cursor; this keeps exactly what was typed, and a value arriving from
 * elsewhere re-seeds it through [sent].
 */
@Composable
private fun NumberField(
    label: String,
    supporting: String,
    stored: Int,
    min: Int,
    max: Int,
    onParsed: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf(stored.toString()) }
    var sent by remember { mutableStateOf(stored) }
    if (stored != sent) {
        draft = stored.toString()
        sent = stored
    }
    SettingsTextField(
        value = draft,
        onValueChange = { raw ->
            draft = raw
            val parsed = raw.trim().toIntOrNull() ?: return@SettingsTextField
            val accepted = parsed.coerceIn(min, max)
            sent = accepted
            onParsed(accepted)
        },
        label = label,
        supporting = supporting,
        singleLine = true,
        keyboardType = KeyboardType.Number,
    )
}

@Composable
private fun ResetSection(text: Strings, onRestore: () -> Unit) {
    PiSection {
        // A tappable action row, not a fact row: it has an arrow and the error role,
        // because tapping it asks the question that resets the extension's file.
        ArrowPreference(
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
            titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
            onClick = onRestore,
        )
    }
}
