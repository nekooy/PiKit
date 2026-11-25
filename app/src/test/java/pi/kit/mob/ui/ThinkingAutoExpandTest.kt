package pi.kit.mob.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.pi.ReplyStatus

/**
 * The personalization switch's two moments, pinned without a provider stream.
 *
 * A real stream is the only way to see the block move, but the decision itself is one
 * line and it is the part that can be wrong: only the current step opens, only while it
 * is producing reasoning, and the same call closes it once the step is done.
 */
class ThinkingAutoExpandTest {

    @Test
    fun `a streaming step with thinking opens when the switch is on`() {
        assertTrue(shouldExpandThinking(true, ReplyStatus.Streaming, hasThinking = true))
    }

    @Test
    fun `a finished step folds`() {
        assertFalse(shouldExpandThinking(true, ReplyStatus.Done, hasThinking = true))
        assertFalse(shouldExpandThinking(true, ReplyStatus.Stopped, hasThinking = true))
        assertFalse(shouldExpandThinking(true, ReplyStatus.Error, hasThinking = true))
    }

    @Test
    fun `no thinking yet leaves the block closed`() {
        assertFalse(shouldExpandThinking(true, ReplyStatus.Streaming, hasThinking = false))
    }

    @Test
    fun `the switch off never opens by itself`() {
        assertFalse(shouldExpandThinking(false, ReplyStatus.Streaming, hasThinking = true))
    }
}
