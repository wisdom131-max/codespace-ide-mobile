package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Request too large" is six providers saying one thing. The numbers must come out of the text and
 * the verdict must NOT read like a key problem - this is the case the owner hit with an
 * org-scoped model where even "hi" was refused.
 */
class PromptTooLargeTest {

    private fun classify(status: Int, body: String) =
        ErrorTaxonomy.classify(status, emptyMap(), body, "/v1/chat/completions")

    @Test
    fun groqTpmCarriesBothNumbersAndNamesTheBudget() {
        val body = """{"error":{"message":"Request too large for model `qwen/qwen3.8-27b` in organization `org_01` on tokens per minute (TPM): Limit 6000, Requested 6980, please reduce your message size and try again.","type":"tokens","code":"rate_limit_exceeded"}}"""
        val e = classify(400, body) as ChatError.PromptTooLarge
        assertEquals(6000, e.limit)
        assertEquals(6980, e.requested)
        assertTrue(e.scope, e.scope.contains("per-minute"))
        println("[limits] groq: " + e.userVerdict)
        assertTrue(e.userVerdict.contains("6000"))
        assertTrue(e.userVerdict.contains("6980"))
        assertFalse("never a key sentence", e.userVerdict.lowercase().contains("key"))
    }

    @Test
    fun openAiContextOverflowIsTheContextWindow() {
        val body = """{"error":{"message":"This model's maximum context length is 128000 tokens. However, your messages resulted in 130500 tokens.","type":"invalid_request_error","code":"context_length_exceeded"}}"""
        val e = classify(400, body) as ChatError.PromptTooLarge
        assertEquals(128000, e.limit)
        assertEquals(130500, e.requested)
        assertTrue(e.scope, e.scope.contains("context window"))
    }

    @Test
    fun anthropicSaysPromptIsTooLong() {
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 210000 tokens > 200000 maximum"}}"""
        val e = classify(400, body) as ChatError.PromptTooLarge
        assertEquals(200000, e.limit)
        assertEquals("Anthropic's own words stay as the detail", 210000, e.requested)
    }

    @Test
    fun geminiStatesTheAllowedMaximum() {
        val body = """{"error":{"code":400,"message":"The input token count (1048577) exceeds the maximum number of tokens allowed (1048576)."}}"""
        val e = classify(400, body) as ChatError.PromptTooLarge
        assertEquals(1048576, e.limit)
        assertEquals(1048577, e.requested)
    }

    @Test
    fun a413WithoutNumbersStillSaysWhatIsWrong() {
        val e = classify(413, "") as ChatError.PromptTooLarge
        assertEquals(null, e.limit)
        assertTrue(e.userVerdict.contains("bigger"))
        assertTrue("still actionable", e.userVerdict.contains("new session"))
    }

    @Test
    fun aShapeErrorIsStillAShapeError() {
        // The trap: "too large" alone must not swallow a complaint about one field.
        val e = classify(400, """{"error":{"message":"Unsupported parameter: max_tokens"}}""")
        assertTrue(e is ChatError.BadRequest)
    }

    @Test
    fun aTemperatureComplaintIsNotALimitComplaint() {
        val e = classify(400, """{"error":{"message":"temperature is not supported for this model"}}""")
        assertTrue(e is ChatError.BadRequest)
    }
}
