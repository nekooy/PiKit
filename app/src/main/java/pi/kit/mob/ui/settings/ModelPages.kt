package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PiIcons
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.design.PiAppBarScroll
import pi.kit.mob.ui.design.PiBadge
import pi.kit.mob.ui.design.PiButton
import pi.kit.mob.ui.design.PiButtonKind
import pi.kit.mob.ui.design.PiButtonSize
import pi.kit.mob.ui.design.PiGap
import pi.kit.mob.ui.design.PiGroup
import pi.kit.mob.ui.design.PiLoading
import pi.kit.mob.ui.design.PiNotice
import pi.kit.mob.ui.design.PiPagePadding
import pi.kit.mob.ui.design.PiRow
import pi.kit.mob.ui.design.PiRowDivider
import pi.kit.mob.ui.design.PiScaffold
import pi.kit.mob.ui.design.PiSearchField
import pi.kit.mob.ui.design.PiSectionHeader
import pi.kit.mob.ui.design.PiShapes
import pi.kit.mob.ui.design.PiSheetActions
import pi.kit.mob.ui.design.PiSheetList
import pi.kit.mob.ui.design.PiSheetRow
import pi.kit.mob.ui.design.PiSheetTitle
import pi.kit.mob.ui.design.PiSwitchRow
import pi.kit.mob.ui.design.PiTextField
import pi.kit.mob.ui.design.PiTone
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
 * The shell is `PiScaffold`'s pinned bar, whose subtitle is the count of saved
 * configurations — what the page is. A row states the rest: the configuration's name
 * (its model id until the user names it), the provider it is an endpoint and a key
 * for, the level its model runs at, and whether a key is saved.
 */
