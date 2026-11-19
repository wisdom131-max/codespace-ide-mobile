package com.codespace.ide.agent.facts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 6 — chronological retrieval.
 *
 * Two properties matter: time never silently overrides relevance, and an unknown date is never
 * upgraded to a verified one just because it is the only date available.
 */
class FactChronologyTest {

    private fun fact(
        id: String,
        content: String,
        scope: FactScope = FactScope.USER,
        projectId: String? = null,
        conversationAt: Long = 0L,
        messageAt: Long = 0L,
        source: TimeSource = TimeSource.UNKNOWN,
        status: FactStatus = FactStatus.ACTIVE,
        createdAt: Long = 1_000L,
    ) = Fact(
        id = id, content = content, scope = scope, projectId = projectId,
        status = status, createdAt = createdAt, updatedAt = createdAt,
        sourceConversationAt = conversationAt, sourceMessageAt = messageAt, timeSource = source,
    )

    @Test
    fun `a verified date outranks an unknown one at equal relevance`() {
        val verified = fact("a", "Deploy target is staging", conversationAt = 5_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val unknown = fact("b", "Deploy target is staging too")
        val ranked = FactRetrieval.temporalRank(listOf(unknown, verified), "deploy target", null)
        assertEquals("a", ranked.first().fact.id)
    }

    @Test
    fun `a user-stated date sits between verified and unknown`() {
        val verified = fact("a", "Release is Friday", conversationAt = 9_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val stated = fact("b", "Release is Friday", conversationAt = 9_000L, source = TimeSource.USER_STATED)
        val unknown = fact("c", "Release is Friday")
        val order = FactRetrieval.temporalRank(listOf(unknown, stated, verified), "release friday", null)
            .map { it.fact.id }
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `facts without dates are still retrievable`() {
        val undated = fact("a", "Uses the Gradle wrapper")
        val ranked = FactRetrieval.temporalRank(listOf(undated), "gradle wrapper", null)
        assertEquals(1, ranked.size)
        assertEquals("a", ranked.first().fact.id)
    }

    @Test
    fun `an unknown timestamp is never turned into a verified one`() {
        val f = fact("a", "Something", createdAt = 7_777L)
        assertEquals(0L, f.sourceConversationAt)
        assertTrue(!f.hasVerifiedTime())
        // factTime falls back to the save time, which is honest only because hasVerifiedTime says no
        assertEquals(7_777L, f.factTime())
    }

    @Test
    fun `equal timestamps order deterministically`() {
        val a = fact("a", "Same statement about builds", conversationAt = 4_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val b = fact("b", "Same statement about builds", conversationAt = 4_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val first = FactRetrieval.temporalRank(listOf(a, b), "builds", null).map { it.fact.id }
        val second = FactRetrieval.temporalRank(listOf(b, a), "builds", null).map { it.fact.id }
        assertEquals(first, second)
    }

    @Test
    fun `superseded facts stay out of chronological retrieval`() {
        val live = fact("a", "Deploy target is production", conversationAt = 9_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val retired = fact("b", "Deploy target is staging", conversationAt = 8_000L,
            source = TimeSource.TRANSCRIPT_VERIFIED, status = FactStatus.SUPERSEDED)
        val ids = FactRetrieval.chronological(listOf(live, retired)).map { it.id }
        assertEquals(listOf("a"), ids)
        assertEquals(listOf("b"), FactRetrieval.chronological(listOf(live, retired), includeHistory = true).map { it.id }.filter { it == "b" })
    }

    @Test
    fun `history is ordered newest supersession first`() {
        val older = fact("a", "Old one", status = FactStatus.SUPERSEDED).copy(supersededAt = 1_000L)
        val newer = fact("b", "Newer one", status = FactStatus.SUPERSEDED).copy(supersededAt = 2_000L)
        assertEquals(listOf("b", "a"), FactRetrieval.history(listOf(older, newer)).map { it.id })
    }

    @Test
    fun `project scope is respected by chronological retrieval`() {
        val mine = fact("a", "Targets API 34", FactScope.PROJECT, "p1")
        val other = fact("b", "Targets API 33", FactScope.PROJECT, "p2")
        val ids = FactRetrieval.chronological(listOf(mine, other), "p1").map { it.id }
        assertEquals(listOf("a"), ids)
    }

    @Test
    fun `latest returns the newest active fact and ignores retired ones`() {
        val old = fact("a", "Decision: use Room", conversationAt = 1_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val new = fact("b", "Decision: use Room 2", conversationAt = 3_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val retired = fact("c", "Decision: use SQLite", conversationAt = 9_000L,
            source = TimeSource.TRANSCRIPT_VERIFIED, status = FactStatus.SUPERSEDED)
        assertEquals("b", FactRetrieval.latest(listOf(old, new, retired))?.id)
    }

    @Test
    fun `latest on an empty store is null rather than a guess`() {
        assertNull(FactRetrieval.latest(emptyList()))
    }

    @Test
    fun `relevance still wins over a newer but unrelated fact`() {
        val relevant = fact("a", "The build uses Gradle 8", conversationAt = 1_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val newerUnrelated = fact("b", "Prefers dark theme", conversationAt = 9_000L, source = TimeSource.TRANSCRIPT_VERIFIED)
        val ranked = FactRetrieval.temporalRank(listOf(newerUnrelated, relevant), "gradle build", null)
        assertEquals("a", ranked.first().fact.id)
    }

    @Test
    fun `conflicting facts remain visible to chronological retrieval`() {
        val a = fact("a", "Use tabs for indentation", conversationAt = 2_000L, source = TimeSource.TRANSCRIPT_VERIFIED, status = FactStatus.CONFLICT)
        val ranked = FactRetrieval.temporalRank(listOf(a), "indentation tabs", null)
        assertEquals(1, ranked.size)
    }
}
