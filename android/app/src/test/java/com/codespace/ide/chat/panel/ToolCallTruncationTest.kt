package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 8c: a tool call that was cut mid-JSON is never executed.
 *
 * The bug this pins: a closed <tool_call> tag whose `arguments` object never closed fell back to
 * "{}", so the tool ran with every parameter missing - write_file with no path, run_command with
 * no command. The guard is that no truncation of a real call produces an executable call with no
 * arguments.
 */
class ToolCallTruncationTest {

    private val full = """<tool_call>{"name":"write_file","arguments":{"path":"a.txt","content":"hi"}}</tool_call>"""

    @Test
    fun theWholeCallRunsWithItsRealArguments() {
        val decision = AgentToolWire.decide(ChatMode.AGENT, full, round = 1)
        assertTrue(decision is ToolDecision.Run)
        val call = (decision as ToolDecision.Run).calls.single()
        assertEquals("write_file", call.name)
        assertTrue(call.argumentsJson.contains("a.txt"))
    }

    @Test
    fun noOffsetOfARealCallEverRunsItWithEmptyArguments() {
        for (k in 0..full.length) {
            val decision = AgentToolWire.decide(ChatMode.AGENT, full.take(k), round = 1)
            if (decision is ToolDecision.Run) {
                assertFalse(
                    "offset $k ran a call with no arguments",
                    decision.calls.any { it.argumentsJson == "{}" },
                )
            }
        }
    }

    @Test
    fun aClosedTagWithUnclosedArgumentsIsRefusedNotRun() {
        val cut = """<tool_call>{"name":"write_file","arguments":{"path":"a.txt","content":"hi""}</tool_call>"""
        val decision = AgentToolWire.decide(ChatMode.AGENT, cut, round = 1)
        assertTrue(decision is ToolDecision.Refused)
        assertTrue((decision as ToolDecision.Refused).reason.contains("never"))
        assertTrue(decision.reason.contains("write_file"))
    }

    @Test
    fun aToolThatTakesNoArgumentsStillRuns() {
        val noArgs = """<tool_call>{"name":"list_files"}</tool_call>"""
        val decision = AgentToolWire.decide(ChatMode.AGENT, noArgs, round = 1)
        assertTrue(decision is ToolDecision.Run)
    }

    @Test
    fun anEncodedStringArgumentIsNotMistakenForAnUnclosedObject() {
        val tricky = """<tool_call>{"name":"write_file","arguments":{"path":"a.txt","content":"a } b {"}}</tool_call>"""
        val decision = AgentToolWire.decide(ChatMode.AGENT, tricky, round = 1)
        assertTrue(decision is ToolDecision.Run)
        assertTrue((decision as ToolDecision.Run).calls.single().argumentsJson.contains("a } b {"))
    }
}
