package com.codespace.ide.chat.panel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * PROJECT FOUR, STAGE 4.8 (2026-11-16): the delivery state behind [ReplyOwnership]'s rules.
 *
 * Stage 4.7 wrote the rules; nothing asked them. This is the state those rules need: which execution
 * is current for a session, which executions have already delivered, which were cancelled, and what a
 * previous process left on disk. It is one object, not a second rule system: every decision below is
 * delegated to [ReplyOwnership], and nothing here duplicates a check that already exists.
 *
 * Three things it deliberately cannot do:
 *  - grant a permission (it never talks to the tool gate or the approval gate);
 *  - treat unknown state as permission to replay (an unreadable record is UNKNOWN, and an execution
 *    whose state is unknown may not append and may not be restored as undelivered);
 *  - block legitimate parallel work (it holds no lock beyond the four mutable fields, and it never
 *    serialises tool execution, only the answer-append decision).
 *
 * Persistence stores identities and states only: no prompt text, no answer text, no key, no path.
 */
internal class DeliveryLedger(private val store: DeliveryStore? = null) {

    enum class State { PENDING, COMPLETED, CANCELLED, UNKNOWN }

    /** One execution of one submission, as far as delivery is concerned. */
    data class Record(val executionId: String, val sessionId: String, val state: State, val at: Long)

    private val delivered = LinkedHashSet<String>()
    private val cancelled = LinkedHashSet<String>()
    private val pending = LinkedHashMap<String, Record>()
    private var current: Record? = null
    private val lock = Any()

    @Synchronized
    fun restore() {
        val loaded = store?.load() ?: return
        delivered.clear()
        cancelled.clear()
        pending.clear()
        loaded.records.forEach { record ->
            when (record.state) {
                State.COMPLETED -> delivered.add(record.executionId)
                State.CANCELLED -> cancelled.add(record.executionId)
                State.PENDING, State.UNKNOWN -> pending[record.executionId] = record
            }
        }
    }

    /**
     * A submission begins. False means this execution already ran, so the event is a repeat (a
     * re-delivered click, a UI recreation, a restored view) and must not start a second execution.
     */
    fun beginSubmission(sessionId: String, executionId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (executionId.isBlank()) return false
        synchronized(lock) {
            if (executionId in delivered || executionId in cancelled) return false
            if (pending.containsKey(executionId)) return false
            val record = Record(executionId, sessionId, State.PENDING, now)
            val running = current
            // A different execution replaces the running one: the new send wins, exactly as
            // ReplyOwnership.startAction already decides for the UI.
            if (running != null && running.executionId != executionId && running.sessionId == sessionId) {
                // Replaced means closed: the message the user just sent wins, and the older
                // execution loses its right to append anything further.
                cancelled.add(running.executionId)
                pending.remove(running.executionId)
            }
            if (running == null || running.sessionId == sessionId) current = record
            pending[executionId] = record
            persist()
            return true
        }
    }

    /** Is this execution still the one allowed to write into its session? */
    fun ownsExecution(executionId: String, sessionId: String): Boolean = synchronized(lock) {
        if (executionId.isBlank()) return false
        if (executionId in cancelled) return false
        if (executionId in delivered) return false
        val record = pending[executionId] ?: current?.takeIf { it.executionId == executionId } ?: return false
        ReplyOwnership.ownsSession(record.sessionId, sessionId)
    }

