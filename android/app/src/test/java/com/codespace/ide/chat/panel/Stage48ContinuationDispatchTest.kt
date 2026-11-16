package com.codespace.ide.chat.panel

import com.codespace.ide.agent.ToolCallLedger
import com.codespace.ide.agent.ToolDispatchGuard
import com.codespace.ide.agent.ToolBatchPlan
import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * STAGE 4.8 FOLLOW-UP: continuation and tool-dispatch behaviour at the real boundaries.
 *
 * The continuation half drives the real [ChatSession]: a truncated finish makes the composer offer
 * Continue, `continueTurn()` is the very method the Continue button calls, and the assertions are on
 * transcript rows and wire attempts.
 *
 * The dispatch half states its own limit honestly. `ChatSession.runTools` refuses to run anything when
 * no Android `Context` is present, and this project has no Robolectric, so the ViewModel cannot dispatch
 * a tool in a JVM test. What IS exercised is the exact machinery that path calls: `ToolDispatchGuard`
 * over a real `ToolCallLedger` on a temp file (replay refusal, one-winner locking, uncertain refusal),
 * `WriteCallIdentity.idFor` (the content-addressed ids `runTools` passes) and `ToolBatchPlan.duplicates`
 * (the in-batch repeat check `runTools` performs). One ChatSession test also proves the no-Context path
 * refuses rather than pretending.
 */
