package com.codespace.ide.preview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The preview's console.
 *
 * A blank preview pane is the least actionable thing this app can show. The WebView knows exactly
 * why it is blank — it printed a TypeError to a console nobody reads — and `onConsoleMessage`
 * hands that to us. This is the sink: one process-wide ring buffer, capped so a page in a loop
 * cannot grow it without bound, exposed as state so both the chip and the full list read the same
 * thing.
 *
 * Deliberately Android-free so it can be unit-tested without a device.
 */
object PreviewConsole {

    enum class Level { ERROR, WARN, INFO }

    data class Entry(
        val id: Long,
        val level: Level,
        val message: String,
        val source: String = "",
        val line: Int = 0,
    ) {
        /** "file.js:12" or "file.js" or "" — never a dangling colon. */
        val location: String
            get() = when {
                source.isBlank() -> ""
                line > 0 -> "$source:$line"
                else -> source
            }
    }

    const val MAX_ENTRIES = 200

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private var nextId = 1L

    fun add(level: Level, message: String, source: String = "", line: Int = 0) {
        val text = message.trim()
        if (text.isEmpty()) return
        val entry = Entry(nextId++, level, text, source, line.coerceAtLeast(0))
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    fun count(level: Level): Int = _entries.value.count { it.level == level }

    val hasErrors: Boolean get() = count(Level.ERROR) > 0

    /** One line for the collapsed chip: "2 errors, 1 warning" or "Console". */
    fun summary(list: List<Entry> = _entries.value): String {
        if (list.isEmpty()) return "Console"
        val errors = list.count { it.level == Level.ERROR }
        val warns = list.count { it.level == Level.WARN }
        val parts = mutableListOf<String>()
        if (errors > 0) parts += "$errors error${if (errors == 1) "" else "s"}"
        if (warns > 0) parts += "$warns warning${if (warns == 1) "" else "s"}"
        if (parts.isEmpty()) parts += "${list.size} message${if (list.size == 1) "" else "s"}"
        return parts.joinToString(", ")
    }
}
