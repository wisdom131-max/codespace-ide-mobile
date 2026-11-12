package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Area 5 reconciliation: proof by reading, never by re-running. */
class ToolReconcilerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun entry(tool: String, args: Map<String, String>) =
        ToolCallLedger.Entry("c1", tool, ToolEffect.SIDE_EFFECTING, ToolOutcome.UNKNOWN_AFTER_START,
            null, 0L, args)

    @Test
    fun aWriteWhoseTargetAlreadyHoldsTheContentIsProvenApplied() {
        val r = ToolReconciler.fileWrites { "hello" }
        val out = r.reconcile("write_file", entry("write_file", mapOf("path" to "/a", "content" to "hello")))
        assertTrue(out!!.contains("Verified already applied"))
    }

    @Test
    fun aWriteWhoseTargetDiffersIsNotClaimedAsApplied() {
        val r = ToolReconciler.fileWrites { "something else" }
        assertNull(r.reconcile("write_file", entry("write_file", mapOf("path" to "/a", "content" to "hello"))))
    }

    @Test
    fun anUnreadableTargetProvesNothing() {
        val r = ToolReconciler.fileWrites { null }
        assertNull(r.reconcile("write_file", entry("write_file", mapOf("path" to "/a", "content" to "x"))))
    }

    @Test
    fun aNonWriteToolIsNeverReconciled() {
        val r = ToolReconciler.fileWrites { "anything" }
        assertNull(r.reconcile("run_command", entry("run_command", mapOf("command" to "rm -rf /"))))
    }

    @Test
    fun theDefaultReconcilerProvesNothingSoRefusalStands() {
        assertNull(ToolReconciler.None.reconcile("write_file", entry("write_file", mapOf("path" to "/a"))))
    }

    @Test
    fun reconciliationRecordsACompletedCallAndStopsTheRetry() = runBlocking {
        val f = File(tmp.root, "l.json")
        val ledger = ToolCallLedger.forTest(f)
        ledger.recordStarted("c1", "write_file", ToolEffect.SIDE_EFFECTING, mapOf("path" to "/a", "content" to "hello"))
        val calls = AtomicInteger()
        val guard = ToolDispatchGuard(ledger, ToolReconciler.fileWrites { "hello" })
        val out = guard.run("c1", "write_file") { calls.incrementAndGet(); "wrote" }
        assertEquals("reconciliation must not execute the body", 0, calls.get())
        assertTrue(out.contains("Verified already applied"))
        assertEquals(ToolOutcome.COMPLETED_OK, ledger.lookup("c1")!!.outcome)
        // And a later duplicate reuses the evidence instead of running.
        val again = guard.run("c1", "write_file") { calls.incrementAndGet(); "wrote" }
        assertEquals(0, calls.get())
        assertTrue(again.contains("Verified already applied"))
    }
}
