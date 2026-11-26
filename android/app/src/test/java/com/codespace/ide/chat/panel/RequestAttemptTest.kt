package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 3 (Checkpoint 0.5): per-attempt ownership.
 *
 * These assert the property the whole stage exists for: two attempts cannot see or damage each
 * other's text, and an attempt can be finalized exactly once.
 */
class RequestAttemptTest {

    @Test
    fun `two attempts do not share a buffer`() {
        val a = RequestAttempt(gen = 1, execId = "exec-1")
        val b = RequestAttempt(gen = 2, execId = "exec-2")
        a.append("first")
        b.append("second")
        assertEquals("first", a.text())
        assertEquals("second", b.text())
        assertNotSame(a.rawBuffer(), b.rawBuffer())
    }

    @Test
    fun `a stale attempt writing after a newer one began cannot reach the newer text`() {
        val attempts = RequestAttempts()
        val older = attempts.begin(gen = 1, execId = "exec-1")
        older.append("old ")
        val newer = attempts.begin(gen = 2, execId = "exec-2")
        newer.append("new")
        // The stale attempt keeps writing into its own object.
        older.append("tail")
        assertEquals("new", attempts.current()!!.text())
        assertEquals("old tail", older.text())
    }

    @Test
    fun `drain returns only what was not published yet and never repeats it`() {
        val a = RequestAttempt(gen = 1, execId = "exec-1")
        a.append("Hello ")
        assertEquals("Hello ", a.drain())
        assertEquals("", a.drain())
        a.append("world")
        assertEquals("world", a.drain())
        assertEquals("Hello world", a.text())
    }

    @Test
    fun `finalization is claimed exactly once`() {
        val a = RequestAttempt(gen = 1, execId = "exec-1")
        assertTrue(a.claimFinalization())
        assertFalse(a.claimFinalization())
        assertFalse(a.claimFinalization())
        assertTrue(a.isFinalized())
    }

    @Test
    fun `the registry keeps the newest attempts and forgets the oldest`() {
        val attempts = RequestAttempts(keep = 2)
        attempts.begin(gen = 1, execId = "exec-1")
        attempts.begin(gen = 2, execId = "exec-2")
        attempts.begin(gen = 3, execId = "exec-3")
        assertEquals(3, attempts.current()!!.gen)
        assertTrue(attempts.of(1) == null)
        assertTrue(attempts.of(2) != null)
        assertTrue(attempts.of(3) != null)
    }

    @Test
    fun `each attempt carries its own execution id so a late event is judged by its own`() {
        val attempts = RequestAttempts()
        attempts.begin(gen = 1, execId = "exec-1")
        attempts.begin(gen = 2, execId = "exec-2")
        assertEquals("exec-1", attempts.of(1)!!.execId)
        assertEquals("exec-2", attempts.of(2)!!.execId)
    }

    @Test
    fun `a cleared attempt starts empty and republishes from the beginning`() {
        val a = RequestAttempt(gen = 1, execId = "exec-1")
        a.append("done")
        a.drain()
        a.clearBuffer()
        assertEquals("", a.text())
        a.append("again")
        assertEquals("again", a.drain())
    }
}