    /**
     * The append boundary. True once per execution: the answer is written the first time and a
     * repeated completion event is refused, so the transcript cannot hold the same answer twice.
     */
    fun mayAppendAnswer(executionId: String, sessionId: String, now: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            if (!ownsExecution(executionId, sessionId)) return false
            if (!ReplyOwnership.mayDeliver(executionId, delivered)) return false
            delivered.add(executionId)
            pending.remove(executionId)
            current = current?.takeIf { it.executionId != executionId }
            persist(now)
            return true
        }
    }

    /** A restored transcript already holding this answer must not get it again. */
    fun mayRestoreAnswer(executionId: String, storedExecutionId: String?): Boolean = synchronized(lock) {
        ReplyOwnership.mayRestoreReply(executionId, storedExecutionId) && executionId !in delivered
    }

    fun complete(executionId: String, now: Long = System.currentTimeMillis()) {
        if (executionId.isBlank()) return
        synchronized(lock) {
            delivered.add(executionId)
            pending.remove(executionId)
            if (current?.executionId == executionId) current = null
            persist(now)
        }
    }

    /** Cancellation invalidates delivery from here on, including a callback still in flight. */
    fun cancel(executionId: String, now: Long = System.currentTimeMillis()) {
        if (executionId.isBlank()) return
        synchronized(lock) {
            cancelled.add(executionId)
            pending.remove(executionId)
            if (current?.executionId == executionId) current = null
            persist(now)
        }
    }

    fun isCancelled(executionId: String): Boolean = synchronized(lock) { executionId in cancelled }

    fun stateOf(executionId: String): State = synchronized(lock) {
        when {
            executionId in delivered -> State.COMPLETED
            executionId in cancelled -> State.CANCELLED
            pending.containsKey(executionId) -> State.PENDING
            else -> State.UNKNOWN
        }
    }

    /** Anything this process still believes is running, for an honest "was it delivered?" answer. */
    fun pendingExecutions(): List<Record> = synchronized(lock) { pending.values.toList() }

    fun currentExecution(): Record? = synchronized(lock) { current }

    private fun persist(now: Long = System.currentTimeMillis()) {
        val sink = store ?: return
        val records = ArrayList<Record>()
        delivered.forEach { records.add(Record(it, sessionOf(it), State.COMPLETED, now)) }
        cancelled.forEach { records.add(Record(it, sessionOf(it), State.CANCELLED, now)) }
        pending.values.forEach { records.add(it) }
        sink.save(records.takeLast(CAP))
    }

    private fun sessionOf(executionId: String): String =
        pending[executionId]?.sessionId ?: current?.sessionId.orEmpty()

    companion object {
        /** Bounded: the newest 200 execution identities. */
        const val CAP = 200
    }
}

/**
 * Where a ledger's identities go between processes. Structure only: execution id, session id, state,
 * time. An unreadable file yields nothing plus a reason, and a missing one is simply empty - the ledger
 * then reports UNKNOWN, which never authorises a replay.
 */
internal class DeliveryStore(private val file: File) {

    data class Loaded(val records: List<DeliveryLedger.Record>, val note: String = "")

    fun load(): Loaded {
        if (!file.exists()) return Loaded(emptyList())
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) return Loaded(emptyList(), "the delivery record was empty, so it was not trusted")
            val rows = JSONArray(text)
            val out = ArrayList<DeliveryLedger.Record>()
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val id = o.optString("executionId").trim()
                if (id.isEmpty()) continue
                val state = runCatching { DeliveryLedger.State.valueOf(o.optString("state", "UNKNOWN")) }
                    .getOrDefault(DeliveryLedger.State.UNKNOWN)
                out.add(DeliveryLedger.Record(id, o.optString("sessionId"), state, o.optLong("at", 0L)))
            }
            Loaded(out.takeLast(DeliveryLedger.CAP))
        }.getOrElse {
            Loaded(emptyList(), "the delivery record could not be read, so no answer was treated as delivered")
        }
    }

    fun save(records: List<DeliveryLedger.Record>) {
        runCatching {
            val rows = JSONArray()
            records.takeLast(DeliveryLedger.CAP).forEach { r ->
                rows.put(
                    JSONObject()
                        .put("executionId", r.executionId)
                        .put("sessionId", r.sessionId)
                        .put("state", r.state.name)
                        .put("at", r.at)
                )
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(rows.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(rows.toString())
                tmp.delete()
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val FILE_NAME = "reply-delivery.json"

        fun at(directory: File): DeliveryStore = DeliveryStore(File(directory, FILE_NAME))
    }
}
