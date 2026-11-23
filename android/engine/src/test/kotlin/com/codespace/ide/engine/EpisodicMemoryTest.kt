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

class EpisodicShelfTest {

    @Test
    fun anEpisodeIsKeptAndReadable() {
        val shelf = EpisodicMemory.Shelf()
        shelf.save(EpisodicMemory.Episode("e1", 1L, "fixed the gradle error"))
        assertEquals(1, shelf.size())
        assertEquals("fixed the gradle error", shelf.recent().single().summary)
    }

    @Test
    fun theShelfKeepsTheNewestOnly() {
        val shelf = EpisodicMemory.Shelf(maxEpisodes = 2)
        shelf.save(EpisodicMemory.Episode("e1", 1L, "one"))
        shelf.save(EpisodicMemory.Episode("e2", 2L, "two"))
        shelf.save(EpisodicMemory.Episode("e3", 3L, "three"))
        assertEquals(listOf("two", "three"), shelf.recent().map { it.summary })
    }
}
