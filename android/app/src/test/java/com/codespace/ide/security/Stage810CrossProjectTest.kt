package com.codespace.ide.security

import com.codespace.ide.chat.panel.DeliveryLedger
import com.codespace.ide.chat.provider.ToolExchangeLog
import com.codespace.ide.engine.ExchangeStatus
import com.codespace.ide.chat.session.HandoffGuard
import com.codespace.ide.agent.ExecutionBoundaryScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.10 — the cross-project audit, expressed as executable claims.
 *
 * Each case names the seam it exercises and asserts the property the whole system relies on.
 * These drive the real production objects; none of them is a re-implementation of the logic
 * under test.
 */
class Stage810CrossProjectTest {

    // ---------- 1/2/3: tool exchange status is derived from the executor's own words ----------

    @Test
    fun anExchangeIsNeverOkWhenTheOutcomeWasUncertainOrCancelled() {
        assertEquals(
            ExchangeStatus.UNCERTAIN,
            ToolExchangeLog.statusOf("CANCELLED by the user"),
        )
        assertEquals(
            ExchangeStatus.UNCERTAIN,
            ToolExchangeLog.statusOf("UNSURE: the write may have landed"),
        )
        assertEquals(ExchangeStatus.UNCERTAIN, ToolExchangeLog.statusOf("   "))
    }

    @Test
    fun aRefusalIsNotAFailureAndNeitherIsSilentlyOk() {
        assertEquals(
            ExchangeStatus.REFUSED,
            ToolExchangeLog.statusOf("REFUSED: no approval was given"),
        )
        assertEquals(
            ExchangeStatus.REFUSED,
            ToolExchangeLog.statusOf("NOT RUN: prerequisite missing"),
        )
        assertEquals(
            ExchangeStatus.FAILED,
            ToolExchangeLog.statusOf("FAILED: path does not exist"),
        )
    }

    @Test
    fun aRealResultWithNoSuccessWordingStillCounts() {
        // Scenario 16: plenty of genuine outputs never say "success".
        assertEquals(
            ExchangeStatus.OK,
            ToolExchangeLog.statusOf("3 files matched: Main.kt, App.kt, Util.kt"),
        )
    }

    // ---------- 14/15: cancellation and staleness ----------

    @Test
    fun aCancelledExecutionCannotAppendAnAnswer() {
        val ledger = DeliveryLedger()
        val session = "s-1"
        val execution = "e-1"
        assertTrue(ledger.beginSubmission(session, execution))
        assertTrue(ledger.mayAppendAnswer(execution, session))
        ledger.cancel(execution)
        assertFalse("a cancelled turn must not produce a reply", ledger.mayAppendAnswer(execution, session))
    }

    @Test
    fun oneExecutionCannotBeStartedTwice() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s-1", "e-1"))
        assertFalse("the same execution must not submit twice", ledger.beginSubmission("s-1", "e-1"))
    }

    @Test
    fun aStaleExecutionCannotOwnANewerSession() {
        val ledger = DeliveryLedger()
        ledger.beginSubmission("s-old", "e-old")
        assertFalse(ledger.ownsExecution("e-old", "s-new"))
        assertFalse(ledger.mayAppendAnswer("e-old", "s-new"))
    }

    // ---------- 6/11: continuation stays injective and inert ----------

    @Test
    fun anEmptySuccessorIsReusedAndAStartedOneBranches() {
        val empty = HandoffGuard.decide(
            parentId = "p1",
            existingChildren = listOf("c1" to "Continued from p1"),
            rowsInChild = { 0 },
            labelFor = { "Continued from p1" },
        )
        val started = HandoffGuard.decide(
            parentId = "p1",
            existingChildren = listOf("c1" to "Continued from p1"),
            rowsInChild = { 2 },
            labelFor = { "Continued from p1" },
        )
        assertEquals(
            "an empty successor is reused, never duplicated",
            HandoffGuard.Decision.Reuse("c1", "Continued from p1"),
            empty,
        )
        assertEquals(HandoffGuard.Decision.Create, started)
    }

    // ---------- 8/9/10: the invariants that must never drift ----------

    @Test
    fun theExecutionBoundaryScannerStillRejectsABypass() {
        val report = ExecutionBoundaryScan.scan(
            mapOf(
                "AgentTools.kt" to "private suspend fun executeToolBody() {} fun executeTool() { executeToolBody() }",
                "Rogue.kt" to "fun go() { executeToolBody() }",
            ),
            names = emptyList(),
        )
        assertFalse("a second call site must be reported", report.violations.isEmpty())
        assertTrue(report.violations.any { it.contains("Rogue.kt") })
    }
}
