package com.codespace.ide.agent.projectthree

import com.codespace.ide.agent.goal.PlanState
import com.codespace.ide.agent.goal.TaskPlanEngine
import com.codespace.ide.agent.heal.RecoveryPlanner
import com.codespace.ide.agent.reflect.ReflectionEngine
import com.codespace.ide.agent.reflect.ReflectionState
import com.codespace.ide.agent.subagents.DelegationScheduler
import com.codespace.ide.agent.subagents.SubagentRoles
import com.codespace.ide.chat.panel.ToolMeta
import com.codespace.ide.chat.panel.ToolMetaIndex
import com.codespace.ide.chat.panel.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 3.7 (2026-11-15): the five Project Three capabilities on one realistic task.
 *
 * Each unit suite proves its own part. This proves the workflow the brief asks for: understand the
 * objective, plan it, pick roles, delegate inside bounds, recover only where permitted, then reflect
 * on whether it was actually done. The failing variants are the ways an agent talks itself into a
 * success it did not have.
 */
class ProjectThreeIntegrationTest {

    private val session = "project-three-it"

    private fun realMeta(name: String): ToolMeta? =
        ToolRegistry.spec(name)?.let { ToolMetaIndex.metaFor(it) }

    private val permitted: Set<String> = ToolRegistry.names().toSet()

    private fun step(
        id: String,
        title: String,
        dependsOn: List<String> = emptyList(),
    ) = TaskPlanEngine.Step(id = id, title = title, dependsOn = dependsOn)

    private fun create(objective: String, steps: List<TaskPlanEngine.Step>): TaskPlanEngine.Plan =
        (TaskPlanEngine.create(objective, steps) as TaskPlanEngine.Outcome.Ok).plan

    private fun start(plan: TaskPlanEngine.Plan, id: String): TaskPlanEngine.Plan =
        (TaskPlanEngine.start(plan, id) as TaskPlanEngine.Outcome.Ok).plan

    private fun done(plan: TaskPlanEngine.Plan, id: String, evidence: String): TaskPlanEngine.Plan =
        (TaskPlanEngine.complete(plan, id, evidence) as TaskPlanEngine.Outcome.Ok).plan

    private fun fresh(id: String) {
        ReflectionState.claim(id)
        ReflectionState.clear()
        PlanState.clear(id)
        PlanState.claim(id)
    }

