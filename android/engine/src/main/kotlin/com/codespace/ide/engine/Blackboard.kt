package com.codespace.ide.engine

/**
 * PROJECT NINE 9.10 (catalog 34) - one place several steps can leave a note for each other.
 *
 * Tool steps passed findings along by re-reading files or repeating searches, which costs tokens
 * and time. A blackboard gives them a shared, ordered place to write, with who wrote it, so a
 * later step can ask for everything newer than the last thing it saw.
 *
 * Ordered by a monotonic sequence, never by wall clock: two notes written in the same millisecond
 * still have a stable order, and a clock that jumps cannot reorder history.
 */
object Blackboard {

    data class Note(val key: String, val value: String, val writer: String, val seq: Long, val atMs: Long)

    class Board {
        private val notes = LinkedHashMap<String, Note>()
        private var seq = 0L

        fun write(key: String, value: String, writer: String, atMs: Long): Note {
            seq += 1
            val note = Note(key, value, writer, seq, atMs)
            notes[key] = note
            return note
        }

        fun read(key: String): Note? = notes[key]

        fun since(afterSeq: Long): List<Note> = notes.values.filter { it.seq > afterSeq }

        fun snapshot(): List<Note> = notes.values.toList()

        fun size(): Int = notes.size
    }
}
