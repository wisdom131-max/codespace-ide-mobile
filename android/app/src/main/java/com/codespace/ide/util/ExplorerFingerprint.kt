package com.codespace.ide.util

import java.io.File

/**
 * FSW-1(a) (BATCH4-P2): mtime-fingerprint rescan for the Explorer — the
 * GUARANTEED baseline the owner approved in principle.
 *
 * The terminal-driven path (FsChangeNotifier -> terminalActivityCounter) only
 * covers changes the app itself made. Files created, deleted or renamed by
 * OTHER apps (file managers, adb, downloads) had no path into the tree at
 * all. This is the watcher-free baseline: every Explorer re-entry and every
 * app-resume, ONE off-main-thread scan fingerprints ONLY the visible tree —
 * one listing per EXPANDED directory — and a changed fingerprint bumps the
 * same refresh counter the manual refresh button uses.
 *
 * Bounds (owner conditions): the scan never recurses into unexpanded
 * children, so node_modules/.git contents are never scanned unless the user
 * expanded them; per-directory and total entry caps and a hard time budget
 * keep huge directories from stalling the rescan. No FileObserver, no warm
 * path (S1-c stays parked).
 */
object ExplorerFingerprint {

    const val MAX_PER_DIR = 500
    const val MAX_TOTAL = 5000
    const val TIME_BUDGET_MS = 3000L

    data class Scan(
        val value: String,   // stable canonical string; different = the visible tree changed
        val dirsScanned: Int,
        val entriesSeen: Int,
        val capped: Boolean,  // a per-dir or total cap was hit (change detection still holds via the dir mtime header)
        val timedOut: Boolean, // the time budget was hit; the scan is partial but still comparable
    )

    /**
     * Fingerprints the visible tree: the root listing plus one listing per
     * expanded directory. Each expanded dir contributes a header with its OWN
     * mtime (so a change inside a COLLAPSED child — which bumps that child's
     * mtime entry, or the expanded dir's own mtime — is still detected) plus
     * its immediate children as name|kind|mtime|size. Never descends into
     * unexpanded children.
     */
    fun scanVisibleTree(root: File, expandedDirs: Set<String>, timeBudgetMs: Long = TIME_BUDGET_MS): Scan {
        val start = System.nanoTime()
        val sb = StringBuilder()
        var total = 0
        var scanned = 0
        var capped = false
        var timedOut = false
        val dirs = (listOf(root) + expandedDirs.map { File(it) })
            .distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
        for (dir in dirs) {
            if ((System.nanoTime() - start) / 1_000_000 > timeBudgetMs) { timedOut = true; break }
            if (!dir.isDirectory) continue
            val children = dir.listFiles() ?: continue
            scanned++
            sb.append('D').append(dir.absolutePath).append('|').append(dir.lastModified()).append('\n')
            var n = 0
            for (c in children.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))) {
                if (n >= MAX_PER_DIR) { capped = true; sb.append("CAP\n"); break }
                if (total >= MAX_TOTAL) { capped = true; sb.append("CAP\n"); break }
                sb.append(c.name).append('|')
                    .append(if (c.isDirectory) 'd' else 'f').append('|')
                    .append(c.lastModified()).append('|')
                    .append(c.length()).append('\n')
                n++
                total++
            }
        }
        return Scan(sb.toString(), scanned, total, capped, timedOut)
    }
}

/**
 * Process-level fingerprint cache (NOT persisted): keeps the last fingerprint
 * per workspace root across pane compositions, so a change made while the
 * Explorer pane was CLOSED (sidebar showing another panel) is still detected
 * on re-entry. A fresh app start has no baseline — the tree loads current
 * contents anyway, so the first scan just stores one.
 */
object ExplorerFingerprintCache {
    private val fingerprints = HashMap<String, String>() // root -> last fingerprint
    private val inFlight = HashSet<String>()

    /** Rate limit: true = this scan may proceed; false = one is already running for this root. */
    @Synchronized
    fun beginScan(root: String): Boolean {
        if (inFlight.contains(root)) return false
        inFlight.add(root)
        return true
    }

    /** Stores the new fingerprint and reports whether the VISIBLE TREE CHANGED. */
    @Synchronized
    fun finishScan(root: String, value: String): Boolean {
        inFlight.remove(root)
        val prev = fingerprints[root]
        fingerprints[root] = value
        return prev != null && prev != value
    }

    @Synchronized
    fun abandonScan(root: String) {
        inFlight.remove(root)
    }

    /** JVM tests only. */
    @Synchronized
    fun clearForTests() {
        fingerprints.clear()
        inFlight.clear()
    }
}
