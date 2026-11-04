package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Advisor ruling 2026-11-04: the capability is yes / no / unknown, and UNKNOWN must behave
 * exactly as the shipped app does. The risk this test exists to prevent is a silent regression
 * where a model the catalog has never heard of loses its tools.
 */
class ToolOfferingTest {

    @Test
    fun `unknown capability keeps offering tools`() {
        val plan = ToolOffering.plan(ChatMode.AGENT, null)
        assertTrue("an unlisted model keeps the tool wire", plan.sendTools)
        assertNull("and says nothing about it", plan.note)
    }

    @Test
    fun `a model that does native tool calling is offered tools`() {
        val plan = ToolOffering.plan(ChatMode.AGENT, true)
        assertTrue(plan.sendTools)
        assertNull(plan.note)
    }

    @Test
    fun `a model marked as not supporting tools is not offered them and says so`() {
        val plan = ToolOffering.plan(ChatMode.AGENT, false)
        assertFalse("the wire is withheld", plan.sendTools)
        assertNotNull("and the chat explains why", plan.note)
        assertTrue(
            "the note names the consequence, not the internals",
            plan.note!!.contains("not supporting tool calling"),
        )
    }

    @Test
    fun `Ask is offered the read wire, and still honours an explicit no`() {
        assertTrue("Ask can read, so it is offered tools", ToolOffering.plan(ChatMode.ASK, null).sendTools)
        assertFalse("but a model marked no never gets them", ToolOffering.plan(ChatMode.ASK, false).sendTools)
    }

    @Test
    fun `the edit mode still offers the write tools`() {
        assertTrue(ToolOffering.plan(ChatMode.EDIT, null).sendTools)
    }
}
