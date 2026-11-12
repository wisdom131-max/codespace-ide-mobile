package com.codespace.ide.agent

import com.codespace.ide.chat.panel.RetryDecision
import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import com.codespace.ide.chat.panel.ToolRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Area 5: the ledger that stops a side effect from being applied twice. */
class ToolCallLedgerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ledger(name: String = "l.json"): ToolCallLedger =
        ToolCallLedger.forTest(File(tmp.root, name))

    @Test
    fun anUnknownCallHasNoRecord() = assertNull(ledger().lookup("call-1"))

    @Test
    fun aFirstExecutionStartsAsUnknownNotSafe() {
        val l = ledger()
        l.recordStarted("call-1", "write_file", ToolEffect.SIDE_EFFECTING)
        assertEquals(ToolOutcome.UNKNOWN_AFTER_START, l.lookup("call-1")!!.outcome)
        assertEquals(RetryDecision.RECONCILE_FIRST, ToolRetry.decide(l.lookup("call-1")!!.outcome, ToolEffect.SIDE_EFFECTING))
    }

    @Test
    fun aCompletedCallIsReusedNotRerun() {
        val l = ledger()
        l.recordCompleted("call-1", "write_file", ToolEffect.SIDE_EFFECTING, "wrote 3 bytes")
        val e = l.lookup("call-1")!!
        assertEquals(RetryDecision.REUSE_PERSISTED, ToolRetry.decide(e.outcome, ToolEffect.SIDE_EFFECTING))
        assertEquals("wrote 3 bytes", e.result)
    }

    @Test
    fun cancellationDuringExecutionIsNotProofItDidNotRun() {
        val l = ledger()
        l.recordFailed("call-1", "run_command", ToolEffect.SIDE_EFFECTING, ToolOutcome.CANCELLED_DURING_EXECUTION)
        assertEquals(RetryDecision.RECONCILE_FIRST, ToolRetry.decide(l.lookup("call-1")!!.outcome, ToolEffect.SIDE_EFFECTING))
    }

    @Test
    fun aReadThatWasCancelledMaySimplyBeRetried() {
        val l = ledger()
        l.recordFailed("call-1", "read_file", ToolEffect.READ_ONLY, ToolOutcome.CANCELLED_DURING_EXECUTION)
        assertEquals(RetryDecision.EXECUTE, ToolRetry.decide(l.lookup("call-1")!!.outcome, ToolEffect.READ_ONLY))
    }

    @Test
    fun theLedgerSurvivesAProcessRestart() {
        val f = File(tmp.root, "persist.json")
        ToolCallLedger.forTest(f).recordCompleted("call-9", "write_file", ToolEffect.SIDE_EFFECTING, "done")
        val reopened = ToolCallLedger.forTest(f)
        assertNotNull("a restored session must still know the write happened", reopened.lookup("call-9"))
        assertEquals(RetryDecision.REUSE_PERSISTED, ToolRetry.decide(reopened.lookup("call-9")!!.outcome, ToolEffect.SIDE_EFFECTING))
    }

    @Test
    fun anUnreadableLedgerIsTreatedAsEmptyNotAsProof() {
        val f = File(tmp.root, "broken.json")
        f.writeText("{not json")
        val l = ToolCallLedger.forTest(f)
        assertNull(l.lookup("anything"))
    }

    @Test
    fun theLedgerIsBounded() {
        val l = ledger()
        repeat(ToolCallLedger.MAX_ENTRIES + 40) {
            l.recordCompleted("call-$it", "write_file", ToolEffect.SIDE_EFFECTING, "r")
        }
        assertNull("oldest entries must fall off", l.lookup("call-0"))
        assertNotNull("the newest entry must remain", l.lookup("call-" + (ToolCallLedger.MAX_ENTRIES + 39)))
    }

    @Test
    fun aStoredResultIsTruncatedNotUnbounded() {
        val l = ledger()
        l.recordCompleted("call-1", "read_file", ToolEffect.READ_ONLY, "x".repeat(50_000))
        assertTrue(l.lookup("call-1")!!.result!!.length <= ToolCallLedger.RESULT_CAP)
    }

    @Test
    fun aDuplicateCompletedIdNeverNeedsExecutionAgain() {
        val l = ledger()
        l.recordCompleted("dup", "zip_create", ToolEffect.SIDE_EFFECTING, "archive written")
        repeat(3) {
            val e = l.lookup("dup")!!
            assertEquals(RetryDecision.REUSE_PERSISTED, ToolRetry.decide(e.outcome, ToolEffect.SIDE_EFFECTING))
        }
        assertFalse(ToolRetry.dangerousToRepeat(l.lookup("dup")!!.outcome, ToolEffect.SIDE_EFFECTING))
    }
}
