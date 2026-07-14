package com.codespace.ide.ui.panels

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.project.BuildArtifactManager
import kotlinx.coroutines.launch

/**
 * Phase 12-L — Artifact Panel
 *
 * Bottom panel tab: lists APK/AAB build artifacts with share/install/delete actions.
 */
@Composable
fun ArtifactPanel(
    projectPath: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val artifacts by BuildArtifactManager.artifacts.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<BuildArtifactManager.Artifact?>(null) }

    // Scan on first load
    LaunchedEffect(projectPath) {
        scanning = true
        BuildArtifactManager.scan(projectPath)
        scanning = false
    }

    // Delete confirmation dialog
    deleteTarget?.let { artifact ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Artifact", fontSize = 14.sp) },
            text = { Text("Delete ${artifact.name}?", fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { BuildArtifactManager.delete(artifact) }
                    deleteTarget = null
                }) { Text("Delete", color = Color(0xFFEF5350)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ─────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E2E))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ARTIFACTS",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9EA3B0),
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${artifacts.size} files",
                fontSize = 10.sp,
                color = Color(0xFF4B5563),
                modifier = Modifier.padding(end = 8.dp),
            )
            IconButton(
                onClick = {
                    scope.launch {
                        scanning = true
                        BuildArtifactManager.scan(projectPath)
                        scanning = false
                    }
                },
                enabled = !scanning,
                modifier = Modifier.size(28.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF569CD6))
                } else {
                    Icon(Icons.Default.Refresh, "Scan", tint = Color(0xFF9EA3B0), modifier = Modifier.size(16.dp))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        if (artifacts.isEmpty() && !scanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Android, null, tint = Color(0xFF4B5563), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No APK/AAB artifacts found", fontSize = 12.sp, color = Color(0xFF4B5563))
                    Text("Build the project first", fontSize = 10.sp, color = Color(0xFF374151))
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(artifacts, key = { it.path }) { artifact ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Android,
                        null,
                        tint = if (artifact.type == BuildArtifactManager.ArtifactType.APK)
                            Color(0xFF4CAF50) else Color(0xFF569CD6),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            artifact.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFD4D4D4),
                            fontFamily = FontFamily.Monospace,
                        )
                        Row {
                            Surface(
                                shape = RoundedCornerShape(3.dp),
                                color = if (artifact.type == BuildArtifactManager.ArtifactType.APK)
                                    Color(0xFF1A3A1A) else Color(0xFF1A2A3A),
                            ) {
                                Text(
                                    artifact.type.name,
                                    fontSize = 9.sp,
                                    color = if (artifact.type == BuildArtifactManager.ArtifactType.APK)
                                        Color(0xFF4CAF50) else Color(0xFF569CD6),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(artifact.sizeLabel, fontSize = 10.sp, color = Color(0xFF6B7280))
                            Text(" \u00b7 ", fontSize = 10.sp, color = Color(0xFF4B5563))
                            Text(artifact.dateLabel, fontSize = 10.sp, color = Color(0xFF6B7280))
                        }
                    }

                    // Actions
                    Row {
                        // Install (APK only)
                        if (artifact.type == BuildArtifactManager.ArtifactType.APK) {
                            IconButton(
                                onClick = {
                                    val intent = BuildArtifactManager.installIntent(context, artifact)
                                    if (intent != null) context.startActivity(intent)
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.SystemUpdateAlt, "Install", tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            }
                        }
                        // Share
                        IconButton(
                            onClick = {
                                val intent = BuildArtifactManager.shareIntent(context, artifact)
                                context.startActivity(Intent.createChooser(intent, "Share ${artifact.name}"))
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Share, "Share", tint = Color(0xFF9EA3B0), modifier = Modifier.size(16.dp))
                        }
                        // Delete
                        IconButton(
                            onClick = { deleteTarget = artifact },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFF6B7280), modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 0.5.dp)
            }
        }
    }
}
