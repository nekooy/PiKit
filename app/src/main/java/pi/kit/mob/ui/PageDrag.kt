package pi.kit.mob.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.abs

/** How much of the width a flick has to cover before it counts as one. */
internal const val FLING_MIN_TRAVEL = 0.05f

internal enum class PageDragDirection {
    Right,
    Left,
    Both,
}

/** The page must win against the perpendicular axis as well as the platform's slop (§9.1). */
private const val HORIZONTAL_DOMINANCE = 1.2f

internal fun claimsPageDrag(
    dx: Float,
    dy: Float,
    touchSlop: Float,
    direction: PageDragDirection = PageDragDirection.Right,
): Boolean {
    val travel = when (direction) {
        PageDragDirection.Right -> dx
        PageDragDirection.Left -> -dx
        PageDragDirection.Both -> abs(dx)
    }
    return travel > touchSlop && travel > abs(dy) * HORIZONTAL_DOMINANCE
}

/**
 * Find a fling on a sparse stream as well as on one the platform tracker can fit. Don't
 * turn an intentional pause at the end of that stream into a fling (ARCHITECTURE §9.1).
 */
internal fun pageDragVelocity(
    fitted: Float,
    distance: Float,
    elapsedMillis: Long,
    sinceLastMoveMillis: Long,
): Float = when {
    sinceLastMoveMillis > 100L -> 0f
    fitted.isFinite() && fitted != 0f -> fitted
    elapsedMillis > 0L -> 1000f * distance / elapsedMillis
    else -> 0f
}

/** One gesture's shared claim; nested scroll and pointer input must never both apply a delta. */
private class PageDragSession {
    var down = false
    var owner: Long? = null
    var rawTravel = Offset.Zero
    var candidate = 0f
    var distance = 0f
    var offeredByChild = false

    fun offer(dx: Float, touchSlop: Float, direction: PageDragDirection): Boolean {
        candidate = when (direction) {
            PageDragDirection.Right -> (candidate + dx).coerceAtLeast(0f)
            PageDragDirection.Left -> (candidate + dx).coerceAtMost(0f)
            PageDragDirection.Both -> candidate + dx
        }
        return abs(rawTravel.x) > abs(rawTravel.y) * HORIZONTAL_DOMINANCE &&
            claimsPageDrag(candidate, rawTravel.y, touchSlop, direction)
    }

    fun reset(active: Boolean) {
        down = active
        owner = null
        rawTravel = Offset.Zero
        candidate = 0f
        distance = 0f
        offeredByChild = false
    }
}

/**
 * Install once on the untransformed stage. Main gives child pointers first refusal and
 * nested scroll offers only the horizontal delta the child couldn't use. After either
 * path claims, Initial owns the rest of the same stream: reversing or reaching an edge
 * doesn't hand the gesture back to a child or depend on a scrim still being composed.
 * The owner token lets an explicit close or a newer drag supersede a stale cleanup.
 */
internal fun Modifier.pageDrag(
    direction: () -> PageDragDirection?,
    onStart: () -> Long,
    onDrag: (owner: Long, dx: Float) -> Unit,
    onRelease: (owner: Long, distance: Float, velocity: Float, minFling: Float) -> Unit,
    onCancel: (owner: Long) -> Unit,
): Modifier = composed {
    val config = LocalViewConfiguration.current
    val session = remember { PageDragSession() }
    val dragDirection by rememberUpdatedState(direction)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)

    val offer = remember(session, config) {
        { dx: Float ->
            val allowed = dragDirection()
            if (allowed != null && dx != 0f && session.offer(dx, config.touchSlop, allowed)) {
                val owner = start()
                session.owner = owner
                session.distance = session.candidate
                drag(owner, session.distance)
                true
            } else {
                false
            }
        }
    }
    val connection = remember(session, offer) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || !session.down || session.owner != null) {
                    return Offset.Zero
                }
                session.offeredByChild = true
                return if (offer(available.x)) Offset(available.x, 0f) else Offset.Zero
            }
        }
    }

    Modifier.nestedScroll(connection).pointerInput(config) {
        val tracker = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            session.reset(active = true)
            tracker.resetTracking()
            tracker.addPointerInputChange(down)
            var lastMoveUptime = down.uptimeMillis
            try {
                while (true) {
                    val initial = awaitPointerEvent(PointerEventPass.Initial)
                    val change = initial.changes.firstOrNull { it.id == down.id } ?: break
                    val owner = session.owner
                    if (!change.pressed) {
                        // Compose sends an already consumed up for ACTION_CANCEL. Only
                        // a real up may use velocity to commit to the other anchor.
                        if (owner != null && !change.isConsumed) {
                            change.consume()
                            val velocity = pageDragVelocity(
                                fitted = tracker.calculateVelocity().x,
                                distance = session.distance,
                                elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                                sinceLastMoveMillis = change.uptimeMillis - lastMoveUptime,
                            )
                            val distance = session.distance
                            session.reset(active = false)
                            release(owner, distance, velocity, config.minimumFlingVelocity)
                        }
                        break
                    }
                    val delta = change.positionChangeIgnoreConsumed()
                    tracker.addPointerInputChange(change)
                    if (delta.x != 0f) lastMoveUptime = change.uptimeMillis
                    if (owner != null) {
                        session.distance += delta.x
                        if (delta.x != 0f) drag(owner, delta.x)
                        change.consume()
                        continue
                    }
                    session.rawTravel += delta
                    session.offeredByChild = false
                    awaitPointerEvent(PointerEventPass.Main)
                    if (session.owner != null ||
                        (!change.isConsumed && !session.offeredByChild && offer(delta.x))
                    ) {
                        change.consume()
                    }
                }
            } finally {
                val owner = session.owner
                session.reset(active = false)
                if (owner != null) cancel(owner)
            }
        }
    }
}
