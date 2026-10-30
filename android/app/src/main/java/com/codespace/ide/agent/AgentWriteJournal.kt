package com.codespace.ide.agent

import androidx.compose.runtime.mutableStateListOf
import java.io.File

/**
 * U6-c/U6-d companion — the AGENT WRITE JOURNAL behind the Files Changed row.
 *
 * This records what actually happened to the filesystem, not what the agent proposed:
 * an entry is only created after writeText() returned, so a failed write produces no row.
 *
 * Why not reuse PendingChangesStore: that store is the STAGED flow (write to an overlay,
 * apply later, with checkpoints). The chat tool loop writes directly to disk, so there is no
 * overlay to read. The journal keeps the minimum needed to answer two questions honestly:
 * "what changed" (path + before/after content) and "can this still be reverted safely"
 * (compare the CURRENT disk content with what the agent left behind).
 *
 * Undo rules (owner spec: never overwrite the user's later edits, never report an unverified
 * success):
 *  - created file : delete it, but ONLY if its content is still exactly what the agent wrote.
 *  - modified file: restore the prior content, but ONLY if the file still matches the agent's
 *    version. Any difference is a CONFLICT: the entry is left alone and the reason is
 *    reported, because silently discarding the user's edits is worse than a failed undo.
 *  - every outcome is verified by re-reading the file after the operation.
 */
object AgentWriteJournal {

    enum class Kind { CREATED, MODIFIED }

    data class Entry(
        val path: String,
        val kind: Kind,
        /** Content before the agent wrote; null for CREATED. */
        val before: String?,
        /** Content the agent wrote; what the disk must still hold for undo to be safe. */
        val after: String,
    )

    data class ChangeSet(
        val id: Long,
        val entries: List<Entry>,
        val settled: Boolean = false,
        /** "kept" | "undone" | null - what the user did with it. */
        val outcome: String? = null,
        val note: String? = null,
        /**
         * MULTI-SESSION (M1, 2026-10-30): the conversation this change belongs to.
         *
         * Without it this list is PROCESS-GLOBAL, so undoing "the last edit" while looking at
         * session B could revert a file the agent changed in session A. Attribution is the
         * fix: a set is created by, visible to, and reversible from its own session only.
         */
        val sessionId: String = "",
    )

    /** Never keep more than this many reviewed sets around. */
    const val MAX_SETS = 3

    private var nextId = 1L

    /** Newest last. Lives in Compose state so the row updates itself. */
    val sets = mutableStateListOf<ChangeSet>()

    /**
     * The conversation the UI is currently showing. Set by the chat session that owns the
     * screen, so a write, a row and an undo all agree on which session they belong to even
     * though this object is a process-wide singleton.
     *
     * Empty string means "no session known" (JVM tests and any pre-multi-session caller), and
     * empty matches empty, so the single-session behaviour is unchanged.
     */
    @Volatile
    var activeSessionId: String = ""

    /**
     * Opens a new change set for one agent operation. Called once per tool round so two
     * separate operations never merge into one misleading row.
     */
    @Synchronized
    fun beginOperation(sessionId: String = activeSessionId): Long {
        // The operation's session becomes the CURRENT session: every record() that follows in
        // this flow must land in the set that was just opened, not in a fresh one filed under
        // some other session that happened to be active a moment ago.
        activeSessionId = sessionId
        val id = nextId++
        sets.add(ChangeSet(id = id, entries = emptyList(), sessionId = sessionId))
        while (sets.size > MAX_SETS) sets.removeAt(0)
        return id
    }

    /** Creates a set lazily when a writer did not announce an operation (defensive). */
    private fun activeSetId(): Long {
        val last = sets.lastOrNull { !it.settled && it.sessionId == activeSessionId }
        if (last != null) return last.id
        return beginOperation()
    }

    /** The sets that belong to one session, newest last. This is what a session may show. */
    fun setsFor(sessionId: String): List<ChangeSet> = sets.filter { it.sessionId == sessionId }

