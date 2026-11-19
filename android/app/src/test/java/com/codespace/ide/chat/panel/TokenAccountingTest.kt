package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/** P3-A (2026-11-19): reported-only, last-report-wins accounting. */
class TokenAccountingTest {

    private fun turn(a: TokenAccounting, input: Int, output: Int) {
        a.beginTurn(); a.onUsage(input, output); a.endTurn()
    }

    @Test
    fun `a reported usage increments both counters`() {
        val a = TokenAccounting()
        turn(a, 1200, 300)
        assertEquals(1200L, a.totals.inputTokens)
        assertEquals(300L, a.totals.outputTokens)
        assertEquals(1, a.totals.reportedTurns)
    }

    @Test
    fun `distinct turns accumulate`() {
        val a = TokenAccounting()
        turn(a, 100, 10); turn(a, 200, 20)
        assertEquals(300L, a.totals.inputTokens)
        assertEquals(30L, a.totals.outputTokens)
        assertEquals(2, a.totals.reportedTurns)
    }

    @Test
    fun `missing usage invents nothing`() {
        val a = TokenAccounting()
        turn(a, 0, 0)
        assertEquals(0L, a.totals.total)
        assertEquals(0, a.totals.reportedTurns)
        assertEquals(1, a.totals.unreportedTurns)
    }

    @Test
    fun `a repeated report inside one turn is counted once`() {
        val a = TokenAccounting()
        a.beginTurn(); a.onUsage(500, 50); a.onUsage(500, 50); a.onUsage(500, 50); a.endTurn()
        assertEquals(500L, a.totals.inputTokens)
        assertEquals(50L, a.totals.outputTokens)
    }

    @Test
    fun `a retry reporting the whole turn again does not inflate the total`() {
        val a = TokenAccounting()
        a.beginTurn(); a.onUsage(800, 80); a.onUsage(900, 90); a.endTurn()
        assertEquals(900L, a.totals.inputTokens)
        assertEquals(90L, a.totals.outputTokens)
    }

    @Test
    fun `ending a turn twice adds nothing`() {
        val a = TokenAccounting()
        a.beginTurn(); a.onUsage(10, 1); a.endTurn(); a.endTurn()
        assertEquals(11L, a.totals.total)
        assertEquals(1, a.totals.reportedTurns)
    }

    @Test
    fun `two sessions never share a total`() {
        val a = TokenAccounting(); val b = TokenAccounting()
        turn(a, 100, 10)
        assertEquals(0L, b.totals.total)
    }

    @Test
    fun `usage arriving outside a turn is ignored`() {
        val a = TokenAccounting()
        a.onUsage(999, 99)
        assertEquals(0L, a.totals.total)
    }

    @Test
    fun `summary states counts and never a price`() {
        val a = TokenAccounting()
        turn(a, 10, 2)
        val s = a.summary()
        org.junit.Assert.assertTrue(s.contains("in 10"))
        org.junit.Assert.assertFalse(s.lowercase().contains("$"))
        org.junit.Assert.assertFalse(s.lowercase().contains("cost"))
    }
}
