package com.codespace.ide.util

import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * U6-d — reveal agent-written files in the Explorer.
 *
 * The refresh half already existed (FsChangeNotifier -> ProjectShellScreen -> tree re-scan),
 * so the Explorer always ENDS UP correct. What was missing is REVEAL: an agent writing
 * deep/closed folders left the file invisible, because the tree only renders expanded dirs.
 *
 * Design rules that matter:
 *  - Reveal only EXPANDS ancestors and re-scans. It never opens the file in the editor: an
 *    agent touching six files must not yank the user out of what they were reading.
 *  - Paths outside the project root are ignored, not "revealed" somewhere wrong.
 *  - Requests queue, so a batch write from one operation reveals all of it, and the queue is
 *    drained by the pane - a second request cannot silently overwrite the first.
 */
object ExplorerReveal {

    /** Paths waiting to be revealed. Drained by ExplorerRevealEffect. */
    val pending = mutableStateOf<List<String>>(emptyList())

    fun requestFor(paths: List<String>) {
        if (paths.isEmpty()) return
        synchronized(this) {
            // De-duplicate while keeping order: a write batch can repeat a path.
            val merged = LinkedHashSet(pending.value)
            merged.addAll(paths)
            pending.value = merged.toList()
        }
    }

    fun requestFor(path: String) = requestFor(listOf(path))

    /** Called by the pane once the paths have been expanded. */
    fun consume() {
        synchronized(this) { pending.value = emptyList() }
    }

    // --- pure, unit-tested ---------------------------------------------------------------

    /**
     * Every directory that must be expanded for [path] to be visible under [root], outermost
     * first, EXCLUDING the root itself (the root is always expanded) and excluding any path
     * that is not inside the root. A file directly in the root yields an empty list: nothing
     * to expand, it is already visible.
     */
    fun ancestorsToExpand(path: String, root: String?): List<String> {
        if (root.isNullOrBlank()) return emptyList()
        val rootPath = normalize(root)
        val filePath = normalize(path)
        if (filePath == rootPath) return emptyList()
        if (!filePath.startsWith(rootPath.trimEnd('/') + "/")) return emptyList()
        val relative = filePath.substring(rootPath.trimEnd('/').length + 1)
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.size <= 1) return emptyList()
        val out = ArrayList<String>(segments.size - 1)
        var acc = rootPath.trimEnd('/')
        for (i in 0 until segments.size - 1) {
            acc = acc + "/" + segments[i]
            out.add(acc)
        }
        return out
    }

    /** Collapses duplicate slashes and trailing slashes so prefixes compare honestly. */
    fun normalize(path: String): String {
        var p = path.trim()
        if (p.isEmpty()) return p
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        val sb = StringBuilder(p.length)
        var lastSlash = false
        for (ch in p) {
            if (ch == '/') {
                if (!lastSlash) sb.append(ch)
                lastSlash = true
            } else {
                sb.append(ch)
                lastSlash = false
            }
        }
        return sb.toString()
    }

    /** A write outside the project must not reveal anything (returns false). */
    fun isInsideProject(path: String, root: String?): Boolean {
        if (root.isNullOrBlank()) return false
        val rootPath = normalize(root).trimEnd('/')
        val filePath = normalize(path)
        return filePath == rootPath || filePath.startsWith("$rootPath/")
    }
}
