package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pi.kit.mob.data.FontSize
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.LocalSheetHost
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow
import pi.kit.mob.ui.components.Sheet
import kotlin.math.roundToInt

/**
 * Cold-start conversation behaviour, the interface theme, the text size and the
 * launcher icon.
 *
 * Four settings that are all "how the app feels when I open it" and none of which
 * is about the agent: which conversation a launch lands in, how large its text is,
 * which palette that text is drawn in, and which of the two marks the home screen
 * shows. They share a page because a page each would be four rows deep — the same
 * mistake the old "Advanced" group made.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
 *
 * The icon picker is the one row here whose choice the app does not draw:
 * `applyLauncherIcon` turns a manifest alias on and the other off, and the system
 * paints what the launcher reads. Its footnote is about the home screen rather
 * than about the icon, because that is the part a user notices.
 */
@Composable
internal fun PersonalizationPage(
    session: PiAgentSession,
    onBack: () -> Unit,
) {
    val text = strings
    val settings by session.settingsStore.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        SettingsPageHeader(
            title = text.settings.personalization,
            subtitle = text.settings.personalizationSubtitle,
            onBack = onBack,
        )

        SettingsBody {
            SettingsSection(text.settings.personalizationConversation) {
                SettingsSwitchRow(
                    title = text.settings.openNewOnLaunch,
                    subtitle = text.settings.openNewOnLaunchSubtitle,
                    icon = Icons.AutoMirrored.Filled.Chat,
                    checked = settings.openNewOnLaunch,
                    onChange = { on ->
                        session.settingsStore.update { it.copy(openNewOnLaunch = on) }
                    },
                )
            }

            SettingsSection(text.settings.personalizationAppearance) {
                FontSizeRow(
                    current = settings.fontSize,
                    onChange = { chosen ->
                        session.settingsStore.update { it.copy(fontSize = chosen) }
                    },
                    text = text,
                )
                SettingsDivider()
                PickerRow(
                    title = text.settings.theme,
                    subtitle = text.settings.themeSubtitle,
                    value = themeModeLabel(settings.themeMode, text),
                    icon = Icons.Filled.Brightness6,
                    options = themeModeOptions(text),
                    selectedId = settings.themeMode.code,
                    onPick = { code ->
                        session.settingsStore.update {
                            it.copy(themeMode = ThemeMode.fromCode(code))
                        }
                    },
                )
                SettingsDivider()
                PickerRow(
                    title = text.settings.launcherIcon,
                    subtitle = text.settings.launcherIconSubtitle,
                    value = launcherIconLabel(settings.launcherIcon, text),
                    icon = Icons.Filled.Smartphone,
                    options = launcherIconOptions(text),
                    selectedId = settings.launcherIcon.code,
                    footnote = text.settings.launcherIconFootnote,
                    onPick = { code ->
                        session.settingsStore.update {
                            it.copy(launcherIcon = LauncherIcon.fromCode(code))
                        }
                    },
                )
            }

            SettingsNote(text.settings.personalizationNote)
        }
    }
}

/**
 * The text-size row: tap to open a sheet with the seven-position slider.
 *
 * ## Why a sheet, like every other choice on the page
 *
 * Theme and launcher icon are both `PickerRow` — tap the row, pick from a
 * bottom sheet. The slider used to sit open under the title always (and then,
 * briefly, expand on the row), which made this control a different shape from
 * its neighbours before anything was touched. A size is still a *range*, not a
 * list of names, so the sheet carries a slider rather than seven numbered rows;
 * the *gesture* is the picker's. The row itself is a `SettingsRow` so the three
 * appearance rows stay one visual family.
 *
 * ## The drag is not applied until it is released, and that is a measurement
 *
 * This is the one slider in the app, and the first version applied each step as the
 * finger passed it — which does not work, because applying it re-measures the entire
 * interface. Measured on the emulator, dragging across the whole track with nine
 * `input motionevent MOVE`s advanced **one** step (80% → 90%): the first change
 * re-laid the page out under the pointer and the gesture stopped being delivered. A
 * single MOVE of the same total distance moved four steps (90% → 130%), which is what
 * shows the mapping is fine and the interruption is the live update. So the drag moves
 * a local draft — the thumb, the ticks and the percentage, all of which are the
 * sheet's own — and the step is committed on release, which is one re-measure per
 * gesture instead of one per pixel. The sheet stays open so the step can be
 * adjusted again; the scrim and a downward swipe dismiss it.
 *
 * ## The labels
 *
 * The two ends are the same glyph at the smallest and the largest style rather than
 * the words "small" and "large": it is a sample of what the slider does, it needs no
 * translation, and it grows with the choice — the ends are `sp` like everything else,
 * so they redraw at the new scale as soon as the step is committed. The value column
 * and the sheet's percentage show the step as a percentage of the app's own sizes,
 * which is the one number a reader can check against nothing at all: `100%` is what
 * a fresh install has.
 */
