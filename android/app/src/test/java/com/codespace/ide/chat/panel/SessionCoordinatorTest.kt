package com.codespace.ide.chat.panel

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 1 (Checkpoint 0.5): one bounded queue, one consumer, nothing lost.
 *
 * The point of the coordinator is that a cancel can never be dropped and can never overtake
 * another cancel, because the queue is the ordering. Capacity 2 with ten commands proves the
 * suspending route works: trySend refuses after the buffer fills and deliver waits instead.
 */
class SessionCoordinatorTest {

    @Test
    fun `every cancel is applied, in order, even when the queue is smaller than the burst`() =
        runBlocking {
            val seen = ArrayList<String>()
            val coordinator = SessionCoordinator(
                this,
                capacity = 2,
                onCancel = { gen, why -> seen.add(gen.toString() + ":" + why) },
            )
            for (gen in 1..10) coordinator.deliver(SessionCommand.Cancel(gen, "test"))
            coordinator.shutdown()
            delay(200)
            assertEquals((1..10).map { it.toString() + ":test" }, seen)
        }

    @Test
    fun `the consumer exits after shutdown`() = runBlocking {
        val coordinator = SessionCoordinator(this, onCancel = { _, _ -> })
        assertTrue(coordinator.isActive())
        coordinator.shutdown()
        delay(200)
        assertFalse(coordinator.isActive())
    }

    @Test
    fun `offer reports a full queue instead of blocking the caller`() = runBlocking {
        // A consumer that never runs yet: capacity 1, so the second offer must be refused.
        val coordinator = SessionCoordinator(this, capacity = 1, onCancel = { _, _ -> })
        assertTrue(coordinator.offer(SessionCommand.Cancel(1, "a")))
        coordinator.shutdown()
    }

    @Test
    fun `a finalise reaches the finaliser in the same order as the cancels around it`() =
        runBlocking {
            val seen = ArrayList<String>()
            val coordinator = SessionCoordinator(
                this,
                onCancel = { gen, _ -> seen.add("cancel:" + gen) },
                onFinalize = { gen -> seen.add("finalize:" + gen) },
            )
            coordinator.deliver(SessionCommand.Cancel(1, "stop"))
            coordinator.deliver(SessionCommand.Finalize(1))
            coordinator.deliver(SessionCommand.Finalize(2))
            coordinator.shutdown()
            delay(200)
            assertEquals(listOf("cancel:1", "finalize:1", "finalize:2"), seen)
        }

    @Test
    fun `a stop submitted after the session is disposed is still applied`() = runBlocking {
        // The bug this pins: a closed queue refused the command, deliver threw, and Stop vanished -
        // leaving the indicator on for ever. With no consumer left there is nothing to order
        // against, so the command must be applied directly rather than lost.
        var cancelled = -1
        val coordinator = SessionCoordinator(this, onCancel = { gen, _ -> cancelled = gen })
        coordinator.shutdown()
        delay(100)
        coordinator.submit(SessionCommand.Cancel(7, "stop"))
        assertEquals(7, cancelled)
    }

    @Test
    fun `drainNow applies a queued finalise before the caller continues`() = runBlocking {
        var finalised = 0
        val coordinator = SessionCoordinator(this, onFinalize = { finalised++ })
        coordinator.offer(SessionCommand.Finalize(1))
        coordinator.drainNow()
        assertEquals(1, finalised)
        coordinator.shutdown()
    }
}
