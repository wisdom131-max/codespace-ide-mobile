package com.codespace.ide.ui.panes

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration

/**
 * P14-E — Rename a terminal session tab.
 *
 * Long-press the session tab → this dialog → enter a new name → confirm.
 * Wrapped in key(orientation) for rotation safety (Activity has configChanges=orientation).
 *
 * @param currentName  The existing session label (pre-fills the field).
 * @param onDismiss    Called on Cancel or outside tap.
 * @param onRename     Called with the trimmed new name on Confirm (never called with blank).
 */
@Composable
fun TerminalSessionRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    val orientation = LocalConfiguration.current.orientation
    var name by remember(currentName) { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }

    key(orientation) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title   = { Text("Rename Session") },
            text    = {
                OutlinedTextField(
                    value       = name,
                    onValueChange = { name = it },
                    label       = { Text("Session name") },
                    singleLine  = true,
                    modifier    = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onRename(trimmed)
                        onDismiss()
                    },
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
