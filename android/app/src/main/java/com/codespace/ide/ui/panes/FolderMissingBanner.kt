package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Banner shown when a project's real folder was deleted/moved externally.
 * Takes priority over individual component errors (Explorer empty tree,
 * Git "not a repo", Terminal at /) to give one clear explanation.
 *
 * Extracted to its own file to keep ProjectShellScreen under the JVM 64KB
 * bytecode limit.
 */
@Composable
fun FolderMissingBanner(
    bgColor: Color,
    textColor: Color,
    mutedColor: Color,
    accentColor: Color,
    onOpenFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.WarningAmber,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This project's folder can't be found",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Was it moved or deleted? Open a new folder to re-bind this project.",
            fontSize = 12.sp,
            color = mutedColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onOpenFolder,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Open Folder", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
