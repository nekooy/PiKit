package pi.kit.mob.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import pi.kit.mob.data.ThemeMode
import pi.kit.mob.pi.PiAgentService
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.theme.PiKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val session = PiAgentSession.of(applicationContext)

        setContent {
            // The theme is read here rather than inside `PiKitTheme`'s default:
            // `isSystemInDarkTheme()` is only the answer for `ThemeMode.SYSTEM`,
            // and the preference lives with the rest of the app's settings.
            val settings by session.settingsStore.settings.collectAsState()
            val darkTheme = when (settings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            PiKitTheme(darkTheme = darkTheme) {
                // The foreground service is what keeps a long turn alive while the
                // user is in another app, so it starts as soon as there is a frame
                // to start it from.
                //
                // The permission prompts are deliberately **not** asked for here.
                // They used to be, from this same first composition, and the
                // notification request was cancelled by the system 251 ms after it
                // opened — the splash was still exiting, the window was being
                // replaced and this service was starting. `PiKitRoot` now owns both
                // prompts and raises them once the runtime is ready; the block there
                // has the measurement.
                LaunchedEffect(Unit) {
                    PiAgentService.start(this@MainActivity)
                }

                PiKitRoot(session = session)
            }
        }
    }
}
