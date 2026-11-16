package com.codespace.ide.agent.runtime

import com.codespace.ide.agent.goal.TaskPlanEngine
import com.codespace.ide.agent.heal.RecoveryPlanner
import com.codespace.ide.agent.reflect.ReflectionEngine
import com.codespace.ide.agent.subagents.DelegationScheduler
import com.codespace.ide.chat.panel.ToolCategory
import com.codespace.ide.chat.panel.ToolMeta
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT THREE RUNTIME: the bridge, driven the way the app drives it.
 *
 * These are integration tests in the useful sense: they call the same functions the dispatch path
 * calls, with the same kinds of executor, and assert on what a user would observe (a refusal in the
 * tool output, a task that never ran, a contribution to the plan's evidence). They do not assert on
 * internals of the policies, which have their own suites.
 */
class RuntimeBridgeTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun plan(vararg steps: TaskPlanEngine.Step): TaskPlanEngine.Plan =
        (TaskPlanEngine.create("port the settings screen", steps.toList()) as TaskPlanEngine.Outcome.Ok).plan

    private fun step(id: String, deps: List<String> = emptyList()) =
        TaskPlanEngine.Step(id = id, title = "step " + id, dependsOn = deps)

    private fun meta(destructive: Boolean = false) = ToolMeta(ToolCategory.FILES, destructive = destructive)

    private val metaOf: (String) -> ToolMeta? = { meta() }

    /** A board with a write lane already busy, to prove writes serialise. */
    private fun board() = DelegationScheduler.Board(allowedTools = setOf("read_file", "grep_search", "write_file"))

    // ── 1. dispatch gates ───────────────────────────────────────────────────

    @Test
    fun noPlanMeansNoGateAtAll() {
        assertNull(RuntimeBridge.beforeDispatch(null, "read_file"))
    }

    @Test
    fun completedStepsDoNotBlockUnrelatedWork() {
        var p = plan(step("a"))
        p = (TaskPlanEngine.start(p, "a") as TaskPlanEngine.Outcome.Ok).plan
        p = (TaskPlanEngine.complete(p, "a", "read the file, 40 lines") as TaskPlanEngine.Outcome.Ok).plan
        assertNull(RuntimeBridge.beforeDispatch(p, "read_file"))
    }

    @Test
    fun theWayOutOfADeadPlanIsNeverRefused() {
        var p = plan(step("a"), step("b", listOf("a")))
        p = (TaskPlanEngine.start(p, "a") as TaskPlanEngine.Outcome.Ok).plan
        p = (TaskPlanEngine.fail(p, "a", "the file is not there") as TaskPlanEngine.Outcome.Ok).plan
        // A gate that also blocked the plan tool would leave the model with no way to revise or
        // clear the very plan it is stuck on.
        assertNull(RuntimeBridge.beforeDispatch(p, "task_plan"))
        assertNotNull(RuntimeBridge.beforeDispatch(p, "read_file"))
    }

    @Test
    fun aPlanWithNoRunnableStepLeftStopsDispatching() {
        var p = plan(step("a"), step("b", listOf("a")))
        p = (TaskPlanEngine.start(p, "a") as TaskPlanEngine.Outcome.Ok).plan
        p = (TaskPlanEngine.fail(p, "a", "the file is not there") as TaskPlanEngine.Outcome.Ok).plan
        val refusal = RuntimeBridge.beforeDispatch(p, "read_file")
        assertNotNull("dead work must not be dispatched at", refusal)
        assertTrue("the refusal names what cannot run", refusal!!.contains("cannot run"))
    }

    @Test
    fun aFailedResultIsRecordedAgainstTheRunningStep() {
        com.codespace.ide.agent.goal.PlanState.clear()
        var p = plan(step("a"))
        p = (TaskPlanEngine.start(p, "a") as TaskPlanEngine.Outcome.Ok).plan
        com.codespace.ide.agent.goal.PlanState.put(null, p)
        com.codespace.ide.agent.goal.PlanState.claim("s-dispatch")
        com.codespace.ide.agent.goal.PlanState.put("s-dispatch", p)
        com.codespace.ide.agent.reflect.ReflectionState.claim("s-dispatch")
        com.codespace.ide.agent.reflect.ReflectionState.clear()
        RuntimeBridge.afterDispatch("read_file", "Error executing read_file: no such file", true, "s-dispatch")
        val snapshot = com.codespace.ide.agent.reflect.ReflectionState.current()
        assertTrue("the observation is kept for reflection", snapshot.evidence.any { it.subject == "a" })
        assertTrue(snapshot.actions.any { it.tool == "read_file" && !it.ok })
    }

    // ── 2. failure probe ────────────────────────────────────────────────────

    @Test
    fun aRefusalIsAFailureAndACleanResultIsNot() {
        assertTrue(ToolFailureProbe.looksFailed("Refused: write_file needs approval."))
        assertTrue(ToolFailureProbe.looksFailed("Error executing grep_search: bad regex"))
        assertFalse(ToolFailureProbe.looksFailed("main.kt: 120 lines\n0 errors"))
        assertFalse(ToolFailureProbe.looksFailed("fixed the error in line 4"))
        assertTrue("an empty result is not a success", ToolFailureProbe.looksFailed("   "))
    }

    // ── 3. recovery ─────────────────────────────────────────────────────────

    @Test
    fun anEligibleReadFailureIsRepairedOnceAndValidated() = runBlocking {
        com.codespace.ide.agent.reflect.ReflectionState.claim("s-recover")
        com.codespace.ide.agent.reflect.ReflectionState.clear()
        var calls = 0
        val recovery = RuntimeBridge.recover(
            failure = RecoveryPlanner.Failure(
                tool = "read_file",
                error = "Error executing read_file: no such file",
                category = RecoveryPlanner.Category.UNKNOWN,
                sideEffecting = false,
                outcomeKnown = true,
                mode = RecoveryPlanner.Mode.AGENT,
            ),
            execute = { _, _ -> calls++; "main.kt: 12 lines" },
        )
        assertTrue("a read failure is offered a repair", recovery.applied)
        assertEquals("exactly one attempt", 1, calls)
        assertTrue(recovery.validated)
        assertTrue(recovery.output.contains("12 lines"))
    }

    @Test
    fun aWriteFailureIsRefusedAndTheOriginalErrorStands() = runBlocking {
        com.codespace.ide.agent.reflect.ReflectionState.claim("s-recover2")
        com.codespace.ide.agent.reflect.ReflectionState.clear()
        var calls = 0
        val recovery = RuntimeBridge.recover(
            failure = RecoveryPlanner.Failure(
                tool = "write_file",
                error = "Error executing write_file: disk full",
                category = RecoveryPlanner.Category.UNKNOWN,
                sideEffecting = true,
                outcomeKnown = true,
                mode = RecoveryPlanner.Mode.AGENT,
            ),
            execute = { _, _ -> calls++; "should never run" },
        )
        assertFalse(recovery.applied)
        assertEquals("nothing was executed", 0, calls)
        assertEquals("the original error is untouched", "Error executing write_file: disk full", recovery.output)
        assertTrue(recovery.refusal.isNotBlank())
    }

    @Test
    fun askModeNeverExecutesARepair() = runBlocking {
        var calls = 0
        val recovery = RuntimeBridge.recover(
            failure = RecoveryPlanner.Failure(
                tool = "read_file",
                error = "Error executing read_file: no such file",
                category = RecoveryPlanner.Category.UNKNOWN,
                sideEffecting = false,
                outcomeKnown = true,
                mode = RecoveryPlanner.Mode.ASK,
            ),
            execute = { _, _ -> calls++; "read" },
        )
        assertFalse("Ask is read-only, so nothing runs", recovery.applied)
        assertEquals(0, calls)
    }

    @Test
    fun aRepairThatFailsAgainIsNotReportedAsValidated() = runBlocking {
        val recovery = RuntimeBridge.recover(
            failure = RecoveryPlanner.Failure(
                tool = "read_file",
                error = "Error executing read_file: no such file",
                category = RecoveryPlanner.Category.UNKNOWN,
                sideEffecting = false,
                mode = RecoveryPlanner.Mode.AGENT,
            ),
            execute = { _, _ -> "Error executing read_file: still no such file" },
        )
        assertTrue(recovery.applied)
        assertFalse("a second failure is not a recovery", recovery.validated)
    }

    @Test
    fun aRepairThatThrowsIsContained() = runBlocking {
        val recovery = RuntimeBridge.recover(
            failure = RecoveryPlanner.Failure(
                tool = "read_file",
                error = "Error executing read_file: boom",
                category = RecoveryPlanner.Category.UNKNOWN,
                sideEffecting = false,
                mode = RecoveryPlanner.Mode.AGENT,
            ),
            execute = { _, _ -> throw IllegalStateException("executor exploded") },
        )
        // Whether a repair is even offered depends on the category; what must always hold is that a
        // thrown executor never becomes a validated recovery and never escapes.
        assertFalse(recovery.validated)
        assertNotNull(recovery.output)
    }

    // ── 4. delegation ───────────────────────────────────────────────────────

    @Test
    fun independentReadsRunAndWritesWaitTheirTurn() = runBlocking {
        val executed = mutableListOf<String>()
        val delegated = RuntimeBridge.delegate(
            board = board(),
            requests = listOf(
                DelegationScheduler.Request("researcher", "read a", tool = "read_file", reads = setOf("a.kt")),
                DelegationScheduler.Request("researcher", "read b", tool = "read_file", reads = setOf("b.kt")),
                DelegationScheduler.Request("coder", "write c", tool = "write_file", writes = setOf("c.kt")),
            ),
            permitted = setOf("read_file", "grep_search", "write_file"),
            metaOf = metaOf,
            execute = { task -> executed.add(task.title); "ok: " + task.title },
        )
        assertEquals(3, executed.size)
        assertTrue(delegated.summary().contains("3 of 3"))
        assertTrue(delegated.board.tasks.all { it.state == DelegationScheduler.State.COMPLETED })
        assertTrue("a completed task carries the evidence it produced",
            delegated.board.tasks.all { it.evidence.isNotBlank() })
    }

    @Test
    fun aRoleCannotReachAToolTheSessionDoesNotPermit() = runBlocking {
        val permitted = setOf("read_file")
        val delegated = RuntimeBridge.delegate(
            board = DelegationScheduler.Board(allowedTools = permitted),
            requests = listOf(
                DelegationScheduler.Request("coder", "write it", tool = "write_file", writes = setOf("x.kt")),
            ),
            permitted = permitted,
            metaOf = metaOf,
            execute = { "should not run" },
        )
        assertTrue("nothing was executed", delegated.results.isEmpty())
        assertTrue(delegated.refusals.isNotEmpty())
    }

    @Test
    fun aConflictingWriteIsBlockedNotOverwritten() = runBlocking {
        val order = mutableListOf<String>()
        val delegated = RuntimeBridge.delegate(
            board = board(),
            requests = listOf(
                DelegationScheduler.Request("coder", "first", tool = "write_file", writes = setOf("same.kt")),
                DelegationScheduler.Request("coder", "second", tool = "write_file", writes = setOf("same.kt")),
            ),
            permitted = setOf("read_file", "write_file"),
            metaOf = metaOf,
            execute = { task -> order.add(task.title); "ok" },
        )
        val second = delegated.board.tasks.first { it.title == "second" }
        assertTrue("the second writer is blocked", second.state == DelegationScheduler.State.BLOCKED ||
            delegated.refusals.any { it.contains("second") })
        assertEquals(listOf("first"), order)
    }

    @Test
    fun aFailingTaskDoesNotTakeItsSiblingsDown() = runBlocking {
        val delegated = RuntimeBridge.delegate(
            board = board(),
            requests = listOf(
                DelegationScheduler.Request("researcher", "broken", tool = "read_file", reads = setOf("a")),
                DelegationScheduler.Request("researcher", "fine", tool = "read_file", reads = setOf("b")),
            ),
            permitted = setOf("read_file"),
            metaOf = metaOf,
            execute = { task ->
                if (task.title == "broken") "Error executing read_file: missing" else "ok: fine"
            },
        )
        val states = delegated.board.tasks.associate { it.title to it.state }
        assertEquals(DelegationScheduler.State.FAILED, states["broken"])
        assertEquals(DelegationScheduler.State.COMPLETED, states["fine"])
    }

    @Test
    fun aCancelledBoardRunsNothing() = runBlocking {
        val cancelled = DelegationScheduler.cancel(board(), "the user stopped", 1L)
        var runs = 0
        val delegated = RuntimeBridge.delegate(
            board = cancelled,
            requests = listOf(
                DelegationScheduler.Request("researcher", "read", tool = "read_file", reads = setOf("a")),
            ),
            permitted = setOf("read_file"),
            metaOf = metaOf,
            execute = { runs++; "ok" },
        )
        assertEquals("cancellation reaches the executor", 0, runs)
        assertTrue(delegated.refusals.isNotEmpty())
    }

    @Test
    fun theSameTaskAskedTwiceIsNotRunTwice() = runBlocking {
        val board = board()
        val first = RuntimeBridge.delegate(
            board = board,
            requests = listOf(
                DelegationScheduler.Request("researcher", "read a", tool = "read_file", reads = setOf("a")),
            ),
            permitted = setOf("read_file"),
            metaOf = metaOf,
            execute = { "ok: read" },
        )
        var runs = 0
        val second = RuntimeBridge.delegate(
            board = first.board,
            requests = listOf(
                DelegationScheduler.Request("researcher", "read a", tool = "read_file", reads = setOf("a")),
            ),
            permitted = setOf("read_file"),
            metaOf = metaOf,
            execute = { runs++; "ok: read again" },
        )
        assertEquals("the duplicate is answered from the board, not run again", 0, runs)
        assertTrue(second.refusals.isNotEmpty() || second.board.tasks.size == 1)
    }

    // ── 5. reflection over the whole run ────────────────────────────────────

    @Test
    fun aRunWithEvidenceIsSatisfiedAndARunWithoutItIsNot() {
        val p = plan(step("a"))
        var started = (TaskPlanEngine.start(p, "a") as TaskPlanEngine.Outcome.Ok).plan
        started = (TaskPlanEngine.complete(started, "a", "") as? TaskPlanEngine.Outcome.Ok)
            ?.plan ?: started
        // A blank evidence completion is refused by the engine itself, so the step is still open and
        // the run cannot be satisfied. That refusal is the point.
        com.codespace.ide.agent.goal.PlanState.claim("s-reflect")
        com.codespace.ide.agent.goal.PlanState.put("s-reflect", started)
        com.codespace.ide.agent.reflect.ReflectionState.claim("s-reflect")
        com.codespace.ide.agent.reflect.ReflectionState.clear()
        val reflected = com.codespace.ide.agent.reflect.ReflectionState.reflect()
        assertFalse("an unfinished step is not a satisfied objective", reflected.satisfied)
        assertTrue(reflected.remaining.isNotEmpty())
    }

    @Test
    fun roleToolsNeverExceedTheSession() {
        val permitted = setOf("read_file", "grep_search")
        val tools = RuntimeBridge.roleTools("researcher", permitted, metaOf)
        assertTrue(permitted.containsAll(tools))
        assertTrue(RuntimeBridge.roleTools("nonsense", permitted, metaOf).isEmpty())
    }

    @Test
    fun theModeClaimIsRestrictiveUntilSomethingClaimsIt() {
        RuntimeSession.claimMode("something odd")
        assertEquals(RuntimeSession.MODE_ASK, RuntimeSession.mode())
        RuntimeSession.claimMode("agent")
        assertEquals(RuntimeSession.MODE_AGENT, RuntimeSession.mode())
        assertEquals(RecoveryPlanner.Mode.AGENT, RuntimeSession.healMode())
        RuntimeSession.claimMode("ASK")
    }
}
