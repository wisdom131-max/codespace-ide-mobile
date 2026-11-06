package com.codespace.ide.editor

import java.io.File

/**
 * One bounded walk of a project tree, used by the indexer's watcher.
 *
 * WHY THIS IS ITS OWN FILE: the watcher used to call `root.walkTopDown()` twice - once for its
 * initial snapshot, and once on EVERY 5-second tick - with no node cap, no time cap and no
 * ignore list. `ProjectShellScreen.kt:1025-1037` starts that watcher from a `LaunchedEffect`,
 * whose body runs on the composition dispatcher (the main thread), so the first unbounded walk
 * ran there. Opening a project whose root holds a `.git` and a `node_modules` therefore walked
 * tens of thousands of files on the main thread before the first frame, which is the freeze the
 * owner reported when opening a project root.
 *
 * The ignore predicate is deliberately the SAME one `collectSourceFiles` uses, so "what the
 * indexer indexes" and "what the watcher watches" cannot drift apart. VS Code's
 * `files.watcherExclude` defaults (.git/objects, .git/subtree-cache, .hg/store) are all inside
 * dot-directories and are covered by the dot rule; they are listed in [IGNORED_NAMES] anyway so
 * the intent survives a future change to that rule.
 *
 * Pure `java.io.File` + a clock, so it runs as a JVM test against a synthetic tree.
 */
internal object WatchScan {

    /** Directory names never descended into, at any depth. */
    val IGNORED_NAMES: Set<String> = setOf(
        // dependency and build output - VS Code excludes node_modules from watching by default
        "node_modules", "build", "out", "dist", "target", "Pods", "vendors",
        // tool caches
        ".gradle", ".idea", ".git", ".hg", ".svn", ".cxx", ".kotlin",
    )

    fun isIgnored(dir: File): Boolean {
        val name = dir.name
        return name.startsWith(".") || name in IGNORED_NAMES
    }

    data class Result(
        /** path -> lastModified for every file the walk accepted. */
        val files: Map<String, Long>,
        val dirsVisited: Int,
        /** True when a cap stopped the walk: the caller must say so, not pretend it saw everything. */
        val capped: Boolean,
        val elapsedMs: Long,
    )

    /**
     * Walk [root], accepting files whose parent directories are not ignored, stopping at
     * [maxFiles] accepted files or [budgetMs] elapsed.
     */
    fun scan(
        root: File,
        maxFiles: Int,
        budgetMs: Long,
        accept: (File) -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): Result {
        val started = now()
        val files = LinkedHashMap<String, Long>()
        var dirs = 0
        var capped = false
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            if (files.size >= maxFiles || now() - started >= budgetMs) { capped = true; break }
            val dir = queue.removeFirst()
            dirs++
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (!isIgnored(child)) queue.add(child)
                } else if (accept(child)) {
                    if (files.size >= maxFiles) { capped = true; break }
                    files[child.absolutePath] = child.lastModified()
                }
            }
        }
        return Result(files, dirs, capped, now() - started)
    }
}
