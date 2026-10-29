package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch 2 — the staged-apply report may not overstate what happened. */
class StagedApplySummaryTest {

    @Test
    fun anEmptyBatchSaysThereWasNothingToDo() {
        assertEquals("Nothing to apply.", stagedApplySummary(total = 0, applied = 0))
    }

    @Test
    fun aFullSuccessReportsTheCount() {
        assertEquals("Applied 3 file(s).", stagedApplySummary(total = 3, applied = 3))
    }

    @Test
    fun aPartialFailureNamesBothHalves() {
        val note = stagedApplySummary(total = 3, applied = 2)
        assertTrue(note.contains("Applied 2"))
        assertTrue(note.contains("1 failed"))
    }

    @Test
    fun aTotalFailureNeverReadsAsSuccess() {
        val note = stagedApplySummary(total = 2, applied = 0)
        assertTrue(note.startsWith("Nothing was applied"))
        assertTrue(note.contains("2 failed"))
    }
}
