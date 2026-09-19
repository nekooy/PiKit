package pi.kit.mob.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * Slide and fade between two pages, the direction depending on [forward].
 *
 * [key] is the [AnimatedContent] content key: the animation is driven by the
 * destination alone, so two different pages that happen to want the same
 * composable still count as a move, and the same page recomposing does not.
 *
 * ## Why [forward] is a parameter and not derived here
 *
 * `AnimatedContent` only hands its `transitionSpec` the `initialState` and
 * `targetState` of the transition it is about to run, and once that transition is
 * in flight its `targetState` has already moved on to the *next* destination — so
 * asking the two states "which is deeper?" answers the wrong question as soon as a
 * second tap lands mid-slide. The caller knows which page it was showing a moment
 * ago, and that is the only reliable source of a direction. (An enum of the two
 * directions lived here for a while and had exactly one reader; a `Boolean` is the
 * whole of it.)
 *
 * ## Why every page keeps its own header
 *
 * The header is inside the moving content, so the back arrow and the title slide
 * with the page. Hoisting it into a fixed slot was the obvious alternative —
 * three pages pass an `onBack` that differs from their parent row, one page puts
 * a Save action in `actions`, and the Manual page carries its own scroll, so a
 * single header above [PageSwap] would have to be told all of that by the parent.
 * The payoff would have been a stationary band that only ever changes its text;
 * the cost is one page's worth of state moved away from the page that owns it.
 *
 * ## Why the two pages travel different distances
 *
 * The incoming page starts one full width out and the outgoing one travels only a
 * fifth of the way, in the opposite direction. Equal and opposite offsets would
 * park both pages' text on the same pixels for the middle of the animation, which
 * at this duration reads as a smear rather than as one page replacing another; the
 * short exit instead looks like the outgoing page being pushed back and covered.
 *
 * ## Why 240 ms
 *
 * Long enough to see, short enough that a back gesture does not feel as though it
 * is being waited on. The reference is the platform's own sub-page transition at
 * roughly 250 ms: an animation noticeably slower than the one the user just used
 * to go back reads as the app lagging, which is the complaint this exists to
 * answer. `FastOutSlowInEasing` is the curve the setup screen's progress bar
 * already uses.
 */
@Composable
fun PageSwap(
    key: Any,
    forward: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Any) -> Unit,
) {
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = {
            val distance = if (forward) 1 else -1
            // Both specs are pinned on purpose. There is nothing in a `tween`'s
            // arguments to say what it animates: the slide is in pixels and the
            // fade is in alpha, and one shared spec cannot be both.
            val slide: FiniteAnimationSpec<IntOffset> =
                tween(durationMillis = SLIDE_MILLIS, easing = FastOutSlowInEasing)
            val fade: FiniteAnimationSpec<Float> =
                tween(durationMillis = SLIDE_MILLIS, easing = FastOutSlowInEasing)
            val incoming: EnterTransition = slideInHorizontally(animationSpec = slide) { width ->
                distance * width
            } + fadeIn(fade)
            val outgoing: ExitTransition = slideOutHorizontally(animationSpec = slide) { width ->
                -distance * width / 5
            } + fadeOut(fade)
            // The fade never reaches zero: a page that fades in from nothing
            // shows the background through the gap, and on this screen the
            // background is a different colour from every header band.
            (incoming togetherWith outgoing)
                // The swap always happens inside a `fillMaxSize` box, so this
                // never has a size to interpolate. It is here for the day it is
                // used somewhere unbounded: without it a growing page clips the
                // one leaving through it.
                .using(SizeTransform(clip = false))
        },
        contentKey = { it },
        label = "page",
    ) { page ->
        content(page)
    }
}

/**
 * The tab strip's transition: a bare crossfade, and nothing else.
 *
 * A tab is tapped repeatedly, so this is [CROSSFADE_MILLIS] and no movement at
 * all — a crossfade has no direction to be wrong about, which matters because the
 * four tabs are peers rather than a hierarchy. The container that hosts it must
 * paint its own opaque background: for the whole of the fade both pages are on
 * screen at once, and a transparent host would show the previous tab through the
 * new one.
 *
 * `Crossfade` and not `AnimatedContent` with a fade spec: they compile to the same
 * thing, but this one is not parameterised by a state, and one page is enough
 * content for it.
 */
@Composable
fun TabFade(
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (Any) -> Unit,
) {
    Crossfade(
        targetState = key,
        modifier = modifier,
        animationSpec = tween(durationMillis = CROSSFADE_MILLIS, easing = FastOutSlowInEasing),
        label = "tab",
    ) { tab ->
        content(tab)
    }
}

/**
 * 240 ms. The reference is the platform's own sub-page transition, which is about
 * 250 ms; the slide is timed against it rather than against a feel for what is
 * "smooth", because an animation that outlasts the system one reads as the app
 * lagging behind the finger.
 */
private const val SLIDE_MILLIS = 240

/**
 * 160 ms. Deliberately below the usable floor for a *directional* move, because a
 * tab is tapped repeatedly: a slow fade between four peers feels worse than the
 * cut it replaced.
 */
private const val CROSSFADE_MILLIS = 160
