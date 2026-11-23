package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OWNER REQUEST (2026-11-23): facts must be findable by MEANING, not only by shared words, and a
 * contradicting fact must update rather than duplicate. These are pure tests: the vectors are handed
 * in, so no model and no download is needed to prove the logic.
 */
class FactMeaningTest {

    private fun f(id: String, content: String) = Fact(
        id = id,
        content = content,
        scope = FactScope.USER,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `identical vectors are a perfect match`() {
        val v = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(1.0, FactVectors.cosine(v, v), 0.0001)
    }

    @Test
    fun `unrelated vectors score nothing`() {
        assertEquals(0.0, FactVectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.0001)
    }

    @Test
    fun `a fact that shares no word is still found by meaning`() {
        val facts = listOf(
            f("race", "I am training for a half marathon"),
            f("build", "the project builds with gradle"),
        )
        val query = floatArrayOf(1f, 0f)
        val vectors = mapOf(
            "race" to floatArrayOf(1f, 0f),
            "build" to floatArrayOf(0f, 1f),
        )
        val ranked = FactRetrieval.rank(
            facts = facts,
            request = "what is my running plan",
            projectId = null,
            now = 1_800_000_000_000L,
            queryVector = query,
            vectors = vectors,
        )
        assertEquals("race", ranked.first().fact.id)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `the word filter no longer drops a synonym only fact`() {
        val facts = listOf(f("race", "I am training for a half marathon"))
        val picked = FactRetrieval.forRequest(
            facts = facts,
            request = "what is my running plan",
            projectId = null,
            queryVector = floatArrayOf(1f, 0f),
            vectors = mapOf("race" to floatArrayOf(1f, 0f)),
        )
        assertEquals(1, picked.size)
    }

    @Test
    fun `without vectors nothing changes`() {
        val facts = listOf(f("race", "I am training for a half marathon"))
        val picked = FactRetrieval.forRequest(
            facts = facts,
            request = "what is my running plan",
            projectId = null,
        )
        assertTrue(picked.isEmpty())
    }
}