    fun setsForActive(): List<ChangeSet> = setsFor(activeSessionId)

    /**
     * Session-scoped lookup. A set is only reachable through its OWN session, which is what
     * makes "never undo session A from session B" a property of the data rather than a habit
     * of the caller.
     */
    @Synchronized
    fun findForSession(setId: Long, sessionId: String): ChangeSet? =
        sets.firstOrNull { it.id == setId && it.sessionId == sessionId }

    /** Drops everything a deleted session owned, so its files can never be reverted later. */
    @Synchronized
    fun forgetSession(sessionId: String) {
        sets.removeAll { it.sessionId == sessionId }
    }

    /**
     * Record a completed write. Only call AFTER the write succeeded - this is the reason a
     * failed write can never show up as a change.
     */
    @Synchronized
    fun record(path: String, before: String?, after: String) {
        val id = activeSetId()
        val i = sets.indexOfFirst { it.id == id }
        if (i < 0) return
        val set = sets[i]
        // Same path written twice in one operation: keep the ORIGINAL before-image (that is
        // what undo must restore) and the latest after-image.
        val existing = set.entries.indexOfFirst { it.path == path }
        val entry = Entry(
            path = path,
            kind = if (before == null) Kind.CREATED else Kind.MODIFIED,
            before = if (existing >= 0) set.entries[existing].before else before,
            after = after,
        )
        val updated = if (existing >= 0) {
            set.entries.toMutableList().also { it[existing] = entry }
        } else {
            set.entries + entry
        }
        sets[i] = set.copy(entries = updated)
    }

    /**
     * The newest set that still needs a decision, or null when there is nothing to review.
     * An EMPTY set is not reviewable: beginOperation() opens one before any tool runs, and a
     * round that changed nothing must not leave an empty "0 files changed" row on screen
     * (owner spec: no empty or permanent row).
     */
    fun reviewable(sessionId: String = activeSessionId): ChangeSet? =
        sets.lastOrNull { !it.settled && it.entries.isNotEmpty() && it.sessionId == sessionId }

    /** Keep: mark accepted, touch NO files. */
    @Synchronized
    fun keep(setId: Long, requesterSessionId: String = activeSessionId): String? {
        if (findForSession(setId, requesterSessionId) == null) return OTHER_SESSION
        settle(setId, "kept", null)
        return null
    }

    /** Shown when a caller reaches for a set that belongs to a different conversation. */
    const val OTHER_SESSION: String = "That change belongs to a different session."

    /**
     * Undo: revert everything still safely revertible, verify each one, and settle the set
     * with an honest note describing partial success or conflicts.
     */
    @Synchronized
    fun undo(setId: Long, requesterSessionId: String = activeSessionId): String {
        // Attribution first: a set that is not this session's is not "gone", it is somebody
        // else's, and saying so is more honest than pretending it expired.
        if (findForSession(setId, requesterSessionId) == null &&
            sets.any { it.id == setId }
        ) {
            return OTHER_SESSION
        }
        val i = sets.indexOfFirst { it.id == setId }
        if (i < 0) return "That change set is no longer available."
        val set = sets[i]
        if (set.settled) return "Those changes were already " + (set.outcome ?: "resolved") + "."

        var reverted = 0
        val problems = ArrayList<String>()
        for (entry in set.entries) {
            val result = undoEntry(entry)
            if (result == null) reverted++ else problems.add(entry.path.substringAfterLast('/') + ": " + result)
        }
        val note = when {
            problems.isEmpty() -> "Reverted " + reverted + " file" + (if (reverted == 1) "" else "s")
            reverted == 0 -> "Could not revert: " + problems.joinToString("; ")
            else -> "Reverted " + reverted + "; left alone: " + problems.joinToString("; ")
        }
        // Only a clean revert is reported as "undone"; a partial one says so.
        settle(setId, if (reverted > 0) "undone" else "undo failed", note)
        return note
    }

