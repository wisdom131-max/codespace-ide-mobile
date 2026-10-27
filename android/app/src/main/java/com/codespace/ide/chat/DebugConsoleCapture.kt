package com.codespace.ide.chat

/**
 * REMOVAL 3/7 repair (2026-10-27): DebugConsoleCapture lived inside
 * ChatContextSources.kt (deleted with the engine). ExplorerPane records debug
 * console output here independently of the chat (KEEP-FOR-NOW in the advisor's
 * dependency map) — re-homed verbatim from the archived copy
 * (vn-code-archive @ chat-archive-2026-10-27) as its own file.
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
