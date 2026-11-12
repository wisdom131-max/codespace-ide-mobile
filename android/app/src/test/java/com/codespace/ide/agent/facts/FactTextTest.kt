package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The similarity engine, as rules rather than as a description.
 *
 * These are the numbers the store's save policy depends on: if a correction stopped scoring above
 * [FactText.SAME], every correction would silently become a duplicate fact instead.
 */
class FactTextTest {

    @Test
    fun tokensDropStopwordsAndPunctuation() {
        assertEquals(listOf("build", "gradlew", "assemble"), FactText.tokens("Build with ./gradlew assemble!"))
    }

    @Test
    fun identicalStatementsScoreOne() {
        assertEquals(1.0, FactText.similarity("the build uses gradle", "the build uses gradle"), 0.0001)
    }

    @Test
    fun unrelatedStatementsScoreZero() {
        assertEquals(0.0, FactText.similarity("uses gradle for builds", "prefers dark theme"), 0.0001)
    }

    @Test
    fun sameFactInDifferentWordsIsAboveTheSameThreshold() {
        val score = FactText.similarity(
            "the project builds with gradle",
            "the project builds with gradle wrapper",
        )
        assertTrue("score was $score", score >= FactText.SAME)
    }

    @Test
    fun aShortRestatementOfALongerFactStillMatches() {
        // Containment is what makes "uses Gradle" match "this project uses Gradle 8 with Kotlin DSL".
        val score = FactText.similarity(
            "uses gradle",
            "this project uses gradle 8 with kotlin dsl",
        )
        assertTrue("score was $score", score >= FactText.RELATED)
    }

    @Test
    fun oneTokenStatementsOnlyMatchExactly() {
        assertEquals(1.0, FactText.similarity("2026", "2026"), 0.0001)
        assertEquals(0.0, FactText.similarity("2026", "2027"), 0.0001)
    }

    @Test
    fun negationIsDetectedInItsRealForms() {
        assertTrue(FactText.isNegative("never use tabs"))
        assertTrue(FactText.isNegative("do not run the full suite"))
        assertTrue(FactText.isNegative("don't touch the editor"))
        // A word that merely CONTAINS a negation is not a negation.
        assertFalse(FactText.isNegative("notable improvements"))
        assertFalse(FactText.isNegative("uses gradle"))
    }

    @Test
    fun contradictionNeedsSharedSubjectAndOppositePolarity() {
        assertTrue(FactText.contradicts("use tabs for indentation", "never use tabs for indentation"))
        assertFalse(FactText.contradicts("use tabs for indentation", "prefers the dark theme"))
        // Same words, same polarity: a restatement, not an argument.
        assertFalse(FactText.contradicts("use tabs", "use tabs"))
    }

    @Test
    fun classifyNewWhenNothingIsClose() {
        val m = FactDedupe.classify("prefers dark theme", FactScope.USER, null, emptyList())
        assertEquals(FactDecision.NEW, m.decision)
    }

    @Test
    fun classifyDuplicateWhenTheStatementIsTheSame() {
        val f = fact("The build uses Gradle", FactScope.USER, null)
        assertEquals(FactDecision.DUPLICATE,
            FactDedupe.classify("the build uses gradle", FactScope.USER, null, listOf(f)).decision)
    }

    @Test
    fun classifyCorrectionWhenTheSameFactIsRefined() {
        val f = fact("The build uses Gradle", FactScope.USER, null)
        val d = FactDedupe.classify("The build uses Gradle 8 with the wrapper", FactScope.USER, null, listOf(f))
        assertEquals(FactDecision.CORRECTION, d.decision)
        assertEquals(f.id, d.targetId)
    }

    @Test
    fun classifyConflictWhenTheFactIsNegated() {
        val f = fact("use tabs for indentation", FactScope.USER, null)
        val d = FactDedupe.classify("never use tabs for indentation", FactScope.USER, null, listOf(f))
        assertEquals(FactDecision.CONFLICT, d.decision)
        assertEquals(f.id, d.targetId)
    }

    @Test
    fun projectFactsNeverCollideAcrossProjects() {
        val f = fact("the build uses gradle", FactScope.PROJECT, "proj-a")
        val d = FactDedupe.classify("the build uses gradle", FactScope.PROJECT, "proj-b", listOf(f))
        assertEquals(FactDecision.NEW, d.decision)
    }

    @Test
    fun aUserFactIsNeverComparedWithAProjectFact() {
        val f = fact("the build uses gradle", FactScope.PROJECT, "proj-a")
        val d = FactDedupe.classify("the build uses gradle", FactScope.USER, null, listOf(f))
        assertEquals(FactDecision.NEW, d.decision)
    }

    @Test
    fun supersededFactsAreNotDedupeTargets() {
        val f = fact("the build uses gradle", FactScope.USER, null).copy(status = FactStatus.SUPERSEDED)
        assertEquals(FactDecision.NEW,
            FactDedupe.classify("the build uses gradle", FactScope.USER, null, listOf(f)).decision)
    }

    @Test
    fun punctuationAndCaseDoNotMakeTwoFacts() {
        assertEquals(
            FactDedupe.normalizeForCompare("The build: Gradle!"),
            FactDedupe.normalizeForCompare("the build gradle"),
        )
    }

    private fun fact(content: String, scope: FactScope, projectId: String?) = Fact(
        id = "f_test", content = content, scope = scope, projectId = projectId,
        createdAt = 1L, updatedAt = 1L,
    )
}
