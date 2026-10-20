package com.codespace.ide.chat

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * U4 ADVISOR ITEM-2 (2026-10-20): STUCK-PARSE CONTAINMENT for PDF extraction.
 *
 * READ of the pre-gate reality (honest, verified in code): the outer effect
 * runs [AttachmentIntake.extractBlocking] inside
 * `withTimeoutOrNull(5s) + withContext(Dispatchers.IO)`. withTimeoutOrNull
 * cancels the WAITING coroutine, but a pdfbox parse stuck inside one page is
 * a CPU-bound block that (1) never observes coroutine cancellation, so the
 * Dispatchers.IO thread keeps burning CPU until the parse returns or dies,
 * and (2) because withContext does not abandon a running block on
 * cancellation, a truly stuck parse also held the effect's result past the
 * 5 s timeout. The engine's internal deadline only breaks BETWEEN pages — a
 * single page's parse is uninterruptible from outside.
 *
 * CONTAINMENT (this gate — bounded, flag-guarded, prompt):
 *  - DEDICATED BOUNDED EXECUTOR: ONE daemon, low-priority background thread
 *    ("pdf-extract"). Stuck CPU burn is confined to that single thread; the
 *    shared Dispatchers.IO pool is never touched by PDF parsing.
 *  - CALLER-SIDE WAIT with its own timeout: the caller blocks at most
 *    `timeoutMs` — `future.get` returns TimeoutException promptly, so the
 *    user sees the honest refusal AT the timeout, not whenever the parse
 *    eventually dies.
 *  - ABANDONED-WORK FLAG: when a waiter times out, the job is marked
 *    abandoned and the lane thread is INTERRUPTED (`future.cancel(true)`).
 *  - REFUSAL WHILE STUCK: a NEW PDF extraction submitted while an abandoned
 *    job still occupies the lane gets an immediate Busy refusal — no
 *    queueing behind a zombie. The flag CLEARS when the stuck job finally
 *    completes (or dies), so the lane self-heals.
 *
 * HONEST LIMITATION (advisor: "state a limitation honestly"): the thread
 * interrupt is BEST-EFFORT. pdfbox's page parsing is a CPU loop that does
 * not check Thread.interrupted() — an interrupt can only break it at an
 * interruptible boundary (stream read), which a hostile CPU-recursive file
 * never reaches. The interrupt is still sent (cheap, sometimes helps, e.g.
 * OOM-adjacent recoveries in closable streams); the REAL containment is the
 * one-thread lane + the Busy refusal + the daemon thread (it can never keep
 * the process alive at exit). A parse stuck forever burns ONE background
 * thread's CPU until app restart — stated, contained, not solved.
 */
object PdfExtractionGate {

    /** Caller-side wait — deliberately under the effect's 5 s outer timeout. */
    const val GATE_WAIT_MS = 4_500L

    private val lock = Any()
    private var lane: ThreadPoolExecutor = newLane()
    private var inFlight: Boolean = false
    private var abandonedInFlight: Boolean = false

    private fun newLane(): ThreadPoolExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { r -> Thread(r, "pdf-extract").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    /** Outcome for the caller — every branch maps to honest user wording. */
    sealed class GateResult {
        data class Done(val doc: AttachmentIntake.ExtractedDoc) : GateResult()
        /** An abandoned (timed-out) parse still occupies the lane. */
        object Busy : GateResult()
        /** OUR parse did not finish within the caller-side wait. */
        data class TimedOut(val waitedMs: Long) : GateResult()
    }

    /**
     * Runs [work] (the real PDF extraction, or a test's fake engine) on the
     * dedicated lane. The caller waits at most [timeoutMs]; a job that
     * overruns is marked abandoned + interrupted, and new callers get Busy
     * until the stuck job completes and the flag self-clears.
     */
    fun extract(
        file: File,
        timeoutMs: Long = GATE_WAIT_MS,
        work: (File) -> AttachmentIntake.ExtractedDoc,
    ): GateResult {
        synchronized(lock) {
            if (abandonedInFlight) return GateResult.Busy
            inFlight = true
        }
        var future: Future<AttachmentIntake.ExtractedDoc>? = null
        try {
            future = lane.submit(Callable {
                try {
                    work(file)
                } finally {
                    // Completion (normal, failed, or the stuck parse finally
                    // dying) clears BOTH flags: the lane self-heals here.
                    synchronized(lock) {
                        inFlight = false
                        abandonedInFlight = false
                    }
                }
            })
            return GateResult.Done(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            synchronized(lock) { abandonedInFlight = true }
            // Best-effort interrupt — see the HONEST LIMITATION note above.
            future?.cancel(true)
            return GateResult.TimedOut(timeoutMs)
        } catch (e: Exception) {
            // Lane thread died (rejected/rewound) — one honest Failed doc.
            return GateResult.Done(
                AttachmentIntake.ExtractedDoc(
                    refusal = "PDF extraction failed (${e.message ?: e.javaClass.simpleName}) — showing file info only."
                )
            )
        }
    }

    /** Test support: fresh lane + flags. Waits for any running job to end. */
    internal fun resetForTest() {
        val old = synchronized(lock) {
            val l = lane
            inFlight = false
            abandonedInFlight = false
            lane = newLane()
            l
        }
        try { old.shutdown(); old.awaitTermination(5, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    internal fun isBusyForTest(): Boolean = synchronized(lock) { abandonedInFlight }
}
