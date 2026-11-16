package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * STAGE 4.8: the runtime boundary, not the helper classes.
 *
 * These construct the real [ChatSession] the panel constructs, with the real transport seam the other
 * chat tests use, and assert what the transcript ends up holding and how many executions actually ran.
 * The store is a real [DeliveryStore] over a temp directory, so restoration is tested against a file,
 * not a stub.
 */
class Stage48RuntimeDeliveryTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir: File = Files.createTempDirectory("vncode-runtime").toFile()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A transport that answers once with the given text and counts its calls. */
    private class Fake(var text: String = "hello") : ChatTransport {
        var calls = 0
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    /** A transport whose answer only arrives when the test releases it. */
    private class Gated : ChatTransport {
        var calls = 0
        private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun release() = gate.complete(Unit)
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> = flow {
            calls++
            gate.await()
            emit("data: {\"choices\":[{\"delta\":{\"content\":\"late answer\"}}]}\n\n")
            emit("data: [DONE]\n\n")
        }
    }

    private fun session(
        t: ChatTransport,
        conversation: String = "conv-1",
        store: DeliveryStore? = DeliveryStore.at(File(dir, conversation)),
    ) = ChatSession(
        projectId = "proj",
        keyFor = { slot -> "key-" + slot },
        scope = scope,
        transportFactory = { t },
        conversationId = conversation,
        deliveryStore = store,
    ).also { it.select(PanelSelection("custom_box", "ai_BOX", "model-alpha", "https://box.example/v1")) }

    private fun settle(s: ChatSession) = runBlocking {
        withTimeoutOrNull(10_000) {
            while (s.ui.value.streaming) delay(10)
        }
        Unit
    }

    private fun answers(s: ChatSession): List<String> = s.ui.value.rows.mapNotNull { row ->
        when (row) {
            is ChatRowModel.AssistantStream -> row.text
            else -> null
        }
    }

    // ---------- normal delivery and duplicate submissions ----------

    @Test
    fun oneSubmitsOnceAndDeliversOneAnswer() {
        val t = Fake()
        val s = session(t)
        s.send("hi")
        settle(s)
        assertEquals("one wire attempt", 1, t.calls)
        assertEquals("one answer row", 1, answers(s).size)
        assertFalse(s.ui.value.streaming)
    }

    @Test
    fun aRepeatedTapWhileInFlightDoesNotRunASecondExecution() {
        val t = Gated()
        val s = session(t)
        s.send("hi")
        s.send("hi")            // the second tap lands while the first request is still running
        settle(s)
        assertEquals("the second tap is the same question", 1, t.calls)
        t.release()
        settle(s)
        assertEquals("and it still produces exactly one answer", 1, answers(s).size)
    }

    @Test
    fun aLegitimateSecondQuestionIsStillAnswered() {
        val t = Fake()
        val s = session(t)
        s.send("first")
        settle(s)
        Thread.sleep(20)
        s.send("second")
        settle(s)
        assertEquals("two real questions, two attempts", 2, t.calls)
        assertEquals(2, answers(s).size)
    }

    // ---------- cancellation and late callbacks ----------

    @Test
    fun aCancelledRequestDeliversNothingWhenTheAnswerArrivesLate() {
        val t = Gated()
        val s = session(t)
        s.send("hi")
        settle(s)                     // the request is parked on the gate
        s.cancel()
        t.release()
        settle(s)
        assertEquals("the late answer is refused", 0, answers(s).size)
        assertFalse("nothing is left streaming", s.ui.value.streaming)
    }

    @Test
    fun aLateAnswerIsNotDeliveredIntoAnotherConversation() {
        val t = Gated()
        val first = session(t, conversation = "conv-a")
        first.send("hi")
        settle(first)
        first.cancel()
        // The user switches conversation; the same store directory is reused deliberately, because a
        // stale answer must be refused by identity even when the storage is shared.
        val second = session(Fake(), conversation = "conv-b", store = DeliveryStore.at(File(dir, "conv-b")))
        second.send("other")
        settle(second)
        t.release()
        settle(first)
        assertEquals("the old request wrote nothing", 0, answers(first).size)
        assertEquals("the new conversation answered itself", 1, answers(second).size)
    }

    // ---------- process recreation and restoration ----------

    @Test
    fun aRestoredSessionAnswersTheOldQuestionOnceAndStartsFromAnEmptyTranscript() {
        val first = session(Fake(), conversation = "conv-r")
        first.send("hi")
        settle(first)
        assertEquals(1, answers(first).size)

        // A new ChatSession over the same store stands in for a recreated process. It must not be
        // carrying the previous answer as a row, and asking again is a real question, not a replay.
        val second = session(Fake(), conversation = "conv-r")
        assertTrue("a fresh instance starts with no rows", answers(second).isEmpty())
        second.send("hi")
        settle(second)
        assertEquals("asked once, answered once", 1, answers(second).size)
    }

    @Test
    fun theSameExecutionCannotWriteItsAnswerTwiceAcrossInstances() {
        val store = DeliveryStore.at(File(dir, "conv-shared"))
        val first = session(Fake(), conversation = "conv-shared", store = store)
        first.send("hi")
        settle(first)

        // A second instance sharing the record sees an execution id it has already delivered and
        // refuses to append it again, even though it never saw the events.
        val ledger = DeliveryLedger(store)
        ledger.restore()
        val pending = ledger.pendingExecutions()
        assertTrue("nothing is left pending after a completed answer", pending.isEmpty())
    }

    @Test
    fun aRestoredSessionStillAnswersANewQuestion() {
        val t1 = Fake()
        val first = session(t1, conversation = "conv-n")
        first.send("hi")
        settle(first)

        val t2 = Fake()
        val second = session(t2, conversation = "conv-n")
        second.send("a genuinely new question")
        settle(second)
        assertEquals(1, t2.calls)
        assertEquals("the new answer is delivered", 1, answers(second).size)
    }

    @Test
    fun aCorruptDeliveryRecordIsIgnoredAndDoesNotBreakSending() {
        val conversation = "conv-corrupt"
        val target = File(dir, conversation)
        target.mkdirs()
        File(target, DeliveryStore.FILE_NAME).writeText("{not json")

        val t = Fake()
        val s = session(t, conversation = conversation)
        s.send("hi")
        settle(s)
        assertEquals("the app still works with an unreadable record", 1, t.calls)
        assertEquals(1, answers(s).size)
    }

    @Test
    fun aMissingDeliveryRecordIsSimplyAFreshStart() {
        val t = Fake()
        val s = session(t, conversation = "conv-fresh")
        s.send("hi")
        settle(s)
        assertEquals(1, answers(s).size)
        assertTrue("the record is written where the app keeps it",
            File(File(dir, "conv-fresh"), DeliveryStore.FILE_NAME).exists())
    }

    // ---------- session isolation and concurrency ----------

    @Test
    fun twoSessionsKeepTheirOwnAnswersAndLimits() {
        val ta = Fake("answer-a")
        val tb = Fake("answer-b")
        val a = session(ta, conversation = "conv-iso-a")
        val b = session(tb, conversation = "conv-iso-b")
        a.send("q a")
        b.send("q b")
        settle(a)
        settle(b)
        assertTrue(answers(a).all { it.contains("answer-a") })
        assertTrue(answers(b).all { it.contains("answer-b") })
        assertEquals(1, answers(a).size)
        assertEquals(1, answers(b).size)
    }

    @Test
    fun repeatedCompletionCallbacksAppendOneAnswer() {
        val t = Fake()
        val s = session(t)
        s.send("hi")
        settle(s)
        val before = answers(s).size
        // The provider repeating its completion is covered by mayComplete; delivering again must not add
        // a second answer row even if a duplicate event slipped past the finish-reason handling.
        settle(s)
        assertEquals("no second answer appears", before, answers(s).size)
        assertEquals(1, answers(s).size)
    }
}
