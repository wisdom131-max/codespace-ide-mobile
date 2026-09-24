package com.codespace.ide.ui.panes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TB03 (P1): the single dirty-close gate for ALL user-initiated tab closes
 * (strip X, context Close / Close Others / Close All). Previously only
 * Android's BackHandler warned about dirty tabs — every explicit close
 * discarded the only live buffer silently, and with G01 (unverified disk
 * writes) that buffer could be the ONLY copy of the user's edits.
 *
 * Actions: Save & Close (typed — failed saves keep their tabs open),
 * Discard & Close (explicit), or Cancel via dismiss. Callers own the actual
 * close logic through closeEditorTabInternal — this dialog is only the gate.
 */
@Composable
internal fun DirtyCloseDialog(
    dirtyNames: List<String>,
    onSaveAndClose: () -> Unit,
    onDiscardAndClose: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved Changes") },
        text = {
            Column {
                Text(
                    "${dirtyNames.size} tab(s) have unsaved changes:",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                dirtyNames.take(5).forEach { name ->
                    Text("• $name", fontSize = 12.sp)
                }
                if (dirtyNames.size > 5) {
                    Text("…and ${dirtyNames.size - 5} more", fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Save and close, or close without saving?", fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = onSaveAndClose) { Text("Save & Close") }
        },
        dismissButton = {
            TextButton(onClick = onDiscardAndClose) { Text("Discard & Close") }
        },
    )
}
