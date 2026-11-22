package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT EIGHT 8.15/8.16: a trace that never invents a number, and totals that admit gaps. */
class TraceAndTallyTest {

    @Test
    fun unreportedUsageSaysUnknownRatherThanZero() {
        val t = TurnTrace(providerId = "openai", modelId = "m", payloadChars = 1200, toolsOffered = 3)
        assertFalse(t.hasUsage)
        assertTrue(t.render().contains("usage not reported"))
        assertFalse("must not claim 0 tokens", t.render().contains("0 in"))
    }

    @Test
    fun aFailedTurnCarriesItsCluster() {
        val t = TurnTrace(
            providerId = "openai", modelId = "m", payloadChars = 10, toolsOffered = 0,
            finish = "error", cluster = FailureCluster.RATE_LIMIT,
        )
        assertTrue(t.render().contains("failed: rate_limit"))
    }

    @Test
    fun theTallyAddsOnlyWhatWasReported() {
        val tally = TokenTally()
        tally.record(100, 20)
        tally.record(null, null)
        tally.record(50, null)
        val t = tally.totals()
        assertEquals(150L, t.inputTokens)
        assertEquals(20L, t.outputTokens)
        assertEquals(170L, t.totalTokens)
        assertEquals(2, t.turnsReported)
        assertEquals(1, t.turnsUnreported)
    }

    @Test
    fun theTallyStartsEmptyAndClears() {
        val tally = TokenTally()
        assertEquals(0L, tally.totals().totalTokens)
        tally.record(5, 5)
        tally.clear()
        assertEquals(0L, tally.totals().totalTokens)
        assertNull(null)
    }
}
