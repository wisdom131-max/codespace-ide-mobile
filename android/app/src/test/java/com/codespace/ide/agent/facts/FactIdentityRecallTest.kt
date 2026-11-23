package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OWNER REPORT (2026-11-23): asked what his name was mid-chat, the copilot said it did not know -
 * while the name sat in memory. The fact was never lost; the overlap rule made it unreachable by the
 * one question that needed it. These tests pin the fix: identity is context, not a search result.
 */
class FactIdentityRecallTest {

    private val now = 1_800_000_000_000L

    private fun fact(
        id: String,
        content: String,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        category: FactCategory = FactCategory.GENERAL,
        updatedAt: Long = now,
    ) = Fact(
        id = id, content = content, scope = scope, projectId = projectId, category = category,
        source = FactSource.EXPLICIT_USER, confidence = FactConfidence.STATED,
        status = FactStatus.ACTIVE, createdAt = updatedAt, updatedAt = updatedAt,
    )

    @Test
    fun theNameReachesAQuestionThatSharesNoWordsWithIt() {
        // "who am i" and "The user is Ijezie Wisdom" have nothing in common lexically. Before the
        // fix that meant the name was filtered out; the copilot then said it did not know.
        val facts = listOf(fact("name", "The user is Ijezie Wisdom"))
        val picked = FactRetrieval.forRequest(facts, "who am i?", null, now = now)
        assertEquals(listOf("name"), picked.map { it.id })
    }

    @Test
    fun identityIsRetrievedEvenWhenTheRequestIsOnlyAGreeting() {
        val facts = listOf(fact("name", "The user is Ijezie Wisdom"))
        val picked = FactRetrieval.forRequest(facts, "hi", null, now = now)
        assertEquals(listOf("name"), picked.map { it.id })
    }

    @Test
    fun whereThePersonLivesCountsAsIdentityToo() {
        val facts = listOf(fact("home", "I live in Lagos, Nigeria"))
        assertTrue(FactRetrieval.identityFacts(facts, null).any { it.id == "home" })
    }

    @Test
    fun identityDoesNotDragUnrelatedFactsIntoThePrompt() {
        // The failure this must not create: pinning turning into "send the whole memory database".
        val facts = listOf(
            fact("name", "The user is Ijezie Wisdom"),
            fact("build", "The build uses ./gradlew :app:assembleDevDebug"),
        )
        val picked = FactRetrieval.forRequest(facts, "who am i?", null, now = now)
        assertEquals(listOf("name"), picked.map { it.id })
        assertFalse(picked.any { it.id == "build" })
    }

    @Test
    fun identityStaysCappedSoItCannotBecomeASecondMemoryDump() {
        val facts = (1..5).map { fact("n" + it, "My name is Person" + it, updatedAt = now - it) }
        assertEquals(FactRetrieval.MAX_IDENTITY_FACTS, FactRetrieval.identityFacts(facts, null).size)
    }

    @Test
    fun aPassingRemarkIsNotPromotedToIdentity() {
        // Narrow on purpose: "I am using Kotlin today" is not who the person is.
        val facts = listOf(fact("tmp", "I am using Kotlin today"))
        assertTrue(FactRetrieval.identityFacts(facts, null).isEmpty())
    }
}
