package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE 5.3 — the evidence, and the decision made from it. */
class Stage53ErrorRecoveryTest {

    private fun gemini(code: Int, status: String, message: String) =
        """{"error":{"code":$code,"status":"$status","message":"$message"}}"""

    @Test
    fun `gemini error body is read`() {
        val f = ProviderErrors.extract("gemini", 429, gemini(429, "RESOURCE_EXHAUSTED", "Quota exceeded"))
        assertEquals("gemini", f.shape)
        assertEquals("RESOURCE_EXHAUSTED", f.code)
        assertEquals(ErrorClass.QUOTA_EXHAUSTED, ProviderErrors.classify(f))
    }

    @Test
    fun `anthropic error body is read`() {
        val f = ProviderErrors.extract("anthropic", 400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 250000 tokens"}}""")
        assertEquals("anthropic", f.shape)
        assertEquals(ErrorClass.CONTEXT_EXCEEDED, ProviderErrors.classify(f))
    }

    @Test
    fun `openai error body is read`() {
        val f = ProviderErrors.extract("openai", 401,
            """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}""")
        assertEquals("openai", f.shape)
        assertEquals(ErrorClass.AUTH_INVALID, ProviderErrors.classify(f))
    }

    @Test
    fun `a custom endpoint error that matches nothing stays unknown`() {
        val f = ProviderErrors.extract("custom", 418, """{"weird":{"field":true}}""")
        assertEquals(ErrorClass.UNKNOWN, ProviderErrors.classify(f))
    }

    @Test
    fun `a malformed body does not throw and is read only from the status`() {
        val f = ProviderErrors.extract("openai", 500, "<html>gateway</html>")
        assertEquals("", f.message)
        // The body said nothing usable, but the status is still evidence: a server error.
        assertEquals(ErrorClass.PROVIDER_UNAVAILABLE, ProviderErrors.classify(f))
    }

