package pi.kit.mob.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pi.kit.mob.data.AvatarSlot
import pi.kit.mob.data.AvatarStore
import pi.kit.mob.data.FontSize
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeColor
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * Cold-start conversation behaviour, the interface theme and its accent, the text
 * size, the launcher icon and the transcript's two speakers.
 *
 * Six settings that are all "how the app feels when I open it" and none of which
 * is about the agent: which conversation a launch lands in, how large its text is,
 * which palette that text is drawn in, which colour the app's actions and section
 * names are drawn in, which of the two marks the home screen shows, and whether the
 * transcript draws who is talking. They share a page because a page each would be
 * six rows deep — the same mistake the old "Advanced" group made.
 *
 * The theme and the accent are two questions rather than one — light/dark *and*
 * hue — so they are two groups: the mode is three radio rows, the accent is a row of
 * swatches. `ThemeColor` says why the accent is derived from one seed instead of
 * being two hand-written palettes per colour.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
 *
 * ## The theme is radio rows, the accent is swatches, the size is a slider
 *
 * All of these used to be a row that opened a picker sheet. Three of them are a *set*
 * — three palettes, eight colours, two marks — and a set is better read at once than
 * one at a time behind a scrim: the modes and the marks as radio rows mean the choice
 * is visible, the current one is marked, and picking one re-themes the very rows that
 * were just tapped. That is the live preview the old sheet hid behind its own scrim.
 * The accent is the one of the three drawn as a row of swatches rather than as rows of
 * names, because a colour is recognised before it is read and eight rows of colour
 * names would be the longest group on the page for the least text.
 *
 * The text size is the one that is a *range*, and Miuix has the component for it:
 * `SliderPreference` keeps the slider on the row instead of behind a panel, which
 * removes the sheet and the "let go to apply" note with it. The one behaviour the
 * panel had is kept: the step is committed on **release**, not per tick — measured on
 * the emulator, applying each step under the finger re-measured the whole interface and
 * the drag stopped being delivered (nine `input motionevent MOVE`s advanced one step),
 * so the drag moves a draft and the store is written when the thumb stops.
 *
 * The icon's choice is the one on this page whose effect the app does not draw:
 * `applyLauncherIcon` turns a manifest alias on and the other off, and the system paints
 * what the launcher reads. Its footnote is about the home screen rather than about the
 * icon, because that is the part a user notices.
 */
