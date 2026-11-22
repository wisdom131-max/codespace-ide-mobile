package com.codespace.ide.engine

/**
 * PROJECT NINE 9.20 (catalog 91) - keep the last few turns' traces so a bad answer can be read back.
 *
 * TurnTrace could render a turn and nothing kept the result, so by the time a user asked "why did
 * it do that" the trace was gone. The rendered form is what is kept, because that is what a person
 * reads and what survives a restart unchanged.
 *
 * Bounded, and sequenced so a reader that saw seq N can ask for what came after.
 */
object TraceStore {

    /**
     * One shelf for the whole process. The engine files a turn here without needing a new
     * constructor argument, and a reader has exactly one place to look. Tests use their own Store
     * instance instead, so a shared shelf never makes one test depend on another.
     */
    private val shared = Store()

    fun record(label: String, rendered: String, atMs: Long): Record = shared.add(label, rendered, atMs)

    fun recent(afterSeq: Long): List<Record> = shared.since(afterSeq)

    fun latestRecord(): Record? = shared.latest()

    data class Record(val seq: Long, val label: String, val rendered: String, val atMs: Long)

    class Store(private val maxRecords: Int = 50) {

        private val items = ArrayDeque<Record>()
        private var nextSeq = 0L

        fun add(label: String, rendered: String, atMs: Long): Record {
            nextSeq += 1
            val r = Record(nextSeq, label, rendered, atMs)
            items.addLast(r)
            while (items.size > maxRecords) items.removeFirst()
            return r
        }

        fun since(afterSeq: Long): List<Record> = items.filter { it.seq > afterSeq }

        fun latest(): Record? = items.lastOrNull()

        fun all(): List<Record> = items.toList()

        fun size(): Int = items.size
    }
}