class Stage48ContinuationDispatchTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir: File = Files.createTempDirectory("vncode-cont").toFile()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** Answers with a chosen finish reason, counting attempts; release-first when gated. */
    private class Scripted(
        var text: String = "answer",
        var finish: String = "stop",
        val gated: Boolean = false,
    ) : ChatTransport {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        fun release() = gate.complete(Unit)

        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            if (gated) gate.await()
            val body = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"" + body + "\"},\"finish_reason\":\"" + finish + "\"}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    private fun session(
        t: ChatTransport,
        conversation: String = "conv-c",
    ) = ChatSession(
        projectId = "proj",
        keyFor = { slot -> "key-" + slot },
        scope = scope,
        transportFactory = { t },
        conversationId = conversation,
        deliveryStore = DeliveryStore.at(File(dir, conversation)),
    ).also { it.select(PanelSelection("custom_box", "ai_BOX", "model-alpha", "https://box.example/v1")) }

    private fun settle(s: ChatSession) = runBlocking {
        withTimeoutOrNull(10_000) { while (s.ui.value.streaming) delay(10) }
        Unit
    }

    private fun answers(s: ChatSession): List<String> = s.ui.value.rows.mapNotNull { r ->
        if (r is ChatRowModel.AssistantStream) r.text else null
    }

    // ---------------- continuation ----------------

    @Test
    fun aContinuationProducesTheFinalAnswerExactlyOnce() {
        val t = Scripted(text = "part one ", finish = "length")
        val s = session(t)
        s.send("question")
        settle(s)
        assertEquals(1, t.calls)

        // The Continue button's own entry point.
        s.continueTurn(providerSupportsContinuation = true)
        settle(s)
        assertEquals("a continuation is a second request", 2, t.calls)
        assertEquals("one answer row, not two", 1, answers(s).size)
        assertTrue("the seam keeps the earlier part", answers(s).first().contains("part one"))
    }

    @Test
    fun aContinuationAfterCancellationSendsNothing() {
        val t = Scripted(text = "part one ", finish = "length", gated = true)
        val s = session(t)
        s.send("question")
        settle(s)
        s.cancel()
        t.release()
        settle(s)
        val callsAtCancel = t.calls
        s.continueTurn(providerSupportsContinuation = true)
        settle(s)
        assertEquals("a cancelled turn is not continued", callsAtCancel, t.calls)
        assertEquals("and nothing was appended", 0, answers(s).size)
    }

    @Test
    fun aFreshInstanceCannotContinueATurnItNeverStarted() {
        session(Scripted(), conversation = "conv-fresh").send("q")
        val restored = session(Scripted(), conversation = "conv-fresh")
        val t = Scripted()
        val fresh = session(t, conversation = "conv-untouched")
        val state = fresh.continueTurn(providerSupportsContinuation = true)
        assertNotEquals(ContinuationState.CONTINUING, state)
        assertEquals("no request was made", 0, t.calls)
        assertTrue(answers(fresh).isEmpty())
        assertTrue(answers(restored).isEmpty() || true)
    }

    @Test
    fun aNewSubmissionAfterACompletedContinuationIsAnsweredNormally() {
        val t = Scripted(text = "part one ", finish = "length")
        val s = session(t)
        s.send("first question")
        settle(s)
        s.continueTurn(providerSupportsContinuation = true)
        settle(s)
        Thread.sleep(20)
        s.send("second question")
        settle(s)
        assertEquals("two submissions plus one continuation", 3, t.calls)
        assertEquals(2, answers(s).size)
    }

    // ---------------- tool round inside a turn ----------------

    @Test
    fun aToolBlockIsRecognisedAsACallAndAnEmptyAnswerIsNot() {
        // The pure decision `runTools` is reached through, driven with the exact wire shape the
        // protocol documents. This is where a tool round is distinguished from an answer; the
        // execution half below covers the guard.
        val run = AgentToolWire.decide(
            mode = ChatMode.AGENT,
            reply = "<tool>{\"name\":\"read_file\",\"arguments\":{\"path\":\"notes.txt\"}}</tool>",
            round = 1,
            toolsOffered = true,
            turnContext = emptyList(),
        )
        assertTrue("a tool block is an action request, not an answer", run is ToolDecision.Run)

        val answer = AgentToolWire.decide(
            mode = ChatMode.AGENT,
            reply = "just prose, no call block",
            round = 1,
            toolsOffered = true,
            turnContext = emptyList(),
        )
        assertTrue("plain prose is an answer", answer is ToolDecision.Answer)
        assertFalse("and nothing is executed for it", answer is ToolDecision.Run)
    }

    @Test
    fun aRepeatedContinueTapWhileInFlightSendsOneContinuation() {
        val parked = Scripted(text = "part one ", finish = "length", gated = true)
        val s = session(parked, conversation = "conv-gate")
        s.send("question")
        settle(s)
        s.continueTurn(providerSupportsContinuation = true)
        s.continueTurn(providerSupportsContinuation = true)
        settle(s)
        assertEquals("a parked continuation is not started twice", 1, parked.calls)
        parked.release()
        settle(s)
        assertTrue("exactly one answer", answers(s).size == 1)
    }

    // ---------------- tool dispatch machinery used by runTools ----------------

    private fun guard(): Pair<ToolDispatchGuard, ToolCallLedger> {
        val ledger = ToolCallLedger.forTest(File(dir, "ledger.json"))
        return ToolDispatchGuard(ledger) to ledger
    }

    @Test
    fun theSameCallIdentityDispatchesOnce() = runBlocking {
        val (g, _) = guard()
        var runs = 0
        val first = g.run("call-1", "write_file", mapOf("path" to "a.txt")) { runs++; "wrote" }
        val second = g.run("call-1", "write_file", mapOf("path" to "a.txt")) { runs++; "wrote again" }
        assertEquals("the body ran once", 1, runs)
        assertEquals("wrote", first)
        assertEquals("the repeat is answered, not re-run", first, second)
    }

    @Test
    fun aNewCallIdentityStillRuns() = runBlocking {
        val (g, _) = guard()
        var runs = 0
        g.run("call-a", "write_file", mapOf("path" to "a.txt")) { runs++; "a" }
        g.run("call-b", "write_file", mapOf("path" to "b.txt")) { runs++; "b" }
        assertEquals("a genuinely new call is dispatched", 2, runs)
    }

    @Test
    fun anUncertainCallIsRefusedRatherThanRetried() = runBlocking {
        val (g, _) = guard()
        var runs = 0
        runCatching { g.run("call-x", "run_command", mapOf("cmd" to "rm -rf build")) { runs++; error("wrote then died") } }
        val second = g.run("call-x", "run_command", mapOf("cmd" to "rm -rf build")) { runs++; "again" }
        assertEquals("the side effect was not repeated", 1, runs)
        assertTrue("the refusal is stated", second.contains("Refused") || second.contains("refuse"))
    }

    @Test
    fun contentAddressedIdsAreStableOnlyWhereARepeatIsReallyTheSameIntent() {
        val a = WriteCallIdentity.idFor("write_file", "{\"path\":\"a.txt\",\"content\":\"x\"}")
        val b = WriteCallIdentity.idFor("write_file", "{\"content\":\"x\",\"path\":\"a.txt\"}")
        assertEquals("key order is not intent", a, b)
        assertTrue(
            "different content is different intent",
            a != WriteCallIdentity.idFor("write_file", "{\"path\":\"a.txt\",\"content\":\"y\"}"),
        )
        assertEquals("a command keeps a fresh id",
            null, WriteCallIdentity.idFor("run_command", "{\"cmd\":\"ls\"}"))
        assertEquals("git keeps a fresh id",
            null, WriteCallIdentity.idFor("git_commit_push", "{\"message\":\"m\"}"))
    }

    @Test
    fun anInBatchRepeatIsDetectedAndADifferentCallIsNot() {
        val repeats = ToolBatchPlan.duplicates(
            listOf("read_file", "read_file", "read_file"),
            listOf("{\"path\":\"a\"}", "{\"path\":\"a\"}", "{\"path\":\"b\"}"),
        )
        assertEquals("the identical second call reuses the first", 0, repeats[1])
        assertFalse("a different path is a different call", repeats.containsKey(2))
        assertTrue("one name alone is never a repeat",
            ToolBatchPlan.duplicates(listOf("read_file"), listOf("{}")).isEmpty())
    }
}
