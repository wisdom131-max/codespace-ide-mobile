package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import com.codespace.ide.engine.TransportHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 429 stops and asks. There is no automatic ladder and no invented wait cap - the owner's
 * ruling for 2026-11-01, matching VS Code, where the error part resends only when its button
 * is pressed. These tests pin that: exactly one wire attempt, then a card, then the user.
 */
class ChatSessionRetryTest {

    private class RateLimitedThenOk(private val retryAfter: String?, private val failTimes: Int = 1) : ChatTransport {
        var calls = 0
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            if (calls <= failTimes) {
                throw TransportHttpException(
                    status = 429,
                    headers = if (retryAfter != null) mapOf("Retry-After" to retryAfter) else emptyMap(),
                    body = "slow down",
                )
            }
            emit("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"recovered\"}]}]}}\n\n")
            emit("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n")
        }
    }

    private fun session(t: ChatTransport) = ChatSession(
        "proj", { "k" }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { t },
    )

    private fun settle(s: ChatSession, ms: Long = 2_000) = runBlocking {
        var spins = 0
        while (s.ui.value.streaming && spins < (ms / 10)) { delay(10); spins++ }
        Unit
    }

    @Test
    fun aRateLimitedSendStopsAndShowsTheCardWithoutRetryingItself() {
        val t = RateLimitedThenOk(retryAfter = "1")
        val s = session(t)
        s.send("hi")
        settle(s)
        assertEquals("no automatic retry: the provider is asked exactly once", 1, t.calls)
        assertFalse("the request is over, not waiting in the background", s.ui.value.streaming)
        val failure = s.ui.value.rows.filterIsInstance<ChatRowModel.Failed>().single()
        assertTrue("a rate limit is retryable by hand", failure.canRetry)
    }

    @Test
    fun aPressedRetryIsTheOnlyWayTheProviderIsAskedAgain() {
        val t = RateLimitedThenOk(retryAfter = "1")
        val s = session(t)
        s.send("hi")
        settle(s)
        assertEquals(1, t.calls)
        s.retry()
        settle(s)
        assertEquals("the retry comes from the button, not a timer", 2, t.calls)
        val answers = s.ui.value.rows.filterIsInstance<ChatRowModel.AssistantStream>()
        assertEquals("the recovered answer is stated once", 1, answers.size)
        assertTrue(answers.all { it.text.contains("recovered") })
    }
}
