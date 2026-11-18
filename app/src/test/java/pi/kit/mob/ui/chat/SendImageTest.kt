package pi.kit.mob.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The facts that decide whether a picture is sent as picked or re-encoded first.
 *
 * The raster itself cannot be exercised on the JVM — `BitmapFactory` is Android — so what
 * is pinned here is the arithmetic around it: the two limits pi tests before it decides to
 * rescale, and the size a fitted picture takes. Those are the halves the app and
 * `models.json` have to agree about.
 */
class SendImageTest {

    @Test
    fun `a picture inside both limits is sent untouched`() {
        assertTrue(fitsWithin(1200, 2670, 1_000_000))
    }

    @Test
    fun `a long screenshot is outside the edge limit`() {
        assertFalse(fitsWithin(1200, SEND_IMAGE_MAX_EDGE + 1, 1_000_000))
    }

    @Test
    fun `a picture over the byte ceiling is outside it`() {
        assertFalse(fitsWithin(1200, 2000, SEND_IMAGE_MAX_BYTES + 1))
    }

    @Test
    fun `fitting keeps the aspect ratio and lands on the edge`() {
        assertEquals(900 to SEND_IMAGE_MAX_EDGE, fitSize(1200, 4000))
    }

    @Test
    fun `an extreme banner keeps one pixel of its short side`() {
        assertEquals(1 to SEND_IMAGE_MAX_EDGE, fitSize(3, 12000))
    }

    @Test
    fun `a picture already inside the edge is not enlarged`() {
        assertEquals(800 to 600, fitSize(800, 600))
    }

    @Test
    fun `a picture that was not fitted carries no coordinate note`() {
        assertNull(dimensionNote(PreparedImage(byteArrayOf(), "image/jpeg", 800, 600)))
    }

    @Test
    fun `a fitted picture carries the ratio in pi's own wording`() {
        val note = dimensionNote(
            PreparedImage(
                bytes = byteArrayOf(),
                mimeType = "image/jpeg",
                width = 900,
                height = 3000,
                originalWidth = 1200,
                originalHeight = 4000,
            ),
        )
        assertEquals(
            "[Image: original 1200x4000, displayed at 900x3000. " +
                "Multiply coordinates by 1.33 to map to original image.]",
            note,
        )
    }
}
