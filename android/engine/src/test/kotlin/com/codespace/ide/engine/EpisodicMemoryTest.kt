package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodicMemoryTest {
    @Test fun roundTrips() {
        val e = EpisodicMemory.Episode("e1", 42L, "did the thing", listOf("a.kt", "b.kt"))
        assertEquals(e, EpisodicMemory.parse(EpisodicMemory.render(e)))
    }
    @Test fun badLineIsDropped() { assertEquals(null, EpisodicMemory.parse("nope")) }
}
