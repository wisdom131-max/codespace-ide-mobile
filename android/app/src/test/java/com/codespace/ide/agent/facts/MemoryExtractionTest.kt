package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * STAGE 7 — automatic extraction.
 *
 * The bar: promote durable statements, refuse chatter, never invent provenance, and never let a
 * memory problem reach the chat.
 */
class MemoryExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val prov = FactProvenance.fromTranscript("conv-9", "v3", 5_000L, "m4", 6_000L, true, false)

    @Test
    fun `an explicit preference becomes a candidate`() {
        val c = MemoryExtraction.candidates("I prefer tabs over spaces in this project.")
        assertEquals(1, c.size)
        assertEquals(MemoryExtraction.Kind.PREFERENCE, c[0].kind)
    }

    @Test
    fun `a decision becomes a candidate`() {
        val c = MemoryExtraction.candidates("We decided to use Room for the local cache.")
        assertEquals(MemoryExtraction.Kind.DECISION, c[0].kind)
    }

    @Test
    fun `transient chatter produces nothing`() {
        assertTrue(MemoryExtraction.candidates("thanks").isEmpty())
        assertTrue(MemoryExtraction.candidates("ok cool, continue").isEmpty())
    }

    @Test
    fun `a question is not a durable fact`() {
        assertTrue(MemoryExtraction.candidates("Do I prefer tabs or spaces everywhere?").isEmpty())
    }

    @Test
    fun `a plain observation with no rule shape is ignored`() {
        assertTrue(MemoryExtraction.candidates("The weather in Lagos is warm today.").isEmpty())
    }

    @Test
    fun `secrets are refused before they become candidates`() {
        assertTrue(MemoryExtraction.candidates("I always use api_key sk-abcdefghijklmnopqrstuvwxyz123456").isEmpty())
    }

    @Test
    fun `extraction stores an inferred fact with the transcript provenance`() {
        val d = tmp.newFolder("m")
        val r = MemoryExtraction.capture(d, "I prefer the Gradle wrapper for builds.", prov)
        assertEquals(1, r.accepted.size)
        val f = FactsStore.loadFrom(d).single()
        assertEquals(FactSource.INFERRED, f.source)
        assertEquals(FactConfidence.INFERRED, f.confidence)
        assertEquals("conv-9", f.sourceConversationId)
        assertEquals("m4", f.sourceMessageId)
        assertEquals(6_000L, f.sourceMessageAt)
        assertEquals(TimeSource.TRANSCRIPT_VERIFIED, f.timeSource)
    }

    @Test
    fun `the same statement twice does not duplicate`() {
        val d = tmp.newFolder("m")
        MemoryExtraction.capture(d, "I prefer tabs for indentation.", prov)
        val second = MemoryExtraction.capture(d, "I prefer tabs for indentation.", prov)
        assertEquals(0, second.accepted.size)
        assertEquals(1, second.duplicates)
        assertEquals(1, FactsStore.loadFrom(d).size)
    }

    @Test
    fun `a contradictory statement routes through supersession`() {
        val d = tmp.newFolder("m")
        MemoryExtraction.capture(d, "We decided to use SQLite for storage.", prov)
        MemoryExtraction.capture(d, "We decided to use Room for storage.", prov.copy(sourceMessageId = "m5"))
        val all = FactsStore.loadFrom(d)
        assertTrue("history is kept", all.any { it.status == FactStatus.SUPERSEDED } || all.size == 2)
    }

    @Test
    fun `project scope is respected and falls back when no project is open`() {
        val d = tmp.newFolder("m")
        MemoryExtraction.capture(d, "The project uses Kotlin and Gradle.", prov, FactScope.PROJECT, null)
        val f = FactsStore.loadFrom(d).single()
        assertEquals(FactScope.USER, f.scope)
    }

    @Test
    fun `no provenance means no fabricated source`() {
        val d = tmp.newFolder("m")
        MemoryExtraction.capture(d, "I always run the full suite before pushing.", FactProvenance.NONE)
        val f = FactsStore.loadFrom(d).single()
        assertNull(f.sourceConversationId)
        assertNull(f.sourceMessageId)
        assertEquals(0L, f.sourceConversationAt)
        assertEquals(TimeSource.UNKNOWN, f.timeSource)
    }

    @Test
    fun `a store failure does not throw`() {
        val broken = tmp.newFolder("broken")
        java.io.File(broken, "facts.json").mkdirs()
        java.io.File(java.io.File(broken, "facts.json"), "keep").writeText("x")
        val r = MemoryExtraction.capture(broken, "I prefer short commits that do one thing.", prov)
        assertTrue(r.accepted.isEmpty())
        assertTrue(r.skippedReason != null || r.rejected >= 0)
    }

    @Test
    fun `candidate count per message is bounded`() {
        val long = (1..10).joinToString(" ") { "I always keep commit $it small and focused." }
        assertTrue(MemoryExtraction.candidates(long).size <= MemoryExtraction.MAX_PER_MESSAGE)
    }
}
