package com.codespace.ide.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.data.SecureTokenStore
import com.codespace.ide.data.SessionHandoffManager
import com.codespace.ide.diagnostics.SyncStatusMonitor
import com.codespace.ide.project.CloudBackupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * P16-E: Cloud Backup Panel
 * Lists backups for the current project, allows backup-now and restore.
 * Opened as a full-screen Dialog from the project menu.
 */
@Composable
fun CloudBackupPanel(
    projectId: String,
    backendUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val tokenStore = remember { SecureTokenStore(context) }

    var backups     by remember { mutableStateOf<List<CloudBackupManager.BackupEntry>>(emptyList()) }
    var loading     by remember { mutableStateOf(false) }
    var actionMsg   by remember { mutableStateOf<String?>(null) }
    var isError     by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }   // backup id awaiting confirm

    val authToken = tokenStore.lastAccessToken ?: ""
    val IconBlue  = Color(0xFF007ACC)
    val BgColor   = Color(0xFF1E1E1E)
    val CardBg    = Color(0xFF252526)
    val TextColor = Color(0xFFD4D4D4)
    val MutedColor= Color(0xFF888888)
    val ErrorColor= Color(0xFFCC0000)
    val GreenColor= Color(0xFF4CAF50)

    fun loadBackups() {
        scope.launch {
            loading = true; actionMsg = null
            val result = CloudBackupManager.listBackups(backendUrl, authToken)
            result.onSuccess { backups = it; actionMsg = null }
                  .onFailure { actionMsg = "Failed to load backups: ${it.message}"; isError = true }
            loading = false
        }
    }

    fun doBackup() {
        scope.launch {
            loading = true
            actionMsg = "Backing up..."
            SyncStatusMonitor.setSyncing("Backing up $projectId")
            val result = CloudBackupManager.backupProject(context, projectId, backendUrl, authToken)
            result.onSuccess {
                actionMsg = "Backup complete (ID: ${it.take(8)})"
                isError = false
                SyncStatusMonitor.setSuccess("Backup complete")
                loadBackups()
            }.onFailure {
                actionMsg = "Backup failed: ${it.message}"
                isError = true
                SyncStatusMonitor.setError("Backup failed")
            }
            loading = false
        }
    }

    fun doRestore(backupId: String) {
        scope.launch {
            loading = true
            actionMsg = "Restoring..."
            SyncStatusMonitor.setSyncing("Restoring backup")
            val result = CloudBackupManager.restoreProject(context, backupId, backendUrl, authToken)
            result.onSuccess {
                actionMsg = "Restore complete — restart the IDE to open the project."
                isError = false
                SyncStatusMonitor.setSuccess("Restore complete")
            }.onFailure {
                actionMsg = "Restore failed: ${it.message}"
                isError = true
                SyncStatusMonitor.setError("Restore failed")
            }
            loading = false
        }
    }

    fun doSyncSession() {
        scope.launch {
            loading = true; actionMsg = "Syncing session..."
            SyncStatusMonitor.setSyncing("Session sync")
            val result = SessionHandoffManager.pushSessionToCloud(context, projectId, backendUrl, authToken)
            result.onSuccess {
                actionMsg = "Session pushed to cloud."
                isError = false
                SyncStatusMonitor.setSuccess("Session synced")
            }.onFailure {
                actionMsg = "Session sync failed: ${it.message}"
                isError = true
                SyncStatusMonitor.setError("Session sync failed")
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadBackups() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = BgColor, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(Modifier.fillMaxSize().padding(0.dp)) {

                // Header
                Row(
                    Modifier.fillMaxWidth().background(CardBg).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CloudUpload, null, tint = IconBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cloud Backup", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextColor, modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = IconBlue)
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, null, tint = MutedColor, modifier = Modifier.size(16.dp))
                    }
                }

                // Action buttons
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::doBackup,
                        enabled = !loading && authToken.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = IconBlue),
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Backup Now", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = ::doSyncSession,
                        enabled = !loading && authToken.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(14.dp), tint = IconBlue)
                        Spacer(Modifier.width(4.dp))
                        Text("Sync Session", fontSize = 12.sp, color = IconBlue)
                    }
                }

                // Status message
                actionMsg?.let { msg ->
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = if (isError) ErrorColor else GreenColor,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                HorizontalDivider(color = Color(0xFF333333))

                // Backup list
                if (backups.isEmpty() && !loading) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No backups yet", fontSize = 13.sp, color = MutedColor)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                        items(backups, key = { it.id }) { entry ->
                            BackupEntryRow(
                                entry = entry,
                                onRestore = { confirmRestore = entry.id },
                                textColor = TextColor,
                                mutedColor = MutedColor,
                                iconBlue = IconBlue,
                            )
                            HorizontalDivider(color = Color(0xFF2A2A2A))
                        }
                    }
                }
            }
        }
    }

    // Restore confirmation dialog
    confirmRestore?.let { backupId ->
        key(backupId) {
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore Backup?") },
            text = { Text("This will overwrite the current project files. Continue?") },
            confirmButton = {
                TextButton(onClick = { confirmRestore = null; doRestore(backupId) }) {
                    Text("Restore", color = ErrorColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("Cancel") }
            },
        )
        } // key
    }
}

@Composable
private fun BackupEntryRow(
    entry: CloudBackupManager.BackupEntry,
    onRestore: () -> Unit,
    textColor: Color,
    mutedColor: Color,
    iconBlue: Color,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Archive, null, tint = iconBlue, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name.ifBlank { entry.id.take(12) },
                fontSize = 13.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sizeLabel = when {
                entry.sizeBytes < 1024 -> "${entry.sizeBytes}B"
                entry.sizeBytes < 1024 * 1024 -> "${entry.sizeBytes / 1024}KB"
                else -> String.format("%.1fMB", entry.sizeBytes / (1024.0 * 1024.0))
            }
            Text("$sizeLabel  •  ${entry.createdAt.take(16)}", fontSize = 10.sp, color = mutedColor)
        }
        IconButton(onClick = onRestore, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Restore, "Restore", tint = iconBlue, modifier = Modifier.size(18.dp))
        }
    }
}
