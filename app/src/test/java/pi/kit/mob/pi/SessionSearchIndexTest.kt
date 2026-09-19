package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The cache's staleness rule, on the JVM.
 *
 * The expensive part of the content search is not the matching, it is reading and
 * parsing the transcript, and the cache is what keeps a keystroke from paying it
 * again. So the tests below are about *when* the file is read, and they observe it
 * the only way that needs no instrumentation: by changing the file to something
 * that would produce a different answer and then putting its timestamp back, which
 * leaves the cache's own key — stamp plus length — as the only thing that could
 * still tell the two versions apart.
 *
 * Restoring the stamp is what makes these tests mean anything. Writing the file
 * twice normally moves `lastModified()`, so an implementation that ignored the
 * cache entirely would pass a test that did not restore it.
 */
class SessionSearchIndexTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** A one-message transcript, shaped like pi's own session JSONL. */
    private fun transcript(text: String, id: String = "a1b2c3d4") =
        """{"type":"message","id":"$id","message":{"role":"user",""" +
            """"content":[{"type":"text","text":"$text"}]}}"""

    private fun transcriptFile(name: String, text: String): File =
        temporary.newFile(name).also { it.writeText(transcript(text)) }

    private fun stamp(file: File): Long = file.lastModified()

    /** Puts the clock back, so only a non-time property can reveal the change. */
    private fun restore(file: File, stamp: Long) {
        file.setLastModified(stamp)
        assertEquals(
            "this filesystem cannot round-trip a timestamp, so the test could not " +
                "tell a cached read from a fresh one",
            stamp,
            file.lastModified(),
        )
    }

    @Test
    fun `a transcript whose stamp and length are unchanged is not re-read`() {
        val file = transcriptFile("session.jsonl", "first draft")
        val stamp = stamp(file)
        val index = SessionSearchIndex()
        assertEquals("first draft", index.text(file))

        // Same number of bytes, different words: the length guard cannot see it,
        // which is the point — only the stamp decides here, and it has been put
        // back to what it was.
        val length = file.length()
        file.writeText(transcript("second text"))
        assertEquals("same length is the premise of this test", length, file.length())
        restore(file, stamp)

        assertEquals(
            "the cached text should still stand, not the file's new contents",
            "first draft",
            index.text(file),
        )
    }

    @Test
    fun `a transcript that grew is read again`() {
        val file = transcriptFile("session.jsonl", "short")
        val stamp = stamp(file)
        val index = SessionSearchIndex()
        assertEquals("short", index.text(file))

        // Appended, and the stamp put back to where it was. A transcript that grows
        // inside one filesystem timestamp tick is why the length is part of the
        // cache's key at all.
        file.appendText("\n" + transcript("a longer reply", id = "b2c3d4e5"))
        restore(file, stamp)

        val text = index.text(file)

        assertEquals("both messages should be indexed", "short\na longer reply", text)
    }

    @Test
    fun `retain forgets a conversation that is no longer listed`() {
        val kept = transcriptFile("kept.jsonl", "still here")
        val dropped = transcriptFile("dropped.jsonl", "first draft")
        val index = SessionSearchIndex()
        index.text(kept)
        assertEquals("first draft", index.text(dropped))

        index.retain(setOf(kept.absolutePath))

        // Now the same trick: only a forgotten entry can notice this change.
        val stamp = stamp(dropped)
        dropped.writeText(transcript("second text"))
        restore(dropped, stamp)

        assertEquals("a dropped conversation is read afresh", "second text", index.text(dropped))
        assertEquals("the kept conversation is untouched", "still here", index.text(kept))
    }
}
