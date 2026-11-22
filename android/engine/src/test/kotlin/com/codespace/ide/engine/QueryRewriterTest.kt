package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryRewriterTest {

    @Test
    fun aCamelCaseNameKeepsItsWholeFormAndItsParts() {
        val terms = QueryRewriter.rewrite("where is FactRetrieval used")
        assertTrue("whole identifier kept", terms.contains("FactRetrieval"))
        assertTrue("parts available for prose matching", terms.contains("Fact"))
        assertTrue("parts available for prose matching", terms.contains("Retrieval"))
    }

    @Test
    fun snakeCaseSplitsToo() {
        val parts = QueryRewriter.splitIdentifier("memory_block_size")
        assertEquals(listOf("memory", "block", "size"), parts)
    }

    @Test
    fun stopwordsAreDropped() {
        val terms = QueryRewriter.rewrite("how does the thing work")
        assertTrue("no stopwords", terms.none { it.lowercase() in setOf("how", "does", "the") })
        assertTrue(terms.contains("thing"))
    }

    @Test
    fun nothingIsInvented() {
        val raw = "ChatEngineImpl retry"
        for (t in QueryRewriter.rewrite(raw)) {
            assertTrue("term '$t' must come from the input", raw.contains(t))
        }
    }

    @Test
    fun theCapIsRespected() {
        val long = (1..80).joinToString(" ") { "term$it" }
        assertTrue(QueryRewriter.rewrite(long, maxTerms = 5).size <= 5)
    }

    @Test
    fun blankInputYieldsNothing() {
        assertTrue(QueryRewriter.rewrite("   ").isEmpty())
    }
}
