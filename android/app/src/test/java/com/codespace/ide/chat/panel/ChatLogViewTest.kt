package com.codespace.ide.chat.panel

import com.codespace.ide.engine.ChatLog
import com.codespace.ide.engine.ChatLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen must show the decision at a glance and never a secret. */
class ChatLogViewTest {

    private fun entry(
        outcome: String = "PromptTooLarge",
        status: Int? = 400,
        chars: Int = 24_000,
        dropped: Int = 9,
        retried: Boolean = false,
        message: String = "Request too large for model `m` on tokens per minute (TPM): Limit 6000, Requested 6980",
    ) = ChatLogEntry(
        at = System.currentTimeMillis(), mode = "agent", providerId = "openai", modelId = "org/qwen-test",
        endpointPath = "/openai/v1/chat/completions", requestChars = chars, httpStatus = status,
        outcome = outcome, providerMessage = message, toolsOffered = 74, historyDropped = dropped,
        learnedLimitChars = 20_400, retriedCeilingField = retried,
    )

    @Test
    fun aRowAnswersHowBigAndWhatCameBack() {
        val line = ChatLogView.rowLine(entry())
        println("[logview] " + line)
        assertTrue(line, line.contains("PromptTooLarge"))
        assertTrue(line, line.contains("HTTP 400"))
        assertTrue(line, line.contains("~6000t"))
        assertTrue(line, line.contains("9 dropped"))
        assertTrue("the model is trimmed to its id", line.contains("qwen-test"))
        assertFalse("no provider prefix in the row", line.contains("org/"))
    }

    @Test
    fun aRetriedSendSaysSo() {
        assertTrue(ChatLogView.rowLine(entry(retried = true)).contains("retried"))
        assertFalse(ChatLogView.rowLine(entry(retried = false)).contains("retried"))
    }

    @Test
    fun theDetailLineIsOneClippedSentence() {
        val long = ChatLogView.detailLine(entry(message = "x".repeat(400)))
        assertTrue(long.length <= 220)
        assertTrue(long.endsWith("\u2026"))
        // A completed send has no provider text, so the row falls back to the PATH, not to blank.
        assertEquals("/openai/v1/chat/completions", ChatLogView.detailLine(entry(message = "")))
    }

    @Test
    fun theEmptyStateExplainsItself() {
        assertTrue(ChatLogView.EMPTY.contains("No sends recorded"))
    }

    @Test
    fun copyTextIsTheSafeReport() {
        ChatLog.clear()
        ChatLog.record(entry(message = "bad key sk-live-abcdefghijklmnop"))
        val copy = ChatLogView.copyText()
        assertTrue(copy.contains("VN Code - chat diagnostic"))
        assertFalse("a paste must never carry the key", copy.contains("sk-live-abcdefghijklmnop"))
        ChatLog.clear()
    }
}