    @Test
    fun planDelegateRecoverAndReflectProduceAnHonestCompletion() {
        fresh(session)
        var plan = create(
            "add a share action to the notes list",
            listOf(
                step("s1", "read the notes screen"),
                step("s2", "add the share action", listOf("s1")),
                step("s3", "run the unit tests", listOf("s2")),
            ),
        )

        var board = DelegationScheduler.Board(allowedTools = permitted)
        val enqueued = DelegationScheduler.enqueue(
            board,
            listOf(
                DelegationScheduler.Request("researcher", "check the list screen", "s1", reads = setOf("NotesList.kt")),
                DelegationScheduler.Request("researcher", "check the toolbar", "s1", reads = setOf("NotesToolbar.kt")),
                DelegationScheduler.Request("coder", "add the share action", "s2", writes = setOf("NotesList.kt")),
            ),
            permitted, ::realMeta, now = 100L,
        )
        board = enqueued.board
        assertEquals(3, enqueued.queued.size)

        val reads = DelegationScheduler.wave(board, readySteps = setOf("s1"))
        assertEquals("two independent reads share the lane", 2, reads.size)
        assertTrue(reads.none { it.isWriter })
        reads.forEach { board = DelegationScheduler.start(board, it.id, 110L) }
        reads.forEach { board = DelegationScheduler.complete(board, it.id, "read 120 lines", true, 120L) }

        // A transient read failure is repaired exactly once, and only because it was read-only.
        val recovery = RecoveryPlanner.plan(
            RecoveryPlanner.Failure("read_file", "timed out after 30s", RecoveryPlanner.Category.TIMEOUT),
        )
        assertTrue(RecoveryPlanner.mayExecute(recovery, RecoveryPlanner.Mode.AGENT, 0))
        val verdict = RecoveryPlanner.verdict(recovery.auto()!!, "read 120 lines from NotesList.kt")
        assertTrue(verdict is RecoveryPlanner.Verdict.Recovered)

        val write = DelegationScheduler.wave(board, readySteps = setOf("s1", "s2"))
        assertEquals(1, write.size)
        assertTrue(write.first().isWriter)
        board = DelegationScheduler.start(board, write.first().id, 130L)
        assertTrue(
            "a running write owns the project",
            DelegationScheduler.wave(board, readySteps = setOf("s1", "s2", "s3")).isEmpty(),
        )
        board = DelegationScheduler.complete(board, write.first().id, "wrote 34 lines to NotesList.kt", true, 140L)

        plan = start(plan, "s1")
        plan = done(plan, "s1", "read 120 lines from NotesList.kt")
        plan = start(plan, "s2")
        plan = done(plan, "s2", "wrote 34 lines to NotesList.kt; share action wired")
        plan = start(plan, "s3")
        plan = done(plan, "s3", "unit suite 743 cases, 0 failures")
        PlanState.put(session, plan)

        // The share action is a code change, so the level it must reach is IMPLEMENTED; the suite is
        // the thing that must reach TESTED. Asking for more than was observed is how a criterion lies.
        ReflectionState.addCriterion("share action present", ReflectionEngine.Level.IMPLEMENTED)
        ReflectionState.addCriterion("tests pass", ReflectionEngine.Level.TESTED)
        ReflectionState.addEvidence("share action present", ReflectionEngine.Level.IMPLEMENTED, "34 lines written to NotesList.kt")
        ReflectionState.addEvidence("tests pass", ReflectionEngine.Level.TESTED, "743 cases, 0 failures")
        ReflectionState.setDelegation(board)

        val reflection = ReflectionState.reflect(plan.objective)
        assertTrue(reflection.verdict, reflection.satisfied)
        assertEquals("Objective satisfied", ReflectionEngine.headline(reflection))
        assertEquals(3, reflection.completed.size)
    }

    @Test
    fun everyStepSayingDoneIsNotEnough() {
        val id = session + "-lie"
        fresh(id)
        var plan = create("make the build pass", listOf(step("s1", "fix the error")))
        plan = start(plan, "s1")
        plan = done(plan, "s1", "done")
        PlanState.put(id, plan)
        val reflection = ReflectionState.reflect(plan.objective)
        assertFalse(reflection.satisfied)
        assertTrue(reflection.verdict, reflection.verdict.contains("no evidence"))
        assertTrue(reflection.unsupported.isNotEmpty())
    }

    @Test
    fun aFailedWriteIsNeverRecoveredSilently() {
        val plan = RecoveryPlanner.plan(
            RecoveryPlanner.Failure(
                tool = "write_file",
                error = "timeout",
                category = RecoveryPlanner.Category.TIMEOUT,
                sideEffecting = true,
                outcomeKnown = false,
            ),
        )
        assertFalse(RecoveryPlanner.mayExecute(plan, RecoveryPlanner.Mode.AGENT, 0))
        assertTrue(plan.summary().contains("not applied"))
    }

    @Test
    fun aDuplicateDelegationDoesNotApplyTheWriteTwice() {
        var board = DelegationScheduler.Board(allowedTools = permitted)
        val request = DelegationScheduler.Request("coder", "patch gradle", writes = setOf("app/build.gradle"))
        val first = DelegationScheduler.enqueue(board, listOf(request), permitted, ::realMeta, now = 1L)
        board = DelegationScheduler.start(first.board, first.queued.first().id, 2L)
        board = DelegationScheduler.complete(board, first.queued.first().id, "patched 4 lines", true, 3L)
        val again = DelegationScheduler.enqueue(board, listOf(request), permitted, ::realMeta, now = 4L)
        assertEquals(0, again.queued.size)
        assertEquals(1, again.board.tasks.size)
        assertTrue(again.deduped.first().note.contains("answered from the first"))
    }

