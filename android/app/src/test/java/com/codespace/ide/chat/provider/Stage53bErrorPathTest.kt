package com.codespace.ide.chat.provider

import com.codespace.ide.chat.panel.ChatFailureAdvice
import com.codespace.ide.engine.ChatError
import com.codespace.ide.engine.ErrorTaxonomy
import com.codespace.ide.engine.RecoveryAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 5.3b — engine evidence to app decision, in one path.
 *
 * These tests start where production starts: `ErrorTaxonomy.classify` is exactly what
 * `ChatEngineImpl` calls when the transport throws, and `ChatFailureAdvice` is exactly what
 * `ChatSession` calls to build the row the user reads.
 */
class Stage53bErrorPathTest {

    private fun err(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        ErrorTaxonomy.classify(status, headers, body, "/v1/messages",
            providerLabel = "anthropic", providerId = "anthropic", modelId = "claude-opus-5-5")

    @Test
    fun `a context overrun in a 400 asks for reduction`() {
        val e = err(400, """{"error":{"message":"prompt is too long: 250000 tokens"}}""")
        val note = ChatFailureAdvice.adviceFor(e)!!
        assertTrue(note.contains("too large"))
    }

    @Test
    fun `a malformed request in the same 400 is called permanent`() {
        val e = err(400, """{"error":{"message":"missing required field 'messages'"}}""")
        val plan = ChatFailureAdvice.planFor(e)!!
        assertEquals(RecoveryAction.PERMANENT_FAILURE, plan.action)
    }

    @Test
    fun `a temporary limit keeps the row plain`() {
        assertNull(ChatFailureAdvice.adviceFor(err(429, """{"error":{"type":"rate_limit_error","message":"slow"}}""")))
    }

    @Test
    fun `a long retry-after is explained from the header`() {
        val note = ChatFailureAdvice.adviceFor(
            err(429, """{"error":{"type":"rate_limit_error","message":"slow"}}""", mapOf("retry-after" to "900")))
        assertTrue(note!!.contains("wait"))
    }

    @Test
    fun `quota exhaustion asks the user to act`() {
        val note = ChatFailureAdvice.adviceFor(
            err(429, """{"error":{"type":"insufficient_quota","message":"quota exceeded"}}"""))
        assertTrue(note!!.contains("quota"))
    }

    @Test
    fun `a rejected key names the key, not a stack trace`() {
        val note = ChatFailureAdvice.adviceFor(
            err(401, """{"error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        assertTrue(note!!.contains("API key"))
    }

    @Test
    fun `an unknown custom endpoint error is never invented into a retry`() {
        val e = ErrorTaxonomy.classify(400, emptyMap(), """{"mystery":true}""", "/v1/chat",
            providerId = "custom", modelId = "local")
        val plan = ChatFailureAdvice.planFor(e)!!
        assertEquals(RecoveryAction.PERMANENT_FAILURE, plan.action)
        assertTrue(plan.why.contains("cannot classify"))
    }

    @Test
    fun `an already executed write outranks the provider error`() {
        val plan = ChatFailureAdvice.planFor(
            err(503, """{"error":{"type":"overloaded_error","message":"Overloaded"}}"""),
            externalWriteDone = true)!!
        assertEquals(RecoveryAction.OPERATION_OUTCOME_UNKNOWN, plan.action)
    }

    @Test
    fun `a verdict with no evidence still answers from what it proves`() {
        val note = ChatFailureAdvice.adviceFor(ChatError.Auth("OpenAI", "primary", "rejected"))
        assertTrue(note!!.contains("key"))
    }
}
