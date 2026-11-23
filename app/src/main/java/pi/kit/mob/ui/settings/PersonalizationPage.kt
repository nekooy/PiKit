package pi.kit.mob.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import pi.kit.mob.data.AccentColor
import pi.kit.mob.data.AvatarColor
import pi.kit.mob.data.AvatarSlot
import pi.kit.mob.data.AvatarStore
import pi.kit.mob.data.FontSize
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.kit.AvatarSpeaker
import pi.kit.mob.ui.kit.LocalPiHaptics
import pi.kit.mob.ui.kit.PiAvatar
import pi.kit.mob.ui.kit.PiDropdownPreference
import pi.kit.mob.ui.kit.PiPage
import pi.kit.mob.ui.kit.PiSection
import pi.kit.mob.ui.kit.PiSheet
import pi.kit.mob.ui.kit.PiSheetSection
import pi.kit.mob.ui.kit.piOverScroll
import pi.kit.mob.ui.kit.piSheetBottomInset
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Cold-start conversation behaviour, the light/dark choice, the text size, the
 * launcher icon and the transcript's two speakers.
 *
 * Five settings that are all "how the app feels when I open it" and none of which is
 * about the agent: which conversation a launch lands in, how large its text is,
 * whether the palette is light or dark, which of the two marks the launcher and
 * recent tasks show, and whether the transcript draws who is talking. They share a
 * page because a page each would be five rows deep — the same mistake the old
 * "Advanced" group made.
 *
 * The *neutral* palette is not on this page: the interface is drawn in Miuix's own
 * light and dark schemes, so "follow the system / light / dark" is the whole of that
 * choice. The accent is the one hue the reader may move, and it is a row of swatches
 * because a colour is recognised before it is read. `PiKitMiuixTheme` carries what
 * moves and what deliberately does not.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
 *
 * ## The modes are dropdown rows, the size is a slider
 *
 * Both used to be a row that opened a picker sheet, and then radio rows. The radio rows were
 * chosen for a real reason — three palettes are a *set*, and a set is better read at once than
 * one at a time behind a **scrim** — and the scrim is what the dropdown removes: Miuix's
 * dropdown opens a panel anchored at the row with no scrim at all, so the row being changed
 * stays in sight, the alternatives are one tap away, and picking one re-themes the very panel
 * that was just tapped. Two rows instead of five, and the shape matches the rest of the app's
 * short-list choices (see `SettingsPickerSheet` for where the line to a filtered sheet is).
 *
 * The text size is the one that is a *range*, and Miuix has the component for it:
 * `SliderPreference` keeps the slider on the row instead of behind a panel, which
 * removes the sheet and the "let go to apply" note with it. The one behaviour the
 * panel had is kept: the step is committed on **release**, not per tick — measured on
 * the emulator, applying each step under the finger re-measured the whole interface and
 * the drag stopped being delivered (nine `input motionevent MOVE`s advanced one step),
 * so the drag moves a draft and the store is written when the thumb stops.
 *
 * The icon's choice is a system entry point rather than a Compose redraw:
 * `applyLauncherIcon` turns a manifest alias on and the other off, and the system
 * paints what the launcher reads. The recent-tasks card follows because its icon is
 * the running task's own `TaskDescription`, written by `MainActivity` from the same
 * enum; the application icon on the system settings page is the platform's fixed
 * identity and cannot follow a preference.
 */
