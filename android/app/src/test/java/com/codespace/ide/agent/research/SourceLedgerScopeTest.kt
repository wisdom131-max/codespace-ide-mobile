package com.codespace.ide.agent.research

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PROJECT 1.8: a source belongs to the question that read it.
 *
 * The ledger documented "cleared at the start of each task" and had no caller doing it, so an
 * unrelated earlier page could appear in a later answer's citations. ChatSession now resets at a
 * new turn; these are the ledger-side guarantees that makes safe.
 */
class SourceLedgerScopeTest {

    @Before
    fun clear() = SourceLedger.reset()

    @After
    fun leaveClean() = SourceLedger.reset()

    @Test
    fun aResetEmptiesTheLedgerAndRestartsTheIds() {
        SourceLedger.note("https://example.com/a", "A", "2026-11-15 10:00")
        SourceLedger.note("https://example.org/b", "B", "2026-11-15 10:01")
        assertEquals(2, SourceLedger.all().size)
        SourceLedger.reset()
        assertEquals(0, SourceLedger.all().size)
        val fresh = SourceLedger.note("https://example.net/c", "C", "2026-11-15 10:02")
        assertEquals("S1", fresh.id)
    }

    @Test
    fun theSamePageIsOneCitationNotTwo() {
        val a = SourceLedger.note("https://example.com/page?utm_source=x", "Page", "2026-11-15 10:00")
        val b = SourceLedger.note("https://example.com/page/", "Page again", "2026-11-15 10:05")
        assertEquals(a.id, b.id)
        assertEquals(1, SourceLedger.all().size)
    }

    @Test
    fun aFailureIsNotACitation() {
        // Only real fetches note a source; the ledger never invents one.
        assertEquals(0, SourceLedger.all().size)
        assertTrue(SourceLedger.render().contains("none retrieved"))
    }
}
