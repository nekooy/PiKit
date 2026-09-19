package pi.kit.mob.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import pi.kit.mob.pi.PiAgentService
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.theme.PiKitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val session = PiAgentSession.of(applicationContext)

        setContent {
            PiKitTheme {
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
