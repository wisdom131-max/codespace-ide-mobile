package com.codespace.ide.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OrchestratorTest {

    private fun step(
        id: String,
        capability: String = id,
        writes: Set<String> = emptySet(),
        dependsOn: Set<String> = emptySet(),
        tool: String = "read_file",
    ) = Orchestrator.Step(id, capability, tool, writes = writes, exclusive = writes.isNotEmpty(), dependsOn = dependsOn)

    private fun orchestrator(
        maxParallel: Int = 2,
        cancelled: () -> Boolean = { false },
        run: suspend (Orchestrator.Step) -> Orchestrator.StepResult,
    ) = Orchestrator(maxParallel = maxParallel, isCancelled = cancelled, execute = run)

    @Test
    fun aChainRunsInDependencyOrder() = runBlocking {
        val order = ArrayList<String>()
        val report = orchestrator { s ->
            synchronized(order) { order.add(s.id) }
            Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("find")), listOf(step("replace", dependsOn = setOf("find")))))
        assertTrue(report.ok)
        assertEquals(listOf("find", "replace"), order)
    }

    @Test
    fun independentReadOnlyWorkRunsTogether() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val report = orchestrator(maxParallel = 3) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val now = running.incrementAndGet()
                peak.set(maxOf(peak.get(), now))
                Thread.sleep(60)
                running.decrementAndGet()
            }
            Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("a"), step("b"), step("c"))))
        assertTrue(report.ok)
        assertTrue("expected real overlap, peak was " + peak.get(), peak.get() > 1)
        assertTrue(peak.get() <= 3)
    }

    @Test
    fun theConcurrencyLimitIsEnforced() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        orchestrator(maxParallel = 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val now = running.incrementAndGet()
                peak.set(maxOf(peak.get(), now))
                Thread.sleep(40)
                running.decrementAndGet()
            }
            Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("a"), step("b"), step("c"), step("d"))))
        assertTrue("never more than 2 at once, saw " + peak.get(), peak.get() <= 2)
    }

    @Test
    fun aWriterRunsAloneEvenBesideReaders() = runBlocking {
        val running = AtomicInteger()
        val peakWhileWriting = AtomicInteger()
        val writerSeen = java.util.concurrent.atomic.AtomicBoolean()
        orchestrator(maxParallel = 3) {
            val now = running.incrementAndGet()
            if (it.writes.isNotEmpty()) {
                writerSeen.set(true)
                peakWhileWriting.set(maxOf(peakWhileWriting.get(), now))
            }
            Thread.sleep(40)
            running.decrementAndGet()
            Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("readA"), step("readB"), step("write", writes = setOf("Main.kt")))))
        assertTrue(writerSeen.get())
        assertEquals("a write must not share a wave with anything else", 1, peakWhileWriting.get())
    }

    @Test
    fun aFailedStepSkipsItsDependentsAndKeepsIndependentWork() = runBlocking {
        val report = orchestrator { s ->
            if (s.id == "find") Orchestrator.StepResult(false, "grep is not available", verified = false)
            else Orchestrator.StepResult(true, "ok")
        }.run(
            listOf(
                listOf(step("find"), step("web")),
                listOf(step("replace", dependsOn = setOf("find"))),
            )
        )
        assertFalse(report.ok)
        assertTrue(report.skipped.contains("replace"))
        assertEquals("ok", report.results["web"]?.output)
    }

    @Test
    fun aFailureStopsTheRunSoLaterWavesDoNotStart() = runBlocking {
        val started = ArrayList<String>()
        val report = orchestrator { s ->
            synchronized(started) { started.add(s.id) }
            if (s.id == "build") Orchestrator.StepResult(false, "compile error", verified = false)
            else Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("build")), listOf(step("test", dependsOn = setOf("build")))))
        assertTrue(report.results.containsKey("build"))
        assertFalse("a dependent must not run after its prerequisite failed", started.contains("test"))
    }

    @Test
    fun cancellationIsReportedAndNeverAsSuccess() = runBlocking {
        var alive = true
        val report = orchestrator(cancelled = { !alive }) { s ->
            if (s.id == "first") alive = false
            Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("first")), listOf(step("second", dependsOn = setOf("first")))))
        assertTrue(report.cancelled.contains("second"))
        assertFalse(report.ok)
        assertNull(report.results["second"])
    }

    @Test
    fun aStepThatThrowsIsAFailureNotAPass() = runBlocking {
        val report = orchestrator { s ->
            if (s.id == "boom") throw IllegalStateException("kaboom")
            Orchestrator.StepResult(true, "ok")
        }.run(listOf(listOf(step("boom"))))
        assertFalse(report.ok)
        assertFalse(report.results.getValue("boom").verified)
    }

    @Test
    fun theDefaultParallelismIsSmallOnAPhone() {
        val parallel = Orchestrator.defaultParallelism()
        assertTrue("parallelism must stay conservative, was " + parallel, parallel in 1..3)
    }

    @Test
    fun theLedgerSurvivesAProcessRestart() {
        val file = File.createTempFile("prereq", ".json")
        file.delete()
        val store = PrereqStore(file)
        PrereqLedger.clear()
        PrereqLedger.attach(store, "conv-1", 2)
        PrereqLedger.record("find", "grep_search", "3 matches in Main.kt", sideEffect = false)
        // Simulate a restart: a new store over the same file, a fresh ledger.
        val restored = PrereqStore(file).load()
        assertTrue(restored.note.isBlank())
        assertEquals(1, restored.entries.size)
        val entry = restored.entries.first()
        assertEquals("find", entry.capability)
        assertEquals("conv-1", entry.session)
        assertEquals(2, entry.revision)
        assertFalse(entry.failed)
        file.delete()
        PrereqLedger.clear()
    }

    @Test
    fun aCorruptRecordIsNotTrusted() {
        val file = File.createTempFile("prereq-bad", ".json")
        file.writeText("{not json at all")
        val loaded = PrereqStore(file).load()
        assertTrue(loaded.entries.isEmpty())
        assertTrue("the loader must say it did not trust the file", loaded.note.isNotBlank())
        file.delete()
    }

    @Test
    fun anotherConversationsEvidenceIsBlocked() {
        val entries = listOf(
            PrereqLedger.Entry("find", "grep_search", "3 matches", session = "conv-old", revision = 1)
        )
        val report = OrchestrationResume.plan(entries, session = "conv-new", revision = 1)
        assertEquals(OrchestrationResume.Disposition.BLOCKED, report.steps.first().disposition)
    }

    @Test
    fun aCompletedWriteIsNotRepeatedOnResume() {
        val entries = listOf(
            PrereqLedger.Entry("edit", "write_file", "wrote 12 lines", session = "c", revision = 1, sideEffect = true)
        )
        val report = OrchestrationResume.plan(entries, "c", 1)
        assertEquals(OrchestrationResume.Disposition.DONE, report.steps.first().disposition)
        assertTrue(report.resumable().isEmpty())
    }

    @Test
    fun anUncertainWriteAsksTheOwnerInsteadOfGuessing() {
        val entries = listOf(
            PrereqLedger.Entry("edit", "write_file", "connection dropped", failed = true, session = "c", revision = 1, sideEffect = true)
        )
        val report = OrchestrationResume.plan(entries, "c", 1)
        assertEquals(OrchestrationResume.Disposition.NEEDS_APPROVAL, report.steps.first().disposition)
        assertEquals(1, report.needsOwner().size)
    }

    @Test
    fun aCheapReadIsSafeToRepeat() {
        val entries = listOf(
            PrereqLedger.Entry("find", "grep_search", "2 matches", session = "c", revision = 1)
        )
        val report = OrchestrationResume.plan(entries, "c", 1)
        assertEquals(OrchestrationResume.Disposition.RETRY_SAFE, report.steps.first().disposition)
    }

    @Test
    fun staleEvidenceFromAnOlderRevisionIsBlocked() {
        val entries = listOf(
            PrereqLedger.Entry("find", "grep_search", "2 matches", session = "c", revision = 1)
        )
        val report = OrchestrationResume.plan(entries, "c", 3)
        assertEquals(OrchestrationResume.Disposition.BLOCKED, report.steps.first().disposition)
    }
}
