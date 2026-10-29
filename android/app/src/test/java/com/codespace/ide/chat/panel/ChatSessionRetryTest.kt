package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import com.codespace.ide.engine.TransportHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 6 — retries through the real session.
 *
 * A scripted transport that fails with 429 first and answers second proves the retry
 * actually happens; the cancellation and duplicate tests prove it STOPS.
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
    fun aRateLimitedSendRetriesItselfAndRecovers() {
        val t = RateLimitedThenOk(retryAfter = "1")
        val s = session(t)
        s.send("hi")
        settle(s)
        assertEquals("one retry, one wire attempt each", 2, t.calls)
        val answers = s.ui.value.rows.filterIsInstance<ChatRowModel.AssistantStream>()
        assertEquals("recovered", answers.last().text)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun theWaitIsAnnouncedInsteadOfLookingHangs() {
        val t = RateLimitedThenOk(retryAfter = "1")
        val s = session(t)
        s.send("hi")
        // The retry card is in the transcript while the wait runs.
        var sawCard = false
        runBlocking {
            var spins = 0
            while (s.ui.value.streaming && spins < 200) {
                if (s.ui.value.rows.any { it is ChatRowModel.RateLimited }) sawCard = true
                delay(5); spins++
            }
        }
        assertTrue("the user must see why nothing is arriving", sawCard)
    }

    @Test
    fun aRecoveredRetryDoesNotLeaveTheWaitCardAboveTheAnswer() {
        val t = RateLimitedThenOk(retryAfter = "1")
        val s = session(t)
        s.send("hi")
        settle(s)
        assertTrue(
            "a card claiming we are rate limited cannot sit above a normal answer",
            s.ui.value.rows.none { it is ChatRowModel.RateLimited },
        )
    }

    @Test
    fun stopCancelsAnArmedRetryAndTheWireStaysQuiet() {
        val t = RateLimitedThenOk(retryAfter = "1", failTimes = 5)
        val s = session(t)
        s.send("hi")
        runBlocking {
            var spins = 0
            while (s.ui.value.rows.none { it is ChatRowModel.RateLimited } && spins < 200) { delay(5); spins++ }
        }
        s.cancel()
        assertEquals("the retry timer must not fire after Stop", 1, t.calls)
        assertFalse("Stop must leave the composer idle, not stuck loading", s.ui.value.streaming)
        // And it stays quiet: give the cancelled timer its full window to (not) fire.
        runBlocking { delay(1_400) }
        assertEquals("a cancelled wait cannot come back later", 1, t.calls)
    }

    @Test
    fun anArmedRetryFiresExactlyOnce() {
        // The provider recovers on the second call, so the budget is never exhausted: a
        // stray or duplicated timer would show up as a THIRD wire attempt.
        val t = RateLimitedThenOk(retryAfter = "1", failTimes = 1)
        val s = session(t)
        s.send("first")
        settle(s)
        runBlocking { delay(1_500) }
        assertEquals("exactly one armed retry reached the wire", 2, t.calls)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun retriesAreBoundedAcrossAttemptsNotUnlimited() {
        // A provider that never recovers. 1s waits keep the test quick; the point is that
        // the third failure is the LAST one, whatever the wait would have been.
        val t = RateLimitedThenOk(retryAfter = "1", failTimes = 99)
        val s = session(t)
        s.send("hi")
        runBlocking { delay(4_000) }
        assertFalse(s.ui.value.streaming)
        assertEquals("1 initial + 2 automatic", 3, t.calls)
        assertTrue(
            "the manual card is the end state",
            s.ui.value.rows.any { it is ChatRowModel.RateLimited && it.attempt == 0 },
        )
    }
}
