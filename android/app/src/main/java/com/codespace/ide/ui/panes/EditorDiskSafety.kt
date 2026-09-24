package com.codespace.ide.ui.panes

import android.util.Log
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.editor.FileCache
import java.io.File
import java.net.URLDecoder

/**
 * P1 data-loss-chain disk helpers (G01/TB01/TB03, 2026-09-24).
 *
 * One typed write primitive (writeTabToDisk) consumed by every surface that
 * persists editor content, plus the autosave-restore fix. JsonSettingsStore's
 * importJson is the in-codebase typed-result model; this file applies the
 * same honesty to editor buffers.
 *
 * G01: every write returns Boolean; dirty state may only clear on true.
 * TB01: restore decodes the FULL URL-encoded path (the old compare matched an
 *       encoded full path against a bare basename — never equal, so nothing
 *       restored), deletes a backup ONLY after its content is verified into
 *       a tab, and reports typed counts instead of "Edits restored ✓" lies.
 */
private const val TAG = "EditorDiskSafety"

/** G01: typed editor-buffer write. true = verified on disk + cache invalidated. */
internal fun writeTabToDisk(path: String, content: String): Boolean {
    return try {
        File(path).writeText(content)
        FileCache.invalidate(path)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Disk write FAILED for $path (buffer kept in editor): ${e.message}")
        false
    }
}

/**
 * G01/TB03: save every dirty tab (optionally restricted to onlyPaths).
 * Marks a tab clean ONLY when its write is verified on disk.
 * Returns (saved, failed).
 */
internal fun saveAllDirtyTabs(
    tabs: SnapshotStateList<EditorTab>,
    onlyPaths: Set<String>? = null,
): Pair<Int, Int> {
    var saved = 0
    var failed = 0
    tabs.toList().forEachIndexed { idx, tab ->
        if (!tab.isDirty || !tab.path.startsWith("/")) return@forEachIndexed
        if (onlyPaths != null && tab.path !in onlyPaths) return@forEachIndexed
        if (writeTabToDisk(tab.path, tab.content)) {
            tabs[idx] = tab.copy(isDirty = false)
            saved++
        } else {
            failed++
        }
    }
    return saved to failed
}

/**
 * TB01: restore URL-encoded full-path autosave files into matching open tabs.
 * Deletes a backup ONLY after its content is restored into the tab; files with
 * no matching open tab (or a failed read) are KEPT on disk — they may be the
 * only copy of the user's unsaved edits.
 * Returns (restored, keptNoMatchingTab, keptFailed).
 */
internal fun restoreAutosaveFiles(
    autosaveFiles: List<File>,
    tabs: SnapshotStateList<EditorTab>,
): Triple<Int, Int, Int> {
    var restored = 0
    var noTab = 0
    var failed = 0
    for (autosave in autosaveFiles) {
        try {
            // TB01: decode the FULL path from the P37-CORRUPTION-FIX filename
            // format (URL-encoded absolute path + ".autosave"). The old code
            // compared the ENCODED filename to File(tab.path).name — never equal.
            val originalPath = URLDecoder.decode(
                autosave.name.removeSuffix(".autosave"), "UTF-8")
            val recoveredContent = autosave.readText()
            val idx = tabs.indexOfFirst { it.path == originalPath }
            if (idx >= 0) {
                tabs[idx] = tabs[idx].copy(content = recoveredContent, isDirty = true)
                if (!autosave.delete()) {
                    Log.w(TAG, "Restored but could not delete autosave: ${autosave.name}")
                }
                restored++
            } else {
                noTab++ // file not open this session — KEEP the only copy
            }
        } catch (e: Exception) {
            failed++
            Log.e(TAG, "Autosave restore failed for ${autosave.name}: ${e.message}")
        }
    }
    return Triple(restored, noTab, failed)
}
