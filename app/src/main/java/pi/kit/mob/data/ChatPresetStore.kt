package pi.kit.mob.data

import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One chat-mode persona: the system prompt a chat launch sends, and the identity it is
 * drawn under.
 *
 * Chat mode's whole promise is that the leading context is the reader's document and
 * nothing else (ARCHITECTURE §17). One document is one conversation's voice, and a
 * reader who talks to a translator in the morning and a tutor in the evening was
 * retyping the same paragraph into the settings page between them. A preset is that
 * document with a name, so it can be chosen from the composer instead of rewritten.
 *
 * @param name the persona's name, shown on the composer's chip and beside its avatar in
 *   the transcript. Blank is "not set", and the personalization page's agent name is
 *   used instead — the same inheritance [avatarColor] and [avatarImage] describe.
 * @param prompt the stored *template*, `{{time}}` and all. It is rendered at launch, for
 *   the reason `PiSettings.chatSystemPrompt` was: a rendered timestamp would be stale by
 *   the next process and the placeholder's position would be impossible to move.
 * @param avatarColor one of [AvatarColor]'s codes, or blank to inherit the
 *   personalization page's colour — which may itself be following the accent. A code
 *   rather than the enum so a value written by a build that knows a colour this one
 *   does not still round-trips instead of throwing.
 * @param avatarImage an absolute path to a picture this app owns
 *   ([AvatarStore]'s copy), or blank to inherit the personalization page's picture.
 */
@Serializable
data class ChatPreset(
    val id: String,
    val name: String = "",
    val prompt: String = DEFAULT_CHAT_SYSTEM_PROMPT,
    @SerialName("avatarColor") val avatarColor: String = "",
    @SerialName("avatarImage") val avatarImage: String = "",
) {
    /** The stored colour, or null when this preset inherits the personalization one. */
    val color: AvatarColor?
        get() = avatarColor.takeIf { it.isNotBlank() }?.let { AvatarColor.fromCode(it) }

    /** The identity to draw, with every unset part answered by [settings]. */
    fun identity(settings: PiSettings): ChatIdentity = ChatIdentity(
        name = name.ifBlank { settings.aiAvatarName },
        image = avatarImage.ifBlank { settings.aiAvatarImage },
        color = color ?: settings.aiAvatarDisc,
    )
}

/**
 * One chat persona's identity after inheritance.
 *
 * The transcript, the composer's chip and the settings page all draw the same three
 * values, and the fallback is stated once here rather than at each call site: a preset
 * with a prompt and nothing else uses the reader's own agent identity, which is what
 * "未设置时则沿用个性化里的头像和名字" asks for.
 */
data class ChatIdentity(
    val name: String,
    val image: String,
    val color: AvatarColor,
)

/**
 * The chat-mode presets, the one the next new conversation starts with, and the one each
 * saved conversation uses.
 *
 * ## Why a file of its own rather than a [PiSettings] field
 *
 * A preset is a *list* with an identity attached, and the settings data class is a flat
 * record of app-wide switches; the profile store is the precedent for a list that is its
 * own document. Keeping it out of [PiSettings] also keeps the five landing places a
 * settings field has from becoming five places per preset.
 *
 * ## The selection belongs to the conversation
 *
 * A conversation's voice is a fact about that conversation: switching the chip on one
 * transcript must not change the preset another open conversation is answering with,
 * for the same reason its launch mode is per session ([SessionModeStore]). The
 * selection is therefore keyed by the session file; a conversation with no file yet uses
 * the *default* preset, and records its own answer the moment pi writes the file.
 *
 * The file is one SharedPreferences document rather than several keys per preset because
 * a preset is an object: a half-written one (a name without its prompt) is a persona the
 * reader never made, and one JSON string is written whole.
 */
internal class ChatPresetStore(context: Context, private val legacyPrompt: String) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _presets = MutableStateFlow(loadPresets())

    /** Every preset, in the order the settings page shows them. Never empty. */
    val presets: StateFlow<List<ChatPreset>> = _presets.asStateFlow()

    /** The preset the id [id] names, or the first one when it no longer exists. */
    fun preset(id: String?): ChatPreset {
        val list = _presets.value
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    /**
     * The preset a new conversation starts with.
     *
     * A stored default that has since been deleted falls back to the first preset rather
     * than to nothing: a default that names a preset the list does not hold would be a
     * new conversation with no prompt at all.
     */
    val defaultId: String
        get() = prefs.getString(KEY_DEFAULT, null)
            .takeIf { stored -> _presets.value.any { it.id == stored } }
            ?: _presets.value.first().id

    val defaultPreset: ChatPreset get() = preset(defaultId)

    /** The preset [path] uses, or the default for a conversation with no record yet. */
    fun selectedId(path: String?): String {
        if (path.isNullOrBlank()) return defaultId
        val chosen = selections()[path]
        return chosen.takeIf { id -> _presets.value.any { it.id == id } } ?: defaultId
    }

    /** Records [id] for [path]; a conversation with no file yet has nothing to key on. */
    fun setSelection(path: String?, id: String) {
        if (path.isNullOrBlank()) return
        if (selections()[path] == id) return
        val next = selections().toMutableMap().apply { put(path, id) }
        writeSelections(next)
    }

    /** Forgets a deleted conversation's selection so the document does not grow forever. */
    fun forget(path: String) {
        if (path.isBlank()) return
        val current = selections()
        if (path !in current) return
        writeSelections(current - path)
    }

    fun setDefault(id: String) {
        if (_presets.value.none { it.id == id }) return
        prefs.edit().putString(KEY_DEFAULT, id).apply()
    }

    /** Adds or replaces one preset; the list order is the reader's own. */
    fun upsert(preset: ChatPreset) {
        val list = _presets.value
        val index = list.indexOfFirst { it.id == preset.id }
        val next = if (index >= 0) {
            list.toMutableList().apply { set(index, preset) }
        } else {
            list + preset
        }
        if (next == list) return
        writePresets(next)
    }

    /**
     * Removes a preset, unless it is the last one.
     *
     * A chat conversation must always have a prompt to launch with — the list is what
     * answers that — so the final preset is not deletable. A conversation still
     * selecting the deleted id resolves to [defaultId], which is the same answer a
     * deleted conversation's mode gets.
     */
    fun delete(id: String): Boolean {
        val list = _presets.value
        if (list.size <= 1) return false
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        writePresets(next)
        if (prefs.getString(KEY_DEFAULT, null) == id) {
            prefs.edit().putString(KEY_DEFAULT, next.first().id).apply()
        }
        return true
    }

    /** Re-reads the document, for the restore path's "everything was just replaced". */
    fun reload() {
        _presets.value = loadPresets()
    }

    /** A fresh id for a preset the reader is about to create. */
    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun writePresets(next: List<ChatPreset>) {
        _presets.value = next
        prefs.edit()
            .putString(KEY_PRESETS, json.encodeToString(ListSerializer(ChatPreset.serializer()), next))
            .apply()
    }

    private fun loadPresets(): List<ChatPreset> {
        val stored = runCatching {
            val text = prefs.getString(KEY_PRESETS, null) ?: return@runCatching null
            json.decodeFromString(ListSerializer(ChatPreset.serializer()), text)
        }.getOrElse { failure ->
            // A document this build cannot read is replaced by the one-preset
            // fallback below rather than by an empty list: chat mode's prompt must
            // exist even when the presets file is damaged, and the legacy prompt is
            // the best answer there is. Logged so the loss is not silent.
            Log.w(TAG, "chat presets could not be read; falling back to one preset", failure)
            null
        }
        val clean = stored.orEmpty()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        if (clean.isNotEmpty()) return clean
        val fallback = ChatPreset(id = DEFAULT_ID, prompt = legacyPrompt)
        runCatching {
            prefs.edit()
                .putString(KEY_PRESETS, json.encodeToString(ListSerializer(ChatPreset.serializer()), listOf(fallback)))
                .apply()
        }.onFailure { Log.w(TAG, "could not write the initial chat preset", it) }
        return listOf(fallback)
    }

    /** The per-session selection map, path → preset id. */
    private fun selections(): Map<String, String> = runCatching {
        val text = prefs.getString(KEY_SELECTIONS, null) ?: return@runCatching emptyMap()
        val root = Json.parseToJsonElement(text).jsonObject
        root.mapNotNull { (path, value) ->
            value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { path to it }
        }.toMap()
    }.getOrElse { failure ->
        Log.w(TAG, "chat preset selections could not be read", failure)
        emptyMap()
    }

    private fun writeSelections(next: Map<String, String>) {
        val document = buildJsonObject {
            next.forEach { (path, id) -> put(path, JsonPrimitive(id)) }
        }
        prefs.edit().putString(KEY_SELECTIONS, document.toString()).apply()
    }

    companion object {
        /**
         * Also listed in `BackupArchive.PREFERENCE_FILES`: the presets are settings (the
         * whole list survives a restore) and the selections are per conversation (a
         * restored transcript opens in the voice it was written in).
         */
        const val PREFS = "pikit_chat_presets"

        /** The id of the preset made for an install that predates presets. */
        const val DEFAULT_ID = "default"

        private const val KEY_PRESETS = "presets"
        private const val KEY_DEFAULT = "default"
        private const val KEY_SELECTIONS = "selections"

        private const val TAG = "PiKit"
    }
}
