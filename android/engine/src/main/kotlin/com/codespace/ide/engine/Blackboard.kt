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

    /**
     * One board for the process, so a tool can post what it found without threading a new
     * argument through every call site. Tests keep their own Board.
     */
    private val shared = Board()

    /** What the last writer put under this key, or null when nobody has. */
    fun read(key: String): Note? = shared.read(key)

    fun post(key: String, value: String, writer: String, atMs: Long): Note =
        shared.write(key, value, writer, atMs)

    fun note(key: String): Note? = shared.read(key)

    fun notes(): List<Note> = shared.snapshot()

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

        // A key keeps its original slot in the map when it is overwritten, so reading straight from
        // the map returns notes out of sequence as soon as anything is rewritten. Sorting is what
        // makes the class doc true.
        fun since(afterSeq: Long): List<Note> = notes.values.filter { it.seq > afterSeq }.sortedBy { it.seq }

        fun snapshot(): List<Note> = notes.values.sortedBy { it.seq }

        fun size(): Int = notes.size
    }
}
