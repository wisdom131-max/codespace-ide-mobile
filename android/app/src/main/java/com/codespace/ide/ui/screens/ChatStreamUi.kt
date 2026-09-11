package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * STREAMING + CONTEXT-GAUGE UI (2026-09-11, VS Code lm-parity round).
 * Extracted per the JVM 64KB rule — both chat panels call these with one line.
 *
 * LiveStreamIndicator replaces the old static "Thinking..." row:
 *   - empty live text → spinner + "Thinking..." (previous behavior)
 *   - non-empty → assistant-styled bubble that GROWS as deltas arrive
 *     (agent-mode tool events append status lines after the stream text)
 *
 * ChatContextGauge renders the running context-usage readout
 * ("Context: 12.3k / 128k (10%)") fed by the per-provider token count.
 */

/** Live streaming bubble shown while chatLoading. UI RULE: rounded corners + padding. */
@Composable
internal fun LiveStreamIndicator(
    liveText: String,
    accent: Color,
    surface: Color,
    text: Color,
    textSecondary: Color,
) {
    if (liveText.isEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text("Thinking...", color = textSecondary, fontSize = 11.sp)
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = surface,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "streaming…",
                            color = textSecondary,
                            fontSize = 10.sp,
                        )
                    }
                    Text(
                        liveText,
                        Modifier.padding(top = 4.dp),
                        fontSize = 13.sp,
                        color = text,
                    )
                }
            }
        }
    }
}

/** Running context-usage readout above the chat input. */
@Composable
internal fun ChatContextGauge(
    usedTokens: Int?,
    maxTokens: Int?,
    textSecondary: Color,
    warning: Color,
    error: Color,
) {
    val text: String
    val color: Color
    if (usedTokens == null) {
        text = "Context: —"
        color = textSecondary
    } else if (maxTokens == null || maxTokens <= 0) {
        text = "Context: " + fmtTokens(usedTokens) + " · limit unknown"
        color = textSecondary
    } else {
        val pct = (usedTokens * 100.0 / maxTokens).toInt()
        text = "Context: " + fmtTokens(usedTokens) + " / " + fmtTokens(maxTokens) + " (" + pct + "%)"
        color = when {
            pct >= 95 -> error
            pct >= 80 -> warning
            else -> textSecondary
        }
    }
    Text(
        text,
        fontSize = 10.sp,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/** 12345 -> "12.3k"; 500 -> "500"; 1000000 -> "1000.0k". */
private fun fmtTokens(n: Int): String =
    if (n >= 1000) String.format("%.1fk", n / 1000.0) else n.toString()