@Composable
internal fun PersonalizationPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val settings by session.settingsStore.settings.collectAsState()

    // Which avatar is being edited, as the sheet's own state: a Miuix sheet is a window
    // of its own and is dismissed by the scrim, the back gesture and a downward drag, so
    // the page only has to hold *which* one is open. Null closes it.
    var editingAvatar by remember { mutableStateOf<AvatarSlot?>(null) }

    PiPage(
        title = text.settings.personalization,
        subtitle = text.settings.personalizationSubtitle,
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 12.dp),
        ) {
            PiSection(title = text.settings.personalizationConversation) {
                SwitchPreference(
                    title = text.settings.openNewOnLaunch,
                    summary = text.settings.openNewOnLaunchSubtitle,
                    checked = settings.openNewOnLaunch,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(openNewOnLaunch = on) }
                    },
                )
            }

            PiSection(title = text.settings.personalizationAppearance) {
                FontSizeRow(
                    current = settings.fontSize,
                    onChange = { chosen ->
                        session.settingsStore.update { it.copy(fontSize = chosen) }
                    },
                    text = text,
                )
            }

            PiSection(title = text.settings.theme) {
                ThemeMode.entries.forEach { mode ->
                    RadioButtonPreference(
                        title = themeModeLabel(mode, text),
                        selected = settings.themeMode == mode,
                        onClick = {
                            session.settingsStore.update { it.copy(themeMode = mode) }
                        },
                    )
                }
            }

            // The accent, as swatches rather than as rows of names: a colour is
            // recognised before it is read, and eight rows of colour names would be the
            // longest group on the page for the least text. The row above them is what
            // names the current one — the swatches have no room for a label, and a ring
            // cannot say *which* colour it is, only that one is chosen.
            PiSection(title = text.settings.themeColor) {
                // The wallpaper's palette first, as a row rather than a ninth swatch:
                // the swatches are *seeds* (the avatars' picker reuses them), and
                // "whatever this phone's wallpaper is" is not one. The system supplies
                // the palette only from Android 12; below that `PiKitMiuixTheme` falls
                // back to the swatches, which is what the subtitle says.
                SwitchPreference(
                    title = text.settings.themeDynamic,
                    summary = text.settings.themeDynamicSubtitle,
                    checked = settings.themeDynamic,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(themeDynamic = on) }
                    },
                )
                BasicComponent(
                    title = text.settings.themeColor,
                    summary = text.settings.themeColorSubtitle,
                    endActions = {
                        SettingsValue(
                            text.settings.themeColorName(settings.themeColor.code),
                            emphasised = true,
                        )
                    },
                )
                // Scrollable as a safety rather than as the normal case: eight 38dp
                // swatches and their gaps are 332dp, which fits the narrowest phone this
                // app targets, and a larger font scale or a longer translation cannot
                // push them off the edge because the row scrolls instead of clipping.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThemeColor.entries.forEach { color ->
                        SettingsSwatch(
                            color = color.seed,
                            selected = settings.themeColor == color,
                            onClick = {
                                session.settingsStore.update { it.copy(themeColor = color) }
                            },
                        )
                    }
                }
            }

            PiSection(title = text.settings.launcherIcon) {
                LauncherIcon.entries.forEach { icon ->
                    RadioButtonPreference(
                        title = launcherIconLabel(icon, text),
                        selected = settings.launcherIcon == icon,
                        onClick = {
                            session.settingsStore.update { it.copy(launcherIcon = icon) }
                        },
                    )
                }
            }

            // The transcript's two speakers, as names, pictures or marks. A switch
            // over the pair rather than one per side: they are one feature — "draw
            // who is talking" — and a reader who wants them off wants both off.
            PiSection(title = text.settings.avatars) {
                SwitchPreference(
                    title = text.settings.showAvatars,
                    summary = text.settings.showAvatarsSubtitle,
                    checked = settings.showAvatars,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(showAvatars = on) }
                    },
                )
                ArrowPreference(
                    title = text.settings.aiAvatar,
                    endActions = {
                        SettingsAvatar(
                            color = settings.aiAvatarColor.seed,
                            label = settings.aiAvatarLabel.ifBlank { null },
                            imagePath = settings.aiAvatarImage.ifBlank { null },
                            // The agent's blank is the app's own Pi mark.
                            mark = true,
                        )
                    },
                    onClick = { editingAvatar = AvatarSlot.Ai },
                )
                ArrowPreference(
                    title = text.settings.userAvatar,
                    endActions = {
                        SettingsAvatar(
                            color = settings.userAvatarColor.seed,
                            // Blank draws the silhouette — the reader's default mark.
                            label = settings.userAvatarLabel.ifBlank { null },
                            imagePath = settings.userAvatarImage.ifBlank { null },
                            icon = MiuixIcons.Contacts,
                        )
                    },
                    onClick = { editingAvatar = AvatarSlot.User },
                )
            }
        }
    }

    // One sheet for both speakers: the body is told which slot it edits, so the two rows
    // cannot drift into two editors that save differently.
    val slot = editingAvatar
    PiSheet(
        show = slot != null,
        title = when (slot) {
            AvatarSlot.Ai -> text.settings.aiAvatar
            AvatarSlot.User -> text.settings.userAvatar
            null -> null
        },
        onDismiss = { editingAvatar = null },
    ) {
        when (slot) {
            AvatarSlot.Ai -> AvatarSheet(
                slot = AvatarSlot.Ai,
                label = settings.aiAvatarLabel,
                imagePath = settings.aiAvatarImage,
                mark = true,
                fallbackIcon = null,
                color = settings.aiAvatarColor,
                onChangeLabel = { value ->
                    session.settingsStore.update { it.copy(aiAvatarLabel = value) }
                },
                onChangeImage = { path ->
                    session.settingsStore.update { it.copy(aiAvatarImage = path) }
                },
                onChangeColor = { chosen ->
                    session.settingsStore.update { it.copy(aiAvatarColor = chosen) }
                },
                text = text,
            )

            AvatarSlot.User -> AvatarSheet(
                slot = AvatarSlot.User,
                label = settings.userAvatarLabel,
                imagePath = settings.userAvatarImage,
                mark = false,
                fallbackIcon = MiuixIcons.Contacts,
                color = settings.userAvatarColor,
                onChangeLabel = { value ->
                    session.settingsStore.update { it.copy(userAvatarLabel = value) }
                },
                onChangeImage = { path ->
                    session.settingsStore.update { it.copy(userAvatarImage = path) }
                },
                onChangeColor = { chosen ->
                    session.settingsStore.update { it.copy(userAvatarColor = chosen) }
                },
                text = text,
            )

            null -> Unit
        }
    }
}

