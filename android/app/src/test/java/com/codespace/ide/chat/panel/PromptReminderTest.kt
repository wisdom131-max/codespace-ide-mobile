package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-B (2026-11-19): the tail reminder appears only on long prompts and stays inside the cap. */
class PromptReminderTest {

    private fun occurrences(hay: String, needle: String): Int {
        var i = 0; var n = 0
        while (true) {
            val at = hay.indexOf(needle, i)
            if (at < 0) return n
            n++; i = at + needle.length
        }
    }

    @Test
    fun `a short prompt carries the constraint once, at the head`() {
        val p = ChatInstructions.build(ChatMode.ASK, false, emptyList(), "", "")
        assertEquals(1, occurrences(p, ChatModePrompts.constraints(ChatMode.ASK)))
        assertTrue(p.length < ChatInstructions.reminderThresholdFor(ChatMode.ASK))
    }

    @Test
    fun `a long prompt carries the same constraint at the tail as well`() {
        val ws = "workspace: /repo\n" + "x".repeat(ChatInstructions.reminderThresholdFor(ChatMode.AGENT) + 10)
        val p = ChatInstructions.build(ChatMode.AGENT, true, listOf("read_file"), ws, "")
        assertEquals(2, occurrences(p, ChatModePrompts.constraints(ChatMode.AGENT)))
        assertTrue(p.trimEnd().endsWith(ChatModePrompts.constraints(ChatMode.AGENT)))
    }

    @Test
    fun `the reminder never duplicates tool documentation`() {
        val ws = "x".repeat(ChatInstructions.reminderThresholdFor(ChatMode.AGENT) + 10)
        val p = ChatInstructions.build(ChatMode.AGENT, true, listOf("read_file"), ws, "MCPDOC")
        assertEquals(1, occurrences(p, "MCPDOC"))
        assertEquals(1, occurrences(p, "EXTERNAL MCP TOOLS"))
    }

    @Test
    fun `the assembled prompt stays inside its mode cap`() {
        val memory = "- fact: " + "y".repeat(9_000)
        val ws = "x".repeat(ChatInstructions.reminderThresholdFor(ChatMode.AGENT) + 10)
        val p = ChatInstructions.build(ChatMode.AGENT, true, listOf("read_file"), ws, "", memory = memory)
        assertTrue("prompt " + p.length + " over cap " + ChatInstructions.AGENT_CAP,
            p.length <= ChatInstructions.AGENT_CAP)
        assertTrue(p.trimEnd().endsWith(ChatModePrompts.constraints(ChatMode.AGENT)))
    }

    @Test
    fun `ask mode uses its own lower threshold`() {
        assertTrue(ChatInstructions.reminderThresholdFor(ChatMode.ASK) <
            ChatInstructions.reminderThresholdFor(ChatMode.EDIT))
    }
}
