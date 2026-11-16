package com.codespace.ide.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * STAGE 4.3: orchestration that survives a restart, and never runs the same side effect twice.
 *
 * The tests here are about the two things that can go wrong once waves actually execute: evidence
 * that disappears with the process, and a resumed turn repeating a write because it forgot.
 */
class OrchestrationResumeTest {

    private fun temp(): PrereqStore = PrereqStore(
        File.createTempFile("prereq", ".json").also { it.delete() },
    )

    @Test
    fun theLedgerSurvivesAProcessThatForgetsEverything() {
        val store = temp()
        val first = PrereqLedger.Entry(
            capability = "find",
            solvedBy = "grep_search",
            evidence = "3 matches in Main.kt",
            failed = false,
            session = "s1",
            revision = 2,
        )
        store.save(listOf(first))

        val restored = store.load()
        assertTrue("a clean file loads with no complaint: " + restored.note, restored.note.isBlank())
        assertEquals(1, restored.entries.size)
        assertEquals("find", restored.entries[0].capability)
        assertEquals("s1", restored.entries[0].session)
        assertEquals(2, restored.entries[0].revision)
    }

    @Test
    fun aCorruptFileIsReportedRatherThanGuessedAt() {
        val file = File.createTempFile("prereq", ".json")
        file.writeText("{this is not json")
        val store = PrereqStore(file)
        val loaded = store.load()
        assertTrue("a damaged ledger must say so", loaded.note.isNotBlank())
        assertTrue(loaded.entries.isEmpty())
    }

    @Test
    fun theStoreIsBounded() {
        val store = temp()
        val many = (1..300).map {
            PrereqLedger.Entry("cap" + it, "tool", "ev", failed = false, session = "s", revision = 1)
        }
        store.save(many)
        assertTrue("the ledger keeps a bounded tail", store.load().entries.size <= 200)
    }

    @Test
    fun aCompletedWriteIsNeverResumed() {
        val plan = OrchestrationResume.plan(
            listOf(
                PrereqLedger.Entry("edit", "write_file", "wrote 40 lines", failed = false, session = "s1", revision = 1, sideEffect = true),
            ),
            session = "s1",
            revision = 1,
        )
        assertEquals(0, plan.resumable().size)
        assertEquals("edit", plan.settled().single().capability)
    }

    @Test
    fun anUncertainWriteWaitsForTheOwnerInsteadOfRunningAgain() {
        val plan = OrchestrationResume.plan(
            listOf(
                PrereqLedger.Entry("edit", "write_file", "call started, outcome unknown", failed = true, session = "s1", revision = 1, sideEffect = true),
            ),
            session = "s1",
            revision = 1,
        )
        assertEquals("edit", plan.needsOwner().single().capability)
        assertEquals(0, plan.resumable().size)
    }

    @Test
    fun aCheapReadMayBeRetried() {
        val plan = OrchestrationResume.plan(
            listOf(PrereqLedger.Entry("find", "grep_search", "no matches", failed = true, session = "s1", revision = 1)),
            session = "s1",
            revision = 1,
        )
        assertEquals("find", plan.resumable().single().capability)
    }

    @Test
    fun evidenceFromAnotherConversationIsNotTrustedHere() {
        val plan = OrchestrationResume.plan(
            listOf(PrereqLedger.Entry("find", "grep_search", "2 matches", failed = false, session = "other", revision = 1)),
            session = "s1",
            revision = 1,
        )
        assertEquals("find", plan.blocked().single().capability)
    }

    @Test
    fun evidenceFromAnOlderPlanRevisionIsStale() {
        val plan = OrchestrationResume.plan(
            listOf(PrereqLedger.Entry("find", "grep_search", "2 matches", failed = false, session = "s1", revision = 1)),
            session = "s1",
            revision = 4,
        )
        assertTrue("stale evidence must not count as current", plan.settled().isEmpty())
        assertEquals("find", plan.blocked().single().capability)
    }

