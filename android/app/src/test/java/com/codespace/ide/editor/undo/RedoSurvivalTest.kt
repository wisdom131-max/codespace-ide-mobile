package com.codespace.ide.editor.undo

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-05 (remaining item 2: "redo survival").
 *
 * CONVENTION, established by the first run of this file and worth stating because getting it wrong
 * produces an undo that does nothing rather than an error: a snapshot is the state to restore TO, so
 * `push` is called with the state BEFORE the edit, and `undo(current)` returns that stored state
 * while putting `current` on the redo stack.
 *
 * The worry on the list was that the EDITOR's external content sync pushes a snapshot of its own and
 * eats the redo stack, so redo would work once and then die. Reading CodeEditor.externalContentSync
 * settles the sync half: it sets the value and fires ProgrammaticTextChange and never calls push()
 * or pushForce() (the only push sites are the typing path and the chat-apply undo gate). What that
 * leaves is the STACK contract itself, which is what these tests pin.
 */
class RedoSurvivalTest {

    private fun snap(text: String) = SnapshotUndoManager.TextSnapshot(text, TextRange.Zero, emptyList())

    /** coalesceMs = 0 so consecutive pushes are genuinely separate steps. */
    private fun stack(max: Int = 200) = SnapshotUndoManager(maxStackSize = max, coalesceMs = 0)

    @Test
    fun redoSurvivesMoreThanOneStep() {
        val s = stack()
        s.push(snap("a"))    // before the edit that produced "ab"
        s.push(snap("ab"))   // before the edit that produced "abc"
        val current = "abc"

        assertEquals("ab", s.undo(snap(current))?.text)
        assertEquals("a", s.undo(snap("ab"))?.text)

        // The point of the test: TWO redos, not one. A stack that loses redo after the first step
        // would fail on the second call here.
        assertEquals("ab", s.redo(snap("a"))?.text)
        assertEquals("abc", s.redo(snap("ab"))?.text)
        assertNull("nothing left to redo", s.redo(snap("abc")))
    }

    @Test
    fun undoThenRedoRoundTripsRepeatedly() {
        val s = stack()
        s.push(snap("one")) // before the edit that produced "two"
        repeat(3) { round ->
            assertEquals("one", s.undo(snap("two"))?.text)
            assertEquals("two", s.redo(snap("one"))?.text)
            // One branch: after the redo there is nothing left to redo, and the earlier state is
            // still undoable. Clicking undo/redo back and forth must never exhaust either side.
            assertTrue("undo must survive round " + round, s.canUndo())
            assertTrue("nothing left to redo in round " + round, !s.canRedo())
        }
    }

    @Test
    fun aNewEditIsWhatKillsRedo() {
        val s = stack()
        s.push(snap("a"))   // before "ab"
        s.push(snap("ab"))  // before "ab+new"
        assertEquals("ab", s.undo(snap("ab+new"))?.text)
        assertTrue(s.canRedo())

        // VS Code semantics: a fresh edit after an undo truncates the redo branch. This is the ONLY
        // thing that should consume redo.
        s.push(snap("ab"))
        assertNull("the abandoned branch is gone", s.redo(snap("ab")))
    }

    @Test
    fun forcePushStartsAFreshGroupAndKillsRedo() {
        val s = stack()
        s.push(snap("a"))
        s.push(snap("ab"))
        s.undo(snap("ab"))
        assertTrue("the abandoned branch is redoable first", s.canRedo())

        s.pushForce(snap("programmatic"))
        assertTrue("a force push is a new undo step", s.canUndo())
        assertNull("a programmatic edit is a new branch, not a redo target", s.redo(snap("programmatic")))
    }

    @Test
    fun readingTheStackNeverConsumesRedo() {
        val s = stack()
        s.push(snap("a"))
        s.push(snap("ab"))
        s.undo(snap("ab"))
        // canRedo()/canUndo() are read on every menu open and every toolbar paint; they must be
        // free of side effects or redo would vanish just from looking at it.
        repeat(5) {
            assertTrue(s.canRedo())
            assertTrue(s.canUndo())
        }
        assertNotNull(s.redo(snap("a")))
    }

    @Test
    fun depthLimitDropsTheOldestAndKeepsRedoBehaviour() {
        val s = stack(max = 3)
        s.push(snap("1"))
        s.push(snap("2"))
        s.push(snap("3"))
        s.push(snap("4")) // drops "1"
        assertEquals("4", s.undo(snap("5"))?.text)
        assertEquals("3", s.undo(snap("4"))?.text)
        assertEquals("4", s.redo(snap("3"))?.text)
        assertEquals("5", s.redo(snap("4"))?.text)
    }
}
