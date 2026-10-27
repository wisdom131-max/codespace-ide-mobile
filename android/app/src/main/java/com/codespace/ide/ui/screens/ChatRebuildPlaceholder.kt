package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * REMOVAL 2/7 (advisor ruling 2026-10-27): the old chat panel is archived to
 * vn-code-archive (@ chat-archive-2026-10-27) and fully removed while a new
 * chat/provider layer is designed (docs/rebuild-plan.md v2). This placeholder
 * keeps the panel surface, its toggle and the layout intact. It holds NO chat
 * state, reads no prefs, and cannot crash on restored sessions: tab restore,
 * saved panel state and the panel flag only ever show this composable.
 */
@Composable
fun ChatRebuildPlaceholder() {
    Box(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Chat is being rebuilt",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp
            )
            Text(
                "The new chat arrives in an upcoming build.\nYour saved conversations and keys are kept.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
