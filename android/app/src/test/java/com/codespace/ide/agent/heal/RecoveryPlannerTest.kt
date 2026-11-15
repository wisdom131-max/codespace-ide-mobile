package com.codespace.ide.agent.heal

import com.codespace.ide.agent.heal.RecoveryPlanner.Category
import com.codespace.ide.agent.heal.RecoveryPlanner.Failure
import com.codespace.ide.agent.heal.RecoveryPlanner.Mode
import com.codespace.ide.agent.heal.RecoveryPlanner.RepairKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 3.5: a repair may only be automatic when it cannot change anything the owner cares about.
 *
 * Brahma's healer rewrites arguments and launches an Intent with no visible gate. These cases exist to
 * make sure ours cannot: the write case, the unknown-outcome case, the Ask case, the spent-budget case
 * and the "it said recovered but the error is still there" case.
 */
class RecoveryPlannerTest {

    private fun failure(
        error: String,
        category: Category = Category.UNKNOWN,
        sideEffecting: Boolean = false,
        outcomeKnown: Boolean = true,
        attempts: Int = 0,
        mode: Mode = Mode.AGENT,
    ) = Failure(
        tool = "write_file",
        error = error,
        category = category,
        sideEffecting = sideEffecting,
        outcomeKnown = outcomeKnown,
        attemptsSoFar = attempts,
        mode = mode,
    )

