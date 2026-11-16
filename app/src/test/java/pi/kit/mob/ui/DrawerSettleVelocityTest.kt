package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release velocity a drawer settle may carry.
 *
 * The bug this pins is a boundary bounce: the drawer offset is clamped while a finger
 * is down, but a spring told to settle at the same clamped position with the finger's
 * speed still attached travels past the edge and springs back. It is invisible in a
 * still screenshot and easy to reintroduce by passing `velocity` straight through.
 */
class DrawerSettleVelocityTest {

    @Test
    fun `a pull at the closed edge carries no velocity`() {
        assertEquals(0f, drawerSettleVelocity(fromPx = 0f, targetPx = 0f, velocity = -2400f))
    }

    @Test
    fun `a pull at the open edge carries no velocity`() {
        assertEquals(0f, drawerSettleVelocity(fromPx = 800f, targetPx = 800f, velocity = 2400f))
    }

    @Test
    fun `a normal opening flick keeps its velocity`() {
        assertEquals(1800f, drawerSettleVelocity(fromPx = 200f, targetPx = 800f, velocity = 1800f))
    }

    @Test
    fun `a release that points away from the target is dropped`() {
        assertEquals(0f, drawerSettleVelocity(fromPx = 240f, targetPx = 0f, velocity = 900f))
    }
}
