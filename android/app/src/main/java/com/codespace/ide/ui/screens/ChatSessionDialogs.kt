package com.codespace.ide.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CHAT SESSION DIALOGS (Round 1, Copilot-chat parity):
 *
 * Extracted from the chat panel per the 64KB bytecode rule — dialogs live in
 * their own file and are invoked with single-line calls from the panel.
 */

/** /rename & session-row edit: rename the current chat session. */
@Composable
internal fun SessionRenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        containerColor = Color(0xFF252526),
        title = {
            Text(
                "Rename session",
                color = Color(0xFFD4D4D4),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Session title", fontSize = 12.sp, color = Color(0xFF858585)) },
                textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFD4D4D4)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF007ACC),
                    unfocusedBorderColor = Color(0xFF444444),
                    focusedContainerColor = Color(0xFF1E1E1E),
                    unfocusedContainerColor = Color(0xFF1E1E1E),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            ) {
                Text("Rename", color = Color(0xFF007ACC), fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF858585), fontSize = 13.sp)
            }
        },
    )
}