/**
 * The text size, as Miuix's own slider row.
 *
 * ## Why the slider is on the page rather than behind a panel
 *
 * It used to be a sheet with two "A" samples and a percentage. A sheet is a window of
 * its own, so the one control whose whole point is that the interface changes size was
 * the one control that could not show the change until it was closed — and the note
 * under the track had to *say* "let go to apply" because the drag did not. On the row,
 * the drag re-measures the page it is on, which is the preview.
 *
 * ## The drag commits on release, and that is a measurement
 *
 * The first version applied each step as the finger passed it. Measured on the
 * emulator, dragging across the whole track with nine `input motionevent MOVE`s
 * advanced **one** step (80% → 90%): the first change re-laid the page out under the
 * pointer and the gesture stopped being delivered. A single MOVE of the same total
 * distance moved four steps, which is what shows the mapping is fine and the
 * interruption is the live update. So the thumb moves a draft — the value text, the
 * thumb and the ticks, all of which are this row's own — and the store is written in
 * `onValueChangeFinished`, which is one re-measure per gesture instead of one per pixel.
 *
 * The draft is keyed on the stored step, so the row follows a change made anywhere else
 * (a restore, a build whose default moved) without a hand-written effect, and it does
 * not fight the finger while the finger is down.
 */
@Composable
private fun FontSizeRow(
    current: FontSize,
    onChange: (FontSize) -> Unit,
    text: Strings,
) {
    val steps = FontSize.entries
    var draft by remember(current) { mutableStateOf(current.ordinal.toFloat()) }
    val shown = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]

    SliderPreference(
        value = draft,
        onValueChange = { draft = it },
        title = text.settings.fontSize,
        summary = text.settings.fontSizeSubtitle,
        // The step as a percentage of the app's own sizes, which is the one number a
        // reader can check against nothing at all: `100%` is what a fresh install has.
        valueText = "${shown.percent}%",
        valueRange = 0f..steps.lastIndex.toFloat(),
        // Miuix (like the slider it wraps) counts the ticks *between* the ends, so
        // seven positions is five of them.
        steps = steps.size - 2,
        onValueChangeFinished = {
            val chosen = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]
            if (chosen != current) onChange(chosen)
        },
    )
}

/**
 * The avatar editor: a large preview, the picture, the label field, and the eight
 * colours.
 *
 * Saves as it is typed and on a swatch tap, like the working directory and the theme
 * accent — none of them restarts anything, and the sheet is dismissed by the scrim, the
 * drag and back, so a second "done" control would be the third of three.
 *
 * Everything is inside one card, which is what keeps the sheet on a single column: the
 * sheet's own 24dp gutter plus a card's 16dp is where the rows' text starts, and the
 * field put inside the same card starts there too. A field outside the card sat 16dp to
 * the left of the rows above it.
 *
 * ## The picture
 *
 * A row above the label rather than a second control beside it, because the two are
 * not peers: a picture is drawn *instead of* the label, which is what the hint under
 * the row says. Picking one opens the system's image picker and copies the result
 * into the app's own storage ([AvatarStore]); a picture that could not be read is
 * reported in the sheet rather than leaving the circle unchanged and unexplained.
 *
 * The label is capped at [AVATAR_LABEL_MAX] characters: it is drawn in a circle the
 * size of a thumbnail, where a fourth character is already an ellipsis.
 */