@Composable
internal fun ModelPage(
    session: PiAgentSession,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val store = session.settingsStore.profiles
    var pendingDelete by remember { mutableStateOf<ModelProfile?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
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

    PiScaffold(
        title = text.settings.profilesTitle,
        subtitle = text.settings.modelCount(profiles.size),
        onBack = onBack,
        scrollBehavior = PiAppBarScroll.Pinned,
    ) { body ->
        Column(
            modifier = body
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding),
        ) {
            // An empty list is not drawn as an empty frame: `PiGroup` is a rounded
            // surface, and one with no rows in it is a slab of nothing under a heading.
            // The store refuses to delete the last configuration, so this is only
            // reachable before the first one is saved — the button below is the page then.
            if (profiles.isNotEmpty()) {
                PiSectionHeader(text.settings.savedProfiles)
                PiGroup {
                    profiles.forEachIndexed { index, profile ->
                        if (index > 0) PiRowDivider()
                        PiRow(
                            title = profile.displayName,
                            subtitle = subtitleFor(
                                profile,
                                text,
                                thinkingLevelFor(preferences, profile.modelId),
                            ),
                            // The model mark, as on the settings root's own row into this
                            // page: the row is about which model answers, not about the key
                            // that carries it.
                            leading = { Icon(PiIcons.Model, contentDescription = null) },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (profile.id == activeId) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = text.settings.active,
                                            tint = MaterialTheme.colorScheme.primary,
                                            // The mark "in use" has always been: the primary tick
                                            // every picker in the app draws (`PiSheetRow`), at
                                            // the same 20dp, so the answer looks the same here as
                                            // in the sheet it was chosen in. `PiChoiceRow` — the
                                            // container a settings page fills for its own
                                            // selection — was the alternative, and it does not fit
                                            // this row: it draws its control in the trailing slot,
                                            // which is where the edit and delete pair lives, and a
                                            // filled row beside a row of icon buttons reads as the
                                            // row being unavailable rather than as the chosen one.
                                            // It was unsized before (24dp), which made the mark
                                            // bigger here than in every picker.
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    IconButton(onClick = { onEdit(profile.id) }) {
                                        Icon(
                                            Icons.Filled.Edit,
                                            contentDescription = text.settings.editProfile,
                                        )
                                    }
                                    IconButton(onClick = { pendingDelete = profile }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = text.settings.deleteProfile,
                                        )
                                    }
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

            PiGap(8.dp)
            PiNotice(text.settings.tapToActivate, tone = PiTone.Neutral)

            PiGap(12.dp)
            PiButton(
                text = text.settings.addProfile,
                onClick = { onEdit("") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )

            deleteError?.let { message ->
                PiGap(8.dp)
                PiNotice(message)
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text.settings.deleteProfileTitle) },
            text = { Text(text.settings.deleteProfileBody(target.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = store.delete(target.id)
                    if (deleted) {
                        deleteError = null
                        if (target.id == activeId) session.scheduleRestart()
                    } else {
                        deleteError = text.settings.lastProfile
                    }
                    pendingDelete = null
                }) {
                    // Red, because this takes the model out of the list and discards what
                    // the user declared about it — the image switch and the two numbers.
                    // Nothing on this page can put that back.
                    Text(text.settings.deleteProfile, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(text.settings.cancel) }
            },
        )
    }
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
 * The inset a field keeps inside its group: 12dp horizontally, the gutter `PiRow`'s own
 * padding leaves, so a field's box lines up with the rows above and below it; 4dp
 * vertically, which is the 8dp two stacked fields then sit apart by — a run of fields is
 * separated by their own outlines rather than by a rule between them.
 *
 * Written once because four fields share it, and because it is the measurement that
 * agrees with `PiRow`'s: a literal at each call site is how the card's left edge goes
 * ragged, which is one of the three faults the parameter rows were rewritten for.
 */
private val FIELD_INSET = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)

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
 * The back *gesture* is the shell's: `PiScaffold` registers a `PageBackHandler` inside
 * the page, so it is deeper than the tab's own handler and runs first, and it yields to
 * a sheet — which is the behaviour this page's pickers depend on (a swipe while the
 * provider sheet is up must close the sheet, not the page under it). The one thing that
 * handler cannot express is [leaving], because it is always enabled: the flag is
 * therefore read in [requestLeave] itself, where the decision is made.
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
    // Every sheet on this page — the provider picker, the protocol picker, the fetched
    // model list and the two number editors — goes into the root's one modal layer, and
    // each body is a `PiSheet*` component. The two number rows open one rather than
    // holding a field of their own — see the custom-parameters section for what that
    // replaced.
    val sheets = LocalSheetHost.current
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

    var saveMessage by remember { mutableStateOf<String?>(null) }

    var fetching by remember { mutableStateOf(false) }
    // The fetch failure, already worded: [DiscoveryProblem]s are rendered through
    // `Strings.discoveryProblem` at the point of capture, so the state holds text the
    // interface owns rather than the throw site's English. `chooseProviderFirst` lands
    // here too, which is why this is a string and not a problem list.
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
    var retries by remember { mutableStateOf(0) }

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
            saveMessage = message
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
            saveMessage = text.settings.saveFailed
            return false
        }
        saveMessage = null
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

    PiScaffold(
        title = if (isNew) text.settings.newProfile else text.settings.editProfile,
        // The provider is what the page is about: the model ids below are its ids and the
        // key is its credential. `isNew` is `existing == null`, so a profile being created
        // has no name to show and the provider is what the reader has to choose.
        subtitle = provider?.label ?: text.settings.notChosen,
        onBack = ::requestLeave,
        scrollBehavior = PiAppBarScroll.Pinned,
    ) { body ->
        Column(
            modifier = body
                .verticalScroll(rememberScrollState())
                .padding(PiPagePadding),
        ) {
            PiSectionHeader(text.settings.profile)
            PiGroup {
                PiTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = text.settings.name,
                    placeholder = text.settings.namePlaceholder,
                    // The row that used to sit above this field with the same sentence as
                    // its subtitle is gone: a field carries its own explanation, and the
                    // two of them said "Name" twice.
                    supportingText = text.settings.nameSubtitle,
                    modifier = FIELD_INSET,
                )
            }

            PiSectionHeader(text.settings.provider)
            // One frame for the endpoint: which provider, where it lives, how it speaks
            // and the key it wants are one decision, and the design's own rule is that a
            // group is a set of peer rows in a frame rather than a card each.
            PiGroup {
                PiRow(
                    title = text.settings.provider,
                    subtitle = provider?.let { text.settings.keyPassedAs(it.envVar) }
                        ?: text.settings.providerSubtitle,
                    leading = { Icon(Icons.Filled.Key, contentDescription = null) },
                    trailing = {
                        RowValue(provider?.label ?: text.settings.notChosen, chevron = true)
                    },
                    onClick = {
                        // pi ships more than thirty single-key providers, so this sheet is a
                        // list to search rather than a handful of rows to compare — see
                        // `ProviderPickerSheet`.
                        sheets.show(
                            Sheet(key = "provider-picker") {
                                ProviderPickerSheet(
                                    selectedId = provider?.id,
                                    onPick = { entry ->
                                        if (entry != provider) {
                                            provider = entry
                                            // Model ids are provider-specific: a list built for one
                                            // provider is not a list for another, and pi would
                                            // reject every entry in it. The list is emptied rather
                                            // than carried across, and the key is deliberately
                                            // *not* cleared — switching to the same account's other
                                            // endpoint is the case this page exists for.
                                            models = emptyList()
                                            activeModel = ""
                                            // The three declarations are keyed by *model id* and nothing
                                            // else, so they cannot tell one provider's `gpt-4o` from
                                            // another's: carrying them across a switch applied the
                                            // window and the image switch the user had set for provider
                                            // A to the same id on provider B. That is a statement about
                                            // a model they never made, and it is written into pi's file
                                            // for B — so they go with the list, and the half-typed id
                                            // goes too (it was for the provider just left).
                                            settings = emptyMap()
                                            draft = ""
                                            // The endpoint goes with the provider for the same reason,
                                            // and more sharply since the field became an *override*:
                                            // a relay URL typed for `pikit-custom` kept on the form and
                                            // saved against DeepSeek would rewrite every DeepSeek model
                                            // to that relay (`applyModelsJson`). The withdrawal record
                                            // goes too — it belongs to the provider just left, and a
                                            // blank one here would leave that provider's override in
                                            // pi's file for ever.
                                            baseUrl = ""
                                            // Same reason as the URL: an `anthropic-messages` choice
                                            // left on the form after a switch to DeepSeek is a statement
                                            // about a relay this profile no longer has.
                                            api = ""
                                        }
                                    },
                                )
                            },
                        )
                    },
                )

                // The endpoint is a property of every provider, not only of the custom
                // one. A built-in provider leaves it blank to use pi's own URL; filling
                // it in is how a proxy or relay in front of DeepSeek/OpenAI is used
                // without giving up the catalogue. A custom endpoint has no default to
                // fall back to, so the same field is required there — `refusal` says so.
                // Inside this group rather than its own so which provider, and where it
                // lives, read as one decision; the note is the field's own supporting
                // text, which is where the reader is when they need it.
                PiTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = text.settings.baseUrl,
                    placeholder = text.settings.baseUrlPlaceholder,
                    supportingText = if (provider in PiProvider.needsBaseUrl) {
                        text.settings.baseUrlNote
                    } else {
                        text.settings.baseUrlOptionalNote
                    },
                    keyboardType = KeyboardType.Uri,
                    modifier = FIELD_INSET,
                )

                // The wire protocol, only for a custom endpoint: a built-in
                // provider's client is pi's own and its models already name it.
                // Offering it there would be a second answer to a question pi
                // has already settled. The labels are pi's identifiers because
                // those are the values the relay's own docs name — see
                // `CustomApi`.
                //
                // The row carries the one line of the explanation that decides the
                // choice — what a wrong value costs — and the rest is the paragraph
                // under the group. All three sentences in the row made one picker
                // taller than the whole section around it.
                //
                // The row used to need 8dp of space above it: the URL field's own 4dp
                // bottom pad and the row's top pad met as one dense block, so the
                // protocol control looked attached to the input above it rather than a
                // separate decision. A field's outline and the row's own 14dp of padding
                // are that separation now, which is why the literal is gone.
                if (isCustomEndpoint) {
                    PiRow(
                        title = text.settings.apiType,
                        subtitle = text.settings.apiTypeSubtitle,
                        leading = { Icon(Icons.Filled.Key, contentDescription = null) },
                        trailing = { RowValue(CustomApi.fromId(api).id, chevron = true) },
                        onClick = {
                            sheets.show(
                                Sheet(key = "api-picker") {
                                    ApiTypePickerSheet(
                                        selectedId = CustomApi.fromId(api).id,
                                        onPick = { api = it },
                                    )
                                },
                            )
                        },
                    )
                }

                // The key field is the app's one field that does not come from the
                // design package, and the reason is exact: `PiTextField` has no
                // `visualTransformation`, and a field whose contents are a secret has to
                // mask them. Everything else about it — the shape, the transparent
                // container, the inset — is `PiTextField`'s, so the two are the same
                // field to anyone looking at the page.
                //
                // The key also goes on the *command line* for a custom endpoint, and that
                // is deliberate rather than an oversight: pi does not expand the `$VAR`
                // reference in a `models.json` provider's `apiKey`, so a relay configured
                // that way and given only its environment variable answers
                // `401 无效的令牌` while the identical request with `--api-key` succeeds
                // (measured; ARCHITECTURE §6.1). The cost is real and accepted — the key
                // ends up in argv, which `/proc` exposes to anything that can see this
                // app's processes — and without it a custom endpoint cannot authenticate
                // at all.
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(text.settings.apiKey) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = PiShapes.card,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = FIELD_INSET,
                )
            }

            // The protocol's own paragraph, and the note about pi's settings.json, are
            // outside the frame: both are sentences about the whole endpoint rather than
            // about the control above them, which is what `PiNotice` is for. The protocol
            // paragraph follows the frame rather than the row it explains, because a note
            // inside a group would break the group's own 24dp/64dp rhythm with a third
            // left edge.
            if (isCustomEndpoint) {
                PiGap(8.dp)
                PiNotice(text.settings.apiTypeNote, tone = PiTone.Neutral)
            }
            // The same three values are written into pi's own settings.json, so
            // a `pi` the user starts by hand in the terminal answers with this
            // model instead of reporting none.
            PiGap(8.dp)
            PiNotice(text.settings.terminalModelNote, tone = PiTone.Neutral)

            PiSectionHeader(
                text = text.settings.models,
                trailing = { PiBadge(text.settings.modelCount(models.size)) },
            )
            PiNotice(text.settings.modelsSubtitle, tone = PiTone.Neutral)
            // What this provider can answer with. Choosing one — the row's
            // whole surface — is what makes it the model the agent answers
            // with; the ✕ takes one out of the list without touching the
            // provider's key.
            //
            // The row carries **no switch**, and that is the fix rather than a
            // simplification: a clickable row with a switch in its trailing slot
            // runs both the row's tap and the switch's for one press, so the
            // image switch also moved the selection — and, when the two
            // cancelled out, looked like it did nothing. The capability has its
            // own section below, on inert rows.
            //
            // Every row states the provider and the level its model would run at: the
            // level is clamped *per model* (`thinkingLevelFor`), so two models of one
            // provider can legitimately show two different ones, and a model row is the
            // only place that fact can appear beside the id it is about.
            PiGroup {
                models.forEachIndexed { index, model ->
                    if (index > 0) PiRowDivider()
                    PiRow(
                        title = model,
                        subtitle = modelSubtitle(
                            provider?.label,
                            null,
                            thinkingLevelFor(preferences, model),
                        ),
                        // The same mark the model row uses on the settings root: this list
                        // is the choice that row's value stands for.
                        leading = { Icon(PiIcons.Model, contentDescription = null) },
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (model == activeModel) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = text.settings.active,
                                        tint = MaterialTheme.colorScheme.primary,
                                        // 20dp, the size the picker's own tick is
                                        // drawn at (was unsized 24dp).
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(onClick = { pendingRemove = model }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = text.settings.removeModel,
                                    )
                                }
                            }
                        },
                        onClick = { activeModel = model },
                    )
                }

                PiTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = text.settings.modelId,
                    // Disabled, not merely refused, while there is no provider: a model id is
                    // the *provider's* id, and a field that accepted one would produce a row
                    // nothing can be said about — the model list would be non-empty while the
                    // section above had no endpoint to ask about, which is the state the
                    // status row was reporting as "could not read pi's catalogue". A
                    // disabled field says the same thing before the typing rather than after
                    // it. A profile that somehow has models and no provider — one restored
                    // from a config file — keeps its rows readable and its ids removable;
                    // only adding is closed.
                    enabled = provider != null,
                    placeholder = text.settings.modelIdPlaceholder,
                    modifier = FIELD_INSET,
                )
            }

            // 8dp under the field and the page's own 12dp gutter, which is the distance
            // every other action row in the settings keeps from the control above it. The
            // pair used to be inset 12h/10v here and 8v elsewhere, which put these two
            // buttons nearer to the field than every other action row in the app.
            PiGap(8.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiButton(
                    text = text.settings.addModel,
                    onClick = {
                        val id = draft.trim()
                        addModel(
                            id,
                            prefill = if (isCustomEndpoint) prefillFrom(customIdFacts[id]) else null,
                        )
                    },
                    kind = PiButtonKind.Filled,
                    size = PiButtonSize.Small,
                    enabled = draft.isNotBlank() && provider != null,
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
                // The runner-up, not a second primary: one filled action per screen is the
                // design's own rule, and adding an id by hand is what this pair is really
                // for — the fetch is the shortcut.
                PiButton(
                    text = text.settings.fetchModels,
                    onClick = {
                        val chosen = provider
                        if (chosen == null) {
                            discoveryError = text.settings.chooseProviderFirst
                            return@PiButton
                        }
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
                                    sheets.show(
                                        Sheet(key = "model-picker") {
                                            ModelPickerSheet(
                                                models = result.models,
                                                // The source of each entry is shown rather than
                                                // hidden: a model from pi's catalog is not
                                                // guaranteed to be one the key can use, and the
                                                // user is the only one who can decide whether
                                                // that is worth trying.
                                                footnote = if (
                                                    result.models.any { it.source == ModelSource.PROVIDER }
                                                ) {
                                                    text.settings.fromProvider
                                                } else {
                                                    text.settings.fromCatalog
                                                },
                                                onPick = { model ->
                                                    // The picker already shows each
                                                    // model's window and image
                                                    // capability; picking one is the
                                                    // moment those become the
                                                    // controls' starting point rather
                                                    // than a caption that vanishes.
                                                    //
                                                    // A catalogue match wins over the
                                                    // `/models` answer: the store
                                                    // carries `cost`,
                                                    // `thinkingLevelMap` and
                                                    // `samplingParams` too, and those
                                                    // are what the entry has to name
                                                    // for the id to behave like the
                                                    // model it shares a name with.
                                                    addModel(
                                                        model.id,
                                                        prefill = prefillFrom(customIdFacts[model.id])
                                                            ?: ModelSettings(
                                                                images = model.supportsImages
                                                                    .takeIf { it },
                                                                contextWindow = model.contextWindow,
                                                                maxTokens = null,
                                                                inherited = buildJsonObject {
                                                                    if (model.reasoning) {
                                                                        put("reasoning", true)
                                                                    }
                                                                    model.contextWindow?.let {
                                                                        put("contextWindow", it)
                                                                    }
                                                                }.takeIf { it.isNotEmpty() },
                                                            ),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                                is ModelDiscovery.Failure ->
                                    discoveryError = result.problems
                                        .joinToString("\n\n") { text.settings.discoveryProblem(it) }
                            }
                            fetching = false
                        }
                    },
                    kind = PiButtonKind.Tonal,
                    size = PiButtonSize.Small,
                    enabled = !fetching && provider != null,
                    leadingIcon = {
                        // The label stays put while the call runs: swapping
                        // "获取模型列表" for "获取中…" resized the button and shifted
                        // the one beside it. The mark carries the busy state, and it
                        // is the app's own indicator rather than a Material spinner.
                        if (fetching) {
                            PiLoading(size = 18.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        }
                    },
                )
            }

            PiGap(4.dp)
            PiNotice(text.settings.fetchNote, tone = PiTone.Neutral)

            discoveryError?.let { message ->
                PiGap(8.dp)
                PiNotice(message)
            }

            // Its own section, and one *group* of rows per model: the model's name, the
            // image switch, and the two numbers. The switch cannot live in the trailing
            // slot of the model list's rows — that pattern runs the row's tap and the
            // switch's for one press, so the same tap changed the selection and toggled
            // the capability — and it was tried, which is why this page's model list says
            // so.
            //
            // ## The shape, and what it replaced
            //
            // The three controls used to be a switch row with the model id in its value
            // column, followed by two outlined boxes side by side on a 12dp inset of their
            // own. Three problems, and the rewrite is one answer to all of them:
            //
            //  * **Nothing named the group.** With two declarable models the page drew
            //    switch/boxes/divider/switch/boxes, and the boxes belonged to a model only
            //    by position.
            //  * **The boxes did not line up with anything.** Every other row starts at a
            //    24dp mark with a 16dp gap; the fields started at 12dp, so the frame's left
            //    edge was ragged in two places.
            //  * **Two floating labels and two numbers is a lot of furniture** for an
            //    advanced setting, and the numbers are the rare half of it.
            //
            // So a model is now a group of rows of its own: a heading row (its id, with the
            // rule that applies to it underneath — the same two sentences the switch and the
            // catalogue rows used to carry separately), then its rows: the switch, and the
            // two numbers as ordinary rows whose value is the number, edited in a sheet. A
            // row is 54dp tall and a field is 56dp plus its padding, so this is also the
            // shorter of the two layouts — and the sheet is where the app already edits one
            // value at a time (`WebSearchValueSheet`), keyboard and focus included.
            //
            // The two number rows carry no icon and are indented to where the *title* of
            // the row above starts, which is what makes them read as that switch's
            // continuation rather than as two more subjects.
            //
            // ## Nothing is drawn before there is an answer
            //
            // The controls used to be drawn from the first frame, disabled, with a subtitle
            // that said "reading…". Measured against the report: three states in a row — a
            // block of inert switches and empty number fields saying "cannot be filled in
            // yet", the same block saying "reading…", and then the real thing — so the
            // section visibly *jumped* on every visit, and the jump was the tallest state
            // of a card whose content the user could not touch. A state that cannot be
            // acted on should not be drawn at all: until the answer is in, this is one
            // status row, and it is tappable so a failed check can be asked again.
            //
            // The rule behind it is worth stating once: the controls describe what will be
            // *written* to pi's file, and what gets written depends on the answer, so a
            // control drawn before the answer is a control whose meaning is not known yet.
            PiSectionHeader(text.settings.imageInputSection)
            // One paragraph for the whole section, at the top: it was drawn under the first
            // model's switch for a while, to put it next to the control it explains — and
            // that is worse: the section has two controls *and* two numbers per model, and
            // the sentence is about the card rather than about one switch.
            PiNotice(text.settings.imageInputNote, tone = PiTone.Neutral)
            if (isCustomEndpoint) {
                PiGap(8.dp)
                PiNotice(text.settings.imageInputCustomNote, tone = PiTone.Neutral)
            }

            if (needsProvider) {
                PiGap(8.dp)
                // The honest subject. The catalogue was never the problem, and the row
                // is not tappable because there is nothing to retry: the missing half
                // is the provider, which is a control above this one.
                PiGroup {
                    PiRow(
                        title = text.settings.catalogueTitle,
                        subtitle = text.settings.needProviderSubtitle,
                        leading = { Icon(Icons.Filled.Key, contentDescription = null) },
                    )
                }
            } else if (awaitingCatalogue) {
                PiGap(8.dp)
                PiGroup {
                    PiRow(
                        title = text.settings.catalogueTitle,
                        // The reason when there is one, because "could not read it" alone
                        // leaves the reader with nothing to act on: a `pi` that would not
                        // start, a scratch directory that could not be made and an empty
                        // answer are three different problems with three different fixes.
                        subtitle = when {
                            catalogueChecking -> text.settings.imageInputChecking
                            catalogueError != null ->
                                text.settings.discoveryProblem(catalogueError!!)
                            else -> text.settings.imageInputUnavailable
                        },
                        // The subject of the row is the catalogue, so the mark is the
                        // magnifier the model list's own "Fetch models" button carries
                        // rather than the picture icon the image switch uses.
                        leading = { Icon(Icons.Filled.Search, contentDescription = null) },
                        // The app's own indicator while the answer is coming, and the app's
                        // usual "this row can be tapped" chevron when it did not come: the
                        // failure is an action, not a page, so the alternative would be
                        // another custom vector for one row. The indicator is 20dp, the box
                        // the chevron it replaces draws in, so the row does not change size
                        // when the mark swaps; it is absent while the check is running,
                        // because a retry then would queue a second process behind the first.
                        trailing = if (catalogueChecking) {
                            { PiLoading(size = 20.dp) }
                        } else {
                            null
                        },
                        onClick = if (catalogueChecking) null else { { retries++ } },
                    )
                }
            } else {
                PiGap(8.dp)
                if (models.isEmpty()) {
                    // No model listed yet: the section still needs a row, or a
                    // paragraph floating under a heading reads as a rendering fault.
                    // The row is informational — the same shape the model list's own
                    // intro row uses.
                    PiGroup {
                        PiRow(
                            title = text.settings.imageInput,
                            subtitle = text.settings.noModelChosen,
                            leading = { Icon(Icons.Filled.Image, contentDescription = null) },
                        )
                    }
                }
                models.forEachIndexed { index, current ->
                    // An id pi's catalogue contains is *not* a different kind of model
                    // to the user: it is a model with three parameters, and this page
                    // is where they are changed. What differs is which mechanism
                    // carries the change — a `modelOverrides` entry, which pi merges
                    // field by field, for an id it resolved; a `models` entry, which
                    // has to stand alone, for one it did not — and that is the writer's
                    // problem, not the reader's. See `modelDefinitions`.
                    //
                    // The controls used to be omitted entirely for a catalogued id,
                    // with a row saying "pi's catalog already lists this model". That
                    // is true and was the wrong conclusion: pi's entry is the
                    // *starting point*, and there was no way to say the catalogue's
                    // window is wrong for the endpoint in front of you.
                    val known = catalogueIds?.contains(current) == true
                    // The numbers this model actually has. A built-in provider answers
                    // from its own catalogue; a custom endpoint answers from
                    // `customIdFacts` — the same id under whichever provider catalogues
                    // it — and only an id nothing names falls through to the fallback
                    // model (which a custom endpoint does not have either).
                    val facts = if (isCustomEndpoint) {
                        customIdFacts[current]
                    } else {
                        catalogueIdFacts[current]
                    }
                    val window = facts.number("contextWindow") ?: fallbackFacts.number("contextWindow")
                    val max = facts.number("maxTokens") ?: fallbackFacts.number("maxTokens")
                    // Nothing is known about this id and the throwaway pi that would have
                    // told us what it resolves to could not run. The controls are withheld
                    // rather than drawn empty: a definition written without the fallback's
                    // facts names pi's hard-coded 128k/16k for a model whose real window
                    // nobody asked about, which is the bug this whole path is written
                    // around. The heading row below says which of the two this row is, and
                    // the status row above explains why there is no answer.
                    val declarable = isCustomEndpoint || known || facts != null || fallbackFacts != null

                    if (index > 0) PiGap(8.dp)
                    PiGroup {
                        PiRow(
                            title = current,
                            subtitle = if (known) {
                                text.settings.imageInputCatalogRow
                            } else {
                                text.settings.imageInputRowSubtitle
                            },
                            leading = { Icon(PiIcons.Model, contentDescription = null) },
                        )
                        if (declarable) {
                            PiRowDivider()
                            PiSwitchRow(
                                title = text.settings.imageInput,
                                // Null is "the user has not said", and what the switch shows
                                // then is what the model would have had anyway: the catalogue's
                                // own capability list for a model it describes, the fallback's
                                // for one it does not.
                                checked = settings[current]?.images
                                    ?: (facts ?: fallbackFacts).hasImageInput(),
                                onCheckedChange = { on ->
                                    settings = settings.withImages(current, on)
                                },
                                leading = { Icon(Icons.Filled.Image, contentDescription = null) },
                            )
                            // Both dividers keep `PiRowDivider`'s inset, which is where the two
                            // rules meet: the line begins at 64dp, which is the title column of
                            // a row that has a mark *and* the text column of an indented row
                            // that does not — see `NUMBER_ROW_INDENT`.
                            PiRowDivider()
                            ModelNumberRow(
                                title = text.settings.contextWindowLabel,
                                set = settings[current]?.contextWindow,
                                fallback = window,
                                unknown = text.settings.unknown,
                                onClick = {
                                    sheets.show(
                                        Sheet(key = "model-window:$current") {
                                            ModelNumberSheet(
                                                title = text.settings.contextWindowLabel,
                                                model = current,
                                                initial = settings[current]?.contextWindow?.toString().orEmpty(),
                                                fallback = window,
                                                onSave = { value ->
                                                    settings = settings.withWindow(current, value)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            PiRowDivider()
                            ModelNumberRow(
                                title = text.settings.maxTokensLabel,
                                set = settings[current]?.maxTokens,
                                fallback = max,
                                unknown = text.settings.unknown,
                                onClick = {
                                    sheets.show(
                                        Sheet(key = "model-max:$current") {
                                            ModelNumberSheet(
                                                title = text.settings.maxTokensLabel,
                                                model = current,
                                                initial = settings[current]?.maxTokens?.toString().orEmpty(),
                                                fallback = max,
                                                onSave = { value ->
                                                    settings = settings.withMaxTokens(current, value)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            saveMessage?.let { message ->
                PiGap(12.dp)
                PiNotice(message)
            }

            // Cancel and Save sit together on the right — the primary last — rather than
            // split across the row, which put the secondary action on the far left where the
            // eye does not look for a pair. Ask the same question the back arrow does, because
            // Cancel is the same action: this button has always meant "leave without saving".
            PiGap(12.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PiButton(
                    text = text.settings.cancel,
                    onClick = ::requestLeave,
                    kind = PiButtonKind.Outlined,
                )
                PiButton(
                    text = text.settings.save,
                    onClick = { if (save()) leave() },
                )
            }
            PiGap(12.dp)
        }
    }

    // The model row's ✕, asked about. Its own dialog rather than the profile delete's: the
    // two are different actions with different consequences, and the borrowed strings said
    // "Delete profile?" while naming the profile — neither of which was what the button did.
    pendingRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(text.settings.removeModelTitle) },
            text = { Text(text.settings.removeModelBody(target)) },
            confirmButton = {
                TextButton(onClick = {
                    removeModel(target)
                    pendingRemove = null
                }) {
                    Text(text.settings.removeModel, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text(text.settings.cancel) }
            },
        )
    }

    if (askingToSave) {
        // Two buttons, and no third: the dialog's question is what to do with the edits,
        // and its Cancel would be the third meaning of the same word on this screen —
        // the page's own Cancel already means "leave without saving". Back and a tap
        // outside stay on the page, which is the third answer, without a button saying
        // so.
        //
        // What the first button *is* depends on whether the form can be stored at all.
        // Offering Save on a form that [refusal] refuses is a loop with no exit: the press
        // fails, its reason is written at the bottom of the page the dialog is still
        // covering, and the user is back at the same question. So the refusal is the
        // dialog's own body and the answer is "keep editing" — the one action that makes
        // the other button possible.
        val refused = refusal()
        AlertDialog(
            onDismissRequest = { askingToSave = false },
            title = { Text(text.settings.unsavedTitle) },
            text = { Text(refused ?: text.settings.unsavedBody) },
            confirmButton = {
                if (refused == null) {
                    TextButton(
                        onClick = {
                            askingToSave = false
                            if (save()) leave()
                        },
                    ) { Text(text.settings.save) }
                } else {
                    // Stays on the page, which is where the missing field is.
                    TextButton(onClick = { askingToSave = false }) {
                        Text(text.settings.keepEditing)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        askingToSave = false
                        leave()
                    },
                ) {
                    Text(
                        text.settings.discard,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
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

/**
 * The provider picker, as a sheet body.
 *
 * A list to *search* rather than a handful of rows to compare: pi ships more than thirty
 * providers that take a single key, regional variants of one service included. The filter
 * is local to the sheet — a question asked right now — so a sheet opened again starts with
 * the whole list rather than with the last search still hiding it, and it matches the label
 * and pi's own provider id, which is the string a reader may have from the docs instead of
 * from this app.
 *
 * The tick is the app's choice mark and nothing else: `PiSheetRow` draws it, the profile
 * list draws the same one at the same 20dp, and a filled row was rejected — in this palette
 * a `secondaryContainer` fill on a `surfaceContainerLow` panel is a 3% difference that reads
 * as a rendering artifact rather than as a selection.
 *
 * The body owns its own `Column` because the layer hands the sheet's content a `Box`: three
 * siblings in it would be drawn on top of one another, and the body is what knows the order
 * its own parts go in.
 */
@Composable
private fun ProviderPickerSheet(
    selectedId: String?,
    onPick: (PiProvider) -> Unit,
) {
    val text = strings
    val host = LocalSheetHost.current
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val shown = if (needle.isEmpty()) {
        PiProvider.entries.toList()
    } else {
        PiProvider.entries.filter { entry ->
            entry.label.contains(needle, ignoreCase = true) ||
                entry.id.contains(needle, ignoreCase = true)
        }
    }

    Column(SHEET_BODY_PADDING) {
        PiSheetTitle(title = text.settings.provider, subtitle = text.settings.providerSubtitle)
        PiSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = text.settings.providerPickSearch,
            clearContentDescription = text.common.clear,
            modifier = FIELD_INSET,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            PiSheetList(Modifier.heightIn(max = maxHeight * SHEET_LIST_FRACTION)) {
                shown.forEach { entry ->
                    item(key = entry.id) {
                        PiSheetRow(
                            label = entry.label,
                            selected = entry.id == selectedId,
                            onClick = {
                                // Dismissed before the pick is applied, which is the order a leaving
                                // panel needs: the pick is what the user meant, so it must not wait
                                // for the panel's animation, and the row must stop taking taps.
                                host.dismiss()
                                onPick(entry)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The custom endpoint's wire-protocol picker, as a sheet body.
 *
 * The labels are pi's own identifiers and are not translated: they are the strings in pi's
 * `docs/models.md` and in the relay's own documentation, and the two have to match — see
 * [CustomApi]. Three options, so there is no filter: a search field over three rows is a
 * control whose only effect is to hide the answer.
 */
@Composable
private fun ApiTypePickerSheet(
    selectedId: String,
    onPick: (String) -> Unit,
) {
    val text = strings
    val host = LocalSheetHost.current

    Column(SHEET_BODY_PADDING) {
        PiSheetTitle(title = text.settings.apiType, subtitle = text.settings.apiTypeSubtitle)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            PiSheetList(Modifier.heightIn(max = maxHeight * SHEET_LIST_FRACTION)) {
                CustomApi.entries.forEach { entry ->
                    item(key = entry.id) {
                        PiSheetRow(
                            label = entry.id,
                            selected = entry.id == selectedId,
                            onClick = {
                                host.dismiss()
                                onPick(entry.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The fetched model list, as a sheet body.
 *
 * The title is the *count* rather than the word "models": the sheet was opened by a button
 * that already says what it fetched, and how many rows came back is the fact the reader
 * cannot see from behind the panel. The count is the answer's, not the filter's, for the
 * same reason the panel is dismissed when a row is picked.
 *
 * Each row's second line is the source of the entry — reported by the provider, or read out
 * of pi's own catalog — because a catalog model is not guaranteed to be one this key can
 * use, and the user is the only one who can decide whether that is worth trying.
 *
 * The filter is here for the same reason the provider picker has one: the fetched list is
 * both sources merged, and on a well-catalogued provider that is well over a hundred rows,
 * so *finding* a model is the problem rather than choosing between them.
 */
@Composable
private fun ModelPickerSheet(
    models: List<DiscoveredModel>,
    footnote: String,
    onPick: (DiscoveredModel) -> Unit,
) {
    val text = strings
    val host = LocalSheetHost.current
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val shown = if (needle.isEmpty()) {
        models
    } else {
        models.filter { model ->
            model.id.contains(needle, ignoreCase = true) ||
                model.name.contains(needle, ignoreCase = true) ||
                model.source.label.contains(needle, ignoreCase = true)
        }
    }

    Column(SHEET_BODY_PADDING) {
        PiSheetTitle(
            title = text.settings.modelCount(models.size),
            subtitle = footnote,
        )
        PiSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = text.settings.modelPickSearch,
            clearContentDescription = text.common.clear,
            modifier = FIELD_INSET,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            PiSheetList(Modifier.heightIn(max = maxHeight * SHEET_LIST_FRACTION)) {
                // Keyed on the index *and* the source, because an id can appear twice — once
                // from the provider and once from pi's own catalog — and the same id twice
                // would key two rows identically, which `LazyColumn` refuses outright.
                shown.forEachIndexed { index, model ->
                    item(key = "$index:${model.source}:${model.id}") {
                        PiSheetRow(
                            label = model.id,
                            subtitle = capabilityLine(model, text),
                            onClick = {
                                host.dismiss()
                                onPick(model)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The digits of [raw], and at most [MAX_TOKEN_DIGITS] of them: all a token count may be. */
private fun tokenDigits(raw: String): String = raw.filter { it.isDigit() }.take(MAX_TOKEN_DIGITS)

/**
 * A sheet body's own frame: full width, and 16dp of air under its last row.
 *
 * The layer hands a sheet's content a `Box`, so a body with more than one part has to be a
 * `Column` itself — two siblings in a `Box` are drawn on top of each other. The 16dp is what
 * keeps a list's final row from looking cut off against the panel's edge, and it is the
 * figure the picker body in `ui/components/Forms.kt` uses for the same reason.
 */
private val SHEET_BODY_PADDING = Modifier
    .fillMaxWidth()
    .padding(bottom = 16.dp)

/** The same frame for a body whose last line is a button row, which brings its own padding. */
private val SHEET_FORM_PADDING = Modifier
    .fillMaxWidth()
    .padding(bottom = 8.dp)

/**
 * The share of the height a sheet may take that its list is allowed to fill: 60%.
 *
 * Past that a list stops being something the reader glances at and becomes a page that hides
 * the screen it is being chosen from. A ceiling rather than a height — a two-item picker keeps
 * its own — and a share rather than a figure in dp, so it survives a shorter phone. The picker
 * body in `ui/components/Forms.kt` holds the same number for its own list; it is repeated
 * rather than shared because that constant is private to its file, and the two lists are the
 * same control on the same panel, so they must not end at two different heights.
 */
private const val SHEET_LIST_FRACTION = 0.6f

/**
 * Where a row with no icon starts, so that its *title* lines up with the title of the row
 * above it.
 *
 * A `PiRow`'s title sits at 12dp of outer gutter + 12dp of inner padding + a 24dp leading
 * mark + the 16dp gap between them = 64dp, and a row without a mark starts its title at the
 * 24dp two paddings add up to. 40dp is the difference, and it is the same 64dp
 * `PiRowDivider` insets its line to — which is why the rule reads in one place: a number
 * row's label, the divider above it and the title of the switch row it belongs under all
 * begin on the same column.
 */
private val NUMBER_ROW_INDENT = 40.dp

/**
 * One of a model's two numbers, as a row rather than a box.
 *
 * The value is the user's number, or — in the muted colour — the number pi will use while
 * the user has said nothing. That difference is the whole of what an empty field used to
 * mean, and it was previously invisible: the boxes were pre-filled with the fallback, so a
 * number the user had chosen and a number pi would have used anyway looked identical.
 *
 * Editing happens in [ModelNumberSheet]. A row cannot hold a text field without either a
 * floating label inside a 33%-wide value column or a full-width field that breaks the
 * frame's rhythm, and these two numbers are the rare half of the section — see the section
 * itself for what the two boxes looked like.
 */
@Composable
private fun ModelNumberRow(
    title: String,
    set: Long?,
    fallback: Long?,
    unknown: String,
    onClick: () -> Unit,
) {
    PiRow(
        title = title,
        modifier = Modifier.padding(start = NUMBER_ROW_INDENT),
        trailing = {
            RowValue(
                value = set?.toString() ?: fallback?.toString() ?: unknown,
                emphasised = set != null,
                monospace = true,
                chevron = true,
            )
        },
        onClick = onClick,
    )
}

/**
 * A row's value, plus the chevron the row would draw for itself.
 *
 * `PiRow` draws a chevron only when its trailing slot is empty, and a row whose value is in
 * that slot — the provider, the wire protocol, one of a model's two numbers — needs both:
 * the value is the fact and the chevron is what says the row opens something, and a value
 * that replaced the chevron would be a row that does something and looks like one that does
 * not.
 *
 * Muted for a value the app is reporting, plain for one the user chose, which is the
 * difference between a number the user typed and the number pi would use if they said
 * nothing about it.
 */
@Composable
private fun RowValue(
    value: String,
    emphasised: Boolean = false,
    monospace: Boolean = false,
    chevron: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasised) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontFamily = if (monospace) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * One number, typed in a sheet.
 *
 * A sheet rather than a dialog for the reason every other input in this app is one
 * (`Sheets.kt`): this app's modal layer is a view in the page, so the keyboard stays up and
 * the field keeps focus, where an `AlertDialog` closes the keyboard the moment it appears.
 *
 * The field starts from what the user saved and *not* from pi's fallback, with the fallback
 * as the placeholder: saving a number pi would have used anyway would write an entry that
 * says nothing, and the whole point of the entry is to say something. It follows
 * `WebSearchValueSheet`'s rule for the same reason — a value that arrives because a
 * placeholder showed it is a configuration the user never chose.
 *
 * Emptying the field is a real answer, not a mistake: it removes the number from the entry,
 * which is how pi's own value comes back.
 */
@Composable
private fun ModelNumberSheet(
    title: String,
    model: String,
    initial: String,
    fallback: Long?,
    onSave: (Long?) -> Unit,
) {
    val host = LocalSheetHost.current
    val text = strings
    var draft by remember(model, title) { mutableStateOf(initial) }

    // The number's name over the model it belongs to: a model has two of these, and the
    // pair is what says which one is being edited without the reader having to remember
    // which row they tapped. The body owns its own `Column`, because the layer hands the
    // sheet's content a `Box` and four siblings in it would be drawn on top of each other.
    Column(SHEET_FORM_PADDING) {
        PiSheetTitle(title = title, subtitle = model)
        PiTextField(
            value = draft,
            onValueChange = { draft = tokenDigits(it) },
            placeholder = fallback?.toString() ?: text.settings.unknown,
            keyboardType = KeyboardType.Number,
            modifier = FIELD_INSET,
        )
        PiNotice(text.settings.modelNumbersNote, tone = PiTone.Neutral)
        PiSheetActions {
            PiButton(
                text = text.common.cancel,
                onClick = host::dismiss,
                kind = PiButtonKind.Text,
                size = PiButtonSize.Small,
            )
            PiButton(
                text = text.settings.save,
                onClick = {
                    // Null for an emptied field, which `withWindow`/`withMaxTokens` turn
                    // into "no number in the entry" rather than into a zero.
                    onSave(draft.toLongOrNull())
                    host.dismiss()
                },
                size = PiButtonSize.Small,
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
 * picker's rows carry the source as their second line rather than in their identity, and
 * the row key is the source plus the id for that reason (`ModelPickerSheet`).
 */
private fun capabilityLine(model: DiscoveredModel, text: Strings): String = buildList {
    if (model.name.isNotBlank() && model.name != model.id) add(model.name)
    add(model.source.label)
    model.contextWindow?.let { add(text.settings.contextWindow((it / 1000).toInt())) }
    if (model.supportsImages) add(text.settings.capabilityImages)
    if (model.reasoning) add(text.settings.capabilityReasoning)
}.joinToString(" · ")
