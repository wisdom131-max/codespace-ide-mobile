package com.codespace.ide.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Section 5 — the Files Changed row's data layer.
 *
 * These are the assertions that stop the row from lying: a change that never reached disk,
 * an undo that clobbers the user's later edit, a revert reported as successful without being
 * verified, and two operations merged into one row.
 */
class AgentWriteJournalTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("journal", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        AgentWriteJournal.clear()
    }

    @After
    fun tearDown() {
        AgentWriteJournal.clear()
        dir.deleteRecursively()
    }

    private fun write(name: String, content: String): File =
        File(dir, name).also { it.writeText(content) }

    // --- recording ----------------------------------------------------------------------

    @Test
    fun aCreatedFileIsRecordedAsCreatedWithNoBeforeImage() {
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(File(dir, "a.kt").absolutePath, null, "hello")
        val entry = AgentWriteJournal.reviewable()!!.entries.single()
        assertEquals(AgentWriteJournal.Kind.CREATED, entry.kind)
        assertNull(entry.before)
        assertEquals("hello", entry.after)
    }

    @Test
    fun aModifiedFileKeepsItsBeforeImage() {
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(File(dir, "a.kt").absolutePath, "old", "new")
        val entry = AgentWriteJournal.reviewable()!!.entries.single()
        assertEquals(AgentWriteJournal.Kind.MODIFIED, entry.kind)
        assertEquals("old", entry.before)
    }

    @Test
    fun nothingIsReviewableWhenNothingChanged() {
        AgentWriteJournal.beginOperation()
        assertNull(AgentWriteJournal.reviewable())
    }

    @Test
    fun twoOperationsAreTwoSetsNeverOneMergedRow() {
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(File(dir, "a.kt").absolutePath, null, "one")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(File(dir, "b.kt").absolutePath, null, "two")
        assertEquals(2, AgentWriteJournal.sets.size)
        assertEquals(listOf("b.kt"), AgentWriteJournal.reviewable()!!.entries.map { File(it.path).name })
    }

    @Test
    fun writingTheSamePathTwiceKeepsTheOriginalBeforeImage() {
        // Undo must restore the pre-OPERATION content, not the intermediate one.
        AgentWriteJournal.beginOperation()
        val p = File(dir, "a.kt").absolutePath
        AgentWriteJournal.record(p, "v0", "v1")
        AgentWriteJournal.record(p, "v1", "v2")
        val entry = AgentWriteJournal.reviewable()!!.entries.single()
        assertEquals("v0", entry.before)
        assertEquals("v2", entry.after)
    }

    @Test
    fun aSettledSetIsNoLongerReviewable() {
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(File(dir, "a.kt").absolutePath, "old", "new")
        val id = AgentWriteJournal.reviewable()!!.id
        AgentWriteJournal.keep(id)
        assertNull(AgentWriteJournal.reviewable())
    }

    @Test
    fun onlyTheNewestFewSetsAreKept() {
        repeat(AgentWriteJournal.MAX_SETS + 2) {
            AgentWriteJournal.beginOperation()
            AgentWriteJournal.record(File(dir, "f$it.kt").absolutePath, null, "x")
        }
        assertTrue(AgentWriteJournal.sets.size <= AgentWriteJournal.MAX_SETS)
    }

    // --- keep ---------------------------------------------------------------------------

    @Test
    fun keepLeavesTheFilesExactlyAsTheAgentWroteThem() {
        val f = write("keep.kt", "agent version")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(f.absolutePath, "user version", "agent version")
        AgentWriteJournal.keep(AgentWriteJournal.reviewable()!!.id)
        assertEquals("agent version", f.readText())
        assertTrue(f.exists())
    }

    // --- undo: created ------------------------------------------------------------------

    @Test
    fun undoDeletesAFileThatStillHoldsTheAgentVersion() {
        val f = write("new.kt", "agent content")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(f.absolutePath, null, "agent content")
        val note = AgentWriteJournal.undo(AgentWriteJournal.reviewable()!!.id)
        assertFalse("the created file must be gone", f.exists())
        assertTrue(note, note.contains("Reverted 1 file"))
    }

    @Test
    fun undoRefusesToDeleteAFileTheUserEditedAfterwards() {
        val f = write("new.kt", "user improved this")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(f.absolutePath, null, "agent content")
        val note = AgentWriteJournal.undo(AgentWriteJournal.reviewable()!!.id)
        assertTrue("the user's file must survive", f.exists())
        assertEquals("user improved this", f.readText())
        assertTrue(note, note.contains("changed since the agent wrote it"))
        assertTrue(note, note.contains("Could not revert"))
    }

    // --- undo: modified -----------------------------------------------------------------

    @Test
    fun undoRestoresThePreviousContent() {
        val f = write("mod.kt", "agent version")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(f.absolutePath, "original", "agent version")
        val note = AgentWriteJournal.undo(AgentWriteJournal.reviewable()!!.id)
        assertEquals("original", f.readText())
        assertTrue(note, note.contains("Reverted 1 file"))
    }

    @Test
    fun undoRefusesWhenTheFileWasDeletedAfterTheWrite() {
        AgentWriteJournal.beginOperation()
        val p = File(dir, "gone.kt").absolutePath
        AgentWriteJournal.record(p, "original", "agent version")
        val note = AgentWriteJournal.undo(AgentWriteJournal.reviewable()!!.id)
        assertTrue(note, note.contains("no longer there"))
        assertFalse(File(p).exists())
    }

    @Test
    fun aPartialUndoIsReportedAsPartialNotAsSuccess() {
        val ok = write("ok.kt", "agent")
        val edited = write("edited.kt", "user changed this")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(ok.absolutePath, "before-ok", "agent")
        AgentWriteJournal.record(edited.absolutePath, "before-edited", "agent")
        val note = AgentWriteJournal.undo(AgentWriteJournal.reviewable()!!.id)
        assertEquals("before-ok", ok.readText())          // reverted
        assertEquals("user changed this", edited.readText())  // left alone
        assertTrue(note, note.contains("Reverted 1; left alone"))
    }

    @Test
    fun aSetCannotBeUndoneTwice() {
        val f = write("twice.kt", "agent")
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(f.absolutePath, "original", "agent")
        val id = AgentWriteJournal.reviewable()!!.id
        AgentWriteJournal.undo(id)
        val second = AgentWriteJournal.undo(id)
        assertTrue(second, second.contains("already"))
        assertEquals("original", f.readText())
    }

    @Test
    fun undoingAnUnknownSetIsReported() {
        val note = AgentWriteJournal.undo(4242L)
        assertTrue(note, note.contains("no longer available"))
    }

    // --- diff ---------------------------------------------------------------------------

    @Test
    fun diffCountsExactlyWhatChanged() {
        val d = AgentWriteJournal.diff("a\nb\nc", "a\nB\nc")
        assertEquals(1, d.added)
        assertEquals(1, d.removed)
        assertTrue(d.lines.any { it.kind == AgentWriteJournal.DiffKind.REMOVED && it.text == "b" })
        assertTrue(d.lines.any { it.kind == AgentWriteJournal.DiffKind.ADDED && it.text == "B" })
    }

    @Test
    fun diffOfAnIdenticalFileIsEmpty() {
        val d = AgentWriteJournal.diff("same\ntext", "same\ntext")
        assertEquals(0, d.added)
        assertEquals(0, d.removed)
    }

    @Test
    fun diffOfAWholeNewFileIsAllAdditions() {
        val d = AgentWriteJournal.diff(null, "one\ntwo\nthree")
        assertEquals(3, d.added)
        assertEquals(0, d.removed)
    }

    @Test
    fun anAppendOnlyChangeIsAllAdditions() {
        val d = AgentWriteJournal.diff("keep", "keep\nmore")
        assertEquals(1, d.added)
        assertEquals(0, d.removed)
    }

    @Test
    fun aHugeChangeIsTruncatedAndSaysSo() {
        val big = (1..(AgentWriteJournal.MAX_DIFF_LINES + 50)).joinToString("\n") { "line-$it" }
        val d = AgentWriteJournal.diff(null, big)
        assertTrue(d.lines.any { it.text.contains("more lines not shown") })
        // The COUNT still reports the whole truth even when the preview is clipped.
        assertEquals(AgentWriteJournal.MAX_DIFF_LINES + 50, d.added)
    }

    @Test
    fun summaryLabelReadsCorrectlyForEachKind() {
        val created = AgentWriteJournal.Entry("/x/a.kt", AgentWriteJournal.Kind.CREATED, null, "1\n2")
        assertEquals("new file (2 lines)", AgentWriteJournal.summaryLabel(created))
        val modified = AgentWriteJournal.Entry("/x/b.kt", AgentWriteJournal.Kind.MODIFIED, "a", "a\nb")
        assertEquals("+1 \u22120", AgentWriteJournal.summaryLabel(modified))
        val unchanged = AgentWriteJournal.Entry("/x/c.kt", AgentWriteJournal.Kind.MODIFIED, "a", "a")
        assertEquals("no line changes", AgentWriteJournal.summaryLabel(unchanged))
    }

    @Test
    fun dismissingClearsOnlyASettledSet() {
        AgentWriteJournal.beginOperation()
        AgentWriteJournal.record(File(dir, "a.kt").absolutePath, null, "x")
        val id = AgentWriteJournal.reviewable()!!.id
        AgentWriteJournal.dismiss(id)                 // still open: must not vanish
        assertNotNull(AgentWriteJournal.reviewable())
        AgentWriteJournal.keep(id)
        AgentWriteJournal.dismiss(id)
        assertTrue(AgentWriteJournal.sets.isEmpty())
    }
}
