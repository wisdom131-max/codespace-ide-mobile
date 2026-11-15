package com.codespace.ide.agent.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.codespace.ide.agent.goal.TaskPlanEngine.Outcome
import com.codespace.ide.agent.goal.TaskPlanEngine.Status
import com.codespace.ide.agent.goal.TaskPlanEngine.Step

/**
 * PROJECT 3.1: the plan has to be believable before anything executes it.
 *
 * These cases are the ways a planning agent lies to its user: a cycle that never starts, a dependent
 * step that runs on output that never existed, a step marked done because a tool returned, and the
 * retry loop that hides a real blocker for twenty minutes.
 */
class TaskPlanEngineTest {

    private fun step(id: String, deps: List<String> = emptyList(), milestone: String? = null) =
        Step(id = id, title = "do " + id, dependsOn = deps, milestone = milestone)

    private fun plan(vararg steps: Step): TaskPlanEngine.Plan {
        val outcome = TaskPlanEngine.create("objective", steps.toList())
        assertTrue(outcome.toString(), outcome is Outcome.Ok)
        return (outcome as Outcome.Ok).plan
    }

    private fun ok(outcome: Outcome): TaskPlanEngine.Plan {
        assertTrue("expected Ok but was " + outcome, outcome is Outcome.Ok)
        return (outcome as Outcome.Ok).plan
    }

    private fun rejected(outcome: Outcome): String {
        assertTrue("expected Rejected but was " + outcome, outcome is Outcome.Rejected)
        return (outcome as Outcome.Rejected).reason
    }

    @Test
    fun aCycleIsRefusedAndNamed() {
        val reason = rejected(
            TaskPlanEngine.create("objective", listOf(step("a", listOf("b")), step("b", listOf("a"))))
        )
        assertTrue(reason, reason.contains("cycle"))
        assertTrue(reason, reason.contains("a") && reason.contains("b"))
    }

    @Test
    fun aDanglingDependencyIsRefused() {
        val reason = rejected(TaskPlanEngine.create("objective", listOf(step("a", listOf("ghost")))))
        assertTrue(reason, reason.contains("ghost"))
    }

    @Test
    fun duplicateIdsAreRefused() {
        val reason = rejected(TaskPlanEngine.create("objective", listOf(step("a"), step("a"))))
        assertTrue(reason.lowercase(), reason.contains("share an id"))
    }

    @Test
    fun anObjectiveWithNoStepsIsRefused() {
        assertTrue(rejected(TaskPlanEngine.create("  ", listOf(step("a")))).contains("objective"))
        assertTrue(rejected(TaskPlanEngine.create("objective", emptyList())).contains("at least one step"))
    }

    @Test
    fun onlyStepsWithFinishedDependenciesAreReady() {
        val p = plan(step("read"), step("edit", listOf("read")), step("verify", listOf("edit")))
        assertEquals(listOf("read"), p.ready().map { it.id })
        val after = ok(TaskPlanEngine.start(p, "read")).let { ok(TaskPlanEngine.complete(it, "read", "read 4 files")) }
        assertEquals(listOf("edit"), after.ready().map { it.id })
    }

    @Test
    fun startingOutOfOrderIsRefusedWithTheReason() {
        val p = plan(step("read"), step("edit", listOf("read")))
        val reason = rejected(TaskPlanEngine.start(p, "edit"))
        assertTrue(reason, reason.contains("waiting on read"))
    }

    @Test
    fun aStepCannotFinishWithoutEvidence() {
        val p = ok(TaskPlanEngine.start(plan(step("build")), "build"))
        val reason = rejected(TaskPlanEngine.complete(p, "build", "   "))
        assertTrue(reason, reason.contains("evidence"))
    }

    @Test
    fun aFinishedStepCarriesItsEvidence() {
        val p = ok(TaskPlanEngine.start(plan(step("build")), "build"))
        val done = ok(TaskPlanEngine.complete(p, "build", "CI 43745581499 green"))
        assertEquals(Status.DONE, done.step("build")!!.status)
        assertTrue(done.step("build")!!.evidence.contains("43745581499"))
        assertTrue(done.isFinished())
    }

    @Test
    fun aFailureSkipsEveryDependentRatherThanLeavingThemPending() {
        val p = plan(step("read"), step("edit", listOf("read")), step("verify", listOf("edit")), step("unrelated"))
        val failed = ok(TaskPlanEngine.fail(p, "read", "file not found"))
        assertEquals(Status.FAILED, failed.step("read")!!.status)
        assertEquals(Status.SKIPPED, failed.step("edit")!!.status)
        // Transitive: verify depended on edit, which never ran either.
        assertEquals(Status.SKIPPED, failed.step("verify")!!.status)
        assertEquals(Status.PENDING, failed.step("unrelated")!!.status)
        assertFalse(failed.isFinished())
    }

    @Test
    fun aDependentNeverRunsAfterItsInputFailed() {
        val p = plan(step("read"), step("edit", listOf("read")))
        val failed = ok(TaskPlanEngine.fail(p, "read", "no such file"))
        assertTrue("nothing may be ready after its dependency died", failed.ready().isEmpty())
    }

    @Test
    fun theRevisionIsRecordedSoTheUserCanSeeThePlanChanged() {
        val p = plan(step("read"), step("edit", listOf("read")))
        val failed = ok(TaskPlanEngine.fail(p, "read", "permission denied"))
        assertEquals(2, failed.revision)
        assertEquals(1, failed.revisions.size)
        assertTrue(failed.revisions.first().contains("permission denied"))
    }

