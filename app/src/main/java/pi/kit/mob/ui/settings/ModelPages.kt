package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pi.kit.mob.data.CustomApi
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.ModelSettings
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.PiSettings
import pi.kit.mob.data.normalizeApiBaseUrl
import pi.kit.mob.data.rememberedThinkingLevels
import pi.kit.mob.locales.DiscoveryProblem
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.DiscoveredModel
import pi.kit.mob.pi.ModelDiscovery
import pi.kit.mob.pi.ModelDiscoveryClient
import pi.kit.mob.pi.ModelSource
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.anyProviderModelFacts
import pi.kit.mob.pi.clampThinkingLevel
import pi.kit.mob.pi.modelDefinitionFacts
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiGroupAction
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The saved model configurations, with the active one marked.
 *
 * Selecting a configuration is a separate tap from editing it: editing writes on
 * Save, so making "activate" an implicit part of opening the form would leave the
 * user unable to look at one without switching to it.
 *
 * Thinking level is deliberately not a *control* on this page. It used to be the row
 * at the top, beside the configuration it applies to — but the composer's chip is
 * where a conversation changes it, and two controls for one setting are two places to
 * look. The chip's sheet is the only one. It is read here, though, in each row's own
 * line: the level that configuration's model would run at is a fact about the model,
 * and a fact belongs beside the model it is about.
 *
 * The shell is Miuix's top bar, whose subtitle is the count of saved configurations —
 * what the page is. A row states the rest: the configuration's name (its model id until
 * the user names it), the provider it is an endpoint and a key for, the level its model
 * runs at, and whether a key is saved.
 */
