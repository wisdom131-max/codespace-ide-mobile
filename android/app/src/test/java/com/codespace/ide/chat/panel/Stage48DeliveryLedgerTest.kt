package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Stage 4.8: the state behind ReplyOwnership's rules, exercised through the three boundaries the chat
 * path now calls - beginSubmission, mayAppendAnswer and cancel - plus its persisted record.
 *
 * Honest scope: these drive the object ChatSession calls, not a live ChatSession. The ViewModel's own
 * wiring compiles and is the only place these boundaries are reached in the app; it is not
 * device-verified.
 */
class Stage48DeliveryLedgerTest {

    private fun store(): Pair<DeliveryStore, File> {
        val dir = Files.createTempDirectory("vncode-delivery").toFile()
        return DeliveryStore.at(dir) to dir
    }

    // ---- A: ownership at the boundaries ----

    @Test
    fun anAnswerIsAppendedOncePerExecution() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        assertTrue(ledger.mayAppendAnswer("e1", "s1"))
        assertFalse("a repeated completion must not append twice", ledger.mayAppendAnswer("e1", "s1"))
        assertEquals(DeliveryLedger.State.COMPLETED, ledger.stateOf("e1"))
    }

    @Test
    fun anAnswerCannotBeAppendedIntoAnotherSession() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        assertFalse(ledger.mayAppendAnswer("e1", "s2"))
        assertTrue("the right session still gets it", ledger.mayAppendAnswer("e1", "s1"))
    }

    @Test
    fun anUnknownExecutionCannotAppend() {
        val ledger = DeliveryLedger()
        assertFalse(ledger.mayAppendAnswer("never-seen", "s1"))
        assertEquals(DeliveryLedger.State.UNKNOWN, ledger.stateOf("never-seen"))
    }

    @Test
    fun aBlankIdentityNeverAppends() {
        val ledger = DeliveryLedger()
        assertFalse(ledger.mayAppendAnswer("", "s1"))
        assertFalse(ledger.beginSubmission("s1", ""))
    }

    // ---- B: duplicate submission ----

    @Test
    fun theSameSubmissionEventStartsOneExecution() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        assertFalse("a re-delivered event is not a second execution", ledger.beginSubmission("s1", "e1"))
        assertNotNull(ledger.currentExecution())
    }

    @Test
    fun aLegitimateNewMessageGetsItsOwnExecution() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        ledger.complete("e1")
        assertTrue("a new question after completion is a new execution", ledger.beginSubmission("s1", "e2"))
        assertTrue(ledger.mayAppendAnswer("e2", "s1"))
    }

    @Test
    fun aCompletedSubmissionIsNotRestartedByRecreation() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        ledger.complete("e1")
        assertFalse("a restored view cannot re-run a finished submission", ledger.beginSubmission("s1", "e1"))
    }

    @Test
    fun aFailedRequestCanBeRetriedUnderANewIdentity() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        ledger.cancel("e1")
        assertTrue(ledger.beginSubmission("s1", "e1-retry"))
        assertTrue(ledger.mayAppendAnswer("e1-retry", "s1"))
    }

    // ---- D: cancellation and stale responses ----

    @Test
    fun aCancelledExecutionCannotDeliver() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        ledger.cancel("e1")
        assertTrue(ledger.isCancelled("e1"))
        assertFalse("a late model response is refused", ledger.mayAppendAnswer("e1", "s1"))
        assertEquals(DeliveryLedger.State.CANCELLED, ledger.stateOf("e1"))
    }

    @Test
    fun aNewExecutionDoesNotInheritTheOldOwnership() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        ledger.cancel("e1")
        assertTrue(ledger.beginSubmission("s1", "e2"))
        assertFalse(ledger.mayAppendAnswer("e1", "s1"))
        assertTrue(ledger.mayAppendAnswer("e2", "s1"))
    }

    @Test
    fun switchingSessionsDoesNotTransferAPendingAnswer() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        // The user switches to s2; the reply for s1 must not land in s2.
        assertFalse(ledger.mayAppendAnswer("e1", "s2"))
        // Switching back is still the same execution's own session.
        assertTrue(ledger.mayAppendAnswer("e1", "s1"))
        assertEquals("nothing is left waiting after it is written", 0, ledger.pendingExecutions().size)
    }

    // ---- E: persistence ----

    @Test
    fun aCompletedAnswerSurvivesRecreationAndIsNotRedelivered() {
        val (store, _) = store()
        val first = DeliveryLedger(store)
        assertTrue(first.beginSubmission("s1", "e1"))
        assertTrue(first.mayAppendAnswer("e1", "s1"))

        val after = DeliveryLedger(store)
        after.restore()
        assertFalse("a restart must not redeliver a finished answer", after.beginSubmission("s1", "e1"))
        assertFalse(after.mayAppendAnswer("e1", "s1"))
        assertEquals(DeliveryLedger.State.COMPLETED, after.stateOf("e1"))
    }

    @Test
    fun aPendingExecutionIsRestorableAndIsNotTreatedAsDelivered() {
        val (store, _) = store()
        val first = DeliveryLedger(store)
        assertTrue(first.beginSubmission("s1", "e1"))

        val after = DeliveryLedger(store)
        after.restore()
        assertEquals(DeliveryLedger.State.PENDING, after.stateOf("e1"))
        assertTrue("unfinished work stays work", after.mayAppendAnswer("e1", "s1"))
    }

    @Test
    fun aCancelledExecutionStaysCancelledAcrossRecreation() {
        val (store, _) = store()
        val first = DeliveryLedger(store)
        assertTrue(first.beginSubmission("s1", "e1"))
        first.cancel("e1")

        val after = DeliveryLedger(store)
        after.restore()
        assertEquals(DeliveryLedger.State.CANCELLED, after.stateOf("e1"))
        assertFalse(after.mayAppendAnswer("e1", "s1"))
        assertFalse(after.beginSubmission("s1", "e1"))
    }

    @Test
    fun aMissingRecordIsSimplyEmpty() {
        val (store, _) = store()
        val loaded = store.load()
        assertTrue(loaded.records.isEmpty())
        assertEquals("", loaded.note)
    }

    @Test
    fun aCorruptRecordIsNotTrustedAndGrantsNoReplay() {
        val (store, dir) = store()
        File(dir, DeliveryStore.FILE_NAME).writeText("not json")
        val loaded = store.load()
        assertTrue(loaded.records.isEmpty())
        assertTrue(loaded.note.isNotBlank())

        val ledger = DeliveryLedger(store)
        ledger.restore()
        assertEquals("nothing is known, and nothing is invented", DeliveryLedger.State.UNKNOWN, ledger.stateOf("e1"))
        assertFalse(ledger.mayAppendAnswer("e1", "s1"))
    }

    @Test
    fun aRestoredAnswerHeldByTheTranscriptIsNotRestoredAgain() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.mayRestoreAnswer("e9", null))
        assertFalse("the transcript already holds this answer", ledger.mayRestoreAnswer("e9", "e9"))
        assertTrue(ledger.mayRestoreAnswer("e9", "e8"))
    }

    @Test
    fun theRecordHoldsNoContentOnlyIdentity() {
        val (store, dir) = store()
        val ledger = DeliveryLedger(store)
        ledger.beginSubmission("s1", "e1")
        val text = File(dir, DeliveryStore.FILE_NAME).readText()
        assertTrue(text.contains("e1"))
        assertFalse("no prompt or answer text is stored", text.contains("SECRET"))
        assertTrue(text.length < 400)
    }

    // ---- F: concurrency ----

    @Test
    fun simultaneousCompletionsAppendExactlyOnce() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = java.util.concurrent.atomic.AtomicInteger()
        repeat(8) {
            pool.submit {
                start.await()
                if (ledger.mayAppendAnswer("e1", "s1")) wins.incrementAndGet()
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals("exactly one winner", 1, wins.get())
    }

    @Test
    fun executionIdentityIsIsolatedPerSession() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "a"))
        assertTrue(ledger.beginSubmission("s2", "b"))
        assertTrue(ledger.mayAppendAnswer("a", "s1"))
        assertTrue(ledger.mayAppendAnswer("b", "s2"))
        assertFalse(ledger.mayAppendAnswer("a", "s2"))
    }

    @Test
    fun aReplacedExecutionStillCannotDeliverOverTheNewerOne() {
        val ledger = DeliveryLedger()
        assertTrue(ledger.beginSubmission("s1", "e1"))
        assertTrue(ledger.beginSubmission("s1", "e2"))
        assertEquals("e2", ledger.currentExecution()!!.executionId)
        assertTrue("the newer execution owns the session", ledger.mayAppendAnswer("e2", "s1"))
        assertFalse("the replaced one is closed", ledger.mayAppendAnswer("e1", "s1"))
    }
}
