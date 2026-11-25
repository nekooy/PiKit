package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The count a branch stops at, pinned without a process.
 *
 * `0` is the branch point that regenerates a first turn; the old loop copied the
 * first message into the copy, so the regenerated prompt appeared twice.
 */
class ForkRecordsTest {

    private val header = """{"type":"session","version":1,"id":"a","cwd":"/tmp"}"""
    private val user = """{"type":"message","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}"""
    private val answer = """{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"hello"}]}}"""
    private val lines = listOf(header, user, answer)

    @Test
    fun `zero keeps the header and no message`() {
        assertEquals(listOf(header), forkRecords(lines, 0))
    }

    @Test
    fun `one keeps the first message`() {
        assertEquals(listOf(header, user), forkRecords(lines, 1))
    }

    @Test
    fun `two keeps both messages`() {
        assertEquals(lines, forkRecords(lines, 2))
    }

    @Test
    fun `past the end is refused`() {
        assertNull(forkRecords(lines, 3))
    }
}
