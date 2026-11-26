package com.codespace.ide.chat.panel

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * STAGE 3 (Checkpoint 0.5) - one request attempt, owning everything that belongs to it.
 *
 * The response buffer, the protocol filter, the job and the publication clock used to be single
 * fields on ChatSession, so every attempt shared them. A stale attempt could therefore clear,
 * extend or repaint text that belonged to a newer one, and the only thing standing between the
 * two was a generation check at the top of the event handler. Storage is now per attempt: an old
 * attempt that slips past a guard writes into its own object, which nobody reads.
 *
 * Everything here is touched from the collector coroutine, the UI thread (cancel) and the
 * coordinator, so the mutable text fields are guarded by [lock]. The lock is only ever held for
 * field access - never across a suspension point.
 */
internal class RequestAttempt(
    val gen: Int,
    val execId: String,
) {
    private val lock = Any()

    private val buffer = StringBuilder()
    private var published = 0

    /** Text the protocol filter has released for display. */
    @Volatile
    var visible: String = ""

    /** The filter belongs to this attempt: a retry must not inherit a half-open protocol region. */
    var filter = com.codespace.ide.agent.ProtocolStreamFilter()

    /** The coroutine running this attempt. Registered before it is started. */
    @Volatile
    var job: Job? = null

    /** Publication clock, per attempt so one attempt cannot make another look overdue. */
    @Volatile
    var lastPublishMs: Long = 0L
    @Volatile
    var lastPublishLen: Int = 0

    /** STAGE 5: this attempt's own stall clock. Traffic is not progress; new text is. */
    val watchdog = StreamProgressWatchdog()

    /** Set on every terminal path so a finished attempt can never be reported as stalled. */
    @Volatile var finished: Boolean = false

    /** The logical reply this attempt is writing into. Survives retry and continuation. */
    @Volatile
    var replyId: String = ""

    /** Set by cancel before the job is cancelled, so a late event can see it. */
    @Volatile
    var cancelRequested: Boolean = false

    /** Set in the collector's finally: no further text will arrive. */
    @Volatile
    var sealed: Boolean = false

    private val finalized = AtomicBoolean(false)

    /** True exactly once per attempt, so a reply cannot be finished twice. */
    fun claimFinalization(): Boolean = finalized.compareAndSet(false, true)

    fun isFinalized(): Boolean = finalized.get()

    fun append(text: String) = synchronized(lock) {
        buffer.append(text)
    }

    fun clearBuffer() = synchronized(lock) {
        buffer.setLength(0)
        published = 0
    }

    fun bufferLength(): Int = synchronized(lock) { buffer.length }

    /**
     * The text that has not been painted yet, in order. Returns only the new suffix, so painting
     * never re-reads the whole answer.
     */
    fun drain(): String = synchronized(lock) {
        if (published >= buffer.length) return ""
        val fresh = buffer.substring(published)
        published = buffer.length
        fresh
    }

    /** Everything received so far, for the final row and for retry capture. */
    fun text(): String = synchronized(lock) { buffer.toString() }

    /** The live builder, for the call sites that mutate it directly (setLength/append/length). */
    fun rawBuffer(): StringBuilder = buffer
}

/**
 * The attempts of one session, keyed by request generation.
 *
 * Kept small on purpose: a phone only ever needs the running attempt and the one it replaced, so
 * older records are dropped as new ones begin. This is the bounded cleanup the contract asks for,
 * and it is also what stops a map of every attempt ever made from growing in memory.
 */
internal class RequestAttempts(private val keep: Int = 4) {

    private val byGen = ConcurrentHashMap<Int, RequestAttempt>()
    @Volatile
    private var currentGen: Int = 0

    fun begin(gen: Int, execId: String): RequestAttempt {
        val attempt = RequestAttempt(gen, execId)
        byGen[gen] = attempt
        currentGen = gen
        if (byGen.size > keep) {
            byGen.keys.sorted().dropLast(keep).forEach { byGen.remove(it) }
        }
        return attempt
    }

    fun current(): RequestAttempt? = byGen[currentGen]

    fun of(gen: Int): RequestAttempt? = byGen[gen]

    fun drop(gen: Int) {
        byGen.remove(gen)
    }

    /** A buffer for the moments before any attempt exists, so no call site can hit a null. */
    private val idleBuffer = StringBuilder()

    fun currentBuffer(): StringBuilder = current()?.rawBuffer() ?: idleBuffer
}
