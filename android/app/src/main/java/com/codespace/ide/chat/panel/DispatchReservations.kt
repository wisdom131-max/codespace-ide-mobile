package com.codespace.ide.chat.panel

/**
 * STAGE 6 (Checkpoint 0.5) - a tool call is claimed BEFORE it runs, not after it returns.
 *
 * Rejecting a duplicate RESULT is not the same protection as rejecting a duplicate DISPATCH: by
 * the time a result arrives the tool has already run, and a file may already have been written.
 * The pair (execution id, call id) is reserved atomically first, so a second dispatch of the same
 * call inside the same execution is refused before it can cause a side effect.
 *
 * Identity is the PAIR, deliberately. A matching call id from a different execution is a different
 * call: two turns can legitimately ask for the same edit, and one turn must never authorise the
 * other's result.
 *
 * LIMIT, stated plainly: this is IN-PROCESS protection. It does not survive process death and it
 * does not make an external tool idempotent. There is no database and no transactional outbox here,
 * so exactly-once across a crash is not claimed and cannot be provided.
 */
internal object DispatchReservations {

    /** Bounded so a long session cannot grow this without limit. Oldest entries go first. */
    private const val MAX = 512

    private val lock = Any()
    private val claimed = LinkedHashSet<String>()

    private fun key(execId: String, callId: String): String = execId + "\u0000" + callId

    /**
     * Claims the pair. True means this caller owns the dispatch and must run it; false means the
     * pair is already claimed (or has already run) and must not run again.
     */
    fun reserve(execId: String?, callId: String?): Boolean = synchronized(lock) {
        // Nothing to identify: do not block. idFor returns null when a call has no identity.
        if (execId.isNullOrBlank() || callId.isNullOrBlank()) return true
        val k = key(execId, callId)
        if (!claimed.add(k)) return false
        while (claimed.size > MAX) {
            val oldest = claimed.firstOrNull() ?: break
            claimed.remove(oldest)
        }
        true
    }

    /** Releases a claim whose dispatch never happened, so an honest retry is not refused for ever. */
    fun release(execId: String?, callId: String?) {
        if (execId.isNullOrBlank() || callId.isNullOrBlank()) return
        synchronized(lock) { claimed.remove(key(execId, callId)) }
    }

    fun isClaimed(execId: String?, callId: String?): Boolean {
        if (execId.isNullOrBlank() || callId.isNullOrBlank()) return false
        return synchronized(lock) { key(execId, callId) in claimed }
    }
}
