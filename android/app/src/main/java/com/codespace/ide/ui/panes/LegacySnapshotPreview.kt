package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Legacy-snapshot palette (mirrors TimelinePanel's Tl colors; kept local so this
// shared section works from both TimelinePanel and the Explorer Local History dialog).
private val LgBgColor   = Color(0xFF252526)
private val LgMuted     = Color(0xFF858585)
private val LgText      = Color(0xFFD4D4D4)
private val LgDivider   = Color(0xFF2D2D30)
private val LgIcon      = Color(0xFF9B9B9B)

/**
 * LEGACY SNAPSHOT SECTION (VERSIONHISTORY_NAMING_PLAN v3, Wisdom GO 2026-09-19):
 * pre-v2 name-only snapshots have UNKNOWABLE ownership (a same-named file may
 * have been renamed, moved or deleted since the snapshot was written), so they
 * are VIEW-ONLY for every file, root files included: tap Preview to see the
 * content in a read-only dialog; there is NO Restore button on legacy entries,
 * ever. Shared by TimelinePanel and the Explorer Local History dialog.
 */
@Composable
fun LegacySnapshotSection(
    snapshots: List<File>,
    modifier: Modifier = Modifier,
) {
    if (snapshots.isEmpty()) return
    var previewFile by remember { mutableStateOf<File?>(null) }
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "Legacy snapshots (pre-v2, owner unknown)",
            fontSize = 10.sp, color = LgIcon,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        snapshots.forEach { snap ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        snap.name,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LgText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (snap.length() / 1024).toString() + " KB - " +
                            SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(snap.lastModified())),
                        fontSize = 9.sp,
                        color = LgMuted,
                        maxLines = 1,
                    )
                }
                Text(
                    "Preview",
                    fontSize = 10.sp,
                    color = LgIcon,
                    modifier = Modifier
                        .background(LgBgColor, shape = RoundedCornerShape(8.dp))
                        .clickable { previewFile = snap }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            HorizontalDivider(color = LgDivider, thickness = 0.5.dp)
        }
    }
    LegacySnapshotPreviewDialog(previewFile, onDismiss = { previewFile = null })
}

/**
 * Read-only content preview for one legacy snapshot. Content is read on the IO
 * dispatcher; preview is the ONLY action — restoring legacy content into any
 * same-named file is exactly the wrong-file data loss this whole design forbids.
 */
@Composable
private fun LegacySnapshotPreviewDialog(snap: File?, onDismiss: () -> Unit) {
    if (snap == null) return
    var content by remember(snap.absolutePath) { mutableStateOf("Loading...") }
    LaunchedEffect(snap.absolutePath) {
        withContext(Dispatchers.IO) {
            content = try {
                snap.readText().take(8000)
            } catch (e: Exception) {
                "(unreadable: " + (e.message ?: "error") + ")"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Legacy snapshot - " + snap.name, fontSize = 13.sp) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Preview only. Pre-v2 snapshots have unknown ownership and are never restorable.",
                    fontSize = 10.sp,
                    color = LgMuted,
                )
                Text(
                    content,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LgText,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = RoundedCornerShape(12.dp),
    )
}
