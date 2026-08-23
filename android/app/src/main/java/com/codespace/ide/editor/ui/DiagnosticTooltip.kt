package com.codespace.ide.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.editor.LintError

/**
 * R3-4: Inline diagnostic tooltip — shows when user taps a squiggle/error.
 *
 * Unlike ErrorLensOverlay which shows messages inline after the line,
 * this is a popup that appears near the diagnostic with full message,
 * code, and severity. Supports multiple diagnostics on the same line.
 */
@Composable
fun DiagnosticTooltip(
    errors: List<LintError>,
    topDp: Float,
    onStartDp: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (errors.isEmpty()) return
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .padding(start = onStartDp.dp, top = topDp.dp)
            .widthIn(max = 280.dp)
            .heightIn(max = 200.dp)
            .zIndex(15f)
            .background(Color(0xFF2D2D2D), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .verticalScroll(scrollState)
        ) {
            errors.forEachIndexed { idx, err ->
                if (idx > 0) Spacer(Modifier.height(8.dp))
                val sevColor = when (err.severity) {
                    1 -> Color(0xFFFF6B6B)
                    2 -> Color(0xFFCCA700)
                    else -> Color(0xFF75BEFF)
                }
                val sevLabel = when (err.severity) {
                    1 -> "Error"
                    2 -> "Warning"
                    else -> "Info"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(sevColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = sevLabel,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.SansSerif,
                        )
                    }
                    if (err.code != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "[${err.code}]",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = err.message,
                    color = Color(0xFFDDDDDD),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
