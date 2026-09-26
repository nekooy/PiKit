package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import pi.kit.mob.data.LauncherIcon
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow

/**
 * Cold-start conversation behaviour, the interface theme, and the launcher icon.
 *
 * Three settings that are all "how the app feels when I open it" and none of which
 * is about the agent: which conversation a launch lands in, which palette that
 * landing is drawn in, and which of the two marks the home screen shows. They share
 * a page because a page each would be three rows deep — the same mistake the old
 * "Advanced" group made.
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
