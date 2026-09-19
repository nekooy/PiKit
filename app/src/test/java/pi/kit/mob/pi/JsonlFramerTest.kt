package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlFramerTest {

    private fun collect(framer: JsonlFramer, vararg chunks: ByteArray): List<String> {
        val out = ArrayList<String>()
        for (chunk in chunks) framer.feed(chunk) { out += it }
        framer.end { out += it }
        return out
    }

    @Test
    fun `splits simple records on LF`() {
        val records = collect(JsonlFramer(), "{\"a\":1}\n{\"b\":2}\n".toByteArray())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), records)
    }

    @Test
    fun `emits trailing record without newline on end`() {
        val records = collect(JsonlFramer(), "{\"a\":1}\n{\"b\":2}".toByteArray())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), records)
    }

    /**
     * pi emits U+2028/U+2029 raw inside JSON strings. A reader built on
     * readLine()/String.lines()/Scanner would split this into three records.
     */
    @Test
    fun `does not split on unicode line separators`() {
        val payload = "{\"type\":\"session_info_changed\",\"name\":\"ls\u2028sep\u2029here\"}"
        val records = collect(JsonlFramer(), (payload + "\n").toByteArray())
        assertEquals(1, records.size)
        assertEquals(payload, records[0])
        assertTrue("U+2028 must survive", records[0].contains('\u2028'))
        assertTrue("U+2029 must survive", records[0].contains('\u2029'))
    }

    /**
     * A live capture delivered two JSON lines in a single 64-byte read, so the
     * framer must never assume one read == one record.
     */
    @Test
    fun `handles several records coalesced into one read`() {
        val records = collect(
            JsonlFramer(),
            "{\"id\":\"s1\",\"type\":\"response\",\"success\":true}\n{\"type\":\"agent_start\"}\n".toByteArray(),
        )
        assertEquals(2, records.size)
    }

    /** A record can also be split arbitrarily across reads. */
    @Test
    fun `handles a record split across many reads`() {
        val payload = "{\"hello\":\"world\",\"n\":123}"
        val framer = JsonlFramer()
        val out = ArrayList<String>()
        for (byte in (payload + "\n").toByteArray()) {
            framer.feed(byteArrayOf(byte)) { out += it }
        }
        framer.end { out += it }
        assertEquals(listOf(payload), out)
    }

    /**
     * Multi-byte UTF-8 must be decoded incrementally. Feeding one byte at a
     * time guarantees every character straddles a read boundary.
     */
    @Test
    fun `decodes multibyte characters split across reads`() {
        val payload = "{\"t\":\"中文テスト 🎈 éüß\"}"
        val framer = JsonlFramer()
        val out = ArrayList<String>()
        val bytes = (payload + "\n").toByteArray(Charsets.UTF_8)
        for (byte in bytes) framer.feed(byteArrayOf(byte)) { out += it }
        framer.end { out += it }
        assertEquals(listOf(payload), out)
    }

    @Test
    fun `tolerates CRLF input by stripping one trailing CR`() {
        val records = collect(JsonlFramer(), "{\"a\":1}\r\n{\"b\":2}\r\n".toByteArray())
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), records)
    }

    @Test
    fun `preserves an interior carriage return inside a string`() {
        val payload = "{\"a\":\"x\\r\\ny\"}"
        val records = collect(JsonlFramer(), (payload + "\n").toByteArray())
        assertEquals(listOf(payload), records)
    }

    @Test
    fun `keeps empty JSON strings and braces intact`() {
        val records = collect(JsonlFramer(), "{}\n\n{\"a\":\"\"}\n".toByteArray())
        // Blank lines are dropped rather than surfaced as malformed records.
        assertEquals(listOf("{}", "{\"a\":\"\"}"), records)
    }

    @Test
    fun `handles large payloads without losing data`() {
        val big = "x".repeat(200_000)
        val payload = "{\"type\":\"message_update\",\"delta\":\"$big\"}"
        val records = collect(JsonlFramer(), (payload + "\n").toByteArray())
        assertEquals(1, records.size)
        assertEquals(payload, records[0])
    }

    @Test
    fun `end is idempotent`() {
        val framer = JsonlFramer()
        val out = ArrayList<String>()
        framer.feed("{\"a\":1}\n".toByteArray()) { out += it }
        framer.end { out += it }
        framer.end { out += it }
        assertEquals(listOf("{\"a\":1}"), out)
    }
}
