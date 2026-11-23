package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log is only useful if it can be pasted in public. Redaction is the feature, not a detail.
 */
class ChatLogTest {

    @Test
    fun bearerTokensNeverSurvive() {
        val raw = "Authorization: Bearer sk-proj-abcdefghijklmnop and Bearer abcdef123456"
        val safe = ChatLogSanitizer.redact(raw)
        println("[log] " + safe)
        assertFalse(safe.contains("sk-proj-abcdefghijklmnop"))
        assertFalse(safe.contains("abcdef123456"))
        assertTrue(safe.contains("<redacted"))
    }

    @Test
    fun providerKeyShapesAreRedacted() {
        listOf(
            "key sk-or-v1-0123456789abcdef",
            "key ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
            "key AIzaSyD-1234567890abcdefghijklmnopqrstu",
            "key gsk_0123456789abcdefghij",
        ).forEach { raw ->
            val safe = ChatLogSanitizer.redact(raw)
            assertFalse("leaked: $safe", ChatLogSanitizer.looksUnsafe(safe))
        }
    }

    @Test
    fun headerLinesAndQueryParametersAreRedacted() {
        val raw = """x-api-key: sk-live-99887766554433
            |x-goog-api-key: AIzaSyD-abcdefghijklmnopqrstuvwxyz12
            |url https://api.test/v1/chat?key=secretvalue&model=x""".trimMargin()
        val safe = ChatLogSanitizer.redact(raw)
        println("[log] " + safe.replace("\n", " | "))
        assertFalse(safe.contains("sk-live-99887766554433"))
        assertFalse(safe.contains("secretvalue"))
        assertTrue("the model id is not a secret and must survive", safe.contains("model=x"))
    }

    @Test
    fun theReportCarriesTheFactsAndNoSecrets() {
        ChatLog.clear()
        ChatLog.record(
            ChatLogEntry(
                at = System.currentTimeMillis(), mode = "agent", providerId = "openai",
                modelId = "qwen-test", endpointPath = "/openai/v1/chat/completions",
                requestChars = 24_000, httpStatus = 400, outcome = "PromptTooLarge",
                providerMessage = "Request too large for model `m` on tokens per minute (TPM): Limit 6000, Requested 6980. Key: sk-live-abcdefghijkl",
                toolsOffered = 74, historyDropped = 9, learnedLimitChars = 20_400,
                retriedCeilingField = true,
            ),
        )
        val r = ChatLog.report()
        println("[log]\n" + r)
        assertTrue(r.contains("Limit 6000"))
        assertTrue(r.contains("~6000 tokens"))
        assertTrue(r.contains("tools offered: 74"))
        assertTrue(r.contains("9 older messages not sent"))
        assertTrue(r.contains("resent once with the other answer-ceiling field"))
        assertFalse("no key may survive into a pasteable report", r.contains("sk-live-abcdefghijkl"))
    }

    @Test
    fun theLogIsBounded() {
        ChatLog.clear()
        repeat(ChatLog.MAX_ENTRIES + 25) { i ->
            ChatLog.record(
                ChatLogEntry(i.toLong(), "ask", "p", "m", "/p", 10, 200, "Completed", "", 0, 0, 0, false),
            )
        }
        assertEquals(ChatLog.MAX_ENTRIES, ChatLog.size())
        assertEquals("newest first", (ChatLog.MAX_ENTRIES + 24).toLong(), ChatLog.recent(1).first().at)
        ChatLog.clear()
    }



    @Test
    fun aZeroToolCountExplainsItself() {
        // OWNER REPORT (2026-11-23): a bare "tools offered: 0" left the owner inferring the cause
        // from a contradiction in his own log. Uses this file's own idiom (record + report) rather
        // than a constructor call, which is what three earlier attempts got wrong.
        ChatLog.clear()
        ChatLog.record(
            ChatLogEntry(
                at = System.currentTimeMillis(), mode = "BALANCED",
                providerId = "custom_cloud_flare", modelId = "m",
                endpointPath = "/ai/v1/chat/completions", requestChars = 11_041,
                httpStatus = 200, outcome = "Completed", providerMessage = "", toolsOffered = 0,
                historyDropped = 0, learnedLimitChars = 0, retriedCeilingField = false,
            ),
        )
        val r = ChatLog.report()
        assertTrue(r.contains("tools: none advertised this turn"))
    }

    @Test
    fun announcedToolCallsWithNoToolsAreCalledOut() {
        // The exact contradiction from the owner's log: end reason tool_calls with zero tools sent.
        ChatLog.clear()
        ChatLog.record(
            ChatLogEntry(
                at = System.currentTimeMillis(), mode = "BALANCED", providerId = "p", modelId = "m",
                endpointPath = "/v1/chat/completions", requestChars = 900,
                httpStatus = 200, outcome = "Completed", providerMessage = "", toolsOffered = 0,
                historyDropped = 0, learnedLimitChars = 0, retriedCeilingField = false,
                endReason = "tool_calls",
            ),
        )
        val r = ChatLog.report()
        assertTrue(r.contains("NO tools advertised"))
    }
}
