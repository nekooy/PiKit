package pi.kit.mob.ui

import android.os.SystemClock
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

/** Which way a page move travels: the card opens rightward, the drawer closes leftward. */
internal enum class PageDragDirection {
    Right,
    Left;

    fun allows(dx: Float): Boolean = when (this) {
        Right -> dx > 0f
        Left -> dx < 0f
    }
}

/**
 * How much farther a gesture has to lean horizontally than vertically before the page may
 * take it from the child it started on.
 *
 * The platform's slop is a square, so a diagonal swipe crosses it in both axes at almost the
 * same event and slop alone cannot tell a transcript scroll from a drawer pull. At 1.2 the
 * horizontal travel has to beat the vertical by a fifth: a 45° drag (equal parts) still
 * belongs to whatever the finger went down on, and only a gesture closer to the drawer's own
 * axis is claimed. The ratio is deliberately lopsided toward the child — a missed opening can
 * be repeated, whereas a stolen vertical scroll fights the reader on every frame.
 */
private const val HORIZONTAL_DOMINANCE = 1.2f

/**
 * Whether the page's own travel has earned the drawer yet.
 *
 * [dx] is the *drawn* horizontal travel accumulated so far, signed in the direction the page
 * moves. [dy] is the finger's vertical travel from where it went down. [touchSlop] is this
 * layout's own slop, not a dp guess, so the threshold follows the density and the platform's
 * accessibility settings.
 *
 * Direction is part of the answer: the conversation card opens [PageDragDirection.Right] and
 * the open drawer closes [PageDragDirection.Left]. A drag the other way belongs to the table,
 * formula or list under the finger and is never claimed. Keeping the test pure is what lets a
 * JVM test pin all three refusals without a device.
 */
internal fun claimsPageDrag(
    dx: Float,
    dy: Float,
    touchSlop: Float,
    direction: PageDragDirection = PageDragDirection.Right,
): Boolean {
    val travel = when (direction) {
        PageDragDirection.Right -> dx
        PageDragDirection.Left -> -dx
    }
    if (travel <= 0f) return false
    return travel > touchSlop && travel > abs(dy) * HORIZONTAL_DOMINANCE
}

/**
 * One gesture's shared state between the pointer loop and the nested-scroll fallback.
 *
 * The two halves cannot keep separate totals: the transcript's vertical list reports a
 * horizontal component to the nested-scroll connection even when it consumes the pointer
 * change, and a table's horizontal scroller reports the movement it could not use once it
 * reaches its start. Both are the *same* drawer travel, so both write one pair of numbers.
 */
private class PageDragSession {
    var pointerDown = false

    /** True once the page has claimed the gesture; only one half may claim it. */
    var started = false

    /** True when the nested-scroll half claimed, so the pointer half must not count again. */
    var nestedOwns = false

    /** Signed horizontal distance the drawer has travelled. */
    var travelled = 0f

    /** The finger's raw travel, which decides horizontal dominance regardless of consumption. */
    var rawDx = 0f
    var rawDy = 0f

    var startedAt = 0L
    var lastUptime = 0L

    fun reset(downUptime: Long) {
        pointerDown = true
        started = false
        nestedOwns = false
        travelled = 0f
        rawDx = 0f
        rawDy = 0f
        startedAt = downUptime
        lastUptime = downUptime
    }

    fun horizontalDominant(): Boolean = abs(rawDx) > abs(rawDy) * HORIZONTAL_DOMINANCE
}

