package pi.kit.mob.ui

import androidx.compose.runtime.Composable
import pi.kit.mob.pi.PiAgentSession

/**
 * The Settings tab.
 *
 * The screen is large enough to live in its own package, one file per sub-page;
 * this is the entry point [PiKitRoot] calls. Navigation between the settings
 * pages is local to [pi.kit.mob.ui.settings.SettingsScreen] so that nothing
 * outside has to know the pages exist.
 */
@Composable
fun SettingsScreen(session: PiAgentSession) {
    pi.kit.mob.ui.settings.SettingsScreen(session)
}
