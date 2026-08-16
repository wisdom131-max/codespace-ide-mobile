package com.codespace.ide.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.codespace.ide.editor.ProjectSettingsStore
import com.codespace.ide.editor.CursorBlinkStyle
import com.codespace.ide.editor.CursorMode

/**
 * Custom cursor overlay modifier - draws a wider, touch-friendly cursor
 * when custom cursor overlay is enabled or blink style is SOLID/EXPAND.
 * In SYSTEM mode, returns plain Modifier (uses phone's built-in cursor).
 */
@Composable
internal fun cursorOverlayModifier(
    textLayoutResult: TextLayoutResult?,
    selection: TextRange,
    cursorColor: androidx.compose.ui.graphics.Color,
): Modifier {
    val cursorStyle = ProjectSettingsStore.cursorBlinkStyle.value
    val customEnabled = ProjectSettingsStore.customCursorOverlayEnabled.value
    val cursorMode = ProjectSettingsStore.cursorMode.value
    // SYSTEM mode = use phone's built-in cursor, skip all overlay drawing
    if (cursorMode == CursorMode.SYSTEM) return Modifier
    if (cursorStyle != CursorBlinkStyle.SOLID && cursorStyle != CursorBlinkStyle.EXPAND && !customEnabled) {
        return Modifier
    }
    var expandW by remember(cursorStyle) { mutableStateOf(2f) }
    if (cursorStyle == CursorBlinkStyle.EXPAND) {
        LaunchedEffect(Unit) {
            while (true) {
                expandW = 5f
                kotlinx.coroutines.delay(350)
                expandW = 1.5f
                kotlinx.coroutines.delay(350)
            }
        }
    }
    return Modifier.drawWithContent {
        drawContent()
        val layout = textLayoutResult ?: return@drawWithContent
        // CRASH-FIX: clamp cursor offset to valid range — after multi-cursor edits
        // the stored offset can exceed the current text length, causing
        // IllegalArgumentException in getHorizontalPosition (Test 19 crash).
        val cursor = selection.end.coerceIn(0, layout.text.length)
        val lineIdx = layout.getLineForOffset(cursor)
        val cx = layout.getHorizontalPosition(cursor, true)
        val cy = layout.getLineTop(lineIdx)
        val ch = layout.getLineBottom(lineIdx) - cy
        val w = if (cursorStyle == CursorBlinkStyle.EXPAND) expandW
                else if (customEnabled) 3.dp.toPx()
                else 2.dp.toPx()
        drawRect(
            color = cursorColor,
            topLeft = Offset(cx - w / 2f, cy),
            size = Size(w, ch),
        )
    }
}

/**
 * Custom cursor overlay interaction modifier - adds tap-to-type and drag-to-move
 * when the custom cursor overlay is enabled in In-Project Settings.
 *
 * Tap: requests focus and shows the software keyboard.
 * Drag: moves the cursor to the touched character position.
 */
@Composable
internal fun customCursorInteractionModifier(
    textLayoutResult: TextLayoutResult?,
    onCursorMoved: (Int) -> Unit,
    onTap: () -> Unit,
): Modifier {
    val cursorMode = ProjectSettingsStore.cursorMode.value
    if (!ProjectSettingsStore.customCursorOverlayEnabled.value || cursorMode == CursorMode.SYSTEM) return Modifier
    return Modifier.pointerInput(textLayoutResult) {
        detectTapGestures(
            onTap = { offset ->
                onTap()
                // Also position cursor at tap location
                val layout = textLayoutResult ?: return@detectTapGestures
                val pos = layout.getOffsetForPosition(offset)
                onCursorMoved(pos)
            },
            onLongPress = { offset ->
                val layout = textLayoutResult ?: return@detectTapGestures
                val pos = layout.getOffsetForPosition(offset)
                onCursorMoved(pos)
            },
        )
    }
}
