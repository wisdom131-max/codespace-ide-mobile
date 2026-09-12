package com.codespace.ide.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R4-CHAT-PARITY: typed chat-entry renderers — extracted to their own file
 * (64KB rule; the panel calls each with a single line).
 *
 *  - ChatToolChip:  compact "tools used" transcript chip (monospace, accent border).
 *  - ChatErrorBubble: red-accented error entry (real error part, not a fake
 *    assistant reply with an "Error:" prefix).
 */

@Composable
internal fun ChatToolChip(
    text: String,
    colors: ChatPanelColors,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.divider),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Build, null,
                    tint = colors.accent,
                    modifier = Modifier.size(11.dp).padding(end = 1.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun ChatErrorBubble(
    text: String,
    colors: ChatPanelColors,
) {
    // CUSTOM-ENDPOINT-FIX (c): the transport tucks the raw vendor JSON behind a
    // RAW_RESPONSE block — show the clean one-liner, expand for the raw body.
    val rawStart = text.indexOf("\nRAW_RESPONSE_BEGIN\n")
    val cleanText = if (rawStart >= 0) text.substring(0, rawStart).trim() else text
    val rawText = if (rawStart >= 0) {
        val rest = text.substring(rawStart + "\nRAW_RESPONSE_BEGIN\n".length)
        val endIdx = rest.indexOf("\nRAW_RESPONSE_END")
        if (endIdx >= 0) rest.substring(0, endIdx) else rest
    } else ""
    var showRaw by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.assistantBubble,
            border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFEF4444)),
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Default.ErrorOutline, "Error",
                    tint = androidx.compose.ui.graphics.Color(0xFFEF4444),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        cleanText,
                        fontSize = 12.sp,
                        color = colors.text,
                    )
                    if (rawText.isNotEmpty()) {
                        Text(
                            if (showRaw) "Hide raw response" else "Show raw response",
                            fontSize = 10.sp,
                            color = colors.accent,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { showRaw = !showRaw },
                        )
                        if (showRaw) {
                            Text(
                                rawText,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * R7-FEEDBACK (VS Code feedback part parity): thumbs up/down on assistant
 * replies, persisted on the message (local-only — nothing leaves the device).
 * Tapping the active thumb again clears the rating (toggle).
 */
@Composable
internal fun ChatFeedbackRow(
    rating: String?,
    onRate: (String) -> Unit,
    colors: ChatPanelColors,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.clickable { onRate("up") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ThumbUp,
                contentDescription = "Helpful",
                tint = if (rating == "up") colors.accent else colors.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
        Row(
            Modifier.clickable { onRate("down") },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ThumbDown,
                contentDescription = "Not helpful",
                tint = if (rating == "down") Color(0xFFEF4444) else colors.textSecondary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