    @Test
    fun cancellingTheRunStopsEveryDelegatedTask() {
        var board = DelegationScheduler.Board(allowedTools = permitted)
        val enqueued = DelegationScheduler.enqueue(
            board,
            listOf(
                DelegationScheduler.Request("researcher", "one", reads = setOf("a")),
                DelegationScheduler.Request("coder", "two", writes = setOf("b")),
            ),
            permitted, ::realMeta, now = 1L,
        )
        board = DelegationScheduler.start(enqueued.board, enqueued.queued.first().id, 2L)
        board = DelegationScheduler.cancel(board, "owner pressed stop", 3L)
        assertEquals(0, board.of(DelegationScheduler.State.RUNNING).size)
        assertEquals(0, board.of(DelegationScheduler.State.QUEUED).size)
        assertTrue(DelegationScheduler.report(board).contains("owner pressed stop"))
    }

    @Test
    fun aChildAgentStaysInsideItsParentsScope() {
        var board = DelegationScheduler.Board(allowedTools = permitted)
        val enqueued = DelegationScheduler.enqueue(
            board,
            listOf(DelegationScheduler.Request("security", "review the diff", reads = setOf("NotesList.kt"))),
            permitted, ::realMeta, now = 1L,
        )
        board = DelegationScheduler.start(enqueued.board, "sa1", 2L)
        val parentTools = board.task("sa1")!!.tools
        val child = DelegationScheduler.childBoard(board, "sa1", parentTools)
        assertTrue(child is DelegationScheduler.Child.Ok)
        if (child is DelegationScheduler.Child.Ok) {
            assertTrue(parentTools.containsAll(child.board.allowedTools))
            assertTrue(child.board.budget.depth > board.budget.depth)
        }
        val wider = DelegationScheduler.childBoard(board, "sa1", parentTools + "run_command")
        assertTrue("privilege does not travel down", wider is DelegationScheduler.Child.Refused)
    }

    @Test
    fun everyRoleIsConfinedToTheRegistryAndReadOnlyRolesGetNoWrites() {
        SubagentRoles.Role.entries.forEach { role ->
            when (val result = SubagentRoles.scope(role.id, permitted, ::realMeta)) {
                is SubagentRoles.Result.Ok -> {
                    assertTrue(role.id, permitted.containsAll(result.scope.tools))
                    if (!role.mayWrite) {
                        val destructive = result.scope.tools.filter { realMeta(it)?.destructive == true }
                        assertTrue(role.id + " received writes: " + destructive, destructive.isEmpty())
                    }
                }
                is SubagentRoles.Result.Refused -> Unit
            }
        }
    }

    @Test
    fun aRecordedToolFailureCountsAgainstTheVerdict() {
        val id = session + "-failure"
        fresh(id)
        var plan = create("write the file", listOf(step("s1", "write app/A.kt")))
        plan = start(plan, "s1")
        PlanState.put(id, plan)
        val action = ReflectionState.recordTool("write_file", "Error executing write_file: EACCES")
        assertFalse(action.ok)
        plan = done(plan, "s1", "wrote 12 lines to app/A.kt")
        PlanState.put(id, plan)
        val reflection = ReflectionState.reflect(plan.objective)
        assertTrue(
            reflection.contradictions.toString(),
            reflection.contradictions.any { it.contains("write_file") },
        )
        assertFalse(reflection.satisfied)
    }

    @Test
    fun aRecoveredFailureDoesNotHideTheOriginalError() {
        val plan = RecoveryPlanner.plan(
            RecoveryPlanner.Failure("read_file", "No such file: app/Mian.kt", RecoveryPlanner.Category.MISSING_RESOURCE),
        )
        assertNotNull(plan.auto())
        assertEquals("No such file: app/Mian.kt", plan.original)
        val ledger = RecoveryPlanner.Ledger()
        ledger.record(plan, plan.auto()!!, "read 40 lines from app/Main.kt", true)
        assertEquals("No such file: app/Mian.kt", ledger.all().first().original)
        assertTrue(ledger.lines().first().startsWith("recovered"))
    }
}
