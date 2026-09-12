package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R7-FIND-IN-CHAT (VS Code find-in-chat parity): filters the CURRENT
 * transcript to messages containing the query (case-insensitive), with a live
 * match count. v1 is filter-based; in-message highlight would require rework
 * of the markdown pipeline and is deliberately deferred.
 */
@Composable
internal fun ChatFindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    onClose: () -> Unit,
    colors: ChatPanelColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .background(colors.inputBg, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                if (query.isEmpty()) {
                    Text("Find in chat", fontSize = 12.sp, color = colors.textSecondary)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                )
            }
        }
        Text(
            if (query.isBlank()) "" else "$matchCount",
            fontSize = 10.sp,
            color = colors.textSecondary,
        )
        Text(
            "×",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary,
            modifier = Modifier
                .clickable { onClose() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
