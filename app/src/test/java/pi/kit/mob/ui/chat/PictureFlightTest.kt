package pi.kit.mob.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four answers the picture viewer's opening and closing flight is built on.
 *
 * The flight is the part of the viewer that cannot be checked by looking at one frame: the
 * picture has to *start* as the tile the reader tapped and *end* as the fitted picture, and
 * both of those are a rectangle this object computes. These tests pin the two ends, the crop
 * that makes a square tile show a portrait photo rather than squash it, and the two
 * thresholds a drag is released against.
 */
class PictureFlightTest {

    private val fitted = Rect(100f, 200f, 700f, 1200f)

    @Test
    fun `no progress is the tile`() {
        val tile = Rect(40f, 900f, 340f, 1200f)
        assertEquals(tile, PictureFlight.rect(tile, fitted, 0f))
    }

    @Test
    fun `full progress is the fitted picture`() {
        assertEquals(fitted, PictureFlight.rect(Rect(40f, 900f, 340f, 1200f), fitted, 1f))
    }

    @Test
    fun `halfway is halfway on every edge`() {
        val rect = PictureFlight.rect(Rect(0f, 0f, 100f, 100f), Rect(200f, 400f, 300f, 500f), 0.5f)
        assertEquals(100f, rect.left, 0.01f)
        assertEquals(200f, rect.top, 0.01f)
        assertEquals(200f, rect.right, 0.01f)
        assertEquals(300f, rect.bottom, 0.01f)
    }

    @Test
    fun `a tile that was never laid out is no flight at all`() {
        assertEquals(fitted, PictureFlight.rect(null, fitted, 0f))
        assertEquals(fitted, PictureFlight.rect(Rect.Zero, fitted, 0.3f))
    }

    @Test
    fun `a fitted picture is centred in a taller frame`() {
        // 16:9 in a 9:16 window: the width decides, and the height is the picture's own.
        val rect = PictureFlight.centred(Size(300f, 168.75f), Size(300f, 600f))
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(215.62f, rect.top, 0.01f)
        assertEquals(300f, rect.right, 0.01f)
        assertEquals(384.37f, rect.bottom, 0.01f)
    }

    @Test
    fun `the ground is solid at rest and lighter under the finger`() {
        assertEquals(1f, PictureFlight.groundAlpha(1f, 0f, 120f), 0.001f)
        assertTrue(PictureFlight.groundAlpha(1f, 60f, 120f) < 1f)
        // Past twice the distance the ground has gone as far as it goes.
        assertEquals(
            PictureFlight.groundAlpha(1f, 240f, 120f),
            PictureFlight.groundAlpha(1f, 600f, 120f),
            0.001f,
        )
    }

    @Test
    fun `the chrome is gone by the distance that closes`() {
        assertEquals(1f, PictureFlight.chromeAlpha(1f, 0f, 120f), 0.001f)
        assertEquals(0f, PictureFlight.chromeAlpha(1f, 120f, 120f), 0.001f)
        assertEquals(0.5f, PictureFlight.chromeAlpha(1f, 60f, 120f), 0.001f)
    }

    @Test
    fun `a long slow drag closes and a short one does not`() {
        assertTrue(PictureFlight.dismisses(drag = 200f, velocity = 0f, 120f, 900f))
        assertFalse(PictureFlight.dismisses(drag = 80f, velocity = 0f, 120f, 900f))
    }

    @Test
    fun `a flick closes before the distance is reached`() {
        assertTrue(PictureFlight.dismisses(drag = 60f, velocity = 1400f, 120f, 900f))
        assertFalse(PictureFlight.dismisses(drag = 60f, velocity = 500f, 120f, 900f))
    }
}
