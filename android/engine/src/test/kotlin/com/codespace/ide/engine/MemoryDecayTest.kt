package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L

class MemoryDecayTest {

    @Test
    fun aFactFromTodayIsUnchanged() {
        assertEquals(1.0, MemoryDecay.factor(1_000L, 0L, 0, 1_000L), 1e-9)
    }

    @Test
    fun oneHalfLifeHalvesTheWeight() {
        val created = 1_000L
        val now = created + (90 * DAY)
        val f = MemoryDecay.factor(created, 0L, 0, now)
        assertEquals(0.5, f, 0.02)
    }

    @Test
    fun useResistsDecay() {
        val created = 1_000L
        val now = created + (90 * DAY)
        val unused = MemoryDecay.factor(created, 0L, 0, now)
        val used = MemoryDecay.factor(created, 0L, 5, now)
        assertTrue("a used fact must score higher than an unused one", used > unused)
    }

    @Test
    fun recencyIsMeasuredFromLastUseNotCreation() {
        val created = 1_000L
        val now = created + (365 * DAY)
        val usedYesterday = MemoryDecay.factor(created, now - DAY, 0, now)
        assertTrue("a fact used yesterday must not be aged out", usedYesterday > 0.9)
    }

    @Test
    fun anAncientFactIsQuietButNeverZero() {
        val f = MemoryDecay.factor(1_000L, 0L, 0, 1_000L + (10_000 * DAY))
        assertEquals(MemoryDecay.MIN_FACTOR, f, 1e-9)
    }

    @Test
    fun preferencesAndConstraintsNeverFade() {
        assertEquals(1.0, MemoryDecay.factorFor("preference", 1_000L, 0L, 0, 1_000L + 9_999 * DAY), 1e-9)
        assertEquals(1.0, MemoryDecay.factorFor("constraint", 1_000L, 0L, 0, 1_000L + 9_999 * DAY), 1e-9)
    }

    @Test
    fun anOrdinaryCategoryStillDecays() {
        val f = MemoryDecay.factorFor("general", 1_000L, 0L, 0, 1_000L + 10_000 * DAY)
        assertTrue("a general fact must fade", f < 0.2)
    }
}
