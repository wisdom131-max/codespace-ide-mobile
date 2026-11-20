package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalFusionTest {

    private fun hit(q: String, id: String, text: String, hits: Int, max: Int, path: String? = null) =
        RetrievalFusion.score(q, id, text, hits, max, path)

    @Test
    fun `semantic-only match still ranks`() {
        val h = hit("how do I run a command", "f1", "the shell executes a command", 3, 3)
        assertTrue(h.semantic > 0.0)
        assertTrue(h.total > 0.0)
    }

    @Test
    fun `lexical-only match still ranks`() {
        val h = hit("FactsStore", "f2", "class FactsStore persists facts", 0, 3)
        assertTrue(h.lexical > 0.0)
        assertTrue(h.total > 0.0)
    }

    @Test
    fun `an exact identifier beats a stronger semantic match`() {
        val semantic = hit("FactsStore", "f3", "facts are stored and retrieved here", 3, 3)
        val exact = hit("FactsStore", "f2", "class FactsStore persists facts", 1, 3)
        assertTrue(exact.total > semantic.total)
    }

    @Test
    fun `a weak lexical match does not beat a strong semantic one`() {
        // One incidental shared token, no phrase, no identifier.
        val weak = hit("build error", "f4", "we will build the app later", 1, 3)
        val strong = hit("build error", "f5", "build error: Gradle failed with exit 1", 2, 3)
        assertTrue(strong.total > weak.total)
    }

    @Test
    fun `scores are normalised so counts on different scales compare`() {
        val a = hit("q", "a", "alpha", 50, 100)
        val b = hit("q", "b", "beta", 5, 10)
        assertEquals(a.semantic, b.semantic, 0.0001)
    }

    @Test
    fun `zero semantic max does not divide by zero`() {
        val h = hit("q", "a", "text", 0, 0)
        assertEquals(0.0, h.semantic, 0.0)
    }

    @Test
    fun `merge deduplicates the same source`() {
        val a = hit("FactsStore", "same", "class FactsStore", 2, 2)
        val b = hit("FactsStore", "same", "class FactsStore", 2, 2)
        assertEquals(1, RetrievalFusion.merge(listOf(a, b)).size)
    }

    @Test
    fun `merge keeps every distinct source`() {
        val hs = (1..5).map { hit("q", "id" + it, "text " + it, 1, 5) }
        assertEquals(5, RetrievalFusion.merge(hs).size)
    }

    @Test
    fun `merge respects the caller limit`() {
        val hs = (1..50).map { hit("q", "id" + it, "text", 1, 50) }
        assertEquals(40, RetrievalFusion.merge(hs).size)
        assertEquals(3, RetrievalFusion.merge(hs, limit = 3).size)
    }

    @Test
    fun `a low lexical score is never dropped by merge`() {
        val hs = listOf(
            hit("q", "high", "q q q", 5, 5),
            hit("q", "low", "unrelated text", 0, 5),
        )
        assertEquals(2, RetrievalFusion.merge(hs).size)
    }

    @Test
    fun `ordering is deterministic`() {
        val hs = (1..6).map { hit("build", "id" + it, "build log " + it, it % 3, 3) }
        assertEquals(RetrievalFusion.merge(hs).map { it.id }, RetrievalFusion.merge(hs).map { it.id })
    }

    @Test
    fun `explain states both halves and the reasons`() {
        val e = RetrievalFusion.explain(hit("FactsStore", "f", "class FactsStore", 1, 2))
        assertTrue(e.contains("semantic"))
        assertTrue(e.contains("lexical"))
    }

    @Test
    fun `path matches contribute`() {
        val withPath = hit("FactsStore.kt", "f", "class thing", 0, 1, "agent/facts/FactsStore.kt")
        val without = hit("FactsStore.kt", "f", "class thing", 0, 1)
        assertTrue(withPath.total > without.total)
    }

    @Test
    fun `an error fragment finds its source`() {
        val error = hit("OutOfMemoryError", "log", "OutOfMemoryError in worker", 0, 2)
        val other = hit("OutOfMemoryError", "note", "memory notes about the worker", 1, 2)
        assertTrue(error.total > other.total)
    }
}
