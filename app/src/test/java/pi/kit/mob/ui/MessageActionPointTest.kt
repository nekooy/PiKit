package pi.kit.mob.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pi.kit.mob.pi.ChatItem

class MessageActionPointTest {
    @Test
    fun `an answer targets its own prompt even when steps are folded`() {
        val first = ChatItem.User("question-1", "First question")
        val earlier = ChatItem.Assistant("answer-1", "First answer")
        val second = ChatItem.User("question-2", "Second question")
        val hidden = ChatItem.Assistant("step-2", thinking = "Think first")
        val notice = ChatItem.Notice("info", "Compaction finished")
        val answer = ChatItem.Assistant("answer-2", "Second answer")
        val items = listOf(first, earlier, second, hidden, notice, answer)

        assertEquals(MessageActionPoint(answer, 5, second, 2), messageActionPoint(items, answer.key))
        assertEquals(MessageActionPoint(first, 1, first, 0), messageActionPoint(items, first.key))
        assertNull(messageActionPoint(items, notice.key))
        assertNull(messageActionPoint(items, "recycled"))
    }
}
