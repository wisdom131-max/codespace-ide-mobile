package com.codespace.ide.chat.session

import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.TranscriptFiles
import com.codespace.ide.chat.TranscriptLoad
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/**
 * M1 — PERSISTENT SESSION MANAGEMENT.
 *
 * Owns `filesDir/chat/<project>/sessions.index` (plus `.bak`): the table of which sessions a
 * project has. The TRANSCRIPTS stay exactly where they were (`<conversationId>.txt`), so a
 * project's existing conversation, id "main", is adopted by writing one metadata record and
 * touching no transcript bytes at all.
 *
 * THE FOUR RULES THIS FILE EXISTS TO KEEP:
 *
 * 1. NEVER READ A DAMAGED TABLE AS AN EMPTY ONE. A blank or unrecognised file is Unreadable,
 *    not "no sessions". Overwriting it would silently drop every session it named.
 * 2. MUTATE METADATA BEFORE DELETING BYTES. Deleting a transcript first and then failing to
 *    write the index would destroy a conversation while leaving a record claiming it exists.
 *    So a delete writes the new index first, and only then removes the transcript; if the
 *    index write fails, the transcript is untouched and the caller is told.
 * 3. REPORT, NEVER SWALLOW. Every operation returns an explicit ok/failed value. There is no
 *    "return false on error" that a caller can mistake for a no-op.
 * 4. ONE PROJECT, ONE DIRECTORY. The project id is turned into a path segment with the SAME
 *    function the transcripts use, so a store for project A can physically not name a file
 *    belonging to project B.
 *
 * Not suspend: the operations are small, rare, and driven by explicit user actions, and a
 * non-suspending store is what lets the whole thing be proven by plain JVM tests.
 */
