package pi.kit.mob.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic between a picture's own shape and the frame it is drawn in.
 *
 * A prompt's lone picture is not squared or letterboxed: the frame takes the picture's
 * aspect and fills one of its two bounds, so a portrait screenshot is tall and a
 * panorama is wide, and neither is drawn smaller than the card allows. That is one
 * function, and these are the three shapes it has to answer correctly plus the
 * degenerate one — cheaper to pin than to look at on a phone. The numbers below are the
 * prompt card's on a 1080p phone: about 325dp of measure, 300dp of height.
 */
class ChatPicturesTest {

    private fun box(width: Int, height: Int) =
        pictureBox(width = width, height = height, maxWidth = 325f, maxHeight = 300f)

    @Test
    fun `a wide picture fills the width`() {
        val (width, height) = box(1200, 400)
        assertEquals(325f, width, 0.01f)
        assertEquals(108.33f, height, 0.01f)
    }

    @Test
    fun `a tall picture is bounded by the height, not by a thumbnail box`() {
        // A 9:16 phone screenshot: the shape that came out 88dp wide under a 208 × 156 cap.
        val (width, height) = box(900, 1600)
        assertEquals(168.75f, width, 0.01f)
        assertEquals(300f, height, 0.01f)
    }

    @Test
    fun `a picture whose shape matches the measure fills both`() {
        // 13:12 is the frame's own shape at 325 × 300.
        val (width, height) = box(1300, 1200)
        assertEquals(325f, width, 0.01f)
        assertEquals(300f, height, 0.01f)
    }

    @Test
    fun `a bitmap with no size falls back to the frame`() {
        // Not a case a decoded picture produces, but the frame is what the placeholder
        // draws and it must be the same box the picture will land in.
        val (width, height) = box(0, 0)
        assertEquals(325f, width, 0.01f)
        assertEquals(243.75f, height, 0.01f)
    }
}
