package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RerankerTest {

    @Test
    fun aCandidateHoldingTheQueryTermsIsLifted() {
        val c = listOf(
            Reranker.Candidate("vague", "something about builds", 0.60),
            Reranker.Candidate("exact", "gradle dependency failed", 0.50),
        )
        assertEquals("exact", Reranker.rerank(c, listOf("gradle", "dependency")).first().id)
    }

    @Test
    fun noTermsLeavesTheOrderAlone() {
        val c = listOf(Reranker.Candidate("a", "x", 0.1), Reranker.Candidate("b", "y", 0.9))
        assertEquals(listOf("a", "b"), Reranker.rerank(c, emptyList()).map { it.id })
    }

    @Test
    fun theBonusIsBoundedSoItCannotInventAWinner() {
        val c = listOf(
            Reranker.Candidate("weak", "gradle", 0.01),
            Reranker.Candidate("strong", "nothing relevant", 0.95),
        )
        assertEquals("strong", Reranker.rerank(c, listOf("gradle")).first().id)
    }
}
