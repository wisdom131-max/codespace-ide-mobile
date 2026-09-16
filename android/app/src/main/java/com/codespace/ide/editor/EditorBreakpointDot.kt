package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CW3 — gutter marker composables, extracted from CodeEditor's body.
 *
 * CodeEditor sits at the JVM 64KB method limit (confirmed by builds #2826-#2828:
 * MethodTooLargeException on CodeEditorKt.CodeEditor). ALL gutter marker UI
 * must live HERE — never inline in the composable body.
 */

/** Solid dot = plain breakpoint; HOLLOW RING = conditional/log breakpoint (CW3). */
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

/** P54 — debug current-line arrow. */
@Composable
internal fun EditorGutterDebugArrow(fontSize: Int) {
    Text(
        text = "\u2192",
        color = Color(0xFFCCA700),
        fontSize = (fontSize * 0.8f).sp,
    )
}

/** P2-9 — bookmark dot (tap toggles). */
@Composable
internal fun EditorGutterBookmarkDot(
    isBookmarked: Boolean,
    fontSize: Int,
    colors: com.codespace.ide.ui.EditorColors,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(fontSize.dp)
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        if (isBookmarked) {
            Text(
                text = "\u25C6",
                color = colors.keyword,  // P50-FIX: theme-aware bookmark color
                fontSize = (fontSize * 0.6f).sp,
            )
        }
    }
}

/**
 * P8-1 + CW3 — breakpoint dot + tappable line number row (VS Code style).
 * Long-press opens the breakpoint condition editor (VS Code: Add Condition).
 * [modifier] carries weight(1f)+height from the caller's RowScope.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorGutterBreakpointRow(
    modifier: Modifier = Modifier,
    lineNum: Int,
    hasBreakpoint: Boolean,
    isConditionalBp: Boolean,
    isDebugLine: Boolean,
    isBookmarked: Boolean,
    fontSize: Int,
    colors: com.codespace.ide.ui.EditorColors,
    onBreakpointToggle: (Int) -> Unit,
    onBreakpointLongPress: (Int) -> Unit,
) {
    Row(
        modifier = modifier.combinedClickable(
            onClick = { onBreakpointToggle(lineNum) },
            onLongClick = { onBreakpointLongPress(lineNum) },
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        // P54: Debug current-line indicator — yellow arrow
        if (isDebugLine) {
            EditorGutterDebugArrow(fontSize)
            Spacer(Modifier.width(2.dp))
        }
        if (hasBreakpoint) {
            // CW3: ring = conditional/log breakpoint
            EditorBreakpointDot(isConditional = isConditionalBp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = (lineNum + 1).toString(),
            color = if (isDebugLine)
                Color(0xFFCCA700)  // P54: yellow highlight on current debug line
            else if (isBookmarked)
                colors.keyword  // P50-FIX: theme-aware bookmark color
            else colors.gutter,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.25f).sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
        )
    }
}
