package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pi.kit.mob.pi.ChatItem
import pi.kit.mob.ui.chat.FollowScrollEvent
import pi.kit.mob.ui.chat.FollowTailState

/**
 * The rule that decides whether the transcript chases a running answer.
 *
 * This is the piece of the chat screen that cannot be checked by looking: both
 * the follow and the reader's finger write to the same scroll offset, and the
 * only visible symptom of getting it wrong is that an answer "feels janky" —
 * which is exactly the complaint this was written to fix. A screenshot of the
 * bug and a screenshot of the fix are identical.
 *
 * The bug being pinned: the list scrolled to the last item on every streamed
 * token, unconditionally. A reader who scrolled up to read a command's output was
 * dragged back to the bottom a few milliseconds later, and could not read
 * anything above the tail while the agent was answering.
 *
 * One of these tests failed against the first implementation, which stopped
 * following on the frame a drag *began* at the bottom and then resumed on the
 * next frame of the same drag — the round trip is invisible in a unit test but is
 * a visible jitter on the device. That is why the rule reads the position rather
 * than the gesture's start, and why the tests walk whole gestures rather than
 * asserting on single frames.
 */
class FollowTailTest {

    /** One reading of the scroll state: scrolling? at the bottom? ours? */
    private fun event(
        scrolling: Boolean = false,
        atBottom: Boolean = true,
        programmatic: Boolean = false,
    ) = FollowScrollEvent(scrolling, atBottom, programmatic)

    private fun FollowTailState.feed(vararg events: FollowScrollEvent): FollowTailState =
        events.fold(this) { state, next -> state.onScroll(next) }

    /** Frames of an idle transcript whose content is still arriving. */
    private fun idleAtBottom() = arrayOf(
        event(scrolling = false, atBottom = true),
        event(scrolling = false, atBottom = true),
    )

    /** A reader's drag upward, from the tail to somewhere above it. */
    private fun dragUpAndRelease() = arrayOf(
        // The gesture begins at the bottom and has not moved yet.
        event(scrolling = true, atBottom = true),
        event(scrolling = true, atBottom = false),
        // Released, above the tail: this is where the answer is settled.
        event(scrolling = false, atBottom = false),
    )

    /** The same drag, this time returning to the tail before releasing. */
    private fun dragUpAndBack() = arrayOf(
        event(scrolling = true, atBottom = true),
        event(scrolling = true, atBottom = false),
        event(scrolling = true, atBottom = true),
        event(scrolling = false, atBottom = true),
    )

    @Test
    fun `a fresh transcript follows`() {
        assertTrue(FollowTailState().follows)
    }

    @Test
    fun `it keeps following while nothing is touched`() {
        assertTrue(FollowTailState().feed(*idleAtBottom()).follows)
    }

    @Test
    fun `a drag away from the bottom stops the following`() {
        assertFalse(FollowTailState().feed(*dragUpAndRelease()).follows)
    }

    @Test
    fun `it stays stopped after the reader lets go`() {
        // The frames after the release are the whole point: the offset stops
        // changing and reports "idle", and if that were read as "the reader is
        // here now" the follow would come straight back.
        val state = FollowTailState().feed(*dragUpAndRelease(), *idleAtBottom())
        assertFalse(state.follows)
    }

    @Test
    fun `it stays stopped while output keeps arriving`() {
        // A streamed token changes the list's content; the scroll events it
        // produces are not part of any gesture and must not move the answer.
        val state = FollowTailState().feed(
            *dragUpAndRelease(),
            event(scrolling = false, atBottom = false),
            event(scrolling = false, atBottom = false),
        )
        assertFalse(state.follows)
    }

    @Test
    fun `coming back to the bottom resumes the following`() {
        assertTrue(FollowTailState().feed(*dragUpAndBack()).follows)
    }