    @Test
    fun aReadOnlyTransientFailureIsRepairedAutomatically() {
        val plan = RecoveryPlanner.plan(failure("timed out after 30s", Category.TIMEOUT))
        assertEquals(Category.TIMEOUT, plan.category)
        assertEquals(RepairKind.RETRY_READ, plan.auto()!!.kind)
        assertTrue(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0))
    }

    @Test
    fun theOriginalErrorIsAlwaysPreserved() {
        val original = "java.io.FileNotFoundException: no such file app/Mian.kt"
        val plan = RecoveryPlanner.plan(failure(original, Category.MISSING_RESOURCE))
        assertEquals(original, plan.original)
        assertTrue(plan.summary().contains(Category.MISSING_RESOURCE.phrase))
    }

    @Test
    fun aFailingWriteIsNeverRepairedAutomatically() {
        val plan = RecoveryPlanner.plan(failure("timeout", Category.TIMEOUT, sideEffecting = true))
        assertNull("a write is not retried on its own", plan.auto())
        false.let { assertTrue(plan.repairs.none { it.autoApply }) }
        assertFalse(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0))
        assertTrue(plan.summary().contains("not applied"))
    }

    @Test
    fun aCallWhoseOutcomeIsUnknownIsNeverRetried() {
        val plan = RecoveryPlanner.plan(
            failure("connection reset", Category.PROVIDER_NETWORK, sideEffecting = true, outcomeKnown = false),
        )
        assertFalse(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0))
        assertTrue(plan.needsUser)
    }

    @Test
    fun askModeExecutesNothingEvenWhenTheRepairIsSafe() {
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT))
        assertNotNull("the repair is still offered as a proposal", plan.auto())
        assertFalse("Ask is read-only by owner decision", RecoveryPlanner.mayExecute(plan, Mode.ASK, 0))
    }

    @Test
    fun oneRepairPerFailureAndNoMore() {
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT))
        assertTrue(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0))
        assertFalse("a second repair is a loop", RecoveryPlanner.mayExecute(plan, Mode.AGENT, 1))
    }

    @Test
    fun tooManyAttemptsEndsTheRecovery() {
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT, attempts = 2))
        assertFalse(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0))
    }

    @Test
    fun cancellationStopsRepairsToo() {
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT))
        assertFalse(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0, cancelled = true))
    }

    @Test
    fun permissionFailuresAreNeverSelfHealed() {
        val plan = RecoveryPlanner.plan(failure("permission denied opening /sdcard", Category.PERMISSION))
        assertEquals(RepairKind.REQUEST_APPROVAL, plan.repairs.first().kind)
        assertFalse(plan.repairs.first().autoApply)
        assertTrue(plan.needsUser)
        assertFalse(RecoveryPlanner.mayExecute(plan, Mode.AGENT, 0))
    }

    @Test
    fun aDependencyFailureAsksRatherThanFakes() {
        val plan = RecoveryPlanner.plan(failure("no build outputs yet", Category.DEPENDENCY))
        assertEquals(RepairKind.ASK_USER, plan.repairs.first().kind)
        assertTrue(plan.summary().contains("Do not fake its result"))
    }

    @Test
    fun anUnrecognisedFailureIsReportedNotRetried() {
        val plan = RecoveryPlanner.plan(failure("something odd happened"))
        assertEquals(Category.UNKNOWN, plan.category)
        assertEquals(RepairKind.REPORT_ONLY, plan.repairs.first().kind)
        assertNull(plan.auto())
        assertTrue(plan.summary().contains("no safe repair"))
    }

    @Test
    fun theClassifierReusesTheRetryTaxonomy() {
        assertEquals(Category.MISSING_RESOURCE, RecoveryPlanner.classify(failure("No such file or directory")))
        assertEquals(Category.PERMISSION, RecoveryPlanner.classify(failure("permission denied")))
        assertEquals(Category.PROVIDER_NETWORK, RecoveryPlanner.classify(failure("connection refused")))
        assertEquals(Category.RESOURCE_EXHAUSTION, RecoveryPlanner.classify(failure("file too large: exceeds limit")))
        assertEquals(Category.INVALID_ARGUMENTS, RecoveryPlanner.classify(failure("invalid argument: path")))
    }

    @Test
    fun aRecoveryWithoutEvidenceIsAFailure() {
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT))
        val repair = plan.auto()!!
        val verdict = RecoveryPlanner.verdict(repair, "   ")
        assertTrue(verdict is RecoveryPlanner.Verdict.Failed)
        assertTrue((verdict as RecoveryPlanner.Verdict.Failed).reason.contains("original failure stands"))
    }

    @Test
    fun aRecoveryThatDidNotFixItIsReportedAsFailed() {
        val plan = RecoveryPlanner.plan(failure("no such file: app/Mian.kt", Category.MISSING_RESOURCE))
        val repair = plan.auto()!!
        val verdict = RecoveryPlanner.verdict(repair, "still fails: no such file: app/Mian.kt")
        assertTrue(verdict is RecoveryPlanner.Verdict.Failed)
    }

    @Test
    fun aValidatedRecoveryIsOnlyRecoveredWithEvidence() {
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT))
        val verdict = RecoveryPlanner.verdict(plan.auto()!!, "read 214 lines from app/Main.kt")
        assertTrue(verdict is RecoveryPlanner.Verdict.Recovered)
        assertEquals("read 214 lines from app/Main.kt", (verdict as RecoveryPlanner.Verdict.Recovered).evidence)
    }

    @Test
    fun aProposedRepairIsNeverCountedAsRecovered() {
        val plan = RecoveryPlanner.plan(failure("permission denied", Category.PERMISSION))
        assertTrue(RecoveryPlanner.verdict(plan.repairs.first(), "owner approved") is RecoveryPlanner.Verdict.Proposed)
    }

    @Test
    fun theLedgerKeepsTheOriginalAndTheOutcome() {
        val ledger = RecoveryPlanner.Ledger()
        val plan = RecoveryPlanner.plan(failure("timed out", Category.TIMEOUT))
        val entry = ledger.record(plan, plan.auto()!!, "read 12 lines", true)
        assertEquals("timed out", entry.original)
        assertEquals(RepairKind.RETRY_READ, entry.repair)
        assertTrue(entry.recovered)
        assertEquals(1, ledger.lines().size)
        assertTrue(ledger.lines().first().startsWith("recovered"))
    }

    @Test
    fun theLedgerIsBoundedSoLongSessionsDoNotGrowForever() {
        val ledger = RecoveryPlanner.Ledger(cap = 3)
        repeat(10) { index ->
            val plan = RecoveryPlanner.plan(failure("failure " + index, Category.TIMEOUT))
            ledger.record(plan, plan.repairs.first(), "outcome " + index, false)
        }
        assertEquals(3, ledger.all().size)
        assertTrue(ledger.all().last().original.contains("9"))
    }

    @Test
    fun theLedgerCountsRepeatedFailuresOfTheSameError() {
        val ledger = RecoveryPlanner.Ledger()
        val plan = RecoveryPlanner.plan(failure("same error text", Category.TIMEOUT))
        ledger.record(plan, plan.repairs.first(), "no luck", false)
        assertEquals(1, ledger.attemptsFor("same error text"))
        assertEquals(0, ledger.attemptsFor("something else"))
    }

    @Test
    fun everyCategoryProducesACauseAndAtLeastOneRepair() {
        Category.entries.forEach { category ->
            val plan = RecoveryPlanner.plan(failure("some failure", category))
            assertTrue(category.name, plan.cause.isNotBlank())
            assertTrue(category.name, plan.repairs.isNotEmpty())
        }
    }

    @Test
    fun noCategoryOffersAnAutomaticRepairForAWrite() {
        Category.entries.forEach { category ->
            val plan = RecoveryPlanner.plan(failure("some failure", category, sideEffecting = true))
            assertTrue(
                category.name + " offered an automatic repair for a write",
                plan.repairs.none { it.autoApply },
            )
        }
    }

    @Test
    fun noCategoryOffersAnAutomaticRepairInAskMode() {
        Category.entries.forEach { category ->
            val plan = RecoveryPlanner.plan(failure("some failure", category, mode = Mode.ASK))
            assertTrue(category.name, plan.repairs.none { it.autoApply })
        }
    }
}
