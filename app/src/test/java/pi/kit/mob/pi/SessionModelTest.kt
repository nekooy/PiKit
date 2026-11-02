package pi.kit.mob.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a session replacement has to be told, and why it is not what pi answers by itself.
 *
 * `new_session` rebuilds pi's runtime and builds the session's model from the **launch**
 * arguments, so a conversation switched to another profile with `set_model` comes back on
 * the launch-time profile the moment a new conversation starts. Measured on the emulator:
 * the picker was used at 17:24:58 and `get_state` named `pikit-custom-relayb`; a new
 * conversation was started, and at 17:25:08 the reply named `pikit-custom-relaya` again.
 * The next prompt then carried the launch profile's key — `401 … 无效的令牌` when that key
 * had been rotated away, which is the report this rule answers.
 *
 * The rule is pure, so the four cases are pinned without a process: state the model when
 * the two disagree, and stay out of the way when they already agree or when the app has
 * nothing to state.
 */
class SessionModelTest {

    @Test
    fun `a new session that fell back to the launch profile is stated back`() {
        assertEquals(
            "pikit-custom-relayb" to "deepseek-flash",
            modelToState(
                runningProvider = "pikit-custom-relaya",
                runningModel = "deepseek-flash",
                wantedProvider = "pikit-custom-relayb",
                wantedModel = "deepseek-flash",
            ),
        )
    }

    @Test
    fun `the same model id under another provider is a change`() {
        // The chip names only the id, which is the reason this case is the invisible one:
        // two profiles of one relay serve the same ids, so nothing on screen moved.
        assertEquals(
            "deepseek" to "deepseek-flash",
            modelToState(
                runningProvider = "pikit-custom-relayb",
                runningModel = "deepseek-flash",
                wantedProvider = "deepseek",
                wantedModel = "deepseek-flash",
            ),
        )
    }

    @Test
    fun `a session already on the app's model is left alone`() {
        assertNull(
            modelToState(
                runningProvider = "pikit-custom-relayb",
                runningModel = "deepseek-flash",
                wantedProvider = "pikit-custom-relayb",
                wantedModel = "deepseek-flash",
            ),
        )
    }

    @Test
    fun `nothing is stated when the app has no model of its own`() {
        // A profile with no model chosen launches without `--model` and without a key on
        // argv; pi's own default then answers, and this app has no pair to state.
        assertNull(
            modelToState(
                runningProvider = "deepseek",
                runningModel = "deepseek-v4-pro",
                wantedProvider = "deepseek",
                wantedModel = "",
            ),
        )
        assertNull(
            modelToState(
                runningProvider = "deepseek",
                runningModel = "deepseek-v4-pro",
                wantedProvider = null,
                wantedModel = "deepseek-flash",
            ),
        )
        // The placeholder pi reports when nothing is authenticated names no provider to
        // switch to, so it is not a disagreement either.
        assertNull(
            modelToState(
                runningProvider = null,
                runningModel = null,
                wantedProvider = null,
                wantedModel = null,
            ),
        )
    }
}
