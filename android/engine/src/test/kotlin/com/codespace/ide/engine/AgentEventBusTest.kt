package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventBusTest {

    @Test
    fun aLateReaderGetsEverythingAfterWhatItSaw() {
        val bus = AgentEventBus.Bus()
        val first = bus.publish("round", "1", 1L)
        bus.publish("tool", "read_file", 2L)
        assertEquals(listOf("tool"), bus.since(first.seq).map { it.kind })
    }

    @Test
    fun theSequenceNeverGoesBackwardsEvenWhenTheBufferTrims() {
        val bus = AgentEventBus.Bus(maxEvents = 2)
        bus.publish("a", "1", 1L)
        bus.publish("b", "2", 2L)
        val third = bus.publish("c", "3", 3L)
        assertEquals(2, bus.size())
        assertEquals(3L, third.seq)
        assertEquals(3L, bus.lastSeq())
    }

    @Test
    fun theBufferKeepsTheNewest() {
        val bus = AgentEventBus.Bus(maxEvents = 2)
        bus.publish("a", "1", 1L)
        bus.publish("b", "2", 2L)
        bus.publish("c", "3", 3L)
        assertEquals(listOf("b", "c"), bus.all().map { it.kind })
    }

    @Test
    fun anEmptyBusHasNothingSince() {
        assertTrue(AgentEventBus.Bus().since(0L).isEmpty())
    }
}
