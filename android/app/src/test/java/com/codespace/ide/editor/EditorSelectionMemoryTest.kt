package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 2026-11-05: the caret plumbing behind "undo restores the selection, not offset 0". */
class EditorSelectionMemoryTest {

    @Before
    fun fresh() = EditorSelectionMemory.resetForTest()

    @Test
    fun nothingIsInventedForAFileNobodyReported() {
        assertNull(EditorSelectionMemory.selectionFor("/a.kt"))
        assertNull(EditorSelectionMemory.consumeRestore("/a.kt"))
    }

    @Test
    fun theLastReportedSelectionWins() {
        EditorSelectionMemory.remember("/a.kt", 1, 1)
        EditorSelectionMemory.remember("/a.kt", 40, 55)
        assertEquals(EditorSelectionMemory.Sel(40, 55), EditorSelectionMemory.selectionFor("/a.kt"))
    }

    @Test
    fun filesDoNotShareACaret() {
        EditorSelectionMemory.remember("/a.kt", 10, 10)
        EditorSelectionMemory.remember("/b.kt", 90, 90)
        assertEquals(10, EditorSelectionMemory.selectionFor("/a.kt")?.start)
        assertEquals(90, EditorSelectionMemory.selectionFor("/b.kt")?.start)
    }

    @Test
    fun aRestoreIsConsumedExactlyOnce() {
        EditorSelectionMemory.requestRestore("/a.kt", 12, 18)
        assertEquals(EditorSelectionMemory.Sel(12, 18), EditorSelectionMemory.consumeRestore("/a.kt"))
        assertNull("a second sync must not fight the user's next tap", EditorSelectionMemory.consumeRestore("/a.kt"))
    }

    @Test
    fun rememberingNeverCreatesARestore() {
        EditorSelectionMemory.remember("/a.kt", 5, 9)
        assertNull("moving the caret is not a request to move it back", EditorSelectionMemory.consumeRestore("/a.kt"))
    }

    @Test
    fun aRenameCarriesBothTheCaretAndAPendingRestore() {
        EditorSelectionMemory.remember("/old.kt", 3, 7)
        EditorSelectionMemory.requestRestore("/old.kt", 3, 7)
        EditorSelectionMemory.rekey("/old.kt", "/new.kt")
        assertNull(EditorSelectionMemory.selectionFor("/old.kt"))
        assertEquals(EditorSelectionMemory.Sel(3, 7), EditorSelectionMemory.selectionFor("/new.kt"))
        assertEquals(EditorSelectionMemory.Sel(3, 7), EditorSelectionMemory.consumeRestore("/new.kt"))
    }

    @Test
    fun clampingHandlesTextThatShrankAndBackwardsSelections() {
        assertEquals(EditorSelectionMemory.Sel(2, 4), EditorSelectionMemory.clamp(EditorSelectionMemory.Sel(2, 4), 10))
        assertEquals(EditorSelectionMemory.Sel(4, 6), EditorSelectionMemory.clamp(EditorSelectionMemory.Sel(4, 40), 6))
        assertEquals(EditorSelectionMemory.Sel(1, 3), EditorSelectionMemory.clamp(EditorSelectionMemory.Sel(3, 1), 10))
        assertEquals(EditorSelectionMemory.Sel(0, 0), EditorSelectionMemory.clamp(EditorSelectionMemory.Sel(9, 9), 0))
    }

    @Test
    fun aSyncUsesThePendingRestoreAndOnlyOnce() {
        EditorSelectionMemory.requestRestore("/a.kt", 12, 18)
        // The sync would otherwise map the old caret to 1..1; the request must win.
        assertEquals(EditorSelectionMemory.Sel(12, 18),
            EditorSelectionMemory.resolveRestore("/a.kt", 1, 1, 100))
        // Next sync: nothing pending, so the mapped offsets are used.
        assertEquals(EditorSelectionMemory.Sel(1, 1),
            EditorSelectionMemory.resolveRestore("/a.kt", 1, 1, 100))
    }

    @Test
    fun aRestoreIntoShorterTextIsClampedNotDropped() {
        EditorSelectionMemory.requestRestore("/a.kt", 40, 40)
        assertEquals(EditorSelectionMemory.Sel(6, 6),
            EditorSelectionMemory.resolveRestore("/a.kt", 0, 0, 6))
    }

    @Test
    fun aRestoreForAnotherFileIsNotTaken() {
        EditorSelectionMemory.requestRestore("/a.kt", 9, 9)
        assertEquals(EditorSelectionMemory.Sel(2, 2),
            EditorSelectionMemory.resolveRestore("/b.kt", 2, 2, 50))
        assertEquals(EditorSelectionMemory.Sel(9, 9),
            EditorSelectionMemory.resolveRestore("/a.kt", 2, 2, 50))
    }
}
