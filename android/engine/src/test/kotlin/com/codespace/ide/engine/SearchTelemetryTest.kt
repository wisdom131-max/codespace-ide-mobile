package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTelemetryTest {

    @Test
    fun theThreeBucketsAreCounted() {
        val c = SearchTelemetry.compare(listOf("a", "b"), listOf("b", "c"))
        assertEquals(1, c.lexicalOnly)
        assertEquals(1, c.semanticOnly)
        assertEquals(1, c.both)
        assertEquals(3, c.total)
    }

    @Test
    fun fullOverlapReadsAsHundredPercent() {
        assertEquals(100, SearchTelemetry.compare(listOf("a"), listOf("a")).overlapPercent)
    }

    @Test
    fun nothingSearchedIsZeroNotACrash() {
        val c = SearchTelemetry.compare(emptyList(), emptyList())
        assertEquals(0, c.total)
        assertEquals(0, c.overlapPercent)
    }
}
