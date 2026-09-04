package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.lsp.LspManager

/**
 * MULTI-ROOT (Part B): the SINGLE shared tab-close routine, extracted verbatim
 * from the tab-strip X-button handler in EditorPane so both close paths reuse
 * exactly one code path:
 *
 *   1. LSP textDocument/didClose before removing the tab (P24-2)
 *   2. lspOpenedFiles cleanup
 *   3. tab removal from the tabs list
 *   4. activeId fixup (previous tab, else first) + splitId fixup
 *   5. 30s-idle server-stop grace when no tabs remain for the language
 *
 * Call sites:
 *   - the tab-strip X button (original owner of this logic)
 *   - the root-removal flow: when the user removes a workspace root in the
 *     Explorer, EditorPane closes every open tab under that root by calling
 *     this function per tab — didClose is sent BEFORE the server is told the
 *     root is gone (didChangeWorkspaceFolders "removed").
 *
 * ONE deliberate fix vs. the old inline body (flagged in the 2026-09-04
 * checkpoint): the didClose URI now uses LspManager.fileUriFromHostPath — the
 * SAME guest + percent-encoded conversion didOpen uses (the P33-INTELLISENSE
 * fix for URI-mismatch). The old body sent a raw host file URI which could
 * never match a document opened via the converted URI, making the server-side
 * close a silent no-op. Falls back to the raw URI only if the guest conversion
 * returns null (file outside any bind-mount).
 */
internal fun closeEditorTabInternal(
    context: Context,
    tab: EditorTab,
    tabs: SnapshotStateList<EditorTab>,
    activeId: MutableState<String?>,
    splitId: MutableState<String?>,
    lspOpenedFiles: SnapshotStateMap<String, Boolean>,
) {
    val idx = tabs.indexOfFirst { it.id == tab.id }
    // P24-2: LSP didClose before removing tab
    val closedPath = tab.path
    val closedLang = tab.language
    // MULTI-ROOT: same URI conversion as didOpen (see doc comment above)
    val closedUri = LspManager.fileUriFromHostPath(context, closedPath)
        ?: ("file://" + closedPath)
    if (lspOpenedFiles[closedPath] == true && LspManager.isServerRunning(closedLang)) {
        try { LspManager.didClose(closedLang, closedUri) } catch (_: Exception) {}
        lspOpenedFiles.remove(closedPath)
    }
    tabs.remove(tab)
    if (activeId.value == tab.id) {
        activeId.value = tabs.getOrNull(idx - 1)?.id ?: tabs.firstOrNull()?.id
    }
    if (splitId.value == tab.id) splitId.value = null
    // P24-2: Stop server if no more files open for this language (30s grace)
    val remainingForLang = tabs.count { it.language == closedLang }
    if (remainingForLang == 0 && LspManager.isServerRunning(closedLang)) {
        GlobalScope.launch(Dispatchers.IO) {
            delay(30_000) // 30s idle grace period
            val stillZero = tabs.count { it.language == closedLang } == 0
            if (stillZero) {
                try { LspManager.stopServer(closedLang) } catch (_: Exception) {}
            }
        }
    }
}
