package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.7 (catalog 13) — finishing must be a statement, not an absence.
 *
 * The loop stops when a reply carries no tool call, which makes a completed task and an abandoned
 * one look the same. These tests pin the two properties that matter: a bare declaration ends the
 * turn, and a declaration riding along with real work never cancels that work.
 */
class DoneSignalTest {

    private fun decide(reply: String, round: Int = 1) =
        AgentToolWire.decide(ChatMode.AGENT, reply, round)

    @Test
    fun `a bare completion declaration ends the turn`() {
        val d = decide("""All tests pass. <tool>{"name":"task_complete","arguments":{}}</tool>""")
        assertTrue("expected Answer, was $d", d is ToolDecision.Answer)
        val text = (d as ToolDecision.Answer).text
        assertTrue(text.contains("All tests pass"))
        assertFalse("protocol markup must not reach the user", text.contains("task_complete"))
        assertFalse(text.contains("<tool>"))
    }

    @Test
    fun `a declaration beside real work does not cancel the work`() {
        val d = decide(
            """<tool>{"name":"read_file","arguments":{"path":"a.kt"}}</tool>""" +
                """<tool>{"name":"task_complete","arguments":{}}</tool>"""
        )
        assertTrue("expected Run, was $d", d is ToolDecision.Run)
        val calls = (d as ToolDecision.Run).calls
        assertEquals("only the real call survives", 1, calls.size)
        assertEquals("read_file", calls.first().name)
    }

    @Test
    fun `ordinary tools are untouched`() {
        val d = decide("""<tool>{"name":"run_command","arguments":{"command":"ls"}}</tool>""")
        assertTrue(d is ToolDecision.Run)
        assertEquals("run_command", (d as ToolDecision.Run).calls.first().name)
    }

    @Test
    fun `a declaration at the round cap is not refused as a loop`() {
        // At MAX_ROUNDS the wire refuses further calls. A declaration is not a call, so finishing at
        // the cap must read as finished, not as "stopped to avoid a loop".
        val d = decide("""<tool>{"name":"task_complete","arguments":{}}</tool>""", round = AgentToolWire.MAX_ROUNDS)
        assertTrue("expected Answer at the cap, was $d", d is ToolDecision.Answer)
    }
}
