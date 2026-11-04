package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which session a relaunch rebinds to.
 *
 * pi opens a **new** session on every `pi --mode rpc` start. Restoring the one
 * the user was in is what keeps a Retry after 切后台/锁屏 from silently starting
 * a different conversation in the same UI — the report this rule exists for.
 * Pure, so the three cases are pinned without a process. The cold-start
 * preference is the one case that skips that restore entirely; see
 * [shouldRestoreRememberedSession].
 */
class SessionRestoreTest {

    private val onDisk = setOf("/sessions/a.jsonl", "/sessions/b.jsonl")

    private fun exists(path: String) = path in onDisk

    @Test
    fun `a remembered session that still exists is restored`() {
        assertEquals(
            "/sessions/a.jsonl",
            sessionRestoreTarget(
                remembered = "/sessions/a.jsonl",
                opened = "/sessions/brand-new.jsonl",
                exists = ::exists,
            ),
        )
    }

    @Test
    fun `the session pi already opened is left alone`() {
        assertNull(
            sessionRestoreTarget(
                remembered = "/sessions/a.jsonl",
                opened = "/sessions/a.jsonl",
                exists = ::exists,
            ),
        )
    }

    @Test
    fun `a deleted session is not chased`() {
        assertNull(
            sessionRestoreTarget(
                remembered = "/sessions/gone.jsonl",
                opened = "/sessions/brand-new.jsonl",
                exists = ::exists,
            ),
        )
    }

    @Test
    fun `a cold start with nothing remembered keeps pi's session`() {
        assertNull(
            sessionRestoreTarget(
                remembered = null,
                opened = "/sessions/brand-new.jsonl",
                exists = ::exists,
            ),
        )
        assertNull(
            sessionRestoreTarget(
                remembered = "",
                opened = "/sessions/brand-new.jsonl",
                exists = ::exists,
            ),
        )
    }

    @Test
    fun `a cold start that opens a new conversation skips the restore`() {
        assertFalse(shouldRestoreRememberedSession(openNewOnColdStart = true, coldStart = true))
    }

    @Test
    fun `a cold start that continues the talk restores as before`() {
        assertTrue(shouldRestoreRememberedSession(openNewOnColdStart = false, coldStart = true))
    }

    @Test
    fun `an agent restart restores even when a cold start would open new`() {
        assertTrue(shouldRestoreRememberedSession(openNewOnColdStart = true, coldStart = false))
        assertTrue(shouldRestoreRememberedSession(openNewOnColdStart = false, coldStart = false))
    }

    @Test
    fun `a pending restore keeps the file the drawer is marking`() {
        // The handshake names the file pi opened for itself; the row the reader tapped
        // names the one on screen. Keeping pi's name for the length of the switch is
        // what made the row's fill disappear and come back.
        assertEquals(
            "/sessions/a.jsonl",
            sessionFileAfterHandshake(
                shown = "/sessions/a.jsonl",
                reported = "/sessions/brand-new.jsonl",
                restoring = true,
            ),
        )
    }

    @Test
    fun `a launch with nothing to restore follows pi's own session`() {
        assertEquals(
            "/sessions/brand-new.jsonl",
            sessionFileAfterHandshake(
                shown = "/sessions/a.jsonl",
                reported = "/sessions/brand-new.jsonl",
                restoring = false,
            ),
        )
    }

    @Test
    fun `a restore pi did not need to make still follows the handshake`() {
        // `sessionRestoreTarget` answers null when pi opened the very file that was
        // wanted; there is no switch in flight and the handshake is the truth.
        assertEquals(
            "/sessions/a.jsonl",
            sessionFileAfterHandshake(
                shown = "/sessions/a.jsonl",
                reported = "/sessions/a.jsonl",
                restoring = false,
            ),
        )
    }

    @Test
    fun `a first conversation has no shown file to keep`() {
        assertNull(
            sessionFileAfterHandshake(
                shown = null,
                reported = "/sessions/brand-new.jsonl",
                restoring = true,
            ),
        )
    }
}
