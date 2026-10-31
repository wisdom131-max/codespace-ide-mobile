package com.codespace.ide.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Item 19 - saved undo history (2026-10-31).
 *
 * What these prove, and why each one is worth a test:
 *  - a change set survives a restart EXACTLY (paths, images, settled state, outcome, note), so
 *    undo after reopening restores the real content rather than an approximation;
 *  - one session's history can never load as another's, because the file is named per session
 *    inside its project's directory;
 *  - damage loads as NO history, not as a partial stack (a partial stack can revert the wrong
 *    file);
 *  - a set that cannot be represented is refused whole rather than persisted in pieces;
 *  - after a restart, undo still refuses when the file on disk no longer matches what the agent
 *    wrote, which is the property that makes persistence safe at all.
 */
class JournalPersistenceTest {

    private lateinit var root: File
    private lateinit var work: File

    @Before
    fun setUp() {
        root = File.createTempFile("vncode-journal", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        work = File.createTempFile("vncode-file", ".txt").let { f ->
            f.delete()
            f
        }
        AgentWriteJournal.configure(root)
        AgentWriteJournal.resetForTest()
    }

    @After
    fun tearDown() {
        AgentWriteJournal.configure(null)
        AgentWriteJournal.resetForTest()
        root.deleteRecursively()
        work.delete()
    }

    private fun set(vararg entries: AgentWriteJournal.Entry, session: String = "s_a") =
        AgentWriteJournal.ChangeSet(id = 7L, entries = entries.toList(), sessionId = session)

    private fun entry(path: String, before: String?, after: String) =
        AgentWriteJournal.Entry(path, if (before == null) AgentWriteJournal.Kind.CREATED else AgentWriteJournal.Kind.MODIFIED, before, after)

    @Test
    fun aChangeSetSurvivesEncodeDecodeExactly() {
        val original = set(
            entry("/p/a.txt", "one\n", "two\n"),
            entry("/p/b.txt", null, "created\n"),
        ).copy(settled = true, outcome = "undone", note = "Reverted 2 files")

        val back = JournalStore.decode(JournalStore.encode(listOf(original))!!).single()

        assertEquals(original.id, back.id)
        assertEquals(original.sessionId, back.sessionId)
        assertEquals(original.settled, back.settled)
        assertEquals(original.outcome, back.outcome)
        assertEquals(original.note, back.note)
        assertEquals(original.entries.size, back.entries.size)
        assertEquals("one\n", back.entries[0].before)
        assertEquals("two\n", back.entries[0].after)
        assertNull("a CREATED entry keeps its null before-image", back.entries[1].before)
        assertEquals(AgentWriteJournal.Kind.CREATED, back.entries[1].kind)
    }

    @Test
    fun textWithTabsAndNewlinesIsNotMangled() {
        val nasty = "line1\n\ttabbed\t\nunicode: \u00e9\u20ac\n"
        val back = JournalStore.decode(
            JournalStore.encode(listOf(set(entry("/p/n.txt", "old", nasty))))!!,
        ).single()
        assertEquals("the image must round-trip byte for byte", nasty, back.entries[0].after)
    }

    @Test
    fun oneSessionsHistoryNeverLoadsAsAnothers() {
        JournalStore.save(root, "proj", "s_a", listOf(set(entry("/p/a.txt", "x", "y"), session = "s_a")))
        JournalStore.save(root, "proj", "s_b", listOf(set(entry("/p/b.txt", "x", "y"), session = "s_b")))
        assertEquals(listOf("s_a"), JournalStore.load(root, "proj", "s_a").map { it.sessionId })
        assertEquals(listOf("s_b"), JournalStore.load(root, "proj", "s_b").map { it.sessionId })
        assertEquals(0, JournalStore.load(root, "proj", "s_c").size)
        // and the same session id under a DIFFERENT project is a different file
        assertEquals(0, JournalStore.load(root, "other-project", "s_a").size)
    }

    @Test
    fun aDamagedFileLoadsAsNoHistoryInsteadOfHalfAStack() {
        val f = JournalStore.file(root, "proj", "s_a")
        f.parentFile.mkdirs()
        f.writeText("vjournal 1\nset\t7\t0\t-\t-\tb" + "AAAA" + "\ne\tMODIFIED\tnotbase64\t-\tb###\n")
        assertEquals("damage must not be read as a smaller stack", 0, JournalStore.load(root, "proj", "s_a").size)

        f.writeText("junk\n")
        assertEquals(0, JournalStore.load(root, "proj", "s_a").size)
    }

    @Test
    fun aSetTooLargeToHoldSafelyIsRefusedWholeRatherThanPartiallySaved() {
        val huge = "x".repeat(JournalStore.MAX_IMAGE_CHARS + 1)
        assertNull("encode must refuse rather than truncate", JournalStore.encode(listOf(set(entry("/p/h.txt", null, huge)))))
        val error = JournalStore.save(root, "proj", "s_a", listOf(set(entry("/p/h.txt", null, huge))))
        assertEquals(JournalStore.TOO_LARGE, error)
        assertEquals(0, JournalStore.load(root, "proj", "s_a").size)
    }

    @Test
    fun anEmptyHistoryRemovesTheFileInsteadOfLeavingAStaleOne() {
        JournalStore.save(root, "proj", "s_a", listOf(set(entry("/p/a.txt", "x", "y"))))
        assertTrue(JournalStore.file(root, "proj", "s_a").exists())
        assertNull(JournalStore.save(root, "proj", "s_a", emptyList()))
        assertTrue(!JournalStore.file(root, "proj", "s_a").exists())
    }

    @Test
    fun undoWorksAfterARestartBecauseTheSetIsReloaded() {
        work.writeText("agent version\n")
        AgentWriteJournal.activate("proj", "s_a")
        AgentWriteJournal.beginOperation("s_a")
        AgentWriteJournal.record(work.absolutePath, "original\n", "agent version\n")
        val setId = AgentWriteJournal.setsFor("s_a").single().id

        // ---- the process dies here ----
        AgentWriteJournal.resetForTest()
        assertEquals(0, AgentWriteJournal.setsFor("s_a").size)

        AgentWriteJournal.activate("proj", "s_a")
        val restored = AgentWriteJournal.setsFor("s_a").single()
        assertEquals(setId, restored.id)
        assertNotNull(restored.entries.firstOrNull())

        val note = AgentWriteJournal.undo(setId, "s_a")
        assertEquals("original\n", work.readText())
        assertTrue("undo must report what it did: " + note, note.startsWith("Reverted 1 file"))
    }

    @Test
    fun afterARestartUndoStillRefusesAFileThatChangedUnderneathIt() {
        work.writeText("agent version\n")
        AgentWriteJournal.activate("proj", "s_a")
        AgentWriteJournal.beginOperation("s_a")
        AgentWriteJournal.record(work.absolutePath, "original\n", "agent version\n")
        val setId = AgentWriteJournal.setsFor("s_a").single().id

        AgentWriteJournal.resetForTest()
        AgentWriteJournal.activate("proj", "s_a")

        // the user edited the file in between
        work.writeText("the user's own edit\n")
        val note = AgentWriteJournal.undo(setId, "s_a")
        assertEquals("the user's own edit\n", work.readText())
        assertTrue("refusal must be stated: " + note, note.contains("changed since the agent wrote it"))
    }

    @Test
    fun aDeletedTargetIsReportedNotRecreated() {
        work.writeText("agent version\n")
        AgentWriteJournal.activate("proj", "s_a")
        AgentWriteJournal.beginOperation("s_a")
        AgentWriteJournal.record(work.absolutePath, "original\n", "agent version\n")
        val setId = AgentWriteJournal.setsFor("s_a").single().id

        work.delete()
        val note = AgentWriteJournal.undo(setId, "s_a")
        assertTrue("missing file must be named: " + note, note.contains("no longer there"))
        assertTrue("and must not be recreated", !work.exists())
    }

    @Test
    fun aRestoredSetIsStillInvisibleToAnotherSession() {
        work.writeText("agent version\n")
        AgentWriteJournal.activate("proj", "s_a")
        AgentWriteJournal.beginOperation("s_a")
        AgentWriteJournal.record(work.absolutePath, "original\n", "agent version\n")
        val setId = AgentWriteJournal.setsFor("s_a").single().id

        AgentWriteJournal.resetForTest()
        AgentWriteJournal.activate("proj", "s_b")
        assertEquals(0, AgentWriteJournal.setsFor("s_b").size)
        assertEquals("agent version\n", work.readText())
        AgentWriteJournal.undo(setId, "s_b")
        assertEquals("another session must not revert this file", "agent version\n", work.readText())
    }

    @Test
    fun forgettingASessionDeletesItsSavedHistory() {
        AgentWriteJournal.activate("proj", "s_a")
        AgentWriteJournal.beginOperation("s_a")
        AgentWriteJournal.record(work.absolutePath, "original\n", "agent version\n")
        assertTrue(JournalStore.file(root, "proj", "s_a").exists())

        AgentWriteJournal.forgetSession("s_a")
        assertTrue("a deleted session must not leave undoable history", !JournalStore.file(root, "proj", "s_a").exists())
        assertEquals(0, AgentWriteJournal.setsFor("s_a").size)
    }
}
