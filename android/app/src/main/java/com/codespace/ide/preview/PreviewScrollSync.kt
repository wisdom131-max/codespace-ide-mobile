package com.codespace.ide.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the caret is in the active editor, published for the preview pane.
 *
 * VS Code syncs the preview to the editor (markdown.preview.scrollPreviewWithEditor). Its
 * mechanism is a line map from the rendered blocks back to source lines; this app publishes
 * the caret line instead and the preview converts it to a scroll ratio. A ratio needs no
 * mapping table, which means it cannot drift out of date when the renderer changes, at the
 * cost of being approximate in documents where block heights are very uneven.
 */
object PreviewScrollSync {

    var filePath: String? by mutableStateOf(null)
        private set

    var line: Int by mutableIntStateOf(0)
        private set

    /** Called by the editor on every caret move. */
    fun onCursor(path: String, line: Int) {
        if (path.isBlank()) return
        filePath = path
        this.line = line
    }

    /** 0..1 position of [line] within a document of [totalLines]; safe for any input. */
    fun ratio(line: Int, totalLines: Int): Double {
        if (totalLines <= 1) return 0.0
        val clamped = line.coerceIn(0, totalLines - 1)
        return clamped.toDouble() / (totalLines - 1).toDouble()
    }
}
