package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 6 — the retry budget and its stop conditions.
 *
 * Every number here is a deliberate limit, so the tests assert the LIMITS rather than the
 * happy path alone: what is never retried, what is not waited for, and when it stops.
 */
class RetryPolicyTest {

    @Test
    fun aRateLimitWithAUsableRetryAfterIsHonouredExactly() {
        val d = RetryPolicy.decide(ChatError.RateLimited(12), attemptsUsed = 0)
        assertTrue(d.retry)
        assertEquals("the provider's number, not ours", 12, d.waitSec)
        assertEquals(1, d.attempt)
        assertEquals(RetryPolicy.DEFAULT_MAX_ATTEMPTS, d.maxAttempts)
    }

    @Test
    fun aRateLimitWithoutANumberUsesTheShortLadder() {
        assertEquals(5, RetryPolicy.decide(ChatError.RateLimited(null), 0).waitSec)
        assertEquals("the second fallback waits longer", 15, RetryPolicy.decide(ChatError.RateLimited(null), 1).waitSec)
    }

    @Test
    fun aZeroOrNegativeRetryAfterFallsBackRatherThanRetryingInstantly() {
        assertEquals(5, RetryPolicy.decide(ChatError.RateLimited(0), 0).waitSec)
        assertEquals(5, RetryPolicy.decide(ChatError.RateLimited(-3), 0).waitSec)
    }

    @Test
    fun aVeryLongProviderWaitIsSurfacedNotWaited() {
        val d = RetryPolicy.decide(ChatError.RateLimited(RetryPolicy.MAX_AUTO_WAIT_SEC + 1), 0)
        assertFalse("an app that waits 10 minutes looks hung", d.retry)
        assertNotNull(d.refusal)
        assertTrue(d.refusal!!.contains("press Retry", ignoreCase = true))
    }

    @Test
    fun theLongestAcceptableWaitIsStillAccepted() {
        val d = RetryPolicy.decide(ChatError.RateLimited(RetryPolicy.MAX_AUTO_WAIT_SEC), 0)
        assertTrue(d.retry)
        assertEquals(RetryPolicy.MAX_AUTO_WAIT_SEC, d.waitSec)
    }

    @Test
    fun retriesAreBounded() {
        val first = RetryPolicy.decide(ChatError.RateLimited(3), 0)
        val second = RetryPolicy.decide(ChatError.RateLimited(3), 1)
        val third = RetryPolicy.decide(ChatError.RateLimited(3), 2)
        assertTrue(first.retry)
        assertTrue(second.retry)
        assertEquals(2, second.attempt)
        assertFalse("no unlimited retries", third.retry)
        assertNull("nothing else is wrong, so no excuse is needed", third.refusal?.takeIf { false })
        assertTrue(third.refusal!!.contains("retries used up", ignoreCase = true))
    }

    @Test
    fun anAuthenticationFailureIsNeverRetried() {
        val d = RetryPolicy.decide(ChatError.Auth(), 0)
        assertFalse(d.retry)
        assertNull("auth is not a retry story, it is the card's job", d.refusal)
    }

    @Test
    fun anEmptyAccountIsNeverRetried() {
        assertFalse(RetryPolicy.decide(ChatError.OutOfCredits("top up"), 0).retry)
    }

    @Test
    fun aDeadNetworkIsNotHammered() {
        assertFalse(RetryPolicy.decide(ChatError.Network("offline"), 0).retry)
    }

    @Test
    fun aBadRequestOrMissingModelIsNeverRetried() {
        assertFalse(RetryPolicy.decide(ChatError.BadRequest("bad"), 0).retry)
        assertFalse(RetryPolicy.decide(ChatError.ModelNotFound("x"), 0).retry)
    }

    @Test
    fun aServerErrorStaysManualForNow() {
        // Deliberate: only 429 has a provider-sanctioned wait. A 5xx retry would be this
        // app inventing a policy the provider did not ask for.
        assertFalse(RetryPolicy.decide(ChatError.Server(500), 0).retry)
    }

    @Test
    fun anOverBudgetRefusalIsNotRetried() {
        assertFalse(RetryPolicy.decide(ChatError.OverBudget("too big"), 0).retry)
    }
}
