package com.codespace.ide.chat.panel

import android.content.Context
import com.codespace.ide.agent.AutoInstructionsProvider
import com.codespace.ide.data.SessionStateStore
import com.codespace.ide.editor.EditorBufferStore
import com.codespace.ide.util.ProjectPathResolver
import java.io.File

/**
 * Batch 1b — the ANDROID half of workspace intelligence: resolve the real project,
 * read what is actually open, and cache the resulting block so a send never walks a
 * filesystem on the UI thread.
 *
 * Reuse, not reinvention: roots come from `ProjectPathResolver` (the same multi-root
 * list the Explorer and terminal use), the open file set comes from `SessionStateStore`
 * (the shell's own restore record), the CURRENT file body prefers `EditorBufferStore`
 * (unsaved edits) over disk, and instructions come from `AutoInstructionsProvider` (the
 * same provider the HTTP agent already uses, so both request paths agree).
 *
 * SECURITY POSTURE, unchanged and not bypassed:
 *  - paths are read-only here; every WRITE still goes through AgentTools behind
 *    AgentFlowGate, exactly as before;
 *  - a credential-shaped current file is never read and is marked withheld;
 *  - the tree skips VCS, build, dependency and dot directories and omits
 *    credential-shaped file names (see WorkspaceContext);
 *  - a missing root yields an honest UNRESOLVED block, never a guessed path.
 */
internal object WorkspaceContextSource {

    /** Above this, the "current file" is not read for imports: not a source file story. */
    private const val MAX_CURRENT_FILE_BYTES = 500_000L

    private data class Entry(val block: String, val atMs: Long)

    private val cache = HashMap<String, Entry>()

    /**
     * The cached block for [projectId]. Computes once when cold (bounded walk: max
     * depth 3, max 80 entries), otherwise a pure map read. Never throws.
     */
    @Synchronized
    fun block(context: Context?, projectId: String): String {
        if (context == null || projectId.isBlank()) return ""
        cache[projectId]?.let { return it.block }
        val block = try { computeBlock(context, projectId) } catch (_: Exception) { "" }
        cache[projectId] = Entry(block, System.currentTimeMillis())
        return block
    }

    /**
     * Recompute and swap the cached block. Call from a background dispatcher; the send
     * path never calls this, so a slow filesystem can never stall a message.
     */
    fun warm(context: Context?, projectId: String) {
        if (context == null || projectId.isBlank()) return
        val fresh = try { computeBlock(context, projectId) } catch (_: Exception) { return }
        synchronized(this) { cache[projectId] = Entry(fresh, System.currentTimeMillis()) }
    }

    @Synchronized
    fun invalidate(projectId: String) {
        cache.remove(projectId)
    }

    @Synchronized
    internal fun cachedAtMs(projectId: String): Long? = cache[projectId]?.atMs

    /** Resolve the workspace, then build the block. Pure IO, no state beyond reads. */
    internal fun computeBlock(context: Context, projectId: String): String {
        val snap = collect(context, projectId)
        return WorkspaceContext.build(snap)
    }

    /**
     * Collect the snapshot the builder renders. Public-in-module so a test (or a future
     * caller on another surface) can inspect the facts without re-deriving them.
     */
    internal fun collect(context: Context, projectId: String): WorkspaceSnapshot {
        val roots = try {
            ProjectPathResolver.getAllWorkspaceRoots(context, projectId)
        } catch (_: Exception) { emptyList() }
        val active = try {
            ProjectPathResolver.resolveProjectRoot(context, projectId)
                ?.takeIf { File(it).exists() }
                ?: roots.firstOrNull()
        } catch (_: Exception) { roots.firstOrNull() }

        // Active root first, so the block reads the way the shell behaves.
        val orderedRoots = if (active != null) listOf(active) + roots.filter { it != active } else roots

        val shell = try { SessionStateStore(context).loadShellState(projectId) } catch (_: Exception) { null }
        val currentFile = shell?.activeFilePath?.takeIf { it.isNotBlank() }
        val openFiles = shell?.openFilePaths ?: emptyList()

        // Contents: live buffer first (unsaved edits are the truth the user sees), then
        // disk. Withheld for credential-shaped names; null when unreadable.
        var withheld = false
        var text: String? = null
        if (currentFile != null) {
            val name = File(currentFile).name
            if (WorkspaceContext.isSensitiveName(name)) {
                withheld = true
            } else {
                text = try {
                    EditorBufferStore.contentOf(currentFile) ?: run {
                        val f = File(currentFile)
                        if (f.isFile && f.canRead() && f.length() <= MAX_CURRENT_FILE_BYTES) f.readText() else null
                    }
                } catch (_: Exception) { null }
            }
        }

        val instructions = try {
            if (active != null && AutoInstructionsProvider.isEnabled(context, active))
                AutoInstructionsProvider.buildBlock(active) else ""
        } catch (_: Exception) { "" }

        return WorkspaceSnapshot(
            projectId = projectId,
            activeRoot = active,
            roots = orderedRoots,
            currentFile = currentFile,
            currentFileText = text,
            currentFileWithheld = withheld,
            openFiles = openFiles,
            instructionsBlock = instructions,
        )
    }
}
