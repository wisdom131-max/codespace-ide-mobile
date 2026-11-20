package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalRankerTest {

    private val files = listOf(
        LexicalRanker.Candidate("a", "fun runInTerminal(command: String) starts a shell", "terminal/RunTerminal.kt"),
        LexicalRanker.Candidate("b", "class FactStore persists memory facts", "agent/facts/FactsStore.kt"),
        LexicalRanker.Candidate("c", "the build failed with Gradle error", "notes/build.md"),
        LexicalRanker.Candidate("d", "Gradle daemon crashed: OutOfMemoryError in the worker", "logs/build-error.txt"),
        LexicalRanker.Candidate("e", "a memory about tabs versus spaces", "notes/style.md"),
    )

    @Test
    fun `an exact filename finds its file`() {
        val r = LexicalRanker.rank("FactsStore.kt", files)
        assertEquals("b", r.first().id)
    }

    @Test
    fun `an exact class name wins over topical noise`() {
        val r = LexicalRanker.rank("FactsStore", files)
        assertEquals("b", r.first().id)
    }

    @Test
    fun `a method name written in camel case is found`() {
        val r = LexicalRanker.rank("runInTerminal", files)
        assertEquals("a", r.first().id)
        assertTrue(r.first().reasons.isNotEmpty())
    }

    @Test
    fun `an error message fragment finds the log that holds it`() {
        val r = LexicalRanker.rank("OutOfMemoryError", files)
        assertEquals("d", r.first().id)
    }

    @Test
    fun `an exact phrase outranks scattered token matches`() {
        val r = LexicalRanker.rank("Gradle daemon crashed", files)
        assertEquals("d", r.first().id)
    }

    @Test
    fun `underscores and dots do not destroy matching`() {
        val r = LexicalRanker.rank("facts_store", files)
        // The query splits to facts + store; the store candidate holds both.
        assertTrue(r.any { it.id == "b" })
    }

    @Test
    fun `case never decides the outcome`() {
        assertEquals(LexicalRanker.rank("factsstore", files).first().id, LexicalRanker.rank("FACTSSTORE", files).first().id)
    }

    @Test
    fun `a query with no lexical match returns nothing rather than everything`() {
        assertTrue(LexicalRanker.rank("zzzznothing", files).isEmpty())
    }

    @Test
    fun `an irrelevant exact substring does not become the top hit`() {
        // "a" appears inside many words; a one-letter query must not win by substring.
        val r = LexicalRanker.rank("a", files)
        assertTrue(r.all { it.score < 4.0 })
    }

    @Test
    fun `duplicate ids collapse to one row`() {
        val dup = files + LexicalRanker.Candidate("b", "class FactStore persists memory facts again", "agent/facts/FactsStore.kt")
        val r = LexicalRanker.rank("FactsStore", dup)
        assertEquals(1, r.count { it.id == "b" })
    }

    @Test
    fun `fusion is explainable and weighted`() {
        assertEquals(5.0, LexicalRanker.fuse(lexical = 3.0, semantic = 2.0), 0.001)
        assertEquals(4.0, LexicalRanker.fuse(3.0, 2.0, lexicalWeight = 1.0, semanticWeight = 0.5), 0.001)
    }

    @Test
    fun `the limit is respected`() {
        assertTrue(LexicalRanker.rank("build", files, limit = 1).size == 1)
    }

    @Test
    fun `scoring is deterministic across runs`() {
        val a = LexicalRanker.rank("build", files).map { it.id }
        val b = LexicalRanker.rank("build", files).map { it.id }
        assertEquals(a, b)
    }

    @Test
    fun `offloaded output references are rankable but stay scoped by the caller`() {
        val c = listOf(
            LexicalRanker.Candidate("o1", "offloaded run_command output", "offload://p1/s1/run_command-1a2b.txt"),
        )
        val r = LexicalRanker.rank("run_command", c)
        assertEquals("o1", r.first().id)
        // The ranker never resolves a reference; only the caller's scoped read may.
        assertTrue(r.first().reasons.any { it.contains("path") || it.contains("token") })
    }
}
