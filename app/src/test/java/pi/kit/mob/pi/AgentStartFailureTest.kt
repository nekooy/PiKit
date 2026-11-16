package pi.kit.mob.pi

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a launch's throwable is allowed to become.
 *
 * Swiping the chat page away while a new conversation was starting used to paint
 * "rememberCoroutineScope left the composition" over the composer: Compose cancels the
 * page's scope with a `CancellationException`, and the launch's catch-all wrote its
 * message into `AgentStatus.Failed`. The throwable is a caller that left, not a pi that
 * refused; `agentStartFailure` is the one place that distinction is made.
 */
class AgentStartFailureTest {

    @Test
    fun `a caller that left composition is not an agent failure`() {
        val cancellation = CancellationException("rememberCoroutineScope left the composition")

        assertNull(
            "leaving the page cancels the launch, it does not fail the agent",
            agentStartFailure(cancellation),
        )
    }

    @Test
    fun `a real launch error keeps its sentence`() {
        val failure = agentStartFailure(IllegalStateException("models.json write failed"))

        assertEquals("models.json write failed", failure?.message)
    }
}
