package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT EIGHT 8.14 (catalog 99): every failure lands in exactly one countable bucket. */
class FailureClusterTest {

    @Test
    fun everyFailureMapsToItsCluster() {
        assertEquals(FailureCluster.AUTH, ChatError.Auth("openai", "ai_openai", "no key").cluster())
        assertEquals(FailureCluster.CREDITS, ChatError.OutOfCredits("openai").cluster())
        assertEquals(FailureCluster.RATE_LIMIT, ChatError.RateLimited(30).cluster())
        assertEquals(FailureCluster.PAYLOAD, ChatError.PromptTooLarge(6_000, 6_980, "tokens", "too big").cluster())
        assertEquals(FailureCluster.MODEL, ChatError.ModelNotFound("m").cluster())
        assertEquals(FailureCluster.REQUEST, ChatError.BadRequest("bad").cluster())
        assertEquals(FailureCluster.PROVIDER_DOWN, ChatError.Server(500).cluster())
        assertEquals(FailureCluster.NETWORK, ChatError.Network("down").cluster())
        assertEquals(FailureCluster.BUDGET, ChatError.OverBudget("over").cluster())
        assertEquals(FailureCluster.USER_STOP, ChatError.Cancelled().cluster())
    }

    @Test
    fun clustersAreStableStringsSoTheyCanBeCountedOverTime() {
        assertEquals("rate_limit", FailureCluster.RATE_LIMIT.id)
        assertEquals(FailureCluster.entries.size, FailureCluster.entries.map { it.id }.toSet().size)
    }

    @Test
    fun theTallyCountsAndFindsTheWorst() {
        val t = FailureTally()
        assertNull("no failures means no worst", t.worst())
        repeat(3) { t.record(ChatError.RateLimited(5)) }
        t.record(ChatError.Network("x"))
        assertEquals(3, t.count(FailureCluster.RATE_LIMIT))
        assertEquals(1, t.count(FailureCluster.NETWORK))
        assertEquals(4, t.snapshot().values.sum())
        assertEquals(FailureCluster.RATE_LIMIT, t.worst())
        t.clear()
        assertTrue(t.snapshot().isEmpty())
    }
}