@Composable
internal fun PersonalizationPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val settings by session.settingsStore.settings.collectAsStateWithLifecycle()
    val haptics = LocalPiHaptics.current

    // Which avatar is being edited, as the sheet's own state: a Miuix sheet is a window
    // of its own and is dismissed by the scrim, the back gesture and a downward drag, so
    // the page only has to hold *which* one is open. Null closes it.
    var editingAvatar by remember { mutableStateOf<AvatarSlot?>(null) }
    // The slot the panel is drawing: replaced by a newer one, never cleared by the tap.
    var shownSlot by remember { mutableStateOf<AvatarSlot?>(null) }

    val pageScroll = rememberScrollState()
    PiPage(
        title = text.settings.personalization,
        subtitle = text.settings.personalizationSubtitle,
        onBack = onBack,
        contentScrolled = pageScroll.canScrollBackward,
        // One sheet for both speakers: the body is told which slot it edits, so the two rows
        // cannot drift into two editors that save differently.
        //
        // The slot and the panel are two values, not one: `show` goes false on the tap while
        // Miuix's exit animation is still playing, and a body and a title read off the same
        // null slot play that last 200 ms empty — the panel shrinks away with no title and no
        // content in it. `shownSlot` is the slot the panel is *drawing*, and it is only ever
        // replaced by a newer one, which is the shape `WebSearchConfig` uses for its own
        // value sheet for the same reason.
        overlay = {
            val slot = editingAvatar
            PiSheet(
                show = slot != null,
                title = when (shownSlot) {
                    AvatarSlot.Ai -> text.settings.aiAvatar
                    AvatarSlot.User -> text.settings.userAvatar
                    null -> null
                },
                onDismiss = { editingAvatar = null },
            ) {
                when (shownSlot) {
                    AvatarSlot.Ai -> AvatarSheet(
                        slotId = AvatarSlot.Ai.id,
                        label = settings.aiAvatarName,
                        imagePath = settings.aiAvatarImage,
                        speaker = AvatarSpeaker.AGENT,
                        color = settings.aiAvatarColor,
                        accent = settings.accentColor,
                        onChangeLabel = { value ->
                            session.settingsStore.update { it.copy(aiAvatarName = value) }
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
                        slotId = AvatarSlot.User.id,
                        label = settings.userAvatarName,
                        imagePath = settings.userAvatarImage,
                        speaker = AvatarSpeaker.READER,
                        color = settings.userAvatarColor,
                        accent = settings.accentColor,
                        onChangeLabel = { value ->
                            session.settingsStore.update { it.copy(userAvatarName = value) }
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
        },
    ) { padding ->
        // The page draws through the navigation strip now, so the strip's height is content
        // padding at the end of the scroll: the last row can still be scrolled clear of the
        // gesture handle, which a bottom padding on the viewport would have prevented.
        Column(Modifier.settingsPageScroll(padding, pageScroll)) {
            PiSection(title = text.settings.personalizationConversation) {
                SwitchPreference(
                    title = text.settings.openNewOnLaunch,
                    summary = text.settings.openNewOnLaunchSubtitle,
                    checked = settings.openNewOnLaunch,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(openNewOnLaunch = on) }
                    },
                )
                // The two timestamp switches are display choices about the two lists
                // the app draws: the history page's rows and the transcript's
                // messages. They share this section because both answer "how much
                // furniture does a conversation row wear", and the defaults are
                // deliberately opposite — the history list is a list of questions,
                // while a message's time is part of the record.
                SwitchPreference(
                    title = text.settings.showHistoryTime,
                    summary = text.settings.showHistoryTimeSubtitle,
                    checked = settings.showHistoryTime,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(showHistoryTime = on) }
                    },
                )
                SwitchPreference(
                    title = text.settings.showMessageTime,
                    summary = text.settings.showMessageTimeSubtitle,
                    checked = settings.showMessageTime,
                    onCheckedChange = { on ->
                        session.settingsStore.update { it.copy(showMessageTime = on) }
                    },
                )
            }

            // One section, four controls: the text size, the theme, the accent and the launcher
            // mark are the whole of "how the app looks", and they used to be separate groups
            // with the theme and the mark as radio lists under headings of their own.
            //
            // The theme and the mark are one row each now, not a radio group. They used to
            // argue for visible radios — three palettes are a *set*, and a set is better read
            // at once than one at a time behind a scrim — and that argument was about the
            // *scrim*, not about the list: Miuix's dropdown opens a panel over the row with no
            // scrim at all, so the row being changed stays in sight, the alternatives are one
            // tap away, and the page is shorter. The accent keeps its swatches because a colour
            // is recognised before it is read; eight rows of colour names would be the longest
            // group on the page for the least information.
            PiSection(title = text.settings.personalizationAppearance) {
                FontSizeRow(
                    current = settings.fontSize,
                    onChange = { chosen ->
                        session.settingsStore.update { it.copy(fontSize = chosen) }
                    },
                    text = text,
                )
                PiDropdownPreference(
                    items = ThemeMode.entries.map { themeModeLabel(it, text) },
                    selectedIndex = settings.themeMode.ordinal,
                    title = text.settings.theme,
                    onSelectedIndexChange = { index ->
                        val mode = ThemeMode.entries[index]
                        if (mode != settings.themeMode) {
                            // The library ticks when the panel opens and says nothing when a
                            // row is picked; `choose` is the documented moment for exactly
                            // this, and the same one a radio row would perform.
                            haptics.choose()
                            session.settingsStore.update { it.copy(themeMode = mode) }
                        }
                    },
                )
                AccentColorRow(
                    current = settings.accentColor,
                    onChange = { chosen ->
                        // The swatch performs `PiHaptics.choose()` itself when the tap
                        // changes the answer; a second buzz here would be the double
                        // charge `PiHaptics` forbids.
                        session.settingsStore.update { it.copy(accentColor = chosen) }
                    },
                    text = text,
                )
                PiDropdownPreference(
                    items = LauncherIcon.entries.map { launcherIconLabel(it, text) },
                    selectedIndex = settings.launcherIcon.ordinal,
                    title = text.settings.launcherIcon,
                    onSelectedIndexChange = { index ->
                        val icon = LauncherIcon.entries[index]
                        if (icon != settings.launcherIcon) {
                            haptics.choose()
                            session.settingsStore.update { it.copy(launcherIcon = icon) }
                        }
                    },
                )
            }

            // The transcript's two speakers, as names, pictures or marks. A switch
            // over the pair rather than one per side: they are one feature — "draw
            // who is talking" — and a reader who wants them off wants both off.
            //
            // Each row's disc is the mark the transcript draws, and nothing else: the
            // name is a word beside that mark on the side its speaker owns, so passing
            // it here would put a monogram in the one place the reader goes to see what
            // the transcript will show. `PiAvatar` no longer has the branch for it.
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
                        PiAvatar(
                            speaker = AvatarSpeaker.AGENT,
                            color = settings.aiAvatarDisc.seed,
                            imagePath = settings.aiAvatarImage.ifBlank { null },
                        )
                    },
                    onClick = {
                        shownSlot = AvatarSlot.Ai
                        editingAvatar = AvatarSlot.Ai
                    },
                )
                ArrowPreference(
                    title = text.settings.userAvatar,
                    endActions = {
                        PiAvatar(
                            speaker = AvatarSpeaker.READER,
                            color = settings.userAvatarDisc.seed,
                            // No picture draws the silhouette — the reader's default mark.
                            imagePath = settings.userAvatarImage.ifBlank { null },
                        )
                    },
                    onClick = {
                        shownSlot = AvatarSlot.User
                        editingAvatar = AvatarSlot.User
                    },
                )
            }
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
        // The step as a percentage of the ladder's own unit, which is the one number a
        // reader can check against nothing at all: `100%` is the size the app ships as
        // its default, and that size is what the ladder used to call 95%
        // (`TEXT_SCALE_BASELINE`).
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
 * The accent, as a named value over the eight swatches.
 *
 * A colour is recognised before it is read, which is why the choice is discs rather
 * than eight rows of colour names; the row above them is what names the answer,
 * because a ring can say *which* one is chosen but not *which colour* it is.
 *
 * The swatches scroll rather than clip as a safety: eight 34dp discs and their seven
 * 4dp gaps are 300dp, which fits the narrowest page this app targets, and a larger
 * font scale or a longer translation cannot push one off the edge.
 */