/**
 * A page move driven by a finger, with the platform's own settle rule.
 *
 * ## The starvation this replaces
 *
 * `detectHorizontalDragGestures` looked for its gesture by waiting out its own touch slop on
 * the pointer, and it cancelled the moment another node consumed a change. Children see the
 * `Main` pass before their parents, and the transcript is full of children that consume: a
 * horizontally scrollable table or a long formula claims a horizontal drag from its first
 * slop-width, and the `LazyColumn` consumes a diagonal one. The detector was therefore
 * cancelled before it could cross its own slop, which is why a rightward swipe *exactly* on a
 * table never opened the drawer and why a quick swipe after a scroll often found no gesture:
 * the gesture was taken, not missed.
 *
 * ## The two halves
 *
 * This modifier watches both levels of Compose's gesture pipeline:
 *
 *  - a pointer loop in the **Main** pass counts only horizontal movement a child did *not*
 *    consume. It is what opens the drawer from the header, the page's margins, or any part of
 *    the transcript with no scroller under it;
 *  - a **nested-scroll connection** receives the horizontal movement a child scrollable could
 *    not use. A table scrolled away from its start consumes the rightward drag until it is back
 *    at its start; the leftover then arrives here and opens the drawer. A vertical transcript
 *    also reports the horizontal component it never uses, which is what makes a quick rightward
 *    swipe after a scroll work even though the list consumed the pointer change.
 *
 * The two share [PageDragSession], so the distance and velocity describe one gesture. A child
 * that consumes a movement keeps it: the page never counts movement someone else acted on, and
 * a table scrolled 200dp back to its start contributes none of those 200dp to the drawer's
 * half-width commit rule.
 *
 * ## The rule
 *
 * A claim needs this layout's touch slop **and** [HORIZONTAL_DOMINANCE] against the finger's
 * vertical travel, in the direction the page is allowed to move. Once claimed, [onStart] fires
 * once and [onDrag] receives the accumulated travel, then every later delta; the callers clamp
 * the offset, so a reversal closes what was opened. A release before the claim calls nothing.
 *
 * ## Why the average is the fallback
 *
 * A `VelocityTracker` is a least-squares fit over the samples it was given, and a gesture that
 * arrives as three events — which is what a synthetic swipe and a brisk thumb both can
 * produce — leaves it with nothing to fit: measured on the emulator, a 329px drag in 500ms
 * reported `samples=3 v=0.0`, and a fling that reports zero is a fling that never happens.
 * When the fit comes back empty the *average* velocity (`distance ÷ elapsed`) is used instead:
 * the same quantity in the same unit, exact for a constant-speed gesture, and never zero for a
 * gesture that moved.
 *
 * [onRelease] also receives the minimum fling velocity from this layout's own
 * `ViewConfiguration`, so the caller decides with the numbers Android uses instead of a
 * fraction guessed from a screenshot.
 */
internal fun Modifier.pageDrag(
    width: Int,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: (distance: Float, velocity: Float, minFling: Float) -> Unit,
    direction: PageDragDirection = PageDragDirection.Right,
): Modifier = composed {
    val minFling = LocalViewConfiguration.current.minimumFlingVelocity
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val session = remember { PageDragSession() }
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)

    val connection = remember(session, direction, touchSlop) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val dx = available.x
                if (dx == 0f || !session.pointerDown) return Offset.Zero
                if (!session.started && !direction.allows(dx)) return Offset.Zero
                if (!session.horizontalDominant()) return Offset.Zero
                if (!session.started) {
                    session.travelled += dx
                    if (!claimsPageDrag(session.travelled, session.rawDy, touchSlop, direction)) {
                        // Nothing has moved visibly yet. The leftover is not consumed either,
                        // so a scrollable that still has room keeps it.
                        return Offset.Zero
                    }
                    session.started = true
                    session.nestedOwns = true
                    session.startedAt = SystemClock.uptimeMillis()
                    start()
                    drag(session.travelled)
                } else {
                    if (!session.nestedOwns) return Offset.Zero
                    session.travelled += dx
                    drag(dx)
                }
                return Offset(dx, 0f)
            }
        }
    }

    Modifier
        .nestedScroll(connection)
        .pointerInput(width, minFling, touchSlop, direction) {
            if (width <= 0) return@pointerInput
            val tracker = VelocityTracker()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                session.reset(down.uptimeMillis)
                tracker.resetTracking()
                tracker.addPointerInputChange(down)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    session.lastUptime = change.uptimeMillis
                    // Raw positions: the tracker's answer is the finger's speed even when a
                    // child — or this page after the claim — is the one consuming the movement.
                    tracker.addPointerInputChange(change)
                    val dx = change.positionChangeIgnoreConsumed().x
                    val dy = change.positionChangeIgnoreConsumed().y
                    session.rawDx += dx
                    session.rawDy += dy
                    if (!session.started) {
                        if (!change.isConsumed && direction.allows(dx)) {
                            session.travelled += dx
                        }
                        if (claimsPageDrag(session.travelled, session.rawDy, touchSlop, direction)) {
                            session.started = true
                            session.startedAt = change.uptimeMillis
                            start()
                            drag(session.travelled)
                            change.consume()
                        }
                    } else if (!session.nestedOwns) {
                        // The page owns the gesture. The child may still consume the change on
                        // the frame the claim lands, so the raw delta is forwarded rather than
                        // the positionChange() — which reports zero for a consumed change.
                        session.travelled += dx
                        if (dx != 0f) drag(dx)
                        change.consume()
                    }
                    if (!change.pressed) break
                }
                if (session.started) {
                    val elapsed = (session.lastUptime - session.startedAt).coerceAtLeast(1L)
                    val fitted = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
                    val velocity = if (fitted != 0f) {
                        fitted
                    } else {
                        session.travelled / (elapsed / 1000f)
                    }
                    release(session.travelled, velocity, minFling)
                }
                session.pointerDown = false
            }
        }
}
