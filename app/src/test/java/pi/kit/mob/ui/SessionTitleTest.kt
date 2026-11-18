package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import pi.kit.mob.locales.EnglishStrings
import pi.kit.mob.pi.PiAgentSession

/**
 * What a conversation row is called.
 *
 * The list is the one place a conversation is identified without being opened, so the
 * label has to be true of the *first* turn: a prompt that was only a picture is an
 * ordinary conversation, and calling it "Empty conversation" was the app disagreeing with
 * what the reader had just done.
 */
class SessionTitleTest {

    private fun summary(title: String, images: Int = 0, files: Int = 0) =
        PiAgentSession.SessionSummary(
            path = "/tmp/session.jsonl",
            title = title,
            hasExplicitName = false,
            messageCount = 1,
            modifiedAt = 0L,
            pinned = false,
            imageCount = images,
            fileCount = files,
        )

    @Test
    fun `words name the conversation`() {
        assertEquals("Hello", summary("Hello").displayTitle(EnglishStrings))
    }

    @Test
    fun `a first turn that was only a picture is named by its count`() {
        assertEquals("1 image", summary("", images = 1).displayTitle(EnglishStrings))
    }

    @Test
    fun `a first turn that was only files is named by their count`() {
        assertEquals("2 files", summary("", files = 2).displayTitle(EnglishStrings))
    }

    @Test
    fun `a transcript with nothing in it keeps the empty label`() {
        assertEquals("Empty conversation", summary("").displayTitle(EnglishStrings))
    }
}
