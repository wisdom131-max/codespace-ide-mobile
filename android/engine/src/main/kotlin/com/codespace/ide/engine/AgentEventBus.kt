package com.codespace.ide.engine

/**
 * PROJECT NINE 9.14 (catalog 6) - one ordered stream of what the agent did.
 *
 * Events were passed by direct callback, so a second observer meant changing the emitter, and a
 * step that finished before the UI attached was simply lost. A bus with a sequence number lets a
 * late reader ask for everything after what it last saw.
 *
 * The buffer is bounded but the sequence is not: trimming old events must never make a reader
 * think nothing happened, so `since` is answered from the live window and gaps are honest.
 */
object AgentEventBus {

    data class Event(val seq: Long, val kind: String, val payload: String, val atMs: Long)

    class Bus(private val maxEvents: Int = 200) {

        private val items = ArrayDeque<Event>()
        private var nextSeq = 0L

        fun publish(kind: String, payload: String, atMs: Long): Event {
            nextSeq += 1
            val e = Event(nextSeq, kind, payload, atMs)
            items.addLast(e)
            while (items.size > maxEvents) items.removeFirst()
            return e
        }

        fun since(afterSeq: Long): List<Event> = items.filter { it.seq > afterSeq }

        fun all(): List<Event> = items.toList()

        fun size(): Int = items.size

        fun lastSeq(): Long = nextSeq
    }
}
