package com.codespace.ide.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

/**
 * Extracted layout helpers for the editor surface.
 * Lives in a separate file to keep CodeEditor.kt under the JVM 64KB bytecode limit.
 */
internal object EditorLayoutHelper {
    fun calcMaxLineWidth(wordWrap: Boolean, textLayoutResult: TextLayoutResult?): Float {
        if (wordWrap || textLayoutResult == null || textLayoutResult.lineCount == 0) return 0f
        var maxW = 0f
        for (i in 0 until textLayoutResult.lineCount) {
            val w = textLayoutResult.getLineRight(i) - textLayoutResult.getLineLeft(i)
            if (w > maxW) maxW = w
        }
        return maxW
    }

    @Composable
    fun buildEditorWidthModifier(wordWrap: Boolean, maxLineWidth: Float, hScroll: ScrollState): Modifier {
        // FIX(paste-render): Always ensure the editor is at least screen-wide.
        // When a large paste arrives, textLayoutResult is stale for 1+ frames
        // and maxLineWidth reflects the OLD (short) text. Without a floor,
        // the editor box shrinks to the old width, clipping the pasted text
        // to invisible. The viewport width ensures text is always rendered.
        val screenWidthPx = with(LocalDensity.current) {
            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
        }
        val safeMaxWidth = maxOf(maxLineWidth, screenWidthPx)
        return if (!wordWrap && safeMaxWidth > 0f) {
            Modifier
                .horizontalScroll(hScroll)
                .width(with(LocalDensity.current) { safeMaxWidth.toDp() } + 16.dp)
        } else if (!wordWrap) {
            Modifier.horizontalScroll(hScroll)
        } else {
            Modifier
        }
    }
}
