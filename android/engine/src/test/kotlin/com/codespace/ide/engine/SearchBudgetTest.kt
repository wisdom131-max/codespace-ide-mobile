package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchBudgetTest {

    private val items = listOf(
        SearchBudget.Item("a", "x".repeat(10)),
        SearchBudget.Item("b", "y".repeat(10)),
        SearchBudget.Item("c", "z".repeat(10)),
    )

    @Test
    fun wholeResultsAreKeptUntilTheCap() {
        assertEquals(listOf("a", "b"), SearchBudget.fit(items, 25).map { it.path })
    }

    @Test
    fun aResultIsNeverCutInHalf() {
        val kept = SearchBudget.fit(items, 15)
        assertTrue(kept.all { it.text.length == 10 })
    }

    @Test
    fun aZeroBudgetReturnsNothing() {
        assertTrue(SearchBudget.fit(items, 0).isEmpty())
    }
}

class SearchBudgetBoundaryTest {

    @Test
    fun aResultStraddlingTheCapIsDroppedWholeNotSliced() {
        val items = listOf(
            SearchBudget.Item("a", "x".repeat(60)),
            SearchBudget.Item("b", "y".repeat(60)),
        )
        val kept = SearchBudget.fit(items, 100)
        assertEquals(1, kept.size)
        assertEquals(60, kept.single().text.length)
    }
}