@Composable
private fun AccentColorRow(
    current: AccentColor,
    onChange: (AccentColor) -> Unit,
    text: Strings,
) {
    BasicComponent(
        title = text.settings.accentColor,
        summary = text.settings.accentColorSubtitle,
        endActions = {
            SettingsValue(
                text.settings.avatarColorName(current.avatar),
                emphasised = true,
            )
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentColor.entries.forEach { entry ->
            SettingsSwatch(
                color = entry.seed,
                selected = entry == current,
                onClick = { onChange(entry) },
                label = text.settings.avatarColorName(entry.avatar),
                size = ACCENT_SWATCH_SIZE,
            )
        }
    }
}

/**
 * The avatar editor: a large preview, the picture, the label field, and the eight
 * colours.
 *
 * Saves as it is typed and on a swatch tap, like the working directory and the text size
 * — none of them restarts anything, and the sheet is dismissed by the scrim, the drag and
 * back, so a second "done" control would be the third of three.
 *
 * ## The colour follows the accent until it is chosen
 *
 * A null [color] is the default and means "draw the accent's disc". The switch above the
 * swatches states that state and is the way back to it; a swatch tap stores one of the
 * eight and the disc stops following, which is the "默认跟随，改过就不变" half of the
 * feature. The preview above uses the same resolved colour, so the disc the sheet shows
 * is the disc the transcript will draw.
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
 * The name is capped at [AVATAR_NAME_MAX] characters because it is drawn beside a 34dp
 * mark in one line: past that it is an ellipsis in the transcript, and a field that accepts
 * more than it can show is a field the reader keeps retyping into.
 */
@Composable
private fun AvatarSheet(
    slotId: String,
    label: String,
    imagePath: String,
    speaker: AvatarSpeaker,
    color: AvatarColor?,
    accent: AccentColor,
    onChangeLabel: (String) -> Unit,
    onChangeImage: (String) -> Unit,
    onChangeColor: (AvatarColor?) -> Unit,
    text: Strings,
) {
    val context = LocalContext.current
    val filesDir = context.filesDir

    // The pick, crop and store flow is shared with the chat preset editor: one picture's
    // worth of platform work, stated once. See `rememberAvatarPicturePicker`.
    val picker = rememberAvatarPicturePicker(
        filesDir = filesDir,
        slotId = slotId,
        text = text,
        onStored = onChangeImage,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .piOverScroll()
            .verticalScroll(rememberScrollState())
            .padding(bottom = piSheetBottomInset()),
    ) {
        // The preview is the thing the sheet edits, so it sits above the cards rather
        // than inside one: a card is a group of rows, and a picture with nothing to tap
        // is not a row. The 96dp disc is the size the transcript draws its avatar at
        // several times over, which is what makes it read as a preview rather than as
        // another control.
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            PiAvatar(
                speaker = speaker,
                color = color?.seed ?: accent.seed,
                imagePath = imagePath.ifBlank { null },
                size = AVATAR_PREVIEW_SIZE,
            )
        }

        PiSheetSection {
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
                onClick = picker.pick,
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
                        AvatarStore.clear(filesDir, slotId)
                        onChangeImage("")
                    },
                )
            }
        }

        PiSheetSection {
            // The name beside the avatar, not the letter inside it: the disc draws the
            // speaker's own figure now, and a name is a word the transcript shows at the
            // start edge (the agent) or the end edge (the reader).
            SettingsTextField(
                value = label,
                onValueChange = { value -> onChangeLabel(value.take(AVATAR_NAME_MAX)) },
                label = text.settings.avatarName,
                supporting = text.settings.avatarNameHint,
                singleLine = true,
            )
        }

        PiSheetSection(title = text.settings.avatarColor) {
            // Null is "follow the accent", and the switch is how that state is chosen
            // again after a swatch. While it is on the swatches stay visible but
            // disabled, the same shape the chat preset editor uses for its own
            // inheritance: the row keeps its height and the choice stays legible as the
            // one that is not in force.
            SwitchPreference(
                title = text.settings.avatarFollowAccent,
                summary = text.settings.avatarFollowAccentHint,
                checked = color == null,
                onCheckedChange = { follow ->
                    onChangeColor(if (follow) null else accent.avatar)
                },
            )
            // Eight swatches on one line, and the line is *measured* rather than scrolled:
            // the sheet's 16dp gutter and this row's 16dp leave 328dp on a 360dp phone,
            // and 8 x 34dp + 7 x 4dp = 300dp fits without a scroll nothing announces.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarColor.entries.forEach { entry ->
                    SettingsSwatch(
                        color = entry.seed,
                        selected = color == entry,
                        onClick = { onChangeColor(entry) },
                        label = text.settings.avatarColorName(entry),
                        size = SWATCH_SIZE,
                        enabled = color != null,
                    )
                }
            }
        }

        if (picker.failed) {
            SettingsNote(text.settings.avatarPictureFailed, danger = true)
        }
    }
}

/** The swatch's diameter: eight of them and the seven 4dp gaps between them are 300dp. */
private val SWATCH_SIZE = 34.dp

/**
 * The accent row's swatches are the same discs.
 *
 * The row scrolls, so this is a fit figure rather than a measurement: at 34dp the
 * eight and their gaps are 300dp, the width the avatar sheet already proves fits a
 * 360dp phone.
 */
private val ACCENT_SWATCH_SIZE = 34.dp

/** The most characters an avatar *name* holds before it is cut. */
private const val AVATAR_NAME_MAX = 16

/** The large avatar at the top of its editor. */
private val AVATAR_PREVIEW_SIZE = 96.dp

/**
 * A theme's name in the interface language.
 *
 * The label is the whole of the row's trailing slot in the dropdown, which is why it has to
 * be short: the Japanese `システムに合わせる` is nine characters and fits the value slot at a
 * phone's width, where a segmented control of three such labels would have ellipsised it.
 * The enum's own order is the order they are offered in.
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
