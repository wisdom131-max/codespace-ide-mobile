package com.codespace.ide.agent.reflect

import com.codespace.ide.agent.goal.TaskPlanEngine
import com.codespace.ide.agent.subagents.DelegationScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 3.6: reflection must be able to say no.
 *
 * Brahma's reflection takes a `verified` boolean as an argument, so it can only agree. These cases
 * give ours reasons to disagree: a completion with no evidence, evidence that is the word "done",
 * an executor vouching for itself, a step done before its dependency, a failed tool under a done
 * step, disagreeing subagents, and a criterion met only in a weaker sense than it asked for.
 */
class ReflectionEngineTest {

    private fun plan(vararg steps: TaskPlanEngine.Step) =
        TaskPlanEngine.Plan(objective = "port the settings screen", steps = steps.toList())

    private fun step(
        id: String,
        title: String = "step " + id,
        status: TaskPlanEngine.Status = TaskPlanEngine.Status.PENDING,
        evidence: String = "",
        dependsOn: List<String> = emptyList(),
        note: String = "",
    ) = TaskPlanEngine.Step(id = id, title = title, dependsOn = dependsOn, status = status, evidence = evidence, note = note)

    @Test
    fun aFullyEvidencedPlanSatisfiesTheObjective() {
        val p = plan(
            step("s1", "read MainScreen", TaskPlanEngine.Status.DONE, "read 214 lines from app/MainScreen.kt"),
            step("s2", "port to Compose", TaskPlanEngine.Status.DONE, "wrote 380 lines; gradle compileDevDebugKotlin passed", listOf("s1")),
        )
        val reflection = ReflectionEngine.reflect(
            objective = "port the settings screen",
            plan = p,
            actions = listOf(
                ReflectionEngine.Action("read_file", "s1", true, "214 lines"),
                ReflectionEngine.Action("write_file", "s2", true, "380 lines written"),
            ),
            evidence = listOf(
                ReflectionEngine.Evidence("Settings ports cleanly", ReflectionEngine.Level.TESTED, "gradle test suite 743 cases, 0 failures", "s2"),
            ),
        )
        assertTrue(reflection.verdict, reflection.satisfied)
        assertTrue(reflection.verdict, reflection.verdict.startsWith("Satisfied"))
        assertEquals(2, reflection.completed.size)
        assertEquals("Objective satisfied", ReflectionEngine.headline(reflection))
    }

