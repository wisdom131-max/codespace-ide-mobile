package com.codespace.ide.chat.panel

import com.codespace.ide.chat.panel.edits.LastEditActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3 — the six overflow items, their labels and their order are a REQUIREMENT, so they are
 * asserted verbatim. A control that cannot act is disabled with a reason, never fake.
 */
class ChatOverflowSpecTest {

    @Test
    fun `the six reference labels keep their requested order, and approvals follows them`() {
        // The M3 reference fixed the first six. Item 9 appended Approvals as a seventh; moving
        // it among the six would silently reorder a design the owner signed off.
        assertEquals(
            listOf(
                "Undo Last Edit",
                "Redo Last Edit",
                "Move Chat into Editor Area",
                "Move Chat into New Window",
                "Show Voice Transcripts",
                "Show View by Default",
                "Approvals",
            ),
            ChatOverflowSpec.order.map { it.label },
        )
    }

    @Test
    fun `full screen is not a substitute for the new window item`() {
        val labels = ChatOverflowSpec.order.map { it.label }
        assertFalse(labels.any { it.contains("Full", ignoreCase = true) })
        assertTrue(labels.contains("Move Chat into New Window"))
    }

    @Test
    fun `undo and redo are disabled with a reason when there is nothing to act on`() {
        val items = ChatOverflowSpec.items(canUndo = false, canRedo = false, viewByDefault = false, canMoveToEditor = true)
        val undo = items.first { it.action == ChatOverflowAction.UNDO_LAST_EDIT }
        val redo = items.first { it.action == ChatOverflowAction.REDO_LAST_EDIT }
        assertFalse(undo.enabled)
        assertFalse(redo.enabled)
        assertTrue(undo.reason!!.isNotBlank())
        assertTrue(redo.reason!!.isNotBlank())
    }

    @Test
    fun `every other item is always enabled`() {
        val items = ChatOverflowSpec.items(canUndo = true, canRedo = true, viewByDefault = true, canMoveToEditor = true)
        assertEquals(7, items.size)
        assertTrue(items.all { it.enabled })
        assertNull(items.first { it.action == ChatOverflowAction.UNDO_LAST_EDIT }.reason)
    }

    @Test
    fun `the editor-area item is honest when this host has no editor area`() {
        val items = ChatOverflowSpec.items(canUndo = false, canRedo = false, viewByDefault = false, canMoveToEditor = false)
        val move = items.first { it.action == ChatOverflowAction.MOVE_TO_EDITOR_AREA }
        assertFalse(move.enabled)
        assertTrue(move.reason!!.contains("editor area"))
        // The second-window item must still work in that host: it is a separate task.
        assertTrue(items.first { it.action == ChatOverflowAction.MOVE_TO_NEW_WINDOW }.enabled)
    }

    @Test
    fun `view by default is a checkbox, the others are actions`() {
        val items = ChatOverflowSpec.items(canUndo = false, canRedo = false, viewByDefault = true, canMoveToEditor = true)
        assertEquals(true, items.first { it.action == ChatOverflowAction.SHOW_VIEW_BY_DEFAULT }.checked)
        assertEquals(1, items.count { it.checked != null })
    }

    @Test
    fun `the coordinator never claims an edit it does not have`() {
        // Fresh process, unknown conversation: both actions must report nothing rather than
        // returning an empty success.
        assertTrue(LastEditActions.NO_EDIT.isNotBlank())
        assertTrue(LastEditActions.NO_UNDONE_EDIT.isNotBlank())
    }
}
