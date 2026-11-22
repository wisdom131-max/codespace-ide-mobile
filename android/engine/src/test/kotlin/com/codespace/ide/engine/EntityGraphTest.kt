package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityGraphTest {

    @Test
    fun aNewTripleIsReportedAsNew() {
        val g = EntityGraph.Graph()
        assertTrue(g.add("budget", "lives in", "BudgetGuard"))
        assertFalse("the same triple twice is not new knowledge", g.add("budget", "lives in", "BudgetGuard"))
    }

    @Test
    fun objectsCanBeFilteredByRelationship() {
        val g = EntityGraph.Graph()
        g.add("chat", "uses", "BudgetGuard")
        g.add("chat", "uses", "HistoryBudget")
        g.add("chat", "owned by", "panel")
        assertEquals(listOf("BudgetGuard", "HistoryBudget"), g.objects("chat", "uses"))
        assertEquals(3, g.objects("chat").size)
    }

    @Test
    fun theReverseDirectionWorks() {
        val g = EntityGraph.Graph()
        g.add("chat", "uses", "BudgetGuard")
        assertEquals(listOf("chat"), g.subjects("uses", "BudgetGuard"))
    }

    @Test
    fun aBlankPartIsRejected() {
        val g = EntityGraph.Graph()
        assertFalse(g.add("", "uses", "x"))
        assertEquals(0, g.size())
    }
}
