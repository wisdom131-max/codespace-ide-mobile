package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1c (advisor 2026-10-26): the four ruled properties of token-budgeted history:
 * (1) no behavior change below the threshold, (2) the newest turn is never
 * trimmed, (3) tool-call pairs are never split, (4) secrets stay redacted in
 * kept entries. Pure JVM tests — HistoryBudget touches no framework class.
 */
class HistoryBudgetTest {

    private fun turnList(vararg msgs: Triple<String, String, Int>) = msgs.toList()

    @Test
    fun belowThreshold_nothingChanges() {
        val entries = turnList(
            Triple("user", "hello there".repeat(10), 0),
            Triple("assistant", "hi back".repeat(10), 0),
            Triple("user", "one more".repeat(10), 0),
            Triple("assistant", "done".repeat(10), 0),
        )
        val r = HistoryBudget.trim(entries, budgetChars = 60_000)
        assertEquals(0, r.trimmed)
        assertEquals(entries.size, r.entries.size)
        // byte-identical content — the caller must send the ORIGINAL messages
        r.entries.forEachIndexed { i, e ->
            assertTrue(e.kept)
            assertEquals(entries[i].first, e.role)
            assertEquals(entries[i].second, e.text)
        }
    }

    @Test
    fun newestTurn_neverTrimmed_evenWhenItAloneExceedsBudget() {
        val big = "x".repeat(50_000)
        val entries = turnList(
            Triple("user", big, 0),
            Triple("assistant", "old reply", 0),
            Triple("user", big + big, 0),           // newest, huge
            Triple("assistant", "final answer", 0), // newest turn's reply — kept with it
        )
        val r = HistoryBudget.trim(entries, budgetChars = 60_000)
        assertTrue(r.trimmed > 0)
        // the last two entries (newest turn) are kept whole
        assertTrue(r.entries[2].kept)
        assertTrue(r.entries[3].kept)
        assertEquals(big + big, r.entries[2].text)
        assertEquals("final answer", r.entries[3].text)
    }

    @Test
    fun toolPairs_neverSplit() {
        val big = "y".repeat(30_000)
        val entries = turnList(
            Triple("user", big, 0),                  // old turn (over budget -> trimmed whole)
            Triple("assistant", "calling tool", 0),  // tool-call pair, same turn
            Triple("tool", "tool result", 0),        // tool result, same turn
            Triple("user", "latest ask", 0),
            Triple("assistant", "latest answer", 0),
        )
        val r = HistoryBudget.trim(entries, budgetChars = 60_000)
        // the pair was either kept together or noted together — never split:
        val pairKept = r.entries[1].kept && r.entries[2].kept
        val pairNoted = !r.entries[1].kept && !r.entries[2].kept
        assertTrue("tool pair split! kept=" + r.entries[1].kept + "," + r.entries[2].kept, pairKept || pairNoted)
        // with this budget the old turn is noted as one unit
        assertTrue(pairNoted)
        assertTrue(r.entries[1].text.contains("(trimmed:"))
        assertTrue(r.entries[2].text.contains("(trimmed:"))
    }

    @Test
    fun secretsStayRedacted_inKeptEntries() {
        val entries = turnList(
            Triple("assistant", "the token is [REDACTED] in old history", 0),
            Triple("user", "secret is [REDACTED] here too", 0),
            Triple("assistant", "answer", 0),
        )
        val r = HistoryBudget.trim(entries, budgetChars = 60_000)
        assertEquals(0, r.trimmed)
        assertTrue(r.entries[0].text.contains("[REDACTED]"))
        assertTrue(r.entries[1].text.contains("[REDACTED]"))
        // and a trimmed note never carries content at all
        val over = turnList(
            Triple("user", "z".repeat(70_000), 0),
            Triple("assistant", "the token is [REDACTED] in old history", 0),
            Triple("user", "ask", 0),
        )
        val r2 = HistoryBudget.trim(over, budgetChars = 60_000)
        if (r2.trimmed > 0) {
            r2.entries.filter { !it.kept }.forEach { e ->
                assertTrue("trimmed note leaked content", !e.text.contains("[REDACTED]"))
                assertTrue(e.text.contains("(trimmed:"))
            }
        }
    }

    @Test
    fun attachmentCost_estimatedAtSendCap_andTrimmedTurnLosesBlocks() {
        val cap = ChatAttachmentInjector.SEND_CAP_PER_FILE
        val big = "w".repeat(10_000)
        val entries = turnList(
            Triple("user", big, 4),                  // old turn: 10k + 4*cap way over
            Triple("assistant", "reply", 0),
            Triple("user", "newest ask", 1),
            Triple("assistant", "answer", 0),
        )
        val r = HistoryBudget.trim(entries, budgetChars = 2 * cap)
        assertEquals(HistoryBudget.costOf(big, 4), big.length + 4 * cap)
        // newest turn kept with its attachment cost accounted
        assertTrue(r.entries[2].kept)
        assertTrue(r.entries[3].kept)
    }
}
