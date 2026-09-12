package com.codespace.ide.chat

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * R6-PENDING-EDITS: per-session staging buffer for AGENT-mode `write_file`.
 *
 * The agent's file edits NEVER touch disk directly — `write_file` stages into
 * this store (UNGATED at every permission level: content that cannot reach
 * disk does not need an approval gate; run_command keeps its level gating).
 * Only the user-initiated Apply writes to disk. Reads overlay staged content
 * so the model keeps iterating on its own edits (read-through overlay).
 *
 * Locked decisions (R6_PREPLAN.md v2, 2026-09-12):
 *  1. Staging ungated at all levels.
 *  2. ONE SnapshotUndoManager entry per apply (VS Code SingleModelEditStackElement
 *     precedent) + checkpoint restore for disk-level undo.
 *  3. Buffer-centric, dialog-free when the file is open. Drift check applies
 *     ONLY to disk-staged entries (§4.5 acknowledged divergence from VS Code).
 *  4. Per-session in-memory buffer; loss on session close/kill is BY DESIGN.
 *  5. 1MB checkpoint cap + diff budget (~2M LCS cells) on the review UI.
 *
 * FAIL-CLOSED DRIFT RULE (owner-mandated): if the drift verification itself
 * errors or cannot determine whether disk changed (unreadable file, IO error,
 * ambiguous state), the apply is BLOCKED and surfaced for a manual decision —
 * it NEVER falls through to a silent write. Only two outcomes can proceed:
 * verified-no-drift and verified-drift (which shows the conflict UI).
 */
object PendingChangesStore {

    enum class Status { PENDING, DRIFT, BLOCKED }

    data class PendingChange(
        val path: String,
        val newContent: String,
        val baseContent: String,
        val baseWasOpenBuffer: Boolean,
        val stagedAt: Long,
        val sessionId: String,
        val status: Status = Status.PENDING,
        val statusNote: String = "",
    ) {
        val isNewFile: Boolean get() = baseContent.isEmpty()
    }

    /** Result of an apply attempt — the card renders each variant. */
    sealed interface ApplyOutcome {
        data class Applied(val path: String, val checkpointFile: String?) : ApplyOutcome
        data class Drift(val path: String, val currentDiskContent: String) : ApplyOutcome
        data class Blocked(val path: String, val reason: String) : ApplyOutcome
        data class NotFound(val path: String) : ApplyOutcome
        data class Failed(val path: String, val message: String) : ApplyOutcome
    }

    // ── Session scope (set by the chat panel; staging is a no-op without one) ──
    @Volatile var activeSessionId: String? = null
    @Volatile var activeProjectRoot: String? = null

    /** Bumped on every store mutation — the panel reads it in composition. */
    val revision = androidx.compose.runtime.mutableStateOf(0)

    /**
     * Bumped after every apply batch / checkpoint restore — EditorPane observes
     * it to refresh open tabs of applied paths (content-param change drives the
     * existing externalContentSync cursor-mapped replacement path).
     */
    val appliedTick = androidx.compose.runtime.mutableStateOf(0)

    /**
     * Paths whose NEXT content-param sync must push ONE undo snapshot (decision #2).
     * CodeEditor consumes via consumeUndoGate() in its content LaunchedEffect.
     */
    private val undoGatePaths = mutableSetOf<String>()

    /** (path, checkpointFile) of the most recent apply batch — one-tap restore. */
    val lastApplied = androidx.compose.runtime.mutableStateOf<List<Pair<String, String?>>>(emptyList())

    private val pending = linkedMapOf<String, PendingChange>()

    // ── Staging (called from the agentic tool loop, IO thread) ──────────────

    /**
     * Stage a write_file proposal. Base = the open editor buffer when the file
     * is open (decision #3 buffer-centric), else current disk content.
     * Returns the tool result string for the model.
     */
    fun stage(path: String, newContent: String): String {
        val session = activeSessionId ?: "default"
        val (base, wasOpen) = try {
            val buf = com.codespace.ide.editor.EditorBufferStore.contentOf(path)
            if (buf != null) buf to true
            else (if (File(path).exists()) File(path).readText() else "") to false
        } catch (_: Exception) {
            // Unreadable base: stage with a note rather than failing — the
            // apply-time drift check will still fail closed before any write.
            "" to false
        }
        synchronized(pending) {
            pending[path] = PendingChange(
                path = path,
                newContent = newContent,
                baseContent = base,
                baseWasOpenBuffer = wasOpen,
                stagedAt = System.currentTimeMillis(),
                sessionId = session,
            )
        }
        bumpRevision()
        return "staged: $path (${newContent.length} chars, pending review — the user must Apply before it reaches disk; later reads of this file return your staged version)"
    }

