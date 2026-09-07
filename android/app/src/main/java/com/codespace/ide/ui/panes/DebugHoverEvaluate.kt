package com.codespace.ide.ui.panes

/**
 * P2: hover-evaluate support. Word extraction for the cursor position so the
 * hovered identifier can be evaluated against the paused debug session (DAP
 * evaluate, hover context). Pure string helper — no composable state here.
 */
internal fun extractWordAtContent(content: String, line: Int, col: Int): String? {
    val lines = content.split('\n')
    if (line < 0 || line >= lines.size) return null
    val l = lines[line]
    if (col < 0 || col > l.length) return null
    var start = col
    var end = col
    val isWordChar = { ch: Char -> ch.isLetterOrDigit() || ch == '_' }
    while (start > 0 && isWordChar(l[start - 1])) start--
    while (end < l.length && isWordChar(l[end])) end++
    if (end == start) return null
    val word = l.substring(start, end)
    // Skip pure numbers — evaluating literals is noise
    if (word.all { it.isDigit() }) return null
    return word
}
