package com.codespace.ide.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate these tools depend on. If it can hand the model an invented answer, the tools are
 * lying no matter how the card looks.
 */
class AgentQuestionGateTest {

    @Test
    fun anAnswerIsWhatTheToolReturns() = runBlocking {
        var answer: AgentQuestionGate.Answer? = null
        val job = launch { answer = AgentQuestionGate.ask("Which branch?", listOf("main", "dev")) }
        while (AgentQuestionGate.pending.value == null) delay(5)
        assertTrue(AgentQuestionGate.answer("dev", null))
        job.join()
        assertEquals(AgentQuestionGate.Outcome.ANSWERED, answer!!.outcome)
        assertEquals("dev", answer!!.choice)
        assertTrue(answer!!.answered)
    }

    @Test
    fun freeTextIsTrimmedAndKept() = runBlocking {
        var answer: AgentQuestionGate.Answer? = null
        val job = launch { answer = AgentQuestionGate.ask("Name?") }
        while (AgentQuestionGate.pending.value == null) delay(5)
        AgentQuestionGate.answer(null, "  wisdom  ")
        job.join()
        assertEquals("wisdom", answer!!.text)
    }

    @Test
    fun dismissingIsNotAnAnswer() = runBlocking {
        var answer: AgentQuestionGate.Answer? = null
        val job = launch { answer = AgentQuestionGate.ask("Continue?") }
        while (AgentQuestionGate.pending.value == null) delay(5)
        AgentQuestionGate.dismiss()
        job.join()
        assertEquals(AgentQuestionGate.Outcome.DISMISSED, answer!!.outcome)
        assertFalse("a dismissal must never read as answered", answer!!.answered)
        assertNull(answer!!.choice)
    }

    @Test
    fun aTimeoutLeavesNoPendingCardAndNoAnswer() = runBlocking {
        val answer = AgentQuestionGate.ask("Anyone there?", timeoutMs = 60)
        assertEquals(AgentQuestionGate.Outcome.TIMED_OUT, answer.outcome)
        assertFalse(answer.answered)
        assertNull("the card must clear when the call is abandoned", AgentQuestionGate.pending.value)
    }

    @Test
    fun answeringTwiceResolvesOnce() = runBlocking {
        var answer: AgentQuestionGate.Answer? = null
        val job = launch { answer = AgentQuestionGate.ask("Pick") }
        while (AgentQuestionGate.pending.value == null) delay(5)
        assertTrue(AgentQuestionGate.answer("a", null))
        job.join()
        assertFalse("a late second answer must be a no-op", AgentQuestionGate.answer("b", null))
        assertEquals("a", answer!!.choice)
    }

    @Test
    fun terminalSelectionReadsWhatTheViewHas() {
        TerminalSelectionState.register { "ls -la\nfoo.kt" }
        assertEquals("ls -la\nfoo.kt", TerminalSelectionState.current())
        TerminalSelectionState.register { null }
        assertNull(TerminalSelectionState.current())
        TerminalSelectionState.register { error("view detached") }
        assertNull("a detached view must not crash the tool", TerminalSelectionState.current())
        TerminalSelectionState.register(null)
        assertNull(TerminalSelectionState.current())
    }
}
