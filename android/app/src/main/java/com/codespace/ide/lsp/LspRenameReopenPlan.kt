package com.codespace.ide.lsp

/**
 * TB06-R PROTOCOL PLAN (advisor round 2026-10-19): how an OPEN tab re-syncs
 * its LSP server after a file rename.
 *
 * THE GAP (READ evidence, EditorPaneTabEffects TB06 + ProjectShellScreen
 * onFileRenamed): a rename sent ONLY the workspace/didRenameFiles notification
 * (shell side) and rekeyed every CLIENT-side map — but sent NO text-document
 * protocol traffic for the open document. The server keeps the OLD uri open in
 * its tracked set, and every later didChange(newUri) references a document the
 * server never didOpen'd — most servers ignore or drop it, so edits stop
 * syncing and diagnostics freeze at the pre-rename content under the old uri.
 *
 * VS CODE PARITY: on rename the old document CLOSES and the new one OPENS.
 * The server-side trackedDocuments registry is authoritative (BATCH9-P1a
 * hoisted open-doc registry) — didClose(oldUri) untracks, didOpen(newUri)
 * tracks, so the registry and reality agree again.
 *
 * ORDER (pinned by LspRenameReopenPlanTest): close-old BEFORE open-new, both
 * AFTER the workspace didRenameFiles the shell already sent. didOpen carries
 * the CURRENT tab content, so no content drift is possible across the rename.
 */
object LspRenameReopenPlan {

    sealed class Step {
        /** textDocument/didClose for the OLD uri. */
        object CloseOld : Step()
        /** textDocument/didOpen for the NEW uri with the current content. */
        object OpenNew : Step()
    }

    /**
     * The ordered protocol steps for a rename.
     *
     * @param tabWasOpen  the renamed file had an OPEN tab (the effect knows)
     * @param oldPath     the pre-rename path
     * @param newPath     the post-rename path
     */
    fun stepsFor(tabWasOpen: Boolean, oldPath: String, newPath: String): List<Step> {
        if (!tabWasOpen) return emptyList()      // no open doc — nothing to re-sync
        if (oldPath == newPath) return emptyList() // same-path rename: identity
        return listOf(Step.CloseOld, Step.OpenNew)
    }
}
