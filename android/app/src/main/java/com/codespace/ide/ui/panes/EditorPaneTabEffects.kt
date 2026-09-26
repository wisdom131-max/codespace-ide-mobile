package com.codespace.ide.ui.panes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.LocalContext
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.editor.FileCache
import com.codespace.ide.editor.PerFileStateStore
import com.codespace.ide.editor.SplitViewStore
import com.codespace.ide.editor.undo.SharedFileUndo
import com.codespace.ide.util.CanonicalPaths
import java.io.File

/**
 * TB06/TB07 (P4d) — shell-driven TAB requests, extracted into their own file at
 * first write (64KB rule).
 *
 * The shell previously handled Explorer rename and OPEN-EDITORS close by
 * mutating its own MIRROR lists (editorTabs[idx] = newPath; editorTabs.remove
 * (tabPath)) — but EditorPane is the AUTHORITATIVE tab owner (B1 reactive-sync),
 * so those mutations fought the sync stream: renames never rekeyed the real tab
 * (nor any store, breakpoint list, or split view keyed by the path), and
 * OPEN-EDITORS closes were resurrected by the next onTabsChanged report.
 *
 * Both requests now flow through EditorPane the same way closeRootRequest does:
 * the shell sets a request value, the effects here perform the change through
 * the SAME shared paths the tab strip uses, then the shell clears the request.
 */
@Composable
internal fun EditorPaneTabRequests(
    /** TB06: (oldPath, newPath) to rekey the authoritative tab + every keyed store. */
    renameFileRequest: Pair<String, String>?,
    onRenameFileHandled: (() -> Unit)?,
    /** TB07: path of the tab to close through the shared close path. */
    closeTabRequest: String?,
    onCloseTabHandled: (() -> Unit)?,
    tabs: SnapshotStateList<EditorTab>,
    activeIdState: MutableState<String?>,
    lspOpenedFiles: SnapshotStateMap<String, Boolean>,
    pinnedPaths: SnapshotStateList<String>,
    tabScrollLines: SnapshotStateMap<String, Int>,
    tabCursorOffsets: SnapshotStateMap<String, Int>,
    tabFoldedRanges: SnapshotStateMap<String, Set<Int>>,
    fileBookmarks: SnapshotStateMap<String, Set<Int>>,
    udm: com.codespace.ide.debug.UniversalDebugManager?,
) {
    val context = LocalContext.current

    // TB06 (P4d): Explorer rename — rekey EVERYTHING keyed by the old path. The
    // shell (onFileRenamed) keeps its mirror update + the LSP didRenameFiles
    // notification; this effect rekeys the authoritative side. Language is
    // intentionally preserved (LSP document continuity is the server's concern
    // after didRenameFiles; a same-file rename keeps its language anyway).
    LaunchedEffect(renameFileRequest) {
        val req = renameFileRequest ?: return@LaunchedEffect
        val oldPath = req.first
        val newPath = req.second
        if (oldPath != newPath) {
            // Split views first: their ids EMBED the path, and the active-id
            // fixup below needs the old->new id map.
            val splitRemap = SplitViewStore.rekeyPath(oldPath, newPath)
            val idx = tabs.indexOfFirst { CanonicalPaths.sameFileIdentity(it.path, oldPath) }
            if (idx >= 0) {
                val t = tabs[idx]
                val newTab = t.copy(id = newPath, path = newPath, name = File(newPath).name)
                tabs[idx] = newTab
                if (activeIdState.value == t.id) {
                    activeIdState.value = newTab.id
                } else {
                    splitRemap[activeIdState.value]?.let { activeIdState.value = it }
                }
                // per-path composition maps
                tabScrollLines.remove(oldPath)?.let { tabScrollLines[newPath] = it }
                tabCursorOffsets.remove(oldPath)?.let { tabCursorOffsets[newPath] = it }
                tabFoldedRanges.remove(oldPath)?.let { tabFoldedRanges[newPath] = it }
                fileBookmarks.remove(oldPath)?.let { fileBookmarks[newPath] = it }
                if (pinnedPaths.remove(oldPath)) pinnedPaths.add(newPath)
                // LSP bookkeeping: didRenameFiles already told the server, so NO
                // protocol traffic here — just rekey the opened-doc tracker so
                // future didClose/didChange use the new spelling.
                lspOpenedFiles.remove(oldPath)?.let { lspOpenedFiles[newPath] = it }
                // canonical stores, breakpoints, cache
                PerFileStateStore.rekey(oldPath, newPath)
                SharedFileUndo.rekey(oldPath, newPath)
                FileCache.invalidate(oldPath)  // G07-style: never serve pre-rename content
                udm?.rekeyBreakpoints(oldPath, newPath)
            }
        }
        onRenameFileHandled?.invoke()
    }

    // TB07 (P4d): OPEN-EDITORS close — through the shared closeEditorTabInternal
    // path (didClose + removal + active fixup + split cascade), NOT a mirror edit.
    // A tab whose spelling differs from the request still resolves (identity).
    LaunchedEffect(closeTabRequest) {
        val tabPath = closeTabRequest ?: return@LaunchedEffect
        val tab = tabs.firstOrNull { CanonicalPaths.sameFileIdentity(it.path, tabPath) }
        if (tab != null) {
            closeEditorTabInternal(context, tab, tabs, activeIdState, lspOpenedFiles)
        }
        onCloseTabHandled?.invoke()
    }
}
