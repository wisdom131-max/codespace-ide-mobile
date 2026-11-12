package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRetryTest {

    @Test fun aRateLimitIsRetriedAfterTheDelayTheProviderAskedFor() {
        val d = ProviderRetry.decide(ChatError.RateLimited(7), attempt = 1, sideEffectDone = false)
        assertTrue(d.retry)
        assertEquals(7_000L, d.delayMs)
    }

    @Test fun anAbsurdRetryAfterIsRefusedRatherThanWaitedOut() {
        val d = ProviderRetry.decide(ChatError.RateLimited(3_600), attempt = 1, sideEffectDone = false)
        assertFalse(d.retry)
        assertTrue(d.why.contains("3600"))
    }

    @Test fun aBadKeyIsNeverRetried() {
        val d = ProviderRetry.decide(ChatError.Auth("OpenAI", "openai"), attempt = 1, sideEffectDone = false)
        assertFalse(d.retry)
        assertTrue(d.why.contains("401"))
    }

    @Test fun outOfCreditsIsNotRetriedByWaiting() {
        val d = ProviderRetry.decide(ChatError.OutOfCredits("Top up the account."), attempt = 1, sideEffectDone = false)
        assertFalse(d.retry)
    }

    @Test fun aCompletedSideEffectOutranksEveryOtherReasonToRetry() {
        // Even a plain rate limit - the cheapest failure - must not re-run a write that landed.
        val d = ProviderRetry.decide(ChatError.RateLimited(1), attempt = 1, sideEffectDone = true)
        assertFalse(d.retry)
        assertTrue(d.why.contains("already changed something"))
    }

    @Test fun aDroppedStreamWithNoErrorBodyIsRetried() {
        val d = ProviderRetry.decide(error = null, attempt = 1, sideEffectDone = false)
        assertTrue(d.retry)
        assertEquals(ProviderRetry.BASE_DELAY_MS, d.delayMs)
    }

    @Test fun attemptsAreBoundedAndTheLastVerdictIsPassedOn() {
        val d = ProviderRetry.decide(ChatError.RateLimited(2), attempt = ProviderRetry.MAX_ATTEMPTS, sideEffectDone = false)
        assertFalse(d.retry)
        assertTrue(d.why.contains("Gave up"))
        assertTrue(d.why.contains("busy"))
    }

    @Test fun backoffDoublesAndStopsAtTheCeiling() {
        assertEquals(500L, ProviderRetry.backoff(1))
        assertEquals(1_000L, ProviderRetry.backoff(2))
        assertEquals(2_000L, ProviderRetry.backoff(3))
        assertEquals(ProviderRetry.MAX_DELAY_MS, ProviderRetry.backoff(20))
    }

    @Test fun aCeilingIsContinuedNotRetried() {
        assertFalse(ProviderRetry.retryableVerdict(FinishReason.OUTPUT_LIMIT))
        assertTrue(ProviderRetry.retryableVerdict(FinishReason.INTERRUPTED))
        // Absence of a verdict is not permission to repeat the request.
        assertTrue(ProviderRetry.retryableVerdict(FinishReason.UNKNOWN))
        assertFalse(ProviderRetry.retryableVerdict(FinishReason.CANCELLED))
        assertFalse(ProviderRetry.retryableVerdict(FinishReason.CONTENT_FILTER))
    }
}
