package pi.kit.mob.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.min

/**
 * The arithmetic the picture viewer's pinch is built on.
 *
 * A picture is drawn with `ContentScale.Fit` inside the window's box and is then scaled and
 * translated as one layer ([androidx.compose.ui.graphics.graphicsLayer]), so every number
 * here is in the *frame's* pixels: the fitted picture is its size at scale 1, and [scale]
 * multiplies it. Keeping the geometry out of the composable is what lets the four rules
 * that decide whether a picture is cropped, pushed off its frame or centred be pinned by
 * tests instead of looked at on a phone.
 *
 * The frame's origin is its centre, which is also the layer's transform origin: an offset
 * of zero draws the picture centred, and a positive x moves it right.
 */
internal object PictureZoom {

    /** Unzoomed: the whole picture, fitted. */
    const val MIN_SCALE = 1f

    /**
     * The most a picture may be magnified.
     *
     * 5× is the point at which a 3000 px picture on a 1080 px screen shows about 4 source
     * pixels per screen pixel — more magnification is an empty grid of pixels rather than a
     * closer look at the picture.
     */
    const val MAX_SCALE = 5f

    /** What a double tap zooms to, and what it zooms back from. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /**
     * How far from exactly 1 a scale may be and still count as unzoomed.
     *
     * A pinch ends on a float derived from pixel distances, so it lands on 1.0004 as often
     * as on 1; without a tolerance the pager would stay disabled after a pinch that was
     * meant to zoom out again.
     */
    const val SETTLED = 0.01f

    /** Whether [scale] is close enough to unzoomed for the pager to take the drag. */
    fun isSettled(scale: Float): Boolean = scale <= MIN_SCALE + SETTLED

    /**
     * The size [image] is drawn at inside [frame] with `ContentScale.Fit`.
     *
     * Zero for anything unmeasured: a frame is zero on its first composition pass, and a
     * picture's size is unknown until it has been decoded.
     */
    fun fitted(image: Size, frame: Size): Size {
        if (image.width <= 0f || image.height <= 0f) return Size.Zero
        if (frame.width <= 0f || frame.height <= 0f) return Size.Zero
        val scale = min(frame.width / image.width, frame.height / image.height)
        return Size(image.width * scale, image.height * scale)
    }

    /**
     * The offset that keeps the point under [centroid] under [centroid] after a zoom by
     * [factor].
     *
     * The picture is placed as `p = c × scale + offset`, so holding a content point `c`
     * where it is on screen means `offset' = p − (p − offset) × factor`. The centroid is
     * relative to the frame's centre, which is where the layer scales about.
     */
    fun zoomAround(offset: Offset, centroid: Offset, factor: Float): Offset =
        centroid - (centroid - offset) * factor

    /**
     * [offset] moved back inside the frame's edges for a picture drawn at [scale].
     *
     * A dimension the picture already fills cannot move at all: the edges would come inside
     * the frame and show the ground behind a picture that is not zoomed. A dimension with
     * slack may move by half that slack, which is the farthest it can go before its edge
     * meets the frame's.
     */
    fun clampOffset(offset: Offset, frame: Size, fitted: Size, scale: Float): Offset {
        if (fitted.width <= 0f || fitted.height <= 0f) return Offset.Zero
        val drawn = fitted * scale
        val x = ((drawn.width - frame.width) / 2f).coerceAtLeast(0f)
        val y = ((drawn.height - frame.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
    }
}
