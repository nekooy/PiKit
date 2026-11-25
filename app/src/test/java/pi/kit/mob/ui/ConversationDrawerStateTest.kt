package pi.kit.mob.ui

import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDrawerStateTest {
    @Test
    fun `one drag can cross both edges and reverse without a new down`() {
        val drawer = ConversationDrawerState().apply { width = 400 }
        assertEquals(PageDragDirection.Right, drawer.allowedDirection())
        val owner = drawer.beginDrag()
        drawer.dragBy(owner, 500f)
        assertEquals(1f, drawer.progress, 0f)
        assertEquals(PageDragDirection.Left, drawer.allowedDirection())
        drawer.dragBy(owner, -450f)
        assertFalse(drawer.visible)
        drawer.dragBy(owner, 150f)
        assertEquals(0.375f, drawer.progress, 0f)
        assertEquals(PageDragDirection.Both, drawer.allowedDirection())
        assertTrue(drawer.visible)
    }

    @Test
    fun `resize preserves the current fraction and the drag continues on the new width`() {
        val drawer = ConversationDrawerState().apply { width = 400 }
        val owner = drawer.beginDrag()
        drawer.dragBy(owner, 150f)
        drawer.width = 800
        assertEquals(300f, drawer.offset, 0f)
        drawer.dragBy(owner, -100f)
        assertEquals(200f, drawer.offset, 0f)
    }

    @Test
    fun `superseded gestures cannot move or settle the new owner`() {
        val drawer = ConversationDrawerState().apply { width = 400 }
        val older = drawer.beginDrag()
        drawer.dragBy(older, 150f)
        val newer = drawer.beginDrag()
        drawer.dragBy(newer, -100f)
        val scope = CoroutineScope(kotlin.coroutines.EmptyCoroutineContext)
        drawer.dragBy(older, 150f)
        drawer.releaseDrag(older, 150f, 3000f, 50f, scope)
        drawer.cancelDrag(older, scope)
        assertEquals(50f, drawer.offset, 0f)
        assertFalse(drawer.open)
        drawer.dragBy(newer, 150f)
        assertEquals(200f, drawer.offset, 0f)
    }

    @Test
    fun `a slow release follows position while a qualifying reversal follows velocity`() {
        assertFalse(drawerReleaseOpens(0.49f, 800, 100f, 0f, 50f))
        assertTrue(drawerReleaseOpens(0.50f, 800, -100f, 0f, 50f))
        assertTrue(drawerReleaseOpens(0.25f, 800, -160f, 1000f, 50f))
        assertFalse(drawerReleaseOpens(0.75f, 800, 160f, -1000f, 50f))
    }

    @Test
    fun `speed alone does not commit a flick before five percent travel`() {
        assertFalse(drawerReleaseOpens(0.3f, 800, 39f, 1000f, 50f))
        assertTrue(drawerReleaseOpens(0.3f, 800, 40f, 1000f, 50f))
        assertTrue(drawerReleaseOpens(0.7f, 800, -39f, -1000f, 50f))
        assertFalse(drawerReleaseOpens(0.7f, 800, -40f, -1000f, 50f))
    }
}