    @Test
    fun oneRecoveryStepIsAddedWithTheSameDependencies() {
        val p = ok(TaskPlanEngine.start(plan(step("service"), step("app", listOf("service"))), "service"))
        val failed = ok(TaskPlanEngine.fail(p, "service", "port in use", recoveryTitle = "start the service on another port"))
        val retry = failed.step("service.retry")
        assertTrue("the recovery step must exist", retry != null)
        assertEquals(Status.PENDING, retry!!.status)
        assertEquals(emptyList<String>(), retry.dependsOn)
        // app depended on service, which failed, so it stays skipped until the retry succeeds.
        assertEquals(Status.SKIPPED, failed.step("app")!!.status)
    }

    @Test
    fun aStepWithNoRecoveryOfferedIsSimplyFailed() {
        val failed = ok(TaskPlanEngine.fail(plan(step("read")), "read", "no file"))
        assertEquals(1, failed.steps.size)
        assertEquals(Status.FAILED, failed.steps.first().status)
    }

    @Test
    fun retryingForeverIsNotPossible() {
        val p = ok(TaskPlanEngine.start(plan(step("build")), "build"))
        val first = ok(TaskPlanEngine.fail(p, "build", "flaky", recoveryTitle = "rebuild"))
        val retry = ok(TaskPlanEngine.start(first, "build.retry"))
        val second = ok(TaskPlanEngine.fail(retry, "build.retry", "flaky again", recoveryTitle = "rebuild again"))
        // One recovery per failed step: the second failure gets a record, not a loop.
        assertEquals(null, second.step("build.retry.retry"))
        assertEquals(Status.FAILED, second.step("build.retry")!!.status)
    }

    @Test
    fun aFailedStepIsNeverRestartedSilently() {
        val p = ok(TaskPlanEngine.start(plan(step("s0")), "s0"))
        val failed = ok(TaskPlanEngine.fail(p, "s0", "flaky"))
        val reason = rejected(TaskPlanEngine.start(failed, "s0"))
        assertTrue(reason, reason.contains("already failed"))
    }

    @Test
    fun aPlanThatKeepsBeingRevisedStopsAndSaysSo() {
        // Each failure of a *new* step is a revision, so a plan bouncing from one broken step to the
        // next runs out of revisions rather than out of time.
        val many = (0..TaskPlanEngine.MAX_REVISIONS).map { step("s" + it) }
        var p = ok(TaskPlanEngine.create("objective", many))
        // The plan starts at revision 1, so this many failures reach the ceiling exactly.
        repeat(TaskPlanEngine.MAX_REVISIONS - 1) { round ->
            p = ok(TaskPlanEngine.start(p, "s" + round))
            p = ok(TaskPlanEngine.fail(p, "s" + round, "broke " + round))
        }
        p = ok(TaskPlanEngine.start(p, "s" + (TaskPlanEngine.MAX_REVISIONS - 1)))
        val reason = rejected(TaskPlanEngine.fail(p, "s" + (TaskPlanEngine.MAX_REVISIONS - 1), "one too many"))
        assertTrue(reason, reason.contains("revised"))
    }

    @Test
    fun progressCountsEveryStateAndNeverOverclaims() {
        val p = plan(step("a"), step("b", listOf("a")), step("c"))
        val started = ok(TaskPlanEngine.start(p, "a"))
        assertEquals(1, started.progress().running)
        val done = ok(TaskPlanEngine.complete(started, "a", "saw it"))
        val progress = done.progress()
        assertEquals(3, progress.total)
        assertEquals(1, progress.done)
        assertEquals(2, progress.pending)
        assertTrue(progress.summary(), progress.summary().contains("1 of 3"))
        assertFalse(progress.fraction == 1f)
    }

    @Test
    fun milestonesReportTheirOwnCompletion() {
        val p = plan(
            step("a", milestone = "Design"),
            step("b", milestone = "Design"),
            step("c", milestone = "Ship"),
        )
        val started = ok(TaskPlanEngine.start(p, "a"))
        val one = ok(TaskPlanEngine.complete(started, "a", "notes written"))
        val design = one.milestones().first { it.name == "Design" }
        assertEquals(2, design.total)
        assertEquals(1, design.done)
        assertFalse(design.complete)
        val shipped = one.milestones().first { it.name == "Ship" }
        assertFalse(shipped.complete)
    }

    @Test
    fun theVisibleLinesSeparateDoneFromPendingAndDead() {
        val p = plan(step("read"), step("edit", listOf("read")))
        val started = ok(TaskPlanEngine.start(p, "read"))
        val done = ok(TaskPlanEngine.complete(started, "read", "3 files"))
        val lines = done.lines()
        assertTrue(lines.toString(), lines.contains("done: do read"))
        assertTrue(lines.toString(), lines.contains("pending: do edit"))
        val failed = ok(TaskPlanEngine.fail(done, "edit", "no match"))
        assertTrue(failed.lines().toString(), failed.lines().any { it.startsWith("failed: do edit") })
    }

    @Test
    fun aFailureCannotBeInventedForAFinishedStep() {
        val p = ok(TaskPlanEngine.start(plan(step("a")), "a"))
        val done = ok(TaskPlanEngine.complete(p, "a", "saw it"))
        assertTrue(rejected(TaskPlanEngine.fail(done, "a", "pretend")).contains("invented"))
    }

    @Test
    fun aHugePlanIsRefusedWithTheLimitInTheMessage() {
        val many = (0..TaskPlanEngine.MAX_STEPS).map { step("s" + it) }
        val reason = rejected(TaskPlanEngine.create("objective", many))
        assertTrue(reason, reason.contains(TaskPlanEngine.MAX_STEPS.toString()))
    }

    @Test
    fun anEmptyPlanIsNotFinished() {
        val empty = TaskPlanEngine.Plan(objective = "nothing", steps = emptyList())
        assertFalse("no steps is not success", empty.isFinished())
    }
}
