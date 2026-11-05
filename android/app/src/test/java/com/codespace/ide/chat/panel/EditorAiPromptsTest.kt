package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-05: the sparkle must not put a sentinel in the owner's transcript. */
class EditorAiPromptsTest {

    @Test
    fun theSentinelBecomesAnInstructionNamingTheOpenFile() {
        val out = EditorAiPrompts.resolve(EditorAiPrompts.REFACTOR_AI, "/sdcard/proj/wire-test/app.conf")
        assertFalse("no sentinel survives", out.contains(EditorAiPrompts.REFACTOR_AI))
        assertTrue("it names the file", out.contains("app.conf"))
        assertTrue("and it does not authorise edits yet", out.contains("do not change any files"))
    }

    @Test
    fun withNoOpenFileItStillSaysSomethingUsable() {
        val out = EditorAiPrompts.resolve(EditorAiPrompts.REFACTOR_AI, null)
        assertFalse(out.contains(EditorAiPrompts.REFACTOR_AI))
        assertTrue(out.contains("refactor"))
    }

    @Test
    fun aRealPromptIsPassedThroughUntouched() {
        val real = "Explain the selected function and list its edge cases."
        assertEquals(real, EditorAiPrompts.resolve(real, "/sdcard/proj/a.kt"))
    }
}
