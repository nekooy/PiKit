package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser that strips a prompt's machinery out of the bubble.
 *
 * File paths and pi's `[Image: …]` notes travel with the prompt so the model can
 * open the file or map coordinates; the reader typed neither. Getting the split
 * wrong shows either a wall of paths where the sentence should be, or a sentence
 * with a hole in it where the paths were.
 */
class PromptExtrasTest {

    @Test
    fun `paths at the front are collected and removed`() {
        val raw = """
            These files are ready; read them by path:
            /data/data/pi.kit.mob/cache/pikit-attachments/report.pdf
            /data/data/pi.kit.mob/cache/pikit-attachments/notes.txt

            Summarise these.
        """.trimIndent()

        val extras = parsePromptExtras(raw)

        assertEquals("Summarise these.", extras.displayText)
        assertEquals(
            listOf(
                "/data/data/pi.kit.mob/cache/pikit-attachments/report.pdf",
                "/data/data/pi.kit.mob/cache/pikit-attachments/notes.txt",
            ),
            extras.filePaths,
        )
        assertTrue(extras.imageNotes.isEmpty())
    }

    @Test
    fun `paths at the end — the older format — are collected too`() {
        val raw = """
            Summarise these.

            These files are ready; read them by path:
            /tmp/a.pdf
        """.trimIndent()

        val extras = parsePromptExtras(raw)

        assertEquals("Summarise these.", extras.displayText)
        assertEquals(listOf("/tmp/a.pdf"), extras.filePaths)
    }

    @Test
    fun `the Chinese header is recognised`() {
        val raw = """
            以下文件已就绪，请按路径读取：
            /tmp/a.pdf

            总结一下。
        """.trimIndent()

        val extras = parsePromptExtras(raw)

        assertEquals("总结一下。", extras.displayText)
        assertEquals(listOf("/tmp/a.pdf"), extras.filePaths)
    }

    @Test
    fun `image notes are lifted out wherever they sit`() {
        val raw =
            "Look at this [Image: original 1200x2670, displayed at 899x2000. " +
                "Multiply coordinates by 1.33 to map to original image.] and that."

        val extras = parsePromptExtras(raw)

        assertEquals("Look at this and that.", extras.displayText)
        assertEquals(1, extras.imageNotes.size)
        assertTrue(extras.imageNotes[0].startsWith("[Image:"))
    }

    @Test
    fun `both extras together`() {
        val raw = """
            These files are ready; read them by path:
            /tmp/a.pdf

            Check the chart [Image: original 10x10, displayed at 10x10.]
        """.trimIndent()

        val extras = parsePromptExtras(raw)

        assertEquals("Check the chart", extras.displayText.trim())
        assertEquals(listOf("/tmp/a.pdf"), extras.filePaths)
        assertEquals(1, extras.imageNotes.size)
        assertTrue(extras.hasExtras)
    }

    @Test
    fun `a plain prompt is left alone`() {
        val extras = parsePromptExtras("Just a question?")

        assertEquals("Just a question?", extras.displayText)
        assertFalse(extras.hasExtras)
    }

    @Test
    fun `a path the reader typed is not stolen`() {
        // No app-written header, so the path is the reader's own sentence.
        val extras = parsePromptExtras("Please read /tmp/a.pdf and summarise.")

        assertEquals("Please read /tmp/a.pdf and summarise.", extras.displayText)
        assertTrue(extras.filePaths.isEmpty())
    }
}
