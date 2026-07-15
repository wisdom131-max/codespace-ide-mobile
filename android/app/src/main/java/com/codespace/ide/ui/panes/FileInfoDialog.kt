package com.codespace.ide.ui.panes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FileInfoDialog — universal file information dialog.
 * Uses FileDetector to show file metadata and offers actions:
 * Open as Text, Open as Hex, Open as Strings, Open as Binary.
 *
 * Phase 21 Step 3 — the "View File Information" action for any file.
 */

@Composable
fun FileInfoDialog(
    file: File,
    onDismiss: () -> Unit,
    onOpenAsText: (File) -> Unit = {},
    onOpenAsHex: (File) -> Unit = {},
    onOpenAsStrings: (File) -> Unit = {},
    onOpenAsBinary: (File) -> Unit = {},
) {
    val info = remember(file) { FileDetector.detect(file) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = Color(0xFF1E1E1E),
            tonalElevation = 8.dp
        ) {
            Column(
                Modifier.width(320.dp).padding(16.dp).verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = Color(0xFF569CD6), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("File Information", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF333333))

                // File info rows
                InfoRow("Name", info.fileName)
                InfoRow("Size", FileDetector.formatFileSize(info.fileSize) + " (${info.fileSize} bytes)")
                InfoRow("Extension", if (info.extension.isNotBlank()) ".${info.extension}" else "(none)")
                InfoRow("Format", info.detectedFormat.name)
                InfoRow("MIME Type", info.mimeType)
                InfoRow("Encoding", info.encoding)
                InfoRow("Modified", dateFormat.format(Date(info.lastModified)))
                InfoRow("Confidence", info.detectionConfidence.name)
                InfoRow("Magic Bytes", if (info.magicBytesHex.isNotBlank()) info.magicBytesHex.take(32) else "(empty file)")

                Spacer(Modifier.height(8.dp))

                // Category badges
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (info.isText) CategoryChip("Text")
                    if (info.isBinary) CategoryChip("Binary")
                    if (info.isArchive) CategoryChip("Archive")
                    if (info.isImage) CategoryChip("Image")
                    if (info.isAudio) CategoryChip("Audio")
                    if (info.isVideo) CategoryChip("Video")
                    if (info.isDocument) CategoryChip("Document")
                    if (info.isDatabase) CategoryChip("Database")
                    if (info.isCode) CategoryChip("Code")
                    if (info.isFont) CategoryChip("Font")
                    if (info.isCertificate) CategoryChip("Cert")
                    if (info.isApk) CategoryChip("APK")
                    if (info.isElf) CategoryChip("ELF")
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF333333))

                // Actions
                Text("Open As", color = Color(0xFF858585), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Text", Icons.Default.TextFields, Color(0xFF4EC9B0)) { onOpenAsText(file) }
                    ActionButton("Hex", Icons.Default.DataObject, Color(0xFFCE9178)) { onOpenAsHex(file) }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Strings", Icons.Default.TextFields, Color(0xFFDCDCAA)) { onOpenAsStrings(file) }
                    ActionButton("Binary", Icons.Default.BrokenImage, Color(0xFF569CD6)) { onOpenAsBinary(file) }
                }

                Spacer(Modifier.height(16.dp))

                // Close button
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color(0xFF569CD6))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color(0xFF858585), fontSize = 12.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Color(0xFFCCCCCC), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CategoryChip(label: String) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        color = Color(0xFF007ACC)
    ) {
        Text(label, color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        color = Color(0xFF2D2D2D),
        onClick = onClick
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = color, fontSize = 11.sp)
        }
    }
}
