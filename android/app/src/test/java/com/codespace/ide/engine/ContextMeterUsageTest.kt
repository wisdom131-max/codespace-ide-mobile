package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OWNER REPORT (2026-11-23): the context window panel's token numbers were not right. The panel
 * showed only the engine's own character-based estimate. VS Code's widget uses the usage the
 * PROVIDER reported for the last response as the numerator. These pin that half.
 */
class ContextMeterUsageTest {

    private fun report(session: String) = ContextMeter.record(
        session,
        ContextMeter.Segments(systemChars = 4000, toolChars = 2000, messageChars = 8000, toolResultChars = 0, reservedChars = 4000),
        windowTokens = 128_000,
    )

    @Test
    fun reportedUsageIsKeptPerSession() {
        report("s1")
        ContextMeter.recordUsage("s1", promptTokens = 3_500, completionTokens = 250)
        val u = ContextMeter.usageFor("s1")
        assertNotNull(u)
        assertEquals(3_500, u!!.promptTokens)
        assertEquals(250, u.completionTokens)
        assertEquals(3_750, u.totalTokens)
    }

    @Test
    fun oneSessionNeverSeesAnotherSessionsUsage() {
        ContextMeter.recordUsage("a", 1_000, 100)
        ContextMeter.recordUsage("b", 9_000, 900)
        assertEquals(1_100, ContextMeter.usageFor("a")!!.totalTokens)
        assertEquals(9_900, ContextMeter.usageFor("b")!!.totalTokens)
        // the T10 lesson: no global fallback, so an unknown session shows nothing
        assertNull(ContextMeter.usageFor("never-sent"))
        assertNull(ContextMeter.usageFor(null))
    }

    @Test
    fun anEmptyUsageBlockIsNotEvidence() {
        assertNull(ContextMeter.recordUsage("c", 0, 0))
        assertNull(ContextMeter.usageFor("c"))
    }

    @Test
    fun reportedUsageAndTheMeasurementAreSeparateFacts() {
        val r = report("d")
        ContextMeter.recordUsage("d", 10_000, 500)
        // a later measurement must not wipe what the provider reported
        report("d")
        assertEquals(10_500, ContextMeter.usageFor("d")!!.totalTokens)
        assertEquals(r.windowTokens, 128_000)
    }

    @Test
    fun forgettingASessionDropsBoth() {
        report("e")
        ContextMeter.recordUsage("e", 500, 50)
        ContextMeter.forget("e")
        assertNull(ContextMeter.usageFor("e"))
        assertNull(ContextMeter.latestFor("e"))
    }
}
