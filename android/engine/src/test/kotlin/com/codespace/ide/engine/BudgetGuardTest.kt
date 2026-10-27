package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetGuardTest {

    @Test fun `within budget passes`() {
        assertNull(BudgetGuard.checkPayload(1_000, 48_000))
    }

    @Test fun `over budget refuses with plain verdict`() {
        val e = BudgetGuard.checkPayload(49_000, 48_000)
        assertTrue(e != null && e.userVerdict.contains("49k"))
    }

    @Test fun `token estimate is chars over four`() {
        assertEquals(25, BudgetGuard.estimateTokens("x".repeat(101)))
    }
}

class HistoryBudgetTest {

    private fun msg(id: String, len: Int) = ChatMessage(id, ChatRole.USER, "m".repeat(len))

    @Test fun `compact keeps last six`() {
        val h = (1..9).map { msg("$it", 10) }
        val sel = HistoryBudget.select(h, HistoryMode.COMPACT)
        assertEquals(listOf("4", "5", "6", "7", "8", "9"), sel.map { it.id })
    }

    @Test fun `short list returned as-is`() {
        val h = listOf(msg("a", 10))
        assertTrue(HistoryBudget.select(h, HistoryMode.COMPACT) === h)
    }

    @Test fun `char cap drops oldest first, newest always survives`() {
        val h = (1..6).map { msg("$it", 3_000) } // 18k chars, COMPACT cap 12k
        val sel = HistoryBudget.select(h, HistoryMode.COMPACT)
        assertEquals(listOf("3", "4", "5", "6"), sel.map { it.id })
    }

    @Test fun `unknown mode name falls back to balanced`() {
        assertEquals(HistoryMode.BALANCED, HistoryMode.parse("nope"))
        assertEquals(HistoryMode.FULL, HistoryMode.parse("full"))
    }
}