@Composable
private fun FontSizeRow(
    current: FontSize,
    onChange: (FontSize) -> Unit,
    text: Strings,
) {
    val sheets = LocalSheetHost.current
    SettingsRow(
        title = text.settings.fontSize,
        subtitle = text.settings.fontSizeSubtitle,
        icon = Icons.Filled.FormatSize,
        value = "${current.percent}%",
        showChevron = true,
        onClick = {
            sheets.show(
                Sheet(key = "font-size") {
                    FontSizeSheet(current = current, onChange = onChange, text = text)
                },
            )
        },
    )
}

/**
 * The font-size sheet: a title, the seven-step slider, and the percentage.
 *
 * Same title voice as every other sheet (`titleMedium`, SemiBold). The body is
 * not a [pi.kit.mob.ui.components.PickerBody]: there is no list of rows to
 * choose from, and re-expressing a range as seven tappable numbers would throw
 * away the one thing a slider is for. The note under the track is the catalog's
 * own subtitle, because the sheet is where the "let go to apply" rule is read.
 */
@Composable
private fun FontSizeSheet(
    current: FontSize,
    onChange: (FontSize) -> Unit,
    text: Strings,
) {
    val steps = FontSize.entries
    // The step under the finger. Keyed on the committed one, so a change made
    // anywhere else — a restore, a rebuild — lands in the slider, and so that the
    // draft after a release is the value that was released.
    var draft by remember(current) { mutableFloatStateOf(current.ordinal.toFloat()) }
    // The last step this sheet wrote. The sheet body is built once when it opens
    // and `current` is that moment's snapshot, so comparing a later release
    // against it would refuse to write the step the user just left. This is the
    // sheet's own answer to "did I already send this".
    var committed by remember(current) { mutableStateOf(current) }
    val shown = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]

    // 48dp under the last line: this sheet is short — title, percentage, the
    // track, one note — so the slider's block sits against the panel's bottom
    // edge and reads as cut off. 16 and then 32 both left it cramped; the extra
    // air is what a control wants under it, not list padding.
    Column(Modifier.fillMaxWidth().padding(bottom = 48.dp)) {
        Text(
            text = text.settings.fontSize,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 4.dp),
        )
        Text(
            text = "${shown.percent}%",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A fixed-width column for each end, so the two samples read as the ends
            // of one track and the track keeps the same width as the thumb moves.
            Text(
                text = "A",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Slider(
                value = draft,
                onValueChange = { draft = it },
                // Snapped here as well as by the slider: `steps` fixes the *positions*
                // the thumb may take, and the ordinal is what the enum is indexed by —
                // a rounded float is the same statement twice rather than a second
                // source of truth, and a value one step off would be a preference the
                // user never chose.
                onValueChangeFinished = {
                    val chosen = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]
                    if (chosen != committed) {
                        committed = chosen
                        onChange(chosen)
                    }
                },
                valueRange = 0f..steps.lastIndex.toFloat(),
                // Material3 counts the ticks *between* the ends, so seven positions is
                // five of them.
                steps = steps.size - 2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "A",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = text.settings.fontSizeSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }
}

/** The theme picker's three rows, built from the catalog so they follow the language. */
private fun themeModeOptions(text: Strings): List<PickerOption> = listOf(
    PickerOption(id = ThemeMode.SYSTEM.code, label = text.settings.themeSystem),
    PickerOption(id = ThemeMode.LIGHT.code, label = text.settings.themeLight),
    PickerOption(id = ThemeMode.DARK.code, label = text.settings.themeDark),
)

/** The row's value column: the current theme, named in the interface language. */
private fun themeModeLabel(mode: ThemeMode, text: Strings): String = when (mode) {
    ThemeMode.SYSTEM -> text.settings.themeSystem
    ThemeMode.LIGHT -> text.settings.themeLight
    ThemeMode.DARK -> text.settings.themeDark
}

/** The icon picker's two rows, black first because that is the one that ships. */
private fun launcherIconOptions(text: Strings): List<PickerOption> = listOf(
    PickerOption(id = LauncherIcon.DARK.code, label = text.settings.launcherIconBlack),
    PickerOption(id = LauncherIcon.LIGHT.code, label = text.settings.launcherIconWhite),
)

/** The row's value column: the mark the home screen is showing. */
private fun launcherIconLabel(icon: LauncherIcon, text: Strings): String = when (icon) {
    LauncherIcon.DARK -> text.settings.launcherIconBlack
    LauncherIcon.LIGHT -> text.settings.launcherIconWhite
}
