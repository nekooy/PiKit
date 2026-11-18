package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name a prompt's picture is cached under.
 *
 * It is the seam between two paths that never meet — the send, which writes the copy,
 * and the transcript, which reads it — so the two properties that matter are that the
 * same input always gives the same name and that the name is a filename: lowercase hex,
 * fixed length, nothing a filesystem has to interpret.
 */
class ChatImagesTest {

    @Test
    fun `a key is a stable lowercase digest a filename can hold`() {
        val key = ChatImages.keyOf("AAAAAAAA")
        assertEquals(40, key.length)
        assertTrue("hex only", key.all { it in "0123456789abcdef" })
        assertEquals("derivable again, which is what a restore relies on", key, ChatImages.keyOf("AAAAAAAA"))
    }

    @Test
    fun `two different pictures have two different names`() {
        // The whole cache is one directory; a collision would draw one picture where the
        // other was sent, and it would look like the model's answer was about the wrong
        // image rather than like a bug.
        assertNotEquals(ChatImages.keyOf("AAAAAAAA"), ChatImages.keyOf("BBBBBBBB"))
        // A base64 body differs in one character between two similar screenshots.
        assertNotEquals(
            ChatImages.keyOf("iVBORw0KGgoAAAANSUhEUg"),
            ChatImages.keyOf("iVBORw0KGgoAAAANSUhEUh"),
        )
    }

    @Test
    fun `an image block with no payload still gets a name`() {
        // A session pi trimmed, or a shape this app has not met: the row draws a
        // placeholder rather than dropping a picture the reader did send.
        assertEquals(40, ChatImages.keyOf("").length)
    }
}