    @Test
    fun aCompletionClaimWithNoEvidenceIsUnsupported() {
        val p = plan(step("s1", "port the screen", TaskPlanEngine.Status.DONE, ""))
        val reflection = ReflectionEngine.reflect("port", p, actions = listOf(ReflectionEngine.Action("write_file", "s1", true, "ok")), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertEquals(1, reflection.unsupported.size)
        assertTrue(reflection.verdict, reflection.verdict.contains("no evidence"))
    }

    @Test
    fun theWordDoneIsNotEvidence() {
        val p = plan(step("s1", "port the screen", TaskPlanEngine.Status.DONE, "done"))
        val reflection = ReflectionEngine.reflect("port", p, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertTrue(reflection.unsupported.toString(), reflection.unsupported.isNotEmpty())
    }

    @Test
    fun returningWithoutAnExceptionIsNotSuccess() {
        val p = plan(step("s1", "run the build", TaskPlanEngine.Status.DONE, "no exception thrown"))
        val reflection = ReflectionEngine.reflect("build it", p, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertTrue(reflection.unsupported.toString(), reflection.unsupported.isNotEmpty())
    }

    @Test
    fun aSubagentVouchingForItselfIsNotEvidence() {
        val p = plan(step("s1", "review the diff", TaskPlanEngine.Status.DONE, "subagent reported it succeeded"))
        val reflection = ReflectionEngine.reflect("review", p, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertTrue(reflection.unsupported.first(), reflection.unsupported.first().contains("own claim"))
    }

    @Test
    fun aStepDoneBeforeItsDependencyIsAContradiction() {
        val p = plan(
            step("s1", "design", TaskPlanEngine.Status.PENDING),
            step("s2", "implement", TaskPlanEngine.Status.DONE, "wrote 40 lines", listOf("s1")),
        )
        val reflection = ReflectionEngine.reflect("do it", p, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertTrue(reflection.contradictions.first(), reflection.contradictions.first().contains("depends on s1"))
    }

    @Test
    fun aFailedToolUnderADoneStepIsAContradiction() {
        val p = plan(step("s1", "write the file", TaskPlanEngine.Status.DONE, "wrote app/A.kt"))
        val reflection = ReflectionEngine.reflect(
            "write it", p,
            actions = listOf(ReflectionEngine.Action("write_file", "s1", false, "EACCES: permission denied")),
            evidence = emptyList(),
        )
        assertFalse(reflection.satisfied)
        assertTrue(reflection.contradictions.toString(), reflection.contradictions.any { it.contains("permission denied") })
    }

    @Test
    fun failedAndSkippedWorkStaysVisible() {
        val p = plan(
            step("s1", "read", TaskPlanEngine.Status.FAILED, note = "path missing"),
            step("s2", "write", TaskPlanEngine.Status.SKIPPED),
            step("s3", "verify", TaskPlanEngine.Status.DONE, "tests ran: 12 cases passed"),
        )
        val reflection = ReflectionEngine.reflect("do it", p, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertEquals(1, reflection.failed.size)
        assertEquals(1, reflection.skipped.size)
        assertTrue(reflection.failed.first().contains("path missing"))
        assertTrue(reflection.verdict.contains("failed or were skipped"))
    }

    @Test
    fun anAcceptanceCriterionMustBeMetAtTheLevelItAsksFor() {
        val p = plan(step("s1", "ship it", TaskPlanEngine.Status.DONE, "wrote the code"))
        val evidence = listOf(
            ReflectionEngine.Evidence("works on the phone", ReflectionEngine.Level.IMPLEMENTED, "wrote 90 lines"),
        )
        val reflection = ReflectionEngine.reflect(
            "ship it", p, actions = emptyList(), evidence = evidence,
            criteria = listOf(ReflectionEngine.Criterion("works on the phone", ReflectionEngine.Level.DEVICE_VERIFIED)),
        )
        assertFalse("CI is not a device", reflection.satisfied)
        assertTrue(reflection.remaining.toString(), reflection.remaining.any { it.contains("criterion not met") })
    }

    @Test
    fun aCriterionMetAtItsLevelPasses() {
        val p = plan(step("s1", "ship it", TaskPlanEngine.Status.DONE, "wrote the code"))
        val reflection = ReflectionEngine.reflect(
            "ship it", p, actions = emptyList(),
            evidence = listOf(
                ReflectionEngine.Evidence("compiles", ReflectionEngine.Level.TESTED, "compileDevDebugKotlin BUILD SUCCESSFUL"),
                ReflectionEngine.Evidence("ci green", ReflectionEngine.Level.CI_GREEN, "run 43758102836 success"),
            ),
            criteria = listOf(
                ReflectionEngine.Criterion("compiles", ReflectionEngine.Level.TESTED),
                ReflectionEngine.Criterion("ci green", ReflectionEngine.Level.CI_GREEN),
            ),
        )
        assertTrue(reflection.verdict, reflection.satisfied)
    }

    @Test
    fun aStrongerLevelThanAskedIsFine() {
        val p = plan(step("s1", "ship", TaskPlanEngine.Status.DONE, "ran on the device"))
        val reflection = ReflectionEngine.reflect(
            "ship", p, actions = emptyList(),
            evidence = listOf(ReflectionEngine.Evidence("compiles", ReflectionEngine.Level.DEVICE_VERIFIED, "ran on TECNO KL4, screen rendered")),
            criteria = listOf(ReflectionEngine.Criterion("compiles", ReflectionEngine.Level.TESTED)),
        )
        assertTrue(reflection.verdict, reflection.satisfied)
    }

    @Test
    fun disagreeingSubagentsAreReportedAsAConflict() {
        val p = plan(step("s1", "check the api", TaskPlanEngine.Status.PENDING))
        val board = DelegationScheduler.Board(
            tasks = listOf(
                DelegationScheduler.Task(
                    id = "sa1", roleId = "researcher", title = "check A", planStepId = "s1",
                    reads = setOf("api.md"), writes = emptySet(), dedupeKey = "a", tools = setOf("read_file"),
                    state = DelegationScheduler.State.COMPLETED, evidence = "returns 200", verified = true,
                ),
                DelegationScheduler.Task(
                    id = "sa2", roleId = "researcher", title = "check B", planStepId = "s1",
                    reads = setOf("api.md"), writes = emptySet(), dedupeKey = "b", tools = setOf("read_file"),
                    state = DelegationScheduler.State.FAILED, note = "endpoint 404",
                ),
            ),
        )
        val reflection = ReflectionEngine.reflect("check it", p, actions = emptyList(), evidence = emptyList(), delegation = board)
        assertFalse(reflection.satisfied)
        assertTrue(reflection.contradictions.toString(), reflection.contradictions.any { it.contains("disagree") })
    }

    @Test
    fun anUnverifiedDelegatedResultIsNotCounted() {
        val p = plan(step("s1", "research", TaskPlanEngine.Status.DONE, "found 4 sources"))
        val board = DelegationScheduler.Board(
            tasks = listOf(
                DelegationScheduler.Task(
                    id = "sa1", roleId = "researcher", title = "look it up", planStepId = "s1",
                    reads = emptySet(), writes = emptySet(), dedupeKey = "k", tools = setOf("web_search"),
                    state = DelegationScheduler.State.COMPLETED, evidence = "4 sources", verified = false,
                    note = "stale: the project changed while this ran",
                ),
            ),
        )
        val reflection = ReflectionEngine.reflect("research", p, actions = emptyList(), evidence = emptyList(), delegation = board)
        assertTrue(reflection.unsupported.toString(), reflection.unsupported.any { it.contains("unverified") })
    }

    @Test
    fun aBlockedDelegationIsSomethingTheUserMustDecide() {
        val p = plan(step("s1", "edit", TaskPlanEngine.Status.PENDING))
        val board = DelegationScheduler.Board(
            tasks = listOf(
                DelegationScheduler.Task(
                    id = "sa1", roleId = "coder", title = "edit Main.kt", planStepId = "s1",
                    reads = emptySet(), writes = setOf("app/Main.kt"), dedupeKey = "c", tools = setOf("write_file"),
                    state = DelegationScheduler.State.BLOCKED,
                    note = "writes app/Main.kt which 'sa2' already holds",
                ),
            ),
        )
        val reflection = ReflectionEngine.reflect("edit", p, actions = emptyList(), evidence = emptyList(), delegation = board)
        assertTrue(reflection.needsApproval.toString(), reflection.needsApproval.isNotEmpty())
        assertFalse(reflection.satisfied)
    }

    @Test
    fun aRecoveryThatDidNotHoldCountsAgainstTheVerdict() {
        val p = plan(step("s1", "write", TaskPlanEngine.Status.DONE, "wrote 12 lines to app/A.kt"))
        val reflection = ReflectionEngine.reflect(
            "write it", p, actions = emptyList(), evidence = emptyList(),
            recoveryLines = listOf("not recovered: write_file (timeout) via retry_read - still fails"),
        )
        assertFalse(reflection.satisfied)
        assertTrue(reflection.contradictions.toString(), reflection.contradictions.any { it.contains("recovery did not hold") })
    }

    @Test
    fun withNoPlanThereIsNoCompletionClaim() {
        val reflection = ReflectionEngine.reflect("do something", null, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertTrue(reflection.verdict.contains("No plan"))
        assertEquals("Objective not started", ReflectionEngine.headline(reflection))
    }

    @Test
    fun anOpenStepKeepsTheObjectiveOpen() {
        val p = plan(
            step("s1", "read", TaskPlanEngine.Status.DONE, "read 90 lines from app/A.kt"),
            step("s2", "write", TaskPlanEngine.Status.PENDING),
        )
        val reflection = ReflectionEngine.reflect("do it", p, actions = emptyList(), evidence = emptyList())
        assertFalse(reflection.satisfied)
        assertTrue(reflection.remaining.toString(), reflection.remaining.any { it.contains("is ready but not done") })
        assertTrue(ReflectionEngine.headline(reflection).startsWith("Objective partly done"))
    }

    @Test
    fun reflectionNeverInventsAStepThatWasNotPlanned() {
        val p = plan(step("s1", "read", TaskPlanEngine.Status.DONE, "read 90 lines"))
        val reflection = ReflectionEngine.reflect("do it", p, actions = emptyList(), evidence = emptyList())
        assertEquals(1, reflection.planned.size)
        assertTrue(reflection.planned.first().startsWith("s1"))
    }

    @Test
    fun theReportKeepsEverythingInOnePlace() {
        val p = plan(
            step("s1", "read", TaskPlanEngine.Status.DONE, "read 90 lines from app/A.kt"),
            step("s2", "write", TaskPlanEngine.Status.FAILED, note = "EACCES"),
        )
        val lines = ReflectionEngine.reflect("do it", p, actions = emptyList(), evidence = emptyList()).lines()
        assertTrue(lines.first().startsWith("objective:"))
        assertTrue(lines.toString(), lines.any { it.startsWith("failed:") })
        assertTrue(lines.toString(), lines.any { it.startsWith("verdict:") })
    }
}
