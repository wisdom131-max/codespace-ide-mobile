package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Area 5 at the real boundary: [ToolDispatchGuard] is what AgentTools.executeTool delegates to, so
 * these tests drive the same code with a counted fake body instead of a shell.
 */
class ToolDispatchGuardTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ledger(name: String = "l.json") = ToolCallLedger.forTest(File(tmp.root, name))

    @Test
    fun aFirstCallRunsTheBody() = runBlocking {
        val calls = AtomicInteger()
        val out = ToolDispatchGuard(ledger()).run("c1", "write_file") {
            calls.incrementAndGet(); "wrote"
        }
        assertEquals("wrote", out)
        assertEquals(1, calls.get())
    }

    @Test
    fun aRepeatedCompletedCallReturnsTheStoredResultAndDoesNotRunTheBody() = runBlocking {
        val calls = AtomicInteger()
        val g = ToolDispatchGuard(ledger())
        val first = g.run("c1", "write_file") { calls.incrementAndGet(); "wrote" }
        val second = g.run("c1", "write_file") { calls.incrementAndGet(); "wrote again" }
        assertEquals("wrote", first)
        assertEquals("wrote", second)
        assertEquals("the body must run exactly once", 1, calls.get())
    }

    @Test
    fun anUncertainWriteIsRefusedAndNothingRuns() = runBlocking {
        val l = ledger()
        l.recordStarted("c1", "run_command", ToolEffect.SIDE_EFFECTING)
        val calls = AtomicInteger()
        val out = ToolDispatchGuard(l).run("c1", "run_command") { calls.incrementAndGet(); "ran" }
        assertEquals(0, calls.get())
        assertTrue(out.contains("Refused"))
    }

    @Test
    fun anUncertainReadIsAllowedToRun() = runBlocking {
        val l = ledger()
        l.recordStarted("c1", "read_file", ToolEffect.READ_ONLY)
        val calls = AtomicInteger()
        ToolDispatchGuard(l).run("c1", "read_file") { calls.incrementAndGet(); "content" }
        assertEquals(1, calls.get())
    }

    @Test
    fun aLostResponseAfterAsyncWriteLeavesItUncertain() = runBlocking {
        // Body ran, then the process died before the completion was recorded.
        val l = ledger()
        l.recordStarted("c1", "write_file", ToolEffect.SIDE_EFFECTING, mapOf("path" to "/x", "content" to "abc"))
        val calls = AtomicInteger()
        val out = ToolDispatchGuard(l).run("c1", "write_file") { calls.incrementAndGet(); "x" }
        assertEquals(0, calls.get())
        assertTrue(out.contains("cannot be proven safe"))
    }

    @Test
    fun aBodyThatThrowsIsRecordedAsIncompleteNotAsSuccess() = runBlocking {
        val l = ledger()
        val g = ToolDispatchGuard(l)
        runCatching { g.run("c1", "write_file") { throw IllegalStateException("boom") } }
        assertEquals(ToolOutcome.COMPLETED_ERROR, l.lookup("c1")!!.outcome)
        // A side-effecting call that errored is refused on the next attempt, not re-run blindly.
        val calls = AtomicInteger()
        val out = g.run("c1", "write_file") { calls.incrementAndGet(); "again" }
        assertEquals(0, calls.get())
        assertTrue(out.contains("Refused"))
    }

    @Test
    fun cancellationDuringExecutionRecordsUncertainty() = runBlocking {
        val l = ledger()
        val g = ToolDispatchGuard(l)
        runCatching { g.run("c1", "run_command") { throw kotlinx.coroutines.CancellationException("stop") } }
        assertEquals(ToolOutcome.CANCELLED_DURING_EXECUTION, l.lookup("c1")!!.outcome)
    }

    @Test
    fun aMalformedIdGetsAFreshOneRatherThanReuse() = runBlocking {
        val calls = AtomicInteger()
        val g = ToolDispatchGuard(ledger())
        g.run("   ", "write_file") { calls.incrementAndGet(); "a" }
        g.run(null, "write_file") { calls.incrementAndGet(); "b" }
        assertEquals("both ran, because neither id identified a prior call", 2, calls.get())
    }

    @Test
    fun twoConcurrentCallsWithOneIdRunTheSideEffectOnce() = runBlocking {
        val calls = AtomicInteger()
        val g = ToolDispatchGuard(ledger())
        val results = listOf(
            async { g.run("dup", "write_file") { Thread.sleep(30); calls.incrementAndGet(); "done" } },
            async { g.run("dup", "write_file") { calls.incrementAndGet(); "done again" } },
        ).awaitAll()
        assertEquals("the write must happen exactly once", 1, calls.get())
        assertTrue(results.all { it == "done" })
    }

    @Test
    fun theLedgerSurvivesRestorationSoADuplicateIsNotExecuted() = runBlocking {
        val f = File(tmp.root, "persist.json")
        ToolDispatchGuard(ToolCallLedger.forTest(f)).run("c1", "write_file") { "written" }
        val calls = AtomicInteger()
        val out = ToolDispatchGuard(ToolCallLedger.forTest(f)).run("c1", "write_file") {
            calls.incrementAndGet(); "again"
        }
        assertEquals(0, calls.get())
        assertEquals("written", out)
    }
}