    /** Read-through overlay: the staged version of a path, or null. */
    fun overlayFor(path: String): String? =
        synchronized(pending) { pending[path]?.newContent }

    /** True when the path has a staged entry (searchFiles overlay uses this). */
    fun hasPending(path: String): Boolean =
        synchronized(pending) { pending.containsKey(path) }

    fun pendingFor(sessionId: String): List<PendingChange> =
        synchronized(pending) { pending.values.filter { it.sessionId == sessionId } }

    fun pendingCount(sessionId: String): Int =
        synchronized(pending) { pending.values.count { it.sessionId == sessionId } }

    fun discard(path: String) {
        synchronized(pending) { pending.remove(path) }
        bumpRevision()
    }

    fun discardAll(sessionId: String) {
        synchronized(pending) { pending.values.removeAll { it.sessionId == sessionId } }
        bumpRevision()
    }

    // ── Apply (user-initiated, from the review card) ─────────────────────────

    /**
     * Apply one staged entry to disk. FAIL CLOSED: any error while verifying
     * or writing blocks the apply and returns Blocked/Failed — never a silent
     * partial write. Only verified-no-drift and verified-drift-conflict are
     * distinguishable states; everything indeterminate is Blocked.
     */
    fun apply(path: String): ApplyOutcome {
        val entry = synchronized(pending) { pending[path] } ?: return ApplyOutcome.NotFound(path)
        return try {
            // ── Drift verification (disk-staged entries ONLY — decision #3) ──
            if (!entry.baseWasOpenBuffer) {
                val diskNow = try {
                    File(path).readText()
                } catch (e: Exception) {
                    // FAIL CLOSED: cannot verify -> block, surface, no write.
                    markBlocked(path, "Could not read the file on disk to verify changes (${e.message})")
                    return ApplyOutcome.Blocked(path, "Could not read the file on disk to verify changes: ${e.message}")
                }
                if (diskNow != entry.baseContent) {
                    markDrift(path, diskNow)
                    return ApplyOutcome.Drift(path, diskNow)
                }
            }
            // ── Checkpoint the pre-apply disk content (decision #5, 1MB cap) ──
            val checkpointFile = try { writeCheckpoint(path) } catch (_: Exception) { null }
            // ── Write to disk: temp file + atomic-ish rename ────────────────
            val target = File(path)
            target.parentFile?.mkdirs()
            val tmp = File(path + ".chatapply.tmp")
            try {
                tmp.writeText(entry.newContent)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return ApplyOutcome.Failed(path, "Could not replace the file on disk (rename failed)")
                }
            } catch (e: Exception) {
                tmp.delete()
                return ApplyOutcome.Failed(path, "Write failed: ${e.message}")
            }
            // ── Success bookkeeping ──────────────────────────────────────────
            synchronized(pending) { pending.remove(path) }
            synchronized(undoGatePaths) { undoGatePaths.add(path) }
            lastApplied.value = lastApplied.value + (path to checkpointFile)
            appliedTick.value++
            bumpRevision()
            ApplyOutcome.Applied(path, checkpointFile)
        } catch (e: Exception) {
            // FAIL CLOSED catch-all: indeterminate state never writes silently.
            ApplyOutcome.Failed(path, "Apply blocked — verification error: ${e.message}")
        }
    }

    /** Apply all pending for a session; STOPS at the first Drift/Blocked/Failed. */
    fun applyAll(sessionId: String): List<ApplyOutcome> {
        val entries = pendingFor(sessionId)
        val outcomes = mutableListOf<ApplyOutcome>()
        for (e in entries) {
            val out = apply(e.path)
            outcomes.add(out)
            if (out !is ApplyOutcome.Applied) break
        }
        bumpRevision()
        return outcomes
    }

    /** After a DRIFT conflict: rebase the entry's base to current disk and re-diff. */
    fun rediff(path: String) {
        synchronized(pending) {
            val e = pending[path] ?: return
            pending[path] = e.copy(baseContent = try { File(path).readText() } catch (_: Exception) { e.baseContent }, status = Status.PENDING, statusNote = "")
        }
        bumpRevision()
    }

    /**
     * Owner forced an apply past a Blocked/Drift state — explicit manual decision.
     * Bypasses the drift verification entirely (that is the point: the user
     * CHOSE to overwrite whatever is on disk). Still checkpoints first.
     */
    fun forceApply(path: String): ApplyOutcome {
        val entry = synchronized(pending) { pending[path] } ?: return ApplyOutcome.NotFound(path)
        return try {
            val checkpointFile = try { writeCheckpoint(path) } catch (_: Exception) { null }
            val target = File(path)
            target.parentFile?.mkdirs()
            val tmp = File(path + ".chatapply.tmp")
            try {
                tmp.writeText(entry.newContent)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    return ApplyOutcome.Failed(path, "Could not replace the file on disk (rename failed)")
                }
            } catch (e: Exception) {
                tmp.delete()
                return ApplyOutcome.Failed(path, "Write failed: ${e.message}")
            }
            synchronized(pending) { pending.remove(path) }
            synchronized(undoGatePaths) { undoGatePaths.add(path) }
            lastApplied.value = lastApplied.value + (path to checkpointFile)
            appliedTick.value++
            bumpRevision()
            ApplyOutcome.Applied(path, checkpointFile)
        } catch (e: Exception) {
            ApplyOutcome.Failed(path, "Force apply failed: ${e.message}")
        }
    }

    // ── Checkpoints (reuses the existing .versionhistory infra format) ───────

    private fun writeCheckpoint(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        if (f.length() > 1_048_576L) return null // decision #5: 1MB cap
        val root = activeProjectRoot ?: return null
        val vhDir = File(File(root, ".versionhistory"), f.name)
        vhDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val bak = File(vhDir, "${stamp}_prechat.bak")
        f.copyTo(bak, overwrite = true)
        // Same retention as the ExplorerPane 20s loop: keep newest 20 per file.
        vhDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        return bak.absolutePath
    }

    /**
     * One-tap restore of the most recent apply batch (disk-level undo, decision #2).
     * Copies each checkpoint back over the file and bumps appliedTick so open
     * editors refresh through the same externalContentSync path.
     */
    fun undoLastApply(): String {
        val batch = lastApplied.value
        if (batch.isEmpty()) return "Nothing to undo"
        var restored = 0
        for ((path, bakPath) in batch) {
            val bak = bakPath?.let { File(it) }
            if (bak != null && bak.exists()) {
                try {
                    File(path).parentFile?.mkdirs()
                    bak.copyTo(File(path), overwrite = true)
                    restored++
                } catch (_: Exception) { }
            }
        }
        lastApplied.value = emptyList()
        appliedTick.value++
        bumpRevision()
        return if (restored > 0) "Restored $restored file(s) to pre-chat state" else "No checkpoints found to restore"
    }

    // ── Editor integration ──────────────────────────────────────────────────

    /** EditorPane reads this on every appliedTick bump to refresh open tabs. */
    fun lastAppliedPaths(): List<String> = lastApplied.value.map { it.first }

    /**
     * Decision #2: CodeEditor calls this when the content param changes; when it
     * returns true the editor pushes ONE force undo snapshot (pre-apply state)
     * before externalContentSync replaces the text — one toolbar-undo steps
     * back the whole chat apply (VS Code SingleModelEditStackElement precedent).
     */
    fun consumeUndoGate(path: String?): Boolean {
        if (path == null) return false
        val wasGated = synchronized(undoGatePaths) { undoGatePaths.remove(path) }
        if (wasGated) bumpRevision()
        return wasGated
    }

    private fun markBlocked(path: String, note: String) {
        synchronized(pending) {
            pending[path]?.let { pending[path] = it.copy(status = Status.BLOCKED, statusNote = note) }
        }
        bumpRevision()
    }

    private fun markDrift(path: String, currentDisk: String) {
        synchronized(pending) {
            pending[path]?.let { pending[path] = it.copy(status = Status.DRIFT, statusNote = "File changed on disk after this edit was staged", baseContent = currentDisk) }
        }
        bumpRevision()
    }

    private fun bumpRevision() { revision.value++ }
}
