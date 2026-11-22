package com.codespace.ide.engine

/**
 * PROJECT NINE 9.11 (catalog 33) - go back to how the task looked before the last few steps.
 *
 * Checkpointing answers "the app was killed, where were we". It cannot answer "the last two steps
 * made it worse, put it back" - there was only ever one saved position, and it was overwritten.
 *
 * A bounded ring of labelled snapshots answers that. It is deliberately bounded: an unbounded
 * history of every step is a memory leak wearing a feature's clothes.
 */
object RewindLog {

    data class Snapshot(val id: Long, val label: String, val state: String, val atMs: Long)

    class Log(private val maxSnapshots: Int = 20) {

        private val items = ArrayDeque<Snapshot>()
        private var nextId = 0L

        init {
            require(maxSnapshots > 0) { "a rewind log needs room for at least one snapshot" }
        }

        fun save(label: String, state: String, atMs: Long): Snapshot {
            nextId += 1
            val snap = Snapshot(nextId, label, state, atMs)
            items.addLast(snap)
            while (items.size > maxSnapshots) items.removeFirst()
            return snap
        }

        fun latest(): Snapshot? = items.lastOrNull()

        fun at(id: Long): Snapshot? = items.firstOrNull { it.id == id }

        fun list(): List<Snapshot> = items.toList()

        fun size(): Int = items.size
    }
}
