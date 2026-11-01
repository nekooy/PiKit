package pi.kit.mob.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.abs

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
 * [onRelease] receives the distance travelled, the velocity at release, and the minimum
 * fling velocity from this layout's own `ViewConfiguration` — so the caller can decide
 * with the same numbers Android uses, instead of a fraction guessed from a screenshot.
 */
internal fun Modifier.pageDrag(
    width: Int,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: (distance: Float, velocity: Float, minFling: Float) -> Unit,
): Modifier = composed {
    val tracker = VelocityTracker()
    var travelled = 0f
    val minFling = androidx.compose.ui.platform.LocalViewConfiguration.current.minimumFlingVelocity
    pointerInput(width, minFling) {
        if (width <= 0) return@pointerInput
        detectHorizontalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                travelled = 0f
                onStart()
            },
            onHorizontalDrag = { change: PointerInputChange, delta: Float ->
                change.consume()
                tracker.addPointerInputChange(change)
                travelled += delta
                onDrag(delta)
            },
            onDragEnd = {
                val velocity = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
                onRelease(travelled, velocity, minFling)
            },
            onDragCancel = {
                val velocity = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
                onRelease(travelled, velocity, minFling)
            },
        )
    }
}
