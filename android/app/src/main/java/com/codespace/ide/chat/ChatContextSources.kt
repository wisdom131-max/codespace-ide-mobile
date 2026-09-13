package com.codespace.ide.chat

/**
 * I4 — CONTEXT ATTACH COMPLETION (2026-09-13, VS Code chatDynamicVariables +
 * chatPasteTargetService analogs): global sources the attachment picker reads
 * that previously lived only in local UI state.
 *
 * DebugConsoleCapture mirrors the debug-console lines ExplorerPane keeps in a
 * local `consoleLines` remember — every ExplorerPane append also lands here, so
 * "Attach debug console output" can offer the newest REPL/output lines from the
 * chat panel without touching debug-session internals.
 */
object DebugConsoleCapture {

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun record(line: String) {
        val t = line.take(500)
        if (t.isBlank()) return
        lines.addLast(t)
        if (lines.size > 200) lines.removeFirst()
    }

    /** Newest console content (newest last), or null when nothing was captured. */
    @Synchronized
    fun tail(maxChars: Int = 6000): String? {
        val joined = lines.joinToString("\n")
        if (joined.isBlank()) return null
        return if (joined.length > maxChars) joined.takeLast(maxChars) else joined
    }

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