    @Test
    fun aResumedRunDoesNotRepeatASettledStep() = runBlocking {
        var ran = 0
        val orchestrator = Orchestrator(maxParallel = 1, execute = { ran++; Orchestrator.StepResult(true, "ok") })
        val report = orchestrator.run(
            waves = listOf(listOf(Orchestrator.Step("1", "edit", "write_file", writes = setOf("a.kt")))),
            prior = listOf(
                PrereqLedger.Entry("edit", "write_file", "wrote 40 lines", failed = false, session = "s1", revision = 1, sideEffect = true),
            ),
            session = "s1",
            revision = 1,
        )
        assertEquals(0, ran)
        assertEquals(listOf("1"), report.skipped)
    }

    @Test
    fun aHeldStepIsSkippedAndNamedNotSilentlyDropped() = runBlocking {
        var ran = 0
        val orchestrator = Orchestrator(maxParallel = 1, execute = { ran++; Orchestrator.StepResult(true, "ok") })
        val report = orchestrator.run(
            waves = listOf(listOf(Orchestrator.Step("1", "edit", "write_file", writes = setOf("a.kt")))),
            prior = listOf(
                PrereqLedger.Entry("edit", "write_file", "outcome unknown", failed = true, session = "s1", revision = 1, sideEffect = true),
            ),
            session = "s1",
            revision = 1,
        )
        assertEquals(0, ran)
        assertEquals(listOf("1"), report.skipped)
        assertTrue(report.summary().contains("skipped"))
    }

    @Test
    fun theSameOperationIsNeverDispatchedTwiceInOneRun() = runBlocking {
        var ran = 0
        val orchestrator = Orchestrator(maxParallel = 1, execute = { ran++; Orchestrator.StepResult(true, "ok") })
        val step = Orchestrator.Step("1", "find", "grep_search")
        val report = orchestrator.run(waves = listOf(listOf(step), listOf(step)))
        assertEquals("the second dispatch of the same id must lose", 1, ran)
        assertEquals(listOf("1"), report.skipped)
    }

    @Test
    fun aFailureDoesNotCancelWorkThatNeverDependedOnIt() = runBlocking {
        val orchestrator = Orchestrator(
            maxParallel = 2,
            execute = { step ->
                if (step.id == "1") Orchestrator.StepResult(false, "Error executing grep_search: no such dir")
                else Orchestrator.StepResult(true, "ok")
            },
        )
        val report = orchestrator.run(
            waves = listOf(
                listOf(Orchestrator.Step("1", "find", "grep_search")),
                listOf(
                    Orchestrator.Step("2", "replace", "grep_replace_all", writes = setOf("a.kt"), dependsOn = setOf("find")),
                    Orchestrator.Step("3", "diff", "diff_files"),
                ),
            ),
        )
        assertTrue("the dependent step must be skipped", report.skipped.contains("2"))
        assertTrue("the independent step must still run", report.results.containsKey("3"))
    }

    @Test
    fun aCancelledRunReportsCancellationAndNoSuccess() = runBlocking {
        var calls = 0
        val orchestrator = Orchestrator(
            maxParallel = 1,
            isCancelled = { true },
            execute = { calls++; Orchestrator.StepResult(true, "ok") },
        )
        val report = orchestrator.run(
            waves = listOf(listOf(Orchestrator.Step("1", "find", "grep_search"))),
        )
        assertEquals(0, calls)
        assertEquals(listOf("1"), report.cancelled)
        assertFalse("a cancelled step is not a success", report.results.containsKey("1"))
    }

    @Test
    fun theTraceSaysWhyItWaited() = runBlocking {
        OrchestrationTrace.clear()
        val lines = ArrayList<String>()
        val orchestrator = Orchestrator(
            maxParallel = 1,
            trace = { lines.add(it) },
            execute = { step ->
                if (step.id == "1") Orchestrator.StepResult(false, "Error executing grep_search: boom")
                else Orchestrator.StepResult(true, "ok")
            },
        )
        orchestrator.run(
            waves = listOf(
                listOf(Orchestrator.Step("1", "find", "grep_search")),
                listOf(Orchestrator.Step("2", "replace", "grep_replace_all", writes = setOf("a.kt"), dependsOn = setOf("find"))),
            ),
        )
        assertTrue(
            "the transcript must say what blocked the dependent step: " + lines,
            lines.any { it.contains("blocked") },
        )
    }
}
