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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow
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
 * The text-size row: a title, a percentage, and a seven-position slider that
 * opens on the row.
 *
 * ## Why the slider opens on tap
 *
 * Every other choice on this page is a `PickerRow` — tap the row, pick from a
 * sheet. The slider used to sit open under the title always, which made the
 * page's appearance section one control taller than the rest before anything
 * was touched. A size is still a *range*, not a list of names, so a sheet of
 * seven numbers would be the wrong shape; the answer is the picker's gesture
 * with the range's control: tap the row to reveal the slider, tap again to
 * hide it. The page it changes stays visible either way — that part of the
 * original reasoning still holds.
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
 * a local draft — the thumb, the ticks and the percentage, all of which are this row's
 * own — and the step is committed on release, which is one re-measure per gesture
 * instead of one per pixel.
 *
 * ## The labels
 *
 * The two ends are the same glyph at the smallest and the largest style rather than
 * the words "small" and "large": it is a sample of what the slider does, it needs no
 * translation, and it grows with the choice — the ends are `sp` like everything else,
 * so they redraw at the new scale as soon as the step is committed. The value column
 * shows the step as a percentage of the app's own sizes, which is the one number a
 * reader can check against nothing at all: `100%` is what a fresh install has.
 *
 * The row is built here rather than as a `SettingsRow` with a control under it,
 * because the slider is not the row's trailing control — it is a second line under
 * the row's own, spanning the width the title does not. The icon, the 14dp gap and
 * the 16dp gutters match `SettingsRow` so the row sits in the card with its
 * neighbours; see that function for what each of the three is for.
 */
@Composable
private fun FontSizeRow(
    current: FontSize,
    onChange: (FontSize) -> Unit,
    text: Strings,
) {
    val steps = FontSize.entries
    // The step under the finger. Keyed on the committed one, so a change made
    // anywhere else — a restore, a rebuild — lands in the slider, and so that the
    // draft after a release is the value that was released.
    var draft by remember(current) { mutableFloatStateOf(current.ordinal.toFloat()) }
    val shown = steps[draft.roundToInt().coerceIn(0, steps.lastIndex)]

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.FormatSize,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = text.settings.fontSize,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = text.settings.fontSizeSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // The draft's percentage, not the committed one: the number is the
                // only part of the app that may follow the finger while the rest of
                // it waits for the release.
                text = "${shown.percent}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 6dp above the slider and 12dp below the title: the control
                // belongs to the row above it, not to the next card.
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A fix-sized column for each end, so the two samples read as the ends of
            // one track and the track keeps the same width as the thumb moves.
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
                    if (chosen != current) onChange(chosen)
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