    @Test
    fun `a drag that never leaves the bottom keeps following`() {
        // A nudge that does not clear the last row: nothing is hidden, so there
        // is nothing to stop for.
        val state = FollowTailState().feed(
            event(scrolling = true, atBottom = true),
            event(scrolling = true, atBottom = true),
            event(scrolling = false, atBottom = true),
        )
        assertTrue(state.follows)
    }

    @Test
    fun `a fling is judged where it lands, not on the way`() {
        // Mid-fling the offset passes through the bottom on its way further up.
        // Reading that frame is the bug this ordering exists to prevent.
        val state = FollowTailState().feed(
            *dragUpAndRelease(),
            event(scrolling = true, atBottom = false),
            // The reader flings down, through the tail…
            event(scrolling = true, atBottom = true),
            // …and past it, to the very end of the list, where they release.
            event(scrolling = true, atBottom = false),
            event(scrolling = false, atBottom = false),
        )
        assertFalse(state.follows)
    }

    @Test
    fun `a fling that lands at the bottom resumes`() {
        val state = FollowTailState().feed(
            *dragUpAndRelease(),
            // The reader flings back down.
            event(scrolling = true, atBottom = false),
            event(scrolling = true, atBottom = true),
            event(scrolling = false, atBottom = true),
        )
        assertTrue(state.follows)
    }

    @Test
    fun `our own scroll is never read as the reader leaving`() {
        // `scrollToItem` sets `isScrollInProgress` exactly as a drag does. Without
        // the programmatic flag every follow would turn itself off on its own
        // first frame, which reads as "it followed once and then gave up".
        val state = FollowTailState().feed(
            event(scrolling = true, atBottom = true, programmatic = true),
            event(scrolling = false, atBottom = true, programmatic = false),
        )
        assertTrue(state.follows)
    }

    @Test
    fun `our own scrolling does not resume a reader who left`() {
        val state = FollowTailState().feed(
            *dragUpAndRelease(),
            // The jump-to-latest button's own animation, on its way down.
            event(scrolling = true, atBottom = false, programmatic = true),
            event(scrolling = true, atBottom = true, programmatic = true),
            event(scrolling = false, atBottom = true, programmatic = false),
        )
        assertFalse(state.follows)
    }

    // --------------------------------------------- what the follow effect keys on

    private fun assistant(text: String = "", thinking: String = "") =
        ChatItem.Assistant(key = "row", text = text, thinking = thinking)

    @Test
    fun `thinking that streams moves the tail key, with no answer text yet`() {
        // The bug this pins: the key was the answer's length alone, so a reasoning model's
        // whole thinking phase left it unchanged — no re-run, no snap to the end, and the
        // "back to the bottom" button on screen over a list that was only growing. The
        // effect is a `LaunchedEffect`, and its keys are the only thing that makes it run.
        val first = transcriptTailKey(listOf(assistant(thinking = "Let me think")))
        val more = transcriptTailKey(listOf(assistant(thinking = "Let me think about it")))

        assertNotEquals("thinking has to be part of the key", first, more)
    }

    @Test
    fun `answer text that streams moves the tail key`() {
        val first = transcriptTailKey(listOf(assistant(text = "The answer")))
        val more = transcriptTailKey(listOf(assistant(text = "The answer is 4")))

        assertNotEquals("so does the answer", first, more)
    }

    @Test
    fun `a new item moves the tail key, and an unchanged transcript does not`() {
        val one = listOf(assistant(text = "done"))
        assertEquals("no change, no re-run", transcriptTailKey(one), transcriptTailKey(one))
        assertNotEquals(
            "a new row is a change even with an identical-looking tail",
            transcriptTailKey(one),
            transcriptTailKey(one + assistant(text = "done")),
        )
        // A thinking-only item and an empty one are different states of the same row, which
        // is the case the old key could not tell apart from "nothing happened".
        assertEquals(
            transcriptTailKey(listOf(assistant(thinking = "x"))),
            transcriptTailKey(listOf(assistant(thinking = "x"))),
        )
    }
}
