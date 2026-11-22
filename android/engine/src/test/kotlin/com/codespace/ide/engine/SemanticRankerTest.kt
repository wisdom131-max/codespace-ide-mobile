package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticRankerTest {

    @Test
    fun theTopicallyCloserDocumentComesFirst() {
        val docs = listOf(
            SemanticRanker.Doc("compose", "compose preview rendering issue"),
            SemanticRanker.Doc("gradle", "gradle dependency resolution failure"),
        )
        assertEquals("gradle", SemanticRanker.rank("gradle dependency", docs).first())
    }

    @Test
    fun noDocumentsIsNoRanking() {
        assertTrue(SemanticRanker.rank("anything", emptyList()).isEmpty())
    }

    @Test
    fun aBlankQueryRanksNothing() {
        assertTrue(SemanticRanker.rank("  ", listOf(SemanticRanker.Doc("a", "text"))).isEmpty())
    }

    @Test
    fun kIsRespected() {
        val docs = (1..5).map { SemanticRanker.Doc("d" + it, "some text " + it) }
        assertEquals(2, SemanticRanker.rank("text", docs, k = 2).size)
    }
}
