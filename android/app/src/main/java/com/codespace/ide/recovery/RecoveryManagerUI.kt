package com.codespace.ide.recovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AutosaveUiItem(
    val file: File,
    val displayName: String,
    val formattedDate: String
)

@Composable
fun AutosaveRestoreDialog(
    projectDir: File,
    onRestoreFile: (filename: String, content: String) -> Unit,
    onDismiss: () -> Unit
) {
    // ALWAYS call remember() unconditionally at the top of a @Composable (Compose rules of hooks)
    val uiItems = remember(projectDir) {
        val files = RecoveryManager.listAutosaves(projectDir)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        files.map { file ->
            AutosaveUiItem(
                file = file,
                displayName = file.name.removeSuffix(".autosave"),
                formattedDate = sdf.format(Date(file.lastModified()))
            )
        }
    }
    
    val orientation = LocalConfiguration.current.orientation
    // ALWAYS use key(orientation) on AlertDialogs (Activity has configChanges=orientation)
    key(orientation) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color(0xFF007ACC)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Autosave Recovery",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E1E1E)
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Unsaved changes were found. Choose an autosave file to restore:",
                        color = Color(0xFF555555),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (uiItems.isEmpty()) {
                        Text(
                            text = "No autosave files found.",
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            // No remember() inside items{} or conditionals or loops
                            items(uiItems) { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val content = RecoveryManager.restoreAutosave(item.file)
                                            onRestoreFile(item.displayName, content)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = item.displayName,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF1E1E1E),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Saved: ${item.formattedDate}",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF007ACC),
                        contentColor = Color.White
                    )
                ) {
                    Text("Dismiss")
                }
            },
            containerColor = Color(0xFFFFFFFF)
        )
    }
}
