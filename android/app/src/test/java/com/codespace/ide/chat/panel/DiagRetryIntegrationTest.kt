package com.codespace.ide.chat.panel

import com.codespace.ide.agent.CopilotDiag
import com.codespace.ide.engine.ChatTransport
import com.codespace.ide.engine.TransportHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P4.3: the retry events come from the real ChatSession paths, not from calling the recorder.
 * The provider is a fake transport; everything above it is the production chat code.
 */
class DiagRetryIntegrationTest {

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    private class RateLimitedThenOk(private val failTimes: Int = 1) : ChatTransport {
        var calls = 0
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            if (calls <= failTimes) throw TransportHttpException(429, emptyMap(), "slow down")
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

    private fun kinds() = CopilotDiag.recent().map { it.kind }

    @Test fun `a first failure with no retry pressed emits no retry event`() {
        val s = session(RateLimitedThenOk())
        s.send("hi")
        settle(s)
        assertTrue(kinds().none { it.name.startsWith("RETRY") })
    }

    @Test fun `pressing retry emits RETRY_STARTED from the real retry path`() {
        val s = session(RateLimitedThenOk())
        s.send("hi")
        settle(s)
        s.retry()
        settle(s)
        assertEquals(1, kinds().count { it == CopilotDiag.Kind.RETRY_STARTED })
    }

    @Test fun `the retry event carries the provider and no prompt text`() {
        val s = session(RateLimitedThenOk())
        s.send("hi")
        settle(s)
        s.retry()
        settle(s)
        val e = CopilotDiag.recent().first { it.kind == CopilotDiag.Kind.RETRY_STARTED }
        assertEquals("user retry", e.detail)
        assertTrue(e.name.isNotBlank())
        assertTrue(!e.detail.contains("hi"))
    }

    @Test fun `no retry event claims to be exhausted when failover never ran`() {
        val s = session(RateLimitedThenOk())
        s.send("hi")
        settle(s)
        s.retry()
        settle(s)
        assertTrue(kinds().none { it == CopilotDiag.Kind.RETRY_EXHAUSTED })
        assertTrue(kinds().none { it == CopilotDiag.Kind.RETRY_SCHEDULED })
    }

    @Test fun `a normal turn with no failure emits no retry event`() {
        val s = session(RateLimitedThenOk(failTimes = 0))
        s.send("hi")
        settle(s)
        assertTrue(kinds().none { it.name.startsWith("RETRY") })
    }
}