@Composable
internal fun ModelPage(
    session: PiAgentSession,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val store = session.settingsStore.profiles
    val toasts = LocalPiToast.current
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }
    // Collected rather than copied into local state: the store publishes what it
    // has, so a profile saved on the form behind this page, or a model changed
    // from the composer, is on this page the moment it is committed. It used to be
    // a `remember`ed copy refreshed by hand after each action here, which meant
    // any change made *elsewhere* was invisible until the page was re-entered.
    val config by store.snapshots.collectAsState()
    // The level each row reports is a saved fact rather than a field on this page, so
    // it is collected here: a level changed from the composer's chip moves these lines
    // while the page is open.
    val preferences by session.settingsStore.settings.collectAsState()
    val profiles = config.profiles
    val activeId = config.activeProfileId
    val text = strings
    val context = LocalContext.current

    // The catalogue answer this list's profiles are about to be edited against, started
    // here rather than when the form opens.
    //
    // The check is one pi process — node, then pi's bundle — and it is the only way to see
    // which ids pi's own catalogue contains. Starting it as the *list* is opened means the
    // answer is usually already cached by the time the user has picked a profile, which is
    // the difference between a form that flashes a status row and one that is simply ready.
    // It costs nothing when the provider is the one already checked (the answer is cached
    // for the app run, under the catalogue state it was read in) and nothing at all for a
    // custom endpoint, which pi has no catalogue for.
    //
    // The key is deliberately blank: what is being read is a fact about pi's bundle, and
    // `catalogueProbe` supplies the placeholder credential that makes pi compose the
    // provider's catalogue at all. See it for why an empty key field must not be the thing
    // that decides whether the app can see the catalogue.
    val activeProvider = config.activeProfile?.providerEntry
    val discovery = remember(context, session.env) { ModelDiscoveryClient(context, session.env) }
    LaunchedEffect(activeProvider?.id) {
        val chosen = activeProvider ?: return@LaunchedEffect
        if (chosen in PiProvider.needsBaseUrl) return@LaunchedEffect
        discovery.catalogueProbe(chosen, "")
    }

    // What the thinking row used to show lived here — the live levels, the
    // remembered ones and the clamped preference. The row is gone: the composer's
    // chip is the one control for the setting. `thinkingLevelsFor` and
    // `clampThinkingLevel` are still what that sheet and the chip's label use, and
    // `thinkingLevelFor` below reads the saved pair for a row's line.

    PiPage(
        title = text.settings.profilesTitle,
        subtitle = text.settings.modelCount(profiles.size),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            // An empty list is not drawn as an empty frame: a card with no rows in it is a
            // slab of nothing under a heading. The store refuses to delete the last
            // configuration, so this is only reachable before the first one is saved — the
            // action below is the page then.
            if (profiles.isNotEmpty()) {
                PiSection(title = text.settings.savedProfiles) {
                    profiles.forEach { profile ->
                        // The row *is* the switch: a radio says which configuration the
                        // agent launches with, and tapping the one already marked is a read
                        // rather than a change. The edit and delete pair sits at the row's
                        // end because a settings row's trailing slot is where its controls
                        // live, and the mark that says "this is the one" is the radio's own —
                        // one glyph per meaning rather than a tick beside a button.
                        RadioButtonPreference(
                            title = profile.displayName,
                            summary = subtitleFor(
                                profile,
                                text,
                                thinkingLevelFor(preferences, profile.modelId),
                            ),
                            selected = profile.id == activeId,
                            endActions = {
                                IconButton(onClick = { onEdit(profile.id) }) {
                                    Icon(
                                        imageVector = MiuixIcons.Edit,
                                        contentDescription = text.settings.editProfile,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                IconButton(onClick = { pendingDelete = profile }) {
                                    Icon(
                                        imageVector = MiuixIcons.Delete,
                                        contentDescription = text.settings.deleteProfile,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            },
                            onClick = {
                                // Tapping the profile that is already active is a read, not
                                // a switch: restarting there tore down a healthy agent (and
                                // any turn that was streaming) to change nothing.
                                if (profile.id != activeId) {
                                    store.setActive(profile.id)
                                    // Provider, model and key are only read when the
                                    // process is spawned, so the running agent has to be
                                    // replaced for the switch to be real. The page itself
                                    // needs no reload: it is collecting the store.
                                    session.scheduleRestart()
                                }
                            },
                        )
                    }
                }
            }

            // The first-run state is a sentence rather than an empty frame: the page has
            // to say why there is nothing here and what to do about it, and the action
            // under it is the same one the list's foot carries once there is something in
            // it. With profiles saved this is the page's one additive row.
            if (profiles.isEmpty()) {
                PiSection(title = text.settings.profilesTitle) {
                    SettingsNote(text.settings.profilesEmptyHint)
                }
            }
            PiGroupAction(
                text = text.settings.addProfile,
                onClick = { onEdit("") },
            )
        }
    }

    // Deleting a configuration takes the model out of the list and discards what the user
    // declared about it — the image switch and the two numbers — and nothing on this page
    // can put that back, so the question carries the error role on its confirming word.
    val deleting = pendingDelete
    PiConfirm(
        show = deleting != null,
        title = text.settings.deleteProfileTitle,
        text = text.settings.deleteProfileBody(deleting?.displayName.orEmpty()),
        confirm = text.settings.deleteProfile,
        dismiss = text.settings.cancel,
        destructive = true,
        onConfirm = {
            val target = deleting ?: return@PiConfirm
            val deleted = store.delete(target.id)
            if (deleted) {
                if (target.id == activeId) session.scheduleRestart()
            } else {
                // The last profile cannot go: pi would have nothing to launch with. A
                // toast rather than a strip under the list, because it is the answer to
                // the press and the list itself is unchanged.
                toasts?.show(text.settings.lastProfile)
            }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
    )
}

/**
 * A row's own line: the provider, the model, the level that model runs at, and whether
 * a key is saved.
 *
 * The level is added by the redesign and the other three were the line before it, in
 * the same order — a configuration is a provider and a key, so which provider and
 * whether the key is there are the two facts that tell one row from another when two
 * are configured for the same model.
 */
private fun subtitleFor(profile: ModelProfile, text: Strings, level: String?): String {
    val provider = profile.providerEntry?.label
        ?: profile.provider.ifBlank { text.settings.noProfileProvider }
    val model = profile.modelId.ifBlank { text.settings.noModelChosen }
    val key = if (profile.apiKey.isBlank()) text.settings.noKey else text.settings.keySaved
    return listOfNotNull(provider, model, level, key).joinToString(" · ")
}

/**
 * The level [modelId] would run at, or null when nothing is known about it.
 *
 * `clampThinkingLevel` against the levels pi last reported **for that id**
 * (`rememberedThinkingLevels`), which is the pair the composer's chip shows, and for the
 * same reason: pi walks a level the model does not have *forwards*, so asking a DeepSeek
 * model for `medium` leaves `high`. Showing the raw preference beside a model would
 * therefore be showing a level that model never runs at. No memory of that id means
 * nothing to clamp against, and the saved preference is what the row shows until pi has
 * answered for it once.
 */
private fun thinkingLevelFor(preferences: PiSettings, modelId: String): String? =
    modelId.takeIf { it.isNotBlank() }
        ?.let {
            clampThinkingLevel(
                preferences.thinkingLevel,
                preferences.rememberedThinkingLevels(it),
            )
        }



/**
 * The model form.
 *
 * Nothing here touches the store until Save. That is the point of the rewrite:
 * the old page wrote on every keystroke, which restarted the agent while the
 * user was halfway through a model id and left no way to abandon an edit.
 *
 * ## Why leaving asks
 *
 * Holding edits until Save is what makes this page able to *lose* them: the back
 * arrow, the Cancel button and the system's back gesture all leave, and the report
 * was that adding a model and swiping back saved nothing and said nothing — the
 * list behind the form simply did not have it. So every way out goes through
 * [requestLeave], which asks when the form differs from what is stored and leaves
 * directly when it does not.
 *
 * The back *gesture* is the page's own: `PiPage` registers it whenever it is given an
 * `onBack`, and this page passes [requestLeave]. A Miuix sheet or dialog is a window of
 * its own and takes the gesture before the page behind it, which is the behaviour this
 * page's pickers depend on (a swipe while the provider list is up closes the list, not
 * the page under it). The one thing a handler cannot express is [leaving], because it is
 * always enabled: the flag is therefore read in [requestLeave] itself, where the decision
 * is made.
 */
@Composable
internal fun ModelEditPage(
    session: PiAgentSession,
    profileId: String,
    onBack: () -> Unit,
) {
    val store = session.settingsStore.profiles
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Both modal editors — the provider list and the fetched model list — are Miuix
    // sheets, which are windows of their own: their state is a boolean in this composable
    // rather than an entry in a host, and the sheet body reads the choice it was given.
    val toasts = LocalPiToast.current
    // The saved preference, read for the thinking level each model row reports. It is not
    // a field of this form: the composer's chip is the one control for it.
    val preferences by session.settingsStore.settings.collectAsState()

    val existing = remember(profileId) { store.profiles.firstOrNull { it.id == profileId } }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var provider by remember { mutableStateOf(existing?.providerEntry) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    // The wire protocol a custom endpoint speaks. Only drawn for `pikit-custom`;
    // a built-in provider's client is pi's own. Blank resolves to
    // `CustomApi.DEFAULT_ID`, which is what a profile written before the field
    // existed already did.
    var api by remember { mutableStateOf(existing?.api.orEmpty()) }

    // The models this provider may answer with, and which of them the agent is
    // launched with. Held as a list rather than as one field because a provider is
    // an endpoint and a key: wanting a second model from the same account is not a
    // reason to type the key again.
    var models by remember { mutableStateOf(existing?.selectableModels.orEmpty()) }
    var activeModel by remember { mutableStateOf(existing?.modelId.orEmpty()) }
    // What the user has said about each model pi's catalogue does not contain: whether it
    // takes images, and the two numbers pi's `models.json` entry has to name. Held here
    // until Save, like every other field, and an id with nothing said about it has no
    // entry in this map at all — a model nobody has filled anything in for resolves
    // through pi's own fallback copy, which is the state the app should leave alone.
    var settings by remember { mutableStateOf(existing?.modelSettings.orEmpty()) }
    // What the field and the "add" button work on: a model id that is not in the
    // list yet. Cleared when it is added, so the button cannot add it twice.
    var draft by remember { mutableStateOf("") }

    var fetching by remember { mutableStateOf(false) }
    // The fetch failure, already worded: [DiscoveryProblem]s are rendered through
    // `Strings.discoveryProblem` at the point of capture, so the state holds text the
    // interface owns rather than the throw site's English.
    //
    // It is the page's one *dialog*, and it is a dialog rather than a toast because of
    // its length: a provider that answered with an auth failure and a wrong endpoint
    // produces two paragraphs, and a pill that disappears after three seconds is not
    // where a reader can read two paragraphs. The one-line refusal that used to share
    // this state — "choose a provider first" — is a toast now, because it is one
    // sentence and it is about the press rather than about the endpoint.
    var discoveryError by remember { mutableStateOf<String?>(null) }

    // Whether pi's own catalogue already contains each model id, what pi resolves an id
    // it does not contain to, and what it says about each id it *does* contain — the three
    // facts the per-model controls and the save below are the only readers of. Null means
    // "no answer" for the first, and the section draws a status row while it is null,
    // because until the answer is in there is no way to know which mechanism a statement
    // about an id belongs in: pi ignores a `modelOverrides` entry for an id it did not
    // resolve, and a `models` entry for an id it did *replaces* what it knows.
    var catalogueIds by remember { mutableStateOf<Set<String>?>(null) }
    // Per id, as pi's catalogue reports it — from `models-store.json` when the launch path
    // has refreshed it, and from a throwaway pi for an id the store does not hold. One map
    // rather than the two this used to carry (`catalogueFacts` for the fallback model and a
    // second map per id): a model's facts are per id, full stop, and the fallback model is
    // only ever the answer for an id that is *not* in here.
    var catalogueIdFacts by remember { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    // The fallback model's own facts, which is what an id the catalogue does *not* contain
    // starts at. Kept apart from [catalogueIdFacts] because it is not about an id the user
    // has: it is the model pi resolves an unknown id to. See `fallbackFacts` below.
    var catalogueFallback by remember { mutableStateOf<JsonObject?>(null) }
    // Why the check did not answer, when it did not. Shown in the status row so the reader
    // is told what to do rather than only that nothing can be declared yet — see
    // `CatalogueProbe.error`. A [DiscoveryProblem] rather than a string so the wording is
    // the interface's own.
    var catalogueError by remember { mutableStateOf<DiscoveryProblem?>(null) }
    // The catalogue state the answer above was read in, recorded on save. See
    // `CatalogueProbe.basis` for why it is the probe's own snapshot and not a later one.
    var catalogueSeen by remember { mutableStateOf<String?>(null) }
    // Starts true, because the effect that runs the check runs after the first frame: a
    // status row that said "could not read pi's catalogue" for one frame and "reading…"
    // for the next would be the same jump the section is built to avoid.
    var catalogueChecking by remember { mutableStateOf(true) }

    // Set once the form has been left, so a second back press during the exit
    // transition cannot open the question again over a page that is already going.
    var leaving by remember { mutableStateOf(false) }
    var askingToSave by remember { mutableStateOf(false) }
    // The ✕ on a model row, waiting for an answer. It used to remove the row outright, and
    // that is the one edit on this page with no way back: [removeModel] also drops the three
    // declarations the user made about that model, and the page's Save cannot restore them
    // because it never learns they existed. The other destructive actions in the app ask
    // first, and this one now matches them.
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    // Bumped by the section's retry row. The check is the one thing on this page that can
    // fail for a reason outside the app — a runtime that will not start — so it is the one
    // thing here that has to be askable twice.
    var retries by remember { mutableIntStateOf(0) }

    val discovery = remember(context, session.env) { ModelDiscoveryClient(context, session.env) }
    val isNew = existing == null
    val text = strings
    // A custom endpoint is the one provider pi has no catalogue for, which changes both
    // questions this section asks: there is nothing to check the ids against, and there is
    // no fallback model to fill the three controls from.
    val isCustomEndpoint = provider in PiProvider.needsBaseUrl
    // What pi's store says about an id a custom endpoint happens to share with a
    // built-in provider — `claude-sonnet-4-5` behind a relay is the same model, and
    // its window/max-out/image facts belong to the *id*, not to the host. Loaded from
    // `models-store.json` across every provider (see `anyProviderModelFacts`); a match
    // fills the three controls on add, and only an id nothing names falls back to pi's
    // hard-coded 128k/16k. Keyed on the custom flag so switching *to* a custom
    // endpoint loads the store rather than keeping a built-in provider's empty map.
    var customIdFacts by remember(isCustomEndpoint) {
        mutableStateOf(
            if (isCustomEndpoint) {
                anyProviderModelFacts(File(session.env.piConfigDir, PiAgentSession.CATALOGUE_STORE_NAME))
            } else {
                emptyMap()
            },
        )
    }
    // What the section's status row has to say, if anything.
    //
    // A provider has to be chosen before a model id means anything — the ids are the
    // provider's, and `addModel` below records one without asking — so the state a form
    // can be in after typing an id and tapping add with no provider picked is "a model,
    // and no endpoint to ask about it". That used to fall through to the *catalogue*
    // status row, which said pi's catalogue could not be read: true, and the wrong
    // subject. The row's retry did nothing there either, because the missing half was the
    // provider and not the answer.
    val needsProvider = provider == null && models.isNotEmpty()
    // Whether the section has anything to draw at all. What it needs is the *set of ids* pi's
    // catalogue describes, because that set is the whole of the routing decision a save makes,
    // and it now comes from `models-store.json` — no process, and covered for every provider.
    // An **empty** set is an answer ("pi catalogues no model of this provider") and is not the
    // same state as no answer, which is why the test is on the set and not on its contents.
    val answered = isCustomEndpoint || catalogueIds != null
    // ...and whether the section has something to say about *not* having one. A form with no
    // model id yet has nothing for a check to be about, so it gets its own informational row
    // rather than a status about a process that was never started.
    val awaitingCatalogue = !answered && models.isNotEmpty()
    // What an untouched box starts at for an id that is *not* in the catalogue: the model pi
    // would have resolved it to (`buildFallbackModel`'s copy of the provider's default). That
    // is the one thing the store cannot answer — it holds pi.dev's half only — so it still
    // comes from the throwaway pi, and it is null when that process could not run. The numbers
    // then read as unknown, which is what the entry writer does with a missing fallback too:
    // it names nothing rather than pi's hard-coded 128k/16k, which is the bug this whole path
    // is written around.
    //
    // A custom endpoint has no such model at all — unless the id it is offering is
    // one pi already catalogues under another provider, in which case those facts
    // are the right starting point. `customIdFacts` is that lookup; an id nothing
    // names still falls back to pi's hard-coded 128k/16k.
    val fallbackFacts = if (isCustomEndpoint) null else catalogueFallback

    /**
     * Asks pi which of this provider's model ids its own catalogue already contains, and
     * what it resolves an id it does not contain to.
     *
     * One throwaway pi process per (provider, catalogue state) per app run, and the answer
     * is cached by that state — see `ModelDiscoveryClient.catalogueProbe`. Nothing waits on
     * it: the section draws one status row until it is in, and this effect is the only
     * thing that can move it. There is no debounce any more, and its absence is the point:
     * the delay existed to stop a keystroke in the key field from starting a process, and
     * the check no longer depends on the key at all — so it was 800ms of nothing on every
     * visit, including the first.
     *
     * `models.isEmpty()` skips it because a form with no models has nothing for the answer
     * to be about: the section draws one informational row. Keying on the *flip* rather
     * than on the list means adding the second model does not start a second process.
     *
     * The failure state is a row the user can tap, not a dead end: an answer that never
     * came leaves the controls undrawable (see [answered]), so the section has to be able
     * to ask again. See `retries`.
     */
    LaunchedEffect(provider?.id, models.isEmpty(), retries) {
        val chosen = provider
        catalogueIds = null
        catalogueIdFacts = emptyMap()
        catalogueFallback = null
        catalogueError = null
        catalogueSeen = null
        if (chosen == null || chosen in PiProvider.needsBaseUrl || models.isEmpty()) {
            catalogueChecking = false
            return@LaunchedEffect
        }
        catalogueChecking = true
        // `finally` rather than a plain assignment at the end: this effect is cancelled
        // when the page leaves the composition (the back arrow, a tab switch), and a
        // cancelled coroutine does not reach the statement after its suspension point.
        // The flag used to be left raised, which showed "reading…" the next time the page
        // was opened even though no process was running. The probe's own process is closed
        // on every path by `fetchFromPiCatalog`'s `finally`, so this is only about the row.
        try {
            val probe = discovery.catalogueProbe(chosen, apiKey)
            // A check that answered is used even when it found nothing; one that did *not*
            // answer leaves the ids null, which is what keeps the controls off screen — an
            // empty set from a failed check would mean "pi knows none of your models" and
            // would have the page write a replacing `models` entry for a model pi describes.
            if (probe != null && probe.answered) {
                catalogueIds = probe.ids
                catalogueIdFacts = probe.facts
                catalogueFallback = probe.fallback?.let(::modelDefinitionFacts)
                catalogueSeen = probe.basis
            } else {
                catalogueError = probe?.error
            }
        } finally {
            catalogueChecking = false
        }
    }

    /**
     * Whether the form differs from what is stored.
     *
     * Compared field by field rather than tracked by a flag set in every writer:
     * there are eight of them, and a flag is one more thing to forget when the next
     * field is added. [draft] counts — a model id typed into the field and not added
     * is exactly the edit the report was about.
     */
    val dirty = if (existing == null) {
        name.isNotBlank() || provider != null || apiKey.isNotBlank() || baseUrl.isNotBlank() ||
            api.isNotBlank() ||
            models.isNotEmpty() || activeModel.isNotBlank() || settings.isNotEmpty() ||
            draft.isNotBlank()
    } else {
        name != existing.name ||
            provider != existing.providerEntry ||
            apiKey != existing.apiKey ||
            baseUrl != existing.baseUrl ||
            api != existing.api ||
            models != existing.selectableModels ||
            activeModel != existing.modelId ||
            settings != existing.modelSettings ||
            draft.isNotBlank()
    }

    /**
     * Puts [raw] into this provider's model list and returns the new list.
     *
     * A model already in the list is not added twice: picking the same one again is
     * a way of choosing it, not of duplicating the row. Re-adding an existing id
     * therefore *selects* it; a **new** id is only appended and leaves the tick on
     * the model already chosen — taking it would make every fetch-list pick steal
     * the selection from the model the user was actually using. It becomes active
     * only when nothing was selected yet.
     *
     * [prefill] fills the three parameter controls from a catalogue or `/models`
     * answer. For a custom endpoint that is the difference between a row that says
     * "unknown" and one that already carries the window the id is known for. A
     * prefill only lands when the user has said nothing about that id yet, so
     * re-adding a model never overwrites their numbers.
     */
    fun addModel(raw: String, prefill: ModelSettings? = null): List<String> {
        val model = raw.trim()
        if (model.isBlank()) return models
        val already = model in models
        models = if (already) models else models + model
        if (already || activeModel.isBlank()) activeModel = model
        if (prefill != null && model !in settings) {
            settings = settings + (model to prefill)
        }
        draft = ""
        return models
    }

    /** [ModelSettings] taken off a catalogue fact object, or null when it names none of them. */
    fun prefillFrom(facts: JsonObject?): ModelSettings? {
        if (facts == null) return null
        val window = facts.number("contextWindow")
        val max = facts.number("maxTokens")
        val images = facts.hasImageInput()
        val inherited = JsonObject(
            facts.filterKeys { it != "api" && it != "baseUrl" },
        )
        if (window == null && max == null && !images && inherited.isEmpty()) return null
        return ModelSettings(
            images = images.takeIf { it },
            contextWindow = window,
            maxTokens = max,
            // The whole catalogue entry minus the host: `cost`,
            // `thinkingLevelMap`, `reasoning`, `samplingParams` and the two
            // numbers travel with the id so a custom endpoint does not lose the
            // price table or the thinking levels of a model it shares a name with.
            inherited = inherited.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Drops [model] from the list, moving the active mark if it was on it.
     *
     * Removing the active model does not silently pick another one: with nothing
     * marked the page says so, and Save refuses until the user chooses. Picking for
     * them would make a removal look like a switch.
     */
    fun removeModel(model: String) {
        models = models.filterNot { it == model }
        // The settings go with the row: a model that is no longer offered must not keep an
        // entry in pi's own catalog. `writtenModels` is what makes the entry come *out* of
        // pi's file rather than stay in it.
        settings = settings - model
        if (activeModel == model) activeModel = ""
    }

    /**
     * Why the form cannot be stored yet, or null when it can.
     *
     * Separate from [save] because the leave question asks it too, and because it must not
     * change anything: [save] adds the model in the id field as it stores, so a check that
     * called [addModel] would make the *question* mutate the form it is asking about. The
     * two therefore have to agree on the same inputs — a form whose refusal the dialog
     * cannot see would offer a Save button that always fails, which is the dead loop the
     * report described: press Save, get refused, land back on the page under the dialog.
     */
    fun refusal(): String? {
        val chosen = provider
        return when {
            chosen == null -> text.settings.needProvider
            // Caught here rather than at launch: pi's error for an unregistered provider
            // names the provider, not the missing field, and the user has no way to tell
            // the two apart.
            chosen in PiProvider.needsBaseUrl && baseUrl.isBlank() -> text.settings.needBaseUrl
            // A filled field that normalises to nothing is the same trap from the other
            // side. `localhost:11434` has a scheme-looking colon and no host; saved as
            // it is, the launch path drops it and a custom endpoint dies with
            // `Unknown provider "pikit-custom"` — neither of which names the field.
            // The check is on the *normalised* value because that is what the writer
            // stores; asking "is the field non-blank" is how the two disagree.
            baseUrl.isNotBlank() && normalizeApiBaseUrl(baseUrl).isEmpty() ->
                text.settings.invalidBaseUrl
            // A provider with a list but nothing ticked would launch with no model. The
            // row's mark is the choice, so this asks for one rather than picking for the
            // user — and the id typed in the field counts, because Save adds it.
            draft.isBlank() && (models.isEmpty() || activeModel.isBlank()) -> text.settings.needModel
            else -> null
        }
    }

    /**
     * Writes the form into the profile store, or refuses and says why.
     *
     * Returns true when the profile was stored. The refusals are [refusal]'s, asked of the
     * same values the dialog asked about, so a button the dialog offered cannot fail here.
     */
    fun save(): Boolean {
        refusal()?.let { message ->
            toasts?.show(message)
            return false
        }
        val chosen = provider ?: return false
        // Saving adds whatever is still in the field: typing a model
        // and pressing Save is the obvious way to add one, and
        // losing it because the button beside the field was not
        // pressed would be a trap.
        val listed = addModel(
            draft,
            prefill = if (isCustomEndpoint) prefillFrom(customIdFacts[draft.trim()]) else null,
        )
        val wasActive = store.activeId == existing?.id
        // Everything the user has said about every id still in the list, catalogued or not.
        // Which *mechanism* carries each statement — a `models` entry that has to stand
        // alone, or a `modelOverrides` entry that pi merges — is decided at launch time by
        // `modelDefinitions`, from the catalogue answer recorded below. Keeping one map here
        // rather than two is what makes the page's three controls the same three controls
        // for every model.
        //
        // Always the form's own map, filtered to the ids still listed. The previous rule —
        // "no catalogue answer means keep `existing.modelSettings` untouched" — was wrong
        // for a custom endpoint, which never runs the catalogue probe and so had
        // `catalogueIds == null` on every visit: the three numbers and the image switch the
        // user had just set were dropped on Save and the reopened form showed empty rows
        // ("自定义参数保存了再打开就丢失"). A check that could not run still cannot delete
        // what the user chose, because the form's map *starts* as `existing.modelSettings`
        // and only the controls move it — so filtering it is both the new edits and the old
        // ones, which is exactly the carry-over that rule was reaching for.
        val kept = settings.filterKeys { it in listed }
        // The catalogue answer, recorded so the launch path can route each id without a check
        // of its own. Only the ids still in the list are kept: the question this answers is per
        // stored setting, and an id that has left the profile has no setting left to route.
        //
        // No answer at all carries the old one over rather than clearing it, for the same
        // reason `kept` carries the old settings over: a check that could not run must not
        // turn a catalogued id into an uncatalogued one, which is what would make the app
        // write a `models` entry that *replaces* pi's own entry for it.
        //
        // A custom endpoint records nothing — it has no catalogue, and its whole provider
        // object is rebuilt on every launch.
        val known = if (isCustomEndpoint || catalogueIds == null) {
            existing?.knownCatalogueIds.orEmpty()
        } else {
            listed.filter { it in catalogueIds.orEmpty() }
        }
        // The record accumulates rather than being replaced: it is also how the app
        // recognises the `models` entries it wrote, and an entry built out of the fallback's
        // own facts has no fixed shape left to match — `isPikitModelDefinition` finds only
        // the factless shape older builds wrote. An id whose settings were just removed stays
        // in the record so that its entry is withdrawn from pi's file rather than left behind
        // to keep replacing what pi resolves for it.
        //
        // Every kept id is added, **catalogued ones included**, and that is load-bearing: an
        // id that was uncatalogued when the app wrote its entry and catalogued when the page
        // was last saved is exactly the id whose entry must now come *out*, and the record is
        // the only thing that can find it — the entry it wrote carries the fallback's facts,
        // so it has no fixed shape left to recognise. Leaving it out of the record would
        // leave a `models` entry in pi's file replacing pi's own entry for the model for
        // ever.
        //
        // A custom endpoint contributes nothing: its entries are rebuilt from scratch on
        // every launch (`CustomEndpoint.providerObject`), so there is nothing of the app's in
        // pi's file to withdraw — and an id recorded here would later be treated as PiKit's
        // own in *another* provider's `models` array.
        val written = if (isCustomEndpoint) {
            existing?.writtenModels.orEmpty()
        } else {
            (existing?.writtenModels.orEmpty() + kept.keys).distinct()
        }
        val saved = store.upsert(
            ModelProfile(
                id = existing?.id.orEmpty(),
                name = name.trim(),
                provider = chosen.id,
                apiKey = apiKey.trim(),
                modelId = activeModel,
                models = listed,
                baseUrl = baseUrl.trim(),
                api = if (isCustomEndpoint) api else "",
                // The withdrawal record follows the write, not the field: an emptied
                // field means "use the provider's own endpoint", and the override this
                // app last put in pi's file is what has to come out next launch. A
                // value typed now becomes the record; clearing keeps the old one.
                writtenBaseUrl = baseUrl.trim().ifEmpty {
                    // The record is per provider: a custom relay's URL must not
                    // become DeepSeek's withdrawal key after a switch in this
                    // form. Carry it forward only when the provider is the one
                    // the record was written for.
                    if (existing == null || existing.provider == chosen.id) {
                        existing?.writtenBaseUrl.orEmpty()
                    } else {
                        ""
                    }
                },
                modelSettings = kept,
                writtenModels = written,
                // pi's own account of what it resolves an unknown id to. The entry written
                // for a custom id names these facts, so the settings move the model's image
                // support and its two numbers and nothing else — see `pikitModelDefinition`.
                // Carried over when this visit could not read them, so a check that failed
                // cannot erase what a previous one established.
                customModelFacts = fallbackFacts ?: existing?.customModelFacts,
                // The state the *probe* was read in, not the state at the moment of the
                // save: the launch path refreshes pi's catalogue in the background, so a
                // basis taken here could vouch for a verdict the catalogue had already
                // moved past. A snapshot that no longer matches is what withdraws the
                // entries until the model page checks again. See `ModelDefinitions`.
                catalogueBasis = catalogueSeen ?: existing?.catalogueBasis.orEmpty(),
                knownCatalogueIds = known,
            ),
        )
        // A brand-new profile becomes the active one: creating a
        // profile and then having to activate it separately is a
        // step with no purpose.
        val nowActive = isNew || wasActive
        if (nowActive) {
            store.setActive(saved.id)
            // Provider, model and API key are read once, when pi
            // is spawned: `--provider` / `--model` on the command
            // line and the key through the environment. Without a
            // restart the running process keeps the old ones and
            // the header would disagree with this page.
            //
            // Only when something the *launch* reads has moved. A
            // rename used to restart too, which tore down a healthy
            // agent — and a turn that was streaming with it — to
            // rewrite a label no process looks at.
            val launchChanged = existing == null ||
                chosen.id != existing.provider ||
                activeModel != existing.modelId ||
                apiKey.trim() != existing.apiKey ||
                baseUrl.trim() != existing.baseUrl ||
                listed != existing.selectableModels ||
                kept != existing.modelSettings
            if (launchChanged) session.scheduleRestart()
        }
        // A write that reached memory but not disk looks exactly like a save
        // until the next launch re-reads the old file. Say so here rather than
        // leave the page claiming success.
        if (store.lastWriteFailed) {
            toasts?.show(text.settings.saveFailed)
            return false
        }
        return true
    }

    /** One way out of the page, shared by the arrow, Cancel and the back gesture. */
    fun leave() {
        leaving = true
        onBack()
    }

    /**
     * The question, or the exit.
     *
     * [leaving] is read here rather than in a handler's `enabled` because the handler the
     * shell registers cannot be disabled — see this page's KDoc — and a second back press
     * while the page is on its way out must not open the question over it.
     */
    fun requestLeave() {
        if (leaving) return
        if (dirty) askingToSave = true else leave()
    }

    // The form's modal state, as three small values rather than a sheet host: Miuix's
    // sheets and dialogs are windows of their own, so each is a boolean (or the subject it
    // is about) in this composable and the body reads it.
    var providerPicker by remember { mutableStateOf(false) }
    var apiPicker by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<DiscoveredModel>>(emptyList()) }
    var fetchedFootnote by remember { mutableStateOf("") }
    var modelPicker by remember { mutableStateOf(false) }
    var editingNumber by remember { mutableStateOf<NumberEdit?>(null) }

    PiPage(
        title = if (isNew) text.settings.newProfile else text.settings.editProfile,
        // The provider is what the page is about: the model ids below are its ids and the
        // key is its credential. `isNew` is `existing == null`, so a profile being created
        // has no name to show and the provider is what the reader has to choose.
        subtitle = provider?.label ?: text.settings.notChosen,
        // Every way out goes through the question: the arrow, the Cancel button and the
        // system's back gesture are one action, and a form that saves nothing on the way
        // out has to say so first.
        onBack = ::requestLeave,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.profile) {
                SettingsTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = text.settings.name,
                    placeholder = text.settings.namePlaceholder,
                    // The row that used to sit above this field with the same sentence as
                    // its summary is gone: a field carries its own explanation, and the two
                    // of them said "Name" twice.
                    supporting = text.settings.nameSubtitle,
                )
            }

            // One card for the endpoint: which provider, where it lives, how it speaks and
            // the key it wants are one decision, and a group is a set of peer rows in a
            // frame rather than a card each.
            PiSection(title = text.settings.provider) {
                ArrowPreference(
                    title = text.settings.provider,
                    summary = provider?.let { text.settings.keyPassedAs(it.envVar) }
                        ?: text.settings.providerSubtitle,
                    endActions = { SettingsValue(provider?.label ?: text.settings.notChosen) },
                    // pi ships more than thirty single-key providers, so this is a list to
                    // search rather than a handful of rows to compare — see the sheet below.
                    onClick = { providerPicker = true },
                )

                // The endpoint is a property of every provider, not only of the custom one.
                // A built-in provider leaves it blank to use pi's own URL; filling it in is
                // how a proxy or relay in front of DeepSeek/OpenAI is used without giving
                // up the catalogue. A custom endpoint has no default to fall back to, so the
                // same field is required there — `refusal` says so. Inside this group rather
                // than its own so which provider, and where it lives, read as one decision.
                SettingsTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = text.settings.baseUrl,
                    placeholder = text.settings.baseUrlPlaceholder,
                    supporting = if (provider in PiProvider.needsBaseUrl) {
                        text.settings.baseUrlNote
                    } else {
                        text.settings.baseUrlOptionalNote
                    },
                    keyboardType = KeyboardType.Uri,
                )

                // The wire protocol, only for a custom endpoint: a built-in provider's client
                // is pi's own and its models already name it. Offering it there would be a
                // second answer to a question pi has already settled. The labels are pi's
                // identifiers because those are the values the relay's own docs name — see
                // `CustomApi`.
                if (isCustomEndpoint) {
                    ArrowPreference(
                        title = text.settings.apiType,
                        summary = text.settings.apiTypeSubtitle,
                        endActions = { SettingsValue(CustomApi.fromId(api).id, monospace = true) },
                        onClick = { apiPicker = true },
                    )
                }

                // The key is masked, which is the one thing a settings field cannot say by
                // itself: `SettingsTextField`'s `password` flag is that statement, and the
                // field is otherwise the same box as the URL above it.
                //
                // The key also goes on the *command line* for a custom endpoint, and that is
                // deliberate rather than an oversight: pi does not expand the `$VAR`
                // reference in a `models.json` provider's `apiKey`, so a relay configured
                // that way and given only its environment variable answers `401 无效的令牌`
                // while the identical request with `--api-key` succeeds (measured;
                // ARCHITECTURE §6.1). The cost is real and accepted — the key ends up in
                // argv, which `/proc` exposes to anything that can see this app's processes —
                // and without it a custom endpoint cannot authenticate at all.
                SettingsTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = text.settings.apiKey,
                    singleLine = true,
                    password = true,
                    keyboardType = KeyboardType.Password,
                )
            }

            // What this provider can answer with. Choosing one — the row's whole surface —
            // is what makes it the model the agent answers with; the ✕ takes one out of the
            // list without touching the provider's key.
            //
            // The row carries **no switch**, and that is the fix rather than a
            // simplification: a clickable row with a switch in its trailing slot runs both
            // the row's tap and the switch's for one press, so the image switch also moved
            // the selection — and, when the two cancelled out, looked like it did nothing.
            // The capability has its own section below, on rows of its own.
            //
            // Every row states the provider and the level its model would run at: the level
            // is clamped *per model* (`thinkingLevelFor`), so two models of one provider can
            // legitimately show two different ones, and a model row is the only place that
            // fact can appear beside the id it is about.
            SettingsSection(
                title = text.settings.models,
                trailing = text.settings.modelCount(models.size),
            ) {
                models.forEach { model ->
                    RadioButtonPreference(
                        title = model,
                        summary = modelSubtitle(
                            provider?.label,
                            null,
                            thinkingLevelFor(preferences, model),
                        ),
                        selected = model == activeModel,
                        endActions = {
                            IconButton(onClick = { pendingRemove = model }) {
                                Icon(
                                    imageVector = MiuixIcons.Clear,
                                    contentDescription = text.settings.removeModel,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        },
                        onClick = { activeModel = model },
                    )
                }

                SettingsTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = text.settings.modelId,
                    // Disabled, not merely refused, while there is no provider: a model id is
                    // the *provider's* id, and a field that accepted one would produce a row
                    // nothing can be said about — the model list would be non-empty while the
                    // section above had no endpoint to ask about, which is the state the
                    // status row was reporting as "could not read pi's catalogue". A disabled
                    // field says the same thing before the typing rather than after it. A
                    // profile that somehow has models and no provider — one restored from a
                    // config file — keeps its rows readable and its ids removable; only
                    // adding is closed.
                    enabled = provider != null,
                    placeholder = text.settings.modelIdPlaceholder,
                )
            }

            // 8dp under the field and the page's own gutter, which is the distance every
            // other action row in the settings keeps from the control above it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    text = text.settings.addModel,
                    onClick = {
                        val id = draft.trim()
                        addModel(
                            id,
                            prefill = if (isCustomEndpoint) prefillFrom(customIdFacts[id]) else null,
                        )
                    },
                    enabled = draft.isNotBlank() && provider != null,
                    primary = true,
                )
                // The runner-up, not a second primary: one filled action per screen is the
                // design's own rule, and adding an id by hand is what this pair is really
                // for — the fetch is the shortcut.
                SettingsButton(
                    text = text.settings.fetchModels,
                    onClick = {
                        val chosen = provider
                        if (chosen == null) {
                            // One sentence about the press, so a toast: the failures below
                            // are a dialog because a provider can answer with two paragraphs.
                            toasts?.show(text.settings.chooseProviderFirst)
                        } else {
                            discoveryError = null
                            fetching = true
                            scope.launch {
                                when (
                                    val result = discovery.discover(
                                        provider = chosen,
                                        apiKey = apiKey,
                                        baseUrl = baseUrl.trim(),
                                    )
                                ) {
                                    is ModelDiscovery.Success -> {
                                        fetched = result.models
                                        // The source of each entry is shown rather than
                                        // hidden: a model from pi's catalog is not guaranteed
                                        // to be one the key can use, and the user is the only
                                        // one who can decide whether that is worth trying.
                                        fetchedFootnote = if (
                                            result.models.any { it.source == ModelSource.PROVIDER }
                                        ) {
                                            text.settings.fromProvider
                                        } else {
                                            text.settings.fromCatalog
                                        }
                                        modelPicker = true
                                    }

                                    is ModelDiscovery.Failure ->
                                        discoveryError = result.problems
                                            .joinToString("\n\n") { text.settings.discoveryProblem(it) }
                                }
                                fetching = false
                            }
                        }
                    },
                    enabled = !fetching && provider != null,
                )
                // The label stays put while the call runs: swapping "获取模型列表" for
                // "获取中…" resized the button and shifted the one beside it. The spinner is
                // the mark that carries the busy state, and the word beside it is the sheet
                // it opened.
                if (fetching) {
                    SettingsWorking(modifier = Modifier.padding(start = 4.dp))
                }
            }

            // Its own section, and one *card* of rows per model: the model's name, the image
            // switch, and the two numbers. The switch cannot live in the trailing slot of the
            // model list's rows — that pattern runs the row's tap and the switch's for one
            // press, so the same tap changed the selection and toggled the capability — and
            // it was tried, which is why this page's model list says so.
            //
            // ## The shape, and what it replaced
            //
            // The three controls used to be a switch row with the model id in its value
            // column, followed by two outlined boxes side by side on an inset of their own.
            // Three problems, and the rewrite is one answer to all of them:
            //
            //  * **Nothing named the group.** With two declarable models the page drew
            //    switch/boxes/divider/switch/boxes, and the boxes belonged to a model only by
            //    position.
            //  * **The boxes did not line up with anything.** Every other row starts at the
            //    row's own text column; the fields started at the card's edge, so the frame's
            //    left edge was ragged in two places.
            //  * **Two floating labels and two numbers is a lot of furniture** for an
            //    advanced setting, and the numbers are the rare half of it.
            //
            // So a model is now a card of rows of its own: a heading row (its id, with the
            // rule that applies to it underneath), then its rows: the switch, and the two
            // numbers as ordinary rows whose value is the number, edited in a sheet. A row is
            // shorter than a field plus its padding, and the sheet is where the app already
            // edits one value at a time, keyboard and focus included.
            //
            // ## Nothing is drawn before there is an answer
            //
            // The controls used to be drawn from the first frame, disabled, with a summary
            // that said "reading…". Measured against the report: three states in a row — a
            // block of inert switches and empty number fields saying "cannot be filled in
            // yet", the same block saying "reading…", and then the real thing — so the
            // section visibly *jumped* on every visit, and the jump was the tallest state of
            // a card whose content the user could not touch. A state that cannot be acted on
            // should not be drawn at all: until the answer is in, this is one status row, and
            // it is tappable so a failed check can be asked again.
            SmallTitle(text = text.settings.imageInputSection)
            if (needsProvider) {
                // The honest subject. The catalogue was never the problem, and the row is
                // not tappable because there is nothing to retry: the missing half is the
                // provider, which is a control above this one.
                PiSection {
                    BasicComponent(
                        title = text.settings.catalogueTitle,
                        summary = text.settings.needProviderSubtitle,
                    )
                }
            } else if (awaitingCatalogue) {
                PiSection {
                    BasicComponent(
                        title = text.settings.catalogueTitle,
                        // The reason when there is one, because "could not read it" alone
                        // leaves the reader with nothing to act on: a `pi` that would not
                        // start, a scratch directory that could not be made and an empty
                        // answer are three different problems with three different fixes.
                        summary = when {
                            catalogueChecking -> text.settings.imageInputChecking
                            catalogueError != null ->
                                text.settings.discoveryProblem(catalogueError!!)
                            else -> text.settings.imageInputUnavailable
                        },
                        // The app's own indicator while the answer is coming, and no control
                        // when it did not come: a retry while the check is running would queue
                        // a second process behind the first, so the row is inert then.
                        endActions = {
                            if (catalogueChecking) SettingsWorking()
                        },
                        onClick = if (catalogueChecking) null else { { retries++ } },
                    )
                }
            } else {
                if (models.isEmpty()) {
                    // No model listed yet: the section still needs a row, or a paragraph
                    // floating under a heading reads as a rendering fault. The row is
                    // informational — the same shape the model list's own intro row uses.
                    PiSection {
                        BasicComponent(
                            title = text.settings.imageInput,
                            summary = text.settings.noModelChosen,
                        )
                    }
                }
                models.forEach { current ->
                    // An id pi's catalogue contains is *not* a different kind of model to the
                    // user: it is a model with three parameters, and this page is where they
                    // are changed. What differs is which mechanism carries the change — a
                    // `modelOverrides` entry, which pi merges field by field, for an id it
                    // resolved; a `models` entry, which has to stand alone, for one it did
                    // not — and that is the writer's problem, not the reader's. See
                    // `modelDefinitions`.
                    val known = catalogueIds?.contains(current) == true
                    // The numbers this model actually has. A built-in provider answers from
                    // its own catalogue; a custom endpoint answers from `customIdFacts` — the
                    // same id under whichever provider catalogues it — and only an id nothing
                    // names falls through to the fallback model (which a custom endpoint does
                    // not have either).
                    val facts = if (isCustomEndpoint) {
                        customIdFacts[current]
                    } else {
                        catalogueIdFacts[current]
                    }
                    val window = facts.number("contextWindow") ?: fallbackFacts.number("contextWindow")
                    val max = facts.number("maxTokens") ?: fallbackFacts.number("maxTokens")
                    // Nothing is known about this id and the throwaway pi that would have told
                    // us what it resolves to could not run. The controls are withheld rather
                    // than drawn empty: a definition written without the fallback's facts
                    // names pi's hard-coded 128k/16k for a model whose real window nobody asked
                    // about, which is the bug this whole path is written around. The heading
                    // row below says which of the two this row is, and the status row above
                    // explains why there is no answer.
                    val declarable = isCustomEndpoint || known || facts != null || fallbackFacts != null

                    PiSection {
                        BasicComponent(
                            title = current,
                            summary = if (known) {
                                text.settings.imageInputCatalogRow
                            } else {
                                text.settings.imageInputRowSubtitle
                            },
                        )
                        if (declarable) {
                            SwitchPreference(
                                title = text.settings.imageInput,
                                // Null is "the user has not said", and what the switch shows
                                // then is what the model would have had anyway: the
                                // catalogue's own capability list for a model it describes,
                                // the fallback's for one it does not.
                                checked = settings[current]?.images
                                    ?: (facts ?: fallbackFacts).hasImageInput(),
                                onCheckedChange = { on ->
                                    settings = settings.withImages(current, on)
                                },
                            )
                            ModelNumberRow(
                                title = text.settings.contextWindowLabel,
                                set = settings[current]?.contextWindow,
                                fallback = window,
                                unknown = text.settings.unknown,
                                onClick = {
                                    editingNumber = NumberEdit(
                                        model = current,
                                        maxTokens = false,
                                        fallback = window,
                                    )
                                },
                            )
                            ModelNumberRow(
                                title = text.settings.maxTokensLabel,
                                set = settings[current]?.maxTokens,
                                fallback = max,
                                unknown = text.settings.unknown,
                                onClick = {
                                    editingNumber = NumberEdit(
                                        model = current,
                                        maxTokens = true,
                                        fallback = max,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // Cancel and Save sit together on the trailing edge — the primary last — rather
            // than split across the row, which put the secondary action on the far left where
            // the eye does not look for a pair. Ask the same question the back arrow does,
            // because Cancel is the same action: this button has always meant "leave without
            // saving".
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsButton(
                    text = text.settings.cancel,
                    onClick = ::requestLeave,
                )
                SettingsButton(
                    text = text.settings.save,
                    onClick = { if (save()) leave() },
                    primary = true,
                )
            }
        }
    }

    // The provider list: pi ships more than thirty providers that take a single key,
    // regional variants of one service included, so the list is filtered rather than
    // compared. The sheet matches the label and pi's own provider id, which is the string a
    // reader may have from the docs instead of from this app.
    SettingsPickerSheet(
        show = providerPicker,
        title = text.settings.provider,
        subtitle = text.settings.providerSubtitle,
        options = PiProvider.entries.map { SettingsPick(id = it.id, label = it.label) },
        selectedId = provider?.id,
        searchHint = text.settings.providerPickSearch,
        onPick = { id ->
            providerPicker = false
            val entry = PiProvider.entries.firstOrNull { it.id == id }
            if (entry != null && entry != provider) {
                provider = entry
                // Model ids are provider-specific: a list built for one provider is not a
                // list for another, and pi would reject every entry in it. The list is
                // emptied rather than carried across, and the key is deliberately *not*
                // cleared — switching to the same account's other endpoint is the case this
                // page exists for.
                models = emptyList()
                activeModel = ""
                // The three declarations are keyed by *model id* and nothing else, so they
                // cannot tell one provider's `gpt-4o` from another's: carrying them across a
                // switch applied the window and the image switch the user had set for
                // provider A to the same id on provider B. That is a statement about a model
                // they never made, and it is written into pi's file for B — so they go with
                // the list, and the half-typed id goes too (it was for the provider just
                // left).
                settings = emptyMap()
                draft = ""
                // The endpoint goes with the provider for the same reason, and more sharply
                // since the field became an *override*: a relay URL typed for `pikit-custom`
                // kept on the form and saved against DeepSeek would rewrite every DeepSeek
                // model to that relay (`applyModelsJson`). The withdrawal record goes too — it
                // belongs to the provider just left, and a blank one here would leave that
                // provider's override in pi's file for ever.
                baseUrl = ""
                // Same reason as the URL: an `anthropic-messages` choice left on the form
                // after a switch to DeepSeek is a statement about a relay this profile no
                // longer has.
                api = ""
            }
        },
        onDismiss = { providerPicker = false },
    )

    // Three options, so there is no filter: a search field over three rows is a control whose
    // only effect is to hide the answer. The labels are pi's own identifiers and are not
    // translated — they are the strings in pi's `docs/models.md` and in the relay's own
    // documentation, and the two have to match (see `CustomApi`).
    SettingsPickerSheet(
        show = apiPicker,
        title = text.settings.apiType,
        subtitle = text.settings.apiTypeSubtitle,
        options = CustomApi.entries.map { SettingsPick(id = it.id, label = it.id) },
        selectedId = CustomApi.fromId(api).id,
        onPick = { id ->
            apiPicker = false
            api = id
        },
        onDismiss = { apiPicker = false },
    )

    // The fetched list. The title is the *count* rather than the word "models": the sheet was
    // opened by a button that already says what it fetched, and how many rows came back is
    // the fact the reader cannot see from behind the panel. The ids are the list's own
    // indices because an id can appear twice — once from the provider and once from pi's own
    // catalog — and a picker keyed on the id itself would then have two rows with one key.
    SettingsPickerSheet(
        show = modelPicker,
        title = text.settings.modelCount(fetched.size),
        subtitle = fetchedFootnote,
        options = fetched.mapIndexed { index, model ->
            SettingsPick(
                id = index.toString(),
                label = model.id,
                description = capabilityLine(model, text),
            )
        },
        selectedId = null,
        searchHint = text.settings.modelPickSearch,
        onPick = { key ->
            modelPicker = false
            val model = fetched.getOrNull(key.toIntOrNull() ?: -1)
            if (model != null) {
                // The picker already shows each model's window and image capability; picking
                // one is the moment those become the controls' starting point rather than a
                // caption that vanishes.
                //
                // A catalogue match wins over the `/models` answer: the store carries
                // `cost`, `thinkingLevelMap` and `samplingParams` too, and those are what the
                // entry has to name for the id to behave like the model it shares a name with.
                addModel(
                    model.id,
                    prefill = prefillFrom(customIdFacts[model.id])
                        ?: ModelSettings(
                            images = model.supportsImages.takeIf { it },
                            contextWindow = model.contextWindow,
                            maxTokens = null,
                            inherited = buildJsonObject {
                                if (model.reasoning) put("reasoning", true)
                                model.contextWindow?.let { put("contextWindow", it) }
                            }.takeIf { it.isNotEmpty() },
                        ),
                )
            }
        },
        onDismiss = { modelPicker = false },
    )

    // One number, typed in a sheet. A sheet rather than a dialog because the keyboard has to
    // stay up and the field has to keep focus.
    val edit = editingNumber
    PiSheet(
        show = edit != null,
        title = edit?.let {
            if (it.maxTokens) text.settings.maxTokensLabel else text.settings.contextWindowLabel
        },
        onDismiss = { editingNumber = null },
    ) {
        if (edit != null) {
            ModelNumberBody(
                label = if (edit.maxTokens) {
                    text.settings.maxTokensLabel
                } else {
                    text.settings.contextWindowLabel
                },
                model = edit.model,
                initial = (
                    if (edit.maxTokens) settings[edit.model]?.maxTokens
                    else settings[edit.model]?.contextWindow
                    )?.toString().orEmpty(),
                fallback = edit.fallback,
                text = text,
                onSave = { value ->
                    settings = if (edit.maxTokens) {
                        settings.withMaxTokens(edit.model, value)
                    } else {
                        settings.withWindow(edit.model, value)
                    }
                    editingNumber = null
                },
            )
        }
    }

    // The fetched list's failure: a provider that answered with an auth failure and a wrong
    // endpoint produces two paragraphs, and a pill that disappears after three seconds is not
    // where a reader can read two paragraphs. Bounded and scrolled inside the bound, because
    // an unbounded body pushes the acknowledgement off the bottom.
    val failure = discoveryError
    WindowDialog(
        show = failure != null,
        title = text.settings.fetchModels,
        onDismissRequest = { discoveryError = null },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = failure.orEmpty(),
                modifier = Modifier
                    .heightIn(max = DISCOVERY_FAILURE_MAX)
                    .verticalScroll(rememberScrollState()),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            TextButton(
                text = text.common.ok,
                onClick = { discoveryError = null },
                modifier = Modifier.fillMaxWidth(),
                colors = flatDialogAction(),
            )
        }
    }

    // The model row's ✕, asked about. Its own question rather than the profile delete's: the
    // two are different actions with different consequences, and the borrowed strings said
    // "Delete profile?" while naming the profile — neither of which was what the button did.
    val removing = pendingRemove
    PiConfirm(
        show = removing != null,
        title = text.settings.removeModelTitle,
        text = text.settings.removeModelBody(removing.orEmpty()),
        confirm = text.settings.removeModel,
        dismiss = text.settings.cancel,
        destructive = true,
        onConfirm = {
            removing?.let { removeModel(it) }
            pendingRemove = null
        },
        onDismiss = { pendingRemove = null },
    )

    // Two buttons, and no third: the dialog's question is what to do with the edits, and its
    // Cancel would be the third meaning of the same word on this screen — the page's own
    // Cancel already means "leave without saving". Back and a tap outside stay on the page,
    // which is the third answer, without a button saying so.
    //
    // What the first button *is* depends on whether the form can be stored at all. Offering
    // Save on a form that `refusal` refuses is a loop with no exit: the press fails, its
    // reason is written at the bottom of the page the dialog is still covering, and the user
    // is back at the same question. So the refusal is the dialog's own body and the answer is
    // "keep editing" — the one action that makes the other button possible.
    val refused = refusal()
    PiConfirm(
        show = askingToSave,
        title = text.settings.unsavedTitle,
        text = refused ?: text.settings.unsavedBody,
        confirm = if (refused == null) text.settings.save else text.settings.keepEditing,
        dismiss = text.settings.discard,
        onConfirm = {
            askingToSave = false
            if (refused == null && save()) leave()
        },
        onDismiss = {
            askingToSave = false
            leave()
        },
    )
}

/**
 * A row's line: the provider, the model when the row's own title is not it, and the level
 * that model runs at.
 *
 * The separators are the app's own — every dense line in the settings is a run of facts
 * joined this way — and the blanks are dropped rather than joined empty, because a model
 * id a profile has not chosen yet would otherwise draw two separators in a row.
 */
private fun modelSubtitle(provider: String?, modelId: String?, level: String?): String =
    listOfNotNull(
        provider?.takeIf { it.isNotBlank() },
        modelId?.takeIf { it.isNotBlank() },
        level?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")



/** The digits of [raw], and at most [MAX_TOKEN_DIGITS] of them: all a token count may be. */
private fun tokenDigits(raw: String): String = raw.filter { it.isDigit() }.take(MAX_TOKEN_DIGITS)

/**
 * One of a model's two numbers, as a row.
 *
 * The value is the user's number, or — muted — the number pi will use while the user has
 * said nothing. That difference is the whole of what an empty field used to mean, and it was
 * previously invisible: the boxes were pre-filled with the fallback, so a number the user had
 * chosen and a number pi would have used anyway looked identical.
 *
 * Editing happens in the sheet below. A row cannot hold a text field without either a
 * floating label inside a third of the row or a full-width field that breaks the card's
 * rhythm, and these two numbers are the rare half of the section — see the section itself
 * for what the two boxes looked like.
 */
@Composable
private fun ModelNumberRow(
    title: String,
    set: Long?,
    fallback: Long?,
    unknown: String,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = title,
        endActions = {
            SettingsValue(
                text = set?.toString() ?: fallback?.toString() ?: unknown,
                monospace = true,
                emphasised = set != null,
            )
        },
        onClick = onClick,
    )
}

/**
 * Which of a model's two numbers is open in the number sheet.
 *
 * The fallback travels with the subject rather than being looked up when the sheet opens:
 * it comes from the catalogue answer this form is holding, and the sheet has no business
 * reading the catalogue again.
 */
private data class NumberEdit(val model: String, val maxTokens: Boolean, val fallback: Long?)

/**
 * How tall a discovery failure may get before it scrolls: about ten lines.
 *
 * A provider that answered with an auth failure *and* a wrong endpoint produces two
 * paragraphs, and an unbounded body pushes the acknowledgement off the bottom of the dialog.
 */
private val DISCOVERY_FAILURE_MAX = 320.dp

/**
 * One number, typed in a sheet.
 *
 * A sheet rather than a dialog because the keyboard has to stay up and the field has to keep
 * focus, where a dialog closes the keyboard the moment it appears.
 *
 * The field starts from what the user saved and *not* from pi's fallback, with the fallback
 * as the placeholder: saving a number pi would have used anyway would write an entry that says
 * nothing, and the whole point of the entry is to say something. A value that arrives because
 * a placeholder showed it is a configuration the user never chose.
 *
 * Emptying the field is a real answer, not a mistake: it removes the number from the entry,
 * which is how pi's own value comes back.
 *
 * The model's id is the line over the box rather than a second title: a model has two of
 * these numbers, and the pair is what says which one is being edited without the reader
 * having to remember which row they tapped.
 */
@Composable
private fun ModelNumberBody(
    label: String,
    model: String,
    initial: String,
    fallback: Long?,
    text: Strings,
    onSave: (Long?) -> Unit,
) {
    var draft by remember(model, initial) { mutableStateOf(initial) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = model,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Card(Modifier.fillMaxWidth()) {
            SettingsTextField(
                value = draft,
                onValueChange = { draft = tokenDigits(it) },
                label = label,
                placeholder = fallback?.toString() ?: text.settings.unknown,
                // What a blank box means, on the field rather than in a paragraph above the
                // button: it is a rule about *this* value.
                supporting = text.settings.modelNumbersNote,
                keyboardType = KeyboardType.Number,
            )
        }
        // No dismiss beside the save: the scrim, the back gesture and a downward drag
        // already close a sheet.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            SettingsButton(
                text = text.settings.save,
                onClick = {
                    // Null for an emptied field, which `withWindow`/`withMaxTokens` turn into
                    // "no number in the entry" rather than into a zero.
                    onSave(draft.toLongOrNull())
                },
                primary = true,
            )
        }
    }
}

/** [ModelSettings] with one model's image switch set, keeping the two numbers. */
private fun Map<String, ModelSettings>.withImages(id: String, on: Boolean): Map<String, ModelSettings> =
    prune(id, (this[id] ?: ModelSettings()).copy(images = on))

/** [ModelSettings] with one model's context window set — null for a box that was cleared. */
private fun Map<String, ModelSettings>.withWindow(id: String, value: Long?): Map<String, ModelSettings> =
    prune(id, (this[id] ?: ModelSettings()).copy(contextWindow = usable(value)))

/** [ModelSettings] with one model's max-out set — null for a box that was cleared. */
private fun Map<String, ModelSettings>.withMaxTokens(id: String, value: Long?): Map<String, ModelSettings> =
    prune(id, (this[id] ?: ModelSettings()).copy(maxTokens = usable(value)))

/**
 * Zero as an absence.
 *
 * A box holding `0` is not a request — pi's own schema rejects a `contextWindow` or a
 * `maxTokens` of zero, and one rejected document takes every provider in the file with it.
 * Treating it as "not named" is the only reading that cannot break the file, and the box
 * keeps the zero so the user can see what they typed.
 */
private fun usable(value: Long?): Long? = value?.takeIf { it > 0 }

/**
 * [ModelSettings] with one model's entry removed when it says nothing.
 *
 * The map is what the profile writes `models` entries for, so an id whose every field is
 * back to "nothing said" has to leave it: an entry that only repeats pi's fallback is one
 * more thing that can go stale, and the model resolves identically without it.
 */
private fun Map<String, ModelSettings>.prune(
    id: String,
    entry: ModelSettings,
): Map<String, ModelSettings> =
    if (entry == ModelSettings()) this - id else this + (id to entry)

/**
 * Whether the model pi resolves this provider's unknown ids to already takes images.
 *
 * Read off the fallback's own `input`, which is what an id pi's catalogue does not contain
 * inherits, and therefore what an untouched switch has to show: a switch drawn off over a
 * model that does take images would read as a capability the app had removed.
 *
 * False when there are no facts: a custom endpoint has no fallback at all, and pi's own
 * default for a definition that names nothing is text-only.
 */
private fun JsonObject?.hasImageInput(): Boolean {
    val input = (this?.get("input") as? JsonArray) ?: return false
    return input.any { it.jsonPrimitive.contentOrNull == "image" }
}

/** One of the fallback's numbers, when pi reported it as one. */
private fun JsonObject?.number(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.longOrNull

/** The digits a token count may have; a window is a number of tokens, not a paragraph. */
private const val MAX_TOKEN_DIGITS = 9

/**
 * The fetched model list's second line.
 *
 * An id can appear twice — once from the provider and once from pi's catalog — so the
 * picker's rows carry the source as their second line rather than in their identity, and the
 * row's key is its index in the fetched list for that reason: `SettingsPick`'s id is what a
 * row is identified by, and the model id itself is not unique in this list.
 */
private fun capabilityLine(model: DiscoveredModel, text: Strings): String = buildList {
    if (model.name.isNotBlank() && model.name != model.id) add(model.name)
    add(model.source.label)
    model.contextWindow?.let { add(text.settings.contextWindow((it / 1000).toInt())) }
    if (model.supportsImages) add(text.settings.capabilityImages)
    if (model.reasoning) add(text.settings.capabilityReasoning)
}.joinToString(" · ")
