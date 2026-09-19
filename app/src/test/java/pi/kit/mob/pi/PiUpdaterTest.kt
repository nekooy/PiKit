package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the update button actually runs.
 *
 * It used to run a bare `pi update`, which pi documents as *pi only*: it prints
 * `Extensions are skipped. Run pi update --extensions to update extensions.` into a
 * log nobody reads, so an installed extension never moved and the model catalogue was
 * refreshed only by the launch path. The report named exactly that — "pi and its
 * extensions are never updated in time" — and the fix is two commands rather than one,
 * because pi's own argument parser refuses to combine them.
 *
 * Small surface, high cost when wrong, and invisible from the outside: an update that
 * only updates pi looks like a successful update.
 */
class PiUpdaterTest {

    @Test
    fun `an update updates pi and the installed extensions, then the catalogue`() {
        val phases = piUpdatePhases(force = false)

        assertEquals(
            "pi and its installed packages first, then the model catalogue",
            listOf(listOf("update", "--all"), listOf("update", "--models")),
            phases,
        )
    }

    @Test
    fun `a forced update carries the flag on the first command only`() {
        val phases = piUpdatePhases(force = true)

        assertEquals(listOf("update", "--all", "--force"), phases.first())
        // `--models` is a different target: pi's parser rejects `--force` there, and a
        // catalogue refresh has nothing to force anyway.
        assertEquals(listOf("update", "--models"), phases.last())
    }

    @Test
    fun `the two targets are never combined, because pi refuses that`() {
        // `--models cannot be combined with --self, --extensions, --all, or
        // --extension` — from pi's own usage text. One command carrying both would
        // fail outright, and the failure would land as "update exited with code 1".
        piUpdatePhases(force = true).forEach { phase ->
            assertFalse("$phase mixes two targets", phase.contains("--all") && phase.contains("--models"))
        }
    }

    @Test
    fun `nothing in an update is offline`() {
        // `PI_OFFLINE` gates the version lookup: an update that cannot ask what the
        // latest version is fails at the first step. The variable is not in the
        // environment this builds, and no phase may reintroduce the flag either.
        piUpdatePhases(force = true).forEach { phase ->
            assertTrue("$phase must not be offline", phase.none { it == "--offline" })
        }
    }
}
