package com.codespace.ide.chat.provider

import com.codespace.ide.agent.SideEffectVerifier
import com.codespace.ide.chat.panel.ChatFailureAdvice
import com.codespace.ide.chat.panel.ParsedToolCall
import com.codespace.ide.engine.ChatError
import com.codespace.ide.engine.ErrorClass
import com.codespace.ide.engine.ProviderErrors
import com.codespace.ide.engine.RecoveryAction
import com.codespace.ide.engine.RecoveryPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE 5.3 — a lost acknowledgement must never become a second side effect. */
class Stage53SideEffectRecoveryTest {

    private val known = setOf("write_file", "read_file")

    private fun view(outcome: ToolOutcome?, started: Boolean = true, cancelled: Boolean = false,
                     readOnly: Boolean = false) =
        SideEffectRecovery.OutcomeView(started, outcome, cancelled, readOnly)

    @Test
    fun `a throwing write is uncertain, not failed`() = runBlocking {
        val call = WireCalls.fromWire("copilot", listOf(ParsedToolCall("write_file", """{"path":"a","text":"b"}"""))).single()
        val outcome = ToolExecutionGateway.run(call, known, HashSet()) { _, _, _ -> throw IllegalStateException("socket closed") }
        assertEquals(ToolOutcome.UNCERTAIN, outcome.result!!.outcome)
        assertTrue(outcome.result!!.error!!.startsWith(ToolResultNormaliser.UNCERTAIN_PREFIX))
    }

    @Test
    fun `an uncertain write may not be repeated`() {
        val state = SideEffectRecovery.state(view(ToolOutcome.UNCERTAIN))
        assertEquals(SideEffectRecovery.State.UNCERTAIN, state)
        assertFalse(SideEffectRecovery.mayRetry(state, readOnly = false))
    }

    @Test
    fun `an uncertain read may be repeated`() {
        val state = SideEffectRecovery.state(view(ToolOutcome.UNCERTAIN, readOnly = true))
        assertTrue(SideEffectRecovery.mayRetry(state, readOnly = true))
    }

    @Test
    fun `a completed write is not repeated`() {
        val state = SideEffectRecovery.state(
            view(ToolOutcome.SUCCESS),
            SideEffectVerifier.Verification(SideEffectVerifier.Result.VERIFIED, "on disk"),
        )
        assertEquals(SideEffectRecovery.State.COMPLETED, state)
        assertFalse(SideEffectRecovery.mayRetry(state, readOnly = false))
    }

    @Test
    fun `a write the target does not hold may be retried`() {
        val state = SideEffectRecovery.state(
            view(ToolOutcome.FAILED),
            SideEffectVerifier.Verification(SideEffectVerifier.Result.REFUTED, "file absent"),
        )
        assertEquals(SideEffectRecovery.State.VERIFIED_FAILED, state)
        assertTrue(SideEffectRecovery.mayRetry(state, readOnly = false))
    }

    @Test
    fun `a refused call never started`() {
        val state = SideEffectRecovery.state(view(null, started = false))
        assertEquals(SideEffectRecovery.State.NEVER_STARTED, state)
        assertTrue(SideEffectRecovery.mayRetry(state, readOnly = false))
    }

    @Test
    fun `the uncertain note says what is happening`() {
        assertTrue(SideEffectRecovery.uncertainNote("write_file").contains("confirmed"))
    }

    // ---- user-facing advice -------------------------------------------------------------------

    @Test
    fun `a rejected key advises the user, not a retry`() {
        val note = ChatFailureAdvice.adviceFor(ChatError.Auth("OpenAI", "primary", "rejected"))
        assertTrue(note!!.contains("key"))
    }

    @Test
    fun `a temporary limit keeps the plain row`() {
        assertNull(ChatFailureAdvice.adviceFor(ChatError.RateLimited(5)))
    }

    @Test
    fun `a long retry-after is explained instead of held open`() {
        val note = ChatFailureAdvice.adviceFor(ChatError.RateLimited(900))
        assertTrue(note!!.contains("wait"))
    }

    @Test
    fun `out of credits asks the user to act`() {
        val note = ChatFailureAdvice.adviceFor(ChatError.OutOfCredits("Add credit"))
        assertTrue(note!!.contains("quota"))
    }

    @Test
    fun `an external write suppresses every retry suggestion`() {
        val note = ChatFailureAdvice.adviceFor(ChatError.RateLimited(5), externalWriteDone = true)
        assertTrue(note!!.contains("changes files"))
    }

    @Test
    fun `a custom endpoint error is never invented into a classification`() {
        val facts = ProviderErrors.extract("custom", 418, """{"odd":1}""")
        assertEquals(ErrorClass.UNKNOWN, ProviderErrors.classify(facts))
        assertEquals(RecoveryAction.PERMANENT_FAILURE, RecoveryPlanner.plan(facts, 1).action)
    }
}
