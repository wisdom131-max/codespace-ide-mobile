package com.codespace.ide.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini must not receive a thinkingConfig at all when thinking is off. A budget of 0 is only
 * legal on the models that permit disabling thinking; several models cannot disable it and 400 on
 * a 0. Absent is the only safe "off" that is valid for every model.
 * Source read 2026-11-09: https://ai.google.dev/gemini-api/docs/thinking
 */
class GeminiThinkingConfigTest {

    private fun cfg(budget: Int) = EngineConfig(
        modelId = "gemini-test",
        providerId = "gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        apiKey = "fake-key-for-tests-only",
        reasoningBudget = budget,
    )

    @Test
    fun thinkingOffMeansTheFieldIsAbsentNotZero() {
        val body = GeminiAdapter().payload(emptyList(), "hi", cfg(0))
        assertFalse("no thinkingConfig when thinking is off: $body", body.contains("thinkingConfig"))
        assertFalse("and no zero budget either: $body", body.contains("thinkingBudget"))
        assertTrue("the answer ceiling still travels: $body", body.contains("maxOutputTokens"))
    }

    @Test
    fun thinkingOnCarriesABudget() {
        val body = GeminiAdapter().payload(emptyList(), "hi", cfg(1024))
        assertTrue(body, body.contains("\"thinkingConfig\":{\"thinkingBudget\":1024}"))
    }

    @Test
    fun anUnknownBudgetFallsBackToTheBoundedDefaultNeverMinusOne() {
        val body = GeminiAdapter().payload(emptyList(), "hi", cfg(-1))
        assertTrue(body, body.contains("\"thinkingBudget\":1024"))
        assertFalse("the -1 dynamic value is never sent", body.contains("-1"))
    }
}
