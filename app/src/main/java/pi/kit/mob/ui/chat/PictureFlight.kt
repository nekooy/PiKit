package pi.kit.mob.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * The arithmetic a picture's opening and closing flight is built on.
 *
 * A viewer that appears as a black window over the page is a *dialog*; a viewer that grows
 * out of the square the reader tapped is a picture. The difference is one rectangle: the
 * tile's bounds are captured when the tap happens (`screenRect`), and every frame of the
 * open and the close is that rectangle interpolated towards the place the fitted picture
 * sits. The picture keeps its own shape inside that rectangle — it covers it, and the
 * rectangle clips it — so the first frame is the tile exactly (a square tile of a portrait
 * photo is the crop the tile was already showing) and the last frame is the whole picture,
 * because a fitted rectangle and the picture have the same shape by then. Interpolating a
 * *rectangle* rather than a scale plus a clip is what makes those two ends meet without a
 * jump.
 *
 * Everything here is a pure function of numbers that are already known at draw time, which
 * is what lets the thresholds and the crop be pinned by tests instead of looked at on a
 * phone (the same reason [PictureZoom] exists).
 */
internal object PictureFlight {

    /**
     * How far a picture has to be dragged before letting go closes the viewer, in dp.
     *
     * A phone's own gallery dismisses at about a finger's width; less than that turns a
     * scroll that starts a few pixels low into a close.
     */
    const val DISMISS_DISTANCE_DP = 120f

    /**
     * The downward speed, in dp per second, that closes the viewer without the distance.
     *
     * A flick is a decision the distance alone does not capture: the same 60dp that is a
     * spring-back when it is released still is a close when it is thrown.
     */
    const val DISMISS_VELOCITY_DP = 900f

    /**
     * How much of the ground's opacity a full-length drag takes away.
     *
     * The page behind the viewer is the answer to "where am I going" while a finger drags
     * the picture down, so the black has to fall away as the picture moves — but not to
     * nothing, or the chrome and the picture would be read against the chat instead of
     * against the ground they belong to.
     */
    private const val GROUND_FADE = 0.7f

    /**
     * The rectangle the flying picture is drawn in, [progress] of the way from [origin] to
     * [fitted].
     *
     * No origin — a tile that was never laid out, or a page swiped to after the tap — is
     * the fitted rectangle at every progress: the viewer still fades in, it just has
     * nothing to grow out of.
     */
    fun rect(origin: Rect?, fitted: Rect, progress: Float): Rect {
        if (origin == null || origin.width <= 0f || origin.height <= 0f) return fitted
        val t = progress.coerceIn(0f, 1f)
        return Rect(
            origin.left + (fitted.left - origin.left) * t,
            origin.top + (fitted.top - origin.top) * t,
            origin.right + (fitted.right - origin.right) * t,
            origin.bottom + (fitted.bottom - origin.bottom) * t,
        )
    }

    /** [fitted] centred in [frame]: where a `ContentScale.Fit` picture actually lands. */
    fun centred(fitted: Size, frame: Size): Rect {
        if (fitted.width <= 0f || fitted.height <= 0f) return Rect.Zero
        val left = (frame.width - fitted.width) / 2f
        val top = (frame.height - fitted.height) / 2f
        return Rect(left, top, left + fitted.width, top + fitted.height)
    }

    /** The black ground's opacity while the flight is at [progress] and the finger at [drag]. */
    fun groundAlpha(progress: Float, drag: Float, dismissDistance: Float): Float {
        if (dismissDistance <= 0f) return progress.coerceIn(0f, 1f)
        val fade = GROUND_FADE * (drag / (2f * dismissDistance)).coerceIn(0f, 1f)
        return progress.coerceIn(0f, 1f) * (1f - fade)
    }

    /** The chrome's opacity: gone by the distance that closes the viewer, not at half of it. */
    fun chromeAlpha(progress: Float, drag: Float, dismissDistance: Float): Float {
        if (dismissDistance <= 0f) return progress.coerceIn(0f, 1f)
        return progress.coerceIn(0f, 1f) * (1f - (drag / dismissDistance).coerceIn(0f, 1f))
    }

    /**
     * Whether letting go at [drag] with [velocity] closes the viewer.
     *
     * Positive is downward, in this file's own pixels per second. The two limits are
     * deliberately *or*: a slow drag past the distance and a fast flick short of it are
     * both a decision.
     */
    fun dismisses(drag: Float, velocity: Float, distance: Float, velocityLimit: Float): Boolean =
        drag > distance || velocity > velocityLimit
}
