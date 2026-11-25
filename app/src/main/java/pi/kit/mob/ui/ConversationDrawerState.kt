package pi.kit.mob.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pi.kit.mob.ui.kit.PiMotion
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Root-owned so switching conversations cannot cancel the drawer's close (§9.1).
 * The rendered distance is a fraction: resize reprojects a live drag or spring
 * onto the new width, rather than snapping to its last committed destination.
 */
@Stable
internal class ConversationDrawerState {
    var width by mutableIntStateOf(0)
    var progress by mutableFloatStateOf(0f)
        private set
    var open by mutableStateOf(false)
        private set
    var reachable by mutableStateOf(false)
        private set

    val offset: Float get() = progress * width
    val visible: Boolean get() = open || progress > 0f

    private var motion: Long = 0L
    private var activeDrag: Long? = null
    private var job: Job? = null

    fun allowedDirection(): PageDragDirection? = when {
        width <= 0 -> null
        progress <= 0f -> PageDragDirection.Right
        progress >= 1f -> PageDragDirection.Left
        else -> PageDragDirection.Both
    }

    fun beginDrag(): Long {
        interrupt()
        reachable = true
        activeDrag = motion
        return motion
    }

    fun dragBy(owner: Long, dx: Float) {
        if (activeDrag == owner && width > 0) {
            progress = (progress + dx / width).coerceIn(0f, 1f)
        }
    }

    fun releaseDrag(owner: Long, distance: Float, velocity: Float, minFling: Float, scope: CoroutineScope) {
        if (activeDrag != owner) return
        settleTo(drawerReleaseOpens(progress, width, distance, velocity, minFling), velocity, scope)
    }

    /** Cancellation is not a release: return to the last committed answer without a fling. */
    fun cancelDrag(owner: Long, scope: CoroutineScope) {
        if (activeDrag == owner) settleTo(open, 0f, scope)
    }

    fun snapTo(open: Boolean) {
        interrupt()
        if (open) reachable = true
        progress = if (open) 1f else 0f
        this.open = open
    }

    fun settleTo(open: Boolean, velocity: Float, scope: CoroutineScope) {
        interrupt()
        if (open) reachable = true
        this.open = open
        val from = progress
        val target = if (open) 1f else 0f
        val pixels = width
        if (abs(target - from) * pixels < 0.5f) {
            progress = target
            return
        }
        val base = PiMotion.drawerSettle
        // `SpringSpec` stops a Float spring at 1% of its range by default. On a drawer
        // that is a whole screen wide, that is an 8–10px jump written in one frame after
        // the spring has already slowed to a crawl — the "顿一下" at the end of every
        // open and close. Half a pixel is below what the eye can follow, so the spring
        // is allowed to finish its own travel instead of being snapped to the target.
        val spec = spring<Float>(
            dampingRatio = base.dampingRatio,
            stiffness = base.stiffness,
            visibilityThreshold = 0.5f / pixels.coerceAtLeast(1),
        )
        val launchVelocity = drawerSettleVelocity(from * pixels, target * pixels, velocity) / pixels
        // A critically damped spring can still overshoot with a large initial velocity.
        // At this limit it approaches the same boundary monotonically.
        val speedLimit = abs(target - from) * sqrt(spec.stiffness)
        val animation = motion
        job = scope.launch {
            animate(
                initialValue = from,
                targetValue = target,
                initialVelocity = launchVelocity.coerceIn(-speedLimit, speedLimit),
                animationSpec = spec,
            ) { value, _ ->
                if (motion == animation) progress = value.coerceIn(0f, 1f)
            }
            if (motion == animation) progress = target
        }
    }

    private fun interrupt() {
        motion++
        activeDrag = null
        job?.cancel()
        job = null
    }
}

internal fun drawerReleaseOpens(
    progress: Float,
    width: Int,
    distance: Float,
    velocity: Float,
    minFling: Float,
): Boolean = if (width > 0 && abs(distance) >= width * FLING_MIN_TRAVEL && abs(velocity) > minFling) {
    velocity > 0f
} else {
    progress >= 0.5f
}

internal fun drawerSettleVelocity(fromPx: Float, targetPx: Float, velocity: Float): Float {
    val delta = targetPx - fromPx
    return if (abs(delta) < 0.5f || delta * velocity <= 0f) 0f else velocity
}
