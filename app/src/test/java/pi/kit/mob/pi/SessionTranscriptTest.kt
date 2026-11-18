package pi.kit.mob.pi

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a saved conversation's pictures back out of its session file.
 *
 * This is what lets a conversation that predates the picture cache — or one whose cache
 * entries were pruned — show its pictures again instead of the count line. The scan is
 * driven by what the transcript is asking for, so the two facts to pin are that it finds
 * only a *prompt's* pictures (an assistant message never carries one) and that it stops
 * the moment the caller stops wanting more.
 */
class SessionTranscriptTest {

    private fun session(vararg lines: String): java.io.File {
        val file = Files.createTempFile("pikit-session", ".jsonl").toFile()
        file.writeText(lines.joinToString("\n"))
        return file
    }

    /** The record shape pi writes: one message per line, with a wrapper around it. */
    private fun message(json: String, at: Long) =
        """{"type":"message","timestamp":$at,"message":$json}"""

    @Test
    fun `reads the pictures the prompts carried, in the order they were sent`() {
        val file = session(
            """{"type":"session","version":1,"id":"01","cwd":"/data/data/pi.kit.mob/files/home"}""",
            message(
                """{"role":"user","content":[{"type":"text","text":"what is this"},""" +
                    """{"type":"image","data":"AAAA","mimeType":"image/png"}]}""",
                1000,
            ),
            // An assistant message never carries a picture, and a tool result may hold
            // anything at all: neither is a prompt's picture.
            message(
                """{"role":"assistant","content":[{"type":"image","data":"ZZZZ","mimeType":"image/png"}]}""",
                2000,
            ),
            message(
                """{"role":"user","content":[{"type":"image","data":"BBBB","mimeType":"image/jpeg"}]}""",
                3000,
            ),
        )

        val seen = mutableListOf<String>()
        SessionTranscript.forEachPromptPicture(file) { base64, mimeType ->
            seen += "$base64:$mimeType"
            true
        }

        assertEquals(listOf("AAAA:image/png", "BBBB:image/jpeg"), seen)
        file.delete()
    }

    @Test
    fun `stops as soon as the caller has what it asked for`() {
        // A long conversation is megabytes of base64: a scan that found its picture on
        // the first prompt must not read the rest of the file to prove it.
        val file = session(
            message(
                """{"role":"user","content":[{"type":"image","data":"AAAA","mimeType":"image/png"}]}""",
                1000,
            ),
            message(
                """{"role":"user","content":[{"type":"image","data":"BBBB","mimeType":"image/png"}]}""",
                2000,
            ),
        )

        var calls = 0
        SessionTranscript.forEachPromptPicture(file) { _, _ ->
            calls++
            false
        }

        assertEquals(1, calls)
        file.delete()
    }

    @Test
    fun `a file that is not a session yields nothing and does not throw`() {
        val file = session("not json at all", "", """{"type":"message_start"}""")
        val seen = mutableListOf<String>()
        SessionTranscript.forEachPromptPicture(file) { base64, _ ->
            seen += base64
            true
        }
        assertTrue(seen.isEmpty())

        // A path that was deleted between the drawer row and the tap: the caller draws
        // the count line, and nothing is thrown at it.
        file.delete()
        SessionTranscript.forEachPromptPicture(file) { _, _ -> error("never called") }
    }
}
