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
}
