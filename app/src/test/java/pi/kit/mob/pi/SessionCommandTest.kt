package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two answers pi gives that are not failures and not successes either.
 *
 * Both are `success: true` with a payload that changes what the app must do, and both
 * were ignored: a session switch an extension refused, reported as a switch that
 * happened, and the queue an abort leaves behind, which is what made the Stop button
 * look like it did not stop. Parsed through [PiRecordParser] rather than built as
 * objects, so the shapes come from the wire format `docs/rpc.md` documents.
 */
class SessionCommandTest {

    private fun response(line: String) = PiRecordParser.parse(line) as PiRecord.Response

    @Test
    fun `a refused session switch is recognised`() {
        // An extension's `session_before_switch` handler said no. pi answers the same
        // command with the same success flag and a `cancelled` payload.
        assertTrue(
            response(
                """{"id":"r1","type":"response","command":"switch_session","success":true,""" +
                    """"data":{"cancelled":true}}""",
            ).cancelled(),
        )
        assertTrue(
            response(
                """{"id":"r2","type":"response","command":"new_session","success":true,""" +
                    """"data":{"cancelled":true}}""",
            ).cancelled(),
        )
    }

    @Test
    fun `a switch that went through is not`() {
        assertFalse(
            response(
                """{"id":"r1","type":"response","command":"switch_session","success":true,""" +
                    """"data":{"cancelled":false}}""",
            ).cancelled(),
        )
        // Commands that answer with no data at all, and a command whose data is a
        // model object: absent is "it went through", which is the ordinary answer.
        assertFalse(
            response("""{"id":"r2","type":"response","command":"abort","success":true}""").cancelled(),
        )
        assertFalse(
            response(
                """{"id":"r3","type":"response","command":"set_model","success":true,""" +
                    """"data":{"id":"deepseek-flash","provider":"deepseek"}}""",
            ).cancelled(),
        )
    }

    @Test
    fun `the messages an abort drops are read back, steering first`() {
        val cleared = response(
            """{"id":"r1","type":"response","command":"clear_queue","success":true,"data":{""" +
                """"steering":["Do this instead"],"followUp":["And then summarize"]}}""",
        )

        assertEquals("Do this instead\nAnd then summarize", clearedQueueText(cleared))
    }

    @Test
    fun `an empty queue returns nothing to restore`() {
        // pi answers a successful `clear_queue` with two empty arrays when there was
        // nothing queued, and the composer must not be written to for that.
        assertNull(
            clearedQueueText(
                response(
                    """{"id":"r1","type":"response","command":"clear_queue","success":true,""" +
                        """"data":{"steering":[],"followUp":[]}}""",
                ),
            ),
        )
        assertNull(clearedQueueText(null))
        // A rejected `clear_queue` is not a source of text either: the messages are
        // still in pi's queue and will be delivered.
        assertNull(
            clearedQueueText(
                response(
                    """{"id":"r2","type":"response","command":"clear_queue","success":false,""" +
                        """"error":"nope"}""",
                ),
            ),
        )
        assertNull(
            clearedQueueText(
                response(
                    """{"id":"r3","type":"response","command":"clear_queue","success":true,""" +
                        """"data":{"steering":["  "],"followUp":[]}}""",
                ),
            ),
        )
    }
}
