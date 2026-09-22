package pi.kit.mob.data

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * What the user has said about one model id pi's own catalogue does not contain.
 *
 * Three fields, and each one is a thing pi cannot work out for itself. An id pi's
 * catalogue does not contain is resolved from a copy of the provider's **default** model
 * (`buildFallbackModel`), and the only mechanism that reaches such an id at all is a
 * `models` entry, which *replaces* that copy: every field the entry does not name falls
 * back to pi's hard-coded default (`modelFromJson`), so an entry that named only `input`
 * silently cut a 1M-window model down to 128k. PiKit therefore writes the fallback's own
 * facts into the entry (`ModelProfile.customModelFacts`) and these three on top.
 *
 * @param images the switch, in three states rather than two. null is "nothing said", and
 *   the entry then repeats the fallback's own `input` — which is what makes a model whose
 *   fallback takes images keep taking them without the user having to declare anything.
 *   true writes `input: ["text", "image"]`, and false writes `["text"]`, which is a real
 *   statement: it takes image input away from a model whose fallback has it. There is no
 *   fourth state, and off is not "no declaration" — that is null — because a switch that
 *   showed off over a model that does accept images would be a capability the app appeared
 *   to have removed.
 * @param contextWindow null when the field was left empty, which means *do not name it*:
 *   the entry falls back to the fallback model's window rather than to a number this app
 *   invented.
 * @param maxTokens the same, for the largest answer the model may produce.
 */
@Serializable
data class ModelSettings(
    @SerialName("images") val images: Boolean? = null,
    @SerialName("contextWindow") val contextWindow: Long? = null,
    @SerialName("maxTokens") val maxTokens: Long? = null,
)

/**
 * One saved model configuration.
 *
 * The provider is stored as pi's provider id rather than as the enum, so a
 * profile written by a build that knows more providers than this one still
 * round-trips instead of being dropped on read.
 */
