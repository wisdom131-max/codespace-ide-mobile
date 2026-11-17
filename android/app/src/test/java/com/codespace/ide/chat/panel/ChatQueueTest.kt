package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2/3 tests: the queue is FIFO and bounded, and steering rides into the next request instead
 * of restarting a turn. Both are pure, so these assert behaviour rather than the UI.
 */
class ChatQueueTest {

    @Test
    fun `queue is first in first out`() {
        var q = emptyList<String>()
        q = ChatQueue.enqueue(q, "first")
        q = ChatQueue.enqueue(q, "second")
        q = ChatQueue.enqueue(q, "third")
        assertEquals(listOf("first", "second", "third"), q)
        assertEquals("first", ChatQueue.peek(q))
        q = q.drop(1)
        assertEquals("second", ChatQueue.peek(q))
    }

    @Test
    fun `blank text is never queued`() {
        val q = ChatQueue.enqueue(emptyList(), "   ")
        assertTrue(q.isEmpty())
        assertNull(ChatQueue.accept("  "))
        assertEquals("hi", ChatQueue.accept("  hi  "))
    }

    @Test
    fun `an identical repeat is refused instead of queued twice`() {
        val once = ChatQueue.enqueue(emptyList(), "run the tests")
        val twice = ChatQueue.enqueue(once, "run the tests")
        assertTrue("the same list is returned so the caller knows nothing changed", once === twice)
        assertEquals(1, twice.size)
    }

    @Test
    fun `the queue refuses past its cap`() {
        var q = emptyList<String>()
        repeat(ChatQueue.MAX) { q = ChatQueue.enqueue(q, "message " + it) }
        assertEquals(ChatQueue.MAX, q.size)
        val overflow = ChatQueue.enqueue(q, "one too many")
        assertTrue(overflow === q)
    }

    @Test
    fun `dropping a waiting message leaves the rest in order`() {
        val q = listOf("a", "b", "c")
        assertEquals(listOf("a", "c"), ChatQueue.drop(q, 1))
        assertEquals(q, ChatQueue.drop(q, 9))
    }

    @Test
    fun `the notice counts waiting messages and says nothing when empty`() {
        assertNull(ChatQueue.notice(emptyList()))
        assertTrue(ChatQueue.notice(listOf("one"))!!.startsWith("1 message queued"))
        assertTrue(ChatQueue.notice(listOf("a", "b"))!!.startsWith("2 messages queued"))
    }

    @Test
    fun `steering is carried in front of the tool results with an explicit marker`() {
        val payload = "{\"results\":[]}"
        val steered = Steering.apply(payload, "stop editing, just tell me the plan")
        assertTrue(steered.startsWith(Steering.MARK))
        assertTrue(steered.endsWith(payload))
        assertTrue(steered.contains("stop editing, just tell me the plan"))
    }

    @Test
    fun `steering never claims delivery when nothing is running`() {
        assertTrue(Steering.notice(Steering.Delivery.NO_ACTIVE_TURN).contains("nothing to steer"))
        assertTrue(Steering.notice(Steering.Delivery.AT_NEXT_BOUNDARY).contains("next step"))
        assertFalse(Steering.notice(Steering.Delivery.AT_NEXT_BOUNDARY).contains("applied already"))
    }

    @Test
    fun `blank steering is refused`() {
        assertNull(Steering.accept("  \n "))
    }
}
