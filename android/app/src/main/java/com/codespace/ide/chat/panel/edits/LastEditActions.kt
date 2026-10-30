package com.codespace.ide.chat.panel.edits

import com.codespace.ide.agent.AgentWriteJournal
import com.codespace.ide.chat.PendingChangesStore
import java.io.File

/**
 * M3 — "Undo Last Edit" and "Redo Last Edit" as ONE session-scoped action.
 *
 * VN Code has two independent AI-write paths and both count as "the last edit":
 *
 *  1. tool writes in Agent mode, recorded by [AgentWriteJournal] as a change set with the
 *     content before AND after each file, attributed to the conversation that made it;
 *  2. staged proposals in Edit mode, applied through [PendingChangesStore], which keeps a
 *     checkpoint per file but forgets the applied content once it is undone.
 *
 * This coordinator picks the newest of the two that this conversation actually owns, calls the
 * existing implementation UNCHANGED (so its conflict and verify-by-re-read rules are preserved),
 * and for the staged path captures the applied bytes first so redo is possible there too.
 *
 * The cross-session rule is absolute: every lookup takes the conversation id, and a set that
 * belongs to another conversation is never returned, so an edit from session A can never be
 * undone from session B.
 */
internal object LastEditActions {

    private const val MAX_REDO_BYTES = 512 * 1024

    private data class StagedFile(val path: String, val applied: ByteArray?, val restored: ByteArray?)

    /** Captured at undo time for the staged path; consumed by the next redo. */
    private var stagedRedo: List<StagedFile>? = null

    fun canUndo(conversationId: String): Boolean =
        AgentWriteJournal.latestUnsettled(conversationId) != null || PendingChangesStore.hasAppliedBatch()

    fun canRedo(conversationId: String): Boolean =
        AgentWriteJournal.latestUndone(conversationId) != null || stagedRedo != null

    /** @return the message to show the user. Never claims success it did not verify. */
    fun undo(conversationId: String): String {
        AgentWriteJournal.latestUnsettled(conversationId)?.let { set ->
            return AgentWriteJournal.undo(set.id, conversationId)
        }
        if (PendingChangesStore.hasAppliedBatch()) return undoStaged()
        return NO_EDIT
    }

    fun redo(conversationId: String): String {
        AgentWriteJournal.latestUndone(conversationId)?.let { set ->
            return AgentWriteJournal.redo(set.id, conversationId)
        }
        stagedRedo?.let { return redoStaged(it) }
        return NO_UNDONE_EDIT
    }

    const val NO_EDIT: String = "No AI edit to undo in this session."
    const val NO_UNDONE_EDIT: String = "No undone AI edit to redo in this session."

    // ---------------------------------------------------------------- staged path

    private fun undoStaged(): String {
        // Snapshot the APPLIED content before the store restores its checkpoints: without it,
        // redo would have nothing to put back.
        val snapshot = ArrayList<StagedFile>(4)
        var overflow = 0
        for ((path, _) in PendingChangesStore.appliedBatch()) {
            val file = File(path)
            val applied = readCapped(file)
            if (applied == NOT_CAPTURED) {
                overflow++
                continue
            }
            snapshot.add(StagedFile(path, applied, null))
        }
        val note = PendingChangesStore.undoLastApply()
        // Record what the undo actually left on disk, per file, so redo can refuse to clobber
        // anything that changed in between.
        stagedRedo = snapshot.map { it.copy(restored = readCapped(File(it.path))) }
        return if (overflow > 0) note + " (" + overflow + " file(s) too large to redo)" else note
    }

    private fun redoStaged(files: List<StagedFile>): String {
        var applied = 0
        val problems = ArrayList<String>()
        for (item in files) {
            val file = File(item.path)
            if (!sameBytes(file, item.restored)) {
                problems.add(file.name + ": changed since the undo")
                continue
            }
            try {
                if (item.applied == null) {
                    if (file.exists() && !file.delete()) {
                        problems.add(file.name + ": could not delete")
                        continue
                    }
                } else {
                    file.parentFile?.mkdirs()
                    file.writeBytes(item.applied)
                    if (!sameBytes(file, item.applied)) {
                        problems.add(file.name + ": re-apply did not stick")
                        continue
                    }
                }
                applied++
                com.codespace.ide.editor.FileCache.invalidate(item.path)
            } catch (t: Throwable) {
                problems.add(file.name + ": " + (t.message ?: "failed"))
            }
        }
        if (problems.isEmpty()) {
            stagedRedo = null
            return "Re-applied " + applied + " staged file" + (if (applied == 1) "" else "s")
        }
        return if (applied == 0) "Could not re-apply: " + problems.joinToString("; ")
        else "Re-applied " + applied + "; left alone: " + problems.joinToString("; ")
    }

    private val NOT_CAPTURED = ByteArray(0)

    private fun readCapped(file: File): ByteArray? {
        return try {
            if (!file.exists()) null
            else if (file.length() > MAX_REDO_BYTES) NOT_CAPTURED
            else file.readBytes()
        } catch (_: Throwable) {
            NOT_CAPTURED
        }
    }

    private fun sameBytes(file: File, expected: ByteArray?): Boolean {
        val current = if (file.exists()) {
            try {
                file.readBytes()
            } catch (_: Throwable) {
                return false
            }
        } else null
        if (current == null || expected == null) return current == null && expected == null
        if (current.size != expected.size) return false
        return current.contentEquals(expected)
    }

    /** Test seam: forget a captured redo snapshot between cases. */
    internal fun resetForTest() {
        stagedRedo = null
    }
}
