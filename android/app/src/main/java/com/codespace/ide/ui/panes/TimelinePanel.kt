package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.terminal.ProotInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TlBgColor   = Color(0xFF1E1E1E)
private val TlHeaderBg  = Color(0xFF252526)
private val TlMuted     = Color(0xFF858585)
private val TlText      = Color(0xFFD4D4D4)
private val TlDivider   = Color(0xFF2D2D30)
private val TlIcon      = Color(0xFF569CD6)

private data class TimelineEntry(
    val shortHash: String,
    val author: String,
    val relativeDate: String,
    val message: String,
)

/**
 * P42: Timeline panel — shows git log for the currently active file.
 * Rendered as a nested section inside ExplorerPane (not a top-level panel).
 */
@Composable
fun TimelinePanel(
    filePath: String,
    projectDir: File?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<TimelineEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var isGitRepo by remember { mutableStateOf(false) }

    LaunchedEffect(filePath, projectDir) {
        if (filePath.isBlank() || projectDir == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        withContext(Dispatchers.IO) {
            if (!File(projectDir, ".git").exists()) {
                isGitRepo = false
                entries = emptyList()
                loading = false
                return@withContext
            }
            isGitRepo = true
            val guestPath = ProotInstaller.hostToGuestPath(context, projectDir.absolutePath) ?: ""
            val relPath = try {
                File(filePath).relativeTo(projectDir).path
            } catch (_: Exception) {
                filePath.substringAfterLast("/")
            }
            // P-SCM-10: Use GitCommandExecutor for git log (centralized safe.directory)
            val result = com.codespace.ide.scm.GitCommandExecutor.run(
                context, listOf("log", "--follow", "--format=%H|%an|%ar|%s", "-50", "--", relPath),
                guestPath, timeoutSeconds = 15L
            )
            entries = if (result is com.codespace.ide.scm.GitResult.Err) {
                emptyList()
            } else {
                (result as com.codespace.ide.scm.GitResult.Ok).lines.mapNotNull { line ->
                    val parts = line.split("|", limit = 4)
                    if (parts.size < 4) return@mapNotNull null
                    TimelineEntry(
                        shortHash = parts[0].take(7),
                        author = parts[1].trim(),
                        relativeDate = parts[2].trim(),
                        message = parts[3].trim(),
                    )
                }
            }
            loading = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = TlIcon)
            }
        } else if (!isGitRepo) {
            Text(
                "No timeline available.",
                fontSize = 11.sp, color = TlMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else if (filePath.isBlank()) {
            Text(
                "Open a file to see its timeline.",
                fontSize = 11.sp, color = TlMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else if (entries.isEmpty()) {
            Text(
                "No commits for ${'$'}{filePath.substringAfterLast('/')}.",
                fontSize = 11.sp, color = TlMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(entries) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            entry.shortHash,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TlIcon,
                            modifier = Modifier.width(56.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.message,
                                fontSize = 11.sp,
                                color = TlText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${'$'}{entry.author} • ${'$'}{entry.relativeDate}",
                                fontSize = 9.sp,
                                color = TlMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = TlDivider, thickness = 0.5.dp)
                }
            }
        }
    }
}
