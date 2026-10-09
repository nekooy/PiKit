/*
 * PiKit's motion, expressed as the handful of named moves the app actually makes.
 *
 * ## Springs, not durations
 *
 * The M3 expressive motion system replaces easing-and-duration with springs:
 * stiffness, damping and initial velocity, grouped into two *kinds* — spatial for
 * anything that moves or resizes (which overshoots and settles with a bounce) and
 * effects for colour and opacity (which must not overshoot, because a colour that
 * passes through a brighter value on its way reads as a flash). Each kind comes in
 * three speeds: fast for small components, default for most things, slow for
 * full-screen changes.
 *
 * Every spec below is read from `MaterialTheme.motionScheme`, which the theme sets
 * to the expressive scheme, so nothing in the app names a spring value directly.
 * That is the point of the token layer: swapping the scheme to `standard` — the
 * less bouncy one the guidance recommends for utilitarian products — is one line
 * in `PiTheme.kt` and no call site changes.
 *
 * ## Why there is no `spring(dampingRatio = …)` anywhere in the app
 *
 * A hand-written spring in a screen is a spring that stops matching the rest of the
 * app the moment the scheme changes, and the guidance's "one spring can apply to
 * many situations, which makes the motion feel consistent" is the reason. The two
 * exceptions are named here ([PiMotion.settle]) rather than at their call sites,
 * so there are exactly two places to look if the motion ever feels wrong.
 */
package pi.kit.mob.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * The six specs, by name.
 *
 * `@OptIn` sits on the object rather than on each member because the whole
 * `MotionScheme` surface is behind the expressive annotation in this release; the
 * alternative is the same opt-in repeated six times, which is noise on the one
 * file whose subject is motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object PiMotion {

    /** A small component moving: a switch's handle, a button responding to a press. */
    @Composable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

    /** Most movement: a panel travelling, a row resizing, a card expanding. */
    @Composable
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

    /** A whole screen moving, where the distance is large enough to need the time. */
    @Composable
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowSpatialSpec()

    /** A small colour or opacity change: a pressed button, a switch's track. */
    @Composable
    fun <T> fastEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

    /** Most colour and opacity: a chip's fill, an indicator's tint. */
    @Composable
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

    /** A full-screen crossfade, where the whole window changes colour. */
    @Composable
    fun <T> slowEffects(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.slowEffectsSpec()

    /**
     * A spring that returns something to where it was rather than animating it to
     * a new place — a sheet sprung back up after a short drag, a fling that
     * overshot.
     *
     * One of the two hand-written specs in the app. It is deliberately *not* a
     * token: a token describes how a component moves through a transition, while
     * this describes a spring releasing stored energy, and the two want different
     * damping. Measured against the alternative — reusing `defaultSpatial()` — this
     * one settles in roughly three quarters of the time, which is what stops a
     * released drag from looking like it is being drawn back by hand.
     */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.75f,
        stiffness = 850f,
    )
}

/**
 * A move between two pages, the direction depending on [forward].
 *
 * ## Why this is a spring and not the 240 ms tween it replaced
 *
 * The tween was calibrated against the platform's own sub-page transition, which
 * is the right reference and the wrong mechanism: at a fixed duration the move is
 * the same length of time whether it carries a 40dp settings page or a
 * full-width file listing, and a gesture that is interrupted mid-slide has to be
 * restarted rather than retargeted. A spring is defined by where it is going, so a
 * back gesture two-thirds of the way through a forward slide reverses from where
 * the page actually is.
 *
 * ## Why the two pages travel different distances
 *
 * The incoming page starts one full width out and the outgoing one travels only a
 * fifth of the way, in the opposite direction. Equal and opposite offsets park
 * both pages' text on the same pixels for the middle of the animation, which reads
 * as a smear rather than as one page replacing another; the short exit instead
 * looks like the outgoing page being pushed back and covered.
 *
 * [forward] is a parameter and not derived from the two states for a reason that
 * is worth keeping: `AnimatedContent` only hands its spec the initial and target
 * states of the transition it is about to run, and once that transition is in
 * flight its target has already moved on to the *next* destination — so asking two
 * keys "which is deeper?" answers the wrong question the moment a second tap lands
 * mid-slide. The caller knows which page it was showing a moment ago, and that is
 * the only reliable source of a direction.
 */
@Composable
fun PiPageSwap(
    key: Any,
    forward: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Any) -> Unit,
) {
    val slide: FiniteAnimationSpec<IntOffset> = PiMotion.defaultSpatial()
    val fade: FiniteAnimationSpec<Float> = PiMotion.fastEffects()

    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            val distance = if (forward) 1 else -1
            val incoming: EnterTransition = slideInHorizontally(animationSpec = slide) { width ->
                distance * width
            } + fadeIn(fade)
            val outgoing: ExitTransition = slideOutHorizontally(animationSpec = slide) { width ->
                -distance * width / 5
            } + fadeOut(fade)
            (incoming togetherWith outgoing).using(SizeTransform(clip = false))
        },
        contentKey = { it },
        label = "page",
    ) { page ->
        content(page)
    }
}

/**
 * The bottom bar's transition: a crossfade on the effects spring, and nothing else.
 *
 * A destination is tapped repeatedly, so this has no direction to be wrong about —
 * which matters because the four destinations are peers rather than a hierarchy,
 * and a slide between peers claims a relationship that is not there.
 *
 * The host must paint an opaque background: for the length of the fade both pages
 * are composed at once, and a transparent host would show the outgoing page
 * through the incoming one.
 */
@Composable
fun PiTabFade(
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (Any) -> Unit,
) {
    Crossfade(
        targetState = key,
        modifier = modifier,
        animationSpec = PiMotion.defaultEffects(),
        label = "destination",
    ) { destination ->
        content(destination)
    }
}
