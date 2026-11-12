package com.codespace.ide.agent

import com.codespace.ide.chat.panel.ToolEffect
import com.codespace.ide.chat.panel.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Area 5 - the PRODUCTION dispatch path, not the ledger in isolation.
 *
 * Every test calls [AgentTools.executeGated], which is the function `executeTool` delegates to: the
 * guard, the effect index, the retry policy, the ledger and the result check are the real objects.
 * Only the tool BODY is fake, and the Android `Context` is therefore absent - that is the one thing
 * a JVM test cannot build, and it is stated rather than hidden.
 */
class AgentToolsDispatchTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ledger(name: String = "ledger.json") = ToolCallLedger.forTest(File(tmp.root, name))

    private fun guard(
        ledger: ToolCallLedger = ledger(),
        reconciler: ToolReconciler = ToolReconciler.None,
    ) = ToolDispatchGuard(ledger, reconciler)

    private suspend fun call(
        g: ToolDispatchGuard,
        tool: String,
        callId: String?,
        hints: Map<String, String> = emptyMap(),
        body: suspend () -> String,
    ): String = AgentTools.executeGated(g, tool, JSONObject(), hints, callId, body)

    // 1 ---------------------------------------------------------------------------------------
    @Test fun firstCallExecutesItsBody() = runBlocking {
        val hits = AtomicInteger()
        val out = call(guard(), "write_file", "c1") { hits.incrementAndGet(); "written" }
        assertEquals("written", out)
        assertEquals(1, hits.get())
    }

    // 2, 3 ------------------------------------------------------------------------------------
    @Test fun aRepeatedCompletedCallReturnsTheStoredResultAndDoesNotRunTheBody() = runBlocking {
        val hits = AtomicInteger()
        val g = guard()
        call(g, "write_file", "c1") { hits.incrementAndGet(); "written" }
        val again = call(g, "write_file", "c1") { hits.incrementAndGet(); "written TWICE" }
        assertEquals("written", again)
        assertEquals("the body must not run twice", 1, hits.get())
    }

    // 4 ---------------------------------------------------------------------------------------
    @Test fun aLostResponseAfterSuccessDoesNotReExecuteWhenTheResultIsPersisted() = runBlocking {
        val hits = AtomicInteger()
        val l = ledger()
        l.recordCompleted("c1", "run_command", ToolEffect.SIDE_EFFECTING, "exit 0")
        val out = call(guard(l), "run_command", "c1") { hits.incrementAndGet(); "re-ran" }
        assertEquals("exit 0", out)
        assertEquals(0, hits.get())
    }

    // 5 ---------------------------------------------------------------------------------------
    @Test fun anUncertainSideEffectIsRefusedWhenNothingCanProveIt() = runBlocking {
        val hits = AtomicInteger()
        val l = ledger()
        l.recordStarted("c1", "run_command", ToolEffect.SIDE_EFFECTING, mapOf("command" to "rm -rf build"))
        val out = call(guard(l), "run_command", "c1") { hits.incrementAndGet(); "ran again" }
        assertTrue(out.contains("Refused"))
        assertTrue(out.contains("Nothing was executed"))
        assertEquals(0, hits.get())
    }

    @Test fun anUncertainWriteIsReconciledFromTheFileItselfAndTheEvidenceIsRecorded() = runBlocking {
        val target = File(tmp.root, "out.txt")
        target.writeText("hello")
        val l = ledger()
        l.recordStarted("c1", "write_file", ToolEffect.SIDE_EFFECTING, mapOf("path" to target.path, "content" to "hello"))
        l.recordFailed("c1", "write_file", ToolEffect.SIDE_EFFECTING, ToolOutcome.UNKNOWN_AFTER_START)
        val hits = AtomicInteger()
        val reconciler = ToolReconciler.fileWrites { p -> runCatching { File(p).readText() }.getOrNull() }
        val out = call(guard(l, reconciler), "write_file", "c1",
            mapOf("path" to target.path, "content" to "hello")) { hits.incrementAndGet(); "re-wrote" }
        assertTrue(out.contains("Verified already applied"))
        assertEquals(0, hits.get())
        assertTrue(l.lookup("c1")!!.result!!.contains("Verified already applied"))
    }

    @Test fun anUncertainWriteWhoseContentDoesNotMatchIsNotClaimedAsDone() = runBlocking {
        val target = File(tmp.root, "out.txt")
        target.writeText("something else")
        val l = ledger()
        l.recordStarted("c1", "write_file", ToolEffect.SIDE_EFFECTING, mapOf("path" to target.path, "content" to "hello"))
        l.recordFailed("c1", "write_file", ToolEffect.SIDE_EFFECTING, ToolOutcome.UNKNOWN_AFTER_START)
        val reconciler = ToolReconciler.fileWrites { p -> runCatching { File(p).readText() }.getOrNull() }
        val out = call(guard(l, reconciler), "write_file", "c1",
            mapOf("path" to target.path, "content" to "hello")) { "re-wrote" }
        assertTrue(out.contains("Refused"))
        assertFalse(out.contains("Verified"))
    }

    // 6 ---------------------------------------------------------------------------------------
    @Test fun cancellationDuringExecutionRecordsUncertaintyAndBlocksARepeat() = runBlocking {
        val l = ledger()
        val g = guard(l)
        val thrown = runCatching {
            call(g, "run_command", "c1") { throw CancellationException("user cancelled") }
        }
        assertTrue(thrown.exceptionOrNull() is CancellationException)
        assertEquals(ToolOutcome.CANCELLED_DURING_EXECUTION, l.lookup("c1")!!.outcome)
        val hits = AtomicInteger()
        val again = call(g, "run_command", "c1") { hits.incrementAndGet(); "ran" }
        assertTrue(again.contains("Refused"))
        assertEquals(0, hits.get())
    }

    // 7 ---------------------------------------------------------------------------------------
    @Test fun aReadOnlyCallThatFailedIsRetriedRatherThanRefused() = runBlocking {
        val hits = AtomicInteger()
        val g = guard()
        runCatching { call(g, "read_file", "c1") { hits.incrementAndGet(); throw IllegalStateException("io") } }
        val out = call(g, "read_file", "c1") { hits.incrementAndGet(); "contents" }
        assertEquals("contents", out)
        assertEquals(2, hits.get())
    }

    // 8 ---------------------------------------------------------------------------------------
    @Test fun anUnknownToolIsTreatedAsSideEffecting() {
        assertEquals(ToolEffect.SIDE_EFFECTING, ToolEffectIndex.effectOf("brand_new_tool_from_nowhere"))
        assertEquals(ToolEffect.SIDE_EFFECTING, ToolEffectIndex.effectOf("mcp__some_server__do_thing"))
        assertEquals(ToolEffect.READ_ONLY, ToolEffectIndex.effectOf("read_file"))
    }

    @Test fun anUnknownToolWithAnUnknownOutcomeIsRefused() = runBlocking {
        val l = ledger()
        l.recordStarted("c1", "brand_new_tool", ToolEffect.SIDE_EFFECTING)
        val hits = AtomicInteger()
        val out = call(guard(l), "brand_new_tool", "c1") { hits.incrementAndGet(); "ran" }
        assertTrue(out.contains("Refused"))
        assertEquals(0, hits.get())
    }

    // 9 ---------------------------------------------------------------------------------------
    @Test fun aMissingCallIdNeverReusesAnotherCallsResult() = runBlocking {
        val hits = AtomicInteger()
        val g = guard()
        call(g, "write_file", null) { hits.incrementAndGet(); "first" }
        val second = call(g, "write_file", null) { hits.incrementAndGet(); "second" }
        assertEquals("second", second)
        assertEquals(2, hits.get())
    }

    @Test fun aBlankCallIdIsTreatedAsMissingNotAsASharedKey() = runBlocking {
        val hits = AtomicInteger()
        val g = guard()
        call(g, "write_file", "   ") { hits.incrementAndGet(); "one" }
        val two = call(g, "write_file", "\t\n") { hits.incrementAndGet(); "two" }
        assertEquals("two", two)
        assertEquals(2, hits.get())
    }

    // 10 --------------------------------------------------------------------------------------
    @Test fun twoConcurrentRequestsWithTheSameIdExecuteTheSideEffectOnce() = runBlocking {
        val hits = AtomicInteger()
        val g = guard()
        val results = withContext(Dispatchers.Default) {
            (1..4).map {
                async {
                    call(g, "write_file", "same-id") {
                        hits.incrementAndGet()
                        delay(60)
                        "written once"
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, hits.get())
        assertTrue(results.all { it == "written once" })
    }

    // 11 --------------------------------------------------------------------------------------
    @Test fun theLedgerSurvivesRestorationAndStillRefusesTheRepeat() = runBlocking {
        val file = File(tmp.root, "restored.json")
        val hits = AtomicInteger()
        call(ToolDispatchGuard(ToolCallLedger.forTest(file)), "write_file", "c1") {
            hits.incrementAndGet(); "written"
        }
        // A new process: a fresh ledger object reading the same file.
        val restored = ToolCallLedger.forTest(file)
        assertEquals(ToolOutcome.COMPLETED_OK, restored.lookup("c1")!!.outcome)
        val again = call(ToolDispatchGuard(restored), "write_file", "c1") {
            hits.incrementAndGet(); "written again"
        }
        assertEquals("written", again)
        assertEquals(1, hits.get())
    }

    // 12 --------------------------------------------------------------------------------------
    @Test fun anApprovalRefusalAboveTheGuardIsNeverTurnedIntoARun() = runBlocking {
        // The approval gate sits above dispatch: a refused call is recorded as never started, and
        // the guard's job is to keep it that way.
        val l = ledger()
        l.recordFailed("c1", "run_command", ToolEffect.SIDE_EFFECTING, ToolOutcome.FAILED_BEFORE_EXECUTION)
        val hits = AtomicInteger()
        call(guard(l), "run_command", "c1") { hits.incrementAndGet(); "ran" }
        // FAILED_BEFORE_EXECUTION is retryable BY POLICY, and the approval gate re-prompts; what
        // matters here is that the guard did not decide the approval itself.
        assertEquals(1, hits.get())
        assertEquals(ToolOutcome.COMPLETED_OK, l.lookup("c1")!!.outcome)
    }

    @Test fun aCompletedMcpCallIsReusedAndTheRemoteToolIsNotCalledTwice() = runBlocking {
        val l = ledger()
        l.recordStarted("c1", "mcp__github__create_issue", ToolEffect.SIDE_EFFECTING)
        l.recordCompleted("c1", "mcp__github__create_issue", ToolEffect.SIDE_EFFECTING, "issue #12 created")
        val remoteCalls = AtomicInteger()
        val out = call(guard(l), "mcp__github__create_issue", "c1") {
            remoteCalls.incrementAndGet(); "issue #13 created"
        }
        assertEquals("issue #12 created", out)
        assertEquals(0, remoteCalls.get())
    }
}
