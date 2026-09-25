package com.codespace.ide.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.testing.TestResultState

/**
 * F3 (F-TRACK TG03) — gutter pass/fail decoration on the test line.
 *
 * Extracted from CodeEditor (JVM 64KB rule: no inline composable bodies).
 * Rendered in the gutter row between the bookmark dot and the breakpoint
 * row; fixed 14dp width keeps the row geometry identical whether or not a
 * test result exists for the line. Colors follow the file's existing
 * gutter decoration palette (diff green/red).
 */
@Composable
internal fun EditorGutterTestGlyph(
    state: TestResultState?,
    fontSize: Int,
) {
    Box(
        modifier = Modifier.width(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (state != null) {
            val glyph: String
            val color: Color
            when (state) {
                TestResultState.PASSED -> {
                    glyph = "\u2713"
                    color = Color(0xFF4EC9B0)
                }
                TestResultState.FAILED, TestResultState.ERRORED -> {
                    glyph = "\u2717"
                    color = Color(0xFFE06C75)
                }
                TestResultState.RUNNING -> {
                    glyph = "\u25cf"
                    color = Color(0xFF61AFEF)
                }
                TestResultState.QUEUED -> {
                    glyph = "\u25cb"
                    color = Color(0xFF61AFEF)
                }
                TestResultState.SKIPPED -> {
                    glyph = "\u25cb"
                    color = Color(0xFF5C6370)
                }
                TestResultState.RETIRED -> {
                    glyph = " "
                    color = Color(0xFF5C6370)
                }
            }
            Text(
                text = glyph,
                color = color,
                fontSize = (fontSize - 3).sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
