package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 5.3b — the verdict is no longer alone: it carries the evidence it was built from.
 *
 * Every case here goes through [ErrorTaxonomy.classify], which is what `ChatEngineImpl` calls when the
 * transport throws, so this is the production engine boundary rather than a helper.
 */
class Stage53bTypedFactsTest {

    private fun classify(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        ErrorTaxonomy.classify(
            status, headers, body, "/v1/messages",
            providerLabel = "anthropic", keySlotName = "primary",
            providerId = "anthropic", modelId = "claude-opus-5-5",
        )

    @Test
    fun `a rate limit carries its retry-after and its provider code`() {
        val err = classify(429, """{"error":{"type":"rate_limit_error","message":"Rate limit reached"}}""",
            mapOf("retry-after" to "9"))
        assertTrue(err is ChatError.RateLimited)
        val f = err.facts!!
        assertEquals(429, f.status)
        assertEquals("rate_limit_error", f.type)
        assertEquals(9, f.retryAfterSec)
        assertEquals(ErrorClass.RATE_LIMITED, ProviderErrors.classify(f))
        assertEquals("anthropic", f.providerId)
        assertEquals("claude-opus-5-5", f.modelId)
    }

    @Test
    fun `a rejected key keeps the provider's own code`() {
        val err = classify(401,
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals("authentication_error", err.facts!!.type)
        assertEquals(ErrorClass.AUTH_INVALID, ProviderErrors.classify(err.facts!!))
    }

    @Test
    fun `a 400 that is really a context overrun is still recognisable from the verdict`() {
        val err = classify(400,
            """{"error":{"message":"prompt is too long: 250000 tokens > 200000 maximum"}}""")
        val f = err.facts!!
        assertEquals(400, f.status)
        assertEquals(ErrorClass.CONTEXT_EXCEEDED, ProviderErrors.classify(f))
    }

    @Test
    fun `an html error page does not crash the classifier and keeps the status`() {
        val err = classify(502, "<html><body>Bad gateway</body></html>")
        assertEquals(502, err.facts!!.status)
        assertEquals("", err.facts!!.message)
        assertEquals(ErrorClass.PROVIDER_UNAVAILABLE, ProviderErrors.classify(err.facts!!))
    }

    @Test
    fun `a custom endpoint body that matches nothing stays unknown`() {
        val err = ErrorTaxonomy.classify(400, emptyMap(), """{"mystery":true}""", "/v1/chat",
            providerId = "custom", modelId = "local-model")
        assertEquals(ErrorClass.UNKNOWN, ProviderErrors.classify(err.facts!!))
    }

    @Test
    fun `a credential in the body never reaches the facts`() {
        val err = classify(401,
            """{"error":{"message":"Incorrect API key provided: sk-live0123456789abcdef"}}""")
        assertTrue(!err.facts!!.safeMessage.contains("sk-live0123456789abcdef"))
    }

    @Test
    fun `a transport failure carries its own facts`() {
        val err = ErrorTaxonomy.network("read timed out")
        assertNotNull(err.facts)
        assertEquals(ErrorClass.TIMEOUT, ProviderErrors.classify(err.facts!!))
    }

    @Test
    fun `a verdict built without a provider body invents nothing`() {
        val err = ChatError.Server(500)
        assertNull("no body was read, so there is no evidence to carry", err.facts)
    }

    @Test
    fun `the planner answers from the facts, not from the status alone`() {
        val context = classify(400, """{"error":{"message":"input is too long for this model"}}""")
        assertEquals(RecoveryAction.CONTEXT_REDUCTION_REQUIRED,
            RecoveryPlanner.plan(context.facts, 1).action)
        val malformed = classify(400, """{"error":{"message":"missing required field messages"}}""")
        assertEquals(RecoveryAction.PERMANENT_FAILURE,
            RecoveryPlanner.plan(malformed.facts, 1).action)
    }

    @Test
    fun `a quota refusal is not turned into a wait-and-retry`() {
        val err = classify(429, """{"error":{"type":"insufficient_quota","message":"You exceeded your quota"}}""")
        assertEquals(RecoveryAction.USER_ACTION_REQUIRED, RecoveryPlanner.plan(err.facts, 1).action)
    }
}
