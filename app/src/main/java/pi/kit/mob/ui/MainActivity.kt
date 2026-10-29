package pi.kit.mob.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import pi.kit.mob.data.TEXT_SCALE_BASELINE
import pi.kit.mob.data.applyLauncherIcon
import pi.kit.mob.pi.PiAgentService
import pi.kit.mob.pi.PiAgentSession
import pi.kit.mob.ui.kit.PiKitMiuixTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val session = PiAgentSession.of(applicationContext)

        setContent {
            // The theme is assembled from the preferences here rather than inside
            // the composable's default: the light/dark choice and the text size
            // both live in the settings store, and a theme that read the store
            // itself would be a second owner of those decisions.
            val settings by session.settingsStore.settings.collectAsState()

            // The launcher icon is the system's to draw, so choosing one is a
            // component switch rather than a redraw. Keyed on the preference,
            // which makes one call do both jobs: it applies the choice the moment
            // the picker is tapped, and it re-states it on every launch, where it
            // writes nothing unless the installed aliases have drifted from it.
            //
            // Here rather than in `PiKitRoot`, whose body is not composed until
            // the runtime is ready: this is two binder calls and has no business
            // waiting for the image to unpack.
            LaunchedEffect(settings.launcherIcon) {
                applyLauncherIcon(this@MainActivity, settings.launcherIcon)
            }

            // The theme owns the palette, the type scale and the system bars. A
            // light/dark change re-themes the page that chose it because the
            // settings flow is read above every screen; the palette itself is
            // Miuix's own and is not a preference (`PiKitMiuixTheme`).
            PiKitMiuixTheme(settings = settings) {
                // The text size, applied as one multiplier on the composition's own
                // density rather than by overriding each type style at each of the
                // seven steps.
                //
                // Read here, above every screen and above the modal windows, so it
                // reaches the transcript, the terminal banner and the dialogs alike —
                // Compose's `Dialog` runs in the parent composition and inherits its
                // locals, so a dialog opened from a page drawn at this scale is scaled
                // with it.
                //
                // It multiplies the *platform's* fontScale rather than replacing it: a
                // user who has already set 1.3 in Android's display settings keeps their
                // preference and gets this step on top of it. `density` itself is passed
                // through untouched — it is dp, and the row above this one measured its
                // paddings in it.
                //
                // The step is multiplied by the ladder's own base before it is applied:
                // the row's `100%` is the size the ladder used to call 95%, which is
                // `TEXT_SCALE_BASELINE`'s whole content, and it is stated in the data
                // layer because it is a property of the ladder rather than of this
                // composition.
                ScaledType(scale = settings.fontSize.scale * TEXT_SCALE_BASELINE) {
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

    override fun onStart() {
        super.onStart()
        // A window is in front: the shade's "running" marker is noise the reader
        // did not ask for. See `PiAgentService.onUiVisible`.
        PiAgentService.onUiVisible(this, true)
    }

    override fun onStop() {
        PiAgentService.onUiVisible(this, false)
        super.onStop()
    }
}

/**
 * The interface's text size, as one `LocalDensity` override.
 *
 * A composable of its own rather than three lines inline because the density is read
 * and re-provided in the same place: `LocalDensity.current` is the platform's, so the
 * new value has to be built from it rather than from a constant — replacing it would
 * drop the system's own font scale and the device's density with it.
 *
 * This is the only place `fontScale` is written. Everything the user reads is drawn
 * with an `sp` somewhere, and one local is what makes "the text size" mean all of it
 * at once — including the chat transcript, which is the page the setting exists for.
 */
@Composable
private fun ScaledType(scale: Float, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = base.density,
            fontScale = base.fontScale * scale,
        ),
        content = content,
    )
}
