package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraceStoreTest {

    @Test
    fun theLatestTraceIsReadable() {
        val s = TraceStore.Store()
        s.add("turn 1", "rounds=2", 1L)
        assertEquals("rounds=2", s.latest()?.rendered)
    }

    @Test
    fun sinceReturnsOnlyNewer() {
        val s = TraceStore.Store()
        val first = s.add("turn 1", "a", 1L)
        s.add("turn 2", "b", 2L)
        assertEquals(listOf("turn 2"), s.since(first.seq).map { it.label })
    }

    @Test
    fun theStoreKeepsTheNewest() {
        val s = TraceStore.Store(maxRecords = 2)
        s.add("1", "a", 1L)
        s.add("2", "b", 2L)
        s.add("3", "c", 3L)
        assertEquals(listOf("2", "3"), s.all().map { it.label })
    }

    @Test
    fun anEmptyStoreHasNoLatest() {
        assertNull(TraceStore.Store().latest())
    }
}

class TraceAttributionTest {

    @Test
    fun aTraceIsAttributableToItsSession() {
        val shelf = TraceStore.Store()
        shelf.add("turn:abc", "kept=3", 1L)
        shelf.add("turn:xyz", "kept=9", 2L)
        assertEquals("turn:abc", shelf.all().first().label)
        assertEquals("turn:xyz", shelf.latest()?.label)
    }
}
