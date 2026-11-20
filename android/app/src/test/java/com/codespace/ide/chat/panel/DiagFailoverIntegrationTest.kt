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
 * P4.1: the opt-in key failover is the only automatic retry the app has, so its three events are
 * driven here through the real `ChatSession` send path with an injected slot pool and a fake
 * transport that always answers 401. No real key and no live request is used anywhere.
 *
 * The failure is an auth rejection because that is the only condition the failover policy acts on.
 */
class DiagFailoverIntegrationTest {

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    private class AlwaysRejected : ChatTransport {
        var calls = 0
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            throw TransportHttpException(401, emptyMap(), "invalid key")
        }
    }

    private fun session(
        t: ChatTransport,
        slots: List<String> = listOf("slot-a", "slot-b"),
        optIn: Boolean = true,
    ) = ChatSession(
        "proj", { "k" }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { t },
        poolSlotsFor = { slots },
        failoverOptIn = { optIn },
    )

    private fun settle(s: ChatSession, ms: Long = 3_000) = runBlocking {
        var spins = 0
        while (s.ui.value.streaming && spins < (ms / 10)) { delay(10); spins++ }
        Unit
    }

    private fun kinds() = CopilotDiag.recent().map { it.kind }

    @Test fun `failover schedules each remaining key and then reports exhaustion`() {
        val t = AlwaysRejected()
        val s = session(t)
        s.send("hi")
        settle(s)
        val scheduled = kinds().count { it == CopilotDiag.Kind.RETRY_SCHEDULED }
        val exhausted = kinds().count { it == CopilotDiag.Kind.RETRY_EXHAUSTED }
        assertTrue("at least one key was tried after the first rejection", scheduled >= 1)
        assertEquals("exhaustion is reported exactly once", 1, exhausted)
    }

    @Test fun `exhaustion comes last and after a scheduled retry`() {
        val t = AlwaysRejected()
        val s = session(t)
        s.send("hi")
        settle(s)
        val k = kinds()
        val lastScheduled = k.lastIndexOf(CopilotDiag.Kind.RETRY_SCHEDULED)
        val exhaustedAt = k.indexOf(CopilotDiag.Kind.RETRY_EXHAUSTED)
        assertTrue("a retry must be scheduled before it can be exhausted", lastScheduled in 0 until exhaustedAt)
    }

    @Test fun `no exhaustion without at least one failover attempt`() {
        val t = AlwaysRejected()
        val s = session(t, optIn = false)
        s.send("hi")
        settle(s)
        assertTrue("failover disabled: silent", kinds().none { it.name.startsWith("RETRY") })
    }

    @Test fun `a one-slot pool fails over exactly once and then exhausts`() {
        // The pool holds ALTERNATIVE slots, not the primary: one failover to "only-slot" is correct,
        // and a second attempt on the same rejected key would not be. Hence 2 wire calls, then out.
        val t = AlwaysRejected()
        val s = session(t, slots = listOf("only-slot"))
        s.send("hi")
        settle(s)
        assertEquals("primary + one failover, never the same key twice", 2, t.calls)
        assertEquals(1, kinds().count { it == CopilotDiag.Kind.RETRY_SCHEDULED })
        assertEquals(1, kinds().count { it == CopilotDiag.Kind.RETRY_EXHAUSTED })
    }

    @Test fun `each scheduled retry is numbered and carries no key material`() {
        val t = AlwaysRejected()
        val s = session(t)
        s.send("hi")
        settle(s)
        val scheduled = CopilotDiag.recent().filter { it.kind == CopilotDiag.Kind.RETRY_SCHEDULED }
        assertTrue(scheduled.isNotEmpty())
        scheduled.forEach {
            assertTrue("attempt number is recorded", it.detail.startsWith("attempt "))
            assertTrue("no slot name leaks", !it.detail.contains("slot-a") && !it.detail.contains("slot-b"))
        }
    }
}
