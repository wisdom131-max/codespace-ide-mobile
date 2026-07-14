package com.codespace.ide.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.project.DownloadCenter

/**
 * Phase 13-A — Download Center Panel
 *
 * Bottom panel tab showing all active and completed downloads.
 * Driven entirely by DownloadCenter.downloads StateFlow.
 */
@Composable
fun DownloadCenterPanel(modifier: Modifier = Modifier) {
    val downloads by DownloadCenter.downloads.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ──────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E2E))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DOWNLOADS",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9EA3B0),
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            val activeCount = downloads.count { it.isActive }
            if (activeCount > 0) {
                Text(
                    "$activeCount active",
                    fontSize = 10.sp,
                    color = Color(0xFF4EC9B0),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                "${downloads.size} total",
                fontSize = 10.sp,
                color = Color(0xFF6B7280),
            )
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null, tint = Color(0xFF4B5563), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No downloads yet", fontSize = 12.sp, color = Color(0xFF6B7280))
                    Text("Downloads initiated by the IDE appear here", fontSize = 10.sp, color = Color(0xFF4B5563))
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(downloads.sortedByDescending { it.startedAt }, key = { it.id }) { entry ->
                DownloadEntryRow(entry)
                HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun DownloadEntryRow(entry: DownloadCenter.DownloadEntry) {
    val stateColor = when (entry.state) {
        DownloadCenter.DownloadState.COMPLETED  -> Color(0xFF4EC9B0)
        DownloadCenter.DownloadState.FAILED     -> Color(0xFFCD3131)
        DownloadCenter.DownloadState.CANCELLED  -> Color(0xFF808080)
        DownloadCenter.DownloadState.DOWNLOADING -> Color(0xFF569CD6)
        DownloadCenter.DownloadState.QUEUED     -> Color(0xFFDCDCAA)
    }
    val stateIcon = when (entry.state) {
        DownloadCenter.DownloadState.COMPLETED  -> Icons.Default.CheckCircle
        DownloadCenter.DownloadState.FAILED     -> Icons.Default.Error
        DownloadCenter.DownloadState.CANCELLED  -> Icons.Default.Cancel
        DownloadCenter.DownloadState.DOWNLOADING -> Icons.Default.Download
        DownloadCenter.DownloadState.QUEUED     -> Icons.Default.HourglassEmpty
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A2E))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(stateIcon, null, tint = stateColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                entry.name,
                fontSize = 12.sp,
                color = Color(0xFFD4D4D4),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                entry.state.name,
                fontSize = 10.sp,
                color = stateColor,
            )
        }

        if (entry.isActive && entry.totalBytes > 0) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { entry.progressFraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Color(0xFF569CD6),
                trackColor = Color(0xFF2D2D3F),
            )
            Spacer(Modifier.height(2.dp))
            val dlMb = entry.downloadedBytes / (1024f * 1024f)
            val totalMb = entry.totalBytes / (1024f * 1024f)
            Text(
                "%.1f MB / %.1f MB".format(dlMb, totalMb),
                fontSize = 10.sp,
                color = Color(0xFF6B7280),
            )
        }

        if (entry.state == DownloadCenter.DownloadState.FAILED && entry.errorMessage != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                entry.errorMessage,
                fontSize = 10.sp,
                color = Color(0xFFCD3131),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (entry.state == DownloadCenter.DownloadState.COMPLETED) {
            Spacer(Modifier.height(2.dp))
            val destShort = entry.destPath.substringAfterLast("/")
            Text(
                destShort,
                fontSize = 10.sp,
                color = Color(0xFF6B7280),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
