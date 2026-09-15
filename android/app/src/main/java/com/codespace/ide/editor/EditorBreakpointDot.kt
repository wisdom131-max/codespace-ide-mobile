package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CW3 — gutter breakpoint marker, extracted from CodeEditor's body.
 *
 * Solid dot = plain breakpoint; HOLLOW RING = conditional/log breakpoint
 * (VS Code parity: conditional breakpoints render with a hollow glyph).
 * Extracted because CodeEditor sits at the JVM 64KB method limit — any
 * gutter marker changes must live HERE, never inline in the composable.
 */
@Composable
internal fun EditorBreakpointDot(isConditional: Boolean) {
    val bpRed = Color(0xFFE51400)
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .then(
                if (isConditional) Modifier.border(1.5.dp, bpRed, CircleShape)
                else Modifier.background(bpRed)
            )
    )
}

/**
 * P54 — debug current-line arrow, extracted from CodeEditor's body (CW3 64KB fix).
 */
@Composable
internal fun EditorGutterDebugArrow(fontSize: Float) {
    androidx.compose.material3.Text(
        text = "\u2192",
        color = Color(0xFFCCA700),
        fontSize = (fontSize * 0.8f).sp,
    )
}
