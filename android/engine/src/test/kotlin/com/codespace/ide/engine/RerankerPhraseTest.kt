package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CATALOG 51: the stand-in reranker prefers a phrase over the same words scattered. */
class RerankerPhraseTest {

    @Test
    fun aPhraseOutranksTheSameWordsScattered() {
        val scattered = Reranker.Candidate("a", "retrieval is hard and fact checking is separate", 1.0)
        val phrase = Reranker.Candidate("b", "this file explains fact retrieval in one place", 1.0)
        val out = Reranker.rerank(listOf(scattered, phrase), listOf("fact", "retrieval"))
        assertEquals("b", out.first().id)
    }

    @Test
    fun aMuchStrongerResultSurvivesAGoodPhraseMatch() {
        val strong = Reranker.Candidate("strong", "unrelated words only", 10.0)
        val weak = Reranker.Candidate("weak", "fact retrieval", 1.0)
        val out = Reranker.rerank(listOf(strong, weak), listOf("fact", "retrieval"))
        assertEquals("the cap keeps a weak match from overtaking a much better one", "strong", out.first().id)
    }

    @Test
    fun noTermsLeavesTheOrderAlone() {
        val a = Reranker.Candidate("a", "one", 1.0)
        val b = Reranker.Candidate("b", "two", 2.0)
        assertEquals(listOf("a", "b"), Reranker.rerank(listOf(a, b), emptyList()).map { it.id })
    }

    @Test
    fun aMissingTermScoresNoProximity() {
        val onlyOne = Reranker.Candidate("a", "fact only", 1.0)
        val both = Reranker.Candidate("b", "fact retrieval", 1.0)
        val out = Reranker.rerank(listOf(onlyOne, both), listOf("fact", "retrieval"))
        assertTrue("the candidate holding both terms comes first", out.first().id == "b")
    }
}
