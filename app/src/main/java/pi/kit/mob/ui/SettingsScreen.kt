package pi.kit.mob.ui

import androidx.compose.runtime.Composable
import pi.kit.mob.pi.PiAgentSession

/**
 * The Settings page.
 *
 * The screen is large enough to live in its own package, one file per sub-page;
 * this is the entry point [PiKitRoot] calls. Navigation between the settings
 * pages is local to [pi.kit.mob.ui.settings.SettingsScreen] so that nothing
 * outside has to know the pages exist.
 *
 * [onBack] leaves for the conversation, with the drawer still out: settings is
 * reached from the drawer's foot, and the page that opened it is the page a back
 * gesture should return to.
 */
@Composable
fun SettingsScreen(session: PiAgentSession, onBack: () -> Unit) {
    pi.kit.mob.ui.settings.SettingsScreen(session, onBack)
}
