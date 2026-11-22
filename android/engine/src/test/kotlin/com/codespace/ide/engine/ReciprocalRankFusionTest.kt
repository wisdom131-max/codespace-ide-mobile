package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReciprocalRankFusionTest {

    @Test
    fun aDocumentInBothListsBeatsOneInEither() {
        val fused = ReciprocalRankFusion.fuse(
            listOf(listOf("a", "b"), listOf("b", "c")),
        )
        assertEquals("b appears in both, so it wins", "b", fused.first().id)
    }

    @Test
    fun ranksAreRecordedPerList() {
        val fused = ReciprocalRankFusion.fuse(listOf(listOf("a", "b"), listOf("b")))
        val b = fused.first { it.id == "b" }
        assertEquals(listOf(2, 1), b.ranks)
    }

    @Test
    fun aDuplicateInsideOneListVotesOnce() {
        val fused = ReciprocalRankFusion.fuse(listOf(listOf("a", "a", "b")))
        val a = fused.first { it.id == "a" }
        assertEquals("one list, one rank", 1, a.ranks.size)
    }

    @Test
    fun scoresFollowTheDocumentedFormula() {
        val fused = ReciprocalRankFusion.fuse(listOf(listOf("x")), k = 60)
        assertEquals(1.0 / 61.0, fused.first().score, 1e-12)
    }

    @Test
    fun aMissingDocumentIsNotPenalised() {
        val fused = ReciprocalRankFusion.fuse(listOf(listOf("a"), listOf("b")))
        val a = fused.first { it.id == "a" }
        assertEquals("only the list it appeared in counts", 1, a.ranks.size)
        assertTrue(a.score > 0.0)
    }

    @Test
    fun orderingIsStableForEqualScores() {
        val one = ReciprocalRankFusion.fuse(listOf(listOf("b", "a")))
        val two = ReciprocalRankFusion.fuse(listOf(listOf("a", "b")))
        assertEquals("different inputs, so different order", one.map { it.id }, two.map { it.id }.reversed())
        val same = ReciprocalRankFusion.fuse(listOf(listOf("b"), listOf("a")))
        assertEquals("ties broken by id", listOf("a", "b"), same.map { it.id })
    }
}
