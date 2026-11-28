package com.codespace.ide.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OWNER REPORT (2026-11-28): a full-width grey bar with no text appeared on provider switch, on
 * returning to the app and in a new chat. The source was a note carrying "" instead of null.
 */
class ChatRowGuardTest {

    @Test
    fun `a note with no text is refused`() {
        assertTrue(ChatRowGuard.isBlankNote(ChatRowModel.InfoNote("")))
        assertTrue(ChatRowGuard.isBlankNote(ChatRowModel.InfoNote("   ")))
    }

    @Test
    fun `a note with text is kept`() {
        assertFalse(ChatRowGuard.isBlankNote(ChatRowModel.InfoNote("Read 42 lines")))
    }

    @Test
    fun `other rows are never dropped by this guard`() {
        assertFalse(ChatRowGuard.isBlankNote(ChatRowModel.Failed("boom", "detail", true)))
    }
}
