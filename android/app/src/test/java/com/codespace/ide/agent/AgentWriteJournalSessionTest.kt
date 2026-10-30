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
 * M1 — session attribution for AI edit records.
 *
 * The journal is process-wide, so before multi-session "undo the last AI edit" meant "undo the
 * last edit ANY conversation made". These tests pin down the fix: a change set belongs to the
 * session that opened it, is only visible to that session, and cannot be reversed from another
 * one - which is the owner's rule, not a nicety.
 */
class AgentWriteJournalSessionTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File.createTempFile("journal-session", "test").let { f ->
            f.delete()
            f.mkdirs()
            f
        }
        AgentWriteJournal.clear()
        AgentWriteJournal.activeSessionId = ""
    }

    @After
    fun tearDown() {
        AgentWriteJournal.clear()
        AgentWriteJournal.activeSessionId = ""
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).also { it.writeText(content) }

    /**
     * Simulates one agent write: the file is put in its BEFORE state, the operation opens, the
     * tool writes the AFTER content, and the journal records the pair - which is the exact
     * order AgentTools uses. Undo verifies by re-reading, so a file left in some other state
     * would (correctly) be refused as "changed since the agent wrote it".
     */
    private fun record(sessionId: String, name: String, before: String?, after: String): Long {
        val target = File(dir, name)
        if (before != null) target.writeText(before)
        val id = AgentWriteJournal.beginOperation(sessionId)
        target.writeText(after)
        AgentWriteJournal.record(target.absolutePath, before, after)
        return id
    }

    // ---------------------------------------------------------------- attribution

    @Test
    fun `a change set remembers the session that opened it`() {
        val id = record("s_a", "a.kt", null, "one")
        val set = AgentWriteJournal.findForSession(id, "s_a")
        assertNotNull(set)
        assertEquals("s_a", set!!.sessionId)
    }

    @Test
    fun `each session only sees its own change sets`() {
        record("s_a", "a.kt", null, "one")
        record("s_b", "b.kt", null, "two")

        assertEquals(listOf("a.kt"), AgentWriteJournal.setsFor("s_a").flatMap { it.entries }.map { File(it.path).name })
        assertEquals(listOf("b.kt"), AgentWriteJournal.setsFor("s_b").flatMap { it.entries }.map { File(it.path).name })
        assertEquals(0, AgentWriteJournal.setsFor("s_c").size)
    }

    @Test
    fun `reviewable is scoped to the session, and the active session defaults to it`() {
        record("s_a", "a.kt", null, "one")
        assertEquals("s_a", AgentWriteJournal.reviewable("s_a")?.sessionId)
        assertNull(AgentWriteJournal.reviewable("s_b"))

        AgentWriteJournal.activeSessionId = "s_b"
        assertNull("the active session has nothing to review", AgentWriteJournal.reviewable())
        assertEquals(0, AgentWriteJournal.setsForActive().size)
    }

    // ---------------------------------------------------------------- the owner's rule

    @Test
    fun `an edit made in session A cannot be undone from session B`() {
        val target = file("a.kt", "original")
        val id = record("s_a", "a.kt", "original", "changed")
        assertEquals("changed", target.readText())

        val refused = AgentWriteJournal.undo(id, "s_b")

        assertEquals(AgentWriteJournal.OTHER_SESSION, refused)
        assertEquals("the file must be untouched by a foreign undo", "changed", target.readText())
        assertFalse(AgentWriteJournal.findForSession(id, "s_a")!!.settled)
    }

    @Test
    fun `the owning session can still undo its own edit`() {
        val target = file("a.kt", "original")
        val id = record("s_a", "a.kt", "original", "changed")

        // Success is reported as a summary, not as an empty string.
        assertTrue(AgentWriteJournal.undo(id, "s_a").startsWith("Reverted"))
        assertEquals("original", target.readText())
        assertTrue(AgentWriteJournal.findForSession(id, "s_a")!!.settled)
    }

    @Test
    fun `keep is refused across sessions too`() {
        val id = record("s_a", "a.kt", null, "changed")

        assertEquals(AgentWriteJournal.OTHER_SESSION, AgentWriteJournal.keep(id, "s_b"))
        assertFalse(AgentWriteJournal.findForSession(id, "s_a")!!.settled)

        assertNull(AgentWriteJournal.keep(id, "s_a"))
        assertTrue(AgentWriteJournal.findForSession(id, "s_a")!!.settled)
    }

    // ---------------------------------------------------------------- lifetime

    @Test
    fun `forgetting a session drops only that session's records`() {
        record("s_a", "a.kt", null, "one")
        record("s_b", "b.kt", null, "two")

        AgentWriteJournal.forgetSession("s_a")

        assertTrue(AgentWriteJournal.setsFor("s_a").isEmpty())
        assertEquals(1, AgentWriteJournal.setsFor("s_b").size)
        assertNull(
            "an undo cannot reach a forgotten set",
            AgentWriteJournal.setsFor("s_a").firstOrNull(),
        )
    }

    @Test
    fun `a write with no announced operation is filed under the active session`() {
        AgentWriteJournal.activeSessionId = "s_live"
        AgentWriteJournal.record(File(dir, "c.kt").absolutePath, null, "x")

        assertEquals(1, AgentWriteJournal.setsFor("s_live").size)
        assertEquals(0, AgentWriteJournal.setsFor("s_other").size)
    }

    @Test
    fun `single session behaviour is unchanged when no session id is set`() {
        // The pre-multi-session callers pass nothing; empty matches empty.
        AgentWriteJournal.record(File(dir, "d.kt").absolutePath, null, "y")
        assertEquals(1, AgentWriteJournal.setsFor("").size)
        assertEquals("", AgentWriteJournal.reviewable()?.sessionId)
    }
}
