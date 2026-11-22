package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTest {

    @Test
    fun aConversationThatFitsIsLeftAlone() {
        val entries = listOf(HistoryTrim.Entry(0, HistoryTrim.Priority.LOW, 100))
        assertTrue(HistoryTrim.plan(entries, 100).dropped.isEmpty())
    }

    @Test
    fun lowPriorityGoesBeforeNormal() {
        val entries = listOf(
            HistoryTrim.Entry(0, HistoryTrim.Priority.NORMAL, 60),
            HistoryTrim.Entry(1, HistoryTrim.Priority.LOW, 60),
        )
        val plan = HistoryTrim.plan(entries, 60)
        assertEquals(setOf(1), plan.dropped)
        assertEquals(60, plan.keptTokens)
    }

    @Test
    fun aPinnedTurnIsNeverDropped() {
        val entries = listOf(
            HistoryTrim.Entry(0, HistoryTrim.Priority.LOW, 90, pinned = true),
            HistoryTrim.Entry(1, HistoryTrim.Priority.NORMAL, 40),
        )
        val plan = HistoryTrim.plan(entries, 50)
        assertEquals(setOf(1), plan.dropped)
    }

    @Test
    fun theOldestGoesFirstInsideAPriority() {
        val entries = listOf(
            HistoryTrim.Entry(0, HistoryTrim.Priority.LOW, 50),
            HistoryTrim.Entry(1, HistoryTrim.Priority.LOW, 50),
            HistoryTrim.Entry(2, HistoryTrim.Priority.LOW, 50),
        )
        assertEquals(setOf(0), HistoryTrim.plan(entries, 100).dropped)
    }

    @Test
    fun mustKeepIsNeverDroppedEvenOverBudget() {
        val entries = listOf(HistoryTrim.Entry(0, HistoryTrim.Priority.MUST_KEEP, 500))
        val plan = HistoryTrim.plan(entries, 10)
        assertTrue(plan.dropped.isEmpty())
        assertEquals(500, plan.keptTokens)
    }
}

class HistoryTrimFitTest {

    @Test
    fun aPlanThatCannotFitSaysSo() {
        val entries = listOf(HistoryTrim.Entry(0, HistoryTrim.Priority.MUST_KEEP, 900))
        val plan = HistoryTrim.plan(entries, 100)
        assertTrue("mustKeep alone is over budget, so fits must be false", !plan.fits)
    }

    @Test
    fun aPlanThatFitsSaysSo() {
        val entries = listOf(HistoryTrim.Entry(0, HistoryTrim.Priority.LOW, 90))
        assertTrue(HistoryTrim.plan(entries, 100).fits)
    }
}
