package com.codespace.ide.chat

import java.io.File

/**
 * R10-D — attach project-wide search results as chat context
 * (VS Code attach-context "Search results" analog).
 *
 * Walks the project tree (same caps as AgentTools.searchFiles: 500 files,
 * 100KB per file), greps for [query] case-insensitively, and formats
 * "relativePath:line: text" matches. STAGED content (R6 pending edits)
 * wins over disk when a file has un-applied staged changes.
 */
object SearchResultsAttach {

    private const val MAX_FILES = 500
    private const val MAX_FILE_BYTES = 100_000L
    private const val MAX_MATCH_LINES = 80
    private const val MAX_TOTAL_CHARS = 4000

    fun searchProjectContent(projectRoot: String, query: String): String {
        if (query.isBlank()) return ""
        val root = File(projectRoot)
        if (!root.exists()) return ""
        val out = mutableListOf<String>()
        val rootLen = root.absolutePath.length + 1
        try {
            root.walkTopDown().take(MAX_FILES).forEach { f ->
                if (out.size >= MAX_MATCH_LINES) return@forEach
                if (!f.isFile || f.length() > MAX_FILE_BYTES || f.length() == 0L) return@forEach
                val n = f.name.lowercase()
                if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                    n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".mp3") ||
                    n.endsWith(".wav") || n.endsWith(".zip") || n.endsWith(".apk") ||
                    n.endsWith(".jar") || n.endsWith(".so") || n.startsWith(".")
                ) return@forEach
                try {
                    val staged = PendingChangesStore.overlayFor(f.absolutePath)
                    val content = staged ?: f.readText()
                    content.lineSequence().take(4000).forEachIndexed { i, line ->
                        if (out.size >= MAX_MATCH_LINES) return@forEachIndexed
                        if (line.contains(query, ignoreCase = true)) {
                            out.add(f.absolutePath.substring(rootLen) + ":" + (i + 1) + ": " + line.trim().take(200))
                        }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return out.joinToString("\n").take(MAX_TOTAL_CHARS)
    }

    /** Ready-made attachment for the picker row (empty selText when no matches). */
    fun buildAttachment(projectRoot: String, query: String): ChatAttachment? {
        val content = searchProjectContent(projectRoot, query)
        if (content.isBlank()) return null
        return ChatAttachment(
            path = "search", relPath = "search", name = "search: " + query,
            kind = ChatAttachment.Kind.SELECTION, selText = content,
        )
    }
}
