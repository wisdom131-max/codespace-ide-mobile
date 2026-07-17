package com.codespace.ide.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
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
import com.codespace.ide.project.EnvironmentProfiles
import com.codespace.ide.project.ToolchainManager
import kotlinx.coroutines.launch

/**
 * Phase 12-I — Toolchain Panel
 *
 * Bottom panel tab showing detected development tools with health status.
 * Allows one-tap re-scan and profile switching.
 */
@Composable
fun ToolchainPanel(
    modifier: Modifier = Modifier,
    onRunInstall: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val report by ToolchainManager.report.collectAsState()
    val scanning by ToolchainManager.scanning.collectAsState()
    val activeProfile by EnvironmentProfiles.activeProfile.collectAsState()

    // Auto-scan on first composition if no report yet
    LaunchedEffect(Unit) {
        if (report == null && !scanning) {
            ToolchainManager.scan(context)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header bar ─────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E2E))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "TOOLCHAIN",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF9EA3B0),
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )

            // Profile chip
            Text(
                activeProfile.displayName,
                fontSize = 10.sp,
                color = Color(0xFF569CD6),
                modifier = Modifier.padding(end = 12.dp),
            )

            // Scan button
            IconButton(
                onClick = { scope.launch { ToolchainManager.scan(context) } },
                enabled = !scanning,
                modifier = Modifier.size(28.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF569CD6),
                    )
                } else {
                    Icon(Icons.Default.Refresh, "Scan", tint = Color(0xFF9EA3B0), modifier = Modifier.size(16.dp))
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        if (report == null && !scanning) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tap refresh to scan toolchain", fontSize = 12.sp, color = Color(0xFF6B7280))
            }
            return@Column
        }

        if (scanning && report == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF569CD6), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Scanning toolchain...", fontSize = 12.sp, color = Color(0xFF9EA3B0))
                }
            }
            return@Column
        }

        val r = report ?: return@Column

        // ── Overall health banner ───────────────────────────────────────────
        val missingRequired = EnvironmentProfiles.missingRequired(r)
        val bannerColor = if (missingRequired.isEmpty()) Color(0xFF1A3A1A) else Color(0xFF3A1A1A)
        val bannerText = if (missingRequired.isEmpty())
            "Environment ready for ${activeProfile.displayName}"
        else
            "Missing: ${missingRequired.joinToString(", ") { it.displayName }}"

        Row(
            Modifier
                .fillMaxWidth()
                .background(bannerColor)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (missingRequired.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                tint = if (missingRequired.isEmpty()) Color(0xFF4CAF50) else Color(0xFFFF9800),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(bannerText, fontSize = 11.sp, color = Color(0xFFCCCCCC))
        }

        HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 1.dp)

        // ── Tool list ───────────────────────────────────────────────────────
        LazyColumn(Modifier.fillMaxSize()) {
            items(r.tools) { tool ->
                ToolRow(
                    tool = tool,
                    isRequired = tool.id in activeProfile.requiredTools,
                    onRunInstall = onRunInstall,
                )
                HorizontalDivider(color = Color(0xFF2D2D3F), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun ToolRow(
    tool: ToolchainManager.ToolStatus,
    isRequired: Boolean,
    onRunInstall: ((String) -> Unit)? = null,
) {
    val (icon, tint) = when (tool.health) {
        ToolchainManager.ToolHealth.OK      -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        ToolchainManager.ToolHealth.MISSING -> Icons.Default.Error to Color(0xFFEF5350)
        ToolchainManager.ToolHealth.BROKEN  -> Icons.Default.Warning to Color(0xFFFF9800)
        ToolchainManager.ToolHealth.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline to Color(0xFF9EA3B0)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.displayName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFD4D4D4),
                )
                if (isRequired) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "required",
                        fontSize = 9.sp,
                        color = Color(0xFF569CD6),
                        modifier = Modifier
                            .background(Color(0xFF1A2A3A), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            if (tool.version != null) {
                Text(tool.version, fontSize = 10.sp, color = Color(0xFF6B7280), fontFamily = FontFamily.Monospace)
            }
            if (tool.note != null) {
                Text(tool.note, fontSize = 10.sp, color = Color(0xFF9EA3B0))
            }
        }

        if (tool.health == ToolchainManager.ToolHealth.MISSING && tool.installCmd != null && onRunInstall != null) {
            TextButton(
                onClick = { onRunInstall(tool.installCmd) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Icon(Icons.Default.Download, null, tint = Color(0xFF569CD6), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text("Install", fontSize = 10.sp, color = Color(0xFF569CD6))
            }
        } else {
            tool.path?.let {
                Text(
                    it.substringAfterLast("/"),
                    fontSize = 9.sp,
                    color = Color(0xFF4B5563),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
