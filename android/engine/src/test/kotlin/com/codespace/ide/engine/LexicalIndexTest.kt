package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalIndexTest {
    @Test fun queryIsQuotedAndOred() {
        assertEquals("\"Fact\" OR \"Retrieval\"", LexicalIndex.matchQuery(listOf("Fact", "Retrieval")))
    }
    @Test fun emptyQueryStaysEmpty() {
        assertEquals("", LexicalIndex.matchQuery(listOf("  ", "")))
    }
    @Test fun sqlUsesFts5AndBm25() {
        assertTrue(LexicalIndex.createSql().contains("fts5"))
        assertTrue(LexicalIndex.searchSql(10).contains("MATCH ?"))
        assertTrue(LexicalIndex.searchSql(10).contains("bm25"))
    }
}
