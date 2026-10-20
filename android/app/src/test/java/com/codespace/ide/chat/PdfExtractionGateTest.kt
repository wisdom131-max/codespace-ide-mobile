package com.codespace.ide.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * U4 advisor item-2: STUCK-PARSE CONTAINMENT tests. The fake engine below
 * NEVER RETURNS on demand (latch-blocked) — the exact hostile scenario: a
 * pdfbox parse stuck inside one page that cannot be cancelled. Pinned here:
 *
 *  1. the waiter gets TimedOut PROMPTLY (bounded wait, not whenever the
 *     parse dies);
 *  2. a new extraction while the lane is stuck gets Busy IMMEDIATELY (no
 *     queueing behind the zombie, no new CPU lanes);
 *  3. the lane SELF-HEALS: once the stuck job finally completes, the flag
 *     clears and the next extraction runs normally;
 *  4. the interrupt is best-effort (see PdfExtractionGate's HONEST
 *     LIMITATION) — the containment is the one-thread lane + Busy refusal,
 *     not true cancellation, and that is stated, not hidden.
 */
class PdfExtractionGateTest {

    @Before fun setUp() { PdfExtractionGate.resetForTest() }
    @After fun tearDown() { PdfExtractionGate.resetForTest() }

    private val file = File("fake.pdf")
    private fun doc(text: String) = AttachmentIntake.ExtractedDoc(text = text)

    @Test fun fastWork_returnsDoneWithTheDoc() {
        val r = PdfExtractionGate.extract(file, timeoutMs = 2_000) { doc("hello pdf") }
        assertTrue(r is PdfExtractionGate.GateResult.Done)
        assertEquals("hello pdf", (r as PdfExtractionGate.GateResult.Done).doc.text)
        assertFalse(PdfExtractionGate.isBusyForTest())
    }

    /**
     * The REAL hostile case: a pdfbox page parse is a CPU loop that IGNORES
     * Thread.interrupted() — the first CI run proved a latch-blocked fake
     * engine gets killed by the gate's interrupt (CountDownLatch.await IS
     * interruptible), so the zombie here must swallow interrupts like
     * pdfbox does. That interruptible variant gets its own test below.
     */
    @Test fun neverReturningEngine_waiterTimesOutPromptly_thenBusy_thenSelfHeals() {
        // local vars cannot take @Volatile (CI red 39450920239) — the zombie
        // reads cross-thread, so the release flag is an AtomicBoolean
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val stuck: (File) -> AttachmentIntake.ExtractedDoc = {
            while (!released.get()) {
                try { Thread.sleep(20) } catch (_: InterruptedException) {
                    // pdfbox-like: the interrupt is swallowed — the CPU burn
                    // continues until the parse returns on its own
                }
            }
            doc("late")
        }
        val t0 = System.currentTimeMillis()
        val r1 = PdfExtractionGate.extract(file, timeoutMs = 300, work = stuck)
        val waited = System.currentTimeMillis() - t0
        // 1. prompt honest timeout (bounded wait + slack for scheduling)
        assertTrue("expected TimedOut, got $r1", r1 is PdfExtractionGate.GateResult.TimedOut)
        assertEquals(300L, (r1 as PdfExtractionGate.GateResult.TimedOut).waitedMs)
        assertTrue("wait should be bounded (waited ${waited}ms)", waited < 2_000)
        // the abandoned-work flag is up
        assertTrue(PdfExtractionGate.isBusyForTest())

        // 2. new extraction while stuck -> Busy IMMEDIATELY
        val t1 = System.currentTimeMillis()
        val r2 = PdfExtractionGate.extract(file, timeoutMs = 2_000) { doc("should not run") }
        val busyWait = System.currentTimeMillis() - t1
        assertTrue("expected Busy, got $r2", r2 is PdfExtractionGate.GateResult.Busy)
        assertTrue("Busy must be immediate (waited ${busyWait}ms)", busyWait < 150)

        // 3. release the zombie -> the stuck job completes -> flag self-clears
        released.set(true)
        var healed = false
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!PdfExtractionGate.isBusyForTest()) { healed = true; break }
            Thread.sleep(50)
        }
        assertTrue("lane must self-heal after the stuck job completes", healed)

        // ...and the next extraction runs normally again
        val r3 = PdfExtractionGate.extract(file, timeoutMs = 2_000) { doc("recovered") }
        assertEquals("recovered", (r3 as PdfExtractionGate.GateResult.Done).doc.text)
    }

    /**
     * CI-taught (2026-10-20 red): CountDownLatch.await-blocked work IS
     * interruptible — the gate's future.cancel(true) ends it without any
     * manual release, the lane heals on its own. pdfbox is NOT in this
     * class (see the zombie test); this pins the rescue path for engines
     * that are.
     */
    @Test fun interruptibleStuckWork_interruptRescuesIt_laneSelfHeals() {
        val latch = CountDownLatch(1)
        val r = PdfExtractionGate.extract(file, timeoutMs = 300) {
            latch.await()
            doc("late")
        }
        assertTrue("expected TimedOut, got $r", r is PdfExtractionGate.GateResult.TimedOut)
        var healed = false
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!PdfExtractionGate.isBusyForTest()) { healed = true; break }
            Thread.sleep(25)
        }
        assertTrue("interrupt must rescue interruptible stuck work", healed)
        val r2 = PdfExtractionGate.extract(file, timeoutMs = 2_000) { doc("next") }
        assertEquals("next", (r2 as PdfExtractionGate.GateResult.Done).doc.text)
    }

    @Test fun twoSequentialJobs_neverBothBusy() {
        // normal back-to-back jobs: the flag logic must not produce a false
        // Busy for ordinary fast work
        repeat(3) { i ->
            val r = PdfExtractionGate.extract(file, timeoutMs = 2_000) { doc("job $i") }
            assertEquals("job $i", (r as PdfExtractionGate.GateResult.Done).doc.text)
            assertFalse(PdfExtractionGate.isBusyForTest())
        }
    }

    @Test fun throwingWork_becomesAnHonestFailedDoc_neverAStuckLane() {
        val r = PdfExtractionGate.extract(file, timeoutMs = 2_000) {
            throw IllegalStateException("engine blew up")
        }
        // the gate converts any escaped work failure into an honest refusal
        // doc (Done wrapper) — and the lane is free for the next job
        val doc = (r as PdfExtractionGate.GateResult.Done).doc
        assertTrue(doc.refusal.orEmpty().contains("engine blew up"))
        assertFalse(PdfExtractionGate.isBusyForTest())
        val r2 = PdfExtractionGate.extract(file, timeoutMs = 2_000) { doc("next") }
        assertEquals("next", (r2 as PdfExtractionGate.GateResult.Done).doc.text)
    }
}