@Serializable
data class ModelProfile(
    val id: String,
    val name: String = "",
    val provider: String = "",
    @SerialName("apiKey") val apiKey: String = "",
    @SerialName("modelId") val modelId: String = "",
    /**
     * Every model this provider has been used with, the active one included.
     *
     * A profile is a *provider*: one endpoint and one key. The model is a choice
     * made against it, and a provider that can only ever answer with one model is
     * the thing the settings page was reported for — trying a second model meant
     * creating a second profile and pasting the same key again.
     *
     * The active model stays in [modelId] rather than becoming an index into this
     * list, because it is the field the launcher and pi's own `set_model` already
     * speak in; this is the list to choose *from*.
     */
    @SerialName("models") val models: List<String> = emptyList(),
    /**
     * The endpoint for a provider whose URL is not built in — a relay, a gateway
     * or a self-hosted server. For a built-in provider this is an *optional*
     * override: blank means pi's own URL. See [normalizeApiBaseUrl] for how a
     * bare host is completed.
     */
    @SerialName("baseUrl") val baseUrl: String = "",
    /**
     * The endpoint override PiKit last wrote into pi's `models.json` for this
     * provider, which may differ from [baseUrl] after the field was cleared.
     *
     * Withdrawal needs the value that was written, not the one the form now
     * holds: a blank [baseUrl] means "give me the provider's own endpoint", and
     * the override that is still in pi's file has to come out — but only if it
     * is *this app's* override. A `baseUrl` the user wrote into `models.json` by
     * hand is not matched by this record and is left alone. Kept after the field
     * is emptied so the next launch can still find what to withdraw; a second
     * withdrawal of an already-gone key is a no-op.
     */
    @SerialName("writtenBaseUrl") val writtenBaseUrl: String = "",
    /**
     * The per-model settings, keyed by model id, for the ids pi's catalogue does not
     * contain — see [ModelSettings] for what each one means and `modelDefinitions` for
     * where they go.
     *
     * A map rather than three parallel lists because the three always travel together:
     * they describe one model's entry in pi's `models.json`, and a profile that could
     * hold an image flag for an id it no longer offers would write an entry for a model
     * nothing can reach.
     *
     * An id pi's catalogue *does* contain has no entry here on purpose: pi's own entry
     * already states its window, its max-out and whether it takes images, and the entry
     * PiKit would have to write to say anything about it replaces pi's.
     */
    @SerialName("modelSettings") val modelSettings: Map<String, ModelSettings> = emptyMap(),
    /**
     * Every id PiKit has written a `models` entry into pi's `models.json` for, whether or
     * not it is still one of them.
     *
     * This list is how an entry is withdrawn. An entry PiKit wrote names the fields pi
     * itself reported for the model ([customModelFacts]), so once that model's id leaves
     * [modelSettings] — the row was deleted, the switch was turned off, or pi's catalogue
     * learned the id — there is no fixed shape left to recognise it by, and the app's own
     * record of the ids it wrote is the marker instead. `isPikitModelDefinition` still
     * recognises the factless shape older builds wrote.
     *
     * The record **accumulates** rather than being replaced, and that is the point: an id
     * has to stay in it after its settings are gone, or its entry would be left in pi's
     * file to keep replacing what pi resolves for that model. It deliberately carries
     * *catalogued* ids too — that is the case the record is most needed for, because an id
     * that was uncatalogued when the entry was written and catalogued when the page was last
     * saved is precisely the one whose replacing entry must now come out, and the fallback's
     * facts it names leave no shape to recognise it by.
     *
     * Serialized under `customImageModels`, the key the build that introduced the record
     * used for the narrower image-only declaration it then stood for: a profile saved by
     * that build carries its withdrawal record under that key, and reading it under a new
     * one would leave the entries it names in pi's file forever.
     */
    @SerialName("customImageModels") val writtenModels: List<String> = emptyList(),
    /**
     * The fields of the model pi resolves an id its catalogue does not contain to, as pi
     * itself reported them: `buildFallbackModel`'s copy of the provider's **default**
     * model.
     *
     * Read out of a `get_state` reply by `ModelDiscoveryClient.catalogueProbe` and
     * whitelisted by `modelDefinitionFacts`. Without it, a `models` entry would leave
     * every field it does not name at pi's fixed defaults — a 128K window and a 16K
     * max-out for a model whose fallback may well have had something else — which is the
     * bug this whole path is written around. With it, the entry is the fallback plus
     * whatever the user changed, and nothing else moves.
     *
     * One copy for the whole profile because `buildFallbackModel` ignores the requested id:
     * every id this provider does not catalogue resolves to the same default model.
     */
    @SerialName("customModelFacts") val customModelFacts: JsonObject? = null,
    /**
     * What the catalogue check behind [writtenModels] was made against.
     *
     * One string covering the two things that move the answer — pi's own version (its
     * built-in catalogue is compiled into the bundle, so an update can add models) and
     * the revision of `models-store.json`, which the launch path refreshes from pi.dev.
     * An entry is written into pi's `models.json` only while this still matches the
     * world, because an entry left in place after pi learned the model would override the
     * window, cost and thinking map pi now has for it — the bug this whole path exists to
     * avoid. When it does not match, the entries are withdrawn and the model page's own
     * check re-writes them.
     */
    @SerialName("catalogueBasis") val catalogueBasis: String = "",
    /**
     * The ids of [models] that pi's own catalogue already describes, as of the check
     * recorded in [catalogueBasis].
     *
     * **This is what decides which of two mechanisms declares a model.** pi ignores a
     * `modelOverrides` entry for an id it did not resolve from its own catalogue
     * (`composeModelProvider`'s model list is what `applyModelOverride` maps over), so an
     * id the catalogue does not contain needs a `models` entry instead — and a `models`
     * entry *replaces* pi's own for an id it does contain, which is the bug the whole
     * definition path is written around. The two lists are therefore disjoint by
     * construction: an id is in here (an override, which merges) or it is not (a
     * definition, which replaces).
     *
     * It is recorded rather than recomputed because the launch path has to answer the same
     * question as the page did, and the launch path has no pi process to ask: the answer is
     * a fact about the catalogue state the user's check was made against, and
     * [catalogueBasis] is what keeps it honest.
     *
     * An empty list is the state of a profile no check has ever answered for — every id is
     * treated as unknown, which is what this app assumed before the field existed, so a
     * profile written by an older build keeps its old behaviour until the model page is
     * visited again.
     */
    @SerialName("catalogueIds") val knownCatalogueIds: List<String> = emptyList(),
) {
    /** The provider as the launcher needs it, or null when it is unrecognised. */
    val providerEntry: PiProvider? get() = PiProvider.fromId(provider)

    /**
     * The settings of [id], with the defaults pi would use where nothing is stored.
     *
     * The defaults are the model page's starting point for a custom endpoint, which pi
     * has no catalogue for and therefore no fallback facts about: pi's own
     * `modelFromJson` values, spelled once here so the form and the test that pins the
     * written document cannot disagree about them.
     */
    fun settingsFor(id: String): ModelSettings = modelSettings[id] ?: ModelSettings()

    /** What the user sees in a list; never blank, so a row always has a label. */
    val displayName: String
        get() = name.ifBlank { modelId.ifBlank { provider }.ifBlank { "Unnamed profile" } }

    /**
     * The models to offer, with the active one present exactly once.
     *
     * A profile written before this field existed has an empty [models] and one
     * [modelId], so the active model leads the list; a model added without being
     * activated is kept where the user put it. Order is the user's, not the
     * provider's: they added these, and the next one they add goes on the end.
     */
    val selectableModels: List<String>
        get() = when {
            modelId.isBlank() -> models
            modelId in models -> models
            else -> listOf(modelId) + models
        }
}

