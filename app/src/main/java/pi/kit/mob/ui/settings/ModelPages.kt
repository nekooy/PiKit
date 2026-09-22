package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pi.kit.mob.data.ModelProfile
import pi.kit.mob.data.ModelSettings
import pi.kit.mob.data.PiProvider
import pi.kit.mob.data.rememberedThinkingLevels
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.DiscoveredModel
import pi.kit.mob.pi.ModelDiscovery
import pi.kit.mob.pi.ModelDiscoveryClient
import pi.kit.mob.pi.ModelSource
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.pi.clampThinkingLevel
import pi.kit.mob.pi.modelDefinitionFacts
import pi.kit.mob.pi.thinkingLevelsFor
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PageBackHandler
import pi.kit.mob.ui.components.PiIcons
import pi.kit.mob.ui.components.PickerBody
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow
import pi.kit.mob.ui.components.Sheet
import pi.kit.mob.ui.components.thinkingLevelFootnote
import pi.kit.mob.ui.components.thinkingLevelOptions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The profile list, with the active one marked, and the model's thinking level.
 *
 * Selecting a profile is a separate tap from editing it: editing writes on Save,
 * so making "activate" an implicit part of opening the form would leave the user
 * unable to look at a profile without switching to it.
 *
 * Thinking level is here rather than on the agent page because it is a property
 * of the model: pi maps it through the selected model's own reasoning capability,
 * and the level and the model it applies to are meaningless apart.
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
    val profiles = config.profiles
    val activeId = config.activeProfileId
    val settings by session.settingsStore.settings.collectAsState()
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

    // What the thinking row is showing. pi reports the level once it is running
    // and that is the truthful answer; before that the saved value is, already
    // clamped to the levels the model was last seen to support — see
    // `clampThinkingLevel` and `thinkingLevelsFor`.
    //
    // Read as narrowed flows rather than as the whole conversation state: this page
    // is reachable while a turn is running, and collecting the state itself re-ran
    // this entire body on every streamed token of that turn for values that change
    // once per model — the text, the profile list and every row on the page were
    // rebuilt along with them. One flow rather than two because the levels belong to
    // the model: a level list read without the model it was measured for is a list
    // this page has no business showing to anyone.
    val levelsFlow = remember(session) {
        session.conversation
            .map { Triple(it.availableThinkingLevels, it.model?.id, it.thinkingLevel) }
            .distinctUntilChanged()
    }
    val live by levelsFlow.collectAsState(
        initial = with(session.conversation.value) {
            Triple(availableThinkingLevels, model?.id, thinkingLevel)
        },
    )
    val (liveLevels, liveModelId, reportedLevel) = live
    val available = thinkingLevelsFor(
        liveLevels,
        // Before pi has answered for a model the profile names but the agent has not
        // resolved yet, the profile's own id is what the stored list would have been
        // recorded against.
        settings.rememberedThinkingLevels(liveModelId ?: settings.modelId),
    )
    val thinking = reportedLevel?.takeIf { it.isNotBlank() }
        ?: clampThinkingLevel(settings.thinkingLevel, available)

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.settings.profilesTitle,
            subtitle = text.settings.profilesSubtitle,
            onBack = onBack,
        )

        SettingsBody {
            SettingsSection(text.settings.modelBehaviour) {
                // The same row-and-sheet the language setting uses, and the same
                // list the composer's chip offers — built by `thinkingLevelOptions`
                // so the two cannot disagree about a level or a description.
                //
                // It was a `SegmentedChoice` here, which was wrong twice over.
                // Seven Han labels do not fit one row of a phone: the control's own
                // documentation says two to five. And it rendered as an empty box —
                // the control sizes itself from its first segment, so seven of them
                // came out 36dp wide each and every label ellipsised to nothing.
                // Measured on the emulator: a blank 150px rounded rectangle where
                // the seven levels should be, and not one text node in the
                // `uiautomator` dump.
                //
                // The options are the *model's* levels once pi has answered, not
                // pi's seven: a model with four of them clamps a request it cannot
                // honour to the next one above, so a list of seven would be a menu
                // whose rows do not do what they say.
                PickerRow(
                    title = text.settings.thinkingLevel,
                    subtitle = text.settings.thinkingSubtitle,
                    // pi's own name for the level, not a translation of it: the same
                    // string the chip above the composer shows and the same one pi
                    // prints in its own model list.
                    value = thinking,
                    icon = PiIcons.Thinking,
                    options = thinkingLevelOptions(text, available),
                    selectedId = thinking,
                    footnote = thinkingLevelFootnote(text, available),
                    onPick = { level ->
                        // Applied to the running process immediately: pi accepts
                        // the change over RPC, so unlike the provider and model it
                        // needs no restart.
                        session.settingsStore.update { it.copy(thinkingLevel = level) }
                        session.setThinkingLevel(level)
                    },
                )
            }

            SettingsSection(text.settings.savedProfiles) {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        title = profile.displayName,
                        subtitle = subtitleFor(profile, text),
                        icon = Icons.Filled.Key,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (profile.id == activeId) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = text.settings.active,
                                        tint = MaterialTheme.colorScheme.primary,
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

            SettingsNote(text.settings.tapToActivate)

            Button(
                onClick = { onEdit("") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(text.settings.addProfile, modifier = Modifier.padding(start = 8.dp))
            }

            deleteError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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

private fun subtitleFor(profile: ModelProfile, text: Strings): String {
    val provider = profile.providerEntry?.label
        ?: profile.provider.ifBlank { text.settings.noProfileProvider }
    val model = profile.modelId.ifBlank { text.settings.noModelChosen }
    val key = if (profile.apiKey.isBlank()) text.settings.noKey else text.settings.keySaved
    return "$provider · $model · $key"
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
 * directly when it does not. The gesture needs its own handler because the page's
 * parent registers one for the whole tab: a handler composed *inside* the page runs
 * first (`PageBackHandler`), and it yields to a sheet, which is the behaviour this
 * page's pickers depend on.
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
    // The two number rows open a sheet in this host rather than holding a field of
    // their own — see the custom-parameters section for what that replaced.
    val host = LocalSheetHost.current

    val existing = remember(profileId) { store.profiles.firstOrNull { it.id == profileId } }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var provider by remember { mutableStateOf(existing?.providerEntry) }
    var apiKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }

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
    // `CatalogueProbe.error`.
    var catalogueError by remember { mutableStateOf<String?>(null) }
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
    val sheets = LocalSheetHost.current
    // A custom endpoint is the one provider pi has no catalogue for, which changes both
    // questions this section asks: there is nothing to check the ids against, and there is
    // no fallback model to fill the three controls from.
    val isCustomEndpoint = provider in PiProvider.needsBaseUrl
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
    // A custom endpoint has no such model at all, so pi's own defaults for a definition that
    // names nothing are what its boxes show.
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
            models.isNotEmpty() || activeModel.isNotBlank() || settings.isNotEmpty() ||
            draft.isNotBlank()
    } else {
        name != existing.name ||
            provider != existing.providerEntry ||
            apiKey != existing.apiKey ||
            baseUrl != existing.baseUrl ||
            models != existing.selectableModels ||
            activeModel != existing.modelId ||
            settings != existing.modelSettings ||
            draft.isNotBlank()
    }

    /**
     * Puts [raw] into this provider's model list and returns the new list.
     *
     * A model already in the list is not added twice: picking the same one again is
     * a way of choosing it, not of duplicating the row. Adding one always makes it
     * the active model — the field, the fetched list and Save all put the user's
     * attention on that model, and leaving the tick where it was would make the row
     * they just added look inert.
     */
    fun addModel(raw: String): List<String> {
        val model = raw.trim()
        if (model.isBlank()) return models
        models = if (model in models) models else models + model
        activeModel = model
        draft = ""
        return models
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
        val listed = addModel(draft)
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

    fun requestLeave() {
        if (dirty) askingToSave = true else leave()
    }

    // Wins over the tab's own handler because it is composed deeper inside the page,
    // and yields to a sheet because it is `PageBackHandler` rather than `BackHandler`.
    PageBackHandler(enabled = !leaving) { requestLeave() }

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = if (isNew) text.settings.newProfile else text.settings.editProfile,
            subtitle = if (isNew) null else existing?.displayName,
            onBack = ::requestLeave,
        )

        SettingsBody {
            SettingsSection(text.settings.profile) {
                SettingsRow(
                    title = text.settings.name,
                    subtitle = text.settings.nameSubtitle,
                    icon = Icons.Filled.Edit,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text.settings.name) },
                    placeholder = { Text(text.settings.namePlaceholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            SettingsSection(text.settings.provider) {
                PickerRow(
                    title = text.settings.provider,
                    subtitle = provider?.let { text.settings.keyPassedAs(it.envVar) }
                        ?: text.settings.providerSubtitle,
                    value = provider?.label ?: text.settings.notChosen,
                    icon = Icons.Filled.Key,
                    options = PiProvider.entries.map { entry ->
                        PickerOption(id = entry.id, label = entry.label)
                    },
                    selectedId = provider?.id,
                    // pi ships more than thirty single-key providers, so this sheet is a
                    // list to search rather than a handful of rows to compare.
                    searchHint = text.settings.providerPickSearch,
                    onPick = { id ->
                        val entry = PiProvider.fromId(id) ?: return@PickerRow
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
                        }
                    },
                )

                // The endpoint is a property of every provider, not only of the custom
                // one. A built-in provider leaves it blank to use pi's own URL; filling
                // it in is how a proxy or relay in front of DeepSeek/OpenAI is used
                // without giving up the catalogue. A custom endpoint has no default to
                // fall back to, so the same field is required there — `refusal` says so.
                // Inside this section rather than its own so which provider, and where
                // it lives, read as one decision.
                SettingsNote(
                    if (provider in PiProvider.needsBaseUrl) {
                        text.settings.baseUrlNote
                    } else {
                        text.settings.baseUrlOptionalNote
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(text.settings.baseUrl) },
                    placeholder = { Text(text.settings.baseUrlPlaceholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(text.settings.apiKey) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )

                // The same three values are written into pi's own settings.json, so
                // a `pi` the user starts by hand in the terminal answers with this
                // model instead of reporting none.
                SettingsNote(
                    text.settings.terminalModelNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            SettingsSection(text.settings.models) {
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
                SettingsRow(
                    title = text.settings.models,
                    subtitle = text.settings.modelsSubtitle,
                    icon = PiIcons.Model,
                )
                models.forEachIndexed { index, model ->
                    SettingsDivider()
                    SettingsRow(
                        title = model,
                        // The same mark the model row uses on the settings root:
                        // this list is the choice that row's value stands for.
                        icon = PiIcons.Model,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (model == activeModel) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = text.settings.active,
                                        tint = MaterialTheme.colorScheme.primary,
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

                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(text.settings.modelId) },
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
                    placeholder = { Text(text.settings.modelIdPlaceholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )

                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { addModel(draft) },
                        enabled = draft.isNotBlank() && provider != null,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(text.settings.addModel, Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = {
                            val chosen = provider
                            if (chosen == null) {
                                discoveryError = text.settings.chooseProviderFirst
                                return@Button
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
                                                PickerBody(
                                                    title = text.settings.modelCount(result.models.size),
                                                    options = result.models.map { model ->
                                                        PickerOption(
                                                            id = "${model.source}:${model.id}",
                                                            label = model.id,
                                                            description = capabilityLine(model, text),
                                                        )
                                                    },
                                                    selectedId = null,
                                                    onPick = { id ->
                                                        result.models
                                                            .firstOrNull { "${it.source}:${it.id}" == id }
                                                            ?.let { addModel(it.id) }
                                                    },
                                                    footnote = if (
                                                        result.models.any { it.source == ModelSource.PROVIDER }
                                                    ) {
                                                        text.settings.fromProvider
                                                    } else {
                                                        text.settings.fromCatalog
                                                    },
                                                )
                                            },
                                        )
                                    }
                                    is ModelDiscovery.Failure -> discoveryError = result.message
                                }
                                fetching = false
                            }
                        },
                        enabled = !fetching && provider != null,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null)
                        Text(
                            if (fetching) text.settings.fetching else text.settings.fetchModels,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                SettingsNote(
                    text.settings.fetchNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                discoveryError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
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
            //  * **The boxes did not line up with anything.** Every other row in the card
            //    starts at the 16dp gutter with a 24dp icon and a 14dp gap; the fields
            //    started at 12dp, so the card's left edge was ragged in two places.
            //  * **Two floating labels and two numbers is a lot of furniture** for an
            //    advanced setting, and the numbers are the rare half of it.
            //
            // So a model is now a heading row (its id, with the rule that applies to it
            // underneath — the same two sentences the switch and the catalogue rows used to
            // carry separately), then its own rows: the switch, and the two numbers as plain
            // rows whose value is the number, edited in a sheet.
            // A row is 54dp tall and a field is 56dp plus its padding, so this is also the
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
            SettingsSection(text.settings.imageInputSection) {
                // One paragraph for the whole section, at the top, as the report asked for.
                // It was drawn under the first model's switch for a while, to put it next to
                // the control it explains — and that is worse: the section has two controls
                // *and* two numbers per model, the sentence is about the card rather than
                // about one switch, and under a model's switch it read as a note about that
                // model. Here it sits under the heading, where it explains everything below
                // it.
                SettingsNote(
                    text.settings.imageInputNote,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                if (isCustomEndpoint) {
                    SettingsNote(
                        text.settings.imageInputCustomNote,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                if (needsProvider) {
                    // The honest subject. The catalogue was never the problem, and the row
                    // is not tappable because there is nothing to retry: the missing half
                    // is the provider, which is a control above this one.
                    SettingsRow(
                        title = text.settings.catalogueTitle,
                        subtitle = text.settings.needProviderSubtitle,
                        icon = Icons.Filled.Key,
                    )
                } else if (awaitingCatalogue) {
                    SettingsRow(
                        title = text.settings.catalogueTitle,
                        // The reason when there is one, because "could not read it" alone
                        // leaves the reader with nothing to act on: a `pi` that would not
                        // start, a scratch directory that could not be made and an empty
                        // answer are three different problems with three different fixes.
                        subtitle = when {
                            catalogueChecking -> text.settings.imageInputChecking
                            catalogueError != null -> catalogueError.orEmpty()
                            else -> text.settings.imageInputUnavailable
                        },
                        // The subject of the row is the catalogue, so the mark is the
                        // magnifier the model list's own "Fetch models" button carries
                        // rather than the picture icon the image switch uses.
                        icon = Icons.Filled.Search,
                        // A spinner while the answer is coming, and the app's usual "this
                        // row can be tapped" chevron when it did not come: the failure is an
                        // action, not a page, so the alternative would be another custom
                        // vector for one row. The chevron is absent while the check is
                        // running, because a retry then would queue a second process behind
                        // the first.
                        trailing = if (catalogueChecking) {
                            {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            null
                        },
                        showChevron = !catalogueChecking,
                        onClick = if (catalogueChecking) null else { { retries++ } },
                    )
                } else {
                    if (models.isEmpty()) {
                        // No model listed yet: the section still needs a row, or a
                        // paragraph floating under a heading reads as a rendering fault.
                        // The row is informational — the same shape the model list's own
                        // intro row uses.
                        SettingsRow(
                            title = text.settings.imageInput,
                            subtitle = text.settings.noModelChosen,
                            icon = Icons.Filled.Image,
                        )
                    }
                    models.forEachIndexed { index, current ->
                        if (index > 0) SettingsDivider()
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
                        // The numbers this model actually has, as pi's own catalogue
                        // reports them. For an id the catalogue does not contain there is
                        // no such entry, so the fallback model's numbers stand in — that
                        // *is* what pi resolved the id to.
                        val facts = catalogueIdFacts[current]
                        val window = facts.number("contextWindow") ?: fallbackFacts.number("contextWindow")
                        val max = facts.number("maxTokens") ?: fallbackFacts.number("maxTokens")
                        SettingsRow(
                            title = current,
                            subtitle = if (known) {
                                text.settings.imageInputCatalogRow
                            } else {
                                text.settings.imageInputRowSubtitle
                            },
                            icon = PiIcons.Model,
                        )
                        if (!isCustomEndpoint && !known && facts == null && fallbackFacts == null) {
                            // Nothing is known about this id and the throwaway pi that would
                            // have told us what it resolves to could not run. The controls are
                            // withheld rather than drawn empty: a definition written without
                            // the fallback's facts names pi's hard-coded 128k/16k for a model
                            // whose real window nobody asked about, which is the bug this
                            // whole path is written around. The row above says so, and the
                            // status row explains why.
                            return@forEachIndexed
                        }

                        SettingsSwitchRow(
                            title = text.settings.imageInput,
                            icon = Icons.Filled.Image,
                            // Null is "the user has not said", and what the switch shows
                            // then is what the model would have had anyway: the catalogue's
                            // own capability list for a model it describes, the fallback's
                            // for one it does not.
                            checked = settings[current]?.images
                                ?: (facts ?: fallbackFacts).hasImageInput(),
                            onChange = { on ->
                                settings = settings.withImages(current, on)
                            },
                        )
                        ModelNumberRow(
                            title = text.settings.contextWindowLabel,
                            set = settings[current]?.contextWindow,
                            fallback = window,
                            unknown = text.settings.unknown,
                            onClick = {
                                host.show(
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
                        ModelNumberRow(
                            title = text.settings.maxTokensLabel,
                            set = settings[current]?.maxTokens,
                            fallback = max,
                            unknown = text.settings.unknown,
                            onClick = {
                                host.show(
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

            saveMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { if (save()) leave() },
                    modifier = Modifier.weight(1f),
                ) { Text(text.settings.save) }

                // Asks the same question the back arrow does, because it is the same
                // action: this button has always meant "leave without saving".
                TextButton(onClick = ::requestLeave) { Text(text.settings.cancel) }
            }
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

/** The digits of [raw], and at most [MAX_TOKEN_DIGITS] of them: all a token count may be. */
private fun tokenDigits(raw: String): String = raw.filter { it.isDigit() }.take(MAX_TOKEN_DIGITS)

/**
 * Where a row with no icon starts, so that its *title* lines up with the title of the row
 * above it.
 *
 * A settings row is 16dp of gutter, a 24dp icon and a 14dp gap, so a row whose label is
 * meant to read as the continuation of the one above starts 38dp further in. Written down
 * because it is a measurement of another file's layout: if `SettingsRow`'s icon or gap
 * changes, this is what has to change with it.
 */
private val NUMBER_ROW_INDENT = 38.dp

/**
 * One of a model's two numbers, as a row rather than a box.
 *
 * The value is the user's number, or — in the muted colour, which is what
 * `valueEmphasised` is for — the number pi will use while the user has said nothing. That
 * difference is the whole of what an empty field used to mean, and it was previously
 * invisible: the boxes were pre-filled with the fallback, so a number the user had chosen
 * and a number pi would have used anyway looked identical.
 *
 * Editing happens in [ModelNumberSheet]. A row cannot hold a text field without either a
 * floating label inside a 33%-wide value column or a full-width field that breaks the
 * card's rhythm, and these two numbers are the rare half of the section — see the section
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
    SettingsRow(
        title = title,
        value = set?.toString() ?: fallback?.toString() ?: unknown,
        valueEmphasised = set != null,
        monospaceValue = true,
        modifier = Modifier.padding(start = NUMBER_ROW_INDENT),
        showChevron = true,
        onClick = onClick,
    )
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

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp),
        )
        Text(
            text = model,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = tokenDigits(it) },
            placeholder = { Text(fallback?.toString() ?: text.settings.unknown) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            text = text.settings.modelNumbersNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
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
                    // Null for an emptied field, which `withWindow`/`withMaxTokens` turn
                    // into "no number in the entry" rather than into a zero.
                    onSave(draft.toLongOrNull())
                    host.dismiss()
                },
            ) {
                Text(text.settings.save)
            }
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
 * The source of each entry is shown rather than hidden: a model from pi's catalog
 * is not guaranteed to be one the key can use, and the user is the only one who
 * can decide whether that is worth trying.
 *
 * An id can appear twice — once from the provider and once from pi's catalog — so
 * the picker's option ids carry the source. A bare id would key two rows
 * identically, and `LazyColumn` refuses a duplicate key outright.
 */
private fun capabilityLine(model: DiscoveredModel, text: Strings): String = buildList {
    if (model.name.isNotBlank() && model.name != model.id) add(model.name)
    add(model.source.label)
    model.contextWindow?.let { add(text.settings.contextWindow((it / 1000).toInt())) }
    if (model.supportsImages) add(text.settings.capabilityImages)
    if (model.reasoning) add(text.settings.capabilityReasoning)
}.joinToString(" · ")
