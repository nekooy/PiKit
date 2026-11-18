package pi.kit.mob.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's pinching arithmetic.
 *
 * A picture viewer that cannot zoom is a picture read at the size the phone chose, and one
 * whose zoom is not clamped slides a photograph off its own frame and shows the black ground
 * behind it. The rules that keep both from happening are four lines long and invisible on a
 * screenshot, so they are pinned here: the fitted size keeps the source's aspect, a pinch
 * holds the point it started on, a drag is allowed exactly as far as there is picture, and a
 * picture that fits cannot move at all.
 */
class PictureZoomTest {

    private val frame = Size(1080f, 2400f)
    private val wide = Size(4000f, 2000f)
    private val tall = Size(1000f, 3000f)

    @Test
    fun `the fitted picture keeps the source's aspect on the side that binds`() {
        // A 2:1 picture in a 1080-wide frame is 1080 x 540; a 1:3 picture binds on height.
        assertEquals(1080f, PictureZoom.fitted(wide, frame).width, 1e-3f)
        assertEquals(540f, PictureZoom.fitted(wide, frame).height, 1e-3f)
        assertEquals(2400f, PictureZoom.fitted(tall, frame).height, 1e-3f)
        assertEquals(800f, PictureZoom.fitted(tall, frame).width, 1e-3f)
    }

    @Test
    fun `an unmeasured picture or frame has no fitted size`() {
        assertEquals(Size.Zero, PictureZoom.fitted(Size.Zero, frame))
        assertEquals(Size.Zero, PictureZoom.fitted(wide, Size.Zero))
        assertEquals(Size.Zero, PictureZoom.fitted(Size(10f, 0f), frame))
    }

    @Test
    fun `a pinch holds the point under the fingers`() {
        val centroid = Offset(180f, -220f)
        for (from in listOf(1f, 1.7f, 3.2f)) {
            val to = (from * 1.8f).coerceAtMost(PictureZoom.MAX_SCALE)
            for (offset in listOf(Offset.Zero, Offset(-90f, 130f), Offset(240f, 0f))) {
                val moved = PictureZoom.zoomAround(offset, centroid, to / from)
                // The content point under the centroid before the pinch: (p - offset) / from.
                val content = (centroid - offset) / from
                val after = content * to + moved
                assertEquals("x at ${from}x -> ${to}x", centroid.x, after.x, 1e-3f)
                assertEquals("y at ${from}x -> ${to}x", centroid.y, after.y, 1e-3f)
            }
        }
    }

    @Test
    fun `a picture that fits cannot be dragged off its frame`() {
        val fitted = PictureZoom.fitted(tall, frame)
        val clamped = PictureZoom.clampOffset(Offset(500f, -800f), frame, fitted, 1f)
        assertEquals(0f, clamped.x, 1e-3f)
        assertEquals(0f, clamped.y, 1e-3f)
    }

    @Test
    fun `a zoomed picture stops with its edge on the frame's`() {
        val fitted = PictureZoom.fitted(tall, frame)
        // At 2x the picture is 1600 x 4800: 260 px of slack each way across, 1200 down.
        val clamped = PictureZoom.clampOffset(Offset(900f, -9000f), frame, fitted, 2f)
        assertEquals(260f, clamped.x, 1e-3f)
        assertEquals(-1200f, clamped.y, 1e-3f)
        // A picture whose drawn height is still inside the frame keeps its y at zero.
        val wideFitted = PictureZoom.fitted(wide, frame)
        assertEquals(0f, PictureZoom.clampOffset(Offset(0f, 400f), frame, wideFitted, 1f).y, 1e-3f)
    }

    @Test
    fun `zooming back out centres the picture again`() {
        val fitted = PictureZoom.fitted(tall, frame)
        val zoomed = PictureZoom.zoomAround(Offset.Zero, Offset(300f, 0f), 2.5f)
        val atFit = PictureZoom.clampOffset(zoomed, frame, fitted, PictureZoom.MIN_SCALE)
        assertEquals(0f, atFit.x, 1e-3f)
        assertEquals(0f, atFit.y, 1e-3f)
    }

    @Test
    fun `a pinch that lands just above one still counts as unzoomed`() {
        assertTrue(PictureZoom.isSettled(1f))
        assertTrue(PictureZoom.isSettled(1.004f))
        assertTrue(PictureZoom.isSettled(0.98f))
        assertFalse(PictureZoom.isSettled(1.02f))
        assertFalse(PictureZoom.isSettled(PictureZoom.DOUBLE_TAP_SCALE))
    }
}
