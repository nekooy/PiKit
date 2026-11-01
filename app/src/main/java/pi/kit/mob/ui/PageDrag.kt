package pi.kit.mob.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalViewConfiguration

/** How much of the width a flick has to cover before it counts as one. */
internal const val FLING_MIN_TRAVEL = 0.05f

/**
 * A horizontal drag with the platform's own settle rule.
 *
 * `detectHorizontalDragGestures` reports deltas but no velocity, and the version of this
 * gesture that read the *last* delta as "how fast" was the one that made a quick thumb
 * feel stuck: a last frame is a sample, not a speed. A [VelocityTracker] is the component
 * the platform's own scroll and paging gestures use, and it is fed the pointer's real
 * positions here.
 *
 * ## Why the average is the fallback
 *
 * A `VelocityTracker` is a least-squares fit over the samples it was given, and a gesture
 * that arrives as three events — which is what a synthetic swipe and a brisk thumb both
 * can produce — leaves it with nothing to fit: measured on the emulator, a 329px drag in
 * 500ms reported `samples=3 v=0.0`, and a fling that reports zero is a fling that never
 * happens. When the fit comes back empty the *average* velocity (`distance ÷ elapsed`) is
 * used instead: the same quantity in the same unit, exact for a constant-speed gesture,
 * and never zero for a gesture that moved.
 *
 * [onRelease] receives the distance travelled, the velocity at release, and the minimum
 * fling velocity from this layout's own `ViewConfiguration` — so the caller decides with
 * the numbers Android uses, instead of a fraction guessed from a screenshot.
 */
internal fun Modifier.pageDrag(
    width: Int,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: (distance: Float, velocity: Float, minFling: Float) -> Unit,
): Modifier = composed {
    val tracker = VelocityTracker()
    var travelled = 0f
    var startedAt = 0L
    val minFling = LocalViewConfiguration.current.minimumFlingVelocity
    pointerInput(width, minFling) {
        if (width <= 0) return@pointerInput

        fun release() {
            val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(1)
            val fitted = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
            val velocity = if (fitted != 0f) fitted else travelled / (elapsed / 1000f)
            onRelease(travelled, velocity, minFling)
        }

        detectHorizontalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                travelled = 0f
                startedAt = SystemClock.uptimeMillis()
                onStart()
            },
            onHorizontalDrag = { change: PointerInputChange, delta: Float ->
                change.consume()
                tracker.addPointerInputChange(change)
                travelled += delta
                onDrag(delta)
            },
            onDragEnd = { release() },
            onDragCancel = { release() },
        )
    }
}
