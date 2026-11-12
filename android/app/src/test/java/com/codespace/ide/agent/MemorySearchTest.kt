package com.codespace.ide.agent

import com.codespace.ide.agent.facts.Fact
import com.codespace.ide.agent.facts.FactCategory
import com.codespace.ide.agent.facts.FactConfidence
import com.codespace.ide.agent.facts.FactScope
import com.codespace.ide.agent.facts.FactSource
import com.codespace.ide.agent.facts.FactStatus
import com.codespace.ide.agent.facts.FactsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `memory_search` over the STRUCTURED store.
 *
 * The important cases are the negative ones: a superseded or deleted fact must not come back, and a
 * fact from another project or another session must not be reachable from here.
 */
class MemorySearchTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("search", "dir").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fact(
        id: String,
        content: String,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        category: FactCategory = FactCategory.GENERAL,
        source: FactSource = FactSource.EXPLICIT_USER,
        status: FactStatus = FactStatus.ACTIVE,
        sessionOwner: String = "",
        updatedAt: Long = 1_000L,
    ) = Fact(
        id = id, content = content, scope = scope, projectId = projectId, category = category,
        source = source, confidence = FactConfidence.STATED, status = status,
        createdAt = updatedAt, updatedAt = updatedAt, sessionOwner = sessionOwner,
    )

    @Test
    fun findsByContent() {
        val out = InsightTools.searchFacts(listOf(fact("f1", "The build uses gradle")), "gradle")
        assertTrue(out.contains("The build uses gradle"))
        assertTrue(out.contains("(1)"))
    }

    @Test
    fun noMatchSaysHowMuchWasSearched() {
        val out = InsightTools.searchFacts(listOf(fact("f1", "Uses gradle")), "kubernetes")
        assertTrue(out.contains("No memory matches"))
        assertTrue(out.contains("1 visible fact"))
    }

    @Test
    fun emptyQueryAsksForOne() {
        val out = InsightTools.searchFacts(listOf(fact("f1", "x y")), "  ")
        assertTrue(out.contains("Give a word or phrase"))
    }

    @Test
    fun emptyStoreSaysItIsEmpty() {
        val out = InsightTools.searchFacts(emptyList(), "gradle")
        assertTrue(out.contains("Memory is empty"))
    }

    @Test
    fun resultsCarryScopeCategoryAndProvenance() {
        val out = InsightTools.searchFacts(
            listOf(fact("f1", "Run the tests before pushing", FactScope.PROJECT, "p1",
                FactCategory.TEST)), "tests",
        )
        assertTrue(out.contains("[project/test]"))
        assertTrue(out.contains("you said"))
    }

    @Test
    fun rankingPrefersAMoreSpecificMatch() {
        // Both match "gradle"; only one matches BOTH query terms, and it must come first.
        val weak = fact("weak", "The build uses gradle", updatedAt = 5_000L)
        val strong = fact("strong", "The build uses gradle wrapper with assembleDevDebug")
        val out = InsightTools.searchFacts(listOf(weak, strong), "gradle assembleDevDebug")
        val strongAt = out.indexOf("strong:")
        val weakAt = out.indexOf("weak:")
        assertTrue(out, strongAt >= 0 && weakAt >= 0)
        assertTrue(out, strongAt < weakAt)
    }

    @Test
    fun resultsAreBoundedByTheLimit() {
        val facts = (1..30).map { fact("f$it", "gradle fact number $it") }
        val out = InsightTools.searchFacts(facts, "gradle", limit = 3)
        assertTrue(out.contains("...and 27 more"))
    }

    @Test
    fun aConflictedFactIsReportedLowerAndLabelled() {
        val out = InsightTools.searchFacts(
            listOf(fact("c", "Indentation is tabs", status = FactStatus.CONFLICT),
                fact("ok", "Indentation is tabs for kotlin")),
            "indentation tabs",
        )
        assertTrue(out.contains("CONFLICTED"))
    }

    @Test
    fun supersededFactsAreNotSearchable() {
        val a = (FactsStore.saveTo(dir, "Old build rule about gradle", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        val b = (FactsStore.saveTo(dir, "New build rule about gradle wrapper", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        FactsStore.supersedeIn(dir, a.id, b.id)
        val visible = AgentMemory.visibleFactsIn(dir)
        val out = InsightTools.searchFacts(visible, "gradle")
        assertFalse(out.contains("Old build rule"))
        assertTrue(out.contains("New build rule"))
    }

    @Test
    fun deletedFactsAreNotSearchable() {
        val f = (FactsStore.saveTo(dir, "Delete me about gradle", FactScope.USER, null)
            as FactsStore.SaveResult.Saved).fact
        FactsStore.deleteIn(dir, f.id)
        val out = InsightTools.searchFacts(AgentMemory.visibleFactsIn(dir), "gradle")
        assertTrue(out.contains("Memory is empty"))
    }

    @Test
    fun projectIsolationHolds() {
        FactsStore.saveTo(dir, "gradle build for project a", FactScope.PROJECT, "proj-a")
        FactsStore.saveTo(dir, "gradle build for project b", FactScope.PROJECT, "proj-b")
        val inA = InsightTools.searchFacts(AgentMemory.visibleFactsIn(dir, projectId = "proj-a"), "gradle")
        assertTrue(inA.contains("project a"))
        assertFalse(inA.contains("project b"))
    }

    @Test
    fun sessionIsolationHoldsUnderCurrentSessionScope() {
        FactsStore.saveTo(dir, "gradle from session a", FactScope.USER, null,
            sessionOwner = "sess-a")
        val mine = InsightTools.searchFacts(
            AgentMemory.visibleFactsIn(dir, MemoryScope.CURRENT_SESSION, "sess-b"), "gradle")
        assertTrue(mine.contains("Memory is empty"))
        val theirs = InsightTools.searchFacts(
            AgentMemory.visibleFactsIn(dir, MemoryScope.CURRENT_SESSION, "sess-a"), "gradle")
        assertTrue(theirs.contains("session a"))
    }

    @Test
    fun categoryIsSearchableAsAWord() {
        val out = InsightTools.searchFacts(
            listOf(fact("f1", "Run ./gradlew test", category = FactCategory.TEST)), "test")
        assertTrue(out.contains("[user/test]"))
    }

    @Test
    fun aLegacyKeyOutranksAValueMatch() {
        val byKey = fact("k", "Something else entirely").copy(legacyKey = "gradle")
        val byValue = fact("v", "gradle appears only in the value")
        val out = InsightTools.searchFacts(listOf(byValue, byKey), "gradle")
        // The key match is ranked above the value-only match, so its line is printed first.
        val keyLine = out.indexOf("Something else entirely")
        val valueLine = out.indexOf("appears only in the value")
        assertTrue(out, keyLine >= 0 && valueLine >= 0)
        assertTrue(out, keyLine < valueLine)
    }
}
