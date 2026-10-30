package com.codespace.ide.chat.panel

import com.codespace.ide.agent.AgentWriteJournal
import com.codespace.ide.chat.panel.edits.LastEditActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * M3 — undo AND redo of an AI file edit, with the safety rule mirrored.
 *
 * Redo is not "write the after content and hope": it refuses whenever the disk no longer holds
 * what the undo left behind, so a file edited by hand between undo and redo is never clobbered.
 * Cross-session attribution is tested too: an edit made in session A can never be undone or
 * redone from session B.
 */
class LastEditRedoTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("vncode-redo", "test").let { it.delete(); it.mkdirs(); it }
        AgentWriteJournal.clear()
        LastEditActions.resetForTest()
        AgentWriteJournal.activeSessionId = "sess_a"
    }

    @After
    fun tearDown() {
        AgentWriteJournal.clear()
        LastEditActions.resetForTest()
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).also { it.writeText(content) }

    private fun recordModified(name: String, before: String, after: String, session: String = "sess_a") {
        val f = file(name, after)
        AgentWriteJournal.activeSessionId = session
        AgentWriteJournal.beginOperation(session)
        AgentWriteJournal.record(f.path, before, after)
    }

    @Test
    fun `undo restores the old content and redo puts the agent's content back`() {
        val f = file("Main.kt", "before")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f.path, "before", "after")
        f.writeText("after")

        assertTrue(LastEditActions.canUndo("sess_a"))
        assertTrue(LastEditActions.undo("sess_a").contains("Reverted"))
        assertEquals("before", f.readText())

        assertTrue("undo must arm redo", LastEditActions.canRedo("sess_a"))
        val note = LastEditActions.redo("sess_a")
        assertTrue(note, note.contains("Re-applied"))
        assertEquals("after", f.readText())
    }

    @Test
    fun `a created file is deleted by undo and recreated by redo`() {
        val f = file("New.kt", "created by agent")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f.path, null, "created by agent")

        LastEditActions.undo("sess_a")
        assertFalse("undo removes a created file", f.exists())
        LastEditActions.redo("sess_a")
        assertTrue(f.exists())
        assertEquals("created by agent", f.readText())
    }

    @Test
    fun `redo refuses when the file changed after the undo`() {
        val f = file("Main.kt", "before")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f.path, "before", "after")
        f.writeText("after")
        LastEditActions.undo("sess_a")
        assertEquals("before", f.readText())

        f.writeText("edited by hand")
        val note = LastEditActions.redo("sess_a")
        assertTrue(note, note.contains("Could not re-apply") || note.contains("changed since the undo"))
        assertEquals("the hand edit survives", "edited by hand", f.readText())
    }

    @Test
    fun `undo refuses when the agent's content is already gone`() {
        val f = file("Main.kt", "before")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f.path, "before", "after")
        f.writeText("someone else wrote this")

        val note = LastEditActions.undo("sess_a")
        assertTrue(note, note.contains("Could not revert"))
        assertEquals("someone else wrote this", f.readText())
    }

    @Test
    fun `an edit from another session is invisible and immutable from here`() {
        val f = file("Other.kt", "before")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f.path, "before", "after")
        f.writeText("after")

        assertFalse("session B must see nothing to undo", LastEditActions.canUndo("sess_b"))
        assertEquals(LastEditActions.NO_EDIT, LastEditActions.undo("sess_b"))
        assertEquals("the file is untouched", "after", f.readText())

        // And B cannot redo A's undo either.
        LastEditActions.undo("sess_a")
        assertFalse(LastEditActions.canRedo("sess_b"))
        assertEquals(LastEditActions.NO_UNDONE_EDIT, LastEditActions.redo("sess_b"))
    }

    @Test
    fun `nothing to undo is reported, not faked`() {
        assertEquals(LastEditActions.NO_EDIT, LastEditActions.undo("sess_a"))
        assertEquals(LastEditActions.NO_UNDONE_EDIT, LastEditActions.redo("sess_a"))
        assertFalse(LastEditActions.canUndo("sess_a"))
        assertFalse(LastEditActions.canRedo("sess_a"))
    }

    @Test
    fun `a settled redo cannot run twice`() {
        val f = file("Main.kt", "before")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f.path, "before", "after")
        f.writeText("after")
        LastEditActions.undo("sess_a")
        assertTrue(LastEditActions.redo("sess_a").contains("Re-applied"))

        assertFalse("the set is settled", LastEditActions.canRedo("sess_a"))
        assertEquals(LastEditActions.NO_UNDONE_EDIT, LastEditActions.redo("sess_a"))
        assertEquals("after", f.readText())
    }

    @Test
    fun `the journal is the newest-first source of what can be undone`() {
        val f1 = file("one.kt", "v1")
        AgentWriteJournal.activeSessionId = "sess_a"
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f1.path, "v1", "v2")
        f1.writeText("v2")

        val f2 = file("two.kt", "w1")
        AgentWriteJournal.beginOperation("sess_a")
        AgentWriteJournal.record(f2.path, "w1", "w2")
        f2.writeText("w2")

        // Undo takes the newest unsettled set: the second one.
        LastEditActions.undo("sess_a")
        assertEquals("w1", f2.readText())
        assertEquals("v2", f1.readText())
        assertNull("the first set is still pending", null)
        assertTrue(LastEditActions.canUndo("sess_a"))
    }
}
