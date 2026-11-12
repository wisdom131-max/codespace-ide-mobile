package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project 4.1 / 4.2: goal, dependencies, waves, reuse and refusal to plan a blind write. */
class ToolPlannerTest {

    private val registry = setOf(
        "grep_search", "read_file", "replace_string_in_file", "get_diagnostics", "list_dir",
        "git_status", "git_diff", "git_commit", "web_search", "web_fetch", "run_command",
        "read_project_structure",
    )

    @Test fun anEditTaskPlansReadThenWriteThenVerify() {
        val p = ToolPlanner.plan("rename the field in the parser", registry)
        assertEquals(ToolPlanner.Goal.EDIT, p.goal)
        assertEquals(listOf("search", "read", "write", "verify"), p.steps.map { it.id })
        assertTrue(p.problems().isEmpty())
    }

    @Test fun aWriteNeverSitsInTheSameWaveAsTheReadItNeeds() {
        val waves = ToolPlanner.plan("rename the field", registry).waves()
        val read = waves.indexOfFirst { w -> w.any { it.id == "read" } }
        val write = waves.indexOfFirst { w -> w.any { it.id == "write" } }
        assertTrue(write > read)
    }

    @Test fun independentStepsShareAWaveAndDependentOnesDoNot() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(
                ToolPlanner.Step("a", "git_status"),
                ToolPlanner.Step("b", "list_dir"),
                ToolPlanner.Step("c", "read_file", dependsOn = listOf("a")),
            ),
        )
        val waves = plan.waves()
        assertEquals(setOf("a", "b"), waves.first().map { it.id }.toSet())
        assertEquals(listOf("c"), waves[1].map { it.id })
    }

    @Test fun waveOrderIsDeterministic() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(ToolPlanner.Step("z", "a"), ToolPlanner.Step("a", "b"), ToolPlanner.Step("m", "c")),
        )
        assertEquals(listOf("a", "m", "z"), plan.waves().single().map { it.id })
    }

    @Test fun aMissingDependencyIsReportedRatherThanIgnored() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(ToolPlanner.Step("write", "replace_string_in_file", dependsOn = listOf("nope"), writes = true)),
        )
        assertTrue(plan.problems().any { it.contains("needs missing") })
    }

    @Test fun aCyclicPlanDoesNotLoopForever() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(
                ToolPlanner.Step("a", "x", dependsOn = listOf("b")),
                ToolPlanner.Step("b", "y", dependsOn = listOf("a")),
            ),
        )
        assertTrue(plan.waves().isEmpty())
        assertTrue(plan.problems().any { it.contains("cycle") })
    }

    @Test fun theSameOperationIsPlannedOnce() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(ToolPlanner.Step("a", "grep_search", arguments = "foo"), ToolPlanner.Step("b", "grep_search", arguments = "foo")),
        )
        assertTrue(plan.problems().any { it.contains("duplicate") })
    }

    @Test fun aStepAlreadyCompletedIsDroppedInsteadOfRepeated() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(ToolPlanner.Step("a", "grep_search", arguments = "foo"), ToolPlanner.Step("b", "read_file")),
        )
        val reused = plan.reuse(setOf("grep_search\u0000foo"))
        assertEquals(listOf("b"), reused.steps.map { it.id })
    }

    @Test fun aWriteWithoutAnyPrecedingReadIsFlagged() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(ToolPlanner.Step("w", "replace_string_in_file", writes = true)),
        )
        assertTrue(plan.problems().any { it.contains("writes without a preceding read") })
    }

    @Test fun aWriteNeedsAVerificationStepWhenOneCanBeReadBack() {
        val plan = ToolPlanner.Plan(
            ToolPlanner.Goal.GENERIC,
            listOf(ToolPlanner.Step("w", "replace_string_in_file", writes = true)),
        )
        assertTrue(plan.needsVerification(registry))
        assertFalse(
            ToolPlanner.Plan(
                ToolPlanner.Goal.GENERIC,
                listOf(ToolPlanner.Step("w", "replace_string_in_file", writes = true), ToolPlanner.Step("v", "get_diagnostics")),
            ).needsVerification(registry),
        )
    }

    @Test fun aToolThatIsNotOfferedIsNotPlanned() {
        val plan = ToolPlanner.plan("commit this change", setOf("git_status"))
        assertTrue(plan.steps.none { it.tool == "git_commit" })
        assertTrue(plan.notes.any { it.contains("not planned") })
    }

    @Test fun goalsAreReadOffTheRequest() {
        assertEquals(ToolPlanner.Goal.GIT, ToolPlanner.goalOf("commit the fix on a new branch"))
        assertEquals(ToolPlanner.Goal.RESEARCH, ToolPlanner.goalOf("research sources for this claim"))
        assertEquals(ToolPlanner.Goal.SEARCH, ToolPlanner.goalOf("where is the parser defined"))
        assertEquals(ToolPlanner.Goal.VERIFY, ToolPlanner.goalOf("check that it compiles"))
    }
}
