package com.codespace.ide.chat.session

/**
 * M1c — THE DELETION RECORD.
 *
 * Why this file exists: deleting a session is two writes that can fail independently - the
 * metadata write and the transcript-bytes delete. If the metadata write succeeds and the byte
 * delete fails, the session is gone from the list but its transcript is still on disk. The next
 * time the index is damaged and rebuilt from the transcripts, that leftover file comes back as
 * a session the user INTENTIONALLY deleted. That is resurrection, and it is worse than a failed
 * delete because nothing tells the user it happened.
 *
 * So an intentional deletion is recorded on disk FIRST, before any metadata is touched:
 *
 *  - the record is written before the metadata write, so a crash at any point after it leaves
 *    proof that this id was deleted (requirement: crash between the two writes);
 *  - index recovery excludes every id with a record, whether or not its bytes are still there
 *    (requirement: recovery must not resurrect);
 *  - [DeletionRecord.bytesRemoved] keeps a failed byte delete DISTINGUISHABLE from a successful
 *    one instead of collapsing both into "deleted" (requirement: distinguishability);
 *  - bytes that could not be removed are never discarded by recovery - they are reported and
 *    left where they are (requirement: no silent discard);
 *  - records are per project, in that project's own directory, so project A's record can never
 *    reach project B (requirement: isolation);
 *  - records are only pruned once their bytes are CONFIRMED gone and a retention window has
 *    passed, so pruning can never be the thing that resurrects a session.
 */

/** One intentional deletion. [bytesRemoved] is the difference between "gone" and "half gone". */
internal data class DeletionRecord(
    val id: String,
    val deletedAtMs: Long,
    /**
     * True once the transcript bytes were CONFIRMED removed. False means the metadata is gone
     * but the file may still exist, which is exactly the state recovery must not resurrect.
     */
    val bytesRemoved: Boolean,
)

/** A project's deletion records. Small by nature: one line per deleted session. */
internal data class DeletionLog(val records: List<DeletionRecord> = emptyList()) {

    fun contains(id: String): Boolean = records.any { it.id == id }

    fun find(id: String): DeletionRecord? = records.firstOrNull { it.id == id }

    /**
     * True when the bytes for this id are confirmed gone. Recovery uses this to decide what it
     * may safely forget; it never uses "the record is old" as a substitute for "the file is
     * gone".
     */
    fun bytesConfirmedGone(id: String, stillOnDisk: Set<String>?): Boolean {
        val record = find(id) ?: return true
        return record.bytesRemoved || stillOnDisk?.contains(id) == false
    }

    fun with(record: DeletionRecord): DeletionLog =
        DeletionLog(records.filterNot { it.id == record.id } + record)

    fun without(id: String): DeletionLog = DeletionLog(records.filterNot { it.id == id })

    val ids: Set<String> get() = records.map { it.id }.toSet()

    companion object {
        val EMPTY = DeletionLog()
    }
}

/** Result of reading the deletion record. Strict: damage is never read as "nothing deleted". */
internal sealed interface DeletionLogRead {
    data class Ok(val log: DeletionLog) : DeletionLogRead
    data class Unreadable(val reason: String) : DeletionLogRead
}

/** Outcome of pruning stale records. */
internal data class DeletionPrune(
    val removed: Int,
    val kept: Int,
    val failure: String? = null,
)

/** One pending record whose bytes could not be removed, and why. */
internal data class PendingFailure(val id: String, val reason: String)

/**
 * Outcome of retrying the byte removal for records still marked `bytesRemoved = false`.
 *
 * [cleared] ids had their bytes CONFIRMED gone and their record upgraded; [failed] ids were left
 * exactly as they were. [failure] is set only when the whole operation could not run (an
 * unreadable record, or a record write that failed after the bytes were already gone).
 */
internal data class DeletionRetry(
    /** How many pending records were considered (not the number of successful deletes). */
    val retried: Int,
    val cleared: List<String>,
    val failed: List<PendingFailure>,
    val failure: String? = null,
) {
    val bytesStillOnDisk: Boolean get() = failed.isNotEmpty()
}

/**
 * The on-disk form: a header line, then one tab-separated record per deletion.
 *
 * Strict on purpose, for the same reason the session index is strict - a blank or damaged file
 * must not read as "nothing was ever deleted", because that is the exact input that lets a
 * rebuild resurrect a deleted conversation. Ids are already restricted to safe path characters
 * by [SessionMetaRules], so no escaping is needed; anything with a tab or a bad id is damage.
 */
internal object DeletionLogCodec {

    const val FORMAT_VERSION = "vncode-deletions-1"

    fun encode(log: DeletionLog): String {
        val sb = StringBuilder()
        sb.append(FORMAT_VERSION).append('\n')
        for (r in log.records) {
            require(SessionMetaRules.isSafeId(r.id)) { "unsafe deletion id: " + r.id }
            require(r.deletedAtMs >= 0L) { "negative deletion timestamp" }
            sb.append('d').append('\t')
                .append(r.id).append('\t')
                .append(r.deletedAtMs).append('\t')
                .append(if (r.bytesRemoved) '1' else '0')
                .append('\n')
        }
        return sb.toString()
    }

    fun decode(raw: String): DeletionLogRead {
        if (raw.isBlank()) return DeletionLogRead.Unreadable("the deletion record is empty")
        val lines = raw.split('\n')
        if (lines.isEmpty() || lines[0].trim() != FORMAT_VERSION) {
            return DeletionLogRead.Unreadable("unrecognised deletion record format")
        }
        val out = ArrayList<DeletionRecord>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != 4) {
                return DeletionLogRead.Unreadable("a malformed deletion record line")
            }
            if (parts[0] != "d") return DeletionLogRead.Unreadable("unknown deletion record type")
            val id = parts[1]
            if (!SessionMetaRules.isSafeId(id)) {
                return DeletionLogRead.Unreadable("an invalid session id in the deletion record")
            }
            val at = parts[2].toLongOrNull()
                ?: return DeletionLogRead.Unreadable("an invalid deletion timestamp")
            if (at < 0L) return DeletionLogRead.Unreadable("a negative deletion timestamp")
            val removed = when (parts[3]) {
                "1" -> true
                "0" -> false
                else -> return DeletionLogRead.Unreadable("an invalid deletion state")
            }
            if (out.any { it.id == id }) {
                return DeletionLogRead.Unreadable("a duplicate deletion record")
            }
            out.add(DeletionRecord(id, at, removed))
        }
        return DeletionLogRead.Ok(DeletionLog(out))
    }
}
