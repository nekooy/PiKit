/*
 * The app's one transient message.
 *
 * ## Why this exists at all
 *
 * Everything PiKit used to say about a tap was said *in the page*: a strip under
 * the control that was pressed (`PiNotice`), or a line of prose under it
 * (`PiNote`), reporting "saved", "restored", "removed". Each of those is true for
 * about two seconds and then becomes clutter that stays on screen until the page
 * is left or the next action happens — a settings page that has been used twice has
 * three notes on it, each one about something that is already done. A sentence
 * about an action that has *finished* is a toast: it appears, it is read, it goes.
 *
 * What stays an in-page notice is the other half of the vocabulary, and the split is
 * by tense rather than by importance:
 *
 *  - **A toast is about a moment** — "saved", "restored", "copied". Nothing can be
 *    done about it and nothing is still true afterwards except the state itself,
 *    which the page under it already shows.
 *  - **A notice is about a condition** — the agent failed to start, the extension is
 *    not bundled, a file cannot be read. It is still true while the reader is
 *    looking at it, it usually names what to do about it, and a message that
 *    disappears after four seconds is exactly the wrong shape for it.
 *
 * ## The layer, not a `Snackbar`
 *
 * Same reasoning as `Sheets.kt`: this app's modal layer is a view in the page rather
 * than a second window, and a toast is the least modal thing there is — it takes no
 * input, it has no dismissal, and it must not move the page under it. It is drawn
 * into the root's own `Box`, above the page and below nothing, and the whole of its
 * behaviour is one timer.
 *
 * A toast never carries an action. A message with a button on it is a question, and a
 * question belongs in a dialog or in a notice; making this one able to hold a button
 * is how it would grow into a third kind of modal and start competing with both.
 */
package pi.kit.mob.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pi.kit.mob.ui.design.PiMotion
import pi.kit.mob.ui.design.PiShapes

/** One message, with the identity that restarts its timer when a second one arrives. */
@Immutable
internal class Toast(val id: Int, val message: String)

/**
 * Holds the one message the app is allowed to be showing.
 *
 * A single slot, for the same reason the sheet host is: two transient messages at
 * once is a queue, and a queue of toasts is a log nobody reads. A second [show]
 * replaces the first and restarts the clock, which is what makes five quick saves
 * read as one statement rather than as five overlapping ones.
 */
@Stable
class ToastHost {

    private var next by mutableIntStateOf(0)

    internal var current by mutableStateOf<Toast?>(null)
        private set

    /**
     * Shows [message] for the toast's own duration.
     *
     * The nullable form is the only one, and it is not a convenience: every caller is
     * reporting the outcome of a `Result`'s `fold`, and the outcome of a fold is a
     * sentence or nothing. A blank message is ignored, which is what a caller that
     * folded to an empty string meant anyway.
     */
    fun show(message: String?) {
        if (message.isNullOrBlank()) return
        current = Toast(id = next++, message = message)
    }

    internal fun clear() {
        current = null
    }
}

/**
 * The host, from anywhere inside the app.
 *
 * `staticCompositionLocalOf` for the reason `LocalSheetHost` is one: the value is a
 * single object that never changes, and the recomposition a toast needs comes from
 * the snapshot state inside the host.
 */
val LocalToastHost = staticCompositionLocalOf<ToastHost> {
    error("No ToastHost: toasts are drawn by the root, which provides one")
}

/**
 * The layer itself: one message near the bottom of the page that fades in, waits and
 * fades out. Draws nothing while there is none.
 *
 * [modifier] is the caller's, and the root gives it the page's own bottom inset — the
 * navigation bar, the keyboard or the gesture strip, whichever is largest — so the
 * message lands where the composer's band ends rather than under the bar.
 */
@Composable
fun ToastLayer(host: ToastHost, modifier: Modifier = Modifier) {
    val shown = host.current

    // One timer per message: keyed on the message's identity, so a replacement
    // cancels the old one and starts again rather than the second message inheriting
    // whatever was left of the first one's clock.
    LaunchedEffect(shown?.id) {
        if (shown != null) {
            delay(TOAST_MILLIS)
            host.clear()
        }
    }

    // Held so the layer has something to draw for the length of the exit: the state
    // that asked for it is already gone by then.
    var last by remember { mutableStateOf<Toast?>(null) }
    if (shown != null) last = shown

    val enter: FiniteAnimationSpec<Float> = PiMotion.defaultEffects()
    val exit: FiniteAnimationSpec<Float> = PiMotion.fastEffects()
    // The slide's own spec is over `IntOffset` — a vertical slide moves the panel by a
    // pixel count — so it is asked for at that type rather than the float one above.
    val travel: FiniteAnimationSpec<IntOffset> = PiMotion.defaultEffects()

    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(enter) + slideInVertically(travel) { height -> height / 2 },
        exit = fadeOut(exit) + slideOutVertically(travel) { height -> height / 2 },
        modifier = modifier,
    ) {
        last?.let { toast ->
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                // `largeIncreased` rather than `PiShapes.pill`, which the brief for a
                // *toast* suggests: a pill is the app's **action** shape, and a
                // sentence with no control on it is not an action. At 24dp a one-line
                // message and a three-line one are the same object, where `pill`'s
                // half-the-shorter-side radius turns the second into a lozenge.
                shape = PiShapes.largeIncreased,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    // A cap and nothing else: the message is **sized from its own
                    // sentence**. `fillMaxWidth` anywhere in here would set the
                    // *minimum* width to the maximum and draw "Saved" as a 420dp bar —
                    // the trap AGENTS.md names for the composer and the transcript's
                    // cards, and this is the third place it applies. It was measured on
                    // the emulator: `Agent 已重启` came out 1102px wide on a 1080px
                    // screen, which reads as a panel rather than as a remark.
                    .widthIn(max = TOAST_MAX_WIDTH),
            ) {
                Text(
                    text = toast.message,
                    style = MaterialTheme.typography.bodyMedium,
                    // Centred only matters once the sentence has wrapped, which is
                    // exactly when it is at the cap.
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * 3.5 seconds: long enough to read a short sentence twice, short enough that it is
 * gone before a reader who has moved on has to wait for it.
 */
private const val TOAST_MILLIS = 3_500L

/** A sentence wraps rather than running to the window's edges; this is where it stops. */
private val TOAST_MAX_WIDTH = 420.dp
