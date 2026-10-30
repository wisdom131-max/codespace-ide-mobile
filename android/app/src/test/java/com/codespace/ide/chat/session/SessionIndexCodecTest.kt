package com.codespace.ide.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M0 — the index format.
 *
 * The centre of gravity is the STRICT reader: a damaged table must be reported as damaged,
 * never as an empty one, because the store decides whether it is allowed to write based on
 * exactly that distinction. Each test names the failure mode it pins down.
 */
class SessionIndexCodecTest {

    private fun meta(id: String, title: String = "T " + id, created: Long = 1L, updated: Long = 2L) =
        SessionMeta(id, title, created, updated)

    private fun ok(raw: String): SessionIndex {
        val read = SessionIndexCodec.decode(raw)
        assertTrue("expected Ok but was " + read, read is SessionIndexRead.Ok)
        return (read as SessionIndexRead.Ok).index
    }

    private fun unreadable(raw: String): String {
        val read = SessionIndexCodec.decode(raw)
        assertTrue("expected Unreadable but was " + read, read is SessionIndexRead.Unreadable)
        return (read as SessionIndexRead.Unreadable).reason
    }

    // ---------------------------------------------------------------- round trip

    @Test
    fun `an index round trips with its active id and every field intact`() {
        val index = SessionIndex(
            sessions = listOf(
                meta("s_one", "First", 100L, 200L),
                SessionMeta("s_two", "Second", 300L, 400L, archived = true, lastReadAtMs = 350L),
                meta(SessionMeta.LEGACY_ID, "Legacy", 1L, 2L),
            ),
            activeId = "s_two",
        )
        val decoded = ok(SessionIndexCodec.encode(index))
        assertEquals(index.sessions, decoded.sessions)
        assertEquals("s_two", decoded.activeId)
    }

    @Test
    fun `an empty index still carries a shape that cannot be mistaken for damage`() {
        val raw = SessionIndexCodec.encode(SessionIndex())
        assertTrue(raw.startsWith(SessionIndexCodec.FORMAT_VERSION))
        assertEquals(SessionIndex(), ok(raw))
    }

    @Test
    fun `titles survive tabs, newlines and backslashes`() {
        val index = SessionIndex(listOf(meta("s_x", "a\tb\nc\\d")), "s_x")
        val decoded = ok(SessionIndexCodec.encode(index))
        assertEquals("a\tb\nc\\d", decoded.sessions.single().title)
    }

    // ---------------------------------------------------------------- strictness

    @Test
    fun `a blank file is damage, not an empty project`() {
        // A torn write leaves zero bytes. Treating that as "no sessions" is the silent-empty
        // failure this whole design exists to prevent.
        unreadable("")
        unreadable("   \n\n ")
    }

    @Test
    fun `an unknown header is damage`() {
        assertTrue(unreadable("v1\nactive\t\n").contains("format"))
        assertTrue(unreadable("{\"sessions\":[]}").contains("format"))
    }

    @Test
    fun `a single malformed session line makes the whole table unreadable`() {
        val good = SessionIndexCodec.encode(SessionIndex(listOf(meta("s_a")), "s_a"))
        // Drop the last field of the record: a truncated line, exactly what a torn write leaves.
        val damaged = good.trimEnd('\n').substringBeforeLast('\t')
        assertTrue(unreadable(damaged).contains("malformed"))
    }

    @Test
    fun `an unknown record type is damage rather than being ignored`() {
        unreadable(SessionIndexCodec.FORMAT_VERSION + "\nactive\t\nzzz\twhatever\n")
    }

    @Test
    fun `duplicate ids are damage because they would alias one transcript`() {
        val raw = SessionIndexCodec.FORMAT_VERSION + "\nactive\ts_a\n" +
            "s\ts_a\tOne\t1\t2\t0\t0\task\n" +
            "s\ts_a\tTwo\t3\t4\t0\t0\tagent\n"
        assertTrue(unreadable(raw).contains("duplicate"))
    }

    @Test
    fun `a record that fails validation is damage`() {
        val raw = SessionIndexCodec.FORMAT_VERSION + "\nactive\t\n" +
            "s\tbad id\tTitle\t1\t2\t0\t0\task\n"
        assertTrue(unreadable(raw).contains("invalid"))
    }

    @Test
    fun `a missing active line is damage`() {
        unreadable(SessionIndexCodec.FORMAT_VERSION + "\ns\ts_a\tT\t1\t2\t0\t0\task\n")
    }

    @Test
    fun `an encoder refuses to write an invalid record`() {
        var threw = false
        try {
            SessionIndexCodec.encode(SessionIndex(listOf(SessionMeta("bad id", "T", 1, 2))))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("an invalid record must not reach the disk", threw)
    }

    // ---------------------------------------------------------------- active id

    @Test
    fun `a dangling active id resolves to the newest session instead of vanishing`() {
        val index = SessionIndex(listOf(meta("s_a", created = 1L), meta("s_b", created = 9L)), "s_gone")
        assertEquals("s_b", index.resolvedActiveId)
    }

    @Test
    fun `an empty index has no active session`() {
        assertEquals(null, SessionIndex().resolvedActiveId)
    }

    @Test
    fun `upsert replaces in place without reordering`() {
        val index = SessionIndex(listOf(meta("s_a"), meta("s_b")), "s_a")
        val next = index.upsert(meta("s_a", title = "renamed"))
        assertEquals(listOf("s_a", "s_b"), next.sessions.map { it.id })
        assertEquals("renamed", next.find("s_a")?.title)
        assertEquals(2, next.sessions.size)
    }

    @Test
    fun `without removes exactly one session`() {
        val index = SessionIndex(listOf(meta("s_a"), meta("s_b")), "s_a")
        val next = index.without("s_a")
        assertEquals(listOf("s_b"), next.sessions.map { it.id })
        assertEquals("s_b", next.resolvedActiveId)
    }
}