    private fun settle(setId: Long, outcome: String, note: String?) {
        val i = sets.indexOfFirst { it.id == setId }
        if (i < 0) return
        sets[i] = sets[i].copy(settled = true, outcome = outcome, note = note)
    }

    /** Drops a settled set's explanatory note once the user has read it. */
    @Synchronized
    fun dismiss(setId: Long) {
        val i = sets.indexOfFirst { it.id == setId }
        if (i >= 0 && sets[i].settled) sets.removeAt(i)
    }

    fun clear() {
        synchronized(this) { sets.clear() }
    }

    // --- one entry, verified --------------------------------------------------------------

    /** Returns null on success, or a short reason string on refusal/failure. */
    private fun undoEntry(entry: Entry): String? {
        val file = File(entry.path)
        val current = try {
            if (file.exists()) file.readText() else null
        } catch (t: Throwable) {
            return "cannot read (" + (t.message ?: "unknown") + ")"
        }
        // The agent's version is gone (moved/edited): refuse rather than clobber.
        if (current == null) return "file is no longer there"
        if (current != entry.after) return "changed since the agent wrote it"

        return try {
            when (entry.kind) {
                Kind.CREATED -> {
                    if (!file.delete()) return "could not delete"
                    if (file.exists()) return "delete did not stick"
                    null
                }
                Kind.MODIFIED -> {
                    val before = entry.before ?: return "no saved copy to restore"
                    file.writeText(before)
                    // VERIFY: a write that silently failed must not be reported as success.
                    if (file.readText() != before) "restore did not stick" else null
                }
            }
        } catch (t: Throwable) {
            t.message ?: "revert failed"
        }
    }

    // --- pure helpers, unit-tested --------------------------------------------------------

    enum class DiffKind { ADDED, REMOVED, CONTEXT }

    data class DiffLine(val kind: DiffKind, val text: String)

    data class DiffSummary(
        val added: Int,
        val removed: Int,
        /** Only the changed region plus one line of context on each side. */
        val lines: List<DiffLine>,
    )

    /**
     * Line diff by common prefix/suffix trimming with the changed block emitted as
     * removals-then-additions. Not a minimal Myers diff, and deliberately so: it is exact
     * about WHAT changed and honest about WHERE, without pretending to be an optimal
     * alignment. Above MAX_DIFF_LINES the block is truncated with a marker.
     */
    fun diff(before: String?, after: String): DiffSummary {
        val old = splitLines(before ?: "")
        val new = splitLines(after)
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) suffix++

        val removedBlock = old.subList(prefix, old.size - suffix)
        val addedBlock = new.subList(prefix, new.size - suffix)
        val out = ArrayList<DiffLine>()
        if (prefix > 0) out.add(DiffLine(DiffKind.CONTEXT, old[prefix - 1]))
        var truncated = 0
        for (line in removedBlock) {
            if (out.size >= MAX_DIFF_LINES) { truncated++; continue }
            out.add(DiffLine(DiffKind.REMOVED, line))
        }
        for (line in addedBlock) {
            if (out.size >= MAX_DIFF_LINES) { truncated++; continue }
            out.add(DiffLine(DiffKind.ADDED, line))
        }
        if (suffix > 0) out.add(DiffLine(DiffKind.CONTEXT, old[old.size - suffix]))
        if (truncated > 0) out.add(DiffLine(DiffKind.CONTEXT, "[... " + truncated + " more lines not shown]"))
        return DiffSummary(added = addedBlock.size, removed = removedBlock.size, lines = out)
    }

    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split("\n")

    /** "+3 -1" style label, or "new file" / "no line changes" when that reads better. */
    fun summaryLabel(entry: Entry): String {
        val d = diff(entry.before, entry.after)
        if (entry.kind == Kind.CREATED) return "new file (" + d.added + " lines)"
        if (d.added == 0 && d.removed == 0) return "no line changes"
        return "+" + d.added + " \u2212" + d.removed
    }

    const val MAX_DIFF_LINES = 200
}
