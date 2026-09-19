package com.codespace.ide.util

import java.io.File

/**
 * VERSIONHISTORY V2 (2026-09-19, VERSIONHISTORY_NAMING_PLAN.md v3, Wisdom GO):
 * all NEW snapshots live under projectRoot/.versionhistory/v2/<relative path of
 * the file against the project root>, so same-named files in different folders
 * never share a snapshot dir (the pre-v2 name-only form mixed owners and let a
 * Restore write file A's snapshot into file B).
 *
 * Legacy name-only dirs (projectRoot/.versionhistory/<file name>) are NEVER a
 * Restore source again: they are view-only for every file, root files included,
 * because their snapshot ownership is unknowable (a same-named file may have
 * been renamed, moved or deleted since the snapshot was written).
 *
 * Path safety (BUG-A class): both the file and the root are canonicalized before
 * the relative path is computed, so the sdcard and storage spellings of the same
 * file resolve to the SAME v2 dir. Containment is asserted: any dot-dot, absolute
 * or outside-v2 result means NO snapshot (fail closed, never a wrong snapshot).
 */
object VersionHistoryV2 {

    const val ROOT_DIR_NAME = ".versionhistory"
    const val V2_DIR_NAME = "v2"
    const val PRECHAT_SUFFIX = "_prechat.bak"
    const val BAK_SUFFIX = ".bak"
    const val KEEP_PER_GROUP = 20

    /**
     * Canonical v2 snapshot dir for filePath under root, or null when the file is
     * outside the root or the path is unsafe. Block body on purpose (early returns).
     */
    fun v2DirFor(root: File?, filePath: String): File? {
        if (root == null) return null
        if (filePath.isBlank()) return null
        return try {
            val canonRoot = root.canonicalFile
            val canonFile = File(filePath).canonicalFile
            val rel = canonFile.relativeTo(canonRoot).path
            if (rel.isBlank() || rel == ".") return null
            if (File(rel).isAbsolute) return null
            if (rel == ".." || rel.startsWith("../") || rel.contains("/../")) return null
            val marker = File(canonRoot, ROOT_DIR_NAME + File.separator + V2_DIR_NAME).canonicalFile
            val dir = File(marker, rel).canonicalFile
            if (!dir.path.startsWith(marker.path + File.separator)) return null
            dir
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Legacy name-only dir. After v3 this is for VIEW-ONLY listing/preview only;
     * callers must never offer Restore from it.
     */
    fun legacyDirFor(root: File?, fileName: String): File? {
        if (root == null) return null
        return try {
            File(File(root.canonicalFile, ROOT_DIR_NAME), fileName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Grouped retention (Q1 fix, Wisdom-approved): plain .bak captures and
     * _prechat.bak AI checkpoints are trimmed as SEPARATE groups, newest
     * KEEP_PER_GROUP each — so ~20 autosave captures of the same file can no
     * longer evict that file's own AI Undo checkpoint (pre-v3 one shared list
     * let them; READ: ExplorerPane.kt:1517 + PendingChangesStore.kt:261 both
     * counted every file in the dir).
     */
    fun trimGrouped(dir: File) {
        val files = dir.listFiles()
        if (files == null) return
        val plain = ArrayList<File>()
        val prechat = ArrayList<File>()
        for (f in files) {
            if (!f.isFile) continue
            if (f.name.endsWith(PRECHAT_SUFFIX)) {
                prechat.add(f)
            } else if (f.name.endsWith(BAK_SUFFIX)) {
                plain.add(f)
            }
        }
        plain.sortedByDescending { it.lastModified() }.drop(KEEP_PER_GROUP).forEach { it.delete() }
        prechat.sortedByDescending { it.lastModified() }.drop(KEEP_PER_GROUP).forEach { it.delete() }
    }

    /**
     * REQUIRED tap-time Restore assertion (plan v3 condition 1): the snapshot
     * must live under the v2 dir computed from THIS file, else the caller must
     * do NOTHING. Fail closed on any ambiguity.
     */
    fun isSnapshotOf(root: File?, filePath: String, snap: File): Boolean {
        val dir = v2DirFor(root, filePath) ?: return false
        return try {
            val parent = snap.canonicalFile.parentFile
            parent != null && parent.path == dir.path
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Newest-first .bak FILES in dir. Readers must list only files (plan 4.1
     * isFile rule — a project folder literally named v2 makes the marker dir a
     * parent of nested snapshot dirs; those dirs are never listed as snapshots).
     */
    fun listSnapshots(dir: File?, limit: Int = KEEP_PER_GROUP): List<File> {
        if (dir == null || !dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        val out = ArrayList<File>()
        for (f in files) {
            if (f.isFile && f.name.endsWith(BAK_SUFFIX)) out.add(f)
        }
        out.sortByDescending { it.lastModified() }
        return if (out.size > limit) out.subList(0, limit) else out
    }
}
