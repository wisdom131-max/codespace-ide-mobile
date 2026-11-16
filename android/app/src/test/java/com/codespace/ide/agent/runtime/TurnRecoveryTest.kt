package com.codespace.ide.agent.runtime

import com.codespace.ide.agent.goal.TaskPlanEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 4.4: an interrupted turn is described, not guessed at.
 */
class TurnRecoveryTest {

    private fun entry(
        capability: String,
        tool: String,
        failed: Boolean = false,
        sideEffect: Boolean = false,
        session: String = "s1",
        revision: Int = 1,
        evidence: String = "real output",
    ) = PrereqLedger.Entry(
        capability = capability,
        solvedBy = tool,
        evidence = evidence,
        failed = failed,
        session = session,
        revision = revision,
        sideEffect = sideEffect,
        at = 1_700_000_000_000L,
    )

    private fun plan(): TaskPlanEngine.Plan {
        val made = TaskPlanEngine.create(
            "fix the defect",
            listOf(
                TaskPlanEngine.Step("a", "read the file"),
                TaskPlanEngine.Step("b", "change the file", dependsOn = listOf("a")),
            ),
        )
        val plan = (made as TaskPlanEngine.Outcome.Ok).plan
        return (TaskPlanEngine.start(plan, "a") as TaskPlanEngine.Outcome.Ok).plan
    }

    @Test
    fun aFinishedReadIsProvedAndNotRepeated() {
        val status = TurnRecovery.report(plan(), listOf(entry("read", "read_file")), "s1", 1)
        assertTrue(status.lines.any { it.kind == TurnRecovery.Kind.PROVEN })
        assertEquals(0, status.unknown)
        assertFalse("a read is not something the owner must confirm", status.needsOwner)
    }

    @Test
    fun aWriteWhoseOutcomeIsUnknownNeedsTheOwner() {
        val status = TurnRecovery.report(
            plan(),
            listOf(entry("edit", "write_file", failed = true, sideEffect = true)),
            "s1", 1,
        )
        assertEquals(1, status.unknown)
        assertTrue(status.needsOwner)
        val note = TurnRecovery.note(status)
        assertNotNull(note)
        assertTrue(note!!.contains("may or may not have landed"))
    }

    @Test
    fun evidenceFromAnotherConversationIsNeverReused() {
        val status = TurnRecovery.report(plan(), listOf(entry("read", "read_file", session = "other")), "s1", 1)
        assertTrue(status.lines.any { it.kind == TurnRecovery.Kind.BLOCKED })
        assertFalse(status.lines.any { it.kind == TurnRecovery.Kind.PROVEN })
        assertFalse("a wrong-conversation record is not a reason to interrupt", status.needsOwner)
    }

    @Test
    fun evidenceFromAnOlderPlanIsNotTreatedAsCurrent() {
        val status = TurnRecovery.report(plan(), listOf(entry("read", "read_file", revision = 0)), "s1", 1)
        assertFalse(status.lines.any { it.kind == TurnRecovery.Kind.PROVEN })
    }

    @Test
    fun anUnfinishedStepIsNamedAsUnfinished() {
        val status = TurnRecovery.report(plan(), listOf(entry("read", "read_file")), "s1", 1)
        assertTrue(status.unfinishedSteps.any { it.startsWith("a") })
        assertTrue(status.unfinishedSteps.any { it.startsWith("b") })
        val note = TurnRecovery.note(status)
        assertNotNull(note)
        assertTrue(note!!.contains("never finished"))
    }

    @Test
    fun anEmptyRecordSaysNothingRatherThanClaimingRecovery() {
        val status = TurnRecovery.report(null, emptyList(), "s1", 1)
        assertTrue(status.quiet)
        assertNull(TurnRecovery.note(status))
        assertFalse(status.needsOwner)
    }
}
