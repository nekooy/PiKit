package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.WebSearchSettings
import pi.kit.mob.ui.kit.LocalPiNavigationInset
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.piOverScroll
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * The search page's numbers, one level down: the inline-content limit, the fetch
 * timeout, the proxy, and the fake-IP switch.
 *
 * ## Why these four are a page and not rows of the search page
 *
 * They were rows of the search page, under the provider picker. The search page's
 * argument is that its group is *the keys PiKit is willing to own*, and the numbers
 * are four of those keys — so they belonged, and the group grew into the settings a
 * reader changes once: the page a first-time reader opens to choose a provider was
 * also a page of three fields and four hundred characters of labels. The same four
 * are not one question with one answer, so they are not a sheet or a dialog either:
 * a picker sheet is a *list* of names (§9.2), and a bottom sheet here would be a
 * second panel for controls the page's own rows already have — with the page's
 * scroll, insets and elastic end left behind. A `PiPage` one level down keeps all of
 * that: the search page's row names what is behind it, the back arrow and the system
 * gesture return to Search (`SettingsPage.SearchAdvanced`'s parent), and the page is
 * a page in the settings stack's own transition and saver.
 *
 * ## The switch is the writer of `ssrf.allowRanges`
 *
 * `fetch_content` was refused on a phone behind a Clash-style TUN proxy, and so were
 * the pages `source_check` fetches and the pages `web_search` pulls in: the
 * extension's `assertPublicAddress` refuses the `198.18.0.0/15` that such a proxy
 * answers DNS with, before anything is connected. The key that exempts the range is
 * `ssrf.allowRanges`, the extension's own default for it is `[]`, and its error
 * message names the fix — so the page offers the fix as a switch rather than as one
 * more key to type into the JSON editor. On writes `["198.18.0.0/15"]`; off writes
 * `[]`, which is the extension's own meaning for "exempt nothing" *and* the stored
 * value the switch reads back. It is an owned key for that reason: a switch whose
 * answer nothing writes is a control that snaps back, and a key that the launch
 * merge re-defaulted would turn itself on again after every restart.
 *
 * The default is on ([WebSearchSettings.allowFakeIpProxyRange]), because the phone
 * this app runs on is the phone such a proxy runs on, and the failure the key
 * prevents is silent: every web tool reports a refused address and the reader has no
 * way to know a range exemption exists. The cost is stated rather than hidden: the
 * key is owned, so a list of the reader's own LAN ranges is replaced by `[]` or by
 * the fake-IP range on the next write anywhere on the page — the alternative was a
 * free-form list editor for one documented value, which is the JSON tax the
 * configuration section was built to remove (ARCHITECTURE §9.3).
 *
 * ## When the fields write
 *
 * The two number fields save as they are typed: neither restarts anything — the
 * extension reads the file per request — and a number that does not parse yet is
 * left alone in the field rather than rounded into something else. The proxy saves
 * the same way. The switch is the one control here that changes what pi registers at
 * launch, so its write schedules the restart itself instead of leaving the reader a
 * sentence to remember.
 */
@Composable
internal fun SearchAdvancedPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val store = session.webSearch
    val settings by store.settings.collectAsState()
    val unreadable by store.unreadable.collectAsState()
    val extension = store.extension

    PiPage(
        title = text.settings.searchAdvanced,
        // The page is still the bundled extension's configuration, and the title has
        // used its line on the sub-page's own name: the bar's second line says which
        // file all four controls write.
        subtitle = text.settings.searchPageSubtitle,
        onBack = onBack,
    ) { padding ->
        // The page draws through the navigation strip, so the strip's height is
        // content padding at the end of the scroll: the last control can still be
        // scrolled clear of the gesture handle (§9.2).
        val inset = LocalPiNavigationInset.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .piOverScroll()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp + inset),
        ) {
            if (extension == null) {
                // Same answer as the search page's: there is no file for these
                // controls to be about, so the note is the whole page.
                PiSection {
                    SettingsNote(text.settings.searchNotBundled, danger = true)
                }
                return@Column
            }

            if (unreadable) {
                // The store will not write over a file it could not read, so every
                // control here would appear to work and would not. The way out is the
                // editor on the search page, and this page has none: its note says so
                // rather than repeating the search page's "fix the text below".
                PiSection {
                    SettingsNote(text.settings.searchAdvancedUnreadable, danger = true)
                }
                return@Column
            }

            // One card of four controls, with no group label: the page's own title is
            // the label, and a second heading over one card would say "Advanced" twice
            // on a page whose whole content is the advanced settings. The switch comes
            // last, under the proxy field it shares a subject with — both are about
            // how a request leaves the phone.
            PiSection {
                NumberField(
                    label = text.settings.inlineContentLimit,
                    stored = settings.maxInlineContentChars,
                    // The extension clamps to its own cap, so a larger number would be
                    // one the page showed and pi did not use.
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
                // The one control on the page the extension reads at launch, so the
                // write restarts the agent — the trade the search page's own switch
                // makes, and `scheduleRestart` waits for a turn in flight.
                SwitchPreference(
                    title = text.settings.searchFakeIpProxy,
                    summary = text.settings.searchFakeIpProxySubtitle,
                    checked = settings.allowFakeIpProxyRange,
                    onCheckedChange = { wanted ->
                        store.update { it.copy(allowFakeIpProxyRange = wanted) }
                        session.scheduleRestart()
                    },
                )
            }
        }
    }
}

/**
 * A whole-number field that saves what parses and keeps what does not.
 *
 * The draft is the field's, and the store is written beside it — never back into it. The
 * first version keyed the draft on the stored number (`remember(stored)`), which looks
 * equivalent and is not: a clamp written to the store re-created the draft, so the digit
 * the reader had just typed was replaced under the cursor. Typing `05` produced `15` —
 * the `0` was clamped to `1` on the way out, the field was rewritten to `1`, and the `5`
 * landed after it. The field therefore keeps exactly what was typed, and a value that
 * arrives from *elsewhere* (a restore, a file edited in the terminal) re-seeds it through
 * [sent], which only ever holds what this field itself last wrote.
 */
@Composable
private fun NumberField(
    label: String,
    stored: Int,
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
            // Clamped on the way to the store and not on the way back into the field: the
            // extension applies its own cap, so a larger number here is one the page would
            // show and pi would not use.
            val accepted = parsed.coerceIn(1, max)
            sent = accepted
            onParsed(accepted)
        },
        label = label,
        singleLine = true,
        keyboardType = KeyboardType.Number,
    )
}

/**
 * The proxy, as one field with its caption.
 *
 * It used to be a label row *and* a field, which named the setting twice: the row's
 * title and the field's label were the same string, and the caption under them was
 * the only part that was not the name again. The caption is the field's own
 * supporting line now, so one control states the setting, what it is for and the
 * value.
 */
@Composable
private fun ProxyField(
    label: String,
    caption: String,
    stored: String?,
    onChanged: (String?) -> Unit,
) {
    var draft by remember(stored) { mutableStateOf(stored.orEmpty()) }
    SettingsTextField(
        value = draft,
        onValueChange = { value ->
            draft = value
            onChanged(value.ifBlank { null })
        },
        label = label,
        supporting = caption,
        singleLine = true,
        keyboardType = KeyboardType.Uri,
    )
}
