package pi.kit.mob.ui.settings

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The avatar crop's arithmetic.
 *
 * The window that drew it was wrong in a way that only shows on a picture that is not
 * square: `Modifier.size` clamps to the parent's constraints, so the bitmap was measured
 * back down to the frame at every zoom — the pinch moved a number and nothing on screen —
 * and `ContentScale.FillBounds` stretched the clamped result to that square, which cost a
 * 3:4 portrait a quarter of its height. The offset limit came from the size that had been
 * *asked for* rather than the size that was granted, so a drag slid the frame-sized
 * picture off centre and showed the backdrop behind it.
 *
 * These are the properties that make the fix stay fixed: one scale for both axes, a
 * window always covered, a drag allowed exactly as far as there is picture, and a stored
 * square that is the window's own width in the source's pixels.
 */
class AvatarCropGeometryTest {

    private val frame = 1000f

    @Test
    fun `the drawn picture keeps the source's aspect ratio at every zoom`() {
        // The failure this pins: FillBounds drew 1000 x 1000 for a 4000 x 3000 source.
        for (zoom in listOf(1f, 1.5f, 2f, 4f)) {
            val drawn = AvatarCropGeometry.placement(frame, 4000, 3000, zoom).size
            assertEquals("at ${zoom}x", 4f / 3f, drawn.width / drawn.height, 1e-4f)
        }
    }

    @Test
    fun `the drawn picture always covers the window`() {
        val shapes = listOf(4000 to 3000, 3000 to 4000, 1000 to 1000, 5000 to 800)
        for ((width, height) in shapes) {
            for (zoom in listOf(1f, 2f, 4f)) {
                val drawn = AvatarCropGeometry.placement(frame, width, height, zoom).size
                assertTrue(
                    "$width x $height at ${zoom}x drew ${drawn.width} x ${drawn.height}",
                    drawn.width >= frame - 0.01f && drawn.height >= frame - 0.01f,
                )
            }
        }
    }

    @Test
    fun `a square picture is pinned at one zoom and free at two`() {
        val atOne = AvatarCropGeometry.placement(frame, 1000, 1000, 1f).size
        assertOffset(Offset.Zero, AvatarCropGeometry.clampOffset(Offset(300f, -200f), frame, atOne))

        val atTwo = AvatarCropGeometry.placement(frame, 1000, 1000, 2f).size
        assertOffset(
            Offset(300f, -200f),
            AvatarCropGeometry.clampOffset(Offset(300f, -200f), frame, atTwo),
        )
        assertOffset(
            Offset(500f, -500f),
            AvatarCropGeometry.clampOffset(Offset(9000f, -9000f), frame, atTwo),
        )
    }

    @Test
    fun `a landscape picture pans across but not up and down at one zoom`() {
        // The shorter side is exactly the window at 1x, so there is no vertical overhang
        // to spend: the drag is horizontal only, and by (1333 - 1000) / 2.
        val drawn = AvatarCropGeometry.placement(frame, 4000, 3000, 1f).size
        assertEquals(frame, drawn.height, 0.01f)
        assertEquals(1333.33f, drawn.width, 0.1f)
        assertOffset(
            Offset(166.67f, 0f),
            AvatarCropGeometry.clampOffset(Offset(400f, 400f), frame, drawn),
            0.1f,
        )
    }

    @Test
    fun `a pinch keeps the point under the fingers`() {
        val offset = Offset(120f, -80f)
        val centroid = Offset(310f, 90f)
        val factor = 1.8f

        val moved = AvatarCropGeometry.zoomAround(offset, centroid, factor)

        // The source point that was under the centroid reappears at the centroid: the
        // picture's centre lands where the same point is still under the finger.
        val reappears = moved + (centroid - offset) * factor
        assertOffset(centroid, reappears)
    }

    @Test
    fun `the stored square is the window's width in source pixels`() {
        // A 4000 x 3000 picture in a 1000px window: cover is 1/3, so the window shows a
        // 3000px square, centred in the extra 1000px of width.
        val square = AvatarCropGeometry.sourceSquare(4000, 3000, frame, 1f, Offset.Zero)
        assertEquals(3000, square.side)
        assertEquals(500, square.left)
        assertEquals(0, square.top)

        assertEquals(1500, AvatarCropGeometry.sourceSquare(4000, 3000, frame, 2f, Offset.Zero).side)
    }

    @Test
    fun `no offset can put the stored square outside the picture`() {
        val width = 4000
        val height = 3000
        for (zoom in listOf(1f, 1.7f, 4f)) {
            val drawn = AvatarCropGeometry.placement(frame, width, height, zoom).size
            for (x in -5000..5000 step 500) {
                for (y in -5000..5000 step 500) {
                    val clamped = AvatarCropGeometry.clampOffset(
                        Offset(x.toFloat(), y.toFloat()),
                        frame,
                        drawn,
                    )
                    val square = AvatarCropGeometry.sourceSquare(width, height, frame, zoom, clamped)
                    assertTrue("${zoom}x at $clamped", square.left >= 0 && square.top >= 0)
                    assertTrue(
                        "${zoom}x at $clamped",
                        square.left + square.side <= width && square.top + square.side <= height,
                    )
                }
            }
        }
    }

    @Test
    fun `a gesture before the window is measured does nothing`() {
        val state = AvatarCropState(4000, 3000)
        state.transform(Offset(500f, 500f), Offset(80f, 80f), 2f)
        state.zoomTo(3f)
        assertEquals(1f, state.zoom, 0f)
    }

    @Test
    fun `a pinch cannot leave the zoom range`() {
        val state = AvatarCropState(4000, 3000)
        state.frame = 1000
        repeat(12) { state.transform(Offset(500f, 500f), Offset.Zero, 2f) }
        assertEquals(AvatarCropGeometry.MAX_ZOOM, state.zoom, 1e-4f)
        repeat(20) { state.transform(Offset(500f, 500f), Offset.Zero, 0.5f) }
        assertEquals(AvatarCropGeometry.MIN_ZOOM, state.zoom, 1e-4f)
    }

    @Test
    fun `a drag stops where the picture does`() {
        val state = AvatarCropState(1000, 1000)
        state.frame = 1000
        state.zoomTo(2f)
        state.transform(Offset(500f, 500f), Offset(4000f, 4000f), 1f)
        assertEquals(500f, state.offset.x, 0.01f)
        assertEquals(500f, state.offset.y, 0.01f)
    }

    @Test
    fun `a double tap goes in on the point tapped and comes back to the whole picture`() {
        val state = AvatarCropState(4000, 3000)
        state.frame = 1000

        state.toggle(Offset(400f, 400f))
        assertEquals(2.5f, state.zoom, 1e-4f)
        assertTrue(state.offset.x > 0f && state.offset.y > 0f)

        state.toggle(Offset(400f, 400f))
        assertEquals(AvatarCropGeometry.MIN_ZOOM, state.zoom, 1e-4f)
        assertOffset(Offset.Zero, state.offset)
    }

    private fun assertOffset(expected: Offset, actual: Offset, delta: Float = 0.001f) {
        assertEquals("x", expected.x, actual.x, delta)
        assertEquals("y", expected.y, actual.y, delta)
    }
}
