package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorAdviceTest {

    @Test
    fun everyClassGetsAHeadline() {
        for (c in ErrorClass.values()) {
            assertTrue("$c has no headline", ErrorAdvice.headline(c).isNotBlank())
        }
    }

    @Test
    fun adviceIsOfferedExceptForTheUnknown() {
        for (c in ErrorClass.values()) {
            val advice = ErrorAdvice.advice(c)
            if (c == ErrorClass.UNKNOWN) {
                assertEquals("nothing honest to suggest for an unknown failure", "", advice)
            } else {
                assertTrue("$c has no advice", advice.isNotBlank())
            }
        }
    }

    @Test
    fun authAndQuotaAreNotOfferedAsRetryable() {
        // Offering a retry for a wrong key or an exhausted quota teaches the user that the retry
        // button does nothing, which costs more trust than the missing button would.
        assertFalse(ErrorAdvice.retryable(ErrorClass.AUTH_INVALID))
        assertFalse(ErrorAdvice.retryable(ErrorClass.AUTH_MISSING))
        assertFalse(ErrorAdvice.retryable(ErrorClass.QUOTA_EXHAUSTED))
        assertFalse(ErrorAdvice.retryable(ErrorClass.CONTEXT_EXCEEDED))
    }

    @Test
    fun transientFailuresAreRetryable() {
        assertTrue(ErrorAdvice.retryable(ErrorClass.RATE_LIMITED))
        assertTrue(ErrorAdvice.retryable(ErrorClass.NETWORK))
        assertTrue(ErrorAdvice.retryable(ErrorClass.TIMEOUT))
        assertTrue(ErrorAdvice.retryable(ErrorClass.PROVIDER_UNAVAILABLE))
    }
}
