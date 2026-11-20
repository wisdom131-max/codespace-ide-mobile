package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactsLexicalRetrievalTest {

    private fun fact(id: String, content: String, project: String? = null, source: FactSource = FactSource.EXPLICIT_USER) =
        Fact(
            id = id, content = content,
            scope = if (project == null) FactScope.USER else FactScope.PROJECT,
            projectId = project, source = source, confidence = FactConfidence.STATED,
            createdAt = 1_000L, updatedAt = 1_000L,
        )

    @Test
    fun `an exact identifier in a fact outranks a vaguer one`() {
        val facts = listOf(
            fact("a", "the store keeps facts and other things"),
            fact("b", "FactsStore.kt holds the persisted facts"),
        )
        assertEquals("b", FactRetrieval.rank(facts, "FactsStore.kt", null).first().fact.id)
    }

    @Test
    fun `an exact phrase helps`() {
        val facts = listOf(
            fact("a", "gradle takes a while on this phone"),
            fact("b", "the gradle daemon crashed with exit code 1"),
        )
        assertEquals("b", FactRetrieval.rank(facts, "gradle daemon crashed", null).first().fact.id)
    }

    @Test
    fun `scope isolation survives the lexical bonus`() {
        val facts = listOf(
            fact("user", "FactsStore.kt belongs to another project", null),
            fact("proj", "FactsStore.kt is here", "p1"),
        )
        val ranked = FactRetrieval.rank(facts, "FactsStore.kt", "p2")
        assertTrue(ranked.none { it.fact.id == "proj" })
    }

    @Test
    fun `existing token ranking still works`() {
        val facts = listOf(fact("a", "terminal command execution"), fact("b", "unrelated note"))
        assertEquals("a", FactRetrieval.rank(facts, "terminal command", null).first().fact.id)
    }

    @Test
    fun `a superseded fact never returns`() {
        val f = fact("r", "FactsStore.kt is superseded").copy(status = FactStatus.SUPERSEDED)
        assertTrue(FactRetrieval.rank(listOf(f), "FactsStore.kt", null).isEmpty())
    }

    @Test
    fun `provenance is preserved through ranking`() {
        val f = fact("p", "FactsStore.kt matters", source = FactSource.VERIFIED_FILE)
        assertEquals(FactSource.VERIFIED_FILE, FactRetrieval.rank(listOf(f), "FactsStore.kt", null).first().fact.source)
    }
}