    @Test
    fun `an overloaded provider is availability, not a rate limit`() {
        val f = ProviderErrors.extract("anthropic", 529,
            """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        assertEquals(ErrorClass.PROVIDER_UNAVAILABLE, ProviderErrors.classify(f))
    }

    @Test
    fun `a key inside an error message is redacted`() {
        val f = ProviderErrors.extract("openai", 401,
            """{"error":{"message":"Incorrect API key provided: sk-abcdef0123456789abcdef"}}""")
        assertFalse(f.safeMessage.contains("sk-abcdef"))
        assertTrue(f.safeMessage.contains("<redacted>"))
    }

    @Test
    fun `a credential in a query string is redacted`() {
        val out = Redaction.sanitize("GET https://x.example/v1?key=AIzaSuperSecretValue123&alt=sse")
        assertFalse(out.contains("AIzaSuperSecretValue123"))
        assertTrue(out.contains("key=<redacted>"))
    }

    @Test
    fun `throwables are classified as network or timeout`() {
        assertEquals(ErrorClass.TIMEOUT, ProviderErrors.classify(java.io.IOException("read timed out")))
        assertEquals(ErrorClass.NETWORK, ProviderErrors.classify(java.io.IOException("Unable to resolve host")))
    }

    // ---- decisions ---------------------------------------------------------------------------

    private fun plan(status: Int, body: String, attempt: Int = 1, sideEffect: Boolean = false,
                     alternative: String? = null) = RecoveryPlanner.plan(
        ProviderErrors.extract("p", status, body), attempt, sideEffect, alternative)

    @Test
    fun `a rejected key stops and asks the user`() {
        val p = plan(401, gemini(401, "UNAUTHENTICATED", "API key not valid"))
        assertEquals(RecoveryAction.USER_ACTION_REQUIRED, p.action)
        assertFalse(p.retryable)
    }

    @Test
    fun `a temporary rate limit waits then retries`() {
        val p = plan(429, """{"error":{"code":"rate_limit_exceeded","message":"Rate limit reached"}}""")
        assertEquals(RecoveryAction.WAIT_AND_RETRY, p.action)
        assertTrue(p.retryable)
        assertEquals(ProviderRetry.backoff(1), p.waitMs)
    }

    @Test
    fun `retry-after is honoured`() {
        val facts = ProviderErrors.extract("openai", 429, """{"error":{"code":"rate_limit","message":"slow down"}}""",
            mapOf("Retry-After" to "7"))
        assertEquals(7_000L, RecoveryPlanner.plan(facts, 1).waitMs)
    }

    @Test
    fun `an absurd retry-after is refused rather than held open`() {
        val facts = ProviderErrors.extract("openai", 429, """{"error":{"code":"rate_limit","message":"slow"}}""",
            mapOf("retry-after" to "900"))
        val p = RecoveryPlanner.plan(facts, 1)
        assertEquals(RecoveryAction.USER_ACTION_REQUIRED, p.action)
        assertNull(p.waitMs)
    }

    @Test
    fun `quota exhaustion is not a temporary limit`() {
        val p = plan(429, """{"error":{"code":"insufficient_quota","message":"You exceeded your quota"}}""")
        assertEquals(RecoveryAction.USER_ACTION_REQUIRED, p.action)
        assertFalse(p.retryable)
    }

    @Test
    fun `the attempt ceiling stops the ladder`() {
        val p = plan(503, gemini(503, "UNAVAILABLE", "try again later"), attempt = ProviderRetry.MAX_ATTEMPTS)
        assertFalse(p.retryable)
        assertEquals(RecoveryAction.PERMANENT_FAILURE, p.action)
    }

    @Test
    fun `a context overrun asks for reduction, not a retry`() {
        val p = plan(400, gemini(400, "INVALID_ARGUMENT", "The input is too long for this model"))
        assertEquals(RecoveryAction.CONTEXT_REDUCTION_REQUIRED, p.action)
        assertFalse(p.retryable)
    }

    @Test
    fun `unknown model limits are stated rather than invented`() {
        val p = RecoveryPlanner.plan(
            ProviderErrors.extract("custom", 400, """{"error":{"message":"maximum context length exceeded"}}"""),
            1, contextLimitsKnown = false)
        assertTrue(p.why.contains("not known"))
    }

    @Test
    fun `an unsupported feature offers a model instead of switching to one`() {
        val p = plan(400, """{"error":{"message":"this model does not support tools"}}""", alternative = "Gemini 3 Pro")
        assertEquals(RecoveryAction.ALTERNATIVE_MODEL_AVAILABLE, p.action)
        assertEquals("Gemini 3 Pro", p.alternativeModel)
        assertTrue(p.why.contains("if you switch"))
    }

    @Test
    fun `no alternative means a configuration answer, never a silent switch`() {
        val p = plan(400, """{"error":{"message":"model not found"}}""")
        assertEquals(RecoveryAction.CONFIGURATION_REQUIRED, p.action)
        assertNull(p.alternativeModel)
    }

    @Test
    fun `a possible side effect outranks every retry consideration`() {
        val p = plan(503, gemini(503, "UNAVAILABLE", "unavailable"), sideEffect = true)
        assertEquals(RecoveryAction.OPERATION_OUTCOME_UNKNOWN, p.action)
        assertFalse(p.retryable)
    }

    @Test
    fun `a dropped stream with no verdict is safe to retry once`() {
        val p = RecoveryPlanner.plan(null, 1)
        assertEquals(RecoveryAction.RETRY_SAFE, p.action)
        assertTrue(p.retryable)
    }

    @Test
    fun `a malformed request is permanent`() {
        val p = plan(400, """{"error":{"code":"invalid_request","message":"missing required field 'messages'"}}""")
        assertEquals(RecoveryAction.PERMANENT_FAILURE, p.action)
        assertFalse(p.retryable)
    }
}
