package com.codespace.ide.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Phase D: Extracted editor action helpers to reduce CodeEditor composable bytecode.
 * These functions implement multi-line indent and tab-key behavior.
 */

/**
 * Applies multi-line indent: prepends a tab to each line in the selection.
 * Returns the new TextFieldValue, or null if the selection is single-line.
 */
internal fun applyMultiLineIndent(
    value: TextFieldValue,
    positionMapper: PositionMapper,
): Pair<TextFieldValue, String>? {
    val selStart = value.selection.min
    val selEnd = value.selection.max
    if (selStart == selEnd) return null
    val selectedText = value.text.substring(selStart, selEnd)
    if (!selectedText.contains("\n")) return null
    
    val lineStart = positionMapper.lineStart(positionMapper.offsetToLine(selStart))
    val linesToIndent = value.text.substring(lineStart, selEnd)
    val indented = linesToIndent.split("\n").map { "\t" + it }.joinToString("\n")
    val newText = value.text.substring(0, lineStart) + indented + value.text.substring(selEnd)
    val newTfv = TextFieldValue(text = newText, selection = TextRange(lineStart, lineStart + indented.length))
    return Pair(newTfv, newText)
}

/**
 * Applies single-line tab: replaces the selection with a tab character.
 * Returns the new TextFieldValue, or null if there's no selection.
 */
internal fun applySingleLineTab(
    value: TextFieldValue,
): Pair<TextFieldValue, String>? {
    val selStart = value.selection.min
    val selEnd = value.selection.max
    if (selStart == selEnd) return null
    val newText = value.text.substring(0, selStart) + "\t" + value.text.substring(selEnd)
    val newTfv = TextFieldValue(text = newText, selection = TextRange(selStart + 1))
    return Pair(newTfv, newText)
}
