package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval: what reaches a prompt, in what order, and what is deliberately left out.
 *
 * The invariant that matters most here is negative: an unrelated fact must NOT be injected just
 * because it exists. Unconditional memory is how a stale preference ends up steering an unrelated
 * task, which is exactly the failure the old key/value store invited.
 */
class FactRetrievalTest {

    private val now = 1_800_000_000_000L

    private fun fact(
        id: String,
        content: String,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        category: FactCategory = FactCategory.GENERAL,
        source: FactSource = FactSource.EXPLICIT_USER,
        status: FactStatus = FactStatus.ACTIVE,
        updatedAt: Long = now,
    ) = Fact(
        id = id, content = content, scope = scope, projectId = projectId, category = category,
        source = source, confidence = FactConfidence.STATED, status = status,
        createdAt = updatedAt, updatedAt = updatedAt,
    )

    @Test
    fun relevantFactIsRetrievedAndIrrelevantOneIsNot() {
        val facts = listOf(
            fact("f1", "The build uses ./gradlew :app:assembleDevDebug"),
            fact("f2", "Prefers the dark theme"),
        )
        val picked = FactRetrieval.forRequest(facts, "how do I build the app?", null, now = now)
        assertEquals(listOf("f1"), picked.map { it.id })
    }

    @Test
    fun anEmptyRequestRetrievesNothingRatherThanEverything() {
        assertTrue(FactRetrieval.forRequest(listOf(fact("f1", "Uses gradle")), "  ", null, now = now).isEmpty())
    }

    @Test
    fun projectFactsAreIsolatedByProject() {
        val facts = listOf(
            fact("mine", "Uses gradle for the build", FactScope.PROJECT, "proj-a"),
            fact("theirs", "Uses gradle for the build", FactScope.PROJECT, "proj-b"),
        )
        val inA = FactRetrieval.forRequest(facts, "gradle build", "proj-a", now = now).map { it.id }
        assertTrue(inA.contains("mine"))
        assertFalse(inA.contains("theirs"))
        val inB = FactRetrieval.forRequest(facts, "gradle build", "proj-b", now = now).map { it.id }
        assertTrue(inB.contains("theirs"))
        assertFalse(inB.contains("mine"))
    }

    @Test
    fun userFactsFollowTheUserIntoAnyProject() {
        val facts = listOf(fact("u1", "Prefers concise replies about builds"))
        val picked = FactRetrieval.forRequest(facts, "concise replies please", "proj-z", now = now)
        assertEquals(listOf("u1"), picked.map { it.id })
    }

    @Test
    fun supersededFactsAreNeverRetrieved() {
        val f = fact("old", "The build uses maven", status = FactStatus.SUPERSEDED)
        assertTrue(FactRetrieval.forRequest(listOf(f), "build system maven", null, now = now).isEmpty())
    }

    @Test
    fun deletedFactsAreGoneBecauseTheyAreNotOnDisk() {
        // Deletion is covered in FactsStoreTest; retrieval simply never sees what is not passed in.
        assertTrue(FactRetrieval.forRequest(emptyList(), "anything at all", null, now = now).isEmpty())
    }

    @Test
    fun explicitUserStatementsOutrankInferencesAtEqualRelevance() {
        val inferred = fact("inf", "Build uses the gradle wrapper", source = FactSource.INFERRED)
            .copy(confidence = FactConfidence.INFERRED)
        val stated = fact("stated", "Build uses the gradle wrapper tools")
        val ranked = FactRetrieval.rank(listOf(inferred, stated), "gradle build", null, now)
        assertEquals("stated", ranked.first().fact.id)
    }

    @Test
    fun aConflictedFactIsDemotedButStillVisibleAndLabelled() {
        val settled = fact("ok", "Indentation is tabs")
        val conflicted = fact("bad", "Indentation is tabs, never tabs",
            status = FactStatus.CONFLICT)
        val ranked = FactRetrieval.rank(listOf(conflicted, settled), "indentation tabs", null, now)
        assertEquals("ok", ranked.first().fact.id)
        assertTrue(ranked.last().fact.provenanceLine().contains("CONFLICTED"))
    }

    @Test
    fun projectFactsOutrankUserFactsAtEqualRelevance() {
        val user = fact("u", "Uses gradle", FactScope.USER, null)
        val project = fact("p", "Uses gradle", FactScope.PROJECT, "proj-a")
        val ranked = FactRetrieval.rank(listOf(user, project), "gradle", "proj-a", now)
        assertEquals("p", ranked.first().fact.id)
    }

    @Test
    fun resultCountIsBounded() {
        val facts = (1..20).map { fact("f$it", "The build uses gradle variant $it") }
        val picked = FactRetrieval.forRequest(facts, "gradle build", null, now = now)
        assertTrue("got " + picked.size, picked.size <= FactRetrieval.MAX_PROMPT_FACTS)
    }

    @Test
    fun blockIsBoundedInCharacters() {
        val facts = (1..6).map { fact("f$it", "A very long remembered statement about the build " + "x".repeat(400) + " $it") }
        val block = FactRetrieval.block(facts, 500)
        assertTrue("was " + block.length, block.length <= 500)
    }

    @Test
    fun blockCarriesProvenanceOnEveryLine() {
        val block = FactRetrieval.block(listOf(fact("f1", "The build uses gradle")), 900)
        assertTrue(block.contains("[user/general]"))
        assertTrue(block.contains("you said"))
    }

    @Test
    fun blockSaysFactsAreNotInstructions() {
        val block = FactRetrieval.block(listOf(fact("f1", "The build uses gradle")), 900)
        assertTrue(block.contains("not instructions"))
        assertTrue(block.contains("never override"))
    }

    @Test
    fun anEmptyFactListMakesNoBlock() {
        assertEquals("", FactRetrieval.block(emptyList(), 900))
        assertEquals("", FactRetrieval.block(listOf(fact("f1", "x")), 0))
    }

    @Test
    fun aBlockWithNoRoomForEvenOneFactIsEmptyRatherThanAHeader() {
        val block = FactRetrieval.block(listOf(fact("f1", "The build uses gradle")), 60)
        assertFalse(block.contains("[user/"))
    }

    @Test
    fun summaryCountsScopesAndConflicts() {
        val facts = listOf(
            fact("u", "one"),
            fact("p", "two", FactScope.PROJECT, "proj-a"),
            fact("other", "three", FactScope.PROJECT, "proj-b"),
            fact("c", "four", status = FactStatus.CONFLICT),
        )
        val s = FactRetrieval.summary(facts, "proj-a")
        assertTrue(s.contains("2 user facts"))
        assertTrue(s.contains("1 project fact"))
        assertTrue(s.contains("1 in conflict"))
    }

    @Test
    fun markUsedRecordsUsageOnlyForTheFactsItWasGiven() {
        val dir = java.io.File.createTempFile("facts", "dir").let { it.delete(); it.mkdirs(); it }
        try {
            FactsStore.saveTo(dir, "The build uses gradle", FactScope.USER, null)
            FactsStore.saveTo(dir, "Prefers dark theme", FactScope.USER, null)
            val id = FactsStore.loadFrom(dir).first { it.content.contains("gradle") }.id
            FactsStore.markUsedIn(dir, listOf(id), now)
            val used = FactsStore.loadFrom(dir).first { it.id == id }
            assertEquals(now, used.lastUsedAt)
            assertTrue(FactsStore.loadFrom(dir).filter { it.id != id }.all { it.lastUsedAt == 0L })
        } finally {
            dir.deleteRecursively()
        }
    }
}