internal class SessionIndexStore(
    /**
     * The chat root: `filesDir/chat`. The index lives beside the transcripts in the project's
     * own directory, but is NOT named `*.txt`: the transcript glob must never mistake the index
     * for a conversation.
     */
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    /** Needed for title/date adoption and for corrupt-index recovery. Null = metadata only. */
    private val transcripts: SessionTranscripts? = null,
) {

    companion object {
        const val FILE_NAME = "sessions.index"
        const val BACKUP_SUFFIX = ".bak"
        const val TEMP_SUFFIX = ".tmp"

        /** The durable record of intentional deletions, beside the index and per project. */
        const val DELETIONS_FILE = "sessions.deleted"

        /**
         * How long a CONFIRMED deletion record is kept before it is pruned.
         *
         * A record whose bytes are confirmed gone is not needed for correctness (a rebuild
         * finds no file to resurrect), so it is only an audit trail. A record whose bytes are
         * still on disk is NEVER pruned by age: that is the record doing the actual work.
         */
        const val STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L
    }

    // ---------------------------------------------------------------- read

    /**
     * Strict read, with the previous good copy as the only automatic recovery.
     *
     * A MISSING file is a legitimate empty project (nothing has ever created a session).
     * A PRESENT but unparseable file is not: it is reported, and the `.bak` written before the
     * last successful save is offered instead, because that copy is known to be well formed.
     */
    fun read(projectId: String): SessionIndexRead = applyDeletions(projectId, readRaw(projectId))

    /**
     * THE DELETION RECORD IS THE AUTHORITY, NOT THE FILE.
     *
     * A `.bak` copy written before a delete still names the deleted session, and a damaged
     * primary falls back to exactly that copy. So every index this store hands out is filtered
     * through the deletion record: an id with a record is not part of the project any more, no
     * matter which copy happens to mention it. Filtering is a read-side rule, not a write, so
     * the file is left alone until the next legitimate save rewrites it.
     */
    private fun applyDeletions(projectId: String, r: SessionIndexRead): SessionIndexRead {
        if (r !is SessionIndexRead.Ok) return r
        val log = when (val d = readDeletions(projectId)) {
            is DeletionLogRead.Ok -> d.log
            is DeletionLogRead.Unreadable -> return SessionIndexRead.Unreadable(
                "The deletion record could not be read (" + d.reason +
                    "), so this project was left exactly as it is.",
            )
        }
        if (log.records.isEmpty()) return r
        val kept = r.index.sessions.filterNot { log.contains(it.id) }
        if (kept.size == r.index.sessions.size) return r
        val hidden = r.index.sessions.size - kept.size
        val active = r.index.activeId?.takeIf { id -> !log.contains(id) } ?: kept.lastOrNull()?.id
        return SessionIndexRead.Ok(
            SessionIndex(kept, active),
            joinNotes(r.note, hidden.toString() + " deleted session(s) are no longer part of this project."),
        )
    }

    private fun readRaw(projectId: String): SessionIndexRead {
        val target = fileFor(projectId)
        val bak = backupFor(target)
        val primaryRaw = readOrNull(target)
        if (primaryRaw != null) {
            val decoded = SessionIndexCodec.decode(primaryRaw)
            if (decoded is SessionIndexRead.Ok) return decoded
            val reason = (decoded as SessionIndexRead.Unreadable).reason
            val bakRaw = readOrNull(bak)
            if (bakRaw != null) {
                val fromBak = SessionIndexCodec.decode(bakRaw)
                if (fromBak is SessionIndexRead.Ok) {
                    return SessionIndexRead.Ok(
                        fromBak.index,
                        "The session index was unreadable (" + reason + "), so the previous good copy was used.",
                    )
                }
            }
            return SessionIndexRead.Unreadable(reason)
        }
        val bakRaw = readOrNull(bak)
        if (bakRaw != null) {
            val fromBak = SessionIndexCodec.decode(bakRaw)
            if (fromBak is SessionIndexRead.Ok) {
                return SessionIndexRead.Ok(
                    fromBak.index,
                    "The session index was missing, so the previous good copy was used.",
                )
            }
        }
        // "The file is there but its bytes could not be read" is NOT the same as "there is no
        // file". Reporting it as an empty index would let the very next write clobber a table
        // that might still be perfectly good once the read problem (permissions, a directory
        // left in its place) is fixed.
        if (target.exists()) {
            return SessionIndexRead.Unreadable("the session index exists but could not be read")
        }
        // Genuinely absent: this project has no sessions yet. Not an error.
        return SessionIndexRead.Ok(SessionIndex())
    }

    /**
     * True when this project's table names the session. A damaged table answers false, because
     * a caller that cannot read the table has no business acting on a guess.
     */
    fun contains(projectId: String, sessionId: String): Boolean =
        (read(projectId) as? SessionIndexRead.Ok)?.index?.contains(sessionId) == true

    /**
     * Opens a project: read strictly, make sure the active conversation is REPRESENTED in the
     * index, and adopt the legacy "main" conversation when that is the one in use.
     *
     * Adoption writes metadata only. The transcript file is never renamed, rewritten or
     * deleted, which is what keeps an existing conversation reachable across this upgrade.
     */
    fun ensure(projectId: String): SessionIndexRead {
        return when (val r = read(projectId)) {
            is SessionIndexRead.Ok -> {
                val log = when (val d = readDeletions(projectId)) {
                    is DeletionLogRead.Ok -> d.log
                    is DeletionLogRead.Unreadable -> return SessionIndexRead.Unreadable(
                        "The deletion record could not be read (" + d.reason +
                            "), so this project was left exactly as it is.",
                    )
                }
                val chosen = r.index.resolvedActiveId ?: SessionMeta.LEGACY_ID
                if (log.contains(chosen)) {
                    // The conversation this build would open is one the user DELETED whose
                    // bytes may still be on disk. Opening it would show the deleted messages
                    // again, so a fresh conversation is started instead.
                    when (val created = create(projectId)) {
                        is SessionCreate.Created -> SessionIndexRead.Ok(
                            created.index,
                            joinNotes(r.note, "That conversation was deleted, so a new one was started."),
                        )
                        is SessionCreate.Failed -> SessionIndexRead.Ok(
                            r.index,
                            joinNotes(
                                r.note,
                                "That conversation was deleted, and a new one could not be started: " + created.reason,
                            ),
                        )
                    }
                } else {
                    val adopted = adopt(projectId, r.index, r.note)
                    if (adopted === r) r else adopted
                }
            }
            is SessionIndexRead.Unreadable -> recover(projectId, r.reason)
        }
    }

    /**
     * Rebuild the index from the transcripts that actually exist, then persist it, keeping the
     * damaged original as `.corrupt-<timestamp>.txt` so nothing is thrown away. Callers get a
     * note describing exactly what happened.
     */
    fun recover(projectId: String, reason: String): SessionIndexRead {
        // BEFORE anything is rebuilt: a deletion record that cannot be read is a reason to stop.
        // Rebuilding without it is exactly how a deleted conversation comes back.
        val log = when (val d = readDeletions(projectId)) {
            is DeletionLogRead.Ok -> d.log
            is DeletionLogRead.Unreadable -> return SessionIndexRead.Unreadable(
                reason + " The deletion record also could not be read (" + d.reason +
                    "), so no rebuild was attempted: rebuilding without it could bring back a " +
                    "conversation that was deliberately deleted.",
            )
        }
        val ids = try {
            transcripts?.ids(projectId) ?: emptyList()
        } catch (t: Throwable) {
            return SessionIndexRead.Unreadable(reason + " (and the transcripts could not be listed: " + msg(t) + ")")
        }
        val kept = ids.filterNot { log.contains(it) }
        val withheld = ids.filter { log.contains(it) }
        val now = clock()
        val rebuilt = kept.sorted().map { id -> synthesise(projectId, id, now) }
        val index = SessionIndex(rebuilt, rebuilt.firstOrNull()?.id)
        val note = "The session index could not be read (" + reason + "). " +
            rebuilt.size + " session(s) were rebuilt from the transcripts on disk." +
            if (withheld.isEmpty()) "" else " " + withheld.size +
                " deleted session(s) were kept out of the list; their transcript files are " +
                "still on disk and were left untouched."
        // Persist even when nothing was rebuilt: an index that stays damaged is an index the
        // user can never add a session to again. The damaged bytes are quarantined, not lost.
        // Only a metadata-only store (no transcript layer) declines to write, because it has
        // nothing to rebuild FROM and must not overwrite what it cannot reconstruct.
        if (transcripts != null) persistRecovered(projectId, index)
        return SessionIndexRead.Ok(index, note)
    }

    // ---------------------------------------------------------------- deletions

    fun deletionsFileFor(projectId: String): File =
        File(File(root, TranscriptFiles.safeSegment(projectId)), DELETIONS_FILE)

    /**
     * Reads this project's deletion record.
     *
     * A MISSING file means nothing was ever deleted here - a legitimate empty state.
     * A PRESENT but unreadable file is NOT empty: it is the one input that could let a rebuild
     * resurrect a deleted conversation, so callers must stop rather than assume.
     */
    fun readDeletions(projectId: String): DeletionLogRead {
        val file = deletionsFileFor(projectId)
        if (!file.exists()) return DeletionLogRead.Ok(DeletionLog.EMPTY)
        val raw = readOrNull(file)
            ?: return DeletionLogRead.Unreadable("the deletion record exists but could not be read")
        return DeletionLogCodec.decode(raw)
    }

    private fun writeDeletions(projectId: String, log: DeletionLog): SessionWrite {
        val encoded = try {
            DeletionLogCodec.encode(log)
        } catch (t: Throwable) {
            return SessionWrite.Failed("Could not encode the deletion record: " + msg(t))
        }
        val error = writeAtomic(deletionsFileFor(projectId), encoded)
        return if (error == null) SessionWrite.Ok else SessionWrite.Failed(error)
    }

    /** True when this project recorded an intentional deletion of this id. */
    fun isDeleted(projectId: String, sessionId: String): Boolean =
        (readDeletions(projectId) as? DeletionLogRead.Ok)?.log?.contains(sessionId) == true

    /**
     * Prunes records that can no longer do any work: bytes CONFIRMED gone AND past the
     * retention window. A record whose bytes are still on disk is kept no matter how old it is,
     * because it is the only thing standing between those bytes and a rebuild.
     */
    fun pruneDeletions(
        projectId: String,
        olderThanMs: Long = STALE_AFTER_MS,
        nowMs: Long = clock(),
    ): DeletionPrune {
        val log = when (val d = readDeletions(projectId)) {
            is DeletionLogRead.Ok -> d.log
            is DeletionLogRead.Unreadable -> return DeletionPrune(0, 0, d.reason)
        }
        if (log.records.isEmpty()) return DeletionPrune(0, 0)
        val stillOnDisk: Set<String>? = try {
            transcripts?.ids(projectId)?.toSet()
        } catch (_: Throwable) {
            null
        }
        val (removable, kept) = log.records.partition { r ->
            log.bytesConfirmedGone(r.id, stillOnDisk) && (nowMs - r.deletedAtMs) >= olderThanMs
        }
        if (removable.isEmpty()) return DeletionPrune(0, kept.size)
        return when (val w = writeDeletions(projectId, DeletionLog(kept))) {
            is SessionWrite.Ok -> DeletionPrune(removable.size, kept.size)
            is SessionWrite.Failed -> DeletionPrune(0, log.records.size, w.reason)
        }
    }

    /**
     * RETRIES the byte removal for records that are still marked `bytesRemoved = false`.
     *
     * This exists because a failed byte delete leaves a transcript file that nothing else can
     * reclaim: the session is already out of the list (and filtered out of every read), so the
     * ordinary delete path can no longer reach it. Without this, those bytes would sit on the
     * device forever.
     *
     * Deliberately NOT automatic. It is never called from [read], [ensure], [recover] or any
     * other open path, because a read must never delete a file. It is a repair operation for an
     * explicit caller (an M2 settings action), and it is safe to call repeatedly.
     *
     * A record is upgraded to `bytesRemoved = true` only after the bytes are CONFIRMED gone by
     * re-listing the project's transcripts - "the delete returned no error" is not treated as
     * proof. A record whose deletion fails again is preserved untouched, so a rebuild continues
     * to exclude it.
     */
    fun retryPendingDeletions(projectId: String): DeletionRetry {
        val log = when (val d = readDeletions(projectId)) {
            is DeletionLogRead.Ok -> d.log
            is DeletionLogRead.Unreadable -> return DeletionRetry(
                0, emptyList(), emptyList(),
                "The deletion record could not be read (" + d.reason + ").",
            )
        }
        val pending = log.records.filter { !it.bytesRemoved }
        if (pending.isEmpty()) return DeletionRetry(0, emptyList(), emptyList())

        val sink = transcripts ?: return DeletionRetry(
            pending.size,
            emptyList(),
            pending.map { PendingFailure(it.id, "no transcript store is attached, so nothing could be removed") },
        )

        var working = log
        val cleared = ArrayList<String>()
        val failed = ArrayList<PendingFailure>()
        for (record in pending) {
            val error = try {
                sink.deleteNow(projectId, record.id)
            } catch (t: Throwable) {
                msg(t)
            }
            if (error != null) {
                failed.add(PendingFailure(record.id, error))
                continue
            }
            // Confirm, do not assume: a store that reports success but leaves the file is not a
            // removal, and upgrading the record on that report would make pruning delete the only
            // thing keeping the bytes out of a rebuild.
            val stillThere = try {
                sink.ids(projectId).contains(record.id)
            } catch (t: Throwable) {
                null
            }
            when {
                stillThere == null -> failed.add(
                    PendingFailure(record.id, "the transcript could not be listed to confirm the removal"),
                )
                stillThere -> failed.add(
                    PendingFailure(record.id, "the transcript file is still present after the delete"),
                )
                else -> {
                    working = working.with(record.copy(bytesRemoved = true))
                    cleared.add(record.id)
                }
            }
        }
        if (cleared.isEmpty()) return DeletionRetry(pending.size, emptyList(), failed)

        return when (val w = writeDeletions(projectId, working)) {
            is SessionWrite.Ok -> DeletionRetry(pending.size, cleared, failed)
            is SessionWrite.Failed -> DeletionRetry(
                pending.size,
                emptyList(),
                failed + cleared.map {
                    PendingFailure(it, "the transcript was deleted but the record could not be updated: " + w.reason)
                },
                w.reason,
            )
        }
    }

    /**
     * DELETES THE WHOLE DELETION RECORD. This is a repair, not a routine operation.
     *
     * It exists for one situation only: the record itself is damaged, and without it this
     * project can never create a session again. The consequence must be stated plainly - any
     * transcript bytes still on disk become visible to a rebuild again, so a session whose
     * bytes could not be removed may come back.
     */
    fun resetDeletions(projectId: String): SessionWrite =
        when (val w = writeDeletions(projectId, DeletionLog.EMPTY)) {
            is SessionWrite.Ok -> SessionWrite.Ok
            is SessionWrite.Failed -> w
        }

    // ---------------------------------------------------------------- write

    /** Atomic write: temp file, fsync, backup the previous copy, then rename into place. */
    fun save(projectId: String, index: SessionIndex): SessionWrite {
        return saveInternal(projectId, index, keepCorruptCopy = false)
    }

    private fun persistRecovered(projectId: String, index: SessionIndex) {
        saveInternal(projectId, index, keepCorruptCopy = true)
    }

    private fun saveInternal(projectId: String, index: SessionIndex, keepCorruptCopy: Boolean): SessionWrite {
        val invalid = index.sessions.firstOrNull { !SessionMetaRules.isValid(it) }
        if (invalid != null) {
            return SessionWrite.Failed(
                "Refusing to save an invalid session record: " + SessionMetaRules.problems(invalid).joinToString(","),
            )
        }
        val encoded = try {
            SessionIndexCodec.encode(index)
        } catch (t: Throwable) {
            return SessionWrite.Failed("Could not encode the session index: " + msg(t))
        }
        val target = fileFor(projectId)
        val bak = backupFor(target)
        val tmp = File(target.parentFile, target.name + TEMP_SUFFIX)
        return try {
            target.parentFile?.mkdirs()
            // fsync, so a power cut cannot leave a rename pointing at a file whose bytes never
            // reached the flash. The index is small and written rarely; this is cheap.
            FileOutputStream(tmp).use { out ->
                out.write(encoded.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (keepCorruptCopy && target.exists()) {
                // Preserve the damaged original instead of letting the backup overwrite it.
                val quarantine = File(target.parentFile, "sessions.index.corrupt-" + clock())
                target.copyTo(quarantine, overwrite = true)
            }
            if (target.exists()) {
                try {
                    target.copyTo(bak, overwrite = true)
                } catch (_: Throwable) {
                    // A missing backup is survivable; a half-written primary is not.
                }
            }
            if (!tmp.renameTo(target)) {
                target.writeText(encoded)
                tmp.delete()
            }
            SessionWrite.Ok
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Throwable) { }
            SessionWrite.Failed("Could not save the session index: " + msg(t))
        }
    }

    /** Temp file + fsync + rename. Returns an error message, or null on success. */
    private fun writeAtomic(target: File, content: String): String? {
        val tmp = File(target.parentFile, target.name + TEMP_SUFFIX)
        return try {
            target.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
            null
        } catch (t: Throwable) {
            try { tmp.delete() } catch (_: Throwable) { }
            "Could not write " + target.name + ": " + msg(t)
        }
    }

    /** Records that a session's conversation changed, so "Updated" ordering stays truthful. */
    fun touch(projectId: String, sessionId: String, atMs: Long = clock()): SessionWrite {
        val index = readOk(projectId) ?: return SessionWrite.Failed(UNREADABLE_WRITE)
        val meta = index.find(sessionId) ?: return SessionWrite.Failed("No such session: " + sessionId)
        if (atMs <= meta.updatedAtMs) return SessionWrite.Ok
        return save(projectId, index.upsert(meta.copy(updatedAtMs = atMs)))
    }

    fun markRead(projectId: String, sessionId: String, atMs: Long = clock()): SessionWrite {
        val index = readOk(projectId) ?: return SessionWrite.Failed(UNREADABLE_WRITE)
        val meta = index.find(sessionId) ?: return SessionWrite.Failed("No such session: " + sessionId)
        if (meta.lastReadAtMs >= atMs) return SessionWrite.Ok
        return save(projectId, index.upsert(meta.copy(lastReadAtMs = atMs)))
    }

    /**
     * Creates an independent session. The id is generated and opaque, never derived from the
     * title, so a later rename cannot move the transcript to a different file.
     */
    fun create(
        projectId: String,
        title: String? = null,
        nowMs: Long = clock(),
    ): SessionCreate {
        val index = readOk(projectId)
            ?: return SessionCreate.Failed(UNREADABLE_WRITE)
        // An id that was deliberately deleted must never be handed to a new session: the old
        // transcript may still be on disk, and the new session would open it as its own.
        val deletions = when (val d = readDeletions(projectId)) {
            is DeletionLogRead.Ok -> d.log
            is DeletionLogRead.Unreadable -> return SessionCreate.Failed(
                "No session was created: the deletion record could not be read (" + d.reason + ").",
            )
        }
        var meta = SessionMeta(
            id = freshId(index, deletions),
            title = title?.let { SessionTitles.normalise(it) } ?: SessionMeta.UNTITLED,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            archived = false,
            // A session the user just created and is looking at counts as read.
            lastReadAtMs = nowMs,
        )
        val invalid = SessionMetaRules.problems(meta)
        if (invalid.isNotEmpty()) return SessionCreate.Failed("Invalid session: " + invalid.joinToString(","))
        if (index.contains(meta.id) || deletions.contains(meta.id)) {
            // The id generator ran out of fresh ids. Refusing is the only honest answer: reusing
            // either an existing id or a deleted one would alias a conversation.
            return SessionCreate.Failed("Could not allocate a fresh session id. Nothing was created.")
        }
        val next = index.upsert(meta).withActive(meta.id)
        return when (val w = save(projectId, next)) {
            is SessionWrite.Ok -> SessionCreate.Created(meta, next)
            is SessionWrite.Failed -> SessionCreate.Failed(w.reason)
        }
    }

    /**
     * Rename. Only the title changes: the id, the transcript path and the timestamps are left
     * alone, because a rename is not conversation activity and must not reorder the list.
     */
    fun rename(projectId: String, sessionId: String, newTitle: String): SessionWrite {
        val index = readOk(projectId) ?: return SessionWrite.Failed(UNREADABLE_WRITE)
        val meta = index.find(sessionId) ?: return SessionWrite.Failed("No such session: " + sessionId)
        val title = SessionTitles.normalise(newTitle) ?: return SessionWrite.Failed("A session title cannot be blank.")
        if (title == meta.title) return SessionWrite.Ok
        return save(projectId, index.upsert(meta.copy(title = title)))
    }

    fun setArchived(projectId: String, sessionId: String, archived: Boolean): SessionWrite {
        val index = readOk(projectId) ?: return SessionWrite.Failed(UNREADABLE_WRITE)
        val meta = index.find(sessionId) ?: return SessionWrite.Failed("No such session: " + sessionId)
        if (meta.archived == archived) return SessionWrite.Ok
        return save(projectId, index.upsert(meta.copy(archived = archived)))
    }

    fun setActive(projectId: String, sessionId: String): SessionWrite {
        val index = readOk(projectId) ?: return SessionWrite.Failed(UNREADABLE_WRITE)
        if (!index.contains(sessionId)) return SessionWrite.Failed("No such session: " + sessionId)
        if (index.activeId == sessionId) return SessionWrite.Ok
        return save(projectId, index.withActive(sessionId))
    }

    /**
     * Deletes a session: RECORD first, metadata second, bytes third.
     *
     * The order is the whole point, and it is three steps rather than two because a two-step
     * delete has a hole. If the metadata write succeeds and the byte delete fails, the session
     * is gone from the list while its transcript is still on disk - and the next rebuild of a
     * damaged index turns that leftover file back into a session the user deleted on purpose.
     *
     * So the intent is made durable BEFORE anything is destroyed:
     *
     *  1. write the deletion record (bytesRemoved = false);
     *  2. remove the session from the index (a failure here withdraws the record again);
     *  3. delete the bytes, then upgrade the record to bytesRemoved = true.
     *
     * A crash between ANY two steps leaves a state that cannot resurrect: step 1 done means the
     * id is excluded from rebuilds; step 2 done means the list no longer names it; step 3 done
     * means there are no bytes left to rebuild from.
     */
    fun delete(projectId: String, sessionId: String): SessionDelete {
        val index = readOk(projectId) ?: return SessionDelete.Failed(UNREADABLE_WRITE)
        if (!index.contains(sessionId)) return SessionDelete.Failed("No such session: " + sessionId)

        val before = when (val d = readDeletions(projectId)) {
            is DeletionLogRead.Ok -> d.log
            is DeletionLogRead.Unreadable -> return SessionDelete.Failed(
                "Nothing was deleted: the deletion record could not be read (" + d.reason + ").",
            )
        }
        val now = clock()
        val marked = before.with(DeletionRecord(sessionId, now, bytesRemoved = false))
        when (val w = writeDeletions(projectId, marked)) {
            is SessionWrite.Failed -> return SessionDelete.Failed(
                "Nothing was deleted: the deletion could not be recorded (" + w.reason +
                    "). A deletion that is not recorded can come back after a rebuild.",
            )
            is SessionWrite.Ok -> Unit
        }

        val remaining = index.without(sessionId)
        val next = remaining.withActive(
            if (index.activeId == sessionId) remaining.resolvedActiveId else index.activeId,
        )
        when (val w = save(projectId, next)) {
            is SessionWrite.Failed -> {
                // The session is still in the list, so it must not stay excluded from rebuilds.
                val rollback = writeDeletions(projectId, before)
                val extra = if (rollback is SessionWrite.Failed) {
                    " The deletion marker could not be withdrawn (" + rollback.reason + ")."
                } else {
                    ""
                }
                return SessionDelete.Failed(
                    "Nothing was deleted: the session list could not be updated (" + w.reason + ")." + extra,
                )
            }
            is SessionWrite.Ok -> Unit
        }

        val sink = transcripts
        if (sink == null) {
            return SessionDelete.Deleted(
                transcriptRemoved = false,
                note = "The session was removed from the list and the deletion is recorded, but no " +
                    "transcript store is attached, so its file could not be removed. A rebuild will " +
                    "still keep it out of the session list.",
            )
        }
        val error = try {
            sink.deleteNow(projectId, sessionId)
        } catch (t: Throwable) {
            msg(t)
        }
        if (error == null) {
            // Upgrade the record, so it becomes prunable once the retention window passes.
            writeDeletions(projectId, marked.with(DeletionRecord(sessionId, now, bytesRemoved = true)))
            return SessionDelete.Deleted(transcriptRemoved = true, note = null)
        }
        return SessionDelete.Deleted(
            transcriptRemoved = false,
            note = "The session was removed from the list and the deletion is recorded, but its " +
                "transcript could not be deleted: " + error + " Nothing was discarded: the file is " +
                "still on disk and a rebuild will keep it out of the session list.",
        )
    }

    // ---------------------------------------------------------------- adoption

    /**
     * Makes sure the conversation the app is actually using has a record.
     *
     * Today the app always uses [SessionMeta.LEGACY_ID], so this is the upgrade path: one
     * metadata line is written, the title and dates are derived from the transcript that is
     * already on disk, and the transcript itself is not touched.
     */
    private fun adopt(projectId: String, index: SessionIndex, note: String?): SessionIndexRead {
        val unresolved = index.resolvedActiveId ?: SessionMeta.LEGACY_ID
        if (index.contains(unresolved)) {
            val fixedActive = if (index.activeId == null || index.activeId != index.resolvedActiveId) {
                index.withActive(index.resolvedActiveId)
            } else {
                index
            }
            return if (fixedActive === index) SessionIndexRead.Ok(index, note)
            else {
                when (val w = save(projectId, fixedActive)) {
                    is SessionWrite.Ok -> SessionIndexRead.Ok(fixedActive, note)
                    is SessionWrite.Failed -> SessionIndexRead.Ok(index, joinNotes(note, w.reason))
                }
            }
        }
        val meta = synthesise(projectId, unresolved, clock())
        val adopted = index.upsert(meta).withActive(meta.id)
        return when (val w = save(projectId, adopted)) {
            is SessionWrite.Ok -> SessionIndexRead.Ok(adopted, note)
            is SessionWrite.Failed -> SessionIndexRead.Ok(index, joinNotes(note, w.reason))
        }
    }

    /**
     * Builds a record for a conversation id that has no metadata: title from its first user
     * message, dates from its own messages. An empty or unreadable transcript still produces a
     * valid record (UNTITLED, now), because the SESSION exists even when its text does not.
     */
    private fun synthesise(projectId: String, conversationId: String, nowMs: Long): SessionMeta {
        var firstUser: String? = null
        var firstMs = 0L
        var lastMs = 0L
        val loaded: TranscriptLoad? = try {
            transcripts?.readNow(projectId, conversationId)
        } catch (_: Throwable) {
            null
        }
        val messages: List<StoredMessage> = loaded?.messages.orEmpty()
        for (m in messages) {
            if (firstMs == 0L && m.atMs > 0L) firstMs = m.atMs
            if (m.atMs > lastMs) lastMs = m.atMs
            if (firstUser == null && m.role == "user" && m.text.isNotBlank()) firstUser = m.text
        }
        val created = if (firstMs > 0L) firstMs else nowMs
        val updated = if (lastMs > 0L) lastMs else created
        return SessionMeta(
            id = conversationId,
            title = SessionTitles.derive(firstUser),
            createdAtMs = created,
            updatedAtMs = updated,
            archived = false,
            // An adopted conversation has already been looked at; otherwise every upgraded
            // project would light up as unread on first launch.
            lastReadAtMs = updated,
        )
    }

    // ---------------------------------------------------------------- helpers

    fun fileFor(projectId: String): File =
        File(File(root, TranscriptFiles.safeSegment(projectId)), FILE_NAME)

    private fun backupFor(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)

    private fun readOk(projectId: String): SessionIndex? =
        (read(projectId) as? SessionIndexRead.Ok)?.index

    private fun readOrNull(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (_: Throwable) {
        null
    }

    private fun freshId(index: SessionIndex, deletions: DeletionLog): String {
        var candidate = SessionIds.newId(random)
        var guard = 0
        while ((index.contains(candidate) || deletions.contains(candidate)) && guard < 1000) {
            candidate = SessionIds.newId(random)
            guard++
        }
        return candidate
    }

    private fun joinNotes(a: String?, b: String): String = if (a.isNullOrBlank()) b else a + " " + b

    private fun msg(t: Throwable): String = t.message ?: t.javaClass.simpleName
}

/** Result of creating a session. */
internal sealed interface SessionCreate {
    data class Created(val meta: SessionMeta, val index: SessionIndex) : SessionCreate
    data class Failed(val reason: String) : SessionCreate
}

/** Result of a metadata write. */
internal sealed interface SessionWrite {
    data object Ok : SessionWrite
    data class Failed(val reason: String) : SessionWrite
}

/** Result of a delete, which has TWO steps and therefore two possible half-successes. */
internal sealed interface SessionDelete {
    /**
     * @param transcriptRemoved false means the record is gone but the bytes remain, and
     *   [note] says why. Never reported as a clean success.
     */
    data class Deleted(val transcriptRemoved: Boolean, val note: String?) : SessionDelete
    data class Failed(val reason: String) : SessionDelete
}

/**
 * What the store needs from the transcript layer. Narrow on purpose: listing ids (recovery),
 * reading one conversation (title/date adoption), and deleting one (session deletion).
 *
 * A separate interface rather than the persist-only `TranscriptSink`, because `TranscriptSink`
 * is a suspend contract used by the send path and this one is deliberately synchronous.
 */
internal interface SessionTranscripts {
    /** Existing conversation ids for a project, ignoring backups and temp files. */
    fun ids(projectId: String): List<String>
    fun readNow(projectId: String, conversationId: String): TranscriptLoad
    /** Returns null when both the transcript and its backup are gone, else the error. */
    fun deleteNow(projectId: String, conversationId: String): String?
}

private const val UNREADABLE_WRITE =
    "The session list cannot be read, so nothing was changed. " +
        "Reopen the project to rebuild it from the transcripts on disk."
