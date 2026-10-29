package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U4-d: the mode wording is data, so what each mode promises is asserted, not assumed. */
class ChatModePromptsTest {

    @Test
    fun everyModeHasItsOwnNonBlankInstruction() {
        val texts = ChatMode.values().map { ChatModePrompts.instruction(it) }
        assertEquals(3, texts.size)
        assertTrue(texts.none { it.isBlank() })
        assertEquals("instructions must differ per mode", 3, texts.toSet().size)
    }

    @Test
    fun noModeClaimsAFileWasChanged() {
        // Nothing is applied until the user accepts it, in every mode.
        for (m in ChatMode.values()) {
            val t = ChatModePrompts.instruction(m).lowercase()
            assertFalse("$m must not claim an applied edit", t.contains("i have modified"))
            assertFalse("$m must not claim an applied edit", t.contains("file was changed"))
        }
    }

    @Test
    fun askDoesNotInstructEditsAndEditDoesNotInstructCommands() {
        val ask = ChatModePrompts.instruction(ChatMode.ASK).lowercase()
        assertTrue("Ask explains rather than edits", ask.contains("instead of changing it"))
        val edit = ChatModePrompts.instruction(ChatMode.EDIT).lowercase()
        assertTrue("Edit asks for a diff", edit.contains("diff"))
        assertFalse("Edit does not run commands", edit.contains("run the command"))
    }

    @Test
    fun theChipLabelComesFromTheModeItself() {
        for (m in ChatMode.values()) assertEquals(m.label, ChatModePrompts.chipLabel(m))
    }
}
