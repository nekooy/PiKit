package pi.kit.mob.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.locales.Strings
import pi.kit.mob.locales.strings
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.components.PickerOption
import pi.kit.mob.ui.components.PickerRow

/**
 * Cold-start conversation behaviour and the interface theme.
 *
 * Two settings that are both "how the app feels when I open it" and neither of
 * which is about the agent: which conversation a launch lands in, and which
 * palette that landing is drawn in. They share a page because a page each would
 * be two rows deep — the same mistake the old "Advanced" group made.
 *
 * The switch is **on** when a cold start opens a *new* conversation. That is the
 * default because a launch is usually a new question, and the previous
 * conversation is one tap away in the history; turning it off is the choice that
 * used to be the only behaviour. Agent restarts after the first launch still
 * restore — see `shouldRestoreRememberedSession`.
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
