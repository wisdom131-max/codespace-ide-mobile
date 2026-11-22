package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPipelineTest {

    private val docs = listOf(
        SearchPipeline.Doc("d1", "A.kt", "gradle dependency resolution failed"),
        SearchPipeline.Doc("d2", "B.kt", "compose preview rendering issue"),
        SearchPipeline.Doc("d3", "A.kt", "another part of the same file about gradle"),
    )

    @Test
    fun aDocumentFoundBothWaysComesFirst() {
        val out = SearchPipeline.run("gradle dependency", docs, lexical = listOf("d1"))
        assertEquals("d1", out.ids.first())
    }

    @Test
    fun theLimitCountsDistinctFilesNotHits() {
        val out = SearchPipeline.run("gradle", docs, lexical = listOf("d1", "d3"), limit = 1)
        assertEquals(1, out.ids.size)
    }

    @Test
    fun twoChunksOfOneFileAppearOnce() {
        val out = SearchPipeline.run("gradle", docs, lexical = listOf("d1", "d3"), limit = 10)
        assertEquals("A.kt only once", 1, out.ids.count { it == "d1" || it == "d3" })
    }

    @Test
    fun noDocumentsIsAnEmptyOutcome() {
        val out = SearchPipeline.run("gradle", emptyList(), listOf("d1"))
        assertTrue(out.ids.isEmpty())
    }

    @Test
    fun theCountsReportWhatEachSideFound() {
        val out = SearchPipeline.run("gradle", docs, lexical = listOf("d1", "d2"))
        assertEquals(2, out.lexicalCount)
        assertTrue(out.semanticCount > 0)
    }
}
