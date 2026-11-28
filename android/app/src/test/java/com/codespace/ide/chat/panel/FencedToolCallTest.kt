package com.codespace.ide.chat.panel

import com.codespace.ide.chat.panel.ChatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OWNER REPORT (2026-11-28): asked for a web search, the copilot said it would do it and stopped.
 *
 * Every fenced block was masked before parsing, so a call the model wrapped in ```json was erased
 * and the turn ended as an ordinary answer. A fence that holds ONLY a call is payload; a fence
 * with prose in it is still documentation and must still be ignored.
 */
class FencedToolCallTest {

    @Test
    fun `a fence holding only a call is payload`() {
        assertTrue(AgentToolWire.isPureCallPayload("\n<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"kotlin 2.1\"}}</tool_call>\n"))
    }

    @Test
    fun `a fence with prose around the call is documentation`() {
        assertTrue(
            "an explained example must not run",
            !AgentToolWire.isPureCallPayload(
                "\nHere is how you would call it:\n<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"x\"}}</tool_call>\nHope that helps.\n",
            ),
        )
    }

    @Test
    fun `an empty fence is documentation`() {
        assertTrue(!AgentToolWire.isPureCallPayload("\n"))
    }

    @Test
    fun `a fenced call is executed instead of being shown as prose`() {
        val reply = "I'll search for that.\n```json\n<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"kotlin 2.1\"}}</tool_call>\n```"
        val decision = AgentToolWire.decide(ChatMode.AGENT, reply, round = 0, toolsOffered = true)
        assertTrue(
            "a fenced payload must reach the tool gate, was: " + decision::class.simpleName,
            decision is ToolDecision.Run,
        )
        assertEquals("web_search", (decision as ToolDecision.Run).calls.first().name)
    }

    @Test
    fun `a fenced example is still never executed`() {
        val reply = "For example:\n```\n<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"kotlin 2.1\"}}</tool_call>\nThis is just an example.\n```"
        val decision = AgentToolWire.decide(ChatMode.AGENT, reply, round = 0, toolsOffered = true)
        assertTrue(
            "documentation must not run, was: " + decision::class.simpleName,
            decision !is ToolDecision.Run,
        )
    }
}
