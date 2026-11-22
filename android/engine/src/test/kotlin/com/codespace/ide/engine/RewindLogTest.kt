package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RewindLogTest {

    @Test
    fun theNewestIsTheLatest() {
        val log = RewindLog.Log()
        log.save("one", "s1", 1L)
        log.save("two", "s2", 2L)
        assertEquals("two", log.latest()?.label)
    }

    @Test
    fun anOldSnapshotCanBeFetchedById() {
        val log = RewindLog.Log()
        val first = log.save("one", "s1", 1L)
        log.save("two", "s2", 2L)
        assertEquals("s1", log.at(first.id)?.state)
    }

    @Test
    fun theRingForgetsTheOldestNotTheNewest() {
        val log = RewindLog.Log(maxSnapshots = 2)
        log.save("one", "s1", 1L)
        log.save("two", "s2", 2L)
        log.save("three", "s3", 3L)
        assertEquals(listOf("two", "three"), log.list().map { it.label })
    }

    @Test
    fun anUnknownIdIsNull() {
        assertNull(RewindLog.Log().at(99L))
    }
}
