package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R8-QUEUE (VS Code chat queue parity): while a reply is streaming, tapping
 * send queues the message instead of dropping it. This chip shows the queued
 * text with a cancel (×) affordance; it auto-sends when the current turn ends.
 */
@Composable
internal fun ChatQueueBar(
    queuedText: String,
    onCancel: () -> Unit,
    colors: ChatPanelColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(colors.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏳", fontSize = 11.sp)
        Text(
            "Queued: $queuedText",
            fontSize = 11.sp,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "×",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary,
            modifier = Modifier
                .clickable { onCancel() }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
