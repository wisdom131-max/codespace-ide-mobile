package com.codespace.ide.ui.screens

import android.content.Context
import com.codespace.ide.domain.Project
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.util.WorkspaceRootsStore

/**
 * [REPO-OPEN] Part 2 item 4 — GitHub repo browsing fix.
 *
 * A repo cloned from the Source Control pane is appended to the CURRENT
 * project's workspace roots (multi-root add, NOT a full project switch —
 * decision approved 2026-09-05) and announced to running LSP servers via the
 * existing workspace/didChangeWorkspaceFolders path.
 *
 * WorkspaceRootsStore is reactive — the Explorer recomposes automatically on
 * the addRoot write (VS Code parity). The caller shows a notification based
 * on the returned value.
 *
 * Returns true when the root was newly added, false when it already existed.
 */
fun handleRepoClonedAddRoot(context: Context, projectId: String, project: Project): Boolean {
    val added = WorkspaceRootsStore.addRoot(context, projectId, project.pathOrUrl)
    if (added) {
        LspManager.notifyWorkspaceFoldersChanged(
            context,
            addedHostRoots = listOf(project.pathOrUrl),
        )
    }
    return added
}