@Composable
private fun AvatarSheet(
    slot: AvatarSlot,
    label: String,
    imagePath: String,
    mark: Boolean,
    fallbackIcon: ImageVector?,
    color: ThemeColor,
    onChangeLabel: (String) -> Unit,
    onChangeImage: (String) -> Unit,
    onChangeColor: (ThemeColor) -> Unit,
    text: Strings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failed by remember(slot) { mutableStateOf(false) }
    val filesDir = context.filesDir

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val stored = AvatarStore.store(context, filesDir, slot, uri)
            failed = stored == null
            // The old picture is removed only once a new one is on disk: a pick that
            // could not be read leaves the avatar as it was rather than blanking it.
            if (stored != null) {
                onChangeImage(stored)
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            SettingsAvatar(
                color = color.seed,
                label = label.ifBlank { null },
                imagePath = imagePath.ifBlank { null },
                icon = fallbackIcon,
                mark = mark,
                size = AVATAR_PREVIEW_SIZE,
                labelStyle = MiuixTheme.textStyles.title1,
            )
        }

        Card {
            ArrowPreference(
                title = text.settings.avatarPicture,
                summary = text.settings.avatarPictureHint,
                endActions = {
                    SettingsValue(
                        if (imagePath.isBlank()) {
                            text.settings.avatarPicturePick
                        } else {
                            text.settings.avatarPictureChange
                        },
                    )
                },
                onClick = { pickImage.launch(IMAGE_MIME) },
            )
            if (imagePath.isNotBlank()) {
                ArrowPreference(
                    title = text.settings.avatarPictureClear,
                    // The clear is the one control here that removes something the user
                    // chose, so its title carries the error role.
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    onClick = {
                        // The file goes with the setting: a picture nothing points at is
                        // a file the app would keep for ever, and the backup would carry
                        // it. `AvatarStore.clear` is a single-file delete rather than the
                        // recursive one `SafeDelete` exists for.
                        AvatarStore.clear(filesDir, slot)
                        failed = false
                        onChangeImage("")
                    },
                )
            }
            SettingsTextField(
                value = label,
                onValueChange = { value -> onChangeLabel(value.take(AVATAR_LABEL_MAX)) },
                label = text.settings.avatarLabel,
                supporting = text.settings.avatarLabelHint,
                singleLine = true,
            )
            if (failed) {
                SettingsNote(text.settings.avatarPictureFailed, danger = true)
            }
            Text(
                text = text.settings.avatarColor,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeColor.entries.forEach { entry ->
                    SettingsSwatch(
                        color = entry.seed,
                        selected = entry == color,
                        onClick = { onChangeColor(entry) },
                    )
                }
            }
        }
    }
}

/** Anything the system picker can hand back as an image. */
private const val IMAGE_MIME = "image/*"

/** The most characters an avatar label holds: a third is already an ellipsis. */
private const val AVATAR_LABEL_MAX = 3

/** The large avatar at the top of its editor. */
private val AVATAR_PREVIEW_SIZE = 96.dp

/**
 * A theme's name in the interface language.
 *
 * Three radio rows rather than a segmented control of three short labels: the
 * Japanese `システムに合わせる` is nine characters in a third of a row, which is an
 * ellipsis in a segmented control and a whole label on a row of its own. The enum's
 * own order is the order they are offered in.
 */
private fun themeModeLabel(mode: ThemeMode, text: Strings): String = when (mode) {
    ThemeMode.SYSTEM -> text.settings.themeSystem
    ThemeMode.LIGHT -> text.settings.themeLight
    ThemeMode.DARK -> text.settings.themeDark
}

/**
 * A launcher mark's name in the interface language.
 *
 * Named by their colours rather than by "light" and "dark", because the two words the
 * theme rows above use already mean the interface palette, and the icon does not
 * follow it — a user on the light theme can have the black icon.
 */
private fun launcherIconLabel(icon: LauncherIcon, text: Strings): String = when (icon) {
    LauncherIcon.DARK -> text.settings.launcherIconBlack
    LauncherIcon.LIGHT -> text.settings.launcherIconWhite
}
