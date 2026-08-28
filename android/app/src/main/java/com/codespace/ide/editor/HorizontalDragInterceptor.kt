package com.codespace.ide.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Horizontal drag interceptor for the editor surface.
 *
 * Placed on the parent Box (before .horizontalScroll) to intercept horizontal
 * drag gestures and scroll the editor content horizontally. Uses
 * detectHorizontalDragGestures which handles touch-slop detection and axis
 * classification automatically.
 *
 * When a horizontal drag is detected:
 *   - The drag delta is applied to hScroll.scrollBy(-dragAmount)
 *   - The pointer change is consumed so BasicTextField doesn't start text selection
 *
 * No fling in this version -- scrolling stops immediately on finger lift.
 */
@Composable
fun Modifier.horizontalDragInterceptor(
    hScroll: ScrollState,
): Modifier = this.pointerInput(hScroll) {
    detectHorizontalDragGestures(
        onDragStart = { },
        onDragEnd = { },
        onDragCancel = { },
    ) { change, dragAmount ->
        // Consume the change so BasicTextField's internal text-selection handler
        // doesn't also process it (which would select text while scrolling)
        change.consume()
        // Negate: drag right = scroll left = see content to the right
        hScroll.scrollBy(-dragAmount)
    }
}