/** Everything persisted in [PiConfigFile]. */
@Serializable
data class PiConfigSnapshot(
    val profiles: List<ModelProfile> = emptyList(),
    @SerialName("activeProfileId") val activeProfileId: String = "",
) {
    val activeProfile: ModelProfile? get() = profiles.firstOrNull { it.id == activeProfileId }
}

/**
 * The model profiles as an on-disk JSON document.
 *
 * A file rather than `SharedPreferences` because the user asked for a real
 * config file: it survives being copied out of the app, diffed, or hand-edited,
 * which preferences cannot be. Reads are served from an in-memory snapshot, so
 * the synchronous contract the agent launcher depends on is preserved.
 *
 * Every write goes to a sibling temp file and is renamed into place. The file is
 * read on every launch and a half-written one would lose every profile the user
 * has saved, which is not a risk worth taking to save a rename.
 */
object PiConfigFile {

    const val FILE_NAME = "pikit-config.json"

    private const val TAG = "PiKit"

    /**
     * Tolerant on read: this file is part of the app's public surface, so a
     * field added by a newer build must not make an older one refuse to start.
     */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun file(filesDir: File): File = File(filesDir, FILE_NAME)

    /**
     * Reads the document, or null when there is nothing usable to read.
     *
     * A corrupt file is renamed rather than deleted: it is the user's only copy
     * of their keys, and a bug in this app must not be the thing that destroys
     * it. The app continues on defaults, and the next write creates a fresh
     * document.
     */
    fun load(file: File): PiConfigSnapshot? {
        if (!file.isFile) return null
        return try {
            val parsed = json.decodeFromString(PiConfigSnapshot.serializer(), file.readText())
            parsed.copy(
                activeProfileId = parsed.activeProfileId.ifBlank {
                    parsed.profiles.firstOrNull()?.id.orEmpty()
                },
                // A profile written before `writtenBaseUrl` existed still has an
                // override in pi's file. Seeding the record from the field is what
                // lets the next clear withdraw that override instead of leaving a
                // proxy the user turned off in place for ever.
                profiles = parsed.profiles.map { profile ->
                    if (profile.writtenBaseUrl.isBlank() && profile.baseUrl.isNotBlank()) {
                        profile.copy(writtenBaseUrl = profile.baseUrl)
                    } else {
                        profile
                    }
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Could not read $FILE_NAME; keeping it as .corrupt and starting over", t)
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt")) }
            null
        }
    }

    /**
     * Writes the document. Returns false when it could not be persisted, which
     * the caller reports instead of pretending the change was saved.
     */
    fun save(file: File, snapshot: PiConfigSnapshot): Boolean = try {
        val parent = file.parentFile
        parent?.mkdirs()
        val temporary = File(parent, "$FILE_NAME.tmp")
        temporary.writeText(json.encodeToString(PiConfigSnapshot.serializer(), snapshot))
        // Some Android filesystems refuse to rename onto an existing file, so
        // the first attempt is followed by a delete-and-retry.
        temporary.renameTo(file) || run {
            file.delete()
            temporary.renameTo(file)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Could not write $FILE_NAME", t)
        false
    }
}

/**
 * The legacy `SharedPreferences` values the profiles replaced.
 *
 * Kept as an interface so [ProfileStore] does not have to know about the store
 * that owns them, and so its migration can be exercised without Android.
 */
interface LegacySettingsSource {
    fun providerId(): String?
    fun modelId(): String
    fun apiKey(): String

    /** Clears the migrated keys. The working directory and thinking level stay. */
    fun clearModelKeys()
}

/**
 * Owns the set of model profiles and which one the agent is launched with.
 *
 * Lives next to [SettingsStore] rather than inside it so that the profile file
 * has exactly one writer. Nothing else may touch it: two writers would mean the
 * file and the in-memory snapshot could disagree, and the launcher reads the
 * snapshot.
 */
class ProfileStore(
    filesDir: File,
    private val legacy: LegacySettingsSource,
    private val applyToSettings: (ModelProfile?) -> Unit,
) {

    private val file: File = PiConfigFile.file(filesDir)

    /**
     * The document as state, so a reader can *follow* a change instead of reading
     * a snapshot of it.
     *
     * The plain getters below stay for the callers that only want the current value
     * — the launcher, the `models.json` writer — but anything that draws the
     * profiles has to collect this. It did not, and that was two reports: the chat
     * page's chip kept showing the model it was composed with after the settings
     * page changed it, and the picker's tick came from whatever the composition
     * happened to capture. A `StateFlow` rather than Compose state because this is
     * the data layer, and `SettingsStore` next door already publishes its own
     * settings this way.
     */
    private val _snapshot = MutableStateFlow(loadOrMigrate())

    val snapshots: StateFlow<PiConfigSnapshot> = _snapshot.asStateFlow()

    val profiles: List<ModelProfile> get() = _snapshot.value.profiles

    val activeProfile: ModelProfile? get() = _snapshot.value.activeProfile

    val activeId: String get() = _snapshot.value.activeProfileId

    private fun loadOrMigrate(): PiConfigSnapshot {
        PiConfigFile.load(file)?.let { loaded ->
            // The file is authoritative once it exists; the settings it feeds
            // were applied when the store was constructed.
            return loaded
        }
        val migrated = migrateFromPreferences()
        // Written immediately so the migration happens once. A failure here is
        // harmless: the same values are still in SharedPreferences.
        PiConfigFile.save(file, migrated)
        return migrated
    }

    /**
     * Turns the pre-profile settings into the first profile.
     *
     * The user's existing provider, model and key are the whole reason this
     * exists: replacing the settings page must not look like a wipe.
     */
    private fun migrateFromPreferences(): PiConfigSnapshot {
        val provider = legacy.providerId()?.let(PiProvider::fromId)
        val modelId = legacy.modelId()
        val apiKey = legacy.apiKey()
        if (provider == null && modelId.isBlank() && apiKey.isBlank()) return PiConfigSnapshot()

        val profile = ModelProfile(
            id = newId(),
            name = "Default",
            provider = provider?.id.orEmpty(),
            apiKey = apiKey,
            modelId = modelId,
        )
        legacy.clearModelKeys()
        return PiConfigSnapshot(profiles = listOf(profile), activeProfileId = profile.id)
    }

    /** Applies the active profile to the values the agent launcher reads. */
    fun applyActive() {
        applyToSettings(activeProfile)
    }

    private fun commit(next: PiConfigSnapshot): Boolean {
        _snapshot.value = next
        applyActive()
        val persisted = PiConfigFile.save(file, next)
        lastWriteFailed = !persisted
        return persisted
    }

    /**
     * Whether the most recent commit failed to reach disk.
     *
     * The snapshot has already moved either way — see [upsert] — so this is the
     * only way a caller can tell a save that will survive the process from one
     * that will not. Cleared by the next successful write.
     */
    var lastWriteFailed: Boolean = false
        private set

    /**
     * Creates or replaces a profile.
     *
     * Passing a profile with a blank id appends a new one; the returned profile
     * carries the generated id so the caller can select what it just created.
     *
     * The in-memory snapshot always moves — the launcher must not keep running
     * against a profile the user just left — but a caller that cares whether the
     * change survives the process can read [lastWriteFailed]. `upsert` used to
     * drop [PiConfigFile.save]'s answer on the floor, so a full disk looked
     * exactly like a successful save until the next launch re-read the old file.
     */
    fun upsert(profile: ModelProfile): ModelProfile {
        val stored = if (profile.id.isBlank()) profile.copy(id = newId()) else profile
        val current = _snapshot.value
        val existing = current.profiles.indexOfFirst { it.id == stored.id }
        val profiles = if (existing >= 0) {
            current.profiles.toMutableList().also { it[existing] = stored }
        } else {
            current.profiles + stored
        }
        val activeId = if (current.profiles.isEmpty()) stored.id else current.activeProfileId
        commit(current.copy(profiles = profiles, activeProfileId = activeId))
        return stored
    }

    fun setActive(id: String): Boolean {
        if (profiles.none { it.id == id }) return false
        return commit(_snapshot.value.copy(activeProfileId = id))
    }

    /**
     * Removes a profile. The last one cannot be removed: with no profile at all
     * the agent would be launched with no provider and no key, which reads to
     * the user as "the app broke".
     */
    fun delete(id: String): Boolean {
        val current = _snapshot.value
        if (current.profiles.size <= 1) return false
        val profiles = current.profiles.filterNot { it.id == id }
        if (profiles.size == current.profiles.size) return false
        val activeId = if (current.activeProfileId == id) {
            profiles.first().id
        } else {
            current.activeProfileId
        }
        return commit(current.copy(profiles = profiles, activeProfileId = activeId))
    }

    /** True when this provider/model pair is complete enough to launch with. */
    fun isUsable(profile: ModelProfile): Boolean =
        profile.providerEntry != null && profile.modelId.isNotBlank()

    private fun newId(): String = java.util.UUID.randomUUID().toString().take(8)
}
