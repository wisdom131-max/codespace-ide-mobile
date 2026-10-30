package com.codespace.ide.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * M0 — the metadata contract.
 *
 * These are the rules the store relies on, so they are asserted directly rather than inferred
 * from a file round trip: an id is either already a safe path segment or it is rejected, a
 * title is one line of UI, and a record whose timestamps contradict themselves never reaches
 * the disk.
 */
class SessionMetaTest {

    private fun meta(
        id: String = "s_abc",
        title: String = "A session",
        created: Long = 1_000L,
        updated: Long = 2_000L,
        archived: Boolean = false,
        lastRead: Long = 0L,
    ) = SessionMeta(id, title, created, updated, archived, lastRead)

    // ---------------------------------------------------------------- validation

    @Test
    fun `a well formed record has no problems`() {
        assertTrue(SessionMetaRules.problems(meta()).isEmpty())
        assertTrue(SessionMetaRules.isValid(meta()))
    }

    @Test
    fun `an id that would be rewritten by the transcript store is rejected`() {
        // TranscriptFiles.safe() turns "my chat" into "my_chat", so "my chat" and "my_chat"
        // would share ONE transcript file. That is silent message mixing, so it is refused.
        val problems = SessionMetaRules.problems(meta(id = "my chat"))
        assertEquals(listOf(SessionProblem.ILLEGAL_ID_CHARS), problems)
        assertFalse(SessionMetaRules.isSafeId("my chat"))
        assertTrue(SessionMetaRules.isSafeId("my_chat"))
    }

    @Test
    fun `path traversal ids are rejected`() {
        assertFalse(SessionMetaRules.isSafeId(".."))
        assertFalse(SessionMetaRules.isSafeId("."))
        assertFalse(SessionMetaRules.isSafeId("a/b"))
        assertEquals(listOf(SessionProblem.BLANK_ID), SessionMetaRules.problems(meta(id = "")))
    }

    @Test
    fun `an id longer than the transcript store accepts is rejected as too long`() {
        val long = "a".repeat(SessionMeta.MAX_ID + 1)
        assertEquals(listOf(SessionProblem.ID_TOO_LONG), SessionMetaRules.problems(meta(id = long)))
    }

    @Test
    fun `the legacy main id is valid and needs no rewriting`() {
        assertTrue(SessionMetaRules.isSafeId(SessionMeta.LEGACY_ID))
        assertTrue(SessionMetaRules.isValid(meta(id = SessionMeta.LEGACY_ID)))
    }

    @Test
    fun `blank and oversized titles are rejected`() {
        assertEquals(listOf(SessionProblem.BLANK_TITLE), SessionMetaRules.problems(meta(title = " ")))
        val long = "t".repeat(SessionMeta.MAX_TITLE + 1)
        assertEquals(listOf(SessionProblem.TITLE_TOO_LONG), SessionMetaRules.problems(meta(title = long)))
    }

    @Test
    fun `timestamps must be non negative and must not go backwards`() {
        assertEquals(listOf(SessionProblem.NEGATIVE_TIME), SessionMetaRules.problems(meta(created = -1L)))
        assertEquals(
            listOf(SessionProblem.UPDATED_BEFORE_CREATED),
            SessionMetaRules.problems(meta(created = 5_000L, updated = 4_000L)),
        )
    }

    // ---------------------------------------------------------------- read state

    @Test
    fun `read is derived from the timestamps rather than stored separately`() {
        assertFalse(meta(updated = 2_000L, lastRead = 1_999L).isRead)
        assertTrue(meta(updated = 2_000L, lastRead = 2_000L).isRead)
        assertTrue(meta(updated = 2_000L, lastRead = 9_000L).isRead)
    }

    // ---------------------------------------------------------------- ids

    @Test
    fun `generated ids are safe, opaque and distinct`() {
        val r = Random(7)
        val ids = (1..200).map { SessionIds.newId(r) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { SessionMetaRules.isSafeId(it) })
        assertTrue(ids.all { it.startsWith("s_") })
        // Opaque: nothing about a title or a clock can be read out of it.
        assertTrue(ids.none { it == SessionMeta.LEGACY_ID })
    }

    @Test
    fun `the same seed produces the same id so tests are deterministic`() {
        assertEquals(SessionIds.newId(Random(42)), SessionIds.newId(Random(42)))
        assertNotEquals(SessionIds.newId(Random(42)), SessionIds.newId(Random(43)))
    }

    // ---------------------------------------------------------------- titles

    @Test
    fun `a title is derived from the first non blank line of the first user message`() {
        assertEquals("Refactor the parser", SessionTitles.derive("\n\n  Refactor   the parser \nmore"))
    }

    @Test
    fun `no user text at all means the untitled label`() {
        assertEquals(SessionMeta.UNTITLED, SessionTitles.derive(null))
        assertEquals(SessionMeta.UNTITLED, SessionTitles.derive("   \n\t "))
    }

    @Test
    fun `a long first message is clipped to one line of UI`() {
        val long = "word ".repeat(40)
        val derived = SessionTitles.derive(long)
        assertEquals(SessionMeta.DERIVED_TITLE_CHARS + 1, derived.length)
        assertTrue(derived.endsWith("\u2026"))
    }

    @Test
    fun `renaming normalises whitespace and refuses blank text`() {
        assertEquals("Sprint planning", SessionTitles.normalise("  Sprint \n planning  "))
        assertEquals(null, SessionTitles.normalise("   "))
        val long = "x".repeat(SessionMeta.MAX_TITLE + 50)
        assertEquals(SessionMeta.MAX_TITLE, SessionTitles.normalise(long)?.length)
    }
}
