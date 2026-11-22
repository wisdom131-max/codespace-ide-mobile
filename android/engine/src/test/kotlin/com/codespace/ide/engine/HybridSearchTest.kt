package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridSearchTest {

    @Test
    fun aDocumentFoundByBothListsWins() {
        val r = HybridSearch.combine(
            lexical = listOf("both", "literal"),
            semantic = listOf("both", "meaning"),
        )
        assertEquals("both", r.first().id)
    }

    @Test
    fun anEmptyLiteralListStillReturnsTheSemanticOrder() {
        val r = HybridSearch.combine(emptyList(), listOf("a", "b"))
        assertEquals(listOf("a", "b"), r.map { it.id })
    }

    @Test
    fun bothEmptyIsNothing() {
        assertTrue(HybridSearch.combine(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun aDuplicateInsideOneListDoesNotAppearTwice() {
        val r = HybridSearch.combine(listOf("a", "a"), emptyList())
        assertEquals(1, r.size)
    }
}
