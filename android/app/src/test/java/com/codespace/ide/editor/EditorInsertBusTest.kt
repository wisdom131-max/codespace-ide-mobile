package com.codespace.ide.editor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH 7 — the chat-to-editor seam.
 *
 * The honest cases are the negatives: no editor open, an editor that throws, and a stale
 * registration that must not be cleared by someone else's dispose.
 */
class EditorInsertBusTest {

    @After
    fun tearDown() = EditorInsertBus.clear()

    @Test
    fun nothingIsInsertableUntilAnEditorRegisters() {
        assertFalse(EditorInsertBus.canInsert())
        assertFalse("no editor open must report failure, not silence", EditorInsertBus.insert("x"))
    }

    @Test
    fun anInsertReachesTheRegisteredEditorVerbatim() {
        var got: String? = null
        EditorInsertBus.register { code -> got = code; true }
        assertTrue(EditorInsertBus.canInsert())
        assertTrue(EditorInsertBus.insert("val x = 1\n"))
        assertEquals("val x = 1\n", got)
    }

    @Test
    fun emptyCodeIsNotAnEdit() {
        var called = false
        EditorInsertBus.register { called = true; true }
        assertFalse(EditorInsertBus.insert(""))
        assertFalse("an empty insert would still move the user's caret", called)
    }

    @Test
    fun aRefusingEditorIsReportedNotSwallowed() {
        EditorInsertBus.register { false }
        assertFalse(EditorInsertBus.insert("x"))
    }

    @Test
    fun aThrowingEditorCannotCrashTheChat() {
        EditorInsertBus.register { throw IllegalStateException("read-only") }
        assertFalse(EditorInsertBus.insert("x"))
    }

    @Test
    fun aStaleUnregisterDoesNotClearTheNewEditor() {
        val first: (String) -> Boolean = { true }
        val second: (String) -> Boolean = { true }
        EditorInsertBus.register(first)
        EditorInsertBus.register(second)
        EditorInsertBus.unregister(first)
        assertTrue("the second editor is still on screen and must still receive inserts",
            EditorInsertBus.canInsert())
        EditorInsertBus.unregister(second)
        assertFalse(EditorInsertBus.canInsert())
    }

    @Test
    fun clearingLeavesNoSink() {
        EditorInsertBus.register { true }
        EditorInsertBus.clear()
        assertFalse(EditorInsertBus.canInsert())
        assertFalse(EditorInsertBus.insert("x"))
    }
}
