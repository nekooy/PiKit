package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pi.kit.mob.data.AvatarColor
import pi.kit.mob.data.AvatarStore
import pi.kit.mob.data.AgentMode
import pi.kit.mob.data.ChatPreset
import pi.kit.mob.data.DEFAULT_CHAT_SYSTEM_PROMPT
import pi.kit.mob.data.PiSettings
import pi.kit.mob.locales.Strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.chatPresetName
import pi.kit.mob.ui.components.chatPresetSummary
import pi.kit.mob.ui.kit.AvatarSpeaker
import pi.kit.mob.ui.kit.LocalPiToast
import pi.kit.mob.ui.kit.PiAvatar
import pi.kit.mob.ui.kit.PiConfirm
import pi.kit.mob.ui.kit.PiCorner
import pi.kit.mob.ui.kit.PiGroupAction
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.piFieldCursor
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The Agent page's chat group: the reader's personas, their default, and their editor.
 *
 * The group is one card of preset rows with the add action under it, outside the card:
 * the add is an act on the list rather than one more persona in it. The rows lead with
 * the preset's disc, because that is what the composer's chip and the transcript show;
 * the default badge stays in the trailing slot, and the row opens the editor.
 *
 * ## What is inherited, and why the switch is a switch
 *
 * A preset may set its own name, picture and colour, and every part it leaves unset is
 * answered by the personalization page — the promise that a preset with only a prompt
 * still signs its answers as the agent the reader already named. The name and the picture
 * say "unset" by being empty (the field's hint and the clear row are the controls); the
 * colour cannot be empty in a swatch row, so it has a switch of its own that writes the
 * blank and leaves the swatches disabled.
 */
@Composable
internal fun ChatPresetSection(
    session: PiAgentSession,
    settings: PiSettings,
    text: Strings,
) {
    val store = session.host.chatPresets
    val presets by session.chatPresets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var defaultId by remember { mutableStateOf(store.defaultId) }
    var defaultPicker by remember { mutableStateOf(false) }
    // The editor keeps its subject and its visibility in two values, because a Miuix
    // window animates out through `show` and a subject that vanished with the flag would
    // leave an empty panel on the way out. `openToken` re-seeds the prompt draft on each
    // *open* rather than on each recomposition: the sheet is composed while it is closed.
    // The preset the sheet is *drawing*, kept when the row is deleted so the exit
    // animates over the editor that was open rather than over the fallback preset. A
    // Miuix window is driven by `show`; content that vanished with the flag would show
    // a different persona on the way out.
    var shownPreset by remember { mutableStateOf<ChatPreset?>(null) }
    var editorShown by remember { mutableStateOf(false) }
    var openToken by remember { mutableStateOf(0) }

    val defaultPreset = presets.firstOrNull { it.id == defaultId } ?: presets.first()

    fun open(preset: ChatPreset) {
        shownPreset = preset
        openToken++
        editorShown = true
    }

    fun add() {
        val created = ChatPreset(
            id = store.newId(),
            name = text.settings.presetNewName,
        )
        store.upsert(created)
        open(created)
    }

    Column(Modifier.fillMaxWidth()) {
        PiSection(title = text.settings.chatMode) {
            SettingsNote(text = text.settings.chatModeSubtitle)
            ArrowPreference(
                title = text.settings.presetDefaultRow,
                summary = text.settings.presetDefaultSubtitle,
                endActions = {
                    SettingsValue(
                        chatPresetName(defaultPreset, settings, text),
                        emphasised = true,
                    )
                },
                onClick = { defaultPicker = true },
            )
            presets.forEach { preset ->
                val identity = preset.identity(settings)
                ArrowPreference(
                    title = chatPresetName(preset, settings, text),
                    summary = chatPresetSummary(preset, text),
                    startAction = {
                        PiAvatar(
                            speaker = AvatarSpeaker.AGENT,
                            color = identity.color.seed,
                            imagePath = identity.image.ifBlank { null },
                            size = PRESET_ROW_AVATAR,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                    endActions = {
                        if (preset.id == defaultId) {
                            SettingsValue(
                                text.settings.presetDefaultBadge,
                                emphasised = true,
                            )
                        }
                    },
                    onClick = { open(preset) },
                )
            }
        }
        PiGroupAction(text = text.settings.presetAdd, onClick = ::add)
    }

    SettingsPickerSheet(
        show = defaultPicker,
        title = text.settings.presetDefaultRow,
        subtitle = text.settings.presetDefaultSubtitle,
        options = presets.map { preset ->
            SettingsPick(
                id = preset.id,
                label = chatPresetName(preset, settings, text),
                description = chatPresetSummary(preset, text),
            )
        },
        selectedId = defaultId,
        onPick = { id ->
            defaultPicker = false
            store.setDefault(id)
            defaultId = id
        },
        onDismiss = { defaultPicker = false },
    )

    val editing = shownPreset?.let { drawn ->
        // Follow the store while the preset still exists, so the sheet is live; fall
        // back to the drawn object once it has been deleted, for the exit.
        presets.firstOrNull { it.id == drawn.id } ?: drawn
    } ?: defaultPreset
    PiSheet(
        show = editorShown,
        title = text.settings.presetEditTitle,
        onDismiss = { editorShown = false },
    ) {
        ChatPresetEditor(
            preset = editing,
            stateKey = openToken,
            shown = editorShown,
            canDelete = presets.size > 1,
            settings = settings,
            text = text,
            onChange = { store.upsert(it) },
            onPromptSaved = {
                // A chat conversation reads its prompt once, at process start. Saving
                // while *this* conversation is the one using the preset schedules the
                // relaunch rather than cutting an answer that is being written.
                if (session.mode.value == AgentMode.CHAT &&
                    session.chatPresetId.value == editing.id
                ) {
                    session.scheduleRestart()
                }
            },
            onDelete = {
                val removed = store.delete(editing.id)
                if (removed) {
                    if (defaultId == editing.id) defaultId = store.defaultId
                    // The persona's picture is a file of this app's; deleting the preset
                    // has to delete it, or the backup carries a picture nothing names.
                    AvatarStore.clear(context.filesDir, "preset-${editing.id}")
                    editorShown = false
                }
            },
        )
    }
}

/**
 * One preset, edited: its identity above its prompt.
 *
 * The identity saves as it is typed and tapped, like the personalization page's avatar
 * sheet — none of it restarts anything. The prompt is a document with a Save, like the
 * editor it replaces, because a launch reads it once and every keystroke would otherwise
 * schedule a relaunch.
 *
 * [stateKey] is the open counter: it re-seeds the prompt draft on every open, so an
 * abandoned edit is not carried into the next visit. The name field is deliberately not
 * keyed on it — the sheet is reached from the row whose title it edits, and re-seeding it
 * mid-typing would fight the cursor.
 */
@Composable
private fun ChatPresetEditor(
    preset: ChatPreset,
    stateKey: Int,
    shown: Boolean,
    canDelete: Boolean,
    settings: PiSettings,
    text: Strings,
    onChange: (ChatPreset) -> Unit,
    onPromptSaved: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val toasts = LocalPiToast.current
    val filesDir = context.filesDir
    val slotId = "preset-${preset.id}"
    val identity = preset.identity(settings)

    // The prompt editor is a glass sheet field; state-based is the beta02 API for new
    // fields, and the state is also what the restore button resets.
    val draftState = remember(preset.id, stateKey) { TextFieldState(preset.prompt) }
    val draft = draftState.text.toString()
    var deleteAsk by remember(preset.id) { mutableStateOf(false) }
    val picker = rememberAvatarPicturePicker(
        filesDir = filesDir,
        slotId = slotId,
        text = text,
        onStored = { path -> onChange(preset.copy(avatarImage = path)) },
    )

    // Opening re-seeds from the stored prompt, and only on the open: the draft is left
    // alone while the sheet slides out, which is the same rule every Miuix window has.
    LaunchedEffect(shown, stateKey) {
        if (shown) draftState.setTextAndPlaceCursorAtEnd(preset.prompt)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .piOverScroll()
            .verticalScroll(rememberScrollState())
            // Inside the scroll: the editor's last row travels clear of the gesture strip
            // and the sheet's fill still reaches the window's edge.
            .padding(bottom = piSheetBottomInset()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PiAvatar(
                speaker = AvatarSpeaker.AGENT,
                color = identity.color.seed,
                imagePath = identity.image.ifBlank { null },
                size = PRESET_EDITOR_AVATAR,
            )
            Text(
                text = chatPresetName(preset, settings, text),
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }

        Card(cornerRadius = PiCorner.radius) {
            SettingsTextField(
                value = preset.name,
                onValueChange = { value ->
                    onChange(preset.copy(name = value.take(PRESET_NAME_MAX)))
                },
                label = text.settings.presetName,
                supporting = text.settings.presetNameHint,
                singleLine = true,
            )
            ArrowPreference(
                title = text.settings.avatarPicture,
                summary = text.settings.avatarPictureHint,
                endActions = {
                    SettingsValue(
                        if (preset.avatarImage.isBlank()) {
                            text.settings.avatarPicturePick
                        } else {
                            text.settings.avatarPictureChange
                        },
                    )
                },
                onClick = picker.pick,
            )
            if (preset.avatarImage.isNotBlank()) {
                ArrowPreference(
                    title = text.settings.avatarPictureClear,
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    onClick = {
                        AvatarStore.clear(filesDir, slotId)
                        onChange(preset.copy(avatarImage = ""))
                    },
                )
            }
            if (picker.failed) {
                SettingsNote(text.settings.avatarPictureFailed, danger = true)
            }
            // The colour is inherited *or* chosen, so the switch writes the blank that
            // means "follow the personalization page". The swatches are disabled while
            // it is on rather than hidden: the row keeps its height and the choice stays
            // visible as the one that is not in force.
            val inherited = preset.avatarColor.isBlank()
            SwitchPreference(
                title = text.settings.presetAvatarInherit,
                summary = text.settings.presetAvatarInheritHint,
                checked = inherited,
                onCheckedChange = { follow ->
                    onChange(preset.copy(avatarColor = if (follow) "" else identity.color.code))
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarColor.entries.forEach { entry ->
                    SettingsSwatch(
                        color = entry.seed,
                        selected = !inherited && preset.color == entry,
                        onClick = { onChange(preset.copy(avatarColor = entry.code)) },
                        label = text.settings.avatarColorName(entry),
                        size = PRESET_SWATCH,
                        // The inherited state is the preset saying "follow the
                        // personalization page"; a swatch that still writes a colour
                        // there would silently override it.
                        enabled = !inherited,
                    )
                }
            }
        }

        Text(
            text = text.settings.presetPrompt,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
        )
        Text(
            text = text.settings.chatSystemPromptEditorHint,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        GlassTextField(
            state = draftState,
            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                // Braces and line breaks are syntax here; an autocorrected token is a
                // placeholder that silently stops being one.
                autoCorrectEnabled = false,
            ),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cornerRadius = PiCorner.radius,
            colors = GlassTextFieldDefaults.textFieldColors(
                borderColor = Color.Transparent,
            ),
            cursorBrush = piFieldCursor(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PRESET_EDITOR_MIN, max = PRESET_EDITOR_MAX),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsButton(
                text = text.settings.chatSystemPromptRestore,
                onClick = { draftState.setTextAndPlaceCursorAtEnd(DEFAULT_CHAT_SYSTEM_PROMPT) },
                modifier = Modifier.weight(1f),
            )
            SettingsButton(
                text = text.settings.agentContextSave,
                onClick = {
                    onChange(preset.copy(prompt = draft))
                    onPromptSaved()
                    toasts?.show(text.settings.agentContextSaved)
                },
                modifier = Modifier.weight(1f),
                primary = true,
            )
        }

        ArrowPreference(
            title = text.settings.presetDelete,
            summary = text.settings.presetDeleteHint,
            titleColor = BasicComponentDefaults.titleColor(
                color = MiuixTheme.colorScheme.error,
            ),
            enabled = canDelete,
            onClick = { deleteAsk = true },
        )
    }

    PiConfirm(
        show = deleteAsk,
        title = text.settings.presetDeleteTitle,
        text = text.settings.presetDeleteBody,
        confirm = text.settings.presetDelete,
        dismiss = text.common.cancel,
        destructive = true,
        onDismissRequest = { deleteAsk = false },
        onDismiss = { deleteAsk = false },
        onConfirm = {
            deleteAsk = false
            onDelete()
        },
    )
}

/** The disc on a preset's settings row. */
private val PRESET_ROW_AVATAR = 28.dp

/** The preview at the top of the editor. */
private val PRESET_EDITOR_AVATAR = 64.dp

/** A preset's name is drawn beside a 28dp mark on one row; this is where it is cut. */
private const val PRESET_NAME_MAX = 16

/** Eight swatches in one row inside a sheet's card: 8 x 30dp + 7 x 4dp = 268dp. */
private val PRESET_SWATCH = 30.dp

/** The prompt field's floor and ceiling: a document, bounded so the buttons stay reachable. */
private val PRESET_EDITOR_MIN = 200.dp
private val PRESET_EDITOR_MAX = 380.dp
