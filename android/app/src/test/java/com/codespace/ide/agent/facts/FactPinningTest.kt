package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.12 (catalog 37): a standing rule applies to every question, so it is pinned
 * ahead of the relevance filter. Everything else must still earn its place.
 *
 * These tests exist because pinning is the one change that could UNDO the invariant the rest of
 * FactRetrievalTest defends: that an unrelated fact is never injected just because it exists.
 */
class FactPinningTest {

    private fun fact(
        id: String,
        content: String,
        category: FactCategory = FactCategory.PREFERENCE,
        scope: FactScope = FactScope.USER,
        status: FactStatus = FactStatus.ACTIVE,
        confidence: FactConfidence = FactConfidence.STATED,
        updatedAt: Long = 0L,
    ) = Fact(
        id = id,
        content = content,
        scope = scope,
        category = category,
        status = status,
        confidence = confidence,
        updatedAt = updatedAt,
    )

    @Test
    fun aStandingRuleIsPinned() {
        val pinned = FactRetrieval.pinnedRules(
            listOf(fact("a", "Always use four spaces for indentation")), null,
        )
        assertEquals(1, pinned.size)
    }

    @Test
    fun aOneOffObservationIsNotPinned() {
        // The whole point of the wording test: this is a preference, but not a RULE.
        val pinned = FactRetrieval.pinnedRules(
            listOf(fact("a", "I liked the dark theme yesterday")), null,
        )
        assertTrue("a one-off must not be pinned", pinned.isEmpty())
    }

    @Test
    fun aProjectFactIsNeverPinned() {
        val pinned = FactRetrieval.pinnedRules(
            listOf(fact("a", "Always run the release script", scope = FactScope.PROJECT)), null,
        )
        assertTrue("project facts must not follow the user across projects", pinned.isEmpty())
    }

    @Test
    fun aNonPreferenceCategoryIsNotPinned() {
        val pinned = FactRetrieval.pinnedRules(
            listOf(fact("a", "Always build with gradle", category = FactCategory.ARCHITECTURE)), null,
        )
        assertTrue(pinned.isEmpty())
    }

    @Test
    fun aConflictedOrSupersededRuleIsNotPinned() {
        val conflicted = FactRetrieval.pinnedRules(
            listOf(fact("a", "Always use tabs", status = FactStatus.CONFLICT)), null,
        )
        val superseded = FactRetrieval.pinnedRules(
            listOf(fact("b", "Never use tabs", status = FactStatus.SUPERSEDED)), null,
        )
        assertTrue("a conflict is shown, not obeyed", conflicted.isEmpty())
        assertTrue("a superseded rule is gone", superseded.isEmpty())
    }

    @Test
    fun pinnedRulesAreCappedSoTheyCannotCrowdOutFacts() {
        val many = (1..10).map { fact("f$it", "Always do thing number $it") }
        assertEquals(FactRetrieval.MAX_PINNED_RULES, FactRetrieval.pinnedRules(many, null).size)
    }

    @Test
    fun aVerifiedRuleOutranksAStatedOne() {
        val pinned = FactRetrieval.pinnedRules(
            listOf(
                fact("stated", "Always use spaces", confidence = FactConfidence.STATED),
                fact("verified", "Never use tabs", confidence = FactConfidence.VERIFIED),
            ),
            null,
        )
        assertEquals("verified", pinned.first().id)
    }

    @Test
    fun aPinnedRuleReachesForRequestEvenWithNoOverlap() {
        // "Always use four spaces" shares no words with "what time is the build"
        val facts = listOf(fact("a", "Always use four spaces for indentation"))
        val got = FactRetrieval.forRequest(facts, "what time is the build", null)
        assertEquals("a standing rule must reach an unrelated request", listOf("a"), got.map { it.id })
    }

    @Test
    fun anUnrelatedOrdinaryFactIsStillExcluded() {
        // The invariant that must survive: an ordinary fact with no overlap is NOT injected.
        val facts = listOf(fact("a", "The project uses a hexagonal layout", category = FactCategory.ARCHITECTURE))
        val got = FactRetrieval.forRequest(facts, "what time is the build", null)
        assertTrue("unrelated facts must still be filtered out", got.isEmpty())
    }
}
